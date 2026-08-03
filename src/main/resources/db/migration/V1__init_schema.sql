-- ==========================================================
-- V1__init_schema.sql
-- Noted App - Initial schema
-- ==========================================================

CREATE TABLE users (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    google_id           VARCHAR(64)  NOT NULL,
    email               VARCHAR(255) NOT NULL,
    full_name           VARCHAR(255),
    avatar_url          VARCHAR(512),

    -- Google Drive integration
    drive_connected      BOOLEAN      NOT NULL DEFAULT FALSE,
    drive_folder_id       VARCHAR(128),
    drive_refresh_token_enc TEXT,        -- ma hoa truoc khi luu (AES)

    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT uq_users_google_id UNIQUE (google_id),
    CONSTRAINT uq_users_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE notes (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    uuid                CHAR(36)     NOT NULL,       -- ten file vat ly tren disk
    user_id             BIGINT       NOT NULL,

    display_name        VARCHAR(255) NOT NULL,       -- ten hien thi, VD "Ban nhap.txt"
    file_path           VARCHAR(512) NOT NULL,       -- duong dan tuong doi tren disk

    content_size_bytes  BIGINT       NOT NULL DEFAULT 0,
    content_hash        VARCHAR(64),                 -- SHA-256, dung de so sanh khi sync

    sync_state          VARCHAR(20)  NOT NULL DEFAULT 'PENDING_DRIVE',
    drive_file_id        VARCHAR(128),
    drive_synced_at       DATETIME,
    drive_sync_attempts    INT          NOT NULL DEFAULT 0,
    drive_sync_error       VARCHAR(512),

    is_deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted_at            DATETIME,

    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_notes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_notes_uuid UNIQUE (uuid),

    -- Chan trung ten file trong pham vi 1 user (chi ap dung cho file chua bi xoa)
    CONSTRAINT uq_notes_user_name UNIQUE (user_id, display_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_notes_user_updated ON notes(user_id, updated_at DESC);
CREATE INDEX idx_notes_sync_state ON notes(sync_state);


CREATE TABLE refresh_tokens (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    token_hash    VARCHAR(128) NOT NULL,
    expires_at    DATETIME     NOT NULL,
    revoked      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
