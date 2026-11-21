package com.xalgocapital.claude.s14;

/**
 * 一次失败属于哪一类。
 *
 * 分类的唯一标准是「重试有没有意义」，不是「错得多严重」：
 *
 *   TRANSIENT        429 限流、5xx、超时、连接重置、响应体半截
 *                    —— 同一份请求原样再发一次，可能就过了
 *   CONTEXT_OVERFLOW 服务端明确说上下文太长
 *                    —— 原样重试一万次也一样长，得先压缩再发
 *   FATAL            401 / 403 / 404 / 400 参数错
 *                    —— 重试只是在烧钱，必须换路或停下来
 *
 * 这三类如果混成一个 catch，最常见的后果是：401 被当成网络抖动重试四次，
 * 然后报「重试 4 次仍失败」，真正的原因（key 错了）被埋在最里面。
 */
public enum Failure {
    TRANSIENT,
    CONTEXT_OVERFLOW,
    FATAL;

    /**
     * 从 HTTP 状态码 + 响应体判断。
     *
     * 只看状态码不够：上下文超长在很多兼容端点上返回的也是 400，
     * 和「参数写错了」同一个码，必须看 body。
     */
    public static Failure of(int status, String body) {
        String b = body == null ? "" : body.toLowerCase();
        if (b.contains("context_length_exceeded")
                || b.contains("maximum context length")
                || b.contains("too many tokens")
                || b.contains("reduce the length")) {
            return CONTEXT_OVERFLOW;
        }
        if (status == 408 || status == 409 || status == 425 || status == 429 || status >= 500) {
            return TRANSIENT;
        }
        return FATAL;
    }
}
