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
