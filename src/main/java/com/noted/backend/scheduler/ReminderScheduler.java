package com.noted.backend.scheduler;

import com.noted.backend.service.FcmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    private final EventRepository        eventRepo;
    private final NotificationRepository notifRepo;
    private final CacheManager           cacheManager;
    private final FcmService fcmService;   // ← THÊM MỚI (SEC-28)

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void sendReminders() {
        LocalDate today = LocalDate.now();
        List<Event> events = eventRepo.findEventsNeedingReminderToday(today);

        log.info("[Scheduler] Kiem tra nhac nho ngay {}: {} su kien", today, events.size());

        for (Event event : events) {
            try {
                String title = "Nhắc nhở: " + event.getTitle();
                String body  = buildBody(event);

                Notification notif = Notification.builder()
                    .user(event.getUser())
                    .event(event)
                    .title(title)
                    .body(body)
                    .isRead(false)
                    .sentAt(LocalDateTime.now())
                    .build();

                notifRepo.save(notif);

                // Evict cache unreadCount
                evictUnreadCount(event.getUser().getId());

                // ── THÊM MỚI (SEC-28): Gửi push notification thật qua FCM ──
                fcmService.sendToUser(
                    event.getUser().getId(),
                    title,
                    body,
                    Map.of(
                        "eventId", String.valueOf(event.getId()),
                        "type", "EVENT_REMINDER"
                    )
                );

                log.info("[Scheduler] Da tao notification + gui push cho event id={} user={}",
                    event.getId(), event.getUser().getId());

            } catch (Exception e) {
                log.error("[Scheduler] Loi khi xu ly event id={}: {}",
                    event.getId(), e.getMessage());
            }
        }
    }

    private void evictUnreadCount(Long userId) {
        Cache cache = cacheManager.getCache("unreadCount");
        if (cache != null) {
            cache.evict(userId);
        }
    }

    private String buildBody(Event event) {
        return String.format("Sự kiện '%s' diễn ra vào ngày %s",
            event.getTitle(), event.getEventDate());
    }
}
