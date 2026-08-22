package com.noted.backend.service.impl;

import com.google.api.services.drive.Drive;
import com.noted.backend.domain.entity.Note;
import com.noted.backend.domain.entity.User;
import com.noted.backend.domain.enums.SyncState;
import com.noted.backend.exception.DriveFileNotFoundException;
import com.noted.backend.repository.NoteRepository;
import com.noted.backend.repository.UserRepository;
import com.noted.backend.service.DriveSyncService;
import com.noted.backend.service.FileStorageService;
import com.noted.backend.service.GoogleDriveService;
import com.noted.backend.service.NoteService;
import com.noted.backend.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
 *
 * ===== FIX quan trong: self-invocation lam @Async/@Transactional cua
 * syncNote() KHONG BAO GIO co hieu luc (bug tim thay khi review, da sua) =====
 * Spring AOP (proxy-based, mac dinh) CHI intercept duoc method call di QUA
 * PROXY - goi noi bo kieu "this.syncNote(...)" (hoac chi "syncNote(...)" bo
 * qua hoan toan @Async/@Transactional RIENG cua syncNote(). Truoc day
 * runDebouncedSyncBatch()/flushDirtyNotes() goi thang "syncNote(...)" (self-
 * invocation) - ket qua: syncNote() chay DONG BO, tren CUNG transaction
 * readOnly=true cua ham goi. Hibernate coi entity trong session readOnly la
 * "read-only" (bo qua dirty-checking khi flush) - moi thay doi note.driveFileId/
 * dirty/syncState BI ROI RA khoi memory, KHONG BAO GIO duoc ghi xuong DB, du
 * Drive API upload THAT SU thanh cong. Hau qua: note KHONG BAO GIO duoc danh
 * dau da sync, moi lan debounce quet lai deu thay driveFileId=null -> upload
 * lai thanh 1 file HOAN TOAN MOI tren Drive - rac file trung tich luy vo han.
 *
 * Fix: tiem 1 THAM CHIEU CHINH BEAN NAY qua proxy (truong "self", @Lazy de
 * tranh loi khoi tao vong tron luc Spring dung bean). Goi "self.syncNote(...)"
 * thay vi "syncNote(...)" - luc nay method call THAT SU di qua proxy, @Async
 * chay tren thread rieng (khong con transaction nao duoc ke thua tu ham goi),
 * @Transactional cua syncNote() tu mo 1 transaction MOI (khong readOnly) -
 * dung nhu thiet ke ban dau.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DriveSyncServiceImpl implements DriveSyncService {

    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final GoogleDriveService googleDriveService;
    // Dung cho mat tran B (conflict Drive-vs-local-dirty, xem pullOneFile()) -
    // tach ban Drive thanh 1 note rieng qua co che "giu ca 2 ban" da co san.
    private final NoteService noteService;

    // Xem giai thich chi tiet o javadoc class phia tren - BAT BUOC @Lazy vi
    // day la tham chieu VONG TRON (bean tu tiem chinh no) - khong co @Lazy,
    // Spring se nem BeanCurrentlyInCreationException luc khoi tao.
    @Lazy
    @Autowired
    private DriveSyncService self;

    // Khoa trong bo nho: chan 2 lenh syncNote() chay dong thoi cho CUNG 1
    // noteId. Chi hoat dong dung tren 1 INSTANCE backend duy nhat (dung quy
    // mo hien tai cua du an) - neu sau nay scale nhieu instance, can chuyen
    // sang khoa phan tan (VD Redis SETNX), giong pattern da ghi chu o TokenBucket.java.
    private final Set<Long> syncingNoteIds = ConcurrentHashMap.newKeySet();

    // Khoa tuong tu nhung cho CA LUOT PULL cua 1 user. Bug thuc te 2026-08-22:
    // Drive chi co 1 file "Thong tin Account.txt" nhung app hien 2 note trung
    // het (cung drive_file_id, cung created_at den tung giay - xem note 108/109
    // va 84/85). Nguyen nhan: FE co TOI 3 duong deu goi POST /api/drive/sync-all
    // (nut "Dong bo ngay", auto-sync 2.5s sau khi luu, va flush luc dong tab),
    // khong duong nao chan duoc duong kia. Hai luot pull chay chong nhau cung
    // hoi "co note nao dang giu drive_file_id nay khong?" -> CA HAI deu thay
    // chua co -> CA HAI cung tao note moi.
    //
    // Bo qua (khong xep hang doi) luot pull den sau la dung y do: no se doc ra
    // dung ket qua ma luot dang chay sap ghi, nen chay them khong mang lai gi.
    // Giong syncingNoteIds: chi dung tren 1 instance backend duy nhat.
    private final Set<Long> pullingUserIds = ConcurrentHashMap.newKeySet();

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

        Drive drive = null;
        String folderId = null;
        String content = null;

        try {
            User user = userRepository.findById(note.getUserId()).orElseThrow();
            if (!user.isDriveConnected()) {
                return; // nguoi dung chua ket noi Drive, khong co gi de lam
            }

            folderId = ensureAppFolder(user.getId());
            drive = googleDriveService.buildClient(user);
            content = fileStorageService.read(note.getFilePath());

            if (note.getDriveFileId() == null) {
                uploadAsNewFile(note, drive, folderId, content);
            } else {
                // Note DA CO driveFileId - kiem tra TRASHED truoc tien (xem
                // javadoc GoogleDriveService.isFileTrashed): 1 file bi cho vao
                // thung rac (hanh dong "Xoa" MAC DINH tren giao dien Drive)
                // VAN cho phep update() THANH CONG binh thuong - neu khong
                // kiem tra rieng, sync se "thanh cong" (khong loi) nhung nguoi
                // dung khong bao gio thay file do trong thu muc nua (bao cao
                // thuc te 2026-08-18, da xac nhan qua Drive API that). Coi
                // trashed = tuong duong "khong con dung duoc" (nem lai
                // DriveFileNotFoundException, tai su dung dung 1 duong hoi
                // phuc voi truong hop 404 that o duoi).
                if (googleDriveService.isFileTrashed(drive, note.getDriveFileId())) {
                    throw new DriveFileNotFoundException(note.getDriveFileId(),
                            new IllegalStateException("File dang nam trong thung rac Drive"));
                }

                // Toi uu bang md5Checksum TRUOC KHI update(): neu noi dung
                // local KHOP HOAN TOAN voi noi dung dang co tren Drive, bo qua
                // goi API (tranh upload thua).
                String localMd5 = HashUtil.md5(content);
                String remoteMd5 = googleDriveService.getFileChecksum(drive, note.getDriveFileId());

                if (remoteMd5 != null && remoteMd5.equalsIgnoreCase(localMd5)) {
                    log.debug("Note {} noi dung khop md5Checksum voi Drive, bo qua update() thua", noteId);
                } else {
                    // LUON update() dung fileId co san, KHONG BAO GIO tao file
                    // moi cho note da co driveFileId - TRU KHI fileId do khong
                    // con ton tai nua (xem catch DriveFileNotFoundException duoi).
                    googleDriveService.updateFile(drive, note.getDriveFileId(), note.getDisplayName(), content);
                }
            }

            markSynced(note, content);

        } catch (DriveFileNotFoundException e) {
            // File GOC tren Drive khong con ton tai nua (VD nguoi dung tu xoa
            // truc tiep tren Drive, khong qua app) - day la loi VINH VIEN cho
            // fileId nay, retry lai voi CUNG fileId se THAT BAI MAI MAI. Xoa
            // driveFileId cu (coi note nhu "chua tung sync") roi upload lai
            // NGAY thanh 1 file HOAN TOAN MOI trong cung 1 lan goi nay - khong
            // can doi them 1 chu ky debounce nua nguoi dung moi thay ket qua.
            log.warn("Note {} tro toi file Drive da bi xoa - upload lai ngay thanh file moi", noteId);
            note.setDriveFileId(null);
            try {
                if (drive == null || folderId == null || content == null) {
                    throw new IllegalStateException("Thieu du lieu can thiet (drive/folderId/content) de upload lai", e);
                }
                uploadAsNewFile(note, drive, folderId, content);
                markSynced(note, content);
            } catch (Exception retryEx) {
                markFailed(note, retryEx);
            }

        } catch (Exception e) {
            markFailed(note, e);
        }

        noteRepository.save(note);
    }

    private void uploadAsNewFile(Note note, Drive drive, String folderId, String content) {
        // CHIEU NGUOC LAI (upload file), dung 4 buoc yeu cau:
        // 1. Kiem tra folder ton tai -> da co folderId tu ensureAppFolder() o tren
        // 2. Lay googleFolderId -> chinh la "folderId" tham so o day
        // 3. Upload file vao folder do -> googleDriveService.uploadFile()
        // 4. Nhan googleFileId, luu vao DB (note.driveFileId)
        //
        // LUON tao file MOI (khong tim theo ten) - dung nghiep vu da chot: cho
        // phep trung ten tren Drive, chi driveFileId can duy nhat.
        GoogleDriveService.UploadResult result = googleDriveService.uploadFile(drive, folderId, note.getDisplayName(), content);
        note.setDriveFileId(result.fileId());
    }

    private void markSynced(Note note, String content) {
        note.setSyncState(SyncState.SYNCED);
        note.setDirty(false); // da day len Drive thanh cong -> het dirty
        note.setDriveSyncedAt(LocalDateTime.now());
        note.setDriveSyncAttempts(0);
        note.setDriveSyncError(null);
        // Snapshot MD5 cua noi dung VUA duoc xac nhan khop voi Drive (du la do
        // vua upload/update(), hay do md5Checksum da khop san tu truoc) - dung
        // lam "baseline" cho pullOneFile() phat hien conflict THAT SU (mat tran
        // B), phan biet voi truong hop local chi don gian dang co sua chua push.
        note.setDriveSyncedContentHash(HashUtil.md5(content));
    }

    private void markFailed(Note note, Exception e) {
        log.warn("Sync Drive that bai cho note {}: {}", note.getId(), e.getMessage());
        note.setDriveSyncAttempts(note.getDriveSyncAttempts() + 1);
        note.setDriveSyncError(truncate(e.getMessage(), 500));
        note.setSyncState(note.getDriveSyncAttempts() >= maxAttempts
                ? SyncState.DRIVE_FAILED
                : SyncState.PENDING_DRIVE);
        // dirty VAN GIU true (khong tat) - de con duoc thu lai o lan debounce/flush ke tiep
    }

    @Override
    @Scheduled(fixedDelayString = "${app.drive.sync-job-fixed-delay-ms}")
    @Transactional(readOnly = true)
    public void runDebouncedSyncBatch() {
        LocalDateTime idleBefore = LocalDateTime.now().minusNanos(debounceIdleMs * 1_000_000);
        List<Note> idleDirtyNotes = noteRepository.findTop50ByDirtyTrueAndDeletedFalseAndUpdatedAtBefore(idleBefore);

        for (Note note : idleDirtyNotes) {
            // Goi QUA "self" (proxy), KHONG goi thang "syncNote(...)" - xem
            // javadoc class ve ly do self-invocation lam @Async mat tac dung.
            self.syncNote(note.getId());
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
            self.syncNote(note.getId());
        }
    }

    @Override
    @Transactional
    public void pullFromDrive(Long userId) {
        // Xem javadoc cua "pullingUserIds": chan 2 luot pull chay chong nhau
        // cho cung 1 user - day chinh la thu tao ra note trung y het nhau.
        if (!pullingUserIds.add(userId)) {
            log.debug("User {} dang co 1 luot pull Drive chay do, bo qua lenh trung lap nay", userId);
            return;
        }

        try {
            User user = userRepository.findById(userId).orElseThrow();
            if (!user.isDriveConnected()) {
                return; // chua ket noi Drive, khong co gi de pull
            }

            String folderId = ensureAppFolder(userId);
            Drive drive = googleDriveService.buildClient(user);

            if (user.getDriveChangesPageToken() == null) {
                bootstrapPull(userId, user, drive, folderId);
            } else {
                incrementalPull(userId, user, drive, folderId);
            }
        } finally {
            pullingUserIds.remove(userId);
        }
    }

    /**
     * Lan pull DAU TIEN (chua co page token luu san) - quet TOAN BO folder
     * nhu truoc day, roi lay "start page token" NGAY SAU DO de bat dau theo
     * doi incremental tu diem nay tro di. Thu tu quan trong: phai full-listing
     * TRUOC roi moi lay start token SAU - neu lam nguoc lai, thay doi xay ra
     * giua 2 buoc co the bi bo lot (khong nam trong ca 2 nguon).
     */
    private void bootstrapPull(Long userId, User user, Drive drive, String folderId) {
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

        // Doi chieu nguoc lai: note nao dang GIU driveFileId nhung KHONG con
        // xuat hien trong lan quet nay - file goc da bi xoa truc tiep tren
        // Drive (tu truoc khi bootstrap chay). Cung logic voi incrementalPull()
        // (xem giai thich chi tiet o do): xoa driveFileId cu, danh dau dirty
        // de tu dong upload lai thanh file moi, kich hoat sync ngay.
        Set<String> currentDriveFileIds = driveFiles.stream()
                .map(GoogleDriveService.DriveFileInfo::id)
                .collect(Collectors.toSet());
        for (Note note : noteRepository.findByUserIdAndDeletedFalseAndDriveFileIdIsNotNull(userId)) {
            if (!currentDriveFileIds.contains(note.getDriveFileId())) {
                markNoteNeedsReupload(note, "phat hien luc bootstrap");
            }
        }

        try {
            user.setDriveChangesPageToken(googleDriveService.getStartPageToken(drive));
            userRepository.save(user);
        } catch (Exception e) {
            // Khong lay duoc start token - KHONG sao, giu nguyen NULL, lan
            // pullFromDrive() ke tiep se tu dong bootstrap lai (full-listing),
            // chi cham hon chu khong mat du lieu gi.
            log.warn("Lay start page token cho userId={} that bai, se bootstrap lai o lan pull ke tiep", userId, e);
        }
    }

    /**
     * Cac lan pull TIEP THEO (da co page token) - CHI hoi Drive "gi da doi tu
     * lan truoc", khong quet lai toan bo folder. Quan trong cho hieu nang khi
     * so luong note lon (xem so sanh voi OneDrive/Google Drive desktop client).
     */
    private void incrementalPull(Long userId, User user, Drive drive, String folderId) {
        GoogleDriveService.ChangesResult changes;
        try {
            changes = googleDriveService.listChanges(drive, user.getDriveChangesPageToken(), folderId);
        } catch (Exception e) {
            // Ca lan goi changes.list() that bai (VD token het han/khong con
            // hop le phia Google) - KHONG throw tiep, giu nguyen token cu, thu
            // lai o lan sau. Neu token thuc su da "chet" vinh vien, nguoi dung
            // se thay note khong cap nhat tu Drive nua - chap nhan duoc, an
            // toan hon la crash ca luong "Dong bo ngay".
            log.warn("Lay danh sach thay doi (Drive Changes API) that bai cho userId={}, giu nguyen token cu", userId, e);
            return;
        }

        for (GoogleDriveService.DriveFileInfo driveFile : changes.changedFiles()) {
            try {
                pullOneFile(userId, drive, driveFile);
            } catch (Exception e) {
                log.warn("Pull file Drive (id={}, name={}) that bai cho userId={}",
                        driveFile.id(), driveFile.name(), userId, e);
            }
        }

        // File bi xoa/mat quyen truy cap tren Drive: KHONG tu dong xoa note
        // tuong ung o Noted (Drive chi la BACKUP, filesystem local moi la nguon
        // su that - xoa note van phai la hanh dong CHU DONG cua nguoi dung
        // trong app, xem NoteServiceImpl.delete()). NHUNG note do can duoc
        // "hoi sinh" ban Drive: xoa driveFileId cu (khong con hop le nua) +
        // danh dau dirty=true, de lan debounce/flush KE TIEP TU DONG upload
        // lai thanh 1 file MOI - dung nhu ky vong "Drive la backup, mat ban
        // backup thi tu dong tao lai tu ban goc local". Truoc day CHI log lai
        // (khong lam gi) - day la ly do bao loi thuc te: xoa file tren Drive,
        // bam "Dong bo ngay" nhung note KHONG BAO GIO len lai duoc, vi push
        // (flushDirtyNotes) chay TRUOC pull trong cung 1 lan "Dong bo ngay" -
        // luc do note con dang syncState=SYNCED/dirty=false (chua ai danh dau
        // dirty ca) nen bi bo qua hoan toan o buoc push.
        for (String removedFileId : changes.removedFileIds()) {
            // Best-effort theo tung id - giong hai vong lap phia tren. Truoc day
            // vong lap nay KHONG duoc bao ve, nen chi 1 id "xau" (VD trung
            // drive_file_id giua 2 note, xem NoteRepository.findAllByDriveFileId)
            // la lam sap ca POST /api/drive/sync-all va chan luon buoc luu page
            // token o cuoi -> loi lap lai vinh vien.
            try {
                for (Note note : noteRepository.findAllByDriveFileId(removedFileId)) {
                    if (note.isDeleted()) continue; // note cung da bi xoa o local - khong can lam gi them
                    markNoteNeedsReupload(note, "Drive bao removed/mat quyen");
                }
            } catch (Exception e) {
                log.warn("Xu ly file Drive da bi xoa (id={}) that bai cho userId={}", removedFileId, userId, e);
            }
        }

        if (changes.newPageToken() != null) {
            user.setDriveChangesPageToken(changes.newPageToken());
            userRepository.save(user);
        }
    }

    /**
     * Note dang GIU 1 driveFileId khong con dung duoc nua (bi xoa vinh vien -
     * 404, HOAC bi cho vao thung rac - xem GoogleDriveService.isFileTrashed).
     * KHONG tu dong xoa NOTE (Drive chi la backup, xoa note van phai la hanh
     * dong CHU DONG cua nguoi dung trong app) - chi "hoi sinh" ban Drive: xoa
     * driveFileId cu, danh dau dirty=true, kich hoat sync NGAY (qua "self",
     * proxy) de note tu dong duoc tao lai thanh 1 file MOI tren Drive trong
     * cung 1 lan "Dong bo ngay", khong can nguoi dung bam lai lan 2.
     */
    private void markNoteNeedsReupload(Note note, String reason) {
        note.setDriveFileId(null);
        note.setDirty(true);
        note.setSyncState(SyncState.PENDING_DRIVE);
        noteRepository.save(note);
        log.info("Note {} - {} - danh dau upload lai thanh file moi", note.getId(), reason);
        self.syncNote(note.getId());
    }

    private void pullOneFile(Long userId, Drive drive, GoogleDriveService.DriveFileInfo driveFile) {
        // Bo qua HOAN TOAN item khong phai note .txt that su - VD 1 Google
        // Doc/Sheet hoac 1 folder con nguoi dung lo tao/di chuyen vao BEN
        // TRONG app-folder "NotedApp" (loc theo "parents" o listChanges()/
        // listFilesInFolder() CHI dam bao item nam dung thu muc, KHONG dam
        // bao item la file .txt - van can loc rieng mimeType o day). KHONG
        // loc se goi downloadFileContent() len 1 item khong co noi dung binary
        // -> Drive tra 403 "fileNotDownloadable" (bug thuc te 2026-08-18, xem
        // GoogleDriveServiceImpl.downloadFileContent).
        if (!"text/plain".equals(driveFile.mimeType())) {
            log.debug("Bo qua item Drive (id={}, name={}, mimeType={}) - khong phai note .txt",
                    driveFile.id(), driveFile.name(), driveFile.mimeType());
            return;
        }

        // Co the co NHIEU note cung tro toi 1 drive_file_id (xem javadoc
        // findAllByDriveFileId). Uu tien ban CHUA bi xoa - dung ban dang thuc su
        // hien trong app; neu tat ca deu da bi xoa thi giu nguyen hanh vi cu
        // (bo qua, cho purge) chu KHONG tao lai note moi tu file Drive do.
        List<Note> matches = noteRepository.findAllByDriveFileId(driveFile.id());
        Note note = matches.stream().filter(n -> !n.isDeleted()).findFirst().orElse(null);

        if (note == null && !matches.isEmpty()) {
            return; // cho purge - khong can pull ve nua
        }

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
            created.setDriveSyncedContentHash(HashUtil.md5(content)); // baseline cho mat tran B ngay tu dau

            noteRepository.save(created);
            return;
        }

        // Kiem tra TRASHED truoc tien - ke ca khi note KHONG dirty (VD Changes
        // API khong bao "removed" cho hanh dong trash, chi bao "changed" binh
        // thuong, xem markNoteNeedsReupload javadoc) - tu phuc hoi ngay ca khi
        // khong co gi khac kich hoat lai note nay. Neu file bien mat hoan toan
        // (404) giua luc list() va luc kiem tra o day (hiem, race condition tu
        // nhien), coi nhu truong hop giong het "removed".
        try {
            if (googleDriveService.isFileTrashed(drive, driveFile.id())) {
                markNoteNeedsReupload(note, "file dang trong thung rac Drive");
                return;
            }
        } catch (DriveFileNotFoundException e) {
            markNoteNeedsReupload(note, "file da bien mat (404) ngay luc dang pull");
            return;
        }

        String remoteMd5 = googleDriveService.getFileChecksum(drive, driveFile.id());

        if (note.isDirty()) {
            // Local dang co sua CHUA kip day len Drive. Truoc day: luon BO QUA
            // pull o day (an toan nhung "mu" - khong biet Drive co THAT SU doi
            // gi khong). Mat tran B: so remoteMd5 voi "driveSyncedContentHash"
            // (baseline MD5 tai lan sync THANH CONG gan nhat, xem syncNoteInternal) -
            // neu Drive VAN khop baseline, khong co gi moi tren Drive de mat ca,
            // cu de push binh thuong o lan debounce ke tiep (nhu truoc). Neu
            // Drive DA KHAC baseline -> Drive cung doi DOC LAP trong luc local
            // dang dirty -> CONFLICT THAT: giu local (dang active o may nay)
            // lam ban chinh (tiep tuc push binh thuong), tach ban Drive thanh 1
            // note rieng qua co che "giu ca 2 ban" da co (createConflictCopy).
            boolean driveChangedIndependently = remoteMd5 != null
                    && note.getDriveSyncedContentHash() != null
                    && !remoteMd5.equalsIgnoreCase(note.getDriveSyncedContentHash());
            if (!driveChangedIndependently) return;

            String driveContent = googleDriveService.downloadFileContent(drive, driveFile.id());
            noteService.createConflictCopy(userId, note.getDisplayName(), driveContent);
            log.info("Phat hien conflict Drive-vs-local-dirty cho note {} - da tach ban Drive thanh note rieng", note.getId());
            return;
        }

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
        note.setDriveSyncedContentHash(remoteMd5); // cap nhat baseline cho mat tran B
        noteRepository.save(note);
    }

    @Override
    @Transactional
    public void disconnect(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        user.setDriveConnected(false);
        user.setDriveFolderId(null);
        user.setDriveRefreshTokenEnc(null);
        // Reset page token - lan connect lai sau nay (folder/token moi hoan
        // toan phia Google) phai bootstrap lai tu dau (full-listing), khong
        // the tiep tuc dung token cu (co the da het han hoac tro sai ngu canh).
        user.setDriveChangesPageToken(null);
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
