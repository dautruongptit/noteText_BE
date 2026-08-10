package com.noted.backend.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EventResponse {
    private Long id;
    private String title;
    private String eventType;
    private java.time.LocalDate eventDate;
    private java.time.LocalTime eventTime;
    private Boolean isRecurring;
    private String recurrenceType;
    private String notes;
    // Thông tin người thân (null nếu là sự kiện bản thân)
    private Long relativeId;
    private String relativeName;
    private String relativeGroupType;
    // Countdown
    private Long daysUntil;  // âm = đã qua, 0 = hôm nay, dương = còn x ngày
    // Danh sách reminder config
    private java.util.List<ReminderResponse> reminders;
}
