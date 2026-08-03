package com.noted.backend.exception;

public class DuplicateFileNameException extends RuntimeException {
    public DuplicateFileNameException(String displayName) {
        super("Ten file da ton tai: " + displayName);
    }
}
