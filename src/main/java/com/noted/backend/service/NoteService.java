package com.noted.backend.service;

import com.noted.backend.dto.request.CreateNoteRequest;
import com.noted.backend.dto.request.RenameNoteRequest;
import com.noted.backend.dto.request.UpdateContentRequest;
import com.noted.backend.dto.response.BulkDeleteResponse;
import com.noted.backend.dto.response.NoteDetailResponse;
import com.noted.backend.dto.response.NoteSummaryResponse;

import java.util.List;

public interface NoteService {

    List<NoteSummaryResponse> listNotes(Long userId);

    NoteDetailResponse getNote(Long userId, Long noteId);

    NoteDetailResponse createNote(Long userId, CreateNoteRequest request);

    NoteDetailResponse updateContent(Long userId, Long noteId, UpdateContentRequest request);

    NoteSummaryResponse rename(Long userId, Long noteId, RenameNoteRequest request);

    NoteDetailResponse duplicate(Long userId, Long noteId);

    void delete(Long userId, Long noteId);

    /**
     * Xoa nhieu note cung luc (chon nhieu de xoa - tinh nang moi).
     * Chi xoa (soft-delete) cac note THUC SU thuoc so huu userId; id khong hop le
     * hoac khong thuoc user se bi bo qua am tham, khong throw loi giua chung
     * (tranh 1 id sai lam hong ca thao tac xoa hang loat).
     */
    BulkDeleteResponse bulkDelete(Long userId, List<Long> noteIds);

    /** Kiem tra ten da ton tai chua - dung cho validate real-time o frontend */
    boolean isNameTaken(Long userId, String displayName);

    /** Sinh ten khong trung, VD "New 1.txt" da co -> tra ve "New 1 (2).txt" */
    String resolveAvailableName(Long userId, String desiredName);

    byte[] downloadAsBytes(Long userId, Long noteId);
}
