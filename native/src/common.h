// Internal helpers shared by the vct_audio sources. Windows only.
#pragma once

#include <windows.h>
#include <objbase.h>
#include <mmdeviceapi.h>
#include <audioclient.h>

#include <string>
#include <thread>
#include <utility>

#include "vct_audio.h"

namespace vct {

// Defined here rather than taken from SDK headers so MSVC and MinGW link the same way.
// {BCDE0395-E52F-467C-8E3D-C4579291692E}
inline const CLSID kClsidMMDeviceEnumerator = {
    0xBCDE0395, 0xE52F, 0x467C, {0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E}};
// {A95664D2-9614-4F35-A746-DE8DB63617E6}
inline const IID kIidIMMDeviceEnumerator = {
    0xA95664D2, 0x9614, 0x4F35, {0xA7, 0x46, 0xDE, 0x8D, 0xB6, 0x36, 0x17, 0xE6}};

// Minimal owning COM pointer (WRL/ATL are not available with MinGW).
template <typename T>
class ComPtr {
public:
    ComPtr() = default;
    ~ComPtr() { reset(); }
    ComPtr(const ComPtr &) = delete;
    ComPtr &operator=(const ComPtr &) = delete;
    ComPtr(ComPtr &&o) noexcept : p_(std::exchange(o.p_, nullptr)) {}
    ComPtr &operator=(ComPtr &&o) noexcept {
        if (this != &o) {
            reset();
            p_ = std::exchange(o.p_, nullptr);
        }
        return *this;
    }

    T *get() const { return p_; }
    T *operator->() const { return p_; }
    explicit operator bool() const { return p_ != nullptr; }

    // For out-parameters: releases the current pointer first.
    T **put() {
        reset();
        return &p_;
    }
    void **put_void() { return reinterpret_cast<void **>(put()); }

    void reset() {
        if (p_) {
            p_->Release();
            p_ = nullptr;
        }
    }

private:
    T *p_ = nullptr;
};

// RAII for CoInitializeEx on the current thread. Always multithreaded apartment.
class ComScope {
public:
    ComScope() : hr_(CoInitializeEx(nullptr, COINIT_MULTITHREADED)) {}
    ~ComScope() {
        if (SUCCEEDED(hr_)) CoUninitialize();
    }
    ComScope(const ComScope &) = delete;
    ComScope &operator=(const ComScope &) = delete;
    HRESULT result() const { return hr_; }

private:
    HRESULT hr_;
};

// Runs `fn` on a fresh thread inside the multithreaded apartment and returns its HRESULT.
// Java threads may already be in a single-threaded apartment (e.g. the JavaFX thread), so COM work
// called directly from Java never relies on the caller's apartment.
template <typename F>
HRESULT run_in_mta(F &&fn) {
    HRESULT result = E_FAIL;
    std::thread worker([&] {
        ComScope com;
        result = FAILED(com.result()) ? com.result() : fn();
    });
    worker.join();
    return result;
}

std::string to_utf8(const wchar_t *s);
std::wstring from_utf8(const char *s);
void append_json_string(std::string &out, const std::string &utf8);

}  // namespace vct
