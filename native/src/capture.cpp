// Capture streams: microphone, render-device loopback and process loopback.

#include <algorithm>
#include <condition_variable>
#include <cstring>
#include <future>
#include <mutex>
#include <vector>

#include "common.h"
// After common.h: avrt.h needs the Windows types from windows.h.
#include <avrt.h>

#if defined(__has_include)
#  if __has_include(<audioclientactivationparams.h>)
#    include <audioclientactivationparams.h>
#    define VCT_HAVE_ACTIVATION_PARAMS 1
#  endif
#endif

#ifndef VCT_HAVE_ACTIVATION_PARAMS
// Windows SDK 10.0.20348+ declares these in audioclientactivationparams.h; MinGW does not.
typedef enum AUDIOCLIENT_ACTIVATION_TYPE {
    AUDIOCLIENT_ACTIVATION_TYPE_DEFAULT = 0,
    AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK = 1
} AUDIOCLIENT_ACTIVATION_TYPE;

typedef enum PROCESS_LOOPBACK_MODE {
    PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE = 0,
    PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE = 1
} PROCESS_LOOPBACK_MODE;

typedef struct AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS {
    DWORD TargetProcessId;
    PROCESS_LOOPBACK_MODE ProcessLoopbackMode;
} AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS;

typedef struct AUDIOCLIENT_ACTIVATION_PARAMS {
    AUDIOCLIENT_ACTIVATION_TYPE ActivationType;
    union {
        AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS ProcessLoopbackParams;
    };
} AUDIOCLIENT_ACTIVATION_PARAMS;

#  define VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK L"VAD\\Process_Loopback"
#endif

namespace {

using vct::ComPtr;

constexpr REFERENCE_TIME kBufferDuration = 1'000'000;  // 100 ms, in 100 ns units
constexpr DWORD kWaitMs = 100;                         // poll even without events (loopback can be quiet)
constexpr DWORD kActivationTimeoutMs = 5000;
constexpr int kRingSeconds = 2;

// {94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90}
const IID kIidIAgileObject = {0x94EA2B94, 0xE9CC, 0x49E0, {0xC0, 0xFF, 0xEE, 0x64, 0xCA, 0x8F, 0x5B, 0x90}};

// Completion handler for ActivateAudioInterfaceAsync. Must be agile: Windows calls it on a worker thread.
class ActivationHandler final : public IActivateAudioInterfaceCompletionHandler {
public:
    ActivationHandler() : done_(CreateEventW(nullptr, TRUE, FALSE, nullptr)) {}

    HANDLE done() const { return done_; }

    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs_); }
    ULONG STDMETHODCALLTYPE Release() override {
        ULONG n = InterlockedDecrement(&refs_);
        if (n == 0) delete this;
        return n;
    }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void **ppv) override {
        if (!ppv) return E_POINTER;
        if (IsEqualIID(riid, IID_IUnknown) || IsEqualIID(riid, __uuidof(IActivateAudioInterfaceCompletionHandler))
                || IsEqualIID(riid, kIidIAgileObject)) {
            *ppv = static_cast<IActivateAudioInterfaceCompletionHandler *>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    HRESULT STDMETHODCALLTYPE ActivateCompleted(IActivateAudioInterfaceAsyncOperation *) override {
        SetEvent(done_);
        return S_OK;
    }

private:
    ~ActivationHandler() {
        if (done_) CloseHandle(done_);
    }
    LONG refs_ = 1;
    HANDLE done_;
};

HRESULT activate_process_loopback(DWORD pid, bool include, ComPtr<IAudioClient> &client) {
    AUDIOCLIENT_ACTIVATION_PARAMS params = {};
    params.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
    params.ProcessLoopbackParams.TargetProcessId = pid;
    params.ProcessLoopbackParams.ProcessLoopbackMode = include ? PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE
                                                               : PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE;
    PROPVARIANT pv;
    PropVariantInit(&pv);
    pv.vt = VT_BLOB;
    pv.blob.cbSize = sizeof(params);
    pv.blob.pBlobData = reinterpret_cast<BYTE *>(&params);  // borrowed: no PropVariantClear

    auto *handler = new ActivationHandler();
    if (!handler->done()) {
        handler->Release();
        return HRESULT_FROM_WIN32(GetLastError());
    }
    ComPtr<IActivateAudioInterfaceAsyncOperation> op;
    HRESULT hr = ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, __uuidof(IAudioClient), &pv,
                                             handler, op.put());
    if (SUCCEEDED(hr)) {
        if (WaitForSingleObject(handler->done(), kActivationTimeoutMs) != WAIT_OBJECT_0) {
            hr = HRESULT_FROM_WIN32(ERROR_TIMEOUT);
        } else {
            HRESULT activate_hr = E_FAIL;
            ComPtr<IUnknown> unk;
            hr = op->GetActivateResult(&activate_hr, unk.put());
            if (SUCCEEDED(hr)) hr = activate_hr;
            if (SUCCEEDED(hr)) hr = unk->QueryInterface(__uuidof(IAudioClient), client.put_void());
        }
    }
    handler->Release();
    return hr;
}

HRESULT activate_device(const std::wstring &id, EDataFlow flow, ComPtr<IAudioClient> &client) {
    ComPtr<IMMDeviceEnumerator> e;
    HRESULT hr = CoCreateInstance(vct::kClsidMMDeviceEnumerator, nullptr, CLSCTX_ALL, vct::kIidIMMDeviceEnumerator,
                                  e.put_void());
    if (FAILED(hr)) return hr;
    ComPtr<IMMDevice> device;
    hr = id.empty() ? e->GetDefaultAudioEndpoint(flow, eConsole, device.put()) : e->GetDevice(id.c_str(), device.put());
    // A malformed id is just an id of no device. E_INVALIDARG would read as "format rejected".
    if (hr == E_INVALIDARG) hr = HRESULT_FROM_WIN32(ERROR_NOT_FOUND);
    if (FAILED(hr)) return hr;
    return device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, client.put_void());
}

}  // namespace

struct vct_stream {
    // Fixed at open.
    int32_t source = 0;
    std::wstring device_id;
    DWORD pid = 0;
    int32_t rate = 0;
    int32_t channels = 0;

    std::thread thread;
    HANDLE stop = nullptr;

    // Ring buffer of interleaved floats, guarded by `m`.
    std::mutex m;
    std::condition_variable cv;
    std::vector<float> ring;
    size_t head = 0;  // index of the oldest stored float
    size_t size = 0;  // floats stored
    HRESULT failure = S_OK;
    bool ended = false;
    int64_t captured = 0;
    int64_t dropped = 0;
    int32_t discontinuities = 0;

    void push(const float *data, UINT32 frames, bool discontinuity) {
        size_t n = static_cast<size_t>(frames) * channels;
        std::lock_guard<std::mutex> lock(m);
        captured += frames;
        if (discontinuity) discontinuities++;
        const size_t cap = ring.size();
        if (n > cap) {  // a single packet larger than the ring: keep its tail
            dropped += static_cast<int64_t>((n - cap) / channels);
            if (data) data += n - cap;
            n = cap;
        }
        if (size + n > cap) {  // reader is behind: drop the oldest audio
            size_t excess = size + n - cap;
            head = (head + excess) % cap;
            size -= excess;
            dropped += static_cast<int64_t>(excess / channels);
        }
        size_t tail = (head + size) % cap;
        size_t first = std::min(n, cap - tail);
        if (data) {
            std::memcpy(&ring[tail], data, first * sizeof(float));
            std::memcpy(&ring[0], data + first, (n - first) * sizeof(float));
        } else {  // AUDCLNT_BUFFERFLAGS_SILENT
            std::fill_n(&ring[tail], first, 0.0f);
            std::fill_n(&ring[0], n - first, 0.0f);
        }
        size += n;
        cv.notify_one();
    }

    void finish(HRESULT hr) {
        std::lock_guard<std::mutex> lock(m);
        failure = hr;
        ended = true;
        cv.notify_all();
    }

    HRESULT initialize(ComPtr<IAudioClient> &client, HANDLE sample_event) {
        HRESULT hr;
        switch (source) {
            case VCT_SOURCE_DEVICE: hr = activate_device(device_id, eCapture, client); break;
            case VCT_SOURCE_DEVICE_LOOPBACK: hr = activate_device(device_id, eRender, client); break;
            default: hr = activate_process_loopback(pid, source == VCT_SOURCE_PROCESS_INCLUDE, client); break;
        }
        if (FAILED(hr)) return hr;

        WAVEFORMATEX fmt = {};
        fmt.wFormatTag = WAVE_FORMAT_IEEE_FLOAT;
        fmt.nChannels = static_cast<WORD>(channels);
        fmt.nSamplesPerSec = static_cast<DWORD>(rate);
        fmt.wBitsPerSample = 32;
        fmt.nBlockAlign = static_cast<WORD>(channels * sizeof(float));
        fmt.nAvgBytesPerSec = fmt.nSamplesPerSec * fmt.nBlockAlign;

        DWORD flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK;
        if (source != VCT_SOURCE_DEVICE) flags |= AUDCLNT_STREAMFLAGS_LOOPBACK;
        // Let Windows convert rate and channel count from the device format.
        hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED,
                                flags | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
                                kBufferDuration, 0, &fmt, nullptr);
        if (hr == E_INVALIDARG && source >= VCT_SOURCE_PROCESS_INCLUDE) {
            // Process loopback converts on its own; some builds reject the conversion flags. A failed
            // Initialize leaves the client unusable, so activate a fresh one.
            hr = activate_process_loopback(pid, source == VCT_SOURCE_PROCESS_INCLUDE, client);
            if (SUCCEEDED(hr)) hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, kBufferDuration, 0, &fmt, nullptr);
        }
        if (FAILED(hr)) return hr;
        return client->SetEventHandle(sample_event);
    }

    // Capture thread body. Reports the open result through `opened` exactly once.
    void run(std::promise<HRESULT> &opened) {
        vct::ComScope com;
        if (FAILED(com.result())) {
            opened.set_value(com.result());
            return;
        }
        DWORD task_index = 0;
        HANDLE mmcss = AvSetMmThreadCharacteristicsW(L"Audio", &task_index);  // best effort

        HANDLE sample_event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        ComPtr<IAudioClient> client;
        ComPtr<IAudioCaptureClient> capture;
        HRESULT hr = sample_event ? initialize(client, sample_event) : HRESULT_FROM_WIN32(GetLastError());
        if (SUCCEEDED(hr)) hr = client->GetService(__uuidof(IAudioCaptureClient), capture.put_void());
        if (SUCCEEDED(hr)) hr = client->Start();
        opened.set_value(hr);

        if (SUCCEEDED(hr)) {
            HANDLE waits[2] = {stop, sample_event};
            for (;;) {
                DWORD w = WaitForMultipleObjects(2, waits, FALSE, kWaitMs);
                if (w == WAIT_OBJECT_0) break;  // vct_close
                if (w == WAIT_FAILED) {
                    hr = HRESULT_FROM_WIN32(GetLastError());
                    break;
                }
                hr = drain(capture.get());  // on the sample event and on timeout alike
                if (FAILED(hr)) break;
            }
            client->Stop();
        }
        finish(hr);

        capture.reset();
        client.reset();
        if (sample_event) CloseHandle(sample_event);
        if (mmcss) AvRevertMmThreadCharacteristics(mmcss);
    }

    HRESULT drain(IAudioCaptureClient *capture) {
        for (;;) {
            UINT32 packet = 0;
            HRESULT hr = capture->GetNextPacketSize(&packet);
            if (FAILED(hr)) return hr;
            if (packet == 0) return S_OK;
            BYTE *data = nullptr;
            UINT32 frames = 0;
            DWORD flags = 0;
            hr = capture->GetBuffer(&data, &frames, &flags, nullptr, nullptr);
            if (FAILED(hr)) return hr;
            if (hr == AUDCLNT_S_BUFFER_EMPTY) return S_OK;
            push((flags & AUDCLNT_BUFFERFLAGS_SILENT) ? nullptr : reinterpret_cast<const float *>(data), frames,
                 (flags & AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY) != 0);
            hr = capture->ReleaseBuffer(frames);
            if (FAILED(hr)) return hr;
        }
    }
};

extern "C" {

VCT_API int32_t vct_open(int32_t source, const char *device_id, uint32_t pid, int32_t sample_rate, int32_t channels,
                         vct_stream **out) {
    if (!out) return E_POINTER;
    *out = nullptr;
    if (source < VCT_SOURCE_DEVICE || source > VCT_SOURCE_PROCESS_EXCLUDE) return E_INVALIDARG;
    if (sample_rate < 8000 || sample_rate > 384000 || channels < 1 || channels > 8) return E_INVALIDARG;
    if (source >= VCT_SOURCE_PROCESS_INCLUDE && pid == 0) return E_INVALIDARG;

    auto *s = new (std::nothrow) vct_stream();
    if (!s) return E_OUTOFMEMORY;
    s->source = source;
    s->device_id = vct::from_utf8(device_id);
    s->pid = pid;
    s->rate = sample_rate;
    s->channels = channels;
    s->ring.assign(static_cast<size_t>(sample_rate) * channels * kRingSeconds, 0.0f);
    s->stop = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!s->stop) {
        HRESULT hr = HRESULT_FROM_WIN32(GetLastError());
        delete s;
        return hr;
    }

    std::promise<HRESULT> opened;
    std::future<HRESULT> result = opened.get_future();
    // The thread owns the promise: set_value may still touch it after result.get() has returned.
    s->thread = std::thread([s, p = std::move(opened)]() mutable { s->run(p); });
    HRESULT hr = result.get();
    if (FAILED(hr)) {
        s->thread.join();  // the thread exits right after a failed open
        CloseHandle(s->stop);
        delete s;
        return hr;
    }
    *out = s;
    return S_OK;
}

VCT_API int32_t vct_read(vct_stream *s, float *dst, int32_t capacity_frames, int32_t timeout_ms,
                         int32_t *frames_read) {
    if (!s || !dst || !frames_read || capacity_frames < 0) return E_POINTER;
    *frames_read = 0;
    std::unique_lock<std::mutex> lock(s->m);
    s->cv.wait_for(lock, std::chrono::milliseconds(std::max(timeout_ms, 0)),
                   [s] { return s->size > 0 || s->ended; });
    if (s->size == 0) {
        if (!s->ended) return S_OK;  // timeout
        return FAILED(s->failure) ? s->failure : VCT_E_CLOSED;
    }
    const size_t ch = static_cast<size_t>(s->channels);
    const size_t cap = s->ring.size();
    size_t n = std::min(s->size, static_cast<size_t>(capacity_frames) * ch);
    n -= n % ch;
    size_t first = std::min(n, cap - s->head);
    std::memcpy(dst, &s->ring[s->head], first * sizeof(float));
    std::memcpy(dst + first, &s->ring[0], (n - first) * sizeof(float));
    s->head = (s->head + n) % cap;
    s->size -= n;
    *frames_read = static_cast<int32_t>(n / ch);
    return S_OK;
}

VCT_API int32_t vct_stats(vct_stream *s, int64_t *frames_captured, int64_t *frames_dropped,
                          int32_t *discontinuities) {
    if (!s) return E_POINTER;
    std::lock_guard<std::mutex> lock(s->m);
    if (frames_captured) *frames_captured = s->captured;
    if (frames_dropped) *frames_dropped = s->dropped;
    if (discontinuities) *discontinuities = s->discontinuities;
    return S_OK;
}

VCT_API void vct_close(vct_stream *s) {
    if (!s) return;
    SetEvent(s->stop);
    if (s->thread.joinable()) s->thread.join();
    CloseHandle(s->stop);
    delete s;
}

}  // extern "C"
