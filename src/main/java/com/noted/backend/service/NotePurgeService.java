package com.noted.backend.service;

public interface NotePurgeService {

    /**
     * Xoa vinh vien (hard delete) cac note da soft-delete VA da qua han giu lai.
     * Chay dinh ky qua @Scheduled (xem NotePurgeServiceImpl), nhung cung co the
     * goi thu cong (VD tu 1 endpoint admin trong tuong lai, hien chua co).
     */
    void purgeExpiredNotes();
}
