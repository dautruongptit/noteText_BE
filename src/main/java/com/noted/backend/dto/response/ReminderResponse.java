package com.noted.backend.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReminderResponse {
    private Long id;
    private Integer remindDaysBefore;
    private Integer remindHoursBefore;
    private Boolean isEnabled;
}
