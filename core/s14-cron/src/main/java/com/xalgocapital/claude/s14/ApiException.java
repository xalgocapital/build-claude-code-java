package com.xalgocapital.claude.s14;

/** 请求失败的携带信息：不只是「错了」，还要带上「哪一类、能不能重试、服务端让等多久」 */
public class ApiException extends RuntimeException {
    private final Failure failure;
    private final int status;
    private final long retryAfterMs;

    public ApiException(Failure failure, int status, long retryAfterMs, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
        this.status = status;
        this.retryAfterMs = retryAfterMs;
    }

    public Failure failure() {
        return failure;
    }

    public int status() {
        return status;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }
}
