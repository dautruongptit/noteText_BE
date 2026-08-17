-- Mat tran B (conflict Drive-vs-local-dirty, xem DriveSyncServiceImpl.pullOneFile):
-- can 1 "baseline" MD5 cua noi dung TAI LAN SYNC THANH CONG GAN NHAT de phan
-- biet "local dang dirty binh thuong" (Drive van khop baseline) voi "conflict
-- THAT SU" (Drive da doi DOC LAP so voi baseline trong luc local cung dang
-- dirty). NULL = chua tung sync thanh cong lan nao.
ALTER TABLE notes ADD COLUMN drive_synced_content_hash VARCHAR(32) NULL;
