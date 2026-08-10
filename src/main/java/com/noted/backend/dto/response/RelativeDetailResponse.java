package com.noted.backend.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RelativeDetailResponse {
    private Long id;
    private String name;
    private String nickname;
    private String groupType;
    private String gender;
    private Integer age;            // tính từ dateOfBirth
    private java.time.LocalDate dateOfBirth;
    private String location;
    private java.math.BigDecimal heightCm;
    private java.math.BigDecimal weightKg;
    private java.util.List<String> hobbies;
    private String avatarUrl;
    private Integer daysUntilBirthday;
    // Danh sách sự kiện liên quan đến người thân này
    private java.util.List<EventResponse> relatedEvents;
}
