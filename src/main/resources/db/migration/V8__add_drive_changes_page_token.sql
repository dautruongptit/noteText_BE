-- Incremental sync (Drive Changes API) thay cho quet lai TOAN BO folder moi
-- lan pullFromDrive() - luu 1 page token/user (pull tap trung o server, khong
-- phai tung thiet bi/trinh duyet tu pull rieng, nen KHONG can 1 bang
-- SYNC_STATE rieng theo device nhu mot so he thong khac). NULL = chua tung
-- pull lan nao (se bootstrap bang full-listing 1 lan, xem DriveSyncServiceImpl).
ALTER TABLE users ADD COLUMN drive_changes_page_token VARCHAR(255) NULL;
