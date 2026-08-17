package com.noted.backend.controller;

import com.noted.backend.domain.entity.Note;
import com.noted.backend.dto.request.CreateNoteRequest;
import com.noted.backend.dto.request.SyncBatchItem;
import com.noted.backend.dto.request.UpdateContentRequest;
import com.noted.backend.repository.NoteRepository;
import com.noted.backend.service.NoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Endpoint danh rieng cho client "offline-first": khi trinh duyet mat ket noi toi server
 * (Ubuntu server tam thoi khong vao duoc), noi dung van duoc luu trong IndexedDB o may
 * nguoi dung. Khi ket noi lai duoc, client goi endpoint nay 1 lan de day toan bo
 * cac note dang o trang thai "pending_server".
 *
 * Chien luoc giai quyet xung dot: "GIU CA 2 BAN" (doi tu ban dau "ai updatedAt
 * moi hon thang, ban kia bi bo") - khi server phat hien ban cua item nay CU
 * hon ban dang co, KHONG am tham bo/ghi de nua: ban server giu nguyen (van la
 * "ban thang"), con ban local duoc tach thanh 1 note MOI rieng bang
 * NoteService.createConflictCopy() ("ban xung dot", ten co hau to "(xung dot
 * dd/MM HH:mm)") - nguoi dung tu kiem tra/gop lai sau, khong mat du lieu nao.
 */
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final NoteService noteService;
    private final NoteRepository noteRepository;

    // @Valid tren List<SyncBatchItem> validate TUNG PHAN TU (displayName/content,
    // xem SyncBatchItem.java) - request loi Validation se bi tu choi 400 NGAY TU
    // DAU (qua GlobalExceptionHandler), khong vao den vong lap ben duoi. Truoc
    // day DTO nay khong co validation gi (client gui displayName rong/content
    // null se lot qua, gay loi kho hieu o tang sau) - chi hop ly vi day la du
    // lieu 100% do client (IndexedDB) tu sinh, malformed item nghia la bug o
    // client, nen tu choi som ro rang hon la co gang xu ly tung phan.
    @PostMapping("/batch")
    public Map<String, Object> syncBatch(@AuthenticationPrincipal Long userId,
                                          @Valid @RequestBody List<SyncBatchItem> items) {
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

                if (item.baseVersion() != null && item.baseVersion() != existing.getVersion()) {
                    // Version client cam KHAC version hien tai cua server (VD da sua tu
                    // thiet bi/phien khac trong luc item nay con nam cho trong hang doi
                    // offline) - dung "version" (so nguyen tang dan) thay cho so sanh
                    // timestamp cu, tranh sai lech do dong ho he thong. GIU CA 2 BAN: note
                    // server giu nguyen (van la "ban thang"), ban local duoc tach thanh 1
                    // note MOI rieng ("ban xung dot") - xem NoteServiceImpl.createConflictCopy().
                    // Client se tu xoa item nay khoi hang doi offline (status khac "conflict"
                    // cu, xem useOfflineSync.ts) vi du lieu da duoc luu an toan o note moi.
                    var conflictCopy = noteService.createConflictCopy(userId, existing.getDisplayName(), item.content());
                    results.add(Map.of(
                            "noteId", item.noteId(),
                            "status", "conflict_kept_both",
                            "serverVersion", existing.getVersion(),
                            "conflictCopyId", conflictCopy.id(),
                            "conflictCopyName", conflictCopy.displayName()
                    ));
                    continue;
                }

                noteService.updateContent(userId, item.noteId(), new UpdateContentRequest(item.content()));
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
