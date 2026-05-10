package com.botfunnel.common;

import org.springframework.http.HttpStatus;

public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AppException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static AppException badRequest(String message) {
        return new AppException(HttpStatus.BAD_REQUEST, null, message);
    }

    public static AppException unauthorized(String message) {
        return new AppException(HttpStatus.UNAUTHORIZED, null, message);
    }

    public static AppException forbidden(String message) {
        return new AppException(HttpStatus.FORBIDDEN, null, message);
    }

    public static AppException conflict(String message) {
        return new AppException(HttpStatus.CONFLICT, null, message);
    }

    public static AppException conflict(String code, String message) {
        return new AppException(HttpStatus.CONFLICT, code, message);
    }

    public static AppException notFound(String message) {
        return new AppException(HttpStatus.NOT_FOUND, null, message);
    }

    public static AppException unprocessableEntity(String code, String message) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static AppException tooManyRequests(String message) {
        return new AppException(HttpStatus.TOO_MANY_REQUESTS, null, message);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
