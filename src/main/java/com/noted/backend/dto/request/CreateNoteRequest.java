package com.noted.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateNoteRequest(
        @NotBlank(message = "Ten file khong duoc de trong")
        @Size(max = 255, message = "Ten file toi da 255 ky tu")
        String displayName,

        String initialContent // co the null / rong
) {}
