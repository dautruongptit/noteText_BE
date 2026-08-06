package com.noted.backend.service;

public interface DriveSyncService {

    /** Dam bao user co folder rieng tren Drive (tao neu chua co), tra ve folder id */
    String ensureAppFolder(Long userId);

    /** Day 1 note len Drive (tao moi neu chua co drive_file_id, update neu da co) */
    void syncNote(Long noteId);

    /**
     * "Debounce Sync" - job dinh ky (quet thuong xuyen, VD moi 5s) tim cac
     * note dang dirty=true VA da "yen tinh" (khong sua gi them) qua 1
     * khoang thoi gian (mac dinh 30s, xem app.drive.sync-debounce-idle-ms) -
     * day chinh la kenh "sau 30 giay khong co thay doi" trong 3 kenh kich
     * hoat sync da thong nhat.
     */
    void runDebouncedSyncBatch();

    /**
     * Flush THU CONG: day NGAY LAP TUC toan bo note dirty=true cua 1 user,
     * BO QUA nguong 30s debounce (nguoi dung/trinh duyet da chu dong yeu cau
     * dong bo ngay). Dung cho 2 kenh con lai:
     *  - Nguoi dung bam "Dong bo ngay" (DriveController.syncAll())
     *  - Trinh duyet flush luc dong tab/roi trang (cung goi endpoint tren,
     *    qua fetch(..., {keepalive:true}) o frontend)
     */
    void flushDirtyNotes(Long userId);

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
