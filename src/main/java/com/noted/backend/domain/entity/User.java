package com.noted.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_id", nullable = false, unique = true, length = 64)
    private String googleId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "drive_connected", nullable = false)
    @Builder.Default
    private boolean driveConnected = false;

    @Column(name = "drive_folder_id")
    private String driveFolderId;

    /** Refresh token cua Google OAuth, PHAI duoc ma hoa (AES) truoc khi luu - xem CryptoUtil */
    @Column(name = "drive_refresh_token_enc", columnDefinition = "TEXT")
    private String driveRefreshTokenEnc;

    /**
     * Page token cho Drive Changes API (incremental sync, xem DriveSyncServiceImpl.
     * pullFromDrive()) - NULL nghia la chua tung pull lan nao, se bootstrap
     * bang quet toan bo folder (listFilesInFolder) 1 lan roi moi bat dau
     * theo doi incremental tu do. Reset ve NULL khi disconnect() Drive.
     */
    @Column(name = "drive_changes_page_token")
    private String driveChangesPageToken;

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
