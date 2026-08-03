package com.noted.backend.exception;

public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String reason) {
        super("Refresh token khong hop le: " + reason);
    }
}
