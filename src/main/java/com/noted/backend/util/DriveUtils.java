package com.noted.backend.util;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

@Slf4j
public class DriveUtils {

    /**
     * Lấy danh sách tệp kèm đầy đủ metadata từ thư mục chỉ định trên Google Drive.
     *
     * @param drive    Đối tượng Google Drive client đã kết nối
     * @param folderId ID thư mục chứa tệp cần quét
     * @return Danh sách các đối tượng File (chứa id, name, size, modifiedTime, owners, mimeType)
     */
    public static List<File> getAllFilesInfo(Drive drive, String folderId) {
        if (drive == null || folderId == null || folderId.isBlank()) {
            log.warn("Drive client hoặc folderId không hợp lệ");
            return Collections.emptyList();
        }

        try {
            // Truy vấn lấy tất cả tệp chưa bị xóa trong folderId
            String query = String.format("'%s' in parents and trashed=false", folderId);

            FileList result = drive.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    // Chọn đúng các thuộc tính hiển thị giống giao diện Web Google Drive
                    .setFields("files(id, name, size, modifiedTime, owners, mimeType)")
                    .execute();

            List<File> files = result.getFiles();

            if (files != null && !files.isEmpty()) {
                log.info("Tìm thấy {} tệp trong thư mục (folderId={}):", files.size(), folderId);
                for (File file : files) {
                    String ownerName = (file.getOwners() != null && !file.getOwners().isEmpty())
                            ? file.getOwners().get(0).getDisplayName()
                            : "Tôi";

                    log.info("-> File ID: {} | Tên: {} | Size: {} bytes | Ngày sửa: {} | Chủ sở hữu: {}",
                            file.getId(),
                            file.getName(),
                            file.getSize() != null ? file.getSize() : 0,
                            file.getModifiedTime(),
                            ownerName);
                }
                return files;
            } else {
                log.info("Thư mục (folderId={}) hiện tại đang rỗng.", folderId);
                return Collections.emptyList();
            }

        } catch (Exception e) {
            log.error("Lỗi khi quét lấy thông tin danh sách tệp từ Google Drive (folderId={})", folderId, e);
            throw new RuntimeException("Không thể lấy danh sách tệp từ Google Drive: " + e.getMessage(), e);
        }
    }
}
