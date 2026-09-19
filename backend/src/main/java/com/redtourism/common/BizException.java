package com.redtourism.common;

/**
 * 业务异常：携带 HTTP 风格状态码，由全局异常处理器转换为统一响应。
 */
public class BizException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int code;

    public BizException(String message) {
        this(500, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
