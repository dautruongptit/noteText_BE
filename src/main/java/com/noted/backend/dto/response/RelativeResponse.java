package com.noted.backend.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RelativeResponse {
    private Long id;
    private String name;
    private String nickname;
    private String groupType;
    private String gender;
    private java.time.LocalDate dateOfBirth;
    private String location;
    private java.math.BigDecimal heightCm;
    private java.math.BigDecimal weightKg;
    private java.util.List<String> hobbies; // parse từ JSON string
    private String avatarUrl;
    private Integer totalEvents;
    private Integer daysUntilBirthday; // -1 nếu không có ngày sinh
    private String nextEventTitle;     // tiêu đề sự kiện gần nhất
}
