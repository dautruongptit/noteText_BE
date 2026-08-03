package com.noted.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameNoteRequest(
        @NotBlank(message = "Ten file khong duoc de trong")
        @Size(max = 255)
        String newDisplayName
) {}
