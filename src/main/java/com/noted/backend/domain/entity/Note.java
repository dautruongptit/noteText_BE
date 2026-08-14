package com.noted.backend.domain.entity;

import com.noted.backend.domain.enums.SyncState;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Note chi luu METADATA trong DB. Noi dung that nam tren filesystem cua Ubuntu server,
 * tai duong dan {filePath}, ten vat ly tren disk = uuid + ".txt" (khong dung display_name
 * lam ten file that -> tranh path traversal / ky tu dac biet / conflict khi rename).
 */
// LUU Y: KHONG (con) unique constraint tren (user_id, display_name) - xem
// V4__drop_notes_unique_name.sql. Google Drive cho phep trung ten trong 1
// folder, dong bo 2 chieu CHI dung drive_file_id/uuid de doi chieu, KHONG
// BAO GIO dung display_name - display_name gio chi la nhan hien thi (UI).
@Entity
@Table(name = "notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    @Builder.Default
    private String uuid = UUID.randomUUID().toString();

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "file_path", nullable = false, length = 512)
    private String filePath;

    @Column(name = "content_size_bytes", nullable = false)
    @Builder.Default
    private Long contentSizeBytes = 0L;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_state", nullable = false, length = 20)
    @Builder.Default
    private SyncState syncState = SyncState.PENDING_DRIVE;

    @Column(name = "drive_file_id")
    private String driveFileId;

    @Column(name = "drive_synced_at")
    private LocalDateTime driveSyncedAt;

    @Column(name = "drive_sync_attempts", nullable = false)
    @Builder.Default
    private Integer driveSyncAttempts = 0;

    @Column(name = "drive_sync_error", length = 512)
    private String driveSyncError;

    /**
     * Co thay doi CHUA duoc day len Drive hay khong. Tach rieng khoi
     * "syncState" (von la enum mo ta trang thai HIEN THI cho UI: SYNCED/
     * PENDING_DRIVE/DRIVE_FAILED/CONFLICT) - "dirty" la co CHUAN dung cho
     * batch job debounce sync quyet dinh note nao can day len Drive, dua
     * tren "updatedAt" (thoi diem sua GAN NHAT) de tinh "da yen tinh du 30s
     * chua". Bat len true moi khi noi dung/ten thay doi, tat ve false SAU
     * KHI da sync THANH CONG len Drive (khong tat neu sync that bai - de
     * lan sau con retry).
     */
    @Column(name = "is_dirty", nullable = false)
    @Builder.Default
    private boolean dirty = true;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    /**
     * TRUE neu day la "ban sao xung dot" duoc tach ra khi dong bo phat hien 2
     * ban khac nhau cua CUNG 1 noi dung (xem NoteServiceImpl.createConflictCopy,
     * SyncController.syncBatch) - thay vi am tham bo/ghi de 1 trong 2 ban,
     * he thong giu CA HAI: ban "thang" (server dang co) giu nguyen id/uuid
     * cu, ban "thua" tro thanh 1 note MOI rieng biet voi co nay = TRUE, ten
     * co hau to "(xung dot ...)" - nguoi dung tu kiem tra/gop lai neu can.
     */
    @Column(name = "is_conflict_copy", nullable = false)
    @Builder.Default
    private boolean conflictCopy = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

}
