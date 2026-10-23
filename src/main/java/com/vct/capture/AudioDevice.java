package com.vct.capture;

/**
 * An active Windows audio endpoint.
 *
 * @param id             stable endpoint id, used to reopen the same device later
 * @param name           friendly name shown in Windows sound settings
 * @param isDefault      the Windows default device
 * @param communications the Windows default communications device
 */
public record AudioDevice(String id, String name, boolean isDefault, boolean communications) {

    public enum Flow { CAPTURE, RENDER }
}
