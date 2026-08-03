package com.noted.backend.exception;

public class NoteNotFoundException extends RuntimeException {
    public NoteNotFoundException(Long id) {
        super("Khong tim thay note id=" + id);
    }
}
