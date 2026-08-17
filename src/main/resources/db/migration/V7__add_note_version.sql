-- Chuyen co che phat hien conflict tu "updatedAt" (timestamp, de bi lech dong
-- ho giua cac lan request) sang "version" (so nguyen tang dan, khong phu
-- thuoc dong ho he thong) - dung cho Optimistic Concurrency Control o
-- /api/sync/batch (xem SyncController). Tang 1 o MOI lan ghi noi dung/ten
-- thanh cong (updateContent/rename/createNote-ghi-de/rename-merge).
ALTER TABLE notes ADD COLUMN version INT NOT NULL DEFAULT 1;
