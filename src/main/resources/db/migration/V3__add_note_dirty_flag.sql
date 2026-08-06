-- ==========================================================
-- V3__add_note_dirty_flag.sql
--
-- Them co "dirty" (co thay doi CHUA duoc day len Drive) - tach rieng khoi
-- "sync_state" (von la enum hien thi cho UI). "dirty" la co CHUAN dung boi
-- batch job debounce sync (xem DriveSyncServiceImpl, luong "Debounce Sync"):
--   - Bat len TRUE moi khi noi dung/ten note thay doi
--   - Tat ve FALSE CHI SAU KHI da day len Drive THANH CONG
-- Ket hop voi cot "updated_at" da co san (SEC-02) de tinh "note da yen tinh
-- du 30 giay chua" ma KHONG can them cot rieng luu "lan sua gan nhat".
-- ==========================================================

ALTER TABLE notes
    ADD COLUMN is_dirty BOOLEAN NOT NULL DEFAULT TRUE AFTER drive_sync_error;

-- Backfill du lieu cu: note nao dang SYNCED (da tung day len Drive thanh
-- cong, khong con thay doi gi moi) thi coi la KHONG dirty; con lai (
-- PENDING_DRIVE/DRIVE_FAILED/CONFLICT) van coi la dirty (can duoc thu sync lai).
UPDATE notes SET is_dirty = FALSE WHERE sync_state = 'SYNCED';

-- Ho tro truc tiep cho batch job debounce: WHERE is_dirty = true AND
-- updated_at < ? (xem NoteRepository.findTop50ByDirtyTrueAndUpdatedAtBefore)
CREATE INDEX idx_notes_dirty_updated ON notes(is_dirty, updated_at);
