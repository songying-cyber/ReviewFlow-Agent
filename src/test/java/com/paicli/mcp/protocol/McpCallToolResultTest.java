package com.paicli.mcp.protocol;

import com.paicli.tool.ToolOutput;
import com.paicli.image.ImageReferenceParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpCallToolResultTest {

    @Test
    void smallImageBecomesImagePart() {
        String base64 = "aGVsbG8=";
        McpCallToolResult result = new McpCallToolResult(
                List.of(new McpContent("image", null, base64, "image/png")),
                false);

        ToolOutput output = result.toToolOutput();

        assertTrue(output.hasImageParts(), "小图片应进入 imageParts");
        assertEquals(1, output.imageParts().size());
        assertEquals(base64, output.imageParts().get(0).imageBase64());
        assertTrue(output.text().contains("base64Length=" + base64.length()));
        assertFalse(output.text().contains("超过"));
    }

    @Test
    void oversizedImageFallsBackToTextOnly() {
        int approxBytes = (int) (ImageReferenceParser.MAX_IMAGE_BYTES + 1024);
        int base64Length = (approxBytes * 4 / 3) + 4;
        StringBuilder sb = new StringBuilder(base64Length);
        for (int i = 0; i < base64Length; i++) {
            sb.append('A');
        }

        McpCallToolResult result = new McpCallToolResult(
                List.of(new McpContent("image", null, sb.toString(), "image/png")),
                false);

        ToolOutput output = result.toToolOutput();

        assertFalse(output.hasImageParts(), "超过上限的图片不应进入 imageParts");
        assertTrue(output.text().contains("超过"));
        assertTrue(output.text().contains("take_snapshot"));
    }

    @Test
    void emptyImageDataKeepsFallbackOnly() {
        McpCallToolResult result = new McpCallToolResult(
                List.of(new McpContent("image", null, "", "image/png")),
                false);

        ToolOutput output = result.toToolOutput();

        assertFalse(output.hasImageParts());
        assertTrue(output.text().contains("base64Length=0"));
    }
}
