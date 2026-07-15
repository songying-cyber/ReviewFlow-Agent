package com.paicli.wechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalQrRendererTest {
    @Test
    void rendersQrAsAnsiBlocks() {
        String rendered = TerminalQrRenderer.renderAnsi("https://liteapp.weixin.qq.com/q/test?qrcode=abc");

        assertTrue(rendered.contains("\u001B[40m"));
        assertTrue(rendered.contains("\u001B[107m"));
        assertTrue(rendered.contains("▀"));
        assertFalse(rendered.contains("https://liteapp.weixin.qq.com"));
    }

    @Test
    void rendersInlinePngAtExpectedSize() {
        String rendered = TerminalQrRenderer.renderInlinePng(
                "https://liteapp.weixin.qq.com/q/test?qrcode=abc",
                TerminalQrRenderer.DEFAULT_IMAGE_SIZE_PX);

        assertTrue(rendered.startsWith("\u001B]1337;File=inline=1;width=260px;height=260px;"));
        assertFalse(rendered.contains("https://liteapp.weixin.qq.com"));
    }

    @Test
    void rendersPngBytes() throws Exception {
        byte[] png = TerminalQrRenderer.renderPng(
                "https://liteapp.weixin.qq.com/q/test?qrcode=abc",
                TerminalQrRenderer.DEFAULT_IMAGE_SIZE_PX);

        assertTrue(png.length > 100);
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
    }
}
