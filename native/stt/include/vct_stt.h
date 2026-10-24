/*
 * vct_stt: speech-to-text for Voice Chat Translator, a thin C interface over whisper.cpp.
 *
 * Called from Java through FFM. whisper.cpp's own API passes large structs by value, whose layout
 * changes between versions; this wrapper keeps the Java side to plain pointers and integers.
 *
 * Results: 0 on success, a negative VCT_STT_E_* code on failure. Text is UTF-8.
 * Threads: one vct_whisper must not be used by two threads at once.
 */
#ifndef VCT_STT_H
#define VCT_STT_H

#include <stdint.h>

#if defined(_WIN32)
#  if defined(VCT_STT_BUILD)
#    define VCT_STT_API __declspec(dllexport)
#  else
#    define VCT_STT_API __declspec(dllimport)
#  endif
#else
#  define VCT_STT_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define VCT_STT_API_VERSION 1

#define VCT_STT_E_INVALID_ARG      (-1)
#define VCT_STT_E_MODEL            (-2) /* the model file is missing, unreadable or invalid */
#define VCT_STT_E_TRANSCRIBE       (-3)
#define VCT_STT_E_BUFFER_TOO_SMALL (-4)

typedef struct vct_whisper vct_whisper;

VCT_STT_API int32_t vct_stt_api_version(void);

/* whisper.cpp's build features, e.g. "CUDA : ARCHS = 890 | ..." (NUL-terminated, truncated to capacity). */
VCT_STT_API int32_t vct_stt_system_info(char *buf, int32_t capacity);

/*
 * Loads a ggml Whisper model (e.g. ggml-large-v3-turbo-q8_0.bin). `use_gpu` 0 forces the CPU.
 * On failure, `error` receives the reason, including whisper.cpp's log output.
 */
VCT_STT_API int32_t vct_whisper_open(const char *model_path, int32_t use_gpu, vct_whisper **out,
                                     char *error, int32_t error_capacity);

/*
 * Transcribes mono float PCM at 16 kHz.
 *   languages: comma-separated ISO 639-1 codes ("en,ja,zh"). One code forces that language; several
 *              restrict language detection to them; NULL or "" lets Whisper detect any language.
 *   n_threads: CPU threads (also used for parts of GPU inference).
 * On success `*json_size` receives the size of the result JSON including the NUL. Fetch it with
 * vct_whisper_result():
 *   {"text":"...","language":"ja","language_probability":0.97,"confidence":0.84,
 *    "no_speech_probability":0.01,"detect_ms":41,"decode_ms":310}
 */
VCT_STT_API int32_t vct_whisper_transcribe(vct_whisper *w, const float *pcm, int32_t n_samples,
                                           const char *languages, int32_t n_threads, int32_t *json_size);

/* Copies the last result JSON. Returns VCT_STT_E_BUFFER_TOO_SMALL if it does not fit. */
VCT_STT_API int32_t vct_whisper_result(vct_whisper *w, char *json, int32_t capacity);

VCT_STT_API void vct_whisper_close(vct_whisper *w);

#ifdef __cplusplus
}
#endif

#endif /* VCT_STT_H */
