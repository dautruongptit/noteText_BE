package com.noted.backend.event;

import com.noted.backend.service.DriveSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
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
     *
     * @Async (them khi review): truoc day listener nay chay DONG BO ngay tren
     * thread request (mac dinh cua @TransactionalEventListener) - xoa 1 note
     * co Drive phai doi xong round-trip Drive API moi tra response; bulkDelete()
     * publish N event thi request phai doi ca N round-trip TUAN TU. Voi @Async,
     * moi loi goi listener nay chay tren thread rieng, response tra ve ngay
     * sau khi transaction xoa note commit xong - khong con phai doi Drive nua.
     * LUU Y: khong dinh phai self-invocation nhu bug syncNote() (xem
     * DriveSyncServiceImpl) - Spring goi listener nay qua co che event dispatch
     * CUA CHINH NO (khong phai "this.onNoteDeleted()"), luon di qua proxy binh
     * thuong nen @Async o day co hieu luc dung ngay tu dau.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNoteDeleted(NoteDeletedEvent event) {
        try {
            driveSyncService.deleteFromDrive(event.noteId());
        } catch (Exception e) {
            log.warn("Xoa Drive ngay sau khi xoa note {} that bai, se duoc purge job don dep sau", event.noteId(), e);
        }
    }
}
