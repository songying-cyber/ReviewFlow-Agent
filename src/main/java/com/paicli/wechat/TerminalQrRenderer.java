package com.paicli.wechat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class TerminalQrRenderer {
    static final int DEFAULT_IMAGE_SIZE_PX = 260;
    private static final String FG_BLACK = "\u001B[30m";
    private static final String FG_WHITE = "\u001B[97m";
    private static final String BG_BLACK = "\u001B[40m";
    private static final String BG_WHITE = "\u001B[107m";
    private static final String RESET = "\u001B[0m";

    private TerminalQrRenderer() {
    }

    public static void print(PrintStream out, String content) {
        if (out == null || content == null || content.isBlank()) {
            return;
        }
        if (supportsInlineImage()) {
            out.print(renderInlinePng(content, DEFAULT_IMAGE_SIZE_PX));
        } else {
            out.println(renderAnsi(content));
        }
    }

    static String renderAnsi(String content) {
        try {
            BitMatrix matrix = encode(content);
            StringBuilder sb = new StringBuilder();
            for (int y = 0; y < matrix.getHeight(); y += 2) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    boolean top = matrix.get(x, y);
                    boolean bottom = y + 1 < matrix.getHeight() && matrix.get(x, y + 1);
                    sb.append(cell(top, bottom));
                }
                sb.append(RESET).append('\n');
            }
            return sb.toString();
        } catch (WriterException e) {
            return content;
        }
    }

    static String renderInlinePng(String content, int sizePx) {
        try {
            byte[] png = renderPng(content, sizePx);
            String base64 = Base64.getEncoder().encodeToString(png);
            return "\u001B]1337;File=inline=1;width=" + sizePx
                    + "px;height=" + sizePx
                    + "px;preserveAspectRatio=1:" + base64 + "\u0007\n";
        } catch (Exception e) {
            return renderAnsi(content);
        }
    }

    static byte[] renderPng(String content, int sizePx) throws WriterException, IOException {
        int size = Math.max(160, sizePx);
        BitMatrix matrix = encode(content, size);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BitMatrix encode(String content) throws WriterException {
        return encode(content, 1);
    }

    private static BitMatrix encode(String content, int size) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
    }

    private static String cell(boolean top, boolean bottom) {
        return (top ? FG_BLACK : FG_WHITE)
                + (bottom ? BG_BLACK : BG_WHITE)
                + "▀";
    }

    private static boolean supportsInlineImage() {
        String termProgram = env("TERM_PROGRAM").toLowerCase(Locale.ROOT);
        String term = env("TERM").toLowerCase(Locale.ROOT);
        if (termProgram.contains("iterm")
                || termProgram.contains("wezterm")
                || termProgram.contains("warp")
                || term.contains("wezterm")) {
            return true;
        }
        return System.getenv("WEZTERM_EXECUTABLE") != null
                || Boolean.getBoolean("paicli.terminal.inlineImages");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }
}
