package com.paicli.policy;

/**
 * 安全策略拦截时抛出。
 *
 * 调用方通常在工具执行体里 catch 后转成用户可见的错误字符串。
 * 策略拦截相当于 LLM 的"硬规则失败"，不要让它静默通过，也不要让 LLM 重试同样的违规请求。
 */
public class PolicyException extends RuntimeException {

    public PolicyException(String message) {
        super(message);
    }
}
