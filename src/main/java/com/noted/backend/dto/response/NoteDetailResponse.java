package com.noted.backend.dto.response;

import com.noted.backend.domain.entity.Note;

import java.time.LocalDateTime;

/** Dung khi mo 1 file de vao tab editor - CO kem noi dung */
public record NoteDetailResponse(
        Long id,
        String displayName,
        String content,
        String syncState,
        LocalDateTime updatedAt,
        boolean conflictCopy
) {
    public static NoteDetailResponse of(Note note, String content) {
        return new NoteDetailResponse(
                note.getId(),
                note.getDisplayName(),
                content,
                note.getSyncState().name(),
                note.getUpdatedAt(),
                note.isConflictCopy()
        );
    }
}
