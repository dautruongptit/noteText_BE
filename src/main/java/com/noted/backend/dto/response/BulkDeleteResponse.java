package com.noted.backend.dto.response;

import java.util.List;

/**
 * Tra ve ro rang so luong da xoa vs so luong yeu cau, vi co the co id khong thuoc
 * user hien tai (bi loai am tham o repository) hoac id khong ton tai -> giup FE
 * bao chinh xac cho nguoi dung, VD "Da xoa 4/5 file (1 file khong tim thay)".
 */
public record BulkDeleteResponse(
        int requestedCount,
        int deletedCount,
        List<Long> deletedIds
) {}
