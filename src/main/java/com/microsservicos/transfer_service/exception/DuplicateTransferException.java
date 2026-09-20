package com.microsservicos.transfer_service.exception;

public class DuplicateTransferException extends RuntimeException {
    public DuplicateTransferException(String message) {
        super(message);
    }
}