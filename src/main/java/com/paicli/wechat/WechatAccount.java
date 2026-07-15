package com.paicli.wechat;

public record WechatAccount(
        String token,
        String accountId,
        String baseUrl,
        String boundUserId,
        String workspace,
        String syncBuf,
        String createdAt
) {
    public WechatAccount withBoundUserId(String userId) {
        return new WechatAccount(token, accountId, baseUrl, userId, workspace, syncBuf, createdAt);
    }

    public WechatAccount withSyncBuf(String nextSyncBuf) {
        return new WechatAccount(token, accountId, baseUrl, boundUserId, workspace, nextSyncBuf, createdAt);
    }

    public WechatAccount withWorkspace(String nextWorkspace) {
        return new WechatAccount(token, accountId, baseUrl, boundUserId, nextWorkspace, syncBuf, createdAt);
    }
}
