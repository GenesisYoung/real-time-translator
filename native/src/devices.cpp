// Device enumeration, device-change watching, error text and string helpers.

#include <atomic>
#include <cstring>
#include <mutex>

#include "common.h"

namespace vct {

std::string to_utf8(const wchar_t *s) {
    if (!s || !*s) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, s, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) return {};
    std::string out(static_cast<size_t>(n - 1), '\0');
    WideCharToMultiByte(CP_UTF8, 0, s, -1, out.data(), n, nullptr, nullptr);
    return out;
}

std::wstring from_utf8(const char *s) {
    if (!s || !*s) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
    if (n <= 1) return {};
    std::wstring out(static_cast<size_t>(n - 1), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s, -1, out.data(), n);
    return out;
}

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
            out += static_cast<char>(c);  // UTF-8 multi-byte sequences pass through unchanged
        }
    }
    out += '"';
}

namespace {

// PKEY_Device_FriendlyName, defined locally for the same reason as the GUIDs in common.h.
const PROPERTYKEY kFriendlyName = {
    {0xA45C254E, 0xDF1C, 0x4EFD, {0x80, 0x20, 0x67, 0xD1, 0x46, 0xA8, 0x50, 0xE0}}, 14};

std::string default_id(IMMDeviceEnumerator *e, EDataFlow flow, ERole role) {
    ComPtr<IMMDevice> d;
    if (FAILED(e->GetDefaultAudioEndpoint(flow, role, d.put()))) return {};  // no device of this kind
    LPWSTR id = nullptr;
    if (FAILED(d->GetId(&id))) return {};
    std::string s = to_utf8(id);
    CoTaskMemFree(id);
    return s;
}

std::string friendly_name(IMMDevice *d) {
    ComPtr<IPropertyStore> props;
    if (FAILED(d->OpenPropertyStore(STGM_READ, props.put()))) return {};
    PROPVARIANT v;
    PropVariantInit(&v);
    std::string name;
    if (SUCCEEDED(props->GetValue(kFriendlyName, &v)) && v.vt == VT_LPWSTR) name = to_utf8(v.pwszVal);
    PropVariantClear(&v);
    return name;
}

HRESULT list_json(EDataFlow flow, std::string &json) {
    ComPtr<IMMDeviceEnumerator> e;
    HRESULT hr = CoCreateInstance(kClsidMMDeviceEnumerator, nullptr, CLSCTX_ALL, kIidIMMDeviceEnumerator,
                                  e.put_void());
    if (FAILED(hr)) return hr;
    ComPtr<IMMDeviceCollection> all;
    hr = e->EnumAudioEndpoints(flow, DEVICE_STATE_ACTIVE, all.put());
    if (FAILED(hr)) return hr;
    UINT count = 0;
    hr = all->GetCount(&count);
    if (FAILED(hr)) return hr;

    std::string console = default_id(e.get(), flow, eConsole);
    std::string comms = default_id(e.get(), flow, eCommunications);
    json = "[";
    bool first = true;
    for (UINT i = 0; i < count; i++) {
        ComPtr<IMMDevice> d;
        if (FAILED(all->Item(i, d.put()))) continue;  // removed while enumerating
        LPWSTR wid = nullptr;
        if (FAILED(d->GetId(&wid))) continue;
        std::string id = to_utf8(wid);
        CoTaskMemFree(wid);
        if (!first) json += ',';
        first = false;
        json += "{\"id\":";
        append_json_string(json, id);
        json += ",\"name\":";
        append_json_string(json, friendly_name(d.get()));
        json += ",\"default\":";
        json += id == console ? "true" : "false";
        json += ",\"communications\":";
        json += id == comms ? "true" : "false";
        json += '}';
    }
    json += ']';
    return S_OK;
}

// ------------------------------------------------------------------ device watching

std::atomic<int64_t> g_changes{0};

// Lives for the whole process, so reference counting is a no-op.
class DeviceWatcher final : public IMMNotificationClient {
public:
    ULONG STDMETHODCALLTYPE AddRef() override { return 1; }
    ULONG STDMETHODCALLTYPE Release() override { return 1; }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void **ppv) override {
        if (!ppv) return E_POINTER;
        if (IsEqualIID(riid, IID_IUnknown) || IsEqualIID(riid, __uuidof(IMMNotificationClient))) {
            *ppv = static_cast<IMMNotificationClient *>(this);
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    HRESULT STDMETHODCALLTYPE OnDeviceStateChanged(LPCWSTR, DWORD) override { return bump(); }
    HRESULT STDMETHODCALLTYPE OnDeviceAdded(LPCWSTR) override { return bump(); }
    HRESULT STDMETHODCALLTYPE OnDeviceRemoved(LPCWSTR) override { return bump(); }
    HRESULT STDMETHODCALLTYPE OnDefaultDeviceChanged(EDataFlow, ERole, LPCWSTR) override { return bump(); }
    // Fires for volume and many other property updates; not a device change.
    HRESULT STDMETHODCALLTYPE OnPropertyValueChanged(LPCWSTR, const PROPERTYKEY) override { return S_OK; }

private:
    static HRESULT bump() {
        g_changes.fetch_add(1, std::memory_order_relaxed);
        return S_OK;
    }
};

DeviceWatcher g_watcher;
std::mutex g_watch_mutex;
// A pointer, never a static std::thread: if the app exits without vct_shutdown, the destructor of a
// still-joinable std::thread calls std::terminate and the whole process aborts (0xC0000409 on MSVC).
std::thread *g_watch_thread = nullptr;
HANDLE g_watch_stop = nullptr;

void start_watching_locked() {
    g_watch_stop = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    HANDLE ready = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!g_watch_stop || !ready) {
        if (g_watch_stop) CloseHandle(g_watch_stop);
        if (ready) CloseHandle(ready);
        g_watch_stop = nullptr;
        return;
    }
    HANDLE stop = g_watch_stop;
    g_watch_thread = new std::thread([stop, ready] {
        ComScope com;
        ComPtr<IMMDeviceEnumerator> e;
        bool registered = SUCCEEDED(com.result())
                && SUCCEEDED(CoCreateInstance(kClsidMMDeviceEnumerator, nullptr, CLSCTX_ALL,
                                              kIidIMMDeviceEnumerator, e.put_void()))
                && SUCCEEDED(e->RegisterEndpointNotificationCallback(&g_watcher));
        SetEvent(ready);
        if (!registered) return;
        WaitForSingleObject(stop, INFINITE);
        e->UnregisterEndpointNotificationCallback(&g_watcher);
    });
    // Registration is quick; waiting means events right after the first call are not missed.
    // On timeout the thread may still signal `ready` later, so the handle is left open.
    if (WaitForSingleObject(ready, 5000) == WAIT_OBJECT_0) CloseHandle(ready);
}

}  // namespace
}  // namespace vct

using namespace vct;

extern "C" {

VCT_API int32_t vct_api_version(void) { return VCT_API_VERSION; }

VCT_API int32_t vct_list_devices(int32_t flow, char *json, int32_t capacity, int32_t *required) {
    if (flow != VCT_FLOW_CAPTURE && flow != VCT_FLOW_RENDER) return E_INVALIDARG;
    std::string out;
    HRESULT hr = run_in_mta([&] { return list_json(flow == VCT_FLOW_CAPTURE ? eCapture : eRender, out); });
    if (FAILED(hr)) return hr;
    int32_t need = static_cast<int32_t>(out.size() + 1);
    if (required) *required = need;
    if (!json || capacity < need) return VCT_E_BUFFER_TOO_SMALL;
    std::memcpy(json, out.c_str(), out.size() + 1);
    return S_OK;
}

VCT_API int64_t vct_device_changes(void) {
    {
        std::lock_guard<std::mutex> lock(g_watch_mutex);
        if (!g_watch_stop) start_watching_locked();
    }
    return g_changes.load(std::memory_order_relaxed);
}

VCT_API void vct_shutdown(void) {
    std::lock_guard<std::mutex> lock(g_watch_mutex);
    if (!g_watch_stop) return;
    SetEvent(g_watch_stop);
    g_watch_thread->join();
    delete g_watch_thread;
    g_watch_thread = nullptr;
    CloseHandle(g_watch_stop);
    g_watch_stop = nullptr;
}

VCT_API int32_t vct_error_message(int32_t hr, char *buf, int32_t capacity) {
    if (!buf || capacity <= 0) return 0;
    std::string text;
    if (hr == VCT_E_CLOSED) {
        text = "The capture stream has stopped.";
    } else {
        wchar_t *w = nullptr;
        DWORD n = FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM
                                         | FORMAT_MESSAGE_IGNORE_INSERTS,
                                 nullptr, static_cast<DWORD>(hr), 0, reinterpret_cast<LPWSTR>(&w), 0, nullptr);
        if (n > 0 && w) {
            text = to_utf8(w);
            LocalFree(w);
            while (!text.empty() && (text.back() == '\n' || text.back() == '\r' || text.back() == ' ')) text.pop_back();
        }
    }
    // Truncating UTF-8 mid-character is harmless here: Java decodes with replacement.
    size_t n = text.size() < static_cast<size_t>(capacity - 1) ? text.size() : static_cast<size_t>(capacity - 1);
    std::memcpy(buf, text.data(), n);
    buf[n] = '\0';
    return static_cast<int32_t>(n);
}

}  // extern "C"
