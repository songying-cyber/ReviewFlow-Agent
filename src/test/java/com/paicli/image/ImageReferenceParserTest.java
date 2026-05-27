package com.paicli.image;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageReferenceParserTest {

    @Test
    void attachesLocalImageAsImageAttachment(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("shot.png");
        Files.write(image, new byte[]{1, 2, 3});

        LlmClient.Message message = ImageReferenceParser.userMessage(
                "分析一下 @image:" + image,
                tempDir);

        assertTrue(message.hasImageContent());
        assertEquals("image_base64", message.contentParts().get(1).type());
        assertEquals("image/png", message.contentParts().get(1).mimeType());
        assertTrue(message.content().contains("已附加"));
        assertTrue(message.content().contains("请直接观察本轮图片内容"));
        assertTrue(message.content().contains("历史对话、历史工具结果、网页/仓库信息都只能作为背景"));
        assertTrue(message.content().contains("如果当前图片与历史上下文冲突，以当前图片为准"));
        assertTrue(message.content().contains("不要调用 MCP、文件系统或浏览器工具重新读取 Image source"));
        assertTrue(message.contentParts().get(message.contentParts().size() - 1).isImage(),
                "图片 block 应放在最后，避免后续 source 文本让模型误判只收到路径");
    }

    @Test
    void keepsOversizedLocalImageAsLosslessPngWhenWithinApiLimit(@TempDir Path tempDir) throws Exception {
        // 单色填充的 2600x1200 PNG 远低于 5MB，新策略下应直通 PNG，不再 resize+JPEG。
        Path image = tempDir.resolve("wide.png");
        BufferedImage buffered = new BufferedImage(2600, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = buffered.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, buffered.getWidth(), buffered.getHeight());
            graphics.setColor(Color.BLUE);
            graphics.fillRect(100, 100, 2200, 800);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(buffered, "png", image.toFile());

        LlmClient.Message message = ImageReferenceParser.userMessage(
                "分析一下 @image:" + image,
                tempDir);

        assertTrue(message.hasImageContent());
        LlmClient.ContentPart imagePart = message.contentParts().stream()
                .filter(LlmClient.ContentPart::isImage)
                .findFirst()
                .orElseThrow();
        assertTrue(message.contentParts().get(message.contentParts().size() - 1).isImage(),
                "图片 block 保持最后");
        assertEquals("image/png", imagePart.mimeType(),
                "字节在 5MB 上限内时保持原始 PNG，不再因尺寸超过 2000 强制转 JPEG");
        assertTrue(imagePart.imageBase64().length() <= ImageProcessor.API_IMAGE_MAX_BASE64_SIZE);
    }

    @Test
    void attachesLocalImageWithoutModelCapabilityGate(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("shot.png");
        Files.write(image, new byte[]{1, 2, 3});

        LlmClient.Message message = ImageReferenceParser.userMessage(
                "分析一下 @image:" + image,
                tempDir);

        assertTrue(message.hasImageContent());
        assertEquals("image_base64", message.contentParts().get(1).type());
        assertFalse(message.content().contains("当前模型不声明图片能力"));
    }

    @Test
    void resolvesFileUriWithUnencodedSpaces(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("path with spaces.png");
        Files.write(image, new byte[]{1, 2, 3});

        LlmClient.Message message = ImageReferenceParser.userMessage(
                "看看 @image:<file://" + image + ">",
                tempDir);

        assertTrue(message.hasImageContent(), "未编码空格的 file:// 应能解析");
    }

    @Test
    void resolvesFileUriWithNonAsciiPath(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("中文截图.png");
        Files.write(image, new byte[]{1, 2, 3});

        LlmClient.Message message = ImageReferenceParser.userMessage(
                "看看 @image:<file://" + image + ">",
                tempDir);

        assertTrue(message.hasImageContent(), "非 ASCII 路径的 file:// 应能解析");
    }

    @Test
    void resolvesFileUriWithPercentEncodedPath(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("space here.png");
        Files.write(image, new byte[]{1, 2, 3});

        String encoded = image.toString().replace(" ", "%20");
        LlmClient.Message message = ImageReferenceParser.userMessage(
                "看看 @image:<file://" + encoded + ">",
                tempDir);

        assertTrue(message.hasImageContent(), "%20 编码路径仍应被解码为本地路径");
    }

    @Test
    void barePathStopsAtFullWidthPunctuation(@TempDir Path tempDir) throws Exception {
        // 用例对应 docs/phase-21-image-input-manual-test.md Case 9：
        // "@image:./shot.png。这是什么？"  —— 路径应在全角句号处截断，不再吞掉后面的中文。
        Path image = tempDir.resolve("shot.png");
        Files.write(image, new byte[]{1, 2, 3});

        LlmClient.Message message = ImageReferenceParser.userMessage(
                "帮我看 @image:" + image + "。这个里面是什么？",
                tempDir);

        assertTrue(message.hasImageContent(),
                "全角句号不该被当成路径一部分，导致文件查不到");
        // 文本里依然保留中文标点和提问
        assertTrue(message.content().contains("。这个里面是什么？"),
                "@image: ref 被替换后中文文本应保留：" + message.content());
    }

    @Test
    void clipboardTokenDoesNotMatchWhenAttachedToWord(@TempDir Path tempDir) {
        // "@clipboardfoo" 不应该被识别成 @clipboard，文本应原样保留，也不会触发抓图分支
        LlmClient.Message message = ImageReferenceParser.userMessage(
                "看看 @clipboardfoo 这是个变量名", tempDir);

        assertFalse(message.hasImageContent(), "@clipboardfoo 不应触发抓图");
        assertTrue(message.content().contains("@clipboardfoo"),
                "未命中的 @clipboardfoo 应原样保留：" + message.content());
    }

    @Test
    void clipboardTokenStripsBeforeFullWidthPunctuation(@TempDir Path tempDir) {
        // 全角句号 / 中文问号紧跟 @clipboard 应该正常作为 word boundary：token 被替换，
        // 标点保留在文本里。
        LlmClient.Message message = ImageReferenceParser.userMessage(
                "看看这张 @clipboard。这是什么？", tempDir);

        assertFalse(message.content().contains("@clipboard"),
                "@clipboard 字面量应已被替换：" + message.content());
        assertTrue(message.content().contains("。这是什么？"),
                "标点和后续中文应保留：" + message.content());
    }

    @Test
    void clipboardTokenIsStrippedFromTextRegardlessOfGrabOutcome(@TempDir Path tempDir) {
        // 不依赖剪贴板真实状态：无论 grab 成功还是失败，@clipboard 字面量都不应留在
        // 发给 LLM 的文本里——成功时变 image part，失败时变 [图片引用无效…] 提示。
        LlmClient.Message message = ImageReferenceParser.userMessage(
                "看看这张 @clipboard 图", tempDir);

        assertFalse(message.content().contains("@clipboard"),
                "@clipboard 字面量应已被 strip：" + message.content());
        boolean attached = message.hasImageContent();
        boolean errored = message.content().contains("引用无效");
        assertTrue(attached || errored,
                "@clipboard 应触发抓图分支（成功附加或返回错误提示）：" + message.content());
    }
}
