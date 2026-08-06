package com.noted.backend.event;

import com.noted.backend.service.DriveSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * QUAN TRONG: dung @TransactionalEventListener(phase = AFTER_COMMIT) thay vi
 * goi thang DriveSyncService ben trong NoteServiceImpl. Ly do: cac thao tac
 * Drive la @Async, chay tren thread rieng gan nhu ngay lap tuc khi duoc goi.
 * Neu goi truoc khi transaction goc commit xong, thread nen co the doc DB
 * qua 1 connection khac va KHONG THAY thay doi vua luu (transaction goc
 * chua commit) -> loi ngau nhien, kho debug. AFTER_COMMIT dam bao listener
 * chi chay sau khi du lieu da chac chan co trong DB.
 *
 * LUU Y (da doi kien truc): truoc day co ca su kien "NoteContentChangedEvent"
 * kich hoat sync GAN NHU NGAY LAP TUC moi lan sua note. Da BO su kien nay -
 * viec day len Drive gio di theo co che "Debounce Sync" (xem DriveSyncServiceImpl):
 * chi sync khi note "yen tinh" 30s, hoac nguoi dung bam Dong bo, hoac luc
 * dong tab. Chi con lai 1 su kien can xu ly NGAY LAP TUC (khong cho debounce):
 * xoa note -> xoa Drive ngay, tranh file "mo coi" ton tai vo ich tren Drive.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NoteSyncEventListener {

    private final DriveSyncService driveSyncService;

    /**
     * Xoa ban tren Drive NGAY LAP TUC khi note bi xoa trong app (thay vi doi
     * 30 ngay den luc purge job chay - xem NoteDeletedEvent.java). deleteFromDrive()
     * ban than da la best-effort (khong throw), nhung van bao ve them 1 lop o
     * day cho chac chan khong bao gio anh huong den viec xoa note chinh.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNoteDeleted(NoteDeletedEvent event) {
        try {
            driveSyncService.deleteFromDrive(event.noteId());
        } catch (Exception e) {
            log.warn("Xoa Drive ngay sau khi xoa note {} that bai, se duoc purge job don dep sau", event.noteId(), e);
        }
    }
}
