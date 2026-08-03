# Noted Backend — Kiến trúc & Hướng dẫn triển khai

Backend cho ứng dụng ghi chú **Noted**, xây dựng theo Clean Architecture, phục vụ:
- Đăng nhập bằng Google (OAuth2 + JWT nội bộ)
- Lưu metadata note trong **MySQL**, nội dung file thật trên **filesystem của Ubuntu server**
- Đồng bộ nền (background job) lên **Google Drive**
- Chặn trùng tên file ở cả tầng service lẫn DB constraint
- Hỗ trợ **offline-first**: client dùng IndexedDB làm buffer, đồng bộ lại khi server online trở lại

## 1. Kiến trúc phân lớp (Clean Architecture)

```
controller/     ← REST API, chỉ nhận request/trả response, KHÔNG chứa business logic
service/        ← Business logic (interface + impl), transaction boundary
repository/     ← Spring Data JPA, chỉ thao tác DB
domain/entity/  ← JPA entity, model nghiệp vụ thuần
dto/            ← Request/Response, tách biệt hoàn toàn khỏi entity (không leak entity ra API)
security/       ← JWT provider, filter, OAuth2 success handler
exception/      ← Custom exception + GlobalExceptionHandler (@RestControllerAdvice)
util/           ← HashUtil (SHA-256), CryptoUtil (AES-256-GCM cho refresh token)
config/         ← SecurityConfig, CORS
```

Nguyên tắc dependency: `controller → service → repository`, không có chiều ngược lại;
`entity` không bao giờ được serialize thẳng ra JSON (luôn qua DTO).

## 2. Vì sao DB + Filesystem, không chỉ Drive (nhắc lại quyết định kiến trúc)

| | MySQL | Filesystem Ubuntu | Google Drive |
|---|---|---|---|
| Vai trò | Metadata, auth, chống trùng tên | Nội dung file thật (source of truth) | Bản sao đồng bộ / backup |
| Trên critical path khi lưu? | Có | Có | **Không** (chạy nền, `@Async` + `@Scheduled`) |

→ Người dùng gõ chữ không bao giờ bị chặn bởi tốc độ/rate-limit của Google Drive API.

## 3. Chặn trùng tên file — 2 lớp bảo vệ

1. **Tầng service** (`NoteServiceImpl`): `existsByUserIdAndDisplayNameAndDeletedFalse` — trả lỗi rõ ràng, UX tốt.
2. **Tầng DB** (`UNIQUE(user_id, display_name)` trong migration `V1__init_schema.sql`) — chặn race condition khi 2 request tạo file trùng tên gần như đồng thời (service check không đủ an toàn một mình vì có khoảng hở giữa check và insert).

Khi bắt được `DataIntegrityViolationException` do vi phạm unique constraint, service convert thành `DuplicateFileNameException` → `GlobalExceptionHandler` trả về **409 Conflict**.

## 4. Ghi file atomic (chống hỏng file khi server crash giữa chừng)

`LocalFileStorageServiceImpl.writeAtomic()`:
1. Ghi nội dung ra file tạm `{uuid}.txt.{nanoTime}.tmp`
2. `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` — thao tác atomic ở cấp OS, file gốc không bao giờ ở trạng thái ghi dở dang.

Tên file vật lý trên disk **luôn là UUID**, không dùng tên hiển thị do người dùng đặt → tránh path traversal, ký tự đặc biệt, và cho phép rename mà không cần đụng vào file thật.

## 5. Luồng Google OAuth2 — 2 giai đoạn tách biệt

| Giai đoạn | Mục đích | Scope |
|---|---|---|
| **Login** (`/oauth2/authorization/google`) | Xác thực danh tính, tạo `User` trong DB, sinh JWT nội bộ | `openid email profile` |
| **Connect Drive** (`/api/drive/connect`) | Xin quyền `drive.file` + `access_type=offline` để lấy `refresh_token` dùng cho job sync nền | `drive.file` |

`refresh_token` được mã hóa AES-256-GCM (`CryptoUtil`) trước khi lưu vào cột `users.drive_refresh_token_enc`.

## 6. Đồng bộ Drive chạy nền

- `DriveSyncServiceImpl.syncNote()` — `@Async`, không block request `PATCH /content`.
- `runPendingSyncBatch()` — `@Scheduled(fixedDelay = 30000)`, quét tối đa 50 note đang `PENDING_DRIVE`/chưa vượt `sync_attempts` để retry.
- Trạng thái đồng bộ (`sync_state`) hiển thị lên UI: `SYNCED` / `PENDING_DRIVE` / `DRIVE_FAILED` / `CONFLICT`.

## 7. Offline-first reconciliation (`/api/sync/batch`)

Khi client mất kết nối tới Ubuntu server, dữ liệu vẫn được giữ trong **IndexedDB** ở trình duyệt.
Khi có mạng lại, client gọi 1 lần `POST /api/sync/batch` gửi toàn bộ note đang "pending_server".
Server so sánh `updatedAt` để phát hiện conflict (chiến lược mặc định: bản mới hơn thắng; có thể nâng cấp trả về cả 2 bản để người dùng tự chọn).

## 7b. Chọn nhiều để xoá — CÓ; Chọn nhiều để sync — KHÔNG (quyết định nghiệp vụ)

- **Bulk delete** (`POST /api/notes/bulk-delete`): xử lý trong 1 transaction duy nhất, chỉ xoá note thực sự thuộc sở hữu `userId` (id lạ bị loại âm thầm, không làm hỏng cả thao tác). Trả về `BulkDeleteResponse` gồm `requestedCount`/`deletedCount` để FE báo chính xác cho người dùng.
- **Không có** "chọn nhiều để sync": đồng bộ Drive là cơ chế **tự động cho mọi note**, không phải thao tác người dùng phải chủ động chọn. Lý do: bắt người dùng chọn file để sync tạo rủi ro *quên chọn* → mất dữ liệu khi server không tới được — đi ngược mục tiêu ban đầu của tính năng Drive sync (an toàn dữ liệu).

## 7c. Sync theo sự kiện (event-based), không chỉ dựa vào job định kỳ

`NoteContentChangedEvent` + `NoteSyncEventListener` (`@TransactionalEventListener(phase = AFTER_COMMIT)`):

- Mỗi lần `createNote` / `updateContent` / `duplicate` **commit thành công**, event được phát ra → `DriveSyncService.syncNote()` được gọi gần như ngay lập tức, không cần đợi tới lần quét định kỳ tiếp theo.
- **Vì sao dùng `AFTER_COMMIT` thay vì gọi thẳng `syncNote()` trong transaction:** `syncNote()` là `@Async`, chạy trên thread riêng gần như ngay khi được gọi. Nếu gọi trước khi transaction gốc commit, thread nền có thể đọc DB qua 1 connection khác và **không thấy** note vừa tạo (do transaction gốc chưa commit) → lỗi "note not found" ngẫu nhiên, khó debug. `AFTER_COMMIT` đảm bảo listener chỉ chạy sau khi dữ liệu đã chắc chắn có trong DB.
- Job định kỳ `runPendingSyncBatch()` (`@Scheduled` mỗi 30s) **vẫn được giữ lại** làm lưới an toàn dự phòng, bắt các note bị lỡ trigger sự kiện (VD server restart đúng lúc, lỗi mạng tạm thời ở lần sync đầu).

## 8. Danh sách API Endpoints

```
# Auth
GET    /api/auth/me
POST   /api/auth/logout
GET    /oauth2/authorization/google        (Spring Security tự cung cấp)

# Notes
GET    /api/notes
GET    /api/notes/{id}
POST   /api/notes
PATCH  /api/notes/{id}/content
PATCH  /api/notes/{id}/rename
POST   /api/notes/{id}/duplicate
DELETE /api/notes/{id}
POST   /api/notes/bulk-delete               (chọn nhiều để xoá - 1 request, 1 transaction)
GET    /api/notes/check-name?name=xxx
GET    /api/notes/{id}/download

# Drive
GET    /api/drive/status
GET    /api/drive/connect
POST   /api/drive/sync-all
DELETE /api/drive/disconnect

# Offline sync
POST   /api/sync/batch
```

## 9. Chạy local

```bash
docker network create dev-network   # nếu chưa có

cp .env.example .env
# Điền GOOGLE_CLIENT_ID/SECRET, JWT_SECRET, CRYPTO_SECRET_KEY
openssl rand -base64 32   # dùng để sinh JWT_SECRET và CRYPTO_SECRET_KEY

docker compose up -d --build
```

Backend chạy tại `http://localhost:8085` (đổi từ 8080 mặc định vì đã bị chiếm dụng, sau đó chốt lại là 8085 — không dùng 8084 như bản nháp trước), MySQL tại `localhost:3306` (container `mysql8`, cùng `dev-network` theo quy ước bạn đang dùng). Frontend build ra `dist/` và serve qua nginx ở port `85` (xem repo `noteText`), nginx reverse-proxy `/api/**` sang backend `8085` — nếu bạn không dùng nginx proxy mà cho frontend gọi thẳng, nhớ cập nhật `ALLOWED_ORIGINS` và `FRONTEND_REDIRECT_URL` trong `.env` cho khớp port thật của frontend.

## 10. Việc còn cần bổ sung trước khi lên production

- [ ] `GoogleTokenExchangeService`: đổi `code` lấy `access_token` + `refresh_token` ở endpoint `/api/drive/callback` (hiện mới có khung `DriveController.connect()`, chưa có phần exchange code — cần gọi trực tiếp Google token endpoint bằng `RestClient`).
- [ ] Refresh token cho chính JWT nội bộ (hiện access token 1 giờ, chưa có refresh token riêng — bảng `refresh_tokens` đã có sẵn trong schema, chỉ cần bổ sung `AuthController`).
- [ ] Rate limiting cho endpoint `PATCH /content` (tránh client auto-save quá dày gây tải DB).
- [ ] Job dọn note đã soft-delete quá X ngày (purge file vật lý + record DB).
- [ ] Unit test cho `NoteServiceImpl` (đặc biệt case race condition trùng tên) và `LocalFileStorageServiceImpl` (atomic write).
#   n o t e T e x t _ B E  
 