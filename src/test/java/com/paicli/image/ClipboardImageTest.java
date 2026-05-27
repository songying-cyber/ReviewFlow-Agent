package com.paicli.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipboardImageTest {

    // 烟雾测试：无论运行环境是 headless 还是 GUI，无论剪贴板里是否有图片，
    // grab() 都不应抛异常，必须返回 GrabResult；失败路径 path 必须为 null、error 必须有内容。
    @Test
    void grabAlwaysReturnsResultAndNeverThrows(@TempDir Path tempDir) {
        ClipboardImage.GrabResult result = ClipboardImage.grab(tempDir);

        assertNotNull(result);
        if (result.ok()) {
            assertNotNull(result.path(), "ok=true 时 path 必须有值");
            assertNull(result.error(), "ok=true 时 error 必须为 null");
            assertTrue(result.path().toFile().exists(), "成功路径下文件应存在");
        } else {
            assertNull(result.path(), "ok=false 时 path 必须为 null");
            assertNotNull(result.error(), "ok=false 时 error 必须有内容");
            assertFalse(result.error().isBlank());
        }
    }
}
