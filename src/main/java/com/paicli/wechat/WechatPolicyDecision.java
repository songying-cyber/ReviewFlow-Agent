package com.paicli.wechat;

public record WechatPolicyDecision(boolean allowed, String reason) {
    public static WechatPolicyDecision allow() {
        return new WechatPolicyDecision(true, "");
    }

    public static WechatPolicyDecision deny(String reason) {
        return new WechatPolicyDecision(false, reason == null || reason.isBlank() ? "微信通道策略拒绝" : reason);
    }
}
