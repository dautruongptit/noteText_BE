package com.noted.backend.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NotificationResponse {
    private Long id;
    private String title;
    private String body;
    private Boolean isRead;
    private Long eventId;
    private String eventTitle;
    private java.time.LocalDateTime sentAt;
}
