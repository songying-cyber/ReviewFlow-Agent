package com.paicli.wechat;

public record WechatLoginResult(
        boolean connected,
        boolean expired,
        String status,
        String token,
        String accountId,
        String baseUrl,
        String userId,
        String message
) {
}
