package com.microsservicos.transfer_service.exception;

public class WalletServiceUnavailableException extends RuntimeException {
    public WalletServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}