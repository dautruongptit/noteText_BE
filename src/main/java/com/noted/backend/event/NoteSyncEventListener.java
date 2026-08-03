package com.noted.backend.event;

import com.noted.backend.service.DriveSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * QUAN TRONG: dung @TransactionalEventListener(phase = AFTER_COMMIT) thay vi
 * goi thang DriveSyncService.syncNote() ben trong NoteServiceImpl.
 *
 * Ly do: syncNote() la @Async, chay tren thread rieng gan nhu ngay lap tuc khi
 * duoc goi. Neu goi no TRUOC khi transaction luu note commit xong, thread nen
 * co the doc DB (qua 1 connection/transaction khac) va KHONG THAY note vua tao
 * (do transaction goc chua commit) -> loi "note not found" ngau nhien, kho debug.
 *
 * Voi AFTER_COMMIT, Spring dam bao listener nay CHI chay sau khi transaction
 * cua NoteServiceImpl da commit thanh cong -> DriveSyncService luon doc duoc
 * du lieu moi nhat.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NoteSyncEventListener {

    private final DriveSyncService driveSyncService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNoteContentChanged(NoteContentChangedEvent event) {
        try {
            driveSyncService.syncNote(event.noteId());
        } catch (Exception e) {
            // Khong throw ra ngoai: sync that bai KHONG duoc phep anh huong luong chinh
            // (note van an toan tren disk), job dinh ky se tu retry sau
            log.warn("Kich hoat sync ngay sau commit that bai cho note {}, se retry o job dinh ky", event.noteId(), e);
        }
    }
}
