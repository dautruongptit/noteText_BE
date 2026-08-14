# Noted Backend — Kiến trúc & Hướng dẫn triển khai

Backend cho ứng dụng ghi chú **Noted**, xây dựng theo Clean Architecture, phục vụ:
- Đăng nhập bằng Google (OAuth2 + JWT nội bộ + refresh token rotation)
- Lưu metadata note trong **MySQL**, nội dung file thật trên **filesystem của Ubuntu server**
- Đồng bộ nền (background job, "Debounce Sync") lên **Google Drive**
- Không cho phép 2 note active cùng tên trong 1 user — trùng tên ở `create`/`rename` thì **ghi đè** note đang có (giữ nguyên id/uuid); `duplicate` vẫn tự tránh trùng bằng hậu tố "(copy)"
- Hỗ trợ **offline-first**: client dùng IndexedDB làm buffer, đồng bộ lại khi server online trở lại

> Đây là nửa **backend** của dự án Noted. Nửa **frontend** (React/Vite/TS, local-first) nằm ở
> repo song song [`noteText_web`](../noteText_web/README.md) — 2 repo tách biệt nhưng là
> **một sản phẩm duy nhất**; xem README bên đó để biết cách chạy full-stack cùng nhau
> (port, biến môi trường `VITE_API_BASE_URL`/`ALLOWED_ORIGINS` phải khớp nhau giữa 2 phía).

## 1. Kiến trúc phân lớp (Clean Architecture)

```
controller/     ← REST API, chỉ nhận request/trả response, KHÔNG chứa business logic
service/        ← Business logic (interface + impl), transaction boundary
repository/     ← Spring Data JPA, chỉ thao tác DB
domain/entity/  ← JPA entity, model nghiệp vụ thuần
domain/enums/   ← SyncState và các enum nghiệp vụ khác
dto/            ← Request/Response, tách biệt hoàn toàn khỏi entity (không leak entity ra API)
security/       ← JWT provider, filter, OAuth2 success handler, RateLimitInterceptor
event/          ← ApplicationEvent + @TransactionalEventListener cho side-effect bất đồng bộ
exception/      ← Custom exception + GlobalExceptionHandler (@RestControllerAdvice)
util/           ← HashUtil (SHA-256/MD5), CryptoUtil (AES-256-GCM cho refresh token), TokenBucket
config/         ← SecurityConfig, CORS
```

Nguyên tắc dependency: `controller → service → repository`, không có chiều ngược lại;
`entity` không bao giờ được serialize thẳng ra JSON (luôn qua DTO).

## 2. Vì sao DB + Filesystem, không chỉ Drive

| | MySQL | Filesystem Ubuntu | Google Drive |
|---|---|---|---|
| Vai trò | Metadata, auth, chống trùng tên | Nội dung file thật (source of truth) | Bản sao đồng bộ / backup |
| Trên critical path khi lưu? | Có | Có | **Không** (chạy nền, `@Async` + `@Scheduled`) |

→ Người dùng gõ chữ không bao giờ bị chặn bởi tốc độ/rate-limit của Google Drive API.

## 3. Trùng tên file — GHI ĐÈ thay vì từ chối (đổi từ SEC-11)

Trước đây trùng tên bị từ chối 409 (`DuplicateFileNameException`/`DUPLICATE_FILE_NAME`), bảo vệ bằng
2 lớp (service check + `UNIQUE(user_id, display_name)` ở DB). Từ bản này, hành vi đổi hoàn toàn:

- **`createNote()`**: tên trùng với 1 note active đang có → **ghi đè nội dung note đó** (giữ nguyên
  `id`/`uuid`/`drive_file_id` của note cũ), không tạo note mới, không báo lỗi.
- **`rename()`**: đổi tên trùng với 1 note **khác** đang có (target) → **ghi đè nội dung target**
  bằng nội dung của note đang đổi tên (source), giữ nguyên `id`/`uuid` của target; note nguồn (source)
  bị xoá mềm (cùng luồng với `delete()`, dọn Drive ngay qua `NoteDeletedEvent`). Response trả về là
  của **target** (`id` khác `id` request ban đầu) — FE (`useRename.ts`) dựa vào `id` khác nhau này để
  biết đây là 1 lần merge, không phải đổi tên bình thường.
- **`duplicate()`**: **không đổi** — tên "X (copy)" là tự sinh (không phải người dùng gõ), vẫn tự tìm
  tên trống qua `resolveAvailableName()` thay vì ghi đè, tránh mất dữ liệu bất ngờ chỉ vì tên hệ thống tự đặt bị trùng.

Không còn `UNIQUE(user_id, display_name)` ở DB (đã drop từ `V4__drop_notes_unique_name.sql`, vì Drive
cho phép trùng tên) nên không có race-condition fallback riêng nữa — "ghi đè" tự nó là kết quả an toàn
kể cả khi 2 request tạo/đổi tên gần như đồng thời (ai `save()` sau sẽ thắng).

FE hiện cảnh báo (không chặn) khi phát hiện trùng tên trước khi gọi API, yêu cầu người dùng bấm
Lưu/Enter thêm 1 lần nữa để xác nhận ghi đè — xem `RenameModal.tsx`/`useRename.ts` ở repo `noteText_web`.

## 4. Ghi file atomic (chống hỏng file khi server crash giữa chừng)

`LocalFileStorageServiceImpl.writeAtomic()`:
1. Ghi nội dung ra file tạm `{uuid}.txt.{nanoTime}.tmp`
2. `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` — thao tác atomic ở cấp OS, file gốc không bao giờ ở trạng thái ghi dở dang.

Tên file vật lý trên disk **luôn là UUID**, không dùng tên hiển thị do người dùng đặt → tránh path traversal, ký tự đặc biệt, và cho phép rename mà không cần đụng vào file thật.

## 5. Luồng Google OAuth2 — 2 giai đoạn tách biệt

| Giai đoạn | Mục đích | Scope |
|---|---|---|
| **Login** (`/oauth2/authorization/google`) | Xác thực danh tính, tạo `User` trong DB, sinh JWT nội bộ | `openid email profile` |
| **Connect Drive** (`GET /api/drive/connect` → `GET /api/drive/callback`) | Xin quyền `drive.file` + `access_type=offline`, đổi `code` lấy `refresh_token` thật qua `GoogleTokenExchangeService`, tạo/tìm app folder ngay để phát hiện lỗi sớm | `drive.file` |

`refresh_token` được mã hóa AES-256-GCM (`CryptoUtil`) trước khi lưu vào cột `users.drive_refresh_token_enc`.
`state` param của bước connect được ký JWT riêng (`JwtTokenProvider.generateDriveStateToken`) để chống CSRF,
vì `GET /api/drive/callback` là điều hướng trình duyệt thuần túy (không có header `Authorization`) nên phải
`permitAll` ở `SecurityConfig`.

## 6. JWT nội bộ + Refresh Token Rotation

- Access token JWT: sống ngắn (1 giờ).
- Refresh token: chuỗi ngẫu nhiên lưu DB (bảng `refresh_tokens`), **rotate** mỗi lần gọi
  `POST /api/auth/refresh` thành công (token cũ bị thu hồi, cấp token mới) — `RefreshTokenServiceImpl`.
- `POST /api/auth/logout` — thu hồi refresh token của phiên hiện tại.
- `POST /api/auth/logout-all` — thu hồi toàn bộ refresh token của user (mọi thiết bị).
- `RateLimitInterceptor` (token bucket theo `userId`, không theo IP) chặn spam
  `PATCH /api/notes/{id}/content` — bảo vệ server dù frontend có debounce sẵn, không được chỉ dựa vào FE.

## 7. Đồng bộ Drive — kiến trúc "Debounce Sync" (đã thay thế sync theo sự kiện tức thời)

**Lưu ý quan trọng nếu đọc code cũ hơn**: kiến trúc này đã đổi. Trước đây mỗi lần sửa note đều
publish `NoteContentChangedEvent` để kích hoạt sync gần như ngay lập tức. File `NoteContentChangedEvent.java`
vẫn còn tồn tại trong repo nhưng **không còn được publish/lắng nghe ở đâu nữa** — có thể coi là dead code
chờ dọn dẹp. Cơ chế hiện tại (`DriveSyncServiceImpl`):

Mỗi lần nội dung/tên note đổi, note chỉ được đánh dấu `dirty=true` + `syncState=PENDING_DRIVE`. Việc
**thật sự** đẩy lên Drive đi theo 3 kênh:

1. **Debounce định kỳ** (`runDebouncedSyncBatch`, `@Scheduled`): tìm note `dirty=true` đã "yên tĩnh"
   (không sửa thêm) qua một ngưỡng thời gian (mặc định 30s, `app.drive.sync-debounce-idle-ms`), dựa
   trên cột `updated_at` có sẵn.
2. **Flush thủ công** (`flushDirtyNotes`, qua `POST /api/drive/sync-all`): người dùng bấm "Đồng bộ ngay" — đẩy TOÀN BỘ note dirty của riêng user đó, bỏ qua ngưỡng 30s.
3. **Flush lúc đóng tab**: frontend gọi CÙNG endpoint `POST /api/drive/sync-all` qua `fetch(..., {keepalive:true})` trong sự kiện `beforeunload`/`pagehide`.

Ngoại lệ duy nhất còn xử lý **ngay lập tức** (không debounce): xóa note → `NoteDeletedEvent` +
`NoteSyncEventListener` (`@TransactionalEventListener(phase = AFTER_COMMIT)`) xóa file tương ứng trên
Drive ngay, tránh file "mồ côi" tồn tại vô ích. Vì sao `AFTER_COMMIT`: các thao tác Drive là `@Async`
(chạy thread riêng gần như ngay lập tức) — nếu gọi trước khi transaction gốc commit, thread nền có thể
đọc DB qua connection khác và không thấy thay đổi vừa lưu.

**Tối ưu `md5Checksum`**: trước khi `updateFile()` cho note đã có `driveFileId`, so sánh MD5 nội dung
local với `md5Checksum` Google đã tính sẵn — khớp thì bỏ qua update (note bị sửa rồi sửa lại về y hệt
cũ trước khi kịp debounce).

**Nguyên tắc nghiệp vụ chốt (SEC-15)**: Google Drive KHÔNG bắt buộc tên file duy nhất trong 1 folder —
hệ thống này **cho phép trùng tên trên Drive**. Định danh duy nhất cần quan tâm CHỈ LÀ `driveFileId`
(do Google cấp phát) — `uploadFile()` luôn tạo file mới (không tìm theo tên), `updateFile()` luôn định
danh bằng `fileId` có sẵn (không bao giờ tìm lại theo tên).

## 7b. Chọn nhiều để xoá — CÓ; Chọn nhiều để sync — KHÔNG (quyết định nghiệp vụ)

- **Bulk delete** (`POST /api/notes/bulk-delete`): xử lý trong 1 transaction duy nhất, chỉ xoá note
  thực sự thuộc sở hữu `userId` (id lạ bị loại âm thầm, không làm hỏng cả thao tác). Trả về
  `BulkDeleteResponse` gồm `requestedCount`/`deletedCount` để FE báo chính xác cho người dùng.
- **Không có** "chọn nhiều để sync": đồng bộ Drive là cơ chế tự động cho mọi note, không phải thao
  tác người dùng phải chủ động chọn — tránh rủi ro *quên chọn* → mất dữ liệu.

## 8. Offline-first reconciliation (`/api/sync/batch`)

Khi client mất kết nối tới server, dữ liệu vẫn giữ trong **IndexedDB** ở trình duyệt (Local Mode).
Khi có mạng lại (hoặc sau khi đăng nhập lần đầu, xem `migrateLocalNotesToServer` ở frontend), client
gọi 1 lần `POST /api/sync/batch` gửi toàn bộ note đang chờ. Server so sánh `localUpdatedAtEpochMs`
từng item với bản đang có.

**Chiến lược conflict — "giữ cả 2 bản" (không còn tự ý bỏ bản thua):** nếu bản local đang cầm CŨ hơn
bản server (server đã có bản mới hơn, VD sửa từ thiết bị/phiên khác trong lúc item còn nằm chờ trong
hàng đợi offline), server **không** âm thầm bỏ bản local nữa — `NoteService.createConflictCopy()` tách
bản local thành **1 note hoàn toàn mới** (tên có hậu tố `"(xung đột dd/MM HH:mm)"`, cờ
`is_conflict_copy=TRUE`), trả về status `conflict_kept_both` kèm `conflictCopyId`/`conflictCopyName`.
Note server giữ nguyên (`id`/`uuid` không đổi, vẫn là "bản thắng"). Frontend (`useOfflineSync.ts`) coi
`conflict_kept_both` như đã đồng bộ xong (xoá khỏi hàng đợi retry), đồng thời hiện banner báo cho người
dùng biết để tự kiểm tra/gộp — không còn tình trạng retry vô hạn trong im lặng như thiết kế cũ.

## 9. Danh sách API Endpoints (đầy đủ, đối chiếu trực tiếp với controller)

```
# Auth
GET    /api/auth/me
POST   /api/auth/refresh                    (rotate refresh token, đọc cookie httpOnly hoặc body)
POST   /api/auth/logout                     (thu hồi phiên hiện tại)
POST   /api/auth/logout-all                 (thu hồi mọi thiết bị)
GET    /oauth2/authorization/google         (Spring Security tự cung cấp - bước Login)

# Notes
GET    /api/notes
GET    /api/notes/{id}
POST   /api/notes
PATCH  /api/notes/{id}/content              (rate-limit theo user, xem RateLimitInterceptor)
PATCH  /api/notes/{id}/rename
POST   /api/notes/{id}/duplicate
DELETE /api/notes/{id}
POST   /api/notes/bulk-delete
GET    /api/notes/check-name?name=xxx
GET    /api/notes/{id}/download

# Drive
GET    /api/drive/status
GET    /api/drive/connect                   (bước Connect Drive - trả authUrl)
GET    /api/drive/callback                  (Google redirect về đây - permitAll, không cần JWT)
POST   /api/drive/sync-all                  (flush thủ công + khi đóng tab)
DELETE /api/drive/disconnect

# Offline sync
POST   /api/sync/batch
```

## 10. Chạy local

```bash
docker network create dev-network   # nếu chưa có

cp .env.example .env
# Điền GOOGLE_CLIENT_ID/SECRET, JWT_SECRET, CRYPTO_SECRET_KEY
openssl rand -base64 32   # dùng để sinh JWT_SECRET và CRYPTO_SECRET_KEY

docker compose up -d --build
```

Backend chạy tại `http://localhost:8085`. MySQL tại `localhost:3306` (container `mysql8`, cùng
`dev-network`). Frontend build ra `dist/` và serve qua nginx ở port `85` (xem repo
[`noteText_web`](../noteText_web/README.md)), nginx reverse-proxy `/api/**` sang backend `8085`.
Nếu chạy frontend bằng `pnpm dev` trực tiếp (không qua nginx), nhớ khớp `ALLOWED_ORIGINS` +
`FRONTEND_REDIRECT_URL`/`DRIVE_FRONTEND_CALLBACK_URL` trong `.env` với đúng port frontend đang chạy
(mặc định quy ước 5175).

## 11. Trạng thái thật hiện tại (đối chiếu code, không phải checklist dự đoán)

Đã implement đầy đủ và có test:
- ✅ `GoogleTokenExchangeService` (đổi `code` → `access_token`/`refresh_token`) — `GoogleTokenExchangeServiceImplTest`
- ✅ Refresh token + rotation cho JWT nội bộ — `RefreshTokenServiceImplTest`
- ✅ Rate limiting cho `PATCH /content` — `RateLimitInterceptor` + `TokenBucket`
- ✅ Trùng tên → ghi đè ở `create`/`rename` (giữ nguyên `duplicate` tự tránh trùng) — `NoteServiceImplTest`
- ✅ Ghi file atomic — `LocalFileStorageServiceImplTest`

Còn thiếu / dở dang thật sự (đã đọc trực tiếp code để xác nhận, không suy đoán):
- ⚠️ **`NotePurgeServiceImpl.purgeExpiredNotes()` là stub RỖNG** — tính `threshold` xong không làm gì
  cả. Note soft-delete quá `app.notes.purge-after-days` (mặc định 30 ngày) **hiện KHÔNG bao giờ bị
  xóa vĩnh viễn thật** (cả file vật lý, record DB, lẫn bản trên Drive) dù job `@Scheduled` có chạy đều.
  Đây là việc cần làm tiếp theo ưu tiên cao nhất.
- 🧹 `NoteContentChangedEvent`/kiến trúc sync-theo-sự-kiện cũ là dead code, có thể xóa hẳn (xem mục 7).
- 🧪 Chưa có unit test cho `GoogleDriveServiceImpl`/`DriveSyncServiceImpl` (mock Drive API) và
  `RateLimitInterceptor`/`TokenBucket`/`JwtTokenProvider`.
- 🔐 Chưa có SSL/HTTPS, CI/CD, integration test (Testcontainers + MockMvc).
