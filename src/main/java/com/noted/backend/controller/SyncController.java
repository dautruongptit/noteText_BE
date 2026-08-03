package com.noted.backend.controller;

import com.noted.backend.domain.entity.Note;
import com.noted.backend.dto.request.CreateNoteRequest;
import com.noted.backend.dto.request.SyncBatchItem;
import com.noted.backend.dto.request.UpdateContentRequest;
import com.noted.backend.repository.NoteRepository;
import com.noted.backend.service.NoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Endpoint danh rieng cho client "offline-first": khi trinh duyet mat ket noi toi server
 * (Ubuntu server tam thoi khong vao duoc), noi dung van duoc luu trong IndexedDB o may
 * nguoi dung. Khi ket noi lai duoc, client goi endpoint nay 1 lan de day toan bo
 * cac note dang o trang thai "pending_server".
 *
 * Chien luoc giai quyet xung dot don gian: "ban co updatedAt moi hon thang".
 * Neu can UX tot hon (hien ca 2 ban cho nguoi dung chon), doi ket qua tra ve them
 * truong "serverContent" khi phat hien conflict thay vi tu dong ghi de.
 */
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final NoteService noteService;
    private final NoteRepository noteRepository;

    @PostMapping("/batch")
    public Map<String, Object> syncBatch(@AuthenticationPrincipal Long userId,
                                          @RequestBody List<SyncBatchItem> items) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (SyncBatchItem item : items) {
            try {
                if (item.noteId() == null) {
                    // Note duoc tao hoan toan trong luc offline -> tao moi tren server
                    var created = noteService.createNote(userId,
                            new CreateNoteRequest(item.displayName(), item.content()));
                    results.add(Map.of(
                            "clientTempId", item.clientTempId(),
                            "status", "created",
                            "serverId", created.id()
                    ));
                    continue;
                }

                Note existing = noteRepository.findByIdAndUserIdAndDeletedFalse(item.noteId(), userId)
                        .orElse(null);

                if (existing == null) {
                    results.add(Map.of("noteId", item.noteId(), "status", "not_found_on_server"));
                    continue;
                }

                long serverEpochMs = existing.getUpdatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();

                if (item.localUpdatedAtEpochMs() != null && item.localUpdatedAtEpochMs() < serverEpochMs) {
                    // Server co ban moi hon (VD da sua tu thiet bi khac) -> bao conflict, khong ghi de
                    results.add(Map.of(
                            "noteId", item.noteId(),
                            "status", "conflict",
                            "serverUpdatedAt", existing.getUpdatedAt().toString()
                    ));
                    continue;
                }

                noteService.updateContent(userId, item.noteId(),
                        new UpdateContentRequest(item.content(), item.localUpdatedAtEpochMs()));
                results.add(Map.of("noteId", item.noteId(), "status", "synced"));

            } catch (Exception e) {
                results.add(Map.of(
                        "noteId", item.noteId() == null ? "" : item.noteId(),
                        "clientTempId", item.clientTempId() == null ? "" : item.clientTempId(),
                        "status", "error",
                        "message", e.getMessage()
                ));
            }
        }

        return Map.of("results", results);
    }
}
