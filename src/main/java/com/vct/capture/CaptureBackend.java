package com.vct.capture;

import java.util.List;

/** Opens capture streams. {@link WasapiBackend} on Windows; fakes in tests. Thread-safe. */
public interface CaptureBackend {

    List<AudioDevice> listDevices(AudioDevice.Flow flow) throws CaptureException;

    /** Opens and starts a stream of interleaved float samples in exactly this format. */
    CaptureStream open(CaptureTarget target, int sampleRate, int channels) throws CaptureException;

    /** Increases whenever a device is added, removed or the default device changes. */
    long deviceChangeCount();
}
