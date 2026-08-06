package com.noted.backend.exception;

public class GoogleDriveOperationException extends RuntimeException {
    public GoogleDriveOperationException(String message, Throwable cause) {
        super(message, cause);
    }

    public GoogleDriveOperationException(String message) {
        super(message);
    }
}
