package com.paicli.wechat;

import java.util.List;

public record WechatUpdate(
        int ret,
        String errMsg,
        String nextSyncBuf,
        Long nextLongPollTimeoutMs,
        List<WechatMessage> messages
) {
    public WechatUpdate {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
