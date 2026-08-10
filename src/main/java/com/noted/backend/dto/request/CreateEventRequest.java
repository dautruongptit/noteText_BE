package com.noted.backend.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateEventRequest {

    // null = sự kiện bản thân, có giá trị = sự kiện người thân
    private Long relativeId;

    @NotBlank(message = "Tiêu đề sự kiện không được để trống")
    @Size(max = 200)
    private String title;

    @NotNull(message = "Loại sự kiện không được để trống")
    private String eventType;
    // SINH_NHAT | KY_NIEM | LE | NHA_O | HOA_DON | MUA_SAM | KHAC

    @NotNull(message = "Ngày không được để trống")
    @FutureOrPresent(message = "Ngày phải từ hôm nay trở đi")
    private java.time.LocalDate eventDate;

    private java.time.LocalTime eventTime;

    private Boolean isRecurring = false;

    // YEARLY | MONTHLY | WEEKLY (bắt buộc nếu isRecurring = true)
    private String recurrenceType;

    @Size(max = 2000)
    private String notes;

    // Danh sách cấu hình nhắc nhở
    private java.util.List<ReminderRequest> reminders;

    private List<Long> participantIds;
}
