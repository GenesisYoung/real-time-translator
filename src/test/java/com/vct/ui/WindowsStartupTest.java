package com.vct.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsStartupTest {

    @Test
    void startsTheProgramInTheTrayAndQuotesItsPath() {
        String exe = "C:\\Users\\me\\AppData\\Local\\VoiceChatTranslator\\VoiceChatTranslator.exe";
        assertEquals("\"" + exe + "\" --tray", WindowsStartup.command(exe));
    }
}
