package com.noted.backend.service.impl;

import com.google.api.services.drive.Drive;
import com.noted.backend.domain.entity.Note;
import com.noted.backend.domain.entity.User;
import com.noted.backend.domain.enums.SyncState;
import com.noted.backend.exception.DriveFileNotFoundException;
import com.noted.backend.exception.GoogleDriveOperationException;
import com.noted.backend.repository.NoteRepository;
import com.noted.backend.repository.UserRepository;
import com.noted.backend.service.FileStorageService;
import com.noted.backend.service.GoogleDriveService;
import com.noted.backend.service.NoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Trong tam: bug tim thay tu bao cao thuc te - note tro toi 1 file DA BI XOA
 * truc tiep tren Google Drive (khong qua app) bi ket "sync that bai mai mai",
 * vi updateFile() luon nem loi cho fileId da chet ma khong bao gio duoc phat
 * hien va thay the bang 1 fileId moi. Xem DriveSyncServiceImpl.syncNoteInternal().
 */
@ExtendWith(MockitoExtension.class)
class DriveSyncServiceImplTest {

    @Mock private NoteRepository noteRepository;
    @Mock private UserRepository userRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private GoogleDriveService googleDriveService;
    @Mock private NoteService noteService;
    @Mock private Drive drive;

    private DriveSyncServiceImpl service;

    private static final Long USER_ID = 1L;
    private static final Long NOTE_ID = 10L;

    @BeforeEach
    void setUp() {
        service = new DriveSyncServiceImpl(noteRepository, userRepository, fileStorageService, googleDriveService, noteService);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        // "self" (@Lazy @Autowired, chi co gia tri that trong container Spring
        // that su) - trong unit test khong co proxy nao ca, tro thang ve chinh
        // service nay la du de goi self.syncNote(...) chay dong bo, khong NPE.
        ReflectionTestUtils.setField(service, "self", service);

        User user = User.builder().driveConnected(true).driveFolderId("folder-1").build();
        user.setId(USER_ID);
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        lenient().when(googleDriveService.buildClient(user)).thenReturn(drive);
        lenient().when(fileStorageService.read(anyString())).thenReturn("noi dung note");
        // Truong hop BINH THUONG: thu muc da luu dung la cua chinh user nay.
        // Khong co dong nay thi ensureAppFolder() se coi "folder-1" la thu muc
        // cua NGUOI KHAC va di tao thu muc moi (xem cac test o cuoi file).
        lenient().when(googleDriveService.isFolderOwnedByMe(drive, "folder-1")).thenReturn(true);
        // Truong hop BINH THUONG: file dang tro toi van con dung cho de ghi de
        // (chua vao thung rac, cua chinh minh, van trong app-folder).
        lenient().when(googleDriveService.isFileUsable(eq(drive), anyString(), eq("folder-1"))).thenReturn(true);
    }

    @Test
    void syncNote_uploadLaiThanhFileMoi_khiFileDriveCuDaBiXoaTrucTiep() {
        Note note = noteWithDriveFile(NOTE_ID, "old-drive-file-id-da-bi-xoa");
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(note));
        // md5Checksum tra ve null (khong lay duoc, hop ly cho file da xoa) ->
        // syncNoteInternal se van thu updateFile() nhu binh thuong truoc.
        when(googleDriveService.getFileMeta(drive, "old-drive-file-id-da-bi-xoa"))
                .thenReturn(new GoogleDriveService.DriveFileMeta(null, null));
        // updateFile() voi fileId cu NEM DriveFileNotFoundException (404 that su tu Drive)
        doThrow(new DriveFileNotFoundException("old-drive-file-id-da-bi-xoa", new RuntimeException("404")))
                .when(googleDriveService).updateFile(eq(drive), eq("old-drive-file-id-da-bi-xoa"), anyString(), anyString());
        // Upload lai NGAY thanh file moi - gia lap Drive tra ve fileId MOI
        when(googleDriveService.uploadFile(eq(drive), eq("folder-1"), anyString(), anyString()))
                .thenReturn(new GoogleDriveService.UploadResult("new-drive-file-id"));

        service.syncNote(NOTE_ID);

        // Note phai duoc GAN LAI driveFileId MOI (khong con la fileId cu da chet)
        assertThat(note.getDriveFileId()).isEqualTo("new-drive-file-id");
        // Va duoc coi la sync THANH CONG - dung 1 lan goi syncNote(), khong can doi chu ky sau
        assertThat(note.getSyncState()).isEqualTo(SyncState.SYNCED);
        assertThat(note.isDirty()).isFalse();
        assertThat(note.getDriveSyncAttempts()).isZero();
        assertThat(note.getDriveSyncError()).isNull();
        verify(noteRepository).save(note);
    }

    @Test
    void syncNote_baoLoiBinhThuong_khiUploadLaiSauKhiPhatHienFileMatCungThatBai() {
        Note note = noteWithDriveFile(NOTE_ID, "old-drive-file-id-da-bi-xoa");
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(note));
        when(googleDriveService.getFileMeta(drive, "old-drive-file-id-da-bi-xoa"))
                .thenReturn(new GoogleDriveService.DriveFileMeta(null, null));
        doThrow(new DriveFileNotFoundException("old-drive-file-id-da-bi-xoa", new RuntimeException("404")))
                .when(googleDriveService).updateFile(eq(drive), eq("old-drive-file-id-da-bi-xoa"), anyString(), anyString());
        // Upload lai CUNG that bai (VD mat mang giua chung)
        when(googleDriveService.uploadFile(eq(drive), eq("folder-1"), anyString(), anyString()))
                .thenThrow(new GoogleDriveOperationException("mat mang"));

        service.syncNote(NOTE_ID);

        // driveFileId cu VAN bi xoa (khong con hop le), nhung sync coi la that bai binh
        // thuong - se duoc retry o lan debounce ke tiep (dirty giu nguyen true)
        assertThat(note.getDriveFileId()).isNull();
        assertThat(note.isDirty()).isTrue();
        assertThat(note.getDriveSyncAttempts()).isEqualTo(1);
        assertThat(note.getSyncState()).isEqualTo(SyncState.PENDING_DRIVE);
    }

    @Test
    void syncNote_uploadLaiThanhFileMoi_khiFileDriveDangTrongThungRac() {
        // Bao cao thuc te 2026-08-18, xac nhan qua Drive API that: nguoi dung
        // bam "Xoa" tren giao dien Drive (hanh dong MAC DINH la cho vao THUNG
        // RAC, khong phai xoa vinh vien) - file VAN ton tai, Drive API van cho
        // update() THANH CONG binh thuong, nhung nguoi dung khong bao gio thay
        // file do trong thu muc nua. Day la ly do can kiem tra rieng "trashed",
        // khong the chi dua vao 404 (Phan 1 cua fix khong du).
        Note note = noteWithDriveFile(NOTE_ID, "trashed-file-id");
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(note));
        when(googleDriveService.isFileUsable(drive, "trashed-file-id", "folder-1")).thenReturn(false);
        when(googleDriveService.uploadFile(eq(drive), eq("folder-1"), anyString(), anyString()))
                .thenReturn(new GoogleDriveService.UploadResult("brand-new-id-not-trashed"));

        service.syncNote(NOTE_ID);

        // Khong duoc goi updateFile() len file dang trashed - phai bo qua
        // hoan toan, di thang sang upload file MOI (khong trashed).
        verify(googleDriveService, never()).updateFile(any(), any(), any(), any());
        assertThat(note.getDriveFileId()).isEqualTo("brand-new-id-not-trashed");
        assertThat(note.getSyncState()).isEqualTo(SyncState.SYNCED);
        assertThat(note.isDirty()).isFalse();
    }

    @Test
    void syncNote_updateBinhThuong_khiFileDriveVanConTonTai() {
        Note note = noteWithDriveFile(NOTE_ID, "still-valid-file-id");
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(note));
        when(googleDriveService.getFileMeta(drive, "still-valid-file-id"))
                .thenReturn(new GoogleDriveService.DriveFileMeta("different-md5", "note.txt"));

        service.syncNote(NOTE_ID);

        verify(googleDriveService).updateFile(eq(drive), eq("still-valid-file-id"), anyString(), eq("noi dung note"));
        verify(googleDriveService, never()).uploadFile(any(), any(), any(), any());
        assertThat(note.getDriveFileId()).isEqualTo("still-valid-file-id");
        assertThat(note.getSyncState()).isEqualTo(SyncState.SYNCED);
    }

    // ---------- pullFromDrive() / incrementalPull(): bao cao thuc te 2026-08-18 -
    // note DA SYNC XONG TU TRUOC (khong dirty) bi xoa file goc truc tiep tren
    // Drive, bam "Dong bo ngay" nhung KHONG BAO GIO len lai duoc, vi flushDirtyNotes()
    // (chay TRUOC pull trong cung 1 lan "Dong bo ngay") chi day note DANG dirty=true -
    // note nay luc do van dirty=false nen bi bo qua hoan toan, va truoc ban fix nay
    // incrementalPull() cung CHI log lai "removedFileIds", khong lam gi khac. ----------

    @Test
    void pullFromDrive_uploadLaiNgay_khiNoteDaSyncXongBiXoaFileTrucTiepTrenDrive() {
        Note note = noteWithDriveFile(NOTE_ID, "removed-on-drive-id");
        note.setDirty(false); // DA sync xong tu truoc - day chinh la trieu chung bug that
        note.setSyncState(SyncState.SYNCED);

        User user = User.builder().driveConnected(true).driveFolderId("folder-1")
                .driveChangesPageToken("existing-page-token") // co token -> di nhanh incrementalPull()
                .build();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(googleDriveService.buildClient(user)).thenReturn(drive);

        when(googleDriveService.listChanges(drive, "existing-page-token", "folder-1")).thenReturn(
                new GoogleDriveService.ChangesResult(List.of(), List.of("removed-on-drive-id"), "new-page-token"));
        when(noteRepository.findAllByUserIdAndDriveFileId(USER_ID, "removed-on-drive-id")).thenReturn(List.of(note));
        // self.syncNote() (goi lai NGAY sau khi danh dau dirty) se tu tim lai
        // note qua findById() - can stub rieng vi day la 1 loi goi KHAC voi
        // findAllByUserIdAndDriveFileId() o tren.
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(note));
        // self.syncNote() se chay lai toan bo syncNoteInternal() - note.getDriveFileId()
        // luc do da duoc xoa (null) nen di vao nhanh "upload file MOI"
        when(googleDriveService.uploadFile(eq(drive), eq("folder-1"), anyString(), anyString()))
                .thenReturn(new GoogleDriveService.UploadResult("brand-new-drive-file-id"));

        service.pullFromDrive(USER_ID);

        // Note phai duoc upload lai NGAY trong CUNG 1 lan goi pullFromDrive() nay -
        // khong can nguoi dung bam "Dong bo ngay" lan 2.
        assertThat(note.getDriveFileId()).isEqualTo("brand-new-drive-file-id");
        assertThat(note.getSyncState()).isEqualTo(SyncState.SYNCED);
        assertThat(note.isDirty()).isFalse();
        assertThat(user.getDriveChangesPageToken()).isEqualTo("new-page-token");
    }

    @Test
    void pullFromDrive_uploadLaiNgay_khiFileXuatHienTrongChangedFilesNhungDangTrashed() {
        // Ban chat cua bug that: Drive Changes API BAO CAO file bi trash duoi
        // dang "changed" (con file, van tra ve), KHONG PHAI "removed" - nen
        // markNoteNeedsReupload() qua nhanh removedFileIds() KHONG BAO GIO
        // chay toi. pullOneFile() (nhanh changedFiles) phai tu kiem tra
        // isFileTrashed() rieng, du note dang khong dirty.
        Note note = noteWithDriveFile(NOTE_ID, "trashed-but-changed-id");
        note.setDirty(false);
        note.setSyncState(SyncState.SYNCED);

        User user = User.builder().driveConnected(true).driveFolderId("folder-1")
                .driveChangesPageToken("existing-page-token")
                .build();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(googleDriveService.buildClient(user)).thenReturn(drive);

        GoogleDriveService.DriveFileInfo changedFile = new GoogleDriveService.DriveFileInfo(
                "trashed-but-changed-id", "Note.txt", 10L, null, true, "text/plain");
        when(googleDriveService.listChanges(drive, "existing-page-token", "folder-1")).thenReturn(
                new GoogleDriveService.ChangesResult(List.of(changedFile), List.of(), "new-page-token"));
        when(noteRepository.findAllByUserIdAndDriveFileId(USER_ID, "trashed-but-changed-id")).thenReturn(List.of(note));
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(note));
        when(googleDriveService.isFileTrashed(drive, "trashed-but-changed-id")).thenReturn(true);
        when(googleDriveService.uploadFile(eq(drive), eq("folder-1"), anyString(), anyString()))
                .thenReturn(new GoogleDriveService.UploadResult("brand-new-id-not-trashed"));

        service.pullFromDrive(USER_ID);

        // KHONG duoc tai noi dung tu ban trashed ve ghi de local (downloadFileContent
        // khong duoc goi cho file nay) - phai tu upload lai thanh file moi.
        verify(googleDriveService, never()).downloadFileContent(eq(drive), eq("trashed-but-changed-id"));
        assertThat(note.getDriveFileId()).isEqualTo("brand-new-id-not-trashed");
        assertThat(note.getSyncState()).isEqualTo(SyncState.SYNCED);
        assertThat(note.isDirty()).isFalse();
    }

    @Test
    void pullFromDrive_boQuaHoanToan_khiChangesApiBaoThayDoiCuaChinhAppFolder() {
        // Bug thuc te 2026-08-18: Drive Changes API co the bao thay doi cua
        // CHINH app-folder "NotedApp" (scope "drive.file" cho app thay ca file
        // CHINH NO tao ra, ke ca folder) - neu khong loc mimeType, pullOneFile()
        // se goi downloadFileContent() len 1 folder -> Drive tra 403
        // "fileNotDownloadable" (folder khong co noi dung binary de tai).
        User user = User.builder().driveConnected(true).driveFolderId("folder-1")
                .driveChangesPageToken("existing-page-token")
                .build();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(googleDriveService.buildClient(user)).thenReturn(drive);

        GoogleDriveService.DriveFileInfo folderChange = new GoogleDriveService.DriveFileInfo(
                "folder-1", "NotedApp", null, null, true, "application/vnd.google-apps.folder");
        when(googleDriveService.listChanges(drive, "existing-page-token", "folder-1")).thenReturn(
                new GoogleDriveService.ChangesResult(List.of(folderChange), List.of(), "new-page-token"));

        service.pullFromDrive(USER_ID);

        // Khong duoc goi bat ky API nao lien quan den "xu ly nhu 1 note" cho folder
        verify(googleDriveService, never()).downloadFileContent(any(), anyString());
        verify(googleDriveService, never()).isFileTrashed(any(), anyString());
        verify(noteRepository, never()).findAllByUserIdAndDriveFileId(any(), anyString());
    }

    @Test
    void pullFromDrive_vanChayXongVaLuuPageToken_khiNhieuNoteTrungCungDriveFileId() {
        // Bug thuc te 2026-08-22: DB co 2 note (84, 85) cung giu 1 drive_file_id.
        // Ban cu dung Optional<Note> findByDriveFileId() -> nem
        // IncorrectResultSizeDataAccessException NGAY trong vong lap removedFileIds
        // (khong co try/catch) -> ca POST /api/drive/sync-all tra 500. Nang hon:
        // page token chi duoc luu O CUOI incrementalPull() nen KHONG BAO GIO tien
        // len, khien moi lan bam "Dong bo ngay" deu lap lai dung loi do vinh vien.
        Note daXoa = noteWithDriveFile(99L, "shared-drive-file-id");
        daXoa.setDeleted(true);
        Note conSong = noteWithDriveFile(NOTE_ID, "shared-drive-file-id");
        conSong.setDirty(false);
        conSong.setSyncState(SyncState.SYNCED);

        User user = User.builder().driveConnected(true).driveFolderId("folder-1")
                .driveChangesPageToken("existing-page-token")
                .build();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(googleDriveService.buildClient(user)).thenReturn(drive);

        when(googleDriveService.listChanges(drive, "existing-page-token", "folder-1")).thenReturn(
                new GoogleDriveService.ChangesResult(List.of(), List.of("shared-drive-file-id"), "new-page-token"));
        when(noteRepository.findAllByUserIdAndDriveFileId(USER_ID, "shared-drive-file-id"))
                .thenReturn(List.of(daXoa, conSong));
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(conSong));
        when(googleDriveService.uploadFile(eq(drive), eq("folder-1"), anyString(), anyString()))
                .thenReturn(new GoogleDriveService.UploadResult("brand-new-drive-file-id"));

        service.pullFromDrive(USER_ID);

        // Note con song duoc hoi sinh binh thuong; note da xoa bi bo qua (khong
        // upload lai ban da xoa len Drive).
        assertThat(conSong.getDriveFileId()).isEqualTo("brand-new-drive-file-id");
        assertThat(daXoa.getDriveFileId()).isEqualTo("shared-drive-file-id");
        // QUAN TRONG NHAT: page token PHAI tien len - day la thu chan vong lap loi vinh vien.
        assertThat(user.getDriveChangesPageToken()).isEqualTo("new-page-token");
    }

    @Test
    void pullFromDrive_khongTaoNoteTrung_khiHaiLuotPullChayChongNhau() throws Exception {
        // Bug thuc te 2026-08-22: Drive chi co 1 file "Thong tin Account.txt"
        // nhung app hien 2 note y het (note 108/109: cung drive_file_id, cung
        // created_at den tung giay). Hai luot pull chay chong nhau cung thay
        // "chua co note nao giu drive_file_id nay" nen ca hai cung tao note moi.
        User user = User.builder().driveConnected(true).driveFolderId("folder-1").build();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(googleDriveService.buildClient(user)).thenReturn(drive);

        GoogleDriveService.DriveFileInfo fileMoi = new GoogleDriveService.DriveFileInfo(
                "file-moi-tren-drive", "Thông tin Account.txt", 20L, null, true, "text/plain");

        // Luot pull thu 2 duoc kich hoat NGAY GIUA luc luot thu 1 dang goi Drive -
        // day la cach tai hien "chong nhau" mot cach xac dinh (khong phu thuoc
        // vao viec 2 thread co that su giao nhau hay khong).
        CountDownLatch daGoiLanHai = new CountDownLatch(1);
        when(googleDriveService.listFilesInFolder(drive, "folder-1")).thenAnswer(inv -> {
            service.pullFromDrive(USER_ID); // luot thu 2, tai dung luc luot 1 chua xong
            daGoiLanHai.countDown();
            return List.of(fileMoi);
        });
        when(googleDriveService.downloadFileContent(drive, "file-moi-tren-drive")).thenReturn("noi dung");
        when(fileStorageService.buildRelativePath(eq(USER_ID), anyString())).thenReturn("1/note.txt");
        when(fileStorageService.writeAtomic(anyString(), anyString())).thenReturn(8L);

        service.pullFromDrive(USER_ID);

        assertThat(daGoiLanHai.getCount()).isZero(); // chac chan luot thu 2 DA chay
        // Chi duoc tao DUNG 1 note - truoc ban fix la 2.
        verify(noteRepository, times(1)).save(any(Note.class));
    }

    // ---------- helpers ----------

    private Note noteWithDriveFile(Long id, String driveFileId) {
        Note n = Note.builder()
                .userId(USER_ID)
                .displayName("Note.txt")
                .syncState(SyncState.PENDING_DRIVE)
                .dirty(true)
                .driveFileId(driveFileId)
                .build();
        n.setId(id);
        n.setFilePath(USER_ID + "/" + n.getUuid() + ".txt");
        return n;
    }

    // ---------- NGHIEP VU: note cua 1 tai khoan Google CHI duoc nam trong Drive
    // cua CHINH tai khoan do. Bug thuc te 2026-08-22: thu muc NotedApp cua
    // dautruongptit@ tung duoc chia se (kem quyen sua) cho dautruong.dt@, nen
    // tai khoan moi tim theo TEN thay luon thu muc do va ghi note vao Drive
    // nguoi khac - Google khong he tu choi vi quyen sua la hop le. ----------

    @Test
    void ensureAppFolder_taoThuMucRieng_khiThuMucDaLuuKhongThuocSoHuuCuaMinh() {
        User user = User.builder().driveConnected(true)
                .driveFolderId("thu-muc-cua-nguoi-khac")
                .build();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(googleDriveService.buildClient(user)).thenReturn(drive);
        when(googleDriveService.isFolderOwnedByMe(drive, "thu-muc-cua-nguoi-khac")).thenReturn(false);
        when(googleDriveService.ensureFolder(drive, null)).thenReturn("thu-muc-cua-chinh-toi");

        String ketQua = service.ensureAppFolder(USER_ID);

        assertThat(ketQua).isEqualTo("thu-muc-cua-chinh-toi");
        assertThat(user.getDriveFolderId()).isEqualTo("thu-muc-cua-chinh-toi");
    }

    @Test
    void ensureAppFolder_danhDauUploadLaiToanBoNote_khiPhaiDoiSangThuMucKhac() {
        // Note dang tro toi file nam trong thu muc CU (Drive cua nguoi khac) -
        // neu khong cat lien ket, moi lan sync sau van update() dung file do.
        Note note = noteWithDriveFile(NOTE_ID, "file-nam-trong-drive-nguoi-khac");
        note.setDirty(false);
        note.setSyncState(SyncState.SYNCED);

        User user = User.builder().driveConnected(true)
                .driveFolderId("thu-muc-cua-nguoi-khac")
                .build();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(googleDriveService.buildClient(user)).thenReturn(drive);
        when(googleDriveService.isFolderOwnedByMe(drive, "thu-muc-cua-nguoi-khac")).thenReturn(false);
        when(googleDriveService.ensureFolder(drive, null)).thenReturn("thu-muc-cua-chinh-toi");
        when(noteRepository.findByUserIdAndDeletedFalseAndDriveFileIdIsNotNull(USER_ID))
                .thenReturn(List.of(note));

        service.ensureAppFolder(USER_ID);

        assertThat(note.getDriveFileId()).isNull();
        assertThat(note.isDirty()).isTrue();
        assertThat(note.getSyncState()).isEqualTo(SyncState.PENDING_DRIVE);
    }

    @Test
    void ensureAppFolder_giuNguyen_khiThuMucDaLuuDungLaCuaMinh() {
        User user = User.builder().driveConnected(true).driveFolderId("thu-muc-cua-chinh-toi").build();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(googleDriveService.buildClient(user)).thenReturn(drive);
        when(googleDriveService.isFolderOwnedByMe(drive, "thu-muc-cua-chinh-toi")).thenReturn(true);

        String ketQua = service.ensureAppFolder(USER_ID);

        assertThat(ketQua).isEqualTo("thu-muc-cua-chinh-toi");
        // Khong duoc dong toi note nao, cung khong tao thu muc moi
        verify(googleDriveService, never()).ensureFolder(any(), any());
        verify(noteRepository, never()).saveAll(any());
    }

    @Test
    void ensureAppFolder_chiGoiDriveApiMotLan_choNhieuLanGoiLienTiep() {
        // Xac minh quyen so huu la 1 lan goi Drive API - ensureAppFolder() duoc
        // goi o MOI lan sync tung note, nen phai duoc nho lai sau lan dau.
        User user = User.builder().driveConnected(true).driveFolderId("thu-muc-cua-chinh-toi").build();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(googleDriveService.buildClient(user)).thenReturn(drive);
        when(googleDriveService.isFolderOwnedByMe(drive, "thu-muc-cua-chinh-toi")).thenReturn(true);

        service.ensureAppFolder(USER_ID);
        service.ensureAppFolder(USER_ID);
        service.ensureAppFolder(USER_ID);

        verify(googleDriveService, times(1)).isFolderOwnedByMe(drive, "thu-muc-cua-chinh-toi");
    }

    @Test
    void pullFromDrive_boQuaFile_khiFileNamTrongAppFolderNhungThuocSoHuuNguoiKhac() {
        // Thu muc CUA TOI van co the chua file CUA NGUOI KHAC neu toi da chia
        // se thu muc kem quyen sua. Khong duoc nhan chung thanh note cua minh.
        User user = User.builder().driveConnected(true).driveFolderId("folder-1")
                .driveChangesPageToken("existing-page-token")
                .build();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(googleDriveService.buildClient(user)).thenReturn(drive);

        GoogleDriveService.DriveFileInfo fileNguoiKhac = new GoogleDriveService.DriveFileInfo(
                "file-cua-nguoi-khac", "Note cua ho.txt", 10L, null, false, "text/plain");
        when(googleDriveService.listChanges(drive, "existing-page-token", "folder-1")).thenReturn(
                new GoogleDriveService.ChangesResult(List.of(fileNguoiKhac), List.of(), "new-page-token"));

        service.pullFromDrive(USER_ID);

        // Khong duoc tai noi dung ve, khong duoc tra DB, va tuyet doi khong tao note
        verify(googleDriveService, never()).downloadFileContent(any(), anyString());
        verify(noteRepository, never()).findAllByUserIdAndDriveFileId(any(), anyString());
        verify(noteRepository, never()).save(any(Note.class));
        // Van phai tien page token - bo qua 1 file khong phai la loi
        assertThat(user.getDriveChangesPageToken()).isEqualTo("new-page-token");
    }

    @Test
    void pullFromDrive_traNoteTheoDUNGUserId_khongDungChungDriveFileIdVoiUserKhac() {
        // Cung 1 file Drive co the duoc tro toi boi note cua NHIEU user (2 tai
        // khoan cung nhin thay 1 thu muc chia se). Neu tra note khong loc userId,
        // luot pull cua user nay se sua thang vao note cua user khac.
        User user = User.builder().driveConnected(true).driveFolderId("folder-1")
                .driveChangesPageToken("existing-page-token")
                .build();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(googleDriveService.buildClient(user)).thenReturn(drive);
        when(googleDriveService.listChanges(drive, "existing-page-token", "folder-1")).thenReturn(
                new GoogleDriveService.ChangesResult(List.of(), List.of("file-dung-chung"), "new-page-token"));

        service.pullFromDrive(USER_ID);

        // Phai hoi DB kem userId - khong duoc hoi bang moi drive_file_id
        verify(noteRepository).findAllByUserIdAndDriveFileId(USER_ID, "file-dung-chung");
    }

    // ---------- Bao cao thuc te 2026-08-24: doi ten note trong app, bam dong
    // bo, nhung file tren Google Drive VAN GIU TEN CU vinh vien. ----------

    @Test
    void syncNote_doiTenTrenDrive_khiChiDoiTenChuKhongDoiNoiDung() {
        // Day chinh la bug: doi ten KHONG lam doi noi dung, nen md5 van khop.
        // Ban cu chi so md5 -> ket luan "khong co gi thay doi" -> bo qua
        // update() -> ma ten file lai CHI duoc gui di ben trong update() do.
        Note note = noteWithDriveFile(NOTE_ID, "file-id");
        note.setDisplayName("Ten MOI.txt");
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(note));
        when(fileStorageService.read(anyString())).thenReturn("noi dung KHONG doi");
        when(googleDriveService.getFileMeta(drive, "file-id")).thenReturn(
                new GoogleDriveService.DriveFileMeta(
                        com.noted.backend.util.HashUtil.md5("noi dung KHONG doi"), // md5 KHOP
                        "Ten cu.txt"));                                            // ten KHAC

        service.syncNote(NOTE_ID);

        // Phai doi ten tren Drive...
        verify(googleDriveService).renameFile(drive, "file-id", "Ten MOI.txt");
        // ...nhung KHONG tai lai noi dung (khong doi thi tai lai lam gi)
        verify(googleDriveService, never()).updateFile(any(), any(), any(), any());
        assertThat(note.getSyncState()).isEqualTo(SyncState.SYNCED);
        assertThat(note.isDirty()).isFalse();
    }

    @Test
    void syncNote_khongGoiApiNao_khiCaTenLanNoiDungDeuKhop() {
        // Buoc toi uu VAN phai hoat dong: that su khong co gi thay doi thi
        // khong duoc goi Drive API nao ca.
        Note note = noteWithDriveFile(NOTE_ID, "file-id");
        note.setDisplayName("Note.txt");
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(note));
        when(fileStorageService.read(anyString())).thenReturn("y het");
        when(googleDriveService.getFileMeta(drive, "file-id")).thenReturn(
                new GoogleDriveService.DriveFileMeta(com.noted.backend.util.HashUtil.md5("y het"), "Note.txt"));

        service.syncNote(NOTE_ID);

        verify(googleDriveService, never()).updateFile(any(), any(), any(), any());
        verify(googleDriveService, never()).renameFile(any(), any(), any());
        assertThat(note.getSyncState()).isEqualTo(SyncState.SYNCED);
    }

    @Test
    void syncNote_dayCaNoiDung_khiNoiDungVaTenCungDoi() {
        Note note = noteWithDriveFile(NOTE_ID, "file-id");
        note.setDisplayName("Ten MOI.txt");
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(note));
        when(fileStorageService.read(anyString())).thenReturn("noi dung MOI");
        when(googleDriveService.getFileMeta(drive, "file-id"))
                .thenReturn(new GoogleDriveService.DriveFileMeta("md5-cu-khac", "Ten cu.txt"));

        service.syncNote(NOTE_ID);

        // update() da mang theo ca ten moi lan noi dung moi - khong can rename rieng
        verify(googleDriveService).updateFile(drive, "file-id", "Ten MOI.txt", "noi dung MOI");
        verify(googleDriveService, never()).renameFile(any(), any(), any());
    }

    // ---------- Lop loi DOI XUNG voi bug day len 2026-08-24: doi ten file
    // TREN DRIVE thi app cung khong nhan ve, vi pullOneFile() cung lay md5 lam
    // dai dien cho "co gi thay doi khong" - ma md5 chi noi ve NOI DUNG. ------

    private User userCoPageToken() {
        User user = User.builder().driveConnected(true).driveFolderId("folder-1")
                .driveChangesPageToken("existing-page-token").build();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(googleDriveService.buildClient(user)).thenReturn(drive);
        return user;
    }

    @Test
    void pullFromDrive_doiTenNoteTheoDrive_khiChiDoiTenChuKhongDoiNoiDung() {
        Note note = noteWithDriveFile(NOTE_ID, "file-id");
        note.setDisplayName("Ten cu.txt");
        note.setDirty(false);
        note.setSyncState(SyncState.SYNCED);

        userCoPageToken();
        GoogleDriveService.DriveFileInfo doiTen = new GoogleDriveService.DriveFileInfo(
                "file-id", "Ten MOI tren Drive.txt", 10L, null, true, "text/plain");
        when(googleDriveService.listChanges(drive, "existing-page-token", "folder-1")).thenReturn(
                new GoogleDriveService.ChangesResult(List.of(doiTen), List.of(), "new-page-token"));
        when(noteRepository.findAllByUserIdAndDriveFileId(USER_ID, "file-id")).thenReturn(List.of(note));
        when(googleDriveService.isFileTrashed(drive, "file-id")).thenReturn(false);
        when(fileStorageService.read(anyString())).thenReturn("noi dung KHONG doi");
        // md5 KHOP -> ban cu thoat ngay tai day, ten moi khong bao gio duoc ap ve
        when(googleDriveService.getFileMeta(drive, "file-id")).thenReturn(
                new GoogleDriveService.DriveFileMeta(
                        com.noted.backend.util.HashUtil.md5("noi dung KHONG doi"), "Ten MOI tren Drive.txt"));

        service.pullFromDrive(USER_ID);

        assertThat(note.getDisplayName()).isEqualTo("Ten MOI tren Drive.txt");
        // Ten local gio DA khop Drive -> khong duoc danh dau dirty (day nguoc len la thua)
        assertThat(note.isDirty()).isFalse();
        // Khong tai lai noi dung: noi dung co doi dau ma tai
        verify(googleDriveService, never()).downloadFileContent(any(), anyString());
        verify(noteRepository).save(note);
    }

    @Test
    void pullFromDrive_giuTenLocal_khiNoteDangDirty() {
        // Local vua doi ten, chua kip day len -> ten local phai THANG, khong
        // duoc bi ten cu tren Drive ghi de nguoc lai.
        Note note = noteWithDriveFile(NOTE_ID, "file-id");
        note.setDisplayName("Ten local vua doi.txt");
        note.setDirty(true);
        note.setDriveSyncedContentHash(com.noted.backend.util.HashUtil.md5("noi dung"));

        userCoPageToken();
        GoogleDriveService.DriveFileInfo tenCu = new GoogleDriveService.DriveFileInfo(
                "file-id", "Ten cu tren Drive.txt", 10L, null, true, "text/plain");
        when(googleDriveService.listChanges(drive, "existing-page-token", "folder-1")).thenReturn(
                new GoogleDriveService.ChangesResult(List.of(tenCu), List.of(), "new-page-token"));
        when(noteRepository.findAllByUserIdAndDriveFileId(USER_ID, "file-id")).thenReturn(List.of(note));
        when(googleDriveService.isFileTrashed(drive, "file-id")).thenReturn(false);
        when(googleDriveService.getFileMeta(drive, "file-id")).thenReturn(
                new GoogleDriveService.DriveFileMeta(
                        com.noted.backend.util.HashUtil.md5("noi dung"), "Ten cu tren Drive.txt"));

        service.pullFromDrive(USER_ID);

        assertThat(note.getDisplayName()).isEqualTo("Ten local vua doi.txt");
    }

    @Test
    void pullFromDrive_khongGhiDB_khiCaTenLanNoiDungDeuKhop() {
        Note note = noteWithDriveFile(NOTE_ID, "file-id");
        note.setDisplayName("Note.txt");
        note.setDirty(false);
        note.setSyncState(SyncState.SYNCED);

        userCoPageToken();
        GoogleDriveService.DriveFileInfo khongDoi = new GoogleDriveService.DriveFileInfo(
                "file-id", "Note.txt", 10L, null, true, "text/plain");
        when(googleDriveService.listChanges(drive, "existing-page-token", "folder-1")).thenReturn(
                new GoogleDriveService.ChangesResult(List.of(khongDoi), List.of(), "new-page-token"));
        when(noteRepository.findAllByUserIdAndDriveFileId(USER_ID, "file-id")).thenReturn(List.of(note));
        when(googleDriveService.isFileTrashed(drive, "file-id")).thenReturn(false);
        when(fileStorageService.read(anyString())).thenReturn("y het");
        when(googleDriveService.getFileMeta(drive, "file-id")).thenReturn(
                new GoogleDriveService.DriveFileMeta(com.noted.backend.util.HashUtil.md5("y het"), "Note.txt"));

        service.pullFromDrive(USER_ID);

        verify(noteRepository, never()).save(note);
        verify(googleDriveService, never()).downloadFileContent(any(), anyString());
    }
}
