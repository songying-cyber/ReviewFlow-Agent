package com.paicli.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageProcessorTest {

    @Test
    void keepsSmallImageAsImageBlock(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("small.png");
        BufferedImage buffered = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(buffered, "png", image.toFile());

        ImageProcessor.ProcessedImage processed = ImageProcessor.fromPath(image, "image/png");

        assertEquals("image/png", processed.mimeType());
        assertEquals(20, processed.dimensions().displayWidth());
        assertEquals(10, processed.dimensions().displayHeight());
        assertTrue(processed.base64().length() <= ImageProcessor.API_IMAGE_MAX_BASE64_SIZE);
    }

    @Test
    void flattensSmallAlphaPngBeforeAttaching(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("alpha.png");
        BufferedImage buffered = new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = buffered.createGraphics();
        try {
            graphics.setColor(new Color(0, 0, 0, 0));
            graphics.fillRect(0, 0, buffered.getWidth(), buffered.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.drawString("Hi", 2, 8);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(buffered, "png", image.toFile());

        ImageProcessor.ProcessedImage processed = ImageProcessor.fromPath(image, "image/png");

        assertEquals("image/png", processed.mimeType());
        assertTrue(processed.reencoded(), "带 alpha 的 PNG 应铺白底重编码，避免 provider 透明通道处理不一致");
        assertEquals(20, processed.dimensions().displayWidth());
        assertEquals(10, processed.dimensions().displayHeight());
        assertTrue(processed.base64().length() <= ImageProcessor.API_IMAGE_MAX_BASE64_SIZE);
    }

    @Test
    void keepsLargeButUnderApiLimitImageAsLosslessPng(@TempDir Path tempDir) throws Exception {
        // 2600x1200 RGB PNG 单色压缩后只有几十 KB，远低于 5MB API 上限。
        // 新策略：尺寸超过 IMAGE_MAX_WIDTH 不再触发 resize+JPEG，直通原始 PNG bytes，
        // 让 provider 自己按 tile 计费/缩放，避免小字被 JPEG 块伪影糊掉。
        Path image = tempDir.resolve("large.png");
        BufferedImage buffered = new BufferedImage(2600, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = buffered.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, buffered.getWidth(), buffered.getHeight());
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 2600, 1200);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(buffered, "png", image.toFile());

        ImageProcessor.ProcessedImage processed = ImageProcessor.fromPath(image, "image/png");

        assertEquals("image/png", processed.mimeType(), "字节在 API 上限内应保持 PNG，不转 JPEG");
        assertTrue(processed.base64().length() <= ImageProcessor.API_IMAGE_MAX_BASE64_SIZE);
        assertEquals(2600, processed.dimensions().originalWidth());
        assertEquals(2600, processed.dimensions().displayWidth(), "尺寸超过 2000 但字节够小时不应 resize");
        assertEquals(1200, processed.dimensions().displayHeight());
        String metadata = ImageProcessor.createMetadataText(processed);
        assertNotNull(metadata);
        assertTrue(metadata.contains("source: " + image), "图片元信息应保留本地路径，供 MCP media 工具兜底读取");
    }

    @Test
    void fallsBackToJpegOnlyWhenPngExceedsApiLimit(@TempDir Path tempDir) throws Exception {
        // 大尺寸 + 高熵噪声让 PNG 无法压缩进 5MB，强制走 JPEG fallback 分支。
        Path image = tempDir.resolve("noisy.png");
        BufferedImage buffered = new BufferedImage(3000, 3000, BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(42);
        for (int y = 0; y < buffered.getHeight(); y++) {
            for (int x = 0; x < buffered.getWidth(); x++) {
                buffered.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        ImageIO.write(buffered, "png", image.toFile());

        ImageProcessor.ProcessedImage processed = ImageProcessor.fromPath(image, "image/png");

        assertEquals("image/jpeg", processed.mimeType(),
                "PNG 无法压进 5MB 时退到 JPEG（resize 也无能为力的高熵图）");
        assertTrue(processed.base64().length() <= ImageProcessor.API_IMAGE_MAX_BASE64_SIZE);
        assertEquals(3000, processed.dimensions().originalWidth());
        assertEquals(2000, processed.dimensions().displayWidth(), "JPEG 兜底前会 resize 到 IMAGE_MAX_WIDTH");
    }
}
