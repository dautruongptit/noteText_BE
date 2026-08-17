package com.noted.backend.exception;

/**
 * File tren Google Drive (dinh danh boi driveFileId) khong con ton tai nua -
 * VD nguoi dung tu tay xoa truc tiep tren Drive. Khac voi GoogleDriveOperationException
 * thong thuong (loi TAM THOI, dang retry lai duoc): day la loi VINH VIEN doi
 * voi fileId cu - khong bao gio "song lai" du retry bao nhieu lan. Nem rieng
 * ra de DriveSyncServiceImpl biet ma xoa driveFileId cu (coi note nhu chua
 * tung sync) thay vi retry vo ich mai mai voi cung 1 fileId da chet.
 */
public class DriveFileNotFoundException extends RuntimeException {
    public DriveFileNotFoundException(String fileId, Throwable cause) {
        super("File tren Google Drive khong con ton tai (id=" + fileId + ")", cause);
    }
}
