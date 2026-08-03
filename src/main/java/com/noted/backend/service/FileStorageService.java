package com.noted.backend.service;

public interface FileStorageService {

    /**
     * Ghi noi dung ra file, atomic (ghi ra .tmp roi rename de tranh hong file
     * neu server crash giua chung).
     *
     * @param relativePath duong dan tuong doi, VD "12/ab12cd34-....txt" (12 = userId, tranh 1 thu muc qua nhieu file)
     * @param content      noi dung text
     * @return so byte da ghi
     */
    long writeAtomic(String relativePath, String content);

    /** Doc noi dung file, tra ve chuoi rong neu file chua ton tai (note vua tao, chua co noi dung) */
    String read(String relativePath);

    /** Xoa file vat ly (dung soft-delete o tang DB, nhung van co the goi ham nay khi purge that su) */
    void delete(String relativePath);

    /** Copy file (dung cho chuc nang Duplicate) */
    void copy(String sourceRelativePath, String targetRelativePath);

    /** Sinh duong dan tuong doi chuan cho 1 note moi, dua tren userId + uuid */
    String buildRelativePath(Long userId, String noteUuid);
}
