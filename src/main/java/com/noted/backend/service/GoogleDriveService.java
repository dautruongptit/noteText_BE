package com.noted.backend.service;

import com.google.api.services.drive.Drive;
import com.noted.backend.domain.entity.User;

import java.util.List;
import java.util.Optional;

/**
 * Lop THUAN THAO TAC voi Google Drive API - KHONG chua business logic ve
 * note/sync (do la viec cua DriveSyncService). Tach rieng de:
 *  - De test (mock 1 interface nho, khong phai mock ca DriveSyncServiceImpl)
 *  - De tai su dung neu sau nay co tinh nang khac can goi Drive (VD export
 *    hang loat, import tu Drive...)
 *
 * NGUYEN TAC QUAN TRONG (theo yeu cau nghiep vu moi nhat): Google Drive
 * KHONG bat buoc ten file duy nhat trong 1 folder (khac filesystem thong
 * thuong) - he thong nay CHO PHEP trung ten tren Drive. Dinh danh DUY NHAT
 * VA DUY NHAT CAN QUAN TAM la "File ID" (googleFileId) do Google cap phat -
 * KHONG tim/doi chieu theo ten khi quyet dinh tao moi hay cap nhat.
 */
public interface GoogleDriveService {

    /** Metadata 1 file tren Drive - request voi .setFields("files(id, name, size, modifiedTime, owners, mimeType)") */
    record DriveFileInfo(
            String id,
            String name,
            Long sizeBytes,
            String modifiedTime,
            String ownerEmail,
            String mimeType
    ) {}

    record UploadResult(String fileId) {}

    /** Dung refresh_token da ma hoa cua user (User.driveRefreshTokenEnc) de dung Drive client that su goi API */
    Drive buildClient(User user);

    /**
     * QUY TRINH TAO FOLDER:
     * 1. Validate du lieu (ten khong duoc rong/blank)
     * 2. Goi Drive API tao folder
     * 3. Tra ve googleFolderId nhan duoc tu Google
     * (Buoc "luu vao DB cung googleFolderId" la trach nhiem cua NGUOI GOI -
     * xem DriveSyncServiceImpl.ensureAppFolder() - GoogleDriveService KHONG
     * dong den DB, chi thuan tuy goi Drive API.)
     */
    String createFolder(Drive drive, String folderName);

    /** KIEM TRA FOLDER TON TAI: tim theo ten trong root Drive, tra ve googleFolderId neu co */
    Optional<String> findFolderByName(Drive drive, String folderName);

    /** Dam bao folder ton tai: tim truoc (buoc kiem tra), khong thay moi tao moi (buoc tao) - gop 2 buoc lam 1 cho tien dung */
    String ensureFolder(Drive drive, String folderName);

    /**
     * QUY TRINH UPLOAD FILE (chieu nguoc lai voi tao folder):
     * 1. (Nguoi goi dam bao folder ton tai TRUOC - qua ensureFolder(), lay googleFolderId)
     * 2. Upload file MOI vao folder do
     * 3. Tra ve googleFileId nhan duoc tu Google
     * (Buoc "luu googleFileId vao DB" la trach nhiem cua NGUOI GOI.)
     *
     * KHONG kiem tra trung ten truoc khi upload (dung theo nghiep vu: cho
     * phep trung ten tren Drive) - LUON tao file MOI khi goi ham nay.
     */
    UploadResult uploadFile(Drive drive, String folderId, String fileName, String content);

    /**
     * Cap nhat NOI DUNG + TEN cua 1 file DA CO SAN, dinh danh CHINH XAC bang
     * fileId (KHONG tim lai theo ten) - day la ly do "khong trung File ID"
     * luon dung: moi note chi giu DUY NHAT 1 fileId, moi lan sync sau chi
     * update() dung fileId do, khong bao gio tao them file moi cho note da
     * co fileId.
     */
    void updateFile(Drive drive, String fileId, String fileName, String content);

    /** List toan bo file trong 1 folder, kem metadata day du (size/modifiedTime/owner/mimeType) */
    List<DriveFileInfo> listFilesInFolder(Drive drive, String folderId);

    /** Chuyen 1 file vao thung rac Drive (KHONG xoa vinh vien - van khoi phuc duoc) */
    void trashFile(Drive drive, String fileId);

    /** Xoa vinh vien 1 file khoi Drive (dung cho purge job, SEC-12/15) */
    void deleteFilePermanently(Drive drive, String fileId);

    /**
     * Lay "md5Checksum" ma CHINH GOOGLE DRIVE tinh san cho 1 file - dung de
     * so sanh voi MD5 cua noi dung local (xem HashUtil.md5) TRUOC KHI goi
     * updateFile(), tranh upload lai neu noi dung THAT SU khong doi (tiet
     * kiem Drive API call + khong lam "modifiedTime" tren Drive nhay vo ich).
     * Tra ve null neu file khong ton tai/khong lay duoc checksum.
     */
    String getFileChecksum(Drive drive, String fileId);
}
