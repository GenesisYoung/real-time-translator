/*
 * vct_audio: WASAPI capture for Voice Chat Translator (Windows 10 build 20348+ / Windows 11).
 *
 * Plain C interface, called from Java through FFM (java.lang.foreign).
 *
 * Model: pull. Each open stream owns a capture thread inside the DLL that fills a ring buffer;
 * the caller drains it with vct_read(). The DLL never calls back into the caller.
 *
 * Results: every int32_t result is an HRESULT. 0 (S_OK) means success, negative means failure.
 * Text: all strings are UTF-8.
 * Threads: functions may be called from any thread, except that one stream must not be used by two
 * threads at once, and vct_close() must not race with vct_read() on the same stream.
 */
#ifndef VCT_AUDIO_H
#define VCT_AUDIO_H

#include <stdint.h>

#if defined(_WIN32)
#  if defined(VCT_AUDIO_BUILD)
#    define VCT_API __declspec(dllexport)
#  else
#    define VCT_API __declspec(dllimport)
#  endif
#else
#  define VCT_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define VCT_API_VERSION 1

/* Codes specific to this library (Windows HRESULTs are passed through unchanged). */
#define VCT_E_CLOSED           ((int32_t)0x80047C01) /* the stream's capture thread has ended */
#define VCT_E_BUFFER_TOO_SMALL ((int32_t)0x8007007A) /* HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER) */

/* Device flow for vct_list_devices. */
#define VCT_FLOW_CAPTURE 0 /* microphones */
#define VCT_FLOW_RENDER  1 /* speakers / headphones */

/* What vct_open captures. */
#define VCT_SOURCE_DEVICE          0 /* a capture device (microphone) */
#define VCT_SOURCE_DEVICE_LOOPBACK 1 /* everything a render device plays */
#define VCT_SOURCE_PROCESS_INCLUDE 2 /* only audio played by process `pid` and its child processes */
#define VCT_SOURCE_PROCESS_EXCLUDE 3 /* all audio except process `pid` and its child processes */

typedef struct vct_stream vct_stream;

/* Returns VCT_API_VERSION of the loaded DLL. */
VCT_API int32_t vct_api_version(void);

/*
 * Writes active devices of `flow` as a NUL-terminated JSON array into `json`:
 *   [{"id":"...","name":"...","default":true,"communications":false}, ...]
 * `*required` always receives the size needed in bytes, including the NUL.
 * Returns VCT_E_BUFFER_TOO_SMALL if `capacity` is not enough (nothing is written).
 */
VCT_API int32_t vct_list_devices(int32_t flow, char *json, int32_t capacity, int32_t *required);

/*
 * Opens and starts a capture stream delivering interleaved 32-bit float samples at `sample_rate`
 * and `channels`. Windows converts from the device format.
 *   VCT_SOURCE_DEVICE / _DEVICE_LOOPBACK: `device_id` from vct_list_devices, or NULL for the default device.
 *   VCT_SOURCE_PROCESS_*: `pid` selects the process tree; `device_id` is ignored.
 * On success `*out` receives the stream. Blocks until the stream is running or has failed (at most ~5 s).
 */
VCT_API int32_t vct_open(int32_t source, const char *device_id, uint32_t pid,
                         int32_t sample_rate, int32_t channels, vct_stream **out);

/*
 * Copies up to `capacity_frames` frames (one frame = `channels` floats) into `dst`.
 * Waits up to `timeout_ms` for data; `*frames_read` is 0 on timeout.
 * After the stream has failed, returns the failure (e.g. AUDCLNT_E_DEVICE_INVALIDATED) once
 * buffered audio is drained.
 */
VCT_API int32_t vct_read(vct_stream *stream, float *dst, int32_t capacity_frames, int32_t timeout_ms,
                         int32_t *frames_read);

/* Counters since open. Dropped frames happen when the caller reads too slowly (buffer holds 2 s). */
VCT_API int32_t vct_stats(vct_stream *stream, int64_t *frames_captured, int64_t *frames_dropped,
                          int32_t *discontinuities);

/* Stops the capture thread and frees the stream. NULL is ignored. */
VCT_API void vct_close(vct_stream *stream);

/*
 * Count of device events (added, removed, state changed, default changed) since the first call.
 * The first call starts watching and returns 0. Poll it to notice devices being plugged or unplugged.
 */
VCT_API int64_t vct_device_changes(void);

/* Writes a NUL-terminated description of `hr` into `buf`. Returns the length written, without the NUL. */
VCT_API int32_t vct_error_message(int32_t hr, char *buf, int32_t capacity);

/* Stops device watching. Call once before the process exits; open streams must be closed first. */
VCT_API void vct_shutdown(void);

#ifdef __cplusplus
}
#endif

#endif /* VCT_AUDIO_H */
