package com.noted.backend.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateFileNameException.class)
    public ResponseEntity<?> handleDuplicateName(DuplicateFileNameException ex) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_FILE_NAME", ex.getMessage());
    }

    @ExceptionHandler(NoteNotFoundException.class)
    public ResponseEntity<?> handleNotFound(NoteNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOTE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<?> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", ex.getMessage());
    }

    @ExceptionHandler(DriveTokenExchangeException.class)
    public ResponseEntity<?> handleDriveTokenExchange(DriveTokenExchangeException ex) {
        return build(HttpStatus.BAD_GATEWAY, "DRIVE_TOKEN_EXCHANGE_FAILED", ex.getMessage());
    }

    @ExceptionHandler(GoogleDriveOperationException.class)
    public ResponseEntity<?> handleGoogleDriveOperation(GoogleDriveOperationException ex) {
        return build(HttpStatus.BAD_GATEWAY, "GOOGLE_DRIVE_OPERATION_FAILED", ex.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<?> handleRateLimit(RateLimitExceededException ex) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", ex.getMessage());
    }

    @ExceptionHandler(FileStorageException.class)
    public ResponseEntity<?> handleStorage(FileStorageException ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<?> handleValidation(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneric(Exception ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Da co loi xay ra, vui long thu lai");
    }

    private ResponseEntity<?> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "code", code,
                "message", message
        ));
    }
}
