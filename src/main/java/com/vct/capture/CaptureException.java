package com.vct.capture;

import java.util.Locale;

/** A capture failure, classified from the Windows HRESULT so callers can react and show a useful message. */
public final class CaptureException extends Exception {

    public enum Kind {
        /** Device unplugged, disabled or reconfigured. Reopening usually works. */
        DEVICE_LOST,
        /** Device, or the process to capture, does not exist (now). */
        NOT_FOUND,
        /** Windows privacy settings block microphone access for desktop apps. */
        ACCESS_DENIED,
        /** Another app uses the device in exclusive mode. */
        IN_USE,
        /** The requested sample format was rejected; another format may work. */
        UNSUPPORTED_FORMAT,
        /** This Windows version cannot do it (process loopback needs Windows 11 or build 20348+). */
        UNSUPPORTED_OS,
        /** The Windows Audio service is not running. */
        SERVICE_DOWN,
        /** The stream was closed. */
        CLOSED,
        OTHER
    }

    static final int AUDCLNT_E_DEVICE_INVALIDATED = 0x88890004;
    static final int AUDCLNT_E_UNSUPPORTED_FORMAT = 0x88890008;
    static final int AUDCLNT_E_DEVICE_IN_USE = 0x8889000A;
    static final int AUDCLNT_E_ENDPOINT_CREATE_FAILED = 0x8889000F;
    static final int AUDCLNT_E_SERVICE_NOT_RUNNING = 0x88890010;
    static final int AUDCLNT_E_RESOURCES_INVALIDATED = 0x88890026;
    static final int E_NOTIMPL = 0x80004001;
    static final int E_ACCESSDENIED = 0x80070005;
    static final int E_INVALIDARG = 0x80070057;
    static final int E_NOTFOUND = 0x80070490;
    static final int ERROR_NOT_SUPPORTED = 0x80070032;
    static final int ERROR_FILE_NOT_FOUND = 0x80070002;
    static final int VCT_E_CLOSED = 0x80047C01;

    private final Kind kind;
    private final int hresult;

    public CaptureException(Kind kind, String message) {
        this(kind, 0, message);
    }

    private CaptureException(Kind kind, int hresult, String message) {
        super(message);
        this.kind = kind;
        this.hresult = hresult;
    }

    public Kind kind() {
        return kind;
    }

    /** The Windows error code, or 0 if the failure did not come from Windows. */
    public int hresult() {
        return hresult;
    }

    /**
     * Builds the exception for a failed native call.
     *
     * @param action      what was being done, e.g. "Opening the microphone"
     * @param systemText  Windows' own description of the code (may be empty)
     */
    public static CaptureException fromHresult(String action, int hr, String systemText) {
        Kind kind = classify(hr);
        String hint = switch (kind) {
            case DEVICE_LOST -> "The audio device was disconnected or changed.";
            case NOT_FOUND -> "The audio device was not found. It may be unplugged or disabled.";
            case ACCESS_DENIED -> "Windows blocked microphone access. Turn on Settings > Privacy & security > "
                    + "Microphone > \"Let desktop apps access your microphone\".";
            case IN_USE -> "Another app is using the device in exclusive mode. Turn off exclusive mode in that app "
                    + "or in the device's Windows sound properties.";
            case UNSUPPORTED_FORMAT -> "The device rejected the requested audio format.";
            case UNSUPPORTED_OS -> "This Windows version cannot capture a single app's audio. "
                    + "Windows 11 is required; use system loopback instead.";
            case SERVICE_DOWN -> "The Windows Audio service is not running.";
            case CLOSED -> "The capture stream has stopped.";
            case OTHER -> systemText == null || systemText.isBlank() ? "Unexpected audio error." : systemText.strip();
        };
        String code = String.format(Locale.ROOT, "0x%08X", hr);
        return new CaptureException(kind, hr, action + " failed: " + hint + " (" + code + ")");
    }

    static Kind classify(int hr) {
        return switch (hr) {
            case AUDCLNT_E_DEVICE_INVALIDATED, AUDCLNT_E_ENDPOINT_CREATE_FAILED, AUDCLNT_E_RESOURCES_INVALIDATED ->
                    Kind.DEVICE_LOST;
            case E_NOTFOUND, ERROR_FILE_NOT_FOUND -> Kind.NOT_FOUND;
            case E_ACCESSDENIED -> Kind.ACCESS_DENIED;
            case AUDCLNT_E_DEVICE_IN_USE -> Kind.IN_USE;
            // Initialize reports a format it cannot convert as E_INVALIDARG for some endpoints.
            case AUDCLNT_E_UNSUPPORTED_FORMAT, E_INVALIDARG -> Kind.UNSUPPORTED_FORMAT;
            case E_NOTIMPL, ERROR_NOT_SUPPORTED -> Kind.UNSUPPORTED_OS;
            case AUDCLNT_E_SERVICE_NOT_RUNNING -> Kind.SERVICE_DOWN;
            case VCT_E_CLOSED -> Kind.CLOSED;
            default -> Kind.OTHER;
        };
    }
}
