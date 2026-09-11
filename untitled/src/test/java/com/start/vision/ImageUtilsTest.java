package com.start.vision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageUtilsTest {

    @Test
    void detectsPngFromBytesWhenUrlHasNoExtension() {
        byte[] pngHeader = {
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
        };

        assertEquals("image/png", ImageUtils.detectMimeType(pngHeader, "https://qq.invalid/image"));
    }
}
