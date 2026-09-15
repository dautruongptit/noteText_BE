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

                // Version client cam KHAC version hien tai cua server (VD note da bi
                // sua tu 1 TRINH DUYET KHAC cua chinh nguoi dung trong luc item nay con
                // nam cho trong hang doi offline).
                //
                // Truoc day: tach ban local thanh 1 note MOI co hau to "(xung dot ...)".
                // Doi tu 2026-09-15 sang GHI DE, theo yeu cau "chi co 1 file goc duy
                // nhat". Ly do chap nhan duoc: duong luu BINH THUONG (updateContent)
                // von da la ghi-de-ai-sau-thang tu dau, nen tach file o rieng duong
                // hang doi offline chi tao ra su khong nhat quan - cung 1 hanh dong
                // sua note, luu duoc ngay thi ghi de, luu that bai roi retry thi lai
                // de ra file thu hai.
                //
                // An toan cua viec ghi de nay DUA VAO co che lam moi o client (xem
                // App.tsx: kiem tra ban moi hon khi quay lai tab) - trinh duyet nao
                // cung tu keo ban moi nhat ve truoc khi nguoi dung go tiep, nen canh
                // "ghi de mat chu" tro nen hiem. Bo lam moi do di thi phai can nhac
                // lai cho nay.
                //
                // Van bao rieng "synced_overwritten" (khong gop vao "synced") de client
                // biet ma keo lai noi dung - ban trong editor luc do da cu.
                boolean daGhiDeBanMoiHon =
                        item.baseVersion() != null && item.baseVersion() != existing.getVersion();

                noteService.updateContent(userId, item.noteId(), new UpdateContentRequest(item.content()));
                results.add(Map.of(
                        "noteId", item.noteId(),
                        "status", daGhiDeBanMoiHon ? "synced_overwritten" : "synced"
                ));

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
