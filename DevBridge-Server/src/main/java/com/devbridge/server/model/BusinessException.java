package com.devbridge.server.model;

import org.springframework.http.HttpStatus;

/**
 * 本机设备管理业务异常，集中携带错误码和 HTTP 状态。
 *
 * <p>by AI.Coding</p>
 */
public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;
    private final String detail;

    /**
     * 创建业务异常。
     *
     * @param errorCode 稳定错误码
     * @param message 用户可读错误信息
     * @param httpStatus HTTP 状态
     * @param detail 诊断摘要
     */
    public BusinessException(String errorCode, String message, HttpStatus httpStatus, String detail) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.detail = detail == null ? "" : detail;
    }

    /**
     * 获取稳定错误码。
     *
     * @return 错误码
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * 获取建议返回的 HTTP 状态。
     *
     * @return HTTP 状态
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /**
     * 获取诊断摘要。
     *
     * @return 诊断摘要
     */
    public String getDetail() {
        return detail;
    }
}
