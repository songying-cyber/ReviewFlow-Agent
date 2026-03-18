package com.paicli.util;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * jieba-analysis 在首次加载词典时会直接向 stdout 打印初始化信息。
 * 这里在构造分词器时临时静默标准输出，避免污染 CLI 用户界面。
 */
public final class JiebaSegmenterFactory {
    private JiebaSegmenterFactory() {
    }

    public static JiebaSegmenter createSilently() {
        synchronized (JiebaSegmenterFactory.class) {
            PrintStream originalOut = System.out;
            try {
                System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
                return new JiebaSegmenter();
            } finally {
                System.setOut(originalOut);
            }
        }
    }
}
