package com.noted.backend.domain.enums;

/**
 * Trang thai dong bo cua 1 note giua: disk (server) <-> DB <-> Google Drive.
 *
 * SYNCED           : da ghi disk thanh cong VA da len Drive thanh cong, hash khop
 * PENDING_DRIVE    : da ghi disk (server) thanh cong, dang cho / cho job day len Drive
 * DRIVE_FAILED     : da thu day Drive nhung loi qua so lan cho phep (sync_attempts vuot nguong)
 * CONFLICT         : phat hien lech du lieu giua client - server - drive, can nguoi dung xu ly
 */
public enum SyncState {
    SYNCED,
    PENDING_DRIVE,
    DRIVE_FAILED,
    CONFLICT
}
