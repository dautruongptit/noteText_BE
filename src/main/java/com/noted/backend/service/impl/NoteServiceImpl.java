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
import com.noted.backend.service.NoteService;
import com.noted.backend.util.HashUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * QUAN TRONG - luong dong bo Drive DA DOI (xem DriveSyncServiceImpl, "Debounce
 * Sync"): truoc day moi lan sua note deu publish NoteContentChangedEvent de
 * kich hoat sync GAN NHU NGAY LAP TUC. Gio day CHI danh dau note.dirty=true -
 * VIEC DAY LEN DRIVE duoc doi thanh 3 kenh rieng biet:
 *   1. Job debounce dinh ky (note "yen tinh" 30s khong sua them)
 *   2. Nguoi dung bam "Dong bo ngay" (POST /api/drive/sync-all)
 *   3. Trinh duyet flush luc dong tab/roi trang (cung goi /api/drive/sync-all)
 * Xem chi tiet trong DriveSyncServiceImpl.
 */
@Service
@RequiredArgsConstructor
public class NoteServiceImpl implements NoteService {

    private static final DateTimeFormatter CONFLICT_SUFFIX_FORMAT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final NoteRepository noteRepository;
    private final FileStorageService fileStorageService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public List<NoteSummaryResponse> listNotes(Long userId) {
        return noteRepository.findByUserIdAndDeletedFalseOrderByUpdatedAtDesc(userId)
                .stream()
                .map(NoteSummaryResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public NoteDetailResponse getNote(Long userId, Long noteId) {
        Note note = findOwnedNote(userId, noteId);
        String content = fileStorageService.read(note.getFilePath());
        return NoteDetailResponse.of(note, content);
    }

    @Override
    @Transactional
    public NoteDetailResponse createNote(Long userId, CreateNoteRequest request) {
        String displayName = request.displayName().trim();
        String content = request.initialContent() != null ? request.initialContent() : "";

        // Khong cho phep 2 note active cung ten trong 1 user. Thay vi tu choi
        // (409 DUPLICATE_FILE_NAME nhu truoc), GHI DE note dang co san bang
        // noi dung moi - giu nguyen id/uuid/lien ket Drive (drive_file_id) cua
        // note cu, chi thay content/hash/size. Khong con UNIQUE constraint o DB
        // nua (V4__drop_notes_unique_name.sql) nen khong co race-condition
        // fallback rieng - "ghi de" tu no la ket qua an toan ke ca khi 2
        // request gan nhu dong thoi cung tao 1 ten (ai save() sau se thang,
        // khong con truong hop bi DB tu choi giua chung).
        Optional<Note> existing = noteRepository.findByUserIdAndDisplayNameAndDeletedFalse(userId, displayName);
        if (existing.isPresent()) {
            Note note = existing.get();
            writeContentAndUpdateMetadata(note, content);
            note.setSyncState(SyncState.PENDING_DRIVE);
            note.setDirty(true);
            note.setDriveSyncAttempts(0);
            note = noteRepository.save(note);
            return NoteDetailResponse.of(note, content);
        }

        Note note = Note.builder()
                .userId(userId)
                .displayName(displayName)
                .syncState(SyncState.PENDING_DRIVE)
                .dirty(true) // note moi -> chua tung len Drive, can duoc debounce-sync
                .build();
        note.setFilePath(fileStorageService.buildRelativePath(userId, note.getUuid()));

        note = noteRepository.save(note);
        writeContentAndUpdateMetadata(note, content);
        note = noteRepository.save(note);

        return NoteDetailResponse.of(note, content);
    }
    @Override
    @Transactional
    public NoteDetailResponse updateContent(Long userId, Long noteId, UpdateContentRequest request) {
        Note note = findOwnedNote(userId, noteId);

        writeContentAndUpdateMetadata(note, request.content());
        note.setSyncState(SyncState.PENDING_DRIVE); // moi thay doi -> can day len Drive lai
        note.setDirty(true); // "Auto Save vao DB" xong -> danh dau dirty, cho Debounce Sync xu ly rieng
        note.setDriveSyncAttempts(0);
        note = noteRepository.save(note);

        return NoteDetailResponse.of(note, request.content());
    }

    @Override
    @Transactional
    public NoteSummaryResponse rename(Long userId, Long noteId, RenameNoteRequest request) {
        Note note = findOwnedNote(userId, noteId);
        String newName = request.newDisplayName().trim();

        if (newName.equals(note.getDisplayName())) {
            return NoteSummaryResponse.from(note);
        }

        Optional<Note> collision = noteRepository.findByUserIdAndDisplayNameAndDeletedFalse(userId, newName);
        if (collision.isPresent()) {
            // Doi ten trung voi 1 note KHAC dang ton tai -> "ghi de file moi":
            // note DICH (collision) duoc GHI DE bang noi dung cua note NGUON
            // (note dang doi ten), giu nguyen id/uuid/lien ket Drive cua note
            // DICH. Note NGUON coi nhu da "nhap" vao note dich -> xoa mem (cung
            // luong voi delete(), don Drive ngay qua NoteDeletedEvent).
            Note target = collision.get();
            String sourceContent = fileStorageService.read(note.getFilePath());

            writeContentAndUpdateMetadata(target, sourceContent);
            target.setSyncState(SyncState.PENDING_DRIVE);
            target.setDirty(true);
            target.setDriveSyncAttempts(0);
            target = noteRepository.save(target);

            note.setDeleted(true);
            note.setDeletedAt(LocalDateTime.now());
            noteRepository.save(note);
            eventPublisher.publishEvent(new NoteDeletedEvent(note.getId()));

            return NoteSummaryResponse.from(target);
        }

        // Chi doi ten hien thi trong DB, KHONG dung den file vat ly (ten vat ly la UUID co dinh)
        note.setDisplayName(newName);
        // Ten hien thi cung la ten file tren Drive (xem GoogleDriveService.updateFile) -
        // doi ten cung can day len Drive lai, danh dau dirty tuong tu doi content.
        note.setDirty(true);
        note = noteRepository.save(note);

        return NoteSummaryResponse.from(note);
    }

    @Override
    @Transactional
    public NoteDetailResponse duplicate(Long userId, Long noteId) {
        Note source = findOwnedNote(userId, noteId);
        String baseName = stripExtension(source.getDisplayName());
        String ext = extractExtension(source.getDisplayName());

        String candidateName = resolveAvailableName(userId, baseName + " (copy)" + ext);

        Note copy = Note.builder()
                .userId(userId)
                .displayName(candidateName)
                .syncState(SyncState.PENDING_DRIVE)
                .dirty(true) // ban sao la 1 note MOI tren Drive (driveFileId rieng, khong dung chung voi ban goc)
                .build();
        copy.setFilePath(fileStorageService.buildRelativePath(userId, copy.getUuid()));

        // Khac voi createNote()/rename(): ten o day la TU SINH ("X (copy)"),
        // khong phai nguoi dung go - ghi de 1 note khac chi vi trung ten tu
        // sinh se gay bat ngo/mat du lieu vo ly, nen GIU NGUYEN co che tu tim
        // ten trong (resolveAvailableName) thay vi ghi de nhu 2 ham tren.
        // Khong con try/catch DataIntegrityViolationException o day nua - tu
        // khi bo UNIQUE(user_id, display_name) (V4__drop_notes_unique_name.sql)
        // constraint nay khong con ton tai de nem loi nua.
        copy = noteRepository.save(copy);

        fileStorageService.copy(source.getFilePath(), copy.getFilePath());
        String content = fileStorageService.read(copy.getFilePath());

        copy.setContentHash(HashUtil.sha256(content));
        copy.setContentSizeBytes((long) content.getBytes(StandardCharsets.UTF_8).length);
        copy = noteRepository.save(copy);

        return NoteDetailResponse.of(copy, content);
    }

    @Override
    @Transactional
    public NoteDetailResponse createConflictCopy(Long userId, String originalDisplayName, String content) {
        String suffix = " (xung dot " + LocalDateTime.now().format(CONFLICT_SUFFIX_FORMAT) + ")";
        String candidateName = resolveAvailableName(userId,
                stripExtension(originalDisplayName) + suffix + extractExtension(originalDisplayName));

        Note copy = Note.builder()
                .userId(userId)
                .displayName(candidateName)
                .syncState(SyncState.PENDING_DRIVE)
                .dirty(true) // ban conflict cung can duoc day len Drive nhu 1 note binh thuong
                .conflictCopy(true)
                .build();
        copy.setFilePath(fileStorageService.buildRelativePath(userId, copy.getUuid()));

        copy = noteRepository.save(copy);
        writeContentAndUpdateMetadata(copy, content);
        copy = noteRepository.save(copy);

        return NoteDetailResponse.of(copy, content);
    }

    @Override
    @Transactional
    public void delete(Long userId, Long noteId) {
        Note note = findOwnedNote(userId, noteId);
        note.setDeleted(true);
        note.setDeletedAt(LocalDateTime.now());
        noteRepository.save(note);
        // File vat ly + ban tren Drive: file vat ly don qua purge job (30 ngay,
        // SEC-12), rieng ban tren Drive duoc xoa NGAY LAP TUC qua event nay
        // (xem NoteDeletedEvent/NoteSyncEventListener) - khong doi den purge.
        eventPublisher.publishEvent(new NoteDeletedEvent(note.getId()));
    }

    /**
     * Xoa nhieu note cung luc - tinh nang "chon nhieu de xoa".
     *
     * Co tinh KHONG lam tuong tu cho "chon nhieu de sync": dong bo Drive la co che
     * tu dong/toan bo (moi note dirty deu duoc Debounce Sync xu ly), bat nguoi
     * dung chon file nao de sync se tao rui ro quen sync -> mat du lieu.
     */
    @Override
    @Transactional
    public BulkDeleteResponse bulkDelete(Long userId, List<Long> noteIds) {
        List<Note> owned = noteRepository.findByIdInAndUserIdAndDeletedFalse(noteIds, userId);

        LocalDateTime now = LocalDateTime.now();
        for (Note note : owned) {
            note.setDeleted(true);
            note.setDeletedAt(now);
        }
        noteRepository.saveAll(owned);

        List<Long> deletedIds = owned.stream().map(Note::getId).toList();
        // Xoa Drive NGAY LAP TUC cho TUNG note trong danh sach (khong doi purge job)
        deletedIds.forEach(id -> eventPublisher.publishEvent(new NoteDeletedEvent(id)));

        return new BulkDeleteResponse(noteIds.size(), deletedIds.size(), deletedIds);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isNameTaken(Long userId, String displayName) {
        return noteRepository.existsByUserIdAndDisplayNameAndDeletedFalse(userId, displayName.trim());
    }

    @Override
    @Transactional(readOnly = true)
    public String resolveAvailableName(Long userId, String desiredName) {
        if (!isNameTaken(userId, desiredName)) {
            return desiredName;
        }
        String base = stripExtension(desiredName);
        String ext = extractExtension(desiredName);

        int counter = 2;
        String candidate;
        do {
            candidate = base + " (" + counter + ")" + ext;
            counter++;
        } while (isNameTaken(userId, candidate));

        return candidate;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] downloadAsBytes(Long userId, Long noteId) {
        Note note = findOwnedNote(userId, noteId);
        return fileStorageService.read(note.getFilePath()).getBytes(StandardCharsets.UTF_8);
    }

    // ---------- helpers ----------

    private Note findOwnedNote(Long userId, Long noteId) {
        return noteRepository.findByIdAndUserIdAndDeletedFalse(noteId, userId)
                .orElseThrow(() -> new NoteNotFoundException(noteId));
    }

    private void writeContentAndUpdateMetadata(Note note, String content) {
        long bytesWritten = fileStorageService.writeAtomic(note.getFilePath(), content);
        note.setContentSizeBytes(bytesWritten);
        note.setContentHash(HashUtil.sha256(content));
    }

    private String stripExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private String extractExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(idx) : "";
    }
}
