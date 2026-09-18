package com.start.vision;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageUtilsTest {

    @Test
    void detectsPngFromBytesWhenUrlHasNoExtension() {
        byte[] pngHeader = {
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
        };

        assertEquals("image/png", ImageUtils.detectMimeType(pngHeader, "https://qq.invalid/image"));
    }

    @Test
    void extractsAtMostThreeGifFramesAsDecodableJpegs() throws Exception {
        List<String> frames = ImageUtils.extractGifFrameDataUris(animatedGif(5), 3);

        assertEquals(3, frames.size());
        for (String dataUri : frames) {
            assertTrue(dataUri.startsWith("data:image/jpeg;base64,"));
            byte[] jpeg = Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1));
            assertEquals("image/jpeg", ImageUtils.detectMimeType(jpeg, ""));
            assertNotNull(ImageIO.read(new ByteArrayInputStream(jpeg)));
        }
    }

    @Test
    void replacesGifDataUriForMultimodalRequests() throws Exception {
        String gifUri = "data:image/gif;base64," + Base64.getEncoder().encodeToString(animatedGif(2));

        List<String> prepared = ImageUtils.prepareMultimodalDataUris(List.of(gifUri));

        assertEquals(2, prepared.size());
        assertTrue(prepared.stream().allMatch(uri -> uri.startsWith("data:image/jpeg;base64,")));
    }

    private static byte[] animatedGif(int frameCount) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            ImageWriteParam params = writer.getDefaultWriteParam();
            for (int i = 0; i < frameCount; i++) {
                BufferedImage frame = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = frame.createGraphics();
                try {
                    graphics.setColor(i % 2 == 0 ? Color.RED : Color.BLUE);
                    graphics.fillRect(0, 0, frame.getWidth(), frame.getHeight());
                } finally {
                    graphics.dispose();
                }
                writer.writeToSequence(new IIOImage(frame, null, null), params);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
