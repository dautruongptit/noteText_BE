package com.noted.backend.event;

/**
 * Phat sinh moi khi 1 note duoc tao/sua/duplicate thanh cong.
 *
 * Day la co che "sync theo su kien" thay the cho viec chi dua vao job dinh ky
 * (@Scheduled 30s): ngay khi nguoi dung luu xong, event nay kich hoat dong bo
 * Drive GAN NHU tuc thi, khong can doi den lan quet dinh ky tiep theo.
 *
 * Job dinh ky (DriveSyncServiceImpl.runPendingSyncBatch) van duoc GIU LAI song song,
 * dong vai tro "luoi an toan du phong" cho cac truong hop event bi lo (VD server
 * restart dung luc, hoac lan sync dau tien bi loi mang).
 */
public record NoteContentChangedEvent(Long noteId) {}
