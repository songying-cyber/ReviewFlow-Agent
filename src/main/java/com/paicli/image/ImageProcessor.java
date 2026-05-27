package com.paicli.image;

import com.paicli.llm.LlmClient;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;

public final class ImageProcessor {
    public static final long API_IMAGE_MAX_BASE64_SIZE = 5L * 1024 * 1024;
    public static final long IMAGE_TARGET_RAW_SIZE = API_IMAGE_MAX_BASE64_SIZE * 3L / 4L;
    public static final long MAX_SOURCE_IMAGE_BYTES = 50L * 1024 * 1024;
    public static final int IMAGE_MAX_WIDTH = 2000;
    public static final int IMAGE_MAX_HEIGHT = 2000;

    private static final float[] JPEG_QUALITIES = new float[]{0.85f, 0.70f, 0.55f, 0.40f, 0.25f};

    private ImageProcessor() {}

    public static ProcessedImage fromPath(Path path, String mimeType) throws IOException {
        long size = Files.size(path);
        if (size == 0) {
            throw new IOException("图片文件为空");
        }
        if (size > MAX_SOURCE_IMAGE_BYTES) {
            throw new IOException("图片超过 " + (MAX_SOURCE_IMAGE_BYTES / 1024 / 1024) + "MB 处理上限");
        }
        byte[] bytes = Files.readAllBytes(path);
        return process(bytes, mimeType, path);
    }

    public static ProcessedImage fromBase64(String base64, String mimeType) throws IOException {
        if (base64 == null || base64.isBlank()) {
            throw new IOException("图片数据为空");
        }
        if (base64.length() > maxSourceBase64Length()) {
            throw new IOException("图片超过 " + (MAX_SOURCE_IMAGE_BYTES / 1024 / 1024) + "MB 处理上限");
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        return process(bytes, mimeType, null);
    }

    public static ProcessedImage process(byte[] bytes, String mimeType, Path sourcePath) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("图片数据为空");
        }
        String normalizedMime = normalizeMimeType(mimeType);
        BufferedImage image = tryRead(bytes);
        if (image == null) {
            long base64Size = estimateBase64Size(bytes.length);
            if (base64Size <= API_IMAGE_MAX_BASE64_SIZE) {
                return new ProcessedImage(
                        Base64.getEncoder().encodeToString(bytes),
                        normalizedMime,
                        bytes.length,
                        bytes.length,
                        null,
                        sourcePath,
                        false);
            }
            throw new IOException("图片超过 5MB API 上限，且当前运行时无法解码压缩该格式");
        }

        int originalWidth = image.getWidth();
        int originalHeight = image.getHeight();
        boolean overSize = estimateBase64Size(bytes.length) > API_IMAGE_MAX_BASE64_SIZE;
        boolean hasAlpha = image.getColorModel() != null && image.getColorModel().hasAlpha();

        // 1) 字节已经在 API 上限内 + 不需要 flatten alpha → 直通原始 PNG/JPEG bytes，
        //    不动尺寸。即便宽高超过 IMAGE_MAX_WIDTH/HEIGHT，2000 这条线只是一个关于
        //    token 成本的"软建议"，provider 自己会按 tile 计费/缩放；强行 resize + JPEG
        //    会破坏小字渲染（Chinese 字 + JPEG 8x8 块伪影），得不偿失。
        if (!overSize && !hasAlpha) {
            return new ProcessedImage(
                    Base64.getEncoder().encodeToString(bytes),
                    normalizedMime,
                    bytes.length,
                    bytes.length,
                    new Dimensions(originalWidth, originalHeight, originalWidth, originalHeight),
                    sourcePath,
                    false);
        }

        // 2) 有 alpha 但字节在 API 上限内：白底 flatten 后用 PNG 输出，保持尺寸。
        //    部分 provider 对带 alpha 的 PNG 处理不一致（边缘色重映射差异），
        //    这里统一铺白底再交付。
        if (!overSize && hasAlpha) {
            byte[] flattened = writePng(flattenAlpha(image, originalWidth, originalHeight));
            if (estimateBase64Size(flattened.length) <= API_IMAGE_MAX_BASE64_SIZE) {
                return new ProcessedImage(
                        Base64.getEncoder().encodeToString(flattened),
                        "image/png",
                        bytes.length,
                        flattened.length,
                        new Dimensions(originalWidth, originalHeight, originalWidth, originalHeight),
                        sourcePath,
                        true);
            }
            // alpha flatten 后还是过大 → 落到 3) 走 resize
        }

        // 3) 原始 / flatten 后仍 > 5MB：等比缩放进 IMAGE_MAX_WIDTH × IMAGE_MAX_HEIGHT，
        //    优先尝试 PNG（lossless），过大才退到 JPEG 多档质量兜底。
        ResizeSize target = fitWithin(originalWidth, originalHeight, IMAGE_MAX_WIDTH, IMAGE_MAX_HEIGHT);
        BufferedImage resized = resize(image, target.width(), target.height());

        byte[] resizedPng = writePng(resized);
        if (estimateBase64Size(resizedPng.length) <= API_IMAGE_MAX_BASE64_SIZE) {
            return new ProcessedImage(
                    Base64.getEncoder().encodeToString(resizedPng),
                    "image/png",
                    bytes.length,
                    resizedPng.length,
                    new Dimensions(originalWidth, originalHeight, target.width(), target.height()),
                    sourcePath,
                    true);
        }

        byte[] encoded = null;
        for (float quality : JPEG_QUALITIES) {
            byte[] candidate = writeJpeg(resized, quality);
            if (estimateBase64Size(candidate.length) <= API_IMAGE_MAX_BASE64_SIZE) {
                encoded = candidate;
                break;
            }
        }
        if (encoded == null && (target.width() > 512 || target.height() > 512)) {
            ResizeSize smaller = fitWithin(originalWidth, originalHeight, 1200, 1200);
            resized = resize(image, smaller.width(), smaller.height());
            target = smaller;
            for (float quality : JPEG_QUALITIES) {
                byte[] candidate = writeJpeg(resized, quality);
                if (estimateBase64Size(candidate.length) <= API_IMAGE_MAX_BASE64_SIZE) {
                    encoded = candidate;
                    break;
                }
            }
        }
        if (encoded == null) {
            throw new IOException("图片压缩后仍超过 5MB API 上限");
        }

        return new ProcessedImage(
                Base64.getEncoder().encodeToString(encoded),
                "image/jpeg",
                bytes.length,
                encoded.length,
                new Dimensions(originalWidth, originalHeight, target.width(), target.height()),
                sourcePath,
                true);
    }

    public static String createMetadataText(ProcessedImage image) {
        if (image == null) {
            return null;
        }
        Dimensions dims = image.dimensions();
        if (dims == null) {
            return image.sourcePath() == null ? null : "[Image source: " + image.sourcePath() + "]";
        }
        boolean wasResized = dims.originalWidth() != dims.displayWidth()
                || dims.originalHeight() != dims.displayHeight();
        boolean hasSource = image.sourcePath() != null;
        if (!hasSource && !wasResized && !image.reencoded()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[Image: ");
        boolean needsComma = false;
        if (hasSource) {
            sb.append("source: ").append(image.sourcePath());
            needsComma = true;
        }
        if (wasResized) {
            if (needsComma) {
                sb.append(", ");
            }
            double scale = (double) dims.originalWidth() / Math.max(1, dims.displayWidth());
            sb.append("original ")
                    .append(dims.originalWidth()).append("x").append(dims.originalHeight())
                    .append(", displayed at ")
                    .append(dims.displayWidth()).append("x").append(dims.displayHeight())
                    .append(". Multiply coordinates by ")
                    .append(String.format(Locale.ROOT, "%.2f", scale))
                    .append(" to map to original image.");
        } else if (image.reencoded()) {
            if (needsComma) {
                sb.append(", ");
            }
            sb.append("re-encoded for API size limit without changing dimensions.");
        }
        sb.append("]");
        return sb.toString();
    }

    public static LlmClient.ContentPart toContentPart(ProcessedImage image) {
        return LlmClient.ContentPart.imageBase64(image.base64(), image.mimeType());
    }

    private static BufferedImage tryRead(byte[] bytes) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(in);
        }
    }

    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "image/png";
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(normalized)) {
            return "image/jpeg";
        }
        return normalized;
    }

    private static long estimateBase64Size(long rawBytes) {
        return ((rawBytes + 2L) / 3L) * 4L;
    }

    private static long maxSourceBase64Length() {
        return estimateBase64Size(MAX_SOURCE_IMAGE_BYTES);
    }

    private static ResizeSize fitWithin(int width, int height, int maxWidth, int maxHeight) {
        double scale = Math.min(1.0d, Math.min((double) maxWidth / width, (double) maxHeight / height));
        return new ResizeSize(
                Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale)));
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static BufferedImage flattenAlpha(BufferedImage image, int width, int height) {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static byte[] writePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }

    private static byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("当前运行时缺少 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(imageOut);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            imageOut.flush();
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private record ResizeSize(int width, int height) {}

    public record Dimensions(int originalWidth, int originalHeight, int displayWidth, int displayHeight) {}

    public record ProcessedImage(String base64, String mimeType, long originalBytes, long outputBytes,
                                 Dimensions dimensions, Path sourcePath, boolean reencoded) {}
}
