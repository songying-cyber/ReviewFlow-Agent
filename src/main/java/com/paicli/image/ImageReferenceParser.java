package com.paicli.image;

import com.paicli.llm.LlmClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageReferenceParser {
    // 同时识别 @image:<path> / @image:path 和 @clipboard。命中 image 时 group(1) 为路径；
    // 命中 @clipboard 时 group(1) 为 null，loadImage 走系统剪贴板抓图分支。
    //
    // 裸路径分支用负字符类显式排除 CJK 符号 / 全角标点（U+3000–U+303F、U+FF00–U+FFEF）和
    // 通用标点（U+2010–U+206F，含 — … 等），这样 "@image:./shot.png。这是什么" 不会把全角句号
    // 吞进路径。ASCII 句号 / 逗号仍然算路径字符，需要这种排版的用户用 @image:<...> 显式包路径。
    //
    // @clipboard 用 Unicode 类 (?![\p{L}\p{N}_]) 兜 word boundary，避免 "@clipboardfoo" 误命中。
    private static final Pattern IMAGE_REF = Pattern.compile(
            "@image:(<[^>]+>|[^\\s<>\\u2010-\\u206F\\u3000-\\u303F\\uFF00-\\uFFEF]+)"
                    + "|@clipboard(?![\\p{L}\\p{N}_])");
    private static final String CLIPBOARD_TOKEN = "@clipboard";
    public static final long MAX_IMAGE_BYTES = ImageProcessor.API_IMAGE_MAX_BASE64_SIZE;

    private ImageReferenceParser() {}

    public static LlmClient.Message userMessage(String input, Path baseDir) {
        String rawInput = input == null ? "" : input;
        List<ImageRef> refs = findRefs(rawInput);
        if (refs.isEmpty()) {
            return LlmClient.Message.user(rawInput);
        }

        String textWithoutRefs = stripRefs(rawInput).trim();
        if (textWithoutRefs.isBlank()) {
            textWithoutRefs = "请分析以下图片。";
        }

        List<String> notes = new ArrayList<>();
        List<LlmClient.ContentPart> imageParts = new ArrayList<>();

        for (ImageRef ref : refs) {
            ImagePayload payload = loadImage(ref.value(), baseDir);
            String displayLabel = displayLabelFor(ref, payload);
            if (!payload.ok()) {
                notes.add("[图片引用无效: " + displayLabel + "，原因: " + payload.error() + "]");
                continue;
            }
            imageParts.add(ImageProcessor.toContentPart(payload.image()));
            String metadataText = ImageProcessor.createMetadataText(payload.image());
            if (metadataText != null) {
                notes.add(metadataText);
            }
        }

        List<LlmClient.ContentPart> parts = new ArrayList<>();
        StringBuilder textPart = new StringBuilder(textWithoutRefs)
                .append("\n\n")
                .append("[图片已作为图片附件附加。请直接观察本轮图片内容；除非用户明确要求结合历史，历史对话、历史工具结果、网页/仓库信息都只能作为背景，不能替代当前图片内容；如果当前图片与历史上下文冲突，以当前图片为准。不要调用 MCP、文件系统或浏览器工具重新读取 Image source；如果你无法直接观察附件，请明确说明无法看图，不要根据路径或历史上下文猜测。]");
        if (!notes.isEmpty()) {
            textPart.append("\n").append(String.join("\n", notes));
        }
        parts.add(LlmClient.ContentPart.text(textPart.toString()));
        parts.addAll(imageParts);

        boolean hasImage = parts.stream().anyMatch(LlmClient.ContentPart::isImage);
        if (hasImage) {
            return LlmClient.Message.user(parts);
        }
        return LlmClient.Message.user(textWithoutRefs + "\n\n" + String.join("\n", notes));
    }

    private static List<ImageRef> findRefs(String input) {
        Matcher matcher = IMAGE_REF.matcher(input);
        List<ImageRef> refs = new ArrayList<>();
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (raw == null) {
                refs.add(new ImageRef(CLIPBOARD_TOKEN));
                continue;
            }
            if (raw.startsWith("<") && raw.endsWith(">")) {
                raw = raw.substring(1, raw.length() - 1);
            }
            refs.add(new ImageRef(raw));
        }
        return refs;
    }

    private static String stripRefs(String input) {
        return IMAGE_REF.matcher(input).replaceAll("").replaceAll("[ \\t]+\\n", "\n").trim();
    }

    private static ImagePayload loadImage(String rawPath, Path baseDir) {
        try {
            Path resolvedPath = null;
            if (CLIPBOARD_TOKEN.equals(rawPath)) {
                ClipboardImage.GrabResult grab = ClipboardImage.grab();
                if (!grab.ok()) {
                    return ImagePayload.error(grab.error());
                }
                resolvedPath = grab.path();
                rawPath = resolvedPath.toString();
            }
            Path path = resolvedPath != null ? resolvedPath : resolvePath(rawPath, baseDir);
            if (!Files.exists(path)) {
                return ImagePayload.error("文件不存在");
            }
            if (!Files.isRegularFile(path)) {
                return ImagePayload.error("不是普通文件");
            }
            String mimeType = detectMimeType(path);
            if (!mimeType.startsWith("image/")) {
                return ImagePayload.error("不是受支持的图片 MIME 类型: " + mimeType);
            }
            ImageProcessor.ProcessedImage image = ImageProcessor.fromPath(path, mimeType);
            return ImagePayload.ok(image);
        } catch (Exception e) {
            return ImagePayload.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String displayLabelFor(ImageRef ref, ImagePayload payload) {
        if (CLIPBOARD_TOKEN.equals(ref.value())) {
            if (payload.ok() && payload.path() != null) {
                return "剪贴板 (" + payload.path().getFileName() + ")";
            }
            return "剪贴板";
        }
        return ref.value();
    }

    private static Path resolvePath(String rawPath, Path baseDir) {
        String value = rawPath == null ? "" : rawPath.trim();
        if (value.startsWith("file://")) {
            return Path.of(fileUriToLocalPath(value)).normalize();
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            Path root = baseDir == null ? Path.of(System.getProperty("user.dir")) : baseDir;
            path = root.resolve(path);
        }
        return path.normalize();
    }

    // file:// 路径自己处理：URI.create 对未编码空格和非 ASCII 会抛 IllegalArgumentException，
    // 而 Claude Code / Cursor 给的引用经常是裸路径或 macOS Finder 拷贝出的 file:// + 中文。
    // 这里只关心拿到本地路径字符串，所以自己做一次宽容的 percent-decode：合法的 %XX 解码，
    // 其他字符（包括空格、中文、未编码字节）原样保留。
    private static String fileUriToLocalPath(String value) {
        String afterScheme = value.substring("file://".length());
        String pathPart;
        if (afterScheme.startsWith("/")) {
            pathPart = afterScheme;
        } else {
            int slashIdx = afterScheme.indexOf('/');
            pathPart = slashIdx < 0 ? "/" + afterScheme : afterScheme.substring(slashIdx);
        }
        return percentDecodeUtf8(pathPart);
    }

    private static String percentDecodeUtf8(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.writeBytes(bytes);
            i++;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String detectMimeType(Path path) throws IOException {
        String probed = Files.probeContentType(path);
        if (probed != null && !probed.isBlank()) {
            return probed;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private record ImageRef(String value) {}

    private record ImagePayload(boolean ok, ImageProcessor.ProcessedImage image, String error) {
        Path path() {
            return image == null ? null : image.sourcePath();
        }

        static ImagePayload ok(ImageProcessor.ProcessedImage image) {
            return new ImagePayload(true, image, null);
        }

        static ImagePayload error(String error) {
            return new ImagePayload(false, null, error);
        }
    }
}
