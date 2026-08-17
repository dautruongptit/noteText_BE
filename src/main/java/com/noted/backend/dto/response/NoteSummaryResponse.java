package com.noted.backend.dto.response;

import com.noted.backend.domain.entity.Note;

import java.time.LocalDateTime;

/** Dung cho danh sach sidebar - KHONG kem noi dung file (tranh tai du lieu nang khi list) */
public record NoteSummaryResponse(
        Long id,
        String displayName,
        String syncState,
        LocalDateTime updatedAt,
        boolean conflictCopy,
        int version
) {
    public static NoteSummaryResponse from(Note note) {
        return new NoteSummaryResponse(
                note.getId(),
                note.getDisplayName(),
                note.getSyncState().name(),
                note.getUpdatedAt(),
                note.isConflictCopy(),
                note.getVersion()
        );
    }
}
