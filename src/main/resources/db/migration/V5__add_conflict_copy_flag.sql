-- Co che "giu ca 2 ban khi conflict" (thay vi am tham ghi de/mat du lieu) -
-- xem SyncController.syncBatch() + NoteServiceImpl.createConflictCopy().
-- Note danh dau is_conflict_copy=TRUE la ban "thua" duoc tach rieng ra 1 note
-- moi khi phat hien xung dot dong bo (VD thiet bi offline reconnect nhung
-- server da co ban moi hon) - KHONG con bi am tham bo/ghi de nhu truoc.
ALTER TABLE notes ADD COLUMN is_conflict_copy BOOLEAN NOT NULL DEFAULT FALSE;

-- Dung khi can liet ke/loc rieng cac ban conflict cua 1 user (VD FE muon
-- hien banner "co N ban xung dot chua xu ly").
CREATE INDEX idx_notes_conflict_copy ON notes (user_id, is_conflict_copy);
