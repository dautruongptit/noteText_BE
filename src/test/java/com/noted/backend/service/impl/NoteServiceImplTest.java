package com.noted.backend.service.impl;

import com.noted.backend.domain.entity.Note;
import com.noted.backend.domain.enums.SyncState;
import com.noted.backend.dto.request.CreateNoteRequest;
import com.noted.backend.dto.request.RenameNoteRequest;
import com.noted.backend.dto.response.BulkDeleteResponse;
import com.noted.backend.dto.response.NoteDetailResponse;
import com.noted.backend.exception.DuplicateFileNameException;
import com.noted.backend.exception.NoteNotFoundException;
import com.noted.backend.repository.NoteRepository;
import com.noted.backend.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Trong tam cua bo test nay la CASE RACE CONDITION TRUNG TEN (xem muc 2) -
 * day la ly do NoteServiceImpl phai co 2 lop bao ve (check o service + UNIQUE
 * constraint o DB) thay vi chi 1 lop, va bo test phai xac nhan CA HAI deu hoat dong.
 */
@ExtendWith(MockitoExtension.class)
class NoteServiceImplTest {

    @Mock private NoteRepository noteRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private NoteServiceImpl noteService;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        lenient().when(fileStorageService.buildRelativePath(anyLong(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + "/" + inv.getArgument(1) + ".txt");
        lenient().when(fileStorageService.writeAtomic(anyString(), anyString())).thenReturn(0L);
    }

    // ---------- 1. Tao note - duong thanh cong ----------

    @Test
    void createNote_thanhCong_khiTenChuaTonTai() {
        when(noteRepository.existsByUserIdAndDisplayNameAndDeletedFalse(USER_ID, "Welcome.txt"))
                .thenReturn(false);
        when(noteRepository.saveAndFlush(any(Note.class))).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            n.setId(100L);
            return n;
        });
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteDetailResponse result = noteService.createNote(USER_ID, new CreateNoteRequest("Welcome.txt", "hello"));

        assertThat(result.displayName()).isEqualTo("Welcome.txt");
        assertThat(result.content()).isEqualTo("hello");
        verify(fileStorageService).writeAtomic(anyString(), eq("hello"));
        // Event phai duoc phat de kich hoat sync Drive gan nhu ngay lap tuc (SEC-03)
        verify(eventPublisher).publishEvent(any());
    }

    // ---------- 2. RACE CONDITION TRUNG TEN - trong tam cua SEC-11 ----------

    @Test
    void createNote_baoLoiNgay_khiServiceLayerDaThayTenTrung() {
        // Truong hop binh thuong (khong race condition): check o tang service
        // da phat hien duoc luon, KHONG can dung toi DB constraint.
        when(noteRepository.existsByUserIdAndDisplayNameAndDeletedFalse(USER_ID, "Note.txt"))
                .thenReturn(true);

        assertThatThrownBy(() -> noteService.createNote(USER_ID, new CreateNoteRequest("Note.txt", "")))
                .isInstanceOf(DuplicateFileNameException.class);

        // QUAN TRONG: khong duoc goi saveAndFlush/ghi file gi ca neu da bi chan tu som
        verify(noteRepository, never()).saveAndFlush(any());
        verify(fileStorageService, never()).writeAtomic(anyString(), anyString());
    }

    @Test
    void createNote_vanBaoLoiDuplicate_khiRaceConditionVuotQuaCheckOTangService() {
        // Mo phong CHINH XAC tinh huong race condition: 2 request tao file cung
        // ten GAN NHU DONG THOI. Tai thoi diem ca 2 cung goi
        // existsByUserIdAndDisplayNameAndDeletedFalse(), CA HAI DEU thay false
        // (vi chua request nao insert xong) -> ca 2 cung vuot qua duoc buoc check
        // o tang service. Request THU HAI insert sau se bi UNIQUE constraint o DB
        // tu choi, Spring Data JPA nem ra DataIntegrityViolationException.
        when(noteRepository.existsByUserIdAndDisplayNameAndDeletedFalse(USER_ID, "New Note.txt"))
                .thenReturn(false); // ca 2 request "thay" chua trung luc kiem tra
        when(noteRepository.saveAndFlush(any(Note.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key uq_notes_user_name"));

        assertThatThrownBy(() -> noteService.createNote(USER_ID, new CreateNoteRequest("New Note.txt", "")))
                .isInstanceOf(DuplicateFileNameException.class);

        // UNIQUE constraint (lop bao ve THU 2) da chan thanh cong - dung nhu thiet ke.
        // Ghi file KHONG duoc goi vi exception xay ra truoc buoc writeContentAndUpdateMetadata.
        verify(fileStorageService, never()).writeAtomic(anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rename_baoLoiDuplicate_khiRaceConditionVuotQuaCheckOTangService() {
        Note existing = ownedNote(5L, "Draft.txt");
        when(noteRepository.findByIdAndUserIdAndDeletedFalse(5L, USER_ID)).thenReturn(Optional.of(existing));
        when(noteRepository.existsByUserIdAndDisplayNameAndDeletedFalse(USER_ID, "Report.txt")).thenReturn(false);
        when(noteRepository.saveAndFlush(any(Note.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry"));

        assertThatThrownBy(() -> noteService.rename(USER_ID, 5L, new RenameNoteRequest("Report.txt")))
                .isInstanceOf(DuplicateFileNameException.class);
    }

    // ---------- 3. Note khong ton tai / khong thuoc user ----------

    @Test
    void getNote_nemNoteNotFound_khiKhongThuocUserHienTai() {
        when(noteRepository.findByIdAndUserIdAndDeletedFalse(99L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> noteService.getNote(USER_ID, 99L))
                .isInstanceOf(NoteNotFoundException.class);
    }

    // ---------- 4. Bulk delete - chi xoa note THUOC SO HUU user, id la loai am tham ----------

    @Test
    void bulkDelete_chiXoaNoteThuocSoHuu_boQuaIdLaKhongThrow() {
        Note owned1 = ownedNote(1L, "A.txt");
        Note owned2 = ownedNote(2L, "B.txt");
        // Gia lap: trong 3 id gui len, chi 2 id thuoc ve user nay (id thu 3 la
        // cua user khac hoac khong ton tai) - repository tu loc, KHONG throw
        when(noteRepository.findByIdInAndUserIdAndDeletedFalse(List.of(1L, 2L, 3L), USER_ID))
                .thenReturn(List.of(owned1, owned2));

        BulkDeleteResponse response = noteService.bulkDelete(USER_ID, List.of(1L, 2L, 3L));

        assertThat(response.requestedCount()).isEqualTo(3);
        assertThat(response.deletedCount()).isEqualTo(2);
        assertThat(response.deletedIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(owned1.isDeleted()).isTrue();
        assertThat(owned2.isDeleted()).isTrue();
        verify(noteRepository).saveAll(List.of(owned1, owned2));
    }

    // ---------- helpers ----------

    private Note ownedNote(Long id, String name) {
        Note n = Note.builder()
                .userId(USER_ID)
                .displayName(name)
                .syncState(SyncState.SYNCED)
                .build();
        n.setId(id);
        n.setFilePath(USER_ID + "/" + n.getUuid() + ".txt");
        n.setCreatedAt(LocalDateTime.now());
        n.setUpdatedAt(LocalDateTime.now());
        return n;
    }
}
