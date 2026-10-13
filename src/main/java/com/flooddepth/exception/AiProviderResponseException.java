package com.flooddepth.exception;

public class AiProviderResponseException extends RuntimeException {

    public AiProviderResponseException(String message) {
        super(message);
    }

    public AiProviderResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
