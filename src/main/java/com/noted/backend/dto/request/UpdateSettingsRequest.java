package com.noted.backend.dto.request;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdateSettingsRequest {

    @Pattern(regexp = "^(vi|en)$",
            message = "Ngôn ngữ chỉ hỗ trợ: vi, en")
    private String language;

    private Boolean darkMode;
}
