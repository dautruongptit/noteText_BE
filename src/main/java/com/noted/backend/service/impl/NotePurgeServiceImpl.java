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
        // Top100/lan chay, giong pattern da dung o cac job Drive sync - tranh 1
        // lan quet load qua nhieu row cung luc (xem NoteRepository).
        List<Note> expired = noteRepository.findTop100ByDeletedTrueAndDeletedAtBefore(threshold);
        if (expired.isEmpty()) return;

        for (Note note : expired) {
            // File vat ly: xoa THAT SU, best-effort - 1 file loi (VD da bi xoa
            // tay tren disk tu truoc, quyen truy cap...) KHONG duoc chan viec
            // xoa DB record cua CHINH note do lan cac note con lai trong batch.
            try {
                fileStorageService.delete(note.getFilePath());
            } catch (Exception e) {
                log.warn("Xoa file vat ly that bai luc purge cho note id={} (path={}), van tiep tuc xoa DB record",
                        note.getId(), note.getFilePath(), e);
            }

            // Ban tren Drive: da duoc xoa GAN NHU NGAY LAP TUC luc soft-delete
            // qua NoteDeletedEvent/NoteSyncEventListener (xem javadoc class) -
            // nhung do la best-effort, co the da that bai tam thoi luc do (VD
            // mat mang/token het han). Day la LUOI AN TOAN DU PHONG CUOI CUNG -
            // sau khi xoa DB record se KHONG CON driveFileId nao de biet ma
            // don nua, nen thu lai 1 lan NUA o day truoc khi qua muon.
            if (note.getDriveFileId() != null) {
                driveSyncService.deleteFromDrive(note.getId());
            }
        }

        noteRepository.deleteAll(expired);
        log.info("Purge job: da xoa vinh vien {} note qua han giu lai ({} ngay)", expired.size(), purgeAfterDays);
    }
}
