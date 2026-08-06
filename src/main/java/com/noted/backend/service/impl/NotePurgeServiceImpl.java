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

    }
}
