// vct_stt: C interface over whisper.cpp. Portable (Windows, Linux, macOS).

#include "vct_stt.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "whisper.h"

#ifdef _WIN32
// Without NOMINMAX, <windows.h> defines min and max macros that break std::min / std::max (MSVC C2589).
#  ifndef NOMINMAX
#    define NOMINMAX
#  endif
#  include <windows.h>
#endif

namespace {

constexpr int kSampleRate = 16000;
constexpr size_t kLogKeep = 16 * 1024;

// whisper.cpp and ggml log to stderr by default; keep the tail instead, to report load failures.
std::mutex g_log_mutex;
std::string g_log;
std::once_flag g_log_once;

void on_log(enum ggml_log_level, const char *text, void *) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log += text;
    if (g_log.size() > kLogKeep) g_log.erase(0, g_log.size() - kLogKeep);
}

size_t log_size() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return g_log.size();
}

std::string log_since(size_t mark) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return mark < g_log.size() ? g_log.substr(mark) : g_log.substr(g_log.size() > 2000 ? g_log.size() - 2000 : 0);
}

void copy_out(const std::string &s, char *buf, int32_t capacity) {
    if (!buf || capacity <= 0) return;
    size_t n = std::min(s.size(), static_cast<size_t>(capacity - 1));
    std::memcpy(buf, s.data(), n);
    buf[n] = '\0';
}

// The model path is UTF-8; on Windows plain fopen would read it in the ANSI code page.
FILE *open_utf8(const char *path) {
#ifdef _WIN32
    int n = MultiByteToWideChar(CP_UTF8, 0, path, -1, nullptr, 0);
    if (n <= 0) return nullptr;
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path, -1, w.data(), n);
    FILE *f = nullptr;
    return _wfopen_s(&f, w.c_str(), L"rb") == 0 ? f : nullptr;
#else
    return std::fopen(path, "rb");
#endif
}

size_t loader_read(void *ctx, void *out, size_t n) { return std::fread(out, 1, n, static_cast<FILE *>(ctx)); }
bool loader_eof(void *ctx) { return std::feof(static_cast<FILE *>(ctx)) != 0; }
void loader_close(void *ctx) { std::fclose(static_cast<FILE *>(ctx)); }

void append_json_string(std::string &out, const std::string &utf8) {
    static const char hex[] = "0123456789abcdef";
    out += '"';
    for (unsigned char c : utf8) {
        if (c == '"' || c == '\\') {
            out += '\\';
            out += static_cast<char>(c);
        } else if (c < 0x20) {
            out += "\\u00";
            out += hex[c >> 4];
            out += hex[c & 0xF];
        } else {
            out += static_cast<char>(c);
        }
    }
    out += '"';
}

void append_number(std::string &out, double v) {
    if (!std::isfinite(v)) {
        out += "null";
        return;
    }
    char buf[32];
    std::snprintf(buf, sizeof buf, "%.4f", v);
    out += buf;
}

std::vector<int> parse_languages(const char *list) {
    std::vector<int> ids;
    if (!list) return ids;
    std::stringstream ss(list);
    std::string code;
    while (std::getline(ss, code, ',')) {
        code.erase(0, code.find_first_not_of(" \t"));
        code.erase(code.find_last_not_of(" \t") + 1);
        if (code.empty()) continue;
        int id = whisper_lang_id(code.c_str());
        if (id >= 0 && std::find(ids.begin(), ids.end(), id) == ids.end()) ids.push_back(id);
    }
    return ids;
}

long long ms_since(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
}

}  // namespace

struct vct_whisper {
    whisper_context *ctx = nullptr;
    std::string result;
};

extern "C" {

VCT_STT_API int32_t vct_stt_api_version(void) { return VCT_STT_API_VERSION; }

VCT_STT_API int32_t vct_stt_system_info(char *buf, int32_t capacity) {
    copy_out(whisper_print_system_info(), buf, capacity);
    return 0;
}

VCT_STT_API int32_t vct_whisper_open(const char *model_path, int32_t use_gpu, vct_whisper **out, char *error,
                                     int32_t error_capacity) {
    if (!model_path || !out) return VCT_STT_E_INVALID_ARG;
    *out = nullptr;
    std::call_once(g_log_once, [] { whisper_log_set(on_log, nullptr); });

    FILE *f = open_utf8(model_path);
    if (!f) {
        copy_out(std::string("Cannot open the Whisper model file: ") + model_path, error, error_capacity);
        return VCT_STT_E_MODEL;
    }
    whisper_model_loader loader = {f, loader_read, loader_eof, loader_close};  // whisper.cpp closes it
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = use_gpu != 0;
    size_t mark = log_size();
    whisper_context *ctx = whisper_init_with_params(&loader, cp);
    if (!ctx) {
        copy_out("whisper.cpp could not load the model:\n" + log_since(mark), error, error_capacity);
        return VCT_STT_E_MODEL;
    }
    auto *w = new (std::nothrow) vct_whisper();
    if (!w) {
        whisper_free(ctx);
        return VCT_STT_E_MODEL;
    }
    w->ctx = ctx;
    *out = w;
    return 0;
}

VCT_STT_API int32_t vct_whisper_transcribe(vct_whisper *w, const float *pcm, int32_t n_samples,
                                           const char *languages, int32_t n_threads, int32_t *json_size) {
    if (!w || !pcm || n_samples < 0 || !json_size) return VCT_STT_E_INVALID_ARG;
    *json_size = 0;
    const int threads = std::max(1, n_threads);
    // whisper.cpp rejects input under 100 ms; pad very short clips to 200 ms of silence instead.
    std::vector<float> padded;
    if (n_samples < kSampleRate / 5) {
        padded.assign(pcm, pcm + n_samples);
        padded.resize(kSampleRate / 5, 0.0f);
        pcm = padded.data();
        n_samples = static_cast<int32_t>(padded.size());
    }

    std::vector<int> allowed = parse_languages(languages);
    const bool multilingual = whisper_is_multilingual(w->ctx) != 0;
    int lang = whisper_lang_id("en");
    double lang_prob = NAN;
    long long detect_ms = 0;
    if (!multilingual) {
        lang_prob = 1.0;  // English-only model
    } else if (allowed.size() == 1) {
        lang = allowed[0];
        lang_prob = 1.0;
    } else {
        // Detect, then choose among the allowed languages only: in noisy voice chat Whisper sometimes
        // picks an unrelated language, which ruins the transcription.
        auto t0 = std::chrono::steady_clock::now();
        if (whisper_pcm_to_mel(w->ctx, pcm, n_samples, threads) != 0) return VCT_STT_E_TRANSCRIBE;
        std::vector<float> probs(static_cast<size_t>(whisper_lang_max_id() + 1), 0.0f);
        int best = whisper_lang_auto_detect(w->ctx, 0, threads, probs.data());
        if (best < 0) return VCT_STT_E_TRANSCRIBE;
        if (allowed.empty()) {
            lang = best;
            lang_prob = probs[static_cast<size_t>(best)];
        } else {
            double total = 0;
            lang = allowed[0];
            for (int id : allowed) {
                total += probs[static_cast<size_t>(id)];
                if (probs[static_cast<size_t>(id)] > probs[static_cast<size_t>(lang)]) lang = id;
            }
            // Renormalized: how sure Whisper is among the languages the user expects.
            lang_prob = total > 0 ? probs[static_cast<size_t>(lang)] / total : NAN;
        }
        detect_ms = ms_since(t0);
    }
    const char *lang_code = whisper_lang_str(lang);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    p.n_threads = threads;
    p.language = lang_code;
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;      // utterances are independent; reusing context can loop hallucinations
    p.no_timestamps = true;   // not needed, and faster
    p.single_segment = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.suppress_blank = true;
    p.suppress_nst = true;    // drops "[Music]", "(applause)" style tokens
    p.beam_search.beam_size = 5;
    // Whisper writes Chinese in Traditional or Simplified characters at random; a prompt in
    // Simplified Chinese steers it to Simplified and to proper punctuation.
    if (std::strcmp(lang_code, "zh") == 0) p.initial_prompt = "以下是普通话的句子，使用简体中文。";

    auto t1 = std::chrono::steady_clock::now();
    if (whisper_full(w->ctx, p, pcm, n_samples) != 0) return VCT_STT_E_TRANSCRIBE;
    long long decode_ms = ms_since(t1);

    std::string text;
    double log_p_sum = 0;
    int tokens = 0;
    double no_speech = NAN;
    const whisper_token eot = whisper_token_eot(w->ctx);
    const int segments = whisper_full_n_segments(w->ctx);
    for (int s = 0; s < segments; s++) {
        text += whisper_full_get_segment_text(w->ctx, s);
        if (s == 0) no_speech = whisper_full_get_segment_no_speech_prob(w->ctx, 0);
        for (int t = 0, n = whisper_full_n_tokens(w->ctx, s); t < n; t++) {
            if (whisper_full_get_token_id(w->ctx, s, t) >= eot) continue;  // special and timestamp tokens
            float tp = whisper_full_get_token_p(w->ctx, s, t);
            log_p_sum += std::log(std::max(tp, 1e-6f));
            tokens++;
        }
    }
    // Geometric mean of token probabilities, i.e. exp(average log-probability).
    double confidence = tokens > 0 ? std::exp(log_p_sum / tokens) : NAN;

    std::string &j = w->result;
    j = "{\"text\":";
    append_json_string(j, text);
    j += ",\"language\":";
    append_json_string(j, lang_code);
    j += ",\"language_probability\":";
    append_number(j, lang_prob);
    j += ",\"confidence\":";
    append_number(j, confidence);
    j += ",\"no_speech_probability\":";
    append_number(j, no_speech);
    j += ",\"detect_ms\":" + std::to_string(detect_ms);
    j += ",\"decode_ms\":" + std::to_string(decode_ms) + "}";
    *json_size = static_cast<int32_t>(j.size() + 1);
    return 0;
}

VCT_STT_API int32_t vct_whisper_result(vct_whisper *w, char *json, int32_t capacity) {
    if (!w || !json) return VCT_STT_E_INVALID_ARG;
    if (capacity < static_cast<int32_t>(w->result.size() + 1)) return VCT_STT_E_BUFFER_TOO_SMALL;
    std::memcpy(json, w->result.c_str(), w->result.size() + 1);
    return 0;
}

VCT_STT_API void vct_whisper_close(vct_whisper *w) {
    if (!w) return;
    whisper_free(w->ctx);
    delete w;
}

}  // extern "C"
