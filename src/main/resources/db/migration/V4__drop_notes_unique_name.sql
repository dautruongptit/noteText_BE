-- ==========================================================
-- V4__drop_notes_unique_name.sql
--
-- Bo unique constraint (user_id, display_name): Google Drive CHO PHEP nhieu
-- file trung ten trong cung 1 folder, va nghiep vu dong bo 2 chieu da chot
-- CHI dung "drive_file_id" (phia Drive) / "uuid" (phia local) de doi chieu -
-- KHONG BAO GIO dung display_name (xem DriveSyncServiceImpl javadoc).
--
-- Neu giu constraint nay, luong PULL (tao note moi tu file la tren Drive -
-- khong tim thay drive_file_id tuong ung o local) se bi crash ngay khi file
-- Drive trung ten voi 1 note local san co, du la 2 file HOAN TOAN khac nhau.
--
-- display_name gio chi con la nhan hien thi (UI) - trung ten giua 2 note
-- khac uuid la hop le, khong con la loi.
-- ==========================================================

ALTER TABLE notes DROP INDEX uq_notes_user_name;
