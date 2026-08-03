package com.noted.backend.controller;

import com.noted.backend.dto.request.BulkDeleteRequest;
import com.noted.backend.dto.request.CreateNoteRequest;
import com.noted.backend.dto.request.RenameNoteRequest;
import com.noted.backend.dto.request.UpdateContentRequest;
import com.noted.backend.dto.response.BulkDeleteResponse;
import com.noted.backend.dto.response.NoteDetailResponse;
import com.noted.backend.dto.response.NoteSummaryResponse;
import com.noted.backend.service.NoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;

    @GetMapping
    public List<NoteSummaryResponse> list(@AuthenticationPrincipal Long userId) {
        return noteService.listNotes(userId);
    }

    @GetMapping("/{id}")
    public NoteDetailResponse get(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return noteService.getNote(userId, id);
    }

    @PostMapping
    public ResponseEntity<NoteDetailResponse> create(@AuthenticationPrincipal Long userId,
                                                       @Valid @RequestBody CreateNoteRequest request) {
        NoteDetailResponse created = noteService.createNote(userId, request);
        return ResponseEntity.status(201).body(created);
    }

    /** Dung cho auto-save - goi thuong xuyen (debounce phia client) */
    @PatchMapping("/{id}/content")
    public NoteDetailResponse updateContent(@AuthenticationPrincipal Long userId,
                                             @PathVariable Long id,
                                             @Valid @RequestBody UpdateContentRequest request) {
        return noteService.updateContent(userId, id, request);
    }

    @PatchMapping("/{id}/rename")
    public NoteSummaryResponse rename(@AuthenticationPrincipal Long userId,
                                       @PathVariable Long id,
                                       @Valid @RequestBody RenameNoteRequest request) {
        return noteService.rename(userId, id, request);
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<NoteDetailResponse> duplicate(@AuthenticationPrincipal Long userId,
                                                          @PathVariable Long id) {
        return ResponseEntity.status(201).body(noteService.duplicate(userId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        noteService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    /** Chon nhieu de xoa - tinh nang moi. Cham 1 lan cho ca danh sach, 1 transaction o backend. */
    @PostMapping("/bulk-delete")
    public BulkDeleteResponse bulkDelete(@AuthenticationPrincipal Long userId,
                                          @Valid @RequestBody BulkDeleteRequest request) {
        return noteService.bulkDelete(userId, request.noteIds());
    }

    /** Validate real-time khi nguoi dung go ten file (goi tu debounced input o frontend) */
    @GetMapping("/check-name")
    public Map<String, Object> checkName(@AuthenticationPrincipal Long userId,
                                          @RequestParam String name) {
        boolean taken = noteService.isNameTaken(userId, name);
        return Map.of(
                "taken", taken,
                "suggestion", taken ? noteService.resolveAvailableName(userId, name) : name
        );
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        NoteDetailResponse note = noteService.getNote(userId, id);
        byte[] bytes = noteService.downloadAsBytes(userId, id);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + note.displayName() + "\"")
                .body(bytes);
    }
}
