package com.vct.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureExceptionTest {

    @Test
    void classifiesWindowsAudioErrors() {
        assertEquals(CaptureException.Kind.DEVICE_LOST, CaptureException.classify(0x88890004));
        assertEquals(CaptureException.Kind.DEVICE_LOST, CaptureException.classify(0x88890026));
        assertEquals(CaptureException.Kind.NOT_FOUND, CaptureException.classify(0x80070490));
        assertEquals(CaptureException.Kind.ACCESS_DENIED, CaptureException.classify(0x80070005));
        assertEquals(CaptureException.Kind.IN_USE, CaptureException.classify(0x8889000A));
        assertEquals(CaptureException.Kind.UNSUPPORTED_FORMAT, CaptureException.classify(0x88890008));
        assertEquals(CaptureException.Kind.UNSUPPORTED_OS, CaptureException.classify(0x80004001));
        assertEquals(CaptureException.Kind.SERVICE_DOWN, CaptureException.classify(0x88890010));
        assertEquals(CaptureException.Kind.CLOSED, CaptureException.classify(0x80047C01));
        assertEquals(CaptureException.Kind.OTHER, CaptureException.classify(0x80004005));
    }

    @Test
    void messageNamesTheActionTheFixAndTheCode() {
        CaptureException e = CaptureException.fromHresult("Opening the microphone", 0x80070005, "Access is denied.");
        assertEquals(0x80070005, e.hresult());
        assertTrue(e.getMessage().startsWith("Opening the microphone failed: Windows blocked microphone access."), e.getMessage());
        assertTrue(e.getMessage().endsWith("(0x80070005)"), e.getMessage());
    }

    @Test
    void unknownCodesUseTheWindowsText() {
        CaptureException e = CaptureException.fromHresult("Reading audio", 0x80004005, "Unspecified error\r\n");
        assertEquals("Reading audio failed: Unspecified error (0x80004005)", e.getMessage());
    }
}
