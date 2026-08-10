package com.noted.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "event_reminders")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class EventReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Quan hệ N-1 với Event (một sự kiện có nhiều reminder)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    // Nhắc trước bao nhiêu NGÀY (nullable: 7, 3, 1)
    @Column(name = "remind_days_before")
    private Integer remindDaysBefore;

    // Nhắc trước bao nhiêu GIỜ (nullable: 1)
    @Column(name = "remind_hours_before")
    private Integer remindHoursBefore;

    // Toggle bật/tắt từng reminder riêng lẻ
    @Column(name = "is_enabled",
            columnDefinition = "TINYINT(1) DEFAULT 1")
    private Boolean isEnabled = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Helper: tính thời điểm thực sự cần gửi thông báo
    public LocalDateTime computeTriggerTime(java.time.LocalDate eventDate,
                                            java.time.LocalTime eventTime) {
        LocalDateTime base = eventTime != null
                ? eventDate.atTime(eventTime)
                : eventDate.atStartOfDay();
        if (remindDaysBefore != null)
            return base.minusDays(remindDaysBefore);
        if (remindHoursBefore != null)
            return base.minusHours(remindHoursBefore);
        return base;
    }
     private final EventParticipantRepository participantRepo;
        private final RelativeRepository relativeRepo;  // đã có sẵn
}

