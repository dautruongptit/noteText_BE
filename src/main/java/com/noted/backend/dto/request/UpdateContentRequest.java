package com.noted.backend.dto.request;

import jakarta.validation.constraints.NotNull;

// LUU Y: truoc day co field "localUpdatedAtEpochMs" nhung KHONG BAO GIO duoc
// doc trong NoteServiceImpl.updateContent() - da bo (dead field). Route nay
// (PATCH /content, autosave dang go song) khong kiem tra conflict (phuong
// an 2, xem SyncController/version-based conflict) - LUON ghi de, chi tang
// version. Viec kiem tra baseVersion THAT SU chi xay ra o SyncController
// (mat tran A, offline reconciliation) qua SyncBatchItem.baseVersion.
public record UpdateContentRequest(
        @NotNull
        String content
) {}
