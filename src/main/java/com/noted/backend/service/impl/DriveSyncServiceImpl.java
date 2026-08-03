package com.noted.backend.service.impl;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.noted.backend.domain.entity.Note;
import com.noted.backend.domain.entity.User;
import com.noted.backend.domain.enums.SyncState;
import com.noted.backend.repository.NoteRepository;
import com.noted.backend.repository.UserRepository;
import com.noted.backend.service.DriveSyncService;
import com.noted.backend.service.FileStorageService;
import com.noted.backend.util.CryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Dong bo file len Google Drive, chay hoan toan NEN (async / scheduled job),
 * KHONG BAO GIO nam tren critical path cua thao tac luu note cua nguoi dung.
 *
 * Co 2 co che kich hoat sync, bo sung cho nhau:
 *  1) Theo su kien (chinh): NoteSyncEventListener goi syncNote() ngay sau khi
 *     transaction luu note commit thanh cong -> gan nhu tuc thi, khong doi 30s.
 *  2) Theo job dinh ky (luoi an toan du phong): runPendingSyncBatch() quet lai
 *     cac note co the bi lo o buoc (1) - VD server restart dung luc, loi mang tam thoi.
 *
 * Nguoi dung KHONG can (va KHONG nen) tu chon file nao de sync - moi note deu
 * duoc dong bo tu dong, tranh rui ro quen sync gay mat du lieu.
 *
 * Neu Drive loi (het quota, mat mang, token het han...), note van an toan tren
 * disk cua Ubuntu server; co che (2) se tu dong retry o lan chay ke tiep.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DriveSyncServiceImpl implements DriveSyncService {

    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final CryptoUtil cryptoUtil;

    @Value("${app.drive.app-folder-name}")
    private String appFolderName;

    @Value("${app.drive.sync-retry-max-attempts}")
    private int maxAttempts;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String clientSecret;

    @Override
    @Transactional
    public String ensureAppFolder(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        if (user.getDriveFolderId() != null) {
            return user.getDriveFolderId();
        }

        Drive drive = buildDriveClient(user);
        try {
            String query = "name='" + appFolderName + "' and mimeType='application/vnd.google-apps.folder' and trashed=false";
            FileList result = drive.files().list().setQ(query).setSpaces("drive").execute();

            String folderId;
            if (!result.getFiles().isEmpty()) {
                folderId = result.getFiles().get(0).getId();
            } else {
                File folderMeta = new File();
                folderMeta.setName(appFolderName);
                folderMeta.setMimeType("application/vnd.google-apps.folder");
                File created = drive.files().create(folderMeta).setFields("id").execute();
                folderId = created.getId();
            }

            user.setDriveFolderId(folderId);
            user.setDriveConnected(true);
            userRepository.save(user);
            return folderId;
        } catch (Exception e) {
            log.error("Khong the tao/tim app folder tren Drive cho user {}", userId, e);
            throw new IllegalStateException("Loi ket noi Google Drive", e);
        }
    }

    @Override
    @Async
    @Transactional
    public void syncNote(Long noteId) {
        Note note = noteRepository.findById(noteId).orElse(null);
        if (note == null || note.isDeleted()) return;

        try {
            User user = userRepository.findById(note.getUserId()).orElseThrow();
            if (!user.isDriveConnected()) {
                return; // nguoi dung chua ket noi Drive, khong co gi de lam
            }

            String folderId = ensureAppFolder(user.getId());
            Drive drive = buildDriveClient(user);
            String content = fileStorageService.read(note.getFilePath());

            ByteArrayContent fileContent = new ByteArrayContent("text/plain",
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            if (note.getDriveFileId() == null) {
                File meta = new File();
                meta.setName(note.getDisplayName());
                meta.setParents(Collections.singletonList(folderId));
                File created = drive.files().create(meta, fileContent).setFields("id").execute();
                note.setDriveFileId(created.getId());
            } else {
                // Cap nhat noi dung; neu ten da doi thi cap nhat luon metadata ten
                File meta = new File();
                meta.setName(note.getDisplayName());
                drive.files().update(note.getDriveFileId(), meta, fileContent).execute();
            }

            note.setSyncState(SyncState.SYNCED);
            note.setDriveSyncedAt(LocalDateTime.now());
            note.setDriveSyncAttempts(0);
            note.setDriveSyncError(null);

        } catch (Exception e) {
            log.warn("Sync Drive that bai cho note {}: {}", noteId, e.getMessage());
            note.setDriveSyncAttempts(note.getDriveSyncAttempts() + 1);
            note.setDriveSyncError(truncate(e.getMessage(), 500));
            note.setSyncState(note.getDriveSyncAttempts() >= maxAttempts
                    ? SyncState.DRIVE_FAILED
                    : SyncState.PENDING_DRIVE);
        }

        noteRepository.save(note);
    }

    @Override
    @Scheduled(fixedDelayString = "${app.drive.sync-job-fixed-delay-ms}")
    @Transactional(readOnly = true)
    public void runPendingSyncBatch() {
        List<Note> pending = noteRepository.findTop50BySyncStateInAndDriveSyncAttemptsLessThanOrderByUpdatedAtAsc(
                List.of(SyncState.PENDING_DRIVE), maxAttempts);

        for (Note note : pending) {
            syncNote(note.getId()); // moi call chay async (@Async), khong block vong lap
        }
    }

    @Override
    @Transactional
    public void disconnect(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        user.setDriveConnected(false);
        user.setDriveFolderId(null);
        user.setDriveRefreshTokenEnc(null);
        userRepository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public void deleteFromDrive(Long noteId) {
        Note note = noteRepository.findById(noteId).orElse(null);
        if (note == null || note.getDriveFileId() == null) {
            return; // chua tung sync len Drive (hoac note khong con ton tai) - khong co gi de xoa
        }

        try {
            User user = userRepository.findById(note.getUserId()).orElse(null);
            if (user == null || !user.isDriveConnected()) {
                return; // user da ngat ket noi Drive tu truoc - khong the/khong can xoa nua
            }

            Drive drive = buildDriveClient(user);
            drive.files().delete(note.getDriveFileId()).execute();
            log.info("Da xoa file tren Drive cho note id={} (driveFileId={})", noteId, note.getDriveFileId());
        } catch (Exception e) {
            // Best-effort - KHONG throw (xem javadoc o interface). Nguyen nhan
            // thuong gap: token Drive het han, nguoi dung da tu tay xoa file
            // nay tren Drive tu truoc (Google se tra 404), hoac vua ngat ket
            // noi Drive giua chung - deu KHONG can retry, chi log lai de biet.
            log.warn("Xoa file tren Drive that bai cho note id={} (driveFileId={}), bo qua (best-effort)",
                    noteId, note.getDriveFileId(), e);
        }
    }

    private Drive buildDriveClient(User user) {
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
            throw new IllegalStateException("Khong the khoi tao Drive client", e);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
