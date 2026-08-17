package com.noted.backend.service.impl;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.noted.backend.domain.entity.User;
import com.noted.backend.exception.DriveFileNotFoundException;
import com.noted.backend.exception.GoogleDriveOperationException;
import com.noted.backend.service.GoogleDriveService;
import com.noted.backend.util.CryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Implement THUAN THAO TAC voi Google Drive API (xem javadoc interface).
 * KHONG chua business logic ve note/user o day (VD KHONG tu doc/ghi
 * NoteRepository) - moi thu ve "gan file nay cho note nao, khi nao sync" la
 * trach nhiem cua DriveSyncServiceImpl, lop nay chi biet "goi Drive lam X".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleDriveServiceImpl implements GoogleDriveService {

    private final CryptoUtil cryptoUtil;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String clientSecret;

    @Override
    public Drive buildClient(User user) {
        try {
            String refreshToken = cryptoUtil.decrypt(user.getDriveRefreshTokenEnc());

            Credential credential = new GoogleCredential.Builder()
                    .setTransport(GoogleNetHttpTransport.newTrustedTransport())
                    .setJsonFactory(GsonFactory.getDefaultInstance())
                    .setClientSecrets(clientId, clientSecret)
                    .build()
                    .setRefreshToken(refreshToken);

            credential.refreshToken(); // lay access_token moi tu refresh_token

            return new Drive.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential)
                    .setApplicationName("Noted App")
                    .build();
        } catch (Exception e) {
            throw new GoogleDriveOperationException("Khong the khoi tao Drive client", e);
        }
    }

    @Override
    public String createFolder(Drive drive, String folderName) {
        // ---- 1. Validate du lieu ----
        if (!StringUtils.hasText(folderName)) {
            throw new IllegalArgumentException("Ten folder khong duoc de trong");
        }

        // ---- 2. Goi Drive API tao folder ----
        try {
            File folderMeta = new File();
            folderMeta.setName(folderName.trim());
            folderMeta.setMimeType("application/vnd.google-apps.folder");

            File created = drive.files().create(folderMeta).setFields("id").execute();

            // ---- 3. Tra ve googleFolderId nhan duoc tu Google ----
            return created.getId();
        } catch (Exception e) {
            log.warn("Tao folder Drive '{}' that bai", folderName, e);
            throw new GoogleDriveOperationException("Khong the tao folder tren Google Drive: " + folderName, e);
        }
    }

    @Override
    public Optional<String> findFolderByName(Drive drive, String folderName) {
        if (!StringUtils.hasText(folderName)) {
            return Optional.empty();
        }
        try {
            String escapedName = folderName.trim().replace("'", "\\'");
            String query = String.format(
                    "name='%s' and mimeType='application/vnd.google-apps.folder' and trashed=false",
                    escapedName);
            FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute();

            if (result.getFiles() != null && !result.getFiles().isEmpty()) {
                return Optional.of(result.getFiles().get(0).getId());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Tim folder Drive '{}' that bai", folderName, e);
            throw new GoogleDriveOperationException("Khong the tim folder tren Google Drive: " + folderName, e);
        }
    }

    @Override
    public String ensureFolder(Drive drive, String folderName) {
        // Buoc KIEM TRA FOLDER TON TAI truoc...
        return findFolderByName(drive, folderName)
                // ...neu khong co moi goi buoc TAO FOLDER
                .orElseGet(() -> createFolder(drive, folderName));
    }

    @Override
    public UploadResult uploadFile(Drive drive, String folderId, String fileName, String content) {
        if (!StringUtils.hasText(folderId)) {
            throw new IllegalArgumentException("folderId khong duoc de trong khi upload file");
        }
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("Ten file khong duoc de trong khi upload");
        }

        try {
            File meta = new File();
            meta.setName(fileName);
            meta.setParents(Collections.singletonList(folderId));

            ByteArrayContent fileContent = new ByteArrayContent(
                    "text/plain", (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));

            // LUON tao file MOI - KHONG kiem tra trung ten (dung theo nghiep
            // vu: Drive duoc phep co nhieu file cung ten, chi File ID moi can
            // la duy nhat, va File ID la do CHINH GOOGLE cap phat nen luon
            // duy nhat tu nhien, khong can code tu kiem tra them).
            File created = drive.files().create(meta, fileContent).setFields("id").execute();

            return new UploadResult(created.getId());
        } catch (Exception e) {
            log.warn("Upload file '{}' vao folder '{}' that bai", fileName, folderId, e);
            throw new GoogleDriveOperationException("Khong the upload file len Google Drive: " + fileName, e);
        }
    }

    @Override
    public void updateFile(Drive drive, String fileId, String fileName, String content) {
        if (!StringUtils.hasText(fileId)) {
            throw new IllegalArgumentException("fileId khong duoc de trong khi cap nhat");
        }

        try {
            File meta = new File();
            if (StringUtils.hasText(fileName)) {
                meta.setName(fileName);
            }

            ByteArrayContent fileContent = new ByteArrayContent(
                    "text/plain", (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));

            // Dinh danh CHINH XAC bang fileId - tuyet doi KHONG tim lai theo
            // ten truoc khi update (day chinh la diem mau chot dam bao "khong
            // trung File ID": moi note giu 1 fileId co dinh, luon update()
            // dung fileId do).
            drive.files().update(fileId, meta, fileContent).execute();
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                // File da bi xoa/khong con ton tai tren Drive (VD nguoi dung tu
                // xoa truc tiep tren Drive, khong qua app) - day la loi VINH VIEN
                // doi voi fileId nay, KHAC voi loi tam thoi thong thuong. Nem
                // rieng de nguoi goi (DriveSyncServiceImpl) biet ma xoa fileId cu
                // va upload lai thanh file MOI, thay vi retry vo ich mai mai.
                log.warn("Cap nhat file Drive (id={}) that bai - file khong con ton tai (404)", fileId);
                throw new DriveFileNotFoundException(fileId, e);
            }
            log.warn("Cap nhat file Drive (id={}) that bai", fileId, e);
            throw new GoogleDriveOperationException("Khong the cap nhat file tren Google Drive (id=" + fileId + ")", e);
        } catch (Exception e) {
            log.warn("Cap nhat file Drive (id={}) that bai", fileId, e);
            throw new GoogleDriveOperationException("Khong the cap nhat file tren Google Drive (id=" + fileId + ")", e);
        }
    }

    @Override
    public List<DriveFileInfo> listFilesInFolder(Drive drive, String folderId) {
        if (!StringUtils.hasText(folderId)) {
            return List.of();
        }

        try {
            String query = String.format("'%s' in parents and trashed=false", folderId);

            // .setFields(...) THEO DUNG YEU CAU: lay ve day du id, name, size,
            // modifiedTime, owners, mimeType cho tung file - dung de hien thi
            // chi tiet file tren UI (VD DrivePanel sau nay muon hien dung
            // luong/thoi gian sua/chu so huu), khong chi mac dinh vai field it oi.
            FileList result = drive.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name, size, modifiedTime, owners, mimeType)")
                    .execute();

            List<DriveFileInfo> infos = new ArrayList<>();
            if (result.getFiles() != null) {
                for (File f : result.getFiles()) {
                    infos.add(toDriveFileInfo(f));
                }
            }
            return infos;
        } catch (Exception e) {
            log.warn("List file trong folder Drive '{}' that bai", folderId, e);
            throw new GoogleDriveOperationException("Khong the lay danh sach file tu Google Drive", e);
        }
    }

    @Override
    public String getStartPageToken(Drive drive) {
        // Goi 1 LAN duy nhat luc bootstrap incremental sync - xem javadoc
        // interface ve ly do PHAI full-listing TRUOC roi moi goi ham nay.
        try {
            return drive.changes().getStartPageToken().execute().getStartPageToken();
        } catch (Exception e) {
            log.warn("Lay start page token cho Drive Changes API that bai", e);
            throw new GoogleDriveOperationException("Khong the khoi tao incremental sync voi Google Drive", e);
        }
    }

    @Override
    public ChangesResult listChanges(Drive drive, String pageToken) {
        if (!StringUtils.hasText(pageToken)) {
            throw new IllegalArgumentException("pageToken khong duoc de trong khi lay danh sach thay doi");
        }

        try {
            List<DriveFileInfo> changedFiles = new ArrayList<>();
            List<String> removedFileIds = new ArrayList<>();
            String currentToken = pageToken;
            String newStartPageToken = null;

            // changes.list() co the tra ve NHIEU TRANG (nextPageToken) neu co
            // qua nhieu thay doi tich luy tu lan pull truoc - lap cho den khi
            // het trang. Google CHI tra ve "newStartPageToken" o TRANG CUOI
            // CUNG - danh dau da quet het, dung lam token cho lan goi ke tiep.
            do {
                ChangeList result = drive.changes().list(currentToken)
                        .setSpaces("drive")
                        .setFields("nextPageToken, newStartPageToken, "
                                + "changes(fileId, removed, file(id, name, size, modifiedTime, owners, mimeType))")
                        .execute();

                if (result.getChanges() != null) {
                    for (Change change : result.getChanges()) {
                        // "removed"=true HOAC file()=null (Drive khong con tra
                        // ve metadata) deu nghia la file da bi xoa/mat quyen -
                        // KHONG con gi de doi chieu ngoai fileId.
                        if (Boolean.TRUE.equals(change.getRemoved()) || change.getFile() == null) {
                            removedFileIds.add(change.getFileId());
                        } else {
                            changedFiles.add(toDriveFileInfo(change.getFile()));
                        }
                    }
                }

                currentToken = result.getNextPageToken();
                if (result.getNewStartPageToken() != null) {
                    newStartPageToken = result.getNewStartPageToken();
                }
            } while (currentToken != null);

            return new ChangesResult(changedFiles, removedFileIds, newStartPageToken);
        } catch (Exception e) {
            log.warn("Lay danh sach thay doi tu Drive Changes API that bai (pageToken={})", pageToken, e);
            throw new GoogleDriveOperationException("Khong the lay danh sach thay doi tu Google Drive", e);
        }
    }

    private DriveFileInfo toDriveFileInfo(File f) {
        String ownerEmail = (f.getOwners() != null && !f.getOwners().isEmpty())
                ? f.getOwners().get(0).getEmailAddress()
                : null;
        return new DriveFileInfo(
                f.getId(),
                f.getName(),
                f.getSize(),
                f.getModifiedTime() != null ? f.getModifiedTime().toStringRfc3339() : null,
                ownerEmail,
                f.getMimeType()
        );
    }

    @Override
    public String downloadFileContent(Drive drive, String fileId) {
        if (!StringUtils.hasText(fileId)) {
            throw new IllegalArgumentException("fileId khong duoc de trong khi tai noi dung file");
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            drive.files().get(fileId).executeMediaAndDownloadTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Tai noi dung file Drive (id={}) that bai", fileId, e);
            throw new GoogleDriveOperationException("Khong the tai noi dung file tu Google Drive (id=" + fileId + ")", e);
        }
    }

    @Override
    public void trashFile(Drive drive, String fileId) {
        if (!StringUtils.hasText(fileId)) return;
        try {
            File trashUpdate = new File();
            trashUpdate.setTrashed(true);
            drive.files().update(fileId, trashUpdate).execute();
        } catch (Exception e) {
            log.warn("Chuyen file Drive (id={}) vao thung rac that bai", fileId, e);
            throw new GoogleDriveOperationException("Khong the chuyen file vao thung rac Drive (id=" + fileId + ")", e);
        }
    }

    @Override
    public void deleteFilePermanently(Drive drive, String fileId) {
        if (!StringUtils.hasText(fileId)) return;
        try {
            drive.files().delete(fileId).execute();
        } catch (Exception e) {
            log.warn("Xoa vinh vien file Drive (id={}) that bai", fileId, e);
            throw new GoogleDriveOperationException("Khong the xoa vinh vien file tren Drive (id=" + fileId + ")", e);
        }
    }

    @Override
    public String getFileChecksum(Drive drive, String fileId) {
        if (!StringUtils.hasText(fileId)) return null;
        try {
            File file = drive.files().get(fileId).setFields("md5Checksum").execute();
            return file.getMd5Checksum();
        } catch (Exception e) {
            // Khong throw - day chi la buoc TOI UU (bo qua update thua), neu
            // khong lay duoc checksum (VD file da bi nguoi dung tu xoa tren
            // Drive) thi coi nhu "khong biet", nguoi goi se cu update() binh
            // thuong (an toan hon la chan ca luong sync vi 1 buoc toi uu that bai).
            log.debug("Khong lay duoc md5Checksum cho file Drive (id={}): {}", fileId, e.getMessage());
            return null;
        }
    }
}
