package com.noted.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class CreateRelativeRequest {

    @NotBlank(message = "Tên người thân không được để trống")
    @Size(max = 100)
    private String name;

    @Size(max = 50)
    private String nickname;

    @NotNull(message = "Nhóm quan hệ không được để trống")
    private String groupType; // GIA_DINH | VO_CHONG | CON_CAI | BAN_BE

    private String gender;    // MALE | FEMALE | OTHER

    @Past(message = "Ngày sinh phải là ngày trong quá khứ")
    private java.time.LocalDate dateOfBirth;

    @Size(max = 200)
    private String location;

    @DecimalMin("50.0") @DecimalMax("300.0")
    private java.math.BigDecimal heightCm;

    @DecimalMin("10.0") @DecimalMax("500.0")
    private java.math.BigDecimal weightKg;

    // JSON array string: ["Bóng đá", "Chơi game"]
    private String hobbies;

    private String avatarUrl;
}
