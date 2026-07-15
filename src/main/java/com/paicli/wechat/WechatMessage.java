package com.paicli.wechat;

import java.util.List;

public record WechatMessage(
        String messageId,
        String fromUserId,
        String contextToken,
        String text,
        List<WechatMediaItem> mediaItems
) {
    public WechatMessage {
        mediaItems = mediaItems == null ? List.of() : List.copyOf(mediaItems);
    }
}
