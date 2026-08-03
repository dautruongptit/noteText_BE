package com.noted.backend.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BulkDeleteRequest(
        @NotEmpty(message = "Danh sach file can xoa khong duoc de trong")
        @Size(max = 200, message = "Toi da 200 file moi lan xoa")
        List<Long> noteIds
) {}
