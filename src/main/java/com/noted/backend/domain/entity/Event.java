package com.noted.backend.domain.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
@Entity @Table(name = "events")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Event {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relative_id")
    private Relative relative; // NULL = sự kiện bản thân

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    public enum EventType {
        SINH_NHAT, KY_NIEM, LE,
        NHA_O, HOA_DON, MUA_SAM, KHAC
    }

    @Column(name = "event_date", nullable = false)
    private java.time.LocalDate eventDate;

    @Column(name = "event_time")
    private java.time.LocalTime eventTime;

    @Column(name = "is_recurring")
    private Boolean isRecurring = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_type")
    private RecurrenceType recurrenceType;
    public enum RecurrenceType { YEARLY, MONTHLY, WEEKLY }

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "is_active",
            columnDefinition = "TINYINT(1) DEFAULT 0")
    private Boolean isActive = false;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<EventReminder> reminders;
}

