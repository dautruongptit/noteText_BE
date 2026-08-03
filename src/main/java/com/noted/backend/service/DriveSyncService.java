package com.noted.backend.service;

public interface DriveSyncService {

    /** Dam bao user co folder rieng tren Drive (tao neu chua co), tra ve folder id */
    String ensureAppFolder(Long userId);

    /** Day 1 note len Drive (tao moi neu chua co drive_file_id, update neu da co) */
    void syncNote(Long noteId);

    /** Job nen: quet cac note dang PENDING_DRIVE / DRIVE_FAILED (chua vuot nguong retry) va sync */
    void runPendingSyncBatch();

    void disconnect(Long userId);

    /**
     * Xoa file tren Google Drive tuong ung voi note (neu co). Goi TRUOC KHI
     * xoa vinh vien note o NotePurgeServiceImpl (SEC-12), tranh de file
     * "mo coi" mai mai tren tai khoan Drive cua nguoi dung sau khi note da
     * bi purge khoi he thong Noted.
     *
     * BEST-EFFORT: KHONG duoc throw exception ra ngoai du Drive co loi gi
     * (token het han, file da bi nguoi dung tu xoa tren Drive tu truoc, mat
     * ket noi...) - viec purge note khoi he thong Noted la thao tac CHINH,
     * phai tiep tuc thanh cong du buoc don dep Drive nay that bai.
     */
    void deleteFromDrive(Long noteId);
}
