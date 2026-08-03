package com.noted.backend.service.impl;

import com.noted.backend.domain.entity.Note;
import com.noted.backend.repository.NoteRepository;
import com.noted.backend.service.DriveSyncService;
import com.noted.backend.service.FileStorageService;
import com.noted.backend.service.NotePurgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Job don dep note da soft-delete (xem NoteServiceImpl.delete/bulkDelete, SEC-02/
 * SEC-04) qua qua han giu lai (mac dinh 30 ngay, xem app.notes.purge-after-days).
 * Sau khoang thoi gian nay, note bi XOA VINH VIEN: ban tren Google Drive (neu
 * co, xem SEC-15) + file vat ly tren disk + record trong DB deu bi xoa that
 * su, KHONG con kha nang khoi phuc.
 *
 * Vi sao giu lai 1 khoang thoi gian truoc khi xoa that (khong xoa ngay luc
 * soft-delete): de danh khong gian cho tinh nang "khoi phuc note vua xoa
 * nham" o mot section sau (UI CHUA co, nhung kien truc DB - cot is_deleted/
 * deleted_at - da san sang cho no tu SEC-02).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotePurgeServiceImpl implements NotePurgeService {

    private final NoteRepository noteRepository;
    private final FileStorageService fileStorageService;
    private final DriveSyncService driveSyncService;

    @Value("${app.notes.purge-after-days}")
    private int purgeAfterDays;

    @Override
    @Scheduled(fixedDelayString = "${app.notes.purge-job-fixed-delay-ms}")
    @Transactional
    public void purgeExpiredNotes() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(purgeAfterDays);
        List<Note> expired = noteRepository.findTop100ByDeletedTrueAndDeletedAtBefore(threshold);

        if (expired.isEmpty()) {
            return;
        }

        log.info("Purge job: tim thay {} note da qua han {} ngay giu lai, bat dau xoa vinh vien",
                expired.size(), purgeAfterDays);

        for (Note note : expired) {
            try {
                // Thu tu xoa: Drive (best-effort, KHONG throw - xem SEC-15) ->
                // file vat ly tren disk -> record DB.
                //
                // Xoa file vat ly TRUOC record DB (khong lam nguoc lai): neu
                // buoc xoa file that bai giua chung ma DB da xoa truoc, file
                // tren disk se "mo coi" vinh vien - khong con record nao tro
                // toi no nua de biet ma don dep sau nay. Thu tu da chon dam
                // bao neu co loi xay ra, van con "dau moi" (record DB) de
                // retry lan sau.
                driveSyncService.deleteFromDrive(note.getId());
                fileStorageService.delete(note.getFilePath());
                noteRepository.delete(note);
            } catch (Exception e) {
                // KHONG throw tiep - 1 note loi (VD loi disk tam thoi) khong
                // duoc lam hong ca batch, cac note con lai van tiep tuc xu ly.
                // Note loi se duoc thu lai o lan chay ke tiep (van con trong DB).
                log.warn("Purge that bai cho note id={}, se thu lai o lan chay sau", note.getId(), e);
            }
        }
    }
}
