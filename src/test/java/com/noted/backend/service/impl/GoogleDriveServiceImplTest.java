package com.noted.backend.service.impl;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.noted.backend.util.CryptoUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Bug thuc te 2026-08-22 (nghiem trong - ro ri du lieu giua 2 tai khoan):
 * mot tai khoan MOI bam "Dong bo ngay" thi note cua no duoc ghi vao thu muc
 * NotedApp THUOC SO HUU CUA TAI KHOAN KHAC, thay vi tao thu muc rieng. Nguoi
 * dung chi thay trieu chung "app khong tu tao thu muc tren Drive cua toi".
 *
 * Nguyen nhan: cau truy vam tim thu muc khong loc theo nguoi so huu, ma
 * setSpaces("drive") thi bao gom ca "Duoc chia se voi toi".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoogleDriveServiceImplTest {

    @Mock private CryptoUtil cryptoUtil;
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) private Drive drive;

    private GoogleDriveServiceImpl service() {
        return new GoogleDriveServiceImpl(cryptoUtil);
    }

    @Test
    void findFolderByName_chiTimThuMucDoCHINHTaiKhoanNaySoHuu() throws Exception {
        Drive.Files.List listReq = drive.files().list();
        when(listReq.setQ(anyString())).thenReturn(listReq);
        when(listReq.setSpaces(anyString())).thenReturn(listReq);
        when(listReq.setFields(anyString())).thenReturn(listReq);
        when(listReq.execute()).thenReturn(new FileList().setFiles(List.of()));

        service().findFolderByName(drive, "NotedApp");

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(listReq).setQ(q.capture());

        // Dieu kien SONG CON: khong co no, tai khoan moi se "nhan vo" thu muc
        // duoc nguoi khac chia se thay vi tao thu muc cua chinh minh.
        assertThat(q.getValue()).contains("'me' in owners");
        assertThat(q.getValue()).contains("name='NotedApp'");
        assertThat(q.getValue()).contains("trashed=false");
    }

    @Test
    void findFolderByName_traVeThuMucKhiTimThay() throws Exception {
        Drive.Files.List listReq = drive.files().list();
        when(listReq.setQ(anyString())).thenReturn(listReq);
        when(listReq.setSpaces(anyString())).thenReturn(listReq);
        when(listReq.setFields(anyString())).thenReturn(listReq);
        when(listReq.execute()).thenReturn(
                new FileList().setFiles(List.of(new File().setId("folder-cua-toi"))));

        Optional<String> found = service().findFolderByName(drive, "NotedApp");

        assertThat(found).contains("folder-cua-toi");
    }

    // ---------- isFileUsable(): 3 dieu kien quyet dinh co duoc ghi de len 1
    // file hay khong. "drive_file_id" da luu la thu DUY NHAT quyet dinh file nao
    // bi ghi de, ma truoc day khong co gi kiem tra lai no. ----------

    private void stubFilesGet(File traVe) throws Exception {
        Drive.Files.Get getReq = drive.files().get(anyString());
        when(getReq.setFields(anyString())).thenReturn(getReq);
        when(getReq.execute()).thenReturn(traVe);
    }

    @Test
    void isFileUsable_dungKhiFileConNguyenVenTrongThuMucCuaMinh() throws Exception {
        stubFilesGet(new File().setTrashed(false).setOwnedByMe(true)
                .setParents(List.of("thu-muc-cua-toi")));

        assertThat(service().isFileUsable(drive, "file-1", "thu-muc-cua-toi")).isTrue();
    }

    @Test
    void isFileUsable_saiKhiFileThuocSoHuuTaiKhoanKhac() throws Exception {
        // Truong hop nguy hiem nhat: file nam DUNG trong thu muc cua minh (do
        // minh da chia se thu muc, nguoi khac tao file vao day) nhung KHONG
        // phai cua minh. Google van cho ghi de - chi co kiem tra nay chan duoc.
        stubFilesGet(new File().setTrashed(false).setOwnedByMe(false)
                .setParents(List.of("thu-muc-cua-toi")));

        assertThat(service().isFileUsable(drive, "file-1", "thu-muc-cua-toi")).isFalse();
    }

    @Test
    void isFileUsable_saiKhiFileDaBiKeoRaKhoiAppFolder() throws Exception {
        stubFilesGet(new File().setTrashed(false).setOwnedByMe(true)
                .setParents(List.of("mot-thu-muc-khac")));

        assertThat(service().isFileUsable(drive, "file-1", "thu-muc-cua-toi")).isFalse();
    }

    @Test
    void isFileUsable_saiKhiFileDangTrongThungRac() throws Exception {
        stubFilesGet(new File().setTrashed(true).setOwnedByMe(true)
                .setParents(List.of("thu-muc-cua-toi")));

        assertThat(service().isFileUsable(drive, "file-1", "thu-muc-cua-toi")).isFalse();
    }

    @Test
    void isFolderOwnedByMe_saiKhiThuMucLaCuaNguoiKhacDuChiaSeQuyenSua() throws Exception {
        // Dung cap gia tri da xac nhan qua Drive API that trong bug 2026-08-22.
        stubFilesGet(new File().setMimeType("application/vnd.google-apps.folder")
                .setOwnedByMe(false).setTrashed(false));

        assertThat(service().isFolderOwnedByMe(drive, "thu-muc-nguoi-khac")).isFalse();
    }

    @Test
    void isFolderOwnedByMe_dungKhiLaThuMucCuaChinhMinh() throws Exception {
        stubFilesGet(new File().setMimeType("application/vnd.google-apps.folder")
                .setOwnedByMe(true).setTrashed(false));

        assertThat(service().isFolderOwnedByMe(drive, "thu-muc-cua-toi")).isTrue();
    }

    @Test
    void listFilesInFolder_chiLayFileCuaChinhMinh() throws Exception {
        Drive.Files.List listReq = drive.files().list();
        when(listReq.setQ(anyString())).thenReturn(listReq);
        when(listReq.setSpaces(anyString())).thenReturn(listReq);
        when(listReq.setFields(anyString())).thenReturn(listReq);
        when(listReq.execute()).thenReturn(new FileList().setFiles(List.of()));

        service().listFilesInFolder(drive, "thu-muc-cua-toi");

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(listReq).setQ(q.capture());
        assertThat(q.getValue()).contains("'me' in owners");
    }
}
