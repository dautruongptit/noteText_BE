package com.noted.backend.service.impl;

import com.noted.backend.exception.FileStorageException;
import com.noted.backend.service.FileStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Luu file that tren filesystem cua Ubuntu server.
 *
 * Nguyen tac quan trong:
 * 1) Ten vat ly tren disk LUON la {uuid}.txt, KHONG BAO GIO dung ten hien thi cua nguoi dung
 *    -> tranh path traversal (../../etc/passwd), ky tu dac biet, unicode co dau.
 * 2) Ghi atomic: ghi ra file .tmp truoc, sau do Files.move(REPLACE_EXISTING, ATOMIC_MOVE)
 *    -> neu server crash giua luc ghi, file goc van nguyen ven (khong bao gio bi noi dung
 *    ghi do dang / corrupt).
 * 3) Thu muc phan theo userId de tranh 1 folder chua qua nhieu file phang
 *    (VD: /data/noted/notes/12/uuid.txt).
 */
@Service
public class LocalFileStorageServiceImpl implements FileStorageService {

    private final Path basePath;

    public LocalFileStorageServiceImpl(@Value("${app.storage.base-path}") String basePathStr) {
        this.basePath = Paths.get(basePathStr).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new FileStorageException("Khong the tao thu muc luu tru: " + basePath, e);
        }
    }

    @Override
    public String buildRelativePath(Long userId, String noteUuid) {
        return userId + "/" + noteUuid + ".txt";
    }

    @Override
    public void write(String filePath, String content) {

    }

    @Override
    public String store(String content, String fileName) {
        return null;
    }

    @Override
    public long writeAtomic(String relativePath, String content) {
        Path target = resolveAndValidate(relativePath);
        Path tmp = target.resolveSibling(target.getFileName() + "." + System.nanoTime() + ".tmp");

        try {
            Files.createDirectories(target.getParent());

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // ATOMIC_MOVE: tren cung 1 filesystem (cung phan vung), day la thao tac atomic o cap OS
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                // fallback neu filesystem khong ho tro atomic move (VD mount qua network)
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }

            return bytes.length;
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new FileStorageException("Ghi file that bai: " + relativePath, e);
        }
    }

    @Override
    public String read(String relativePath) {
        Path target = resolveAndValidate(relativePath);
        if (!Files.exists(target)) {
            return "";
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FileStorageException("Doc file that bai: " + relativePath, e);
        }
    }

    @Override
    public void delete(String relativePath) {
        Path target = resolveAndValidate(relativePath);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new FileStorageException("Xoa file that bai: " + relativePath, e);
        }
    }

    @Override
    public void copy(String sourceRelativePath, String targetRelativePath) {
        Path source = resolveAndValidate(sourceRelativePath);
        Path target = resolveAndValidate(targetRelativePath);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(source)) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createFile(target);
            }
        } catch (IOException e) {
            throw new FileStorageException("Copy file that bai: " + sourceRelativePath, e);
        }
    }

    /**
     * Chuan hoa duong dan va CHAN path traversal: dam bao duong dan cuoi cung
     * van nam ben trong basePath, khong duoc "thoat ra ngoai" qua "..".
     */
    private Path resolveAndValidate(String relativePath) {
        Path target = basePath.resolve(relativePath).normalize();
        if (!target.startsWith(basePath)) {
            throw new FileStorageException("Duong dan khong hop le (path traversal detected): " + relativePath);
        }
        return target;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup, khong can throw
        }
    }
}
