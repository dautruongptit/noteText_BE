-- ==========================================================
-- V2__add_missing_indexes.sql
--
-- Bo sung index con thieu, phat hien khi ra soat lai toan bo Flyway migration
-- doi chieu voi cac query THAT SU dang dung trong code (2026-08-02).
-- ==========================================================

-- Ho tro truc tiep cho NotePurgeServiceImpl.purgeExpiredNotes() (SEC-12):
-- query la "WHERE is_deleted = true AND deleted_at < ?". V1 migration da
-- them cot is_deleted/deleted_at nhung QUEN tao index tuong ung - neu khong
-- co index nay, MySQL phai full table scan toan bo bang "notes" MOI LAN job
-- purge chay (mac dinh 24h/lan, nhung van la thoi quen xau khi bang lon dan).
CREATE INDEX idx_notes_deleted_at ON notes(is_deleted, deleted_at);

-- Chuan bi san cho 1 job don dep refresh_tokens trong tuong lai (HIEN CHUA
-- CO job nay - xem "con treo" trong section_index.txt).
--
-- Ly do can: bang refresh_tokens hien TANG DAN KHONG GIOI HAN. Co che ROTATION
-- (RefreshTokenServiceImpl.rotate(), SEC-06) tao 1 ROW MOI moi lan access
-- token het han va nguoi dung con hoat dong (mac dinh 1 gio/lan, xem
-- access-token-expiration-ms) - row CU (revoked=true) khong bao gio bi xoa.
-- Voi 1 nguoi dung hoat dong 8 tieng/ngay, lieu tinh ~2.920 row/nguoi/nam chi
-- rieng bang nay, va con tang theo so nguoi dung. Index nay ho tro truy van
-- "tim token da revoked hoac het han qua lau de xoa" khi co job don dep.
CREATE INDEX idx_refresh_tokens_cleanup ON refresh_tokens(revoked, expires_at);
