package com.noted.backend.dto.request;

/** 1 note dang cho sync tu IndexedDB cua client len server, sau khi mat ket noi roi co lai */
public record SyncBatchItem(
        Long noteId,          // null neu la note tao moi hoan toan tren client trong luc offline
        String clientTempId,  // id tam client tu sinh (VD uuid) - dung khi noteId = null
        String displayName,
        String content,
        Long localUpdatedAtEpochMs
) {}
