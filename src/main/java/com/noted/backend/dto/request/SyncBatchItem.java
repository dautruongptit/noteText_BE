package com.noted.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 1 note dang cho sync tu IndexedDB cua client len server, sau khi mat ket noi roi co lai */
public record SyncBatchItem(
        Long noteId,          // null neu la note tao moi hoan toan tren client trong luc offline
        String clientTempId,  // id tam client tu sinh (VD uuid) - dung khi noteId = null

        @NotBlank(message = "Ten file khong duoc de trong")
        @Size(max = 255, message = "Ten file toi da 255 ky tu")
        String displayName,

        @NotNull(message = "Noi dung khong duoc null (chuoi rong thi hop le)")
        String content,

        // Version note nay ma client biet GAN NHAT (tu lan get()/save() thanh
        // cong truoc do) - dung cho Optimistic Concurrency Control o
        // SyncController (thay cho "localUpdatedAtEpochMs" cu, de bi lech dong
        // ho giua cac lan request). NULL khi noteId cung NULL (note moi tao,
        // khong co gi de so sanh) - vi vay KHONG @NotNull o day, chi kiem tra
        // dieu kien nay o tang service (SyncController).
        Integer baseVersion
) {}
