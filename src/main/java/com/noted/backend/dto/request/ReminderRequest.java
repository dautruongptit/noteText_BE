package com.noted.backend.dto.request;

import lombok.Data;
@Data
public class ReminderRequest {

    // Chỉ được điền một trong hai
    private Integer remindDaysBefore;  // 7, 3, 1
    private Integer remindHoursBefore; // 1

    private Boolean isEnabled = true;
}
