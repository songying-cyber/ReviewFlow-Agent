package com.paicli.wechat;

public record WechatMediaItem(
        String type,
        String fileName,
        String mimeType,
        String encryptQueryParam,
        String aesKey
) {
    public boolean isImage() {
        return "image".equalsIgnoreCase(type) || (mimeType != null && mimeType.startsWith("image/"));
    }
}
