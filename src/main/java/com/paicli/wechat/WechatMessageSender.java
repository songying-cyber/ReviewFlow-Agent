package com.paicli.wechat;

import java.io.IOException;

@FunctionalInterface
public interface WechatMessageSender {
    void send(String text) throws IOException;
}
