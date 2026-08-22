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

    // Dung cho co che "trung ten -> ghi de" (createNote/rename, xem NoteServiceImpl):
    // can note THAT SU (khong chi boolean) de ghi de noi dung len note dang co san.
    Optional<Note> findByUserIdAndDisplayNameAndDeletedFalse(Long userId, String displayName);

    Optional<Note> findByUuid(String uuid);

    // Dung cho luong PULL (Drive -> local, xem DriveSyncServiceImpl.pullFromDrive):
    // doi chieu file tren Drive voi note local qua drive_file_id - KHONG BAO GIO
    // doi chieu qua display_name (Drive cho phep trung ten).
    //
    // TRA VE LIST chu KHONG PHAI Optional: drive_file_id KHONG co rang buoc UNIQUE
    // trong DB va thuc te DA co du lieu 2 note khac nhau cung tro toi 1 file Drive
    // (bug thuc te 2026-08-22: note 84 va 85 cung giu "1JDHLzJa..."). Khi do ban
    // Optional cu nem IncorrectResultSizeDataAccessException, va vi no bi nem o
    // vong lap removedFileIds (khong nam trong try/catch nao) nen lam SAP toan bo
    // POST /api/drive/sync-all -> 500. Nghiem trong hon: page token chi duoc luu
    // O CUOI incrementalPull() nen no KHONG BAO GIO tien len duoc, khien loi lap
    // lai VINH VIEN o moi lan bam "Dong bo ngay".
    //
    // LOC THEO userId la BAT BUOC, khong phai toi uu: 1 file Drive co the duoc
    // tro toi boi note cua NHIEU user khac nhau (VD 2 tai khoan cung nhin thay
    // 1 thu muc duoc chia se - bug thuc te 2026-08-22). Ban khong loc userId
    // cho phep luot pull cua user nay SUA THANG vao note cua user khac.
    List<Note> findAllByUserIdAndDriveFileId(Long userId, String driveFileId);

    // Dung cho job nen dong bo Drive: lay cac note dang cho sync, gioi han so lan retry
    List<Note> findTop50BySyncStateInAndDriveSyncAttemptsLessThanOrderByUpdatedAtAsc(
            List<SyncState> states, Integer maxAttempts);

    // Dung cho purge job (SEC-12): lay cac note DA soft-delete VA da qua han giu lai
    // (deletedAt truoc "threshold"). Gioi han Top100/lan chay, giong pattern da dung
    // o job dong bo Drive - tranh 1 lan quet load qua nhieu row cung luc.
    List<Note> findTop100ByDeletedTrueAndDeletedAtBefore(LocalDateTime threshold);

    // ---------- Debounce Sync (dirty flag) ----------

    // Job dinh ky quet CHU DONG: note dang dirty VA da "yen tinh" (khong sua
    // gi them) du lau (updatedAt truoc "idleBefore") - day chinh la buoc
    // "sau 30 giay khong co thay doi" trong luong Debounce Sync.
    List<Note> findTop50ByDirtyTrueAndDeletedFalseAndUpdatedAtBefore(LocalDateTime idleBefore);

    // Flush THU CONG (bam nut "Dong bo ngay" HOAC luc dong tab/roi trang) -
    // lay TOAN BO note dirty cua 1 user, BO QUA nguong 30s (nguoi dung/trinh
    // duyet da chu dong yeu cau dong bo NGAY, khong can doi them).
    List<Note> findByDirtyTrueAndDeletedFalseAndUserId(Long userId);

    // Dung cho bootstrapPull() (lan pull DAU TIEN, quet toan bo folder): doi
    // chieu note nao dang GIU driveFileId nhung KHONG con xuat hien trong lan
    // quet nay nua (VD bi xoa truc tiep tren Drive TRUOC KHI ket noi/bootstrap) -
    // xem DriveSyncServiceImpl.
    List<Note> findByUserIdAndDeletedFalseAndDriveFileIdIsNotNull(Long userId);
}
