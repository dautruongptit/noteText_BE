package com.noted.backend.event;

/**
 * Phat sinh khi 1 note bi xoa (soft-delete qua NoteServiceImpl.delete()/
 * bulkDelete()) - dung de kich hoat xoa BAN TREN GOOGLE DRIVE NGAY LAP TUC
 * (khong doi 30 ngay den luc purge job chay, xem NotePurgeServiceImpl).
 *
 * Truoc day chi co NotePurgeServiceImpl goi driveSyncService.deleteFromDrive()
 * luc purge (30 ngay sau) - nguoi dung xoa note nhung file tren Drive van
 * con nguyen suot thoi gian do, gay nham lan "sao xoa trong app roi ma Drive
 * van con file". Event nay dam bao Drive duoc don dep GAN NHU NGAY LAP TUC,
 * NotePurgeServiceImpl van giu nguyen loi goi (best-effort, khong throw) lam
 * luoi an toan du phong cho truong hop event nay bi lo (VD server restart
 * dung luc, deleteFromDrive() that bai tam thoi do mat mang).
 */
public record NoteDeletedEvent(Long noteId) {}
