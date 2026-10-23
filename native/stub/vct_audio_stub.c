/*
 * Test double for vct_audio with the same C interface, for Linux/macOS. It lets the Java FFM binding
 * be tested without Windows. It produces a 440 Hz sine instead of capturing.
 *
 * Special device ids: "missing" fails to open with E_NOTFOUND; "stub-die" fails with
 * AUDCLNT_E_DEVICE_INVALIDATED on the fourth read.
 *
 *   cc -shared -fPIC -O2 -Iinclude -o libvct_audio_stub.so stub/vct_audio_stub.c -lm
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "vct_audio.h"

#define E_INVALIDARG_ ((int32_t)0x80070057)
#define E_POINTER_ ((int32_t)0x80004003)
#define E_NOTFOUND_ ((int32_t)0x80070490)
#define DEVICE_INVALIDATED_ ((int32_t)0x88890004)

struct vct_stream {
    int32_t rate, channels, reads, die;
    double phase;
    int64_t captured;
};

int32_t vct_api_version(void) { return VCT_API_VERSION; }

int32_t vct_list_devices(int32_t flow, char *json, int32_t capacity, int32_t *required) {
    const char *out;
    if (flow == VCT_FLOW_CAPTURE) {
        out = "[{\"id\":\"stub-mic\",\"name\":\"Stub Mikrofon \xC3\xBC \\\"USB\\\"\",\"default\":true,\"communications\":true},"
              "{\"id\":\"stub-die\",\"name\":\"Dies after three reads\",\"default\":false,\"communications\":false}]";
    } else if (flow == VCT_FLOW_RENDER) {
        out = "[{\"id\":\"stub-speaker\",\"name\":\"Stub Speaker\",\"default\":true,\"communications\":true}]";
    } else {
        return E_INVALIDARG_;
    }
    int32_t need = (int32_t)strlen(out) + 1;
    if (required) *required = need;
    if (!json || capacity < need) return VCT_E_BUFFER_TOO_SMALL;
    memcpy(json, out, (size_t)need);
    return 0;
}

int32_t vct_open(int32_t source, const char *device_id, uint32_t pid, int32_t sample_rate, int32_t channels,
                 vct_stream **out) {
    if (!out) return E_POINTER_;
    *out = NULL;
    if (source < VCT_SOURCE_DEVICE || source > VCT_SOURCE_PROCESS_EXCLUDE) return E_INVALIDARG_;
    if (sample_rate < 8000 || channels < 1 || channels > 8) return E_INVALIDARG_;
    if (source >= VCT_SOURCE_PROCESS_INCLUDE && pid == 0) return E_INVALIDARG_;
    if (device_id && strcmp(device_id, "missing") == 0) return E_NOTFOUND_;
    vct_stream *s = calloc(1, sizeof *s);
    if (!s) return (int32_t)0x8007000E;
    s->rate = sample_rate;
    s->channels = channels;
    s->die = device_id && strcmp(device_id, "stub-die") == 0;
    *out = s;
    return 0;
}

int32_t vct_read(vct_stream *s, float *dst, int32_t capacity_frames, int32_t timeout_ms, int32_t *frames_read) {
    if (!s || !dst || !frames_read) return E_POINTER_;
    *frames_read = 0;
    if (s->die && ++s->reads > 3) return DEVICE_INVALIDATED_;
    int ms = timeout_ms < 10 ? timeout_ms : 10;
    struct timespec ts = {0, (long)ms * 1000000L};
    nanosleep(&ts, NULL);
    int32_t frames = s->rate / 100;
    if (frames > capacity_frames) frames = capacity_frames;
    for (int32_t i = 0; i < frames; i++) {
        float v = (float)(0.5 * sin(s->phase));
        s->phase += 2.0 * 3.14159265358979323846 * 440.0 / s->rate;
        for (int32_t c = 0; c < s->channels; c++) dst[i * s->channels + c] = v;
    }
    s->captured += frames;
    *frames_read = frames;
    return 0;
}

int32_t vct_stats(vct_stream *s, int64_t *frames_captured, int64_t *frames_dropped, int32_t *discontinuities) {
    if (!s) return E_POINTER_;
    if (frames_captured) *frames_captured = s->captured;
    if (frames_dropped) *frames_dropped = 0;
    if (discontinuities) *discontinuities = 0;
    return 0;
}

void vct_close(vct_stream *s) { free(s); }

int64_t vct_device_changes(void) { return 0; }

int32_t vct_error_message(int32_t hr, char *buf, int32_t capacity) {
    if (!buf || capacity <= 0) return 0;
    int n = snprintf(buf, (size_t)capacity, "stub error 0x%08X", (unsigned)hr);
    return n < capacity ? n : capacity - 1;
}

void vct_shutdown(void) {}
