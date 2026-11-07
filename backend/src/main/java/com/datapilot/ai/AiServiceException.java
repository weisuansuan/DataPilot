package com.datapilot.ai;

public class AiServiceException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String errorCode;
    private final ProviderFailure providerFailure;

    public AiServiceException(String errorCode) {
        this(errorCode, null, null);
    }

    public AiServiceException(String errorCode, Throwable cause) {
        this(errorCode, cause, null);
    }

    public AiServiceException(String errorCode, ProviderFailure providerFailure) {
        this(errorCode, null, providerFailure);
    }

    private AiServiceException(String errorCode, Throwable cause, ProviderFailure providerFailure) {
        super(errorCode, cause);
        this.errorCode = errorCode;
        this.providerFailure = providerFailure;
    }

    public String errorCode() { return errorCode; }

    public ProviderFailure providerFailure() { return providerFailure; }

    public record ProviderFailure(String category, int status, Long retryAfterSeconds, int attempts,
            Integer remainingRequests, Integer remainingTokens) { }
}
