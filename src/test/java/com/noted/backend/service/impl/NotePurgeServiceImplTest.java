package com.noted.backend.service.impl;

import com.noted.backend.domain.entity.Note;
import com.noted.backend.domain.enums.SyncState;
import com.noted.backend.repository.NoteRepository;
import com.noted.backend.service.DriveSyncService;
import com.noted.backend.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotePurgeServiceImplTest {

    @Test
    void purgeExpiredNotes_xoaFileVaDbRecord_choNoteQuaHanGiuLai() {
        NoteRepository noteRepository = mock(NoteRepository.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        DriveSyncService driveSyncService = mock(DriveSyncService.class);
        NotePurgeServiceImpl service = newService(noteRepository, fileStorageService, driveSyncService);

        Note withDrive = expiredNote(1L, "1/uuid1.txt", "driveFile1");
        Note withoutDrive = expiredNote(2L, "1/uuid2.txt", null);
        when(noteRepository.findTop100ByDeletedTrueAndDeletedAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(withDrive, withoutDrive));

        service.purgeExpiredNotes();

        // File vat ly: xoa CA HAI note, khong phan biet co drive hay khong
        verify(fileStorageService).delete("1/uuid1.txt");
        verify(fileStorageService).delete("1/uuid2.txt");
        // Drive: CHI goi luoi an toan cho note CO driveFileId (best-effort du phong)
        verify(driveSyncService).deleteFromDrive(1L);
        verify(driveSyncService, never()).deleteFromDrive(2L);
        // DB: xoa vinh vien ca 2 record trong 1 lan
        verify(noteRepository).deleteAll(List.of(withDrive, withoutDrive));
    }

    @Test
    void purgeExpiredNotes_khongLamGi_khiChuaCoNoteNaoQuaHan() {
        NoteRepository noteRepository = mock(NoteRepository.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        DriveSyncService driveSyncService = mock(DriveSyncService.class);
        NotePurgeServiceImpl service = newService(noteRepository, fileStorageService, driveSyncService);

        when(noteRepository.findTop100ByDeletedTrueAndDeletedAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of());

        service.purgeExpiredNotes();

        verifyNoInteractions(fileStorageService, driveSyncService);
        verify(noteRepository, never()).deleteAll(anyList());
    }

    @Test
    void purgeExpiredNotes_vanXoaDbRecord_khiXoaFileVatLyThatBai() {
        // File vat ly loi (VD da bi xoa tay tren disk tu truoc) KHONG duoc
        // chan viec xoa DB record - purge van phai hoan tat.
        NoteRepository noteRepository = mock(NoteRepository.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        DriveSyncService driveSyncService = mock(DriveSyncService.class);
        NotePurgeServiceImpl service = newService(noteRepository, fileStorageService, driveSyncService);

        Note note = expiredNote(3L, "1/uuid3.txt", null);
        when(noteRepository.findTop100ByDeletedTrueAndDeletedAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(note));
        doThrow(new RuntimeException("disk error")).when(fileStorageService).delete("1/uuid3.txt");

        assertThatCode(service::purgeExpiredNotes).doesNotThrowAnyException();

        verify(noteRepository).deleteAll(List.of(note));
    }

    // ---------- helpers ----------

    private NotePurgeServiceImpl newService(NoteRepository noteRepository, FileStorageService fileStorageService,
                                              DriveSyncService driveSyncService) {
        NotePurgeServiceImpl service = new NotePurgeServiceImpl(noteRepository, fileStorageService, driveSyncService);
        ReflectionTestUtils.setField(service, "purgeAfterDays", 30);
        return service;
    }

    private Note expiredNote(Long id, String filePath, String driveFileId) {
        Note n = Note.builder()
                .userId(1L)
                .displayName("Old " + id + ".txt")
                .syncState(SyncState.SYNCED)
                .deleted(true)
                .driveFileId(driveFileId)
                .build();
        n.setId(id);
        n.setFilePath(filePath);
        n.setDeletedAt(LocalDateTime.now().minusDays(31));
        return n;
    }
}
