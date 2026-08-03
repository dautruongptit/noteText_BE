package com.noted.backend.service.impl;

import com.noted.backend.exception.FileStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class LocalFileStorageServiceImplTest {

    @TempDir
    Path tempDir;

    private LocalFileStorageServiceImpl newService() {
        return new LocalFileStorageServiceImpl(tempDir.toString());
    }

    @Test
    void writeAtomic_roiRead_traVeDungNoiDungVuaGhi() {
        var service = newService();
        String path = service.buildRelativePath(1L, "uuid-abc");

        service.writeAtomic(path, "Xin chao, day la noi dung tieng Viet co dau");

        assertThat(service.read(path)).isEqualTo("Xin chao, day la noi dung tieng Viet co dau");
    }

    @Test
    void writeAtomic_ghiDe_KHONG_conSotNoiDungCu() {
        // Xac nhan StandardOpenOption.TRUNCATE_EXISTING hoat dong dung: ghi noi
        // dung DAI truoc, roi ghi noi dung NGAN de - neu con sot byte cu, read()
        // se tra ve chuoi dai hon mong doi (co rac o cuoi).
        var service = newService();
        String path = service.buildRelativePath(1L, "uuid-abc");

        service.writeAtomic(path, "Noi dung rat dai o lan ghi dau tien, dai hon nhieu so voi lan sau");
        service.writeAtomic(path, "ngan");

        assertThat(service.read(path)).isEqualTo("ngan");
    }

    @Test
    void writeAtomic_khongDeSotFileTmpSauKhiGhiThanhCong() throws IOException {
        var service = newService();
        String path = service.buildRelativePath(1L, "uuid-abc");

        service.writeAtomic(path, "noi dung");

        Path noteDir = tempDir.resolve("1");
        try (Stream<Path> files = Files.list(noteDir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertThat(names).containsExactly("uuid-abc.txt"); // KHONG con file *.tmp nao sot lai
        }
    }

    @Test
    void read_traVeChuoiRong_khiFileChuaTonTai() {
        var service = newService();
        String path = service.buildRelativePath(1L, "khong-ton-tai");

        assertThat(service.read(path)).isEqualTo("");
    }

    @Test
    void resolveAndValidate_chanPathTraversal_khiDuongDanCoDauCham2Lan() {
        var service = newService();

        // "../../etc/passwd" khi resolve+normalize se thoat ra ngoai basePath
        assertThatThrownBy(() -> service.read("../../etc/passwd"))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void resolveAndValidate_chanPathTraversal_kheoLeoVoiThuMucConHopLe() {
        var service = newService();

        // Duong dan bat dau giong hop le (co userId that) nhung chua "../.."
        // o giua de "thoat" ra khoi thu muc goc - phai bi chan giong het nhu truong hop don gian.
        assertThatThrownBy(() -> service.read("1/../../../etc/passwd"))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void delete_xoaFile_readSauDoTraVeChuoiRong() {
        var service = newService();
        String path = service.buildRelativePath(1L, "uuid-xyz");
        service.writeAtomic(path, "se bi xoa");

        service.delete(path);

        assertThat(service.read(path)).isEqualTo("");
    }

    @Test
    void copy_saoChepDungNoiDung_tuFileNguonSangFileDich() {
        var service = newService();
        String source = service.buildRelativePath(1L, "nguon");
        String target = service.buildRelativePath(1L, "dich");
        service.writeAtomic(source, "noi dung goc");

        service.copy(source, target);

        assertThat(service.read(target)).isEqualTo("noi dung goc");
        assertThat(service.read(source)).isEqualTo("noi dung goc"); // file nguon khong bi anh huong
    }

    @Test
    void copy_taoFileRong_khiFileNguonChuaTonTai() {
        var service = newService();
        String source = service.buildRelativePath(1L, "chua-ton-tai");
        String target = service.buildRelativePath(1L, "dich");

        service.copy(source, target);

        assertThat(service.read(target)).isEqualTo("");
    }

    @Test
    void buildRelativePath_phanThuMucTheoUserId() {
        var service = newService();

        assertThat(service.buildRelativePath(42L, "note-uuid")).isEqualTo("42/note-uuid.txt");
    }
}
