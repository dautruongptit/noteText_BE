package com.noted.backend.service.impl;

import com.google.api.services.drive.Drive;
import com.noted.backend.domain.entity.Note;
import com.noted.backend.domain.entity.User;
import com.noted.backend.domain.enums.SyncState;
import com.noted.backend.repository.NoteRepository;
import com.noted.backend.repository.UserRepository;
import com.noted.backend.service.DriveSyncService;
import com.noted.backend.service.FileStorageService;
import com.noted.backend.service.GoogleDriveService;
import com.noted.backend.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dieu phoi NGHIEP VU dong bo note len Google Drive - moi thao tac Drive
 * API THAT SU (tao folder, upload, update, xoa...) duoc uy quyen cho
 * GoogleDriveService (lop thuan ky thuat, khong biet gi ve Note/User).
 *
 * ===== "DEBOUNCE SYNC" - kien truc dong bo hien tai (thay the ban cu "sync
 * gan nhu tuc thi moi lan luu") =====
 *
 * Moi lan noi dung/ten note thay doi (NoteServiceImpl.updateContent/rename),
 * note CHI duoc danh dau note.dirty=true VA note.syncState=PENDING_DRIVE -
 * KHONG con kich hoat sync ngay lap tuc nua. Viec THAT SU day len Drive di
 * theo 3 kenh:
 *
 *  1) Debounce dinh ky (runDebouncedSyncBatch, @Scheduled quet moi vai giay):
 *     tim note dirty=true VA da "yen tinh" (khong sua gi them) qua nguong
 *     thoi gian (mac dinh 30s, app.drive.sync-debounce-idle-ms), dua tren
 *     cot "updated_at" co san - KHONG can them cau truc du lieu rieng de
 *     theo doi "lan sua gan nhat" (tan dung lai field da co).
 *  2) Flush thu cong (flushDirtyNotes): nguoi dung bam "Dong bo ngay" - day
 *     TOAN BO note dirty cua RIENG user do NGAY LAP TUC, khong doi 30s.
 *  3) Flush luc dong tab/roi trang: frontend goi CUNG endpoint voi (2) qua
 *     fetch(..., {keepalive:true}) trong su kien 'beforeunload'/'pagehide'.
 *
 * Vi sao doi tu "sync tuc thi" sang debounce: nguoi dung go lien tuc (auto-save
 * DB moi 1-2s) se lam Drive API bi goi QUA NHIEU LAN neu sync ngay moi lan
 * luu (ton quota, cham). Debounce gom nhieu lan sua GAN NHAU thanh 1 lan
 * upload duy nhat len Drive.
 *
 * ===== md5Checksum - toi uu tranh upload thua =====
 * Truoc khi goi updateFile() cho note DA CO driveFileId, so sanh MD5 cua noi
 * dung local (HashUtil.md5) voi "md5Checksum" ma CHINH GOOGLE DRIVE da tinh
 * san cho file do (GoogleDriveService.getFileChecksum) - neu KHOP NHAU (noi
 * dung thuc su khong doi tren Drive, VD note bi sua roi sua lai ve y het cu
 * truoc khi kip debounce), BO QUA update(), tranh goi API + lam modifiedTime
 * tren Drive nhay vo ich.
 *
 * NGUYEN TAC NGHIEP VU (chot lai, quan trong): Google Drive KHONG bat buoc
 * ten file duy nhat trong 1 folder - he thong nay CHO PHEP trung ten tren
 * Drive mot cach binh thuong. Dinh danh DUY NHAT can quan tam CHI LA
 * "driveFileId" (do Google cap phat) - moi note giu CO DINH 1 driveFileId,
 * KHONG BAO GIO tim/doi chieu theo ten de quyet dinh tao moi hay cap nhat.
 *
 * FIX rang buoc dong thoi (tu bao loi thuc te ve file bi double): khoa trong
 * bo nho (syncingNoteIds) chan 2 lenh syncNote() chay dong thoi cho CUNG 1
 * noteId (VD debounce job va flush thu cong trung nhau).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DriveSyncServiceImpl implements DriveSyncService {

    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final GoogleDriveService googleDriveService;

    // Khoa trong bo nho: chan 2 lenh syncNote() chay dong thoi cho CUNG 1
    // noteId. Chi hoat dong dung tren 1 INSTANCE backend duy nhat (dung quy
    // mo hien tai cua du an) - neu sau nay scale nhieu instance, can chuyen
    // sang khoa phan tan (VD Redis SETNX), giong pattern da ghi chu o TokenBucket.java.
    private final Set<Long> syncingNoteIds = ConcurrentHashMap.newKeySet();

    @Value("${app.drive.app-folder-name}")
    private String appFolderName;

    @Value("${app.drive.sync-retry-max-attempts}")
    private int maxAttempts;

    @Value("${app.drive.sync-debounce-idle-ms}")
    private long debounceIdleMs;

    @Override
    @Transactional
    public String ensureAppFolder(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        if (user.getDriveFolderId() != null) {
            return user.getDriveFolderId();
        }

        Drive drive = googleDriveService.buildClient(user);

        // QUY TRINH TAO FOLDER (theo dung 4 buoc yeu cau):
        // 1. Validate du lieu -> lam trong GoogleDriveService.createFolder()
        // 2. Kiem tra folder da ton tai chua (tranh tao trung moi lan ensureAppFolder chay)
        // 3. Neu chua co, goi GoogleDriveService tao folder -> nhan googleFolderId
        // 4. Luu googleFolderId vao DB (User.driveFolderId)
        String folderId = googleDriveService.ensureFolder(drive, appFolderName);

        user.setDriveFolderId(folderId);
        user.setDriveConnected(true);
        userRepository.save(user);

        return folderId;
    }

    @Override
    @Async
    @Transactional
    public void syncNote(Long noteId) {
        // Chan 2 lenh syncNote() chay dong thoi cho CUNG 1 noteId. Lenh dang
        // chay se tu cap nhat trang thai dung (SYNCED/DRIVE_FAILED); neu
        // lenh bi bo qua nay THAT SU can thiet, debounce job se tu bat lai
        // o lan quet ke tiep vi note van con dirty=true.
        if (!syncingNoteIds.add(noteId)) {
            log.debug("Note {} dang duoc dong bo boi 1 luot goi khac, bo qua lenh trung lap nay", noteId);
            return;
        }

        try {
            syncNoteInternal(noteId);
        } finally {
            syncingNoteIds.remove(noteId);
        }
    }

    private void syncNoteInternal(Long noteId) {
        Note note = noteRepository.findById(noteId).orElse(null);
        if (note == null || note.isDeleted()) return;

        try {
            User user = userRepository.findById(note.getUserId()).orElseThrow();
            if (!user.isDriveConnected()) {
                return; // nguoi dung chua ket noi Drive, khong co gi de lam
            }

            String folderId = ensureAppFolder(user.getId());
            Drive drive = googleDriveService.buildClient(user);
            String content = fileStorageService.read(note.getFilePath());

            if (note.getDriveFileId() == null) {
                // CHIEU NGUOC LAI (upload file), dung 4 buoc yeu cau:
                // 1. Kiem tra folder ton tai -> da co folderId tu ensureAppFolder() o tren
                // 2. Lay googleFolderId -> chinh la "folderId" bien o tren
                // 3. Upload file vao folder do -> googleDriveService.uploadFile()
                // 4. Nhan googleFileId, luu vao DB (note.driveFileId)
                //
                // LUON tao file MOI (khong tim theo ten) - dung nghiep vu da
                // chot: cho phep trung ten tren Drive, chi driveFileId can duy nhat.
                GoogleDriveService.UploadResult result = googleDriveService.uploadFile(drive, folderId, note.getDisplayName(), content);
                note.setDriveFileId(result.fileId());
            } else {
                // Note DA CO driveFileId - toi uu bang md5Checksum TRUOC KHI
                // update(): neu noi dung local KHOP HOAN TOAN voi noi dung
                // dang co tren Drive, bo qua goi API (tranh upload thua).
                String localMd5 = HashUtil.md5(content);
                String remoteMd5 = googleDriveService.getFileChecksum(drive, note.getDriveFileId());

                if (remoteMd5 != null && remoteMd5.equalsIgnoreCase(localMd5)) {
                    log.debug("Note {} noi dung khop md5Checksum voi Drive, bo qua update() thua", noteId);
                } else {
                    // LUON update() dung fileId co san, KHONG BAO GIO tao file
                    // moi cho note da co driveFileId.
                    googleDriveService.updateFile(drive, note.getDriveFileId(), note.getDisplayName(), content);
                }
            }

            note.setSyncState(SyncState.SYNCED);
            note.setDirty(false); // da day len Drive thanh cong -> het dirty
            note.setDriveSyncedAt(LocalDateTime.now());
            note.setDriveSyncAttempts(0);
            note.setDriveSyncError(null);

        } catch (Exception e) {
            log.warn("Sync Drive that bai cho note {}: {}", noteId, e.getMessage());
            note.setDriveSyncAttempts(note.getDriveSyncAttempts() + 1);
            note.setDriveSyncError(truncate(e.getMessage(), 500));
            note.setSyncState(note.getDriveSyncAttempts() >= maxAttempts
                    ? SyncState.DRIVE_FAILED
                    : SyncState.PENDING_DRIVE);
            // dirty VAN GIU true (khong tat) - de con duoc thu lai o lan debounce/flush ke tiep
        }

        noteRepository.save(note);
    }

    @Override
    @Scheduled(fixedDelayString = "${app.drive.sync-job-fixed-delay-ms}")
    @Transactional(readOnly = true)
    public void runDebouncedSyncBatch() {
        LocalDateTime idleBefore = LocalDateTime.now().minusNanos(debounceIdleMs * 1_000_000);
        List<Note> idleDirtyNotes = noteRepository.findTop50ByDirtyTrueAndDeletedFalseAndUpdatedAtBefore(idleBefore);

        for (Note note : idleDirtyNotes) {
            syncNote(note.getId()); // moi call chay async (@Async), khong block vong lap
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void flushDirtyNotes(Long userId) {
        // BO QUA nguong debounce - lay TOAN BO note dirty cua RIENG user nay
        // (khong dung "idleBefore" nhu runDebouncedSyncBatch, vi day la yeu
        // cau dong bo NGAY tu nguoi dung/trinh duyet, khong can doi yen tinh).
        List<Note> dirtyNotes = noteRepository.findByDirtyTrueAndDeletedFalseAndUserId(userId);

        for (Note note : dirtyNotes) {
            syncNote(note.getId());
        }
    }

    @Override
    @Transactional
    public void pullFromDrive(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        if (!user.isDriveConnected()) {
            return; // chua ket noi Drive, khong co gi de pull
        }

        String folderId = ensureAppFolder(userId);
        Drive drive = googleDriveService.buildClient(user);
        List<GoogleDriveService.DriveFileInfo> driveFiles = googleDriveService.listFilesInFolder(drive, folderId);

        for (GoogleDriveService.DriveFileInfo driveFile : driveFiles) {
            try {
                pullOneFile(userId, drive, driveFile);
            } catch (Exception e) {
                // Best-effort theo tung file - 1 file loi (VD Drive tra 404
                // giua chung, mat mang giua chung) khong duoc lam sap ca vong
                // lap con lai cua cac file khac.
                log.warn("Pull file Drive (id={}, name={}) that bai cho userId={}",
                        driveFile.id(), driveFile.name(), userId, e);
            }
        }
    }

    private void pullOneFile(Long userId, Drive drive, GoogleDriveService.DriveFileInfo driveFile) {
        Note note = noteRepository.findByDriveFileId(driveFile.id()).orElse(null);

        if (note == null) {
            // File "la" - khong tim thay note local nao khop drive_file_id nay
            // (tao truc tiep tren Drive, hoac tu 1 thiet bi/tai khoan khac cung
            // chia se app folder). Tao note MOI, display_name lay dung ten tren
            // Drive - CO THE trung ten voi note khac, van hop le (xem V4 migration).
            String content = googleDriveService.downloadFileContent(drive, driveFile.id());

            Note created = Note.builder()
                    .userId(userId)
                    .displayName(driveFile.name())
                    .syncState(SyncState.SYNCED)
                    .dirty(false) // vua pull ve, dang khop 100% voi Drive, chua can day lai
                    .driveFileId(driveFile.id())
                    .build();
            created.setFilePath(fileStorageService.buildRelativePath(userId, created.getUuid()));

            long bytesWritten = fileStorageService.writeAtomic(created.getFilePath(), content);
            created.setContentSizeBytes(bytesWritten);
            created.setContentHash(HashUtil.sha256(content));
            created.setDriveSyncedAt(LocalDateTime.now());

            noteRepository.save(created);
            return;
        }

        if (note.isDeleted() || note.isDirty()) {
            // Da xoa o local (cho purge - khong can pull ve nua), HOAC dang co
            // thay doi local CHUA kip day len Drive - KHONG ghi de, tranh mat
            // du lieu local. Lan sync ke tiep se push xong (het dirty) roi moi
            // doi chieu pull lai note nay.
            return;
        }

        String remoteMd5 = googleDriveService.getFileChecksum(drive, driveFile.id());
        String localMd5 = HashUtil.md5(fileStorageService.read(note.getFilePath()));
        if (remoteMd5 != null && remoteMd5.equalsIgnoreCase(localMd5)) {
            return; // noi dung khop nhau, khong co gi moi de pull ve
        }

        // Drive co thay doi that su (sua truc tiep tren Drive, hoac tu thiet
        // bi khac) - tai ve VA ghi DE dung file vat ly cu tai note.filePath
        // (KHONG tao file moi, KHONG doi uuid/drive_file_id).
        String content = googleDriveService.downloadFileContent(drive, driveFile.id());
        long bytesWritten = fileStorageService.writeAtomic(note.getFilePath(), content);
        note.setContentSizeBytes(bytesWritten);
        note.setContentHash(HashUtil.sha256(content));
        note.setSyncState(SyncState.SYNCED);
        note.setDriveSyncedAt(LocalDateTime.now());
        note.setDriveSyncAttempts(0);
        note.setDriveSyncError(null);
        noteRepository.save(note);
    }

    @Override
    @Transactional
    public void disconnect(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        user.setDriveConnected(false);
        user.setDriveFolderId(null);
        user.setDriveRefreshTokenEnc(null);
        userRepository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public void deleteFromDrive(Long noteId) {
        Note note = noteRepository.findById(noteId).orElse(null);
        if (note == null || note.getDriveFileId() == null) {
            return; // chua tung sync len Drive (hoac note khong con ton tai) - khong co gi de xoa
        }

        try {
            User user = userRepository.findById(note.getUserId()).orElse(null);
            if (user == null || !user.isDriveConnected()) {
                return; // user da ngat ket noi Drive tu truoc - khong the/khong can xoa nua
            }

            Drive drive = googleDriveService.buildClient(user);
            googleDriveService.deleteFilePermanently(drive, note.getDriveFileId());
            log.info("Da xoa file tren Drive cho note id={} (driveFileId={})", noteId, note.getDriveFileId());
        } catch (Exception e) {
            // Best-effort - KHONG throw (xem javadoc o interface). Nguyen nhan
            // thuong gap: token Drive het han, nguoi dung da tu tay xoa file
            // nay tren Drive tu truoc (Google se tra 404), hoac vua ngat ket
            // noi Drive giua chung - deu KHONG can retry, chi log lai de biet.
            log.warn("Xoa file tren Drive that bai cho note id={} (driveFileId={}), bo qua (best-effort)",
                    noteId, note.getDriveFileId(), e);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
