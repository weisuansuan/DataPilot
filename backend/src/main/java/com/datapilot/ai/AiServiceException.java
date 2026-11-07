package com.datapilot.ai;

public class AiServiceException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String errorCode;

    public AiServiceException(String errorCode) {
        this(errorCode, null);
    }

    public AiServiceException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() { return errorCode; }
}
