package com.noted.backend.repository;

import com.noted.backend.domain.entity.Note;
import com.noted.backend.domain.enums.SyncState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NoteRepository extends JpaRepository<Note, Long> {

    List<Note> findByUserIdAndDeletedFalseOrderByUpdatedAtDesc(Long userId);

    Optional<Note> findByIdAndUserIdAndDeletedFalse(Long id, Long userId);

    // Dung cho bulk-delete: lay tat ca note thuoc so huu cua user trong danh sach id gui len,
    // TU DONG loai bo id nao khong thuoc user nay -> chan truong hop 1 user co tinh gui id
    // cua nguoi khac de xoa nham
    List<Note> findByIdInAndUserIdAndDeletedFalse(List<Long> ids, Long userId);

    boolean existsByUserIdAndDisplayNameAndDeletedFalse(Long userId, String displayName);

    Optional<Note> findByUuid(String uuid);

    // Dung cho job nen dong bo Drive: lay cac note dang cho sync, gioi han so lan retry
    List<Note> findTop50BySyncStateInAndDriveSyncAttemptsLessThanOrderByUpdatedAtAsc(
            List<SyncState> states, Integer maxAttempts);

    // Dung cho purge job (SEC-12): lay cac note DA soft-delete VA da qua han giu lai
    // (deletedAt truoc "threshold"). Gioi han Top100/lan chay, giong pattern da dung
    // o job dong bo Drive - tranh 1 lan quet load qua nhieu row cung luc.
    List<Note> findTop100ByDeletedTrueAndDeletedAtBefore(LocalDateTime threshold);
}
