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
@Entity
@Table(name = "notes", uniqueConstraints = {
        @UniqueConstraint(name = "uq_notes_user_name", columnNames = {"user_id", "display_name"})
})
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
