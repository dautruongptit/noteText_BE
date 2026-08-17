package com.noted.backend.service.impl;

import com.noted.backend.domain.entity.Note;
import com.noted.backend.domain.enums.SyncState;
import com.noted.backend.dto.request.CreateNoteRequest;
import com.noted.backend.dto.request.RenameNoteRequest;
import com.noted.backend.dto.request.UpdateContentRequest;
import com.noted.backend.dto.response.BulkDeleteResponse;
import com.noted.backend.dto.response.NoteDetailResponse;
import com.noted.backend.dto.response.NoteSummaryResponse;
import com.noted.backend.event.NoteDeletedEvent;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Trong tam cua bo test nay la CO CHE "TRUNG TEN -> GHI DE" (xem muc 2):
 * createNote()/rename() KHONG con tu choi ten trung nua, ma ghi de note dang
 * co san bang noi dung moi (giu nguyen id/uuid cua note bi ghi de).
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

    // ---------- 1. Tao note - duong thanh cong (ten chua ton tai) ----------

    @Test
    void createNote_thanhCong_khiTenChuaTonTai() {
        when(noteRepository.findByUserIdAndDisplayNameAndDeletedFalse(USER_ID, "Welcome.txt"))
                .thenReturn(Optional.empty());
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            if (n.getId() == null) n.setId(100L);
            return n;
        });

        NoteDetailResponse result = noteService.createNote(USER_ID, new CreateNoteRequest("Welcome.txt", "hello"));

        assertThat(result.displayName()).isEqualTo("Welcome.txt");
        assertThat(result.content()).isEqualTo("hello");
        assertThat(result.syncState()).isEqualTo(SyncState.PENDING_DRIVE.name());
        verify(fileStorageService).writeAtomic(anyString(), eq("hello"));
        // Debounce Sync (khong con publish event ngay khi tao note, xem NoteServiceImpl) -
        // note chi duoc danh dau dirty=true, cho job dinh ky/"Dong bo ngay" xu ly rieng
        verifyNoInteractions(eventPublisher);
    }

    // ---------- 2. TRUNG TEN -> GHI DE (thay cho tu choi 409 truoc day) ----------

    @Test
    void createNote_ghiDeNoteDaCoSan_khiTenTrung() {
        Note existingNote = ownedNote(7L, "Note.txt");
        when(noteRepository.findByUserIdAndDisplayNameAndDeletedFalse(USER_ID, "Note.txt"))
                .thenReturn(Optional.of(existingNote));
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteDetailResponse result = noteService.createNote(USER_ID, new CreateNoteRequest("Note.txt", "noi dung moi"));

        // Van la note CU (id 7L) - khong tao note moi, chi ghi de noi dung
        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.content()).isEqualTo("noi dung moi");
        verify(fileStorageService).writeAtomic(anyString(), eq("noi dung moi"));
        assertThat(existingNote.isDirty()).isTrue();
        assertThat(existingNote.getSyncState()).isEqualTo(SyncState.PENDING_DRIVE);
        // Ghi de = 1 lan "ghi" that su -> version phai tang, de client cam
        // baseVersion cu (truoc khi bi ghi de) tu phat hien lech o lan sync sau
        assertThat(existingNote.getVersion()).isEqualTo(2);
    }

    @Test
    void rename_ghiDeNoteDich_vaXoaMemNoteNguon_khiTenTrung() {
        Note source = ownedNote(5L, "Draft.txt");
        Note target = ownedNote(9L, "Report.txt");
        when(noteRepository.findByIdAndUserIdAndDeletedFalse(5L, USER_ID)).thenReturn(Optional.of(source));
        when(noteRepository.findByUserIdAndDisplayNameAndDeletedFalse(USER_ID, "Report.txt"))
                .thenReturn(Optional.of(target));
        when(fileStorageService.read(source.getFilePath())).thenReturn("noi dung cua Draft");
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteSummaryResponse result = noteService.rename(USER_ID, 5L, new RenameNoteRequest("Report.txt"));

        // Ket qua tra ve la note DICH (id 9L, khong phai 5L) - id thay doi bao
        // hieu cho FE biet day la 1 lan "nhap" (merge), khong phai doi ten binh thuong
        assertThat(result.id()).isEqualTo(9L);
        verify(fileStorageService).writeAtomic(target.getFilePath(), "noi dung cua Draft");
        assertThat(target.isDirty()).isTrue();
        assertThat(target.getVersion()).isEqualTo(2); // note dich vua bi ghi de -> tang version
        // Note nguon bi xoa mem, KHONG xoa cung - va Drive cua no duoc don ngay qua event
        assertThat(source.isDeleted()).isTrue();
        assertThat(source.getDeletedAt()).isNotNull();
        // NoteDeletedEvent la record (co equals()/hashCode() tu sinh theo noteId) -
        // dung eq() truc tiep thay vi argThat()+instanceof (argThat de bi Mockito
        // suy luan nham sang overload publishEvent(ApplicationEvent) vi
        // NoteDeletedEvent khong extends ApplicationEvent, gay loi compile).
        verify(eventPublisher).publishEvent(eq(new NoteDeletedEvent(5L)));
    }

    @Test
    void rename_doiTenBinhThuong_khiTenMoiChuaTonTai() {
        Note note = ownedNote(5L, "Draft.txt");
        when(noteRepository.findByIdAndUserIdAndDeletedFalse(5L, USER_ID)).thenReturn(Optional.of(note));
        when(noteRepository.findByUserIdAndDisplayNameAndDeletedFalse(USER_ID, "Final.txt"))
                .thenReturn(Optional.empty());
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteSummaryResponse result = noteService.rename(USER_ID, 5L, new RenameNoteRequest("Final.txt"));

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.displayName()).isEqualTo("Final.txt");
        verify(fileStorageService, never()).writeAtomic(anyString(), anyString());
        verifyNoInteractions(eventPublisher);
        assertThat(note.getVersion()).isEqualTo(2); // doi ten cung la 1 lan "ghi" -> tang version
    }

    // ---------- 2b. "Giu ca 2 ban khi conflict" (SyncController.syncBatch) ----------

    @Test
    void createConflictCopy_taoNoteMoiRiengBietVoiHauToXungDot_khongDongDenNoteGoc() {
        when(noteRepository.existsByUserIdAndDisplayNameAndDeletedFalse(eq(USER_ID), anyString()))
                .thenReturn(false); // resolveAvailableName() thay ten hau to chua trung ai
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            if (n.getId() == null) n.setId(200L);
            return n;
        });

        NoteDetailResponse result = noteService.createConflictCopy(USER_ID, "Draft.txt", "noi dung ban local");

        assertThat(result.content()).isEqualTo("noi dung ban local");
        // Chuoi khong dau (dung quy uoc toan bo string BE hien co, xem VD
        // GlobalExceptionHandler "Da co loi xay ra..." - khong dung dau de
        // tranh rui ro mojibake o moi tang encoding)
        assertThat(result.displayName()).startsWith("Draft (xung dot ");
        assertThat(result.displayName()).endsWith(".txt");
        assertThat(result.version()).isEqualTo(1); // note MOI hoan toan -> bat dau tu 1
        verify(fileStorageService).writeAtomic(anyString(), eq("noi dung ban local"));
        // Note GOC (Draft.txt that su, id/uuid rieng) khong bi dong den o day -
        // createConflictCopy() CHI nhan ten/noi dung, khong nhan/sua entity nao co san.
        verify(noteRepository, never()).findByIdAndUserIdAndDeletedFalse(anyLong(), anyLong());
    }

    // ---------- 2c. updateContent() - "phuong an 2": LUON ghi de, khong kiem
    // tra baseVersion (khac SyncController.syncBatch, noi baseVersion THAT SU
    // duoc doi chieu) - chi tang version dung de cac noi khac dung sau ----------

    @Test
    void updateContent_luonGhiDe_khongKiemTraGiCa_vaTangVersion() {
        Note note = ownedNote(5L, "Draft.txt");
        when(noteRepository.findByIdAndUserIdAndDeletedFalse(5L, USER_ID)).thenReturn(Optional.of(note));
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteDetailResponse result = noteService.updateContent(USER_ID, 5L, new UpdateContentRequest("noi dung moi"));

        assertThat(result.content()).isEqualTo("noi dung moi");
        assertThat(result.version()).isEqualTo(2); // tu 1 (mac dinh Note moi) len 2
        verify(fileStorageService).writeAtomic(note.getFilePath(), "noi dung moi");
        assertThat(note.getSyncState()).isEqualTo(SyncState.PENDING_DRIVE);
        assertThat(note.isDirty()).isTrue();
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
