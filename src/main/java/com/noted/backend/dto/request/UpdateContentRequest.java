package com.noted.backend.dto.request;

import jakarta.validation.constraints.NotNull;

public record UpdateContentRequest(
        @NotNull
        String content,

        /** Timestamp phia client luc nguoi dung go, dung de phat hien conflict khi sync-batch */
        Long localUpdatedAtEpochMs
) {}
