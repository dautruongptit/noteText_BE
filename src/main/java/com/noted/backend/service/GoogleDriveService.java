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
            // Doi tu "ownerEmail" (lay ve nhung khong noi nao dung) sang co nay:
            // so sanh email KHONG dang tin - nguoi dung co the dang nhap Noted bang
            // tai khoan A nhung ket noi Drive bang tai khoan B. "ownedByMe" do
            // CHINH Drive tra ve, luon dung theo tai khoan cua access token.
            boolean ownedByMe,
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

    /**
     * Thu muc nay co THUOC SO HUU cua chinh tai khoan dang dang nhap khong?
     *
     * NGHIEP VU BAT BUOC: note cua 1 tai khoan Google CHI duoc phep nam trong
     * Drive cua CHINH tai khoan do. Chi kiem tra "thu muc con ton tai va ghi
     * duoc khong" la KHONG DU - Google VAN cho ghi vao thu muc cua nguoi khac
     * neu thu muc do duoc chia se kem quyen sua. Day dung la bug thuc te
     * 2026-08-22: tai khoan dautruong.dt@ ghi note vao thu muc NotedApp thuoc
     * so huu cua dautruongptit@ (ownedByMe=false nhung canAddChildren=true).
     *
     * Tra ve false neu thu muc khong con ton tai (404), dang trong thung rac,
     * hoac thuoc so huu cua tai khoan khac - ca ba deu dan toi cung mot ket
     * luan: KHONG duoc dung thu muc nay, phai tao thu muc rieng.
     */
    boolean isFolderOwnedByMe(Drive drive, String folderId);

    /**
     * File nay co con DUNG CHO de ghi de len khong?
     *
     * Dung NGAY TRUOC updateFile() o luong day note len Drive. Kiem tra 3 dieu
     * trong DUNG 1 lan goi API (thay cho isFileTrashed() rieng le truoc day):
     *   - chua bi cho vao thung rac
     *   - THUOC SO HUU cua chinh tai khoan dang dang nhap
     *   - VAN nam trong app-folder cua tai khoan do
     *
     * VI SAO CAN: "drive_file_id" da luu chinh la thu duy nhat quyet dinh file
     * nao bi ghi de, ma truoc day KHONG co gi kiem tra lai no. Mot id sai (file
     * bi keo ra khoi thu muc, hoac von la file cua nguoi khac bi nhan nham qua
     * thu muc chia se) se khien app am tham ghi de len file do MAI MAI - dung
     * cung mot lop loi voi "drive_folder_id" sai vinh vien da gap 2026-08-22,
     * chi khac la o cap FILE thay vi cap THU MUC.
     *
     * Tra ve false = "coi nhu file khong con dung duoc": nguoi goi tai su dung
     * dung duong hoi phuc san co (bo driveFileId, upload lai thanh file MOI
     * trong dung thu muc), khong can them nhanh xu ly rieng.
     *
     * @throws com.noted.backend.exception.DriveFileNotFoundException neu fileId khong con ton tai (404)
     */
    boolean isFileUsable(Drive drive, String fileId, String folderId);
}
