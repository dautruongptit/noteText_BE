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
    }

    @Test
    void syncNote_uploadLaiThanhFileMoi_khiFileDriveCuDaBiXoaTrucTiep() {
        Note note = noteWithDriveFile(NOTE_ID, "old-drive-file-id-da-bi-xoa");
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(note));
        // md5Checksum tra ve null (khong lay duoc, hop ly cho file da xoa) ->
        // syncNoteInternal se van thu updateFile() nhu binh thuong truoc.
        when(googleDriveService.getFileChecksum(drive, "old-drive-file-id-da-bi-xoa")).thenReturn(null);
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
        when(googleDriveService.getFileChecksum(drive, "old-drive-file-id-da-bi-xoa")).thenReturn(null);
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
        when(googleDriveService.isFileTrashed(drive, "trashed-file-id")).thenReturn(true);
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
        when(googleDriveService.getFileChecksum(drive, "still-valid-file-id")).thenReturn("different-md5");

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
        when(noteRepository.findByDriveFileId("removed-on-drive-id")).thenReturn(Optional.of(note));
        // self.syncNote() (goi lai NGAY sau khi danh dau dirty) se tu tim lai
        // note qua findById() - can stub rieng vi day la 1 loi goi KHAC voi
        // findByDriveFileId() o tren.
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
                "trashed-but-changed-id", "Note.txt", 10L, null, null, "text/plain");
        when(googleDriveService.listChanges(drive, "existing-page-token", "folder-1")).thenReturn(
                new GoogleDriveService.ChangesResult(List.of(changedFile), List.of(), "new-page-token"));
        when(noteRepository.findByDriveFileId("trashed-but-changed-id")).thenReturn(Optional.of(note));
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
                "folder-1", "NotedApp", null, null, null, "application/vnd.google-apps.folder");
        when(googleDriveService.listChanges(drive, "existing-page-token", "folder-1")).thenReturn(
                new GoogleDriveService.ChangesResult(List.of(folderChange), List.of(), "new-page-token"));

        service.pullFromDrive(USER_ID);

        // Khong duoc goi bat ky API nao lien quan den "xu ly nhu 1 note" cho folder
        verify(googleDriveService, never()).downloadFileContent(any(), anyString());
        verify(googleDriveService, never()).isFileTrashed(any(), anyString());
        verify(noteRepository, never()).findByDriveFileId(anyString());
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
}
