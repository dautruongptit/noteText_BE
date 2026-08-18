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

    /**
     * Ket qua 1 lan goi Drive Changes API (incremental sync, xem
     * DriveSyncServiceImpl.pullFromDrive()) - "changedFiles" la cac file
     * CON TON TAI va vua thay doi/tao moi, "removedFileIds" la cac fileId da
     * bi xoa/mat quyen truy cap (KHONG kem metadata, Drive khong tra ve nua).
     * "newPageToken" phai duoc luu lai (User.driveChangesPageToken) de dung
     * cho lan goi TIEP THEO.
     */
    record ChangesResult(List<DriveFileInfo> changedFiles, List<String> removedFileIds, String newPageToken) {}

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

    /**
     * Lay page token KHOI DIEM cho Drive Changes API - goi 1 LAN duy nhat luc
     * "bootstrap" (lan pullFromDrive() dau tien, chua co token luu san), TRUOC
     * khi bat dau theo doi incremental. Cac thay doi xay ra TRUOC thoi diem
     * goi ham nay se KHONG xuat hien trong changes.list() sau do - vi vay
     * phai lam full-listing (listFilesInFolder) TRUOC, roi moi goi ham nay,
     * dam bao khong bo lot file nao dang co san luc bootstrap.
     */
    String getStartPageToken(Drive drive);

    /**
     * Lay danh sach thay doi tren TOAN BO DRIVE ke tu "pageToken" (dung cho
     * incremental sync) - Changes API KHONG ho tro loc theo folder o tang
     * truy van (khac voi files().list() co the .setQ()). Vi scope OAuth la
     * "drive" day du (doi tu "drive.file" 2026-08-18, xem application.yml),
     * ket qua KHONG con tu dong gioi han trong pham vi app nua - PHAI truyen
     * "folderId" de ham nay tu loc lai, chi giu cac file THAT SU nam trong
     * app-folder (kiem tra "parents"). Neu khong loc, bat ky file .txt nao o
     * BAT KY DAU trong Drive cua nguoi dung cung co the bi "nhan nham" thanh
     * note (rui ro nghiem trong - xem DriveSyncServiceImpl.incrementalPull).
     */
    ChangesResult listChanges(Drive drive, String pageToken, String folderId);

    /**
     * Tai NOI DUNG THAT SU cua 1 file tren Drive ve (dung cho luong PULL -
     * xem DriveSyncServiceImpl.pullFromDrive). Note chi luu text (xem
     * uploadFile/updateFile deu dung "text/plain" UTF-8), nen tra ve thang
     * String, khong can xu ly binary/Blob.
     */
    String downloadFileContent(Drive drive, String fileId);

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

    /**
     * File co dang nam trong THUNG RAC Drive hay khong - KHAC HOAN TOAN voi
     * "khong ton tai" (404): 1 file bi cho vao thung rac (nguoi dung bam "Xoa"
     * tren giao dien Drive - hanh dong MAC DINH, KHONG phai "Xoa vinh vien")
     * VAN duoc Drive API cho phep doc/ghi noi dung binh thuong nhu chua co gi
     * xay ra - chi bi AN khoi giao dien thu muc thong thuong. Neu goi update()
     * ma khong kiem tra rieng dieu nay, sync "thanh cong" (khong loi gi) nhung
     * nguoi dung se KHONG BAO GIO thay file do trong thu muc nua - dung bao
     * cao thuc te 2026-08-18 (file van con, dung ten/dung folder, nhung
     * trashed=true, ket qua kiem tra truc tiep qua Drive API that).
     *
     * @throws com.noted.backend.exception.DriveFileNotFoundException neu fileId khong con ton tai chut nao (404 that su)
     */
    boolean isFileTrashed(Drive drive, String fileId);
}
