**ĐẶC TẢ YÊU CẦU PHẦN MỀM**

**(SOFTWARE REQUIREMENT SPECIFICATION – SRS)**

_Enterprise Log Aggregation & Real-time Observability Platform_

Kiến trúc: Event-Driven Architecture / Microservices-ready

**Phiên bản 2.1 — System-Level Product Specification (đã khóa logic)**

# **Lịch sử thay đổi tài liệu**

| **Phiên bản** | **Ngày**   | **Mô tả thay đổi**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | **Người thực hiện** |
| ------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------- |
| 1.0           | —          | Bản đặc tả kỹ thuật sơ khởi (technical-only), tập trung vào Ingestion, Tracing, Alert Engine, Retention, Dashboard.                                                                                                                                                                                                                                                                                                                                                                                                       | Tác giả dự án       |
| 2.0           | 31/08/2026 | Bổ sung đầy đủ lớp nghiệp vụ (Actor/Use case), Security & RBAC, Service Registry, Alert Rule Management & Lifecycle, Canonical Event Schema, Idempotency, Retry/DLQ, Rate Limiting, Audit Log, API Specification, Business Rules, Failure Scenarios, Performance Benchmark đo lường được, Test Matrix, Monitoring nội bộ nền tảng.                                                                                                                                                                                        | Tác giả dự án       |
| 2.1           | 01/09/2026 | Sửa 6 điểm chưa nhất quán về logic phát hiện ở vòng review v2.0: (1) mô hình User↔Role chuyển sang N:N qua UserRole; (2) Service API Key đổi sang lưu apiKeyHash; (3) chốt thuật toán Rate Limiting (Token Bucket) kèm tham số; (4) định nghĩa ngưỡng Service Health deterministic; (5) chốt Kafka partition key = serviceId kèm trade-off; (6) bổ sung Acceptance Criteria (Given/When/Then) cho các FR trọng yếu. Đồng thời làm rõ ranh giới lưu trữ RDBMS/Elasticsearch/Redis và bổ sung Elasticsearch Index Template. | Tác giả dự án       |

_Ghi chú: Phiên bản 2.0 giải quyết toàn bộ 29 điểm còn thiếu/cần chỉnh sửa được nêu trong biên bản đánh giá SRS v1.0, nâng mức hoàn thiện từ ~7/10 lên khoảng 9/10. Phiên bản 2.1 khóa các điểm chưa nhất quán về logic còn lại trước khi chuyển sang giai đoạn System Design, theo đúng khuyến nghị của vòng đánh giá: không tiếp tục mở rộng phạm vi SRS quá mức cần thiết._

# **1\. Giới thiệu (Introduction)**

## **1.1. Vấn đề nghiệp vụ (Problem Statement)**

Một doanh nghiệp vận hành hàng chục microservices sinh ra khối lượng log rất lớn mỗi ngày. Khi sự cố xảy ra, đội ngũ kỹ thuật không có công cụ tập trung để thu thập, chuẩn hóa, tìm kiếm và liên kết log giữa các service, dẫn đến thời gian phát hiện (MTTD) và khắc phục sự cố (MTTR) kéo dài. Hệ thống hiện tại thiếu một nền tảng quan sát (Observability Platform) có khả năng thu thập log thời gian thực, phát hiện bất thường và cảnh báo chủ động.

Cách tiếp cận của tài liệu này đi theo trình tự Problem → Requirement → Architecture → Technology, thay vì xuất phát từ công nghệ rồi mới tìm bài toán để áp dụng.

## **1.2. Mục tiêu nghiệp vụ (Business Objective)**

- Tập trung hóa toàn bộ log từ nhiều microservices vào một nền tảng duy nhất.
- Cho phép tìm kiếm, lọc và truy vết (trace) sự cố xuyên suốt nhiều service trong thời gian gần thực.
- Phát hiện bất thường và cảnh báo chủ động (proactive alerting) trước khi sự cố ảnh hưởng người dùng cuối.
- Đảm bảo an toàn dữ liệu nhạy cảm (data masking) và kiểm soát truy cập theo vai trò (RBAC).
- Cung cấp bằng chứng vận hành cấp doanh nghiệp: audit log, retention policy, khả năng chịu lỗi có kiểm chứng qua load test/chaos test.

## **1.3. Phạm vi (Scope)**

Trong phạm vi của phiên bản này, hệ thống bao gồm:

- Thu thập log qua REST API (đơn lẻ và theo lô).
- Chuẩn hóa, làm giàu (enrichment) và che dữ liệu nhạy cảm (data masking).
- Xử lý luồng theo thời gian thực bằng Kafka/Kafka Streams (windowing, phát hiện pattern lỗi).
- Lưu trữ, lập chỉ mục và tìm kiếm log trên Elasticsearch với chính sách vòng đời dữ liệu (ILM).
- Quản lý cảnh báo: định nghĩa rule, vòng đời alert, chống trùng lặp (deduplication/suppression).
- Xác thực – phân quyền (Authentication/RBAC), quản lý Service Registry, Audit Log.
- Dashboard thời gian thực qua WebSocket và tích hợp gửi thông báo qua Slack/Telegram.
- Giám sát chính nền tảng (self-monitoring): Kafka lag, tỉ lệ lỗi indexing, tình trạng Redis, API latency.

## **1.4. Ngoài phạm vi (Out of Scope)**

- Triển khai hạ tầng production đa vùng (multi-region) với hàng chục server thật — bản thiết kế chỉ yêu cầu kiến trúc, requirement, failure model và benchmark được thiết kế như production, còn triển khai thực tế có thể ở quy mô single-node/local Docker.
- Tự phát triển công cụ trực quan hóa thay thế Kibana — module Dashboard chỉ cung cấp các view thời gian thực cốt lõi (metrics, error chart, service health), không thay thế toàn bộ tính năng của Kibana.
- Machine Learning cho phát hiện bất thường (anomaly detection) nâng cao — phiên bản này chỉ dùng rule-based detection (ngưỡng, pattern từ khóa); ML-based detection được ghi nhận ở mục 20 (Future Scalability).
- Chuẩn hóa log cho các ngôn ngữ lập trình khác ngoài phạm vi HTTP REST ingestion (ví dụ agent thu thập log gắn trực tiếp vào hệ điều hành).

# **2\. Các bên liên quan & Actor (Stakeholders & Actors)**

SRS phiên bản 1.0 mô tả khá kỹ cách hệ thống nhận log nhưng chưa trả lời rõ "ai sử dụng hệ thống" — đây là khoảng trống lớn nhất giữa một Technical Specification và một Business Requirement Specification thực thụ. Phiên bản này bổ sung đầy đủ 4 actor sau.

## **2.1. Actor 1 — System Administrator (Admin)**

Quản lý toàn bộ cấu hình nền tảng.

- Quản lý users & roles (RBAC)
- Quản lý Service Registry (đăng ký/khóa service, cấp API key)
- Quản lý Alert Rule (tạo/sửa/xóa/bật-tắt)
- Cấu hình Retention Policy
- Xem Audit Log toàn hệ thống
- Cấu hình system-level settings (rate limit, ngưỡng cảnh báo mặc định)

## **2.2. Actor 2 — Developer**

- Tìm kiếm và lọc log (search/filter logs)
- Xem trace theo trace_id xuyên suốt nhiều service
- Xem chi tiết lỗi (error detail) và service health
- Xem danh sách alert liên quan đến service của mình
- Acknowledge (ghi nhận) alert được gán cho mình

## **2.3. Actor 3 — DevOps/SRE**

- Giám sát toàn hệ thống (platform monitoring): Kafka lag, throughput, tỉ lệ lỗi indexing
- Xem error rate, throughput theo service/environment
- Quản lý Alert Rule ở mức vận hành
- Xử lý incident: acknowledge → resolve alert theo vòng đời
- Theo dõi Service Health và DLQ (retry/discard sự kiện lỗi)

## **2.4. Actor 4 — Microservice (Log/Event Producer)**

Đây không phải human actor mà là hệ thống nguồn (service producer), gửi log vào nền tảng thông qua API Key đã được cấp qua Service Registry. Mọi request ingest phải được xác thực bằng API Key hợp lệ trước khi được đẩy vào Kafka.

## **2.5. Bảng phân quyền tổng quan theo Actor**

| **Nhóm chức năng**        | **Admin** | **Developer** | **DevOps/SRE** | **Microservice** |
| ------------------------- | --------- | ------------- | -------------- | ---------------- |
| Search/View Logs          | ✔         | ✔             | ✔              | —                |
| View Alerts               | ✔         | ✔ (của mình)  | ✔              | —                |
| Acknowledge/Resolve Alert | ✔         | ✔ (được gán)  | ✔              | —                |
| Create/Update Alert Rule  | ✔         | ✘             | ✔              | —                |
| Manage Service Registry   | ✔         | ✘             | ✘              | —                |
| System Configuration      | ✔         | ✘             | ✘              | —                |
| View Audit Log            | ✔         | ✘             | ✔ (giới hạn)   | —                |
| Gửi Log Event (Ingestion) | —         | —             | —              | ✔ (qua API Key)  |

# **3\. Tổng quan hệ thống (System Overview)**

Enterprise Log Aggregation & Real-time Observability Platform là một nền tảng event-driven, thiết kế theo hướng microservices-ready, có nhiệm vụ:

- Thu thập (Ingest) log ở lưu lượng lớn với độ trễ thấp.
- Chuẩn hóa, làm giàu và bảo vệ dữ liệu nhạy cảm trước khi lưu trữ.
- Xử lý luồng thời gian thực để phát hiện bất thường và sinh cảnh báo.
- Lưu trữ có chính sách vòng đời và cung cấp khả năng tìm kiếm/truy vết nhanh.
- Hiển thị trạng thái hệ thống theo thời gian thực và thông báo qua nhiều kênh.
- Tự giám sát chính nó (self-observability) để đảm bảo vận hành ổn định.

Điểm khác biệt của phiên bản 2.0 so với 1.0: bổ sung đầy đủ lớp Business/Security (Actor, RBAC, Service Registry, Business Rules) bên cạnh lớp Technical đã có, biến tài liệu từ "Technical Specification" thành "System-Level Product Specification" hoàn chỉnh.

# **4\. Yêu cầu chức năng (Functional Requirements)**

## **4.1. Authentication & Authorization**

| **Mã**     | **Yêu cầu**                     | **Actor**                | **Mô tả**                                                                                                  |
| ---------- | ------------------------------- | ------------------------ | ---------------------------------------------------------------------------------------------------------- |
| FR-AUTH-01 | Đăng nhập                       | Admin, Developer, DevOps | Người dùng đăng nhập bằng username/password, hệ thống trả về JWT Access Token (và Refresh Token).          |
| FR-AUTH-02 | Xác thực API Key                | Microservice             | Mọi request ingestion phải kèm API Key hợp lệ được cấp qua Service Registry; request không hợp lệ trả 401. |
| FR-AUTH-03 | Kiểm soát truy cập theo vai trò | Tất cả                   | Mỗi API endpoint được gắn với một hoặc nhiều role (RBAC); truy cập trái phép trả 403.                      |

## **4.2. Service Management (Service Registry)**

| **Mã**    | **Yêu cầu**                | **Actor**     | **Mô tả**                                                                                                                                                                                      |
| --------- | -------------------------- | ------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-SVC-01 | Đăng ký service            | Admin         | Admin đăng ký service mới (name, team, environment); hệ thống sinh API Key, hiển thị một lần duy nhất cho Admin, sau đó chỉ lưu apiKeyHash (không lưu API Key dạng plain-text — xem mục 13.3). |
| FR-SVC-02 | Quản lý trạng thái service | Admin         | Admin có thể tạm khóa (disable)/kích hoạt lại một service; service bị khóa không thể gửi log.                                                                                                  |
| FR-SVC-03 | Danh sách service          | Admin, DevOps | Xem danh sách toàn bộ service đã đăng ký kèm trạng thái health hiện tại.                                                                                                                       |

## **4.3. Log Ingestion**

| **Mã**    | **Yêu cầu**             | **Actor**    | **Mô tả**                                                                                                       |
| --------- | ----------------------- | ------------ | --------------------------------------------------------------------------------------------------------------- |
| FR-ING-01 | Nhận log đơn lẻ         | Microservice | REST API POST /api/v1/telemetry/logs nhận một log event, xác thực API Key trước khi xử lý.                      |
| FR-ING-02 | Nhận log theo lô        | Microservice | POST /api/v1/telemetry/logs/batch nhận nhiều log event trong một request để tối ưu throughput.                  |
| FR-ING-03 | Xác nhận ghi nhận (ACK) | Microservice | Hệ thống chỉ trả HTTP 202 Accepted sau khi Kafka đã acknowledge message; nếu chưa, trả lỗi 5xx để client retry. |

## **4.4. Log Processing**

| **Mã**     | **Yêu cầu**                  | **Actor** | **Mô tả**                                                                                                                                                                                   |
| ---------- | ---------------------------- | --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-PROC-01 | Chuẩn hóa & làm giàu dữ liệu | Hệ thống  | Tự động trích xuất timestamp, service_name, environment, log_level, trace_id, span_id theo canonical event schema (mục 7).                                                                  |
| FR-PROC-02 | Data Masking                 | Hệ thống  | Quét bằng regex để che thông tin nhạy cảm (thẻ tín dụng, mật khẩu, token) trước khi ghi nhận, thay giá trị bằng \[REDACTED\].                                                               |
| FR-PROC-03 | Idempotency xử lý sự kiện    | Hệ thống  | Mỗi log event có eventId duy nhất; Elasticsearch index theo \_id = eventId (hoặc cơ chế deduplication tương đương) để tránh document trùng lặp khi Kafka gửi lại theo cơ chế at-least-once. |
| FR-PROC-04 | Retry & Dead Letter Queue    | Hệ thống  | Log lỗi (invalid schema, corrupted data, mapping error) được retry theo số lần giới hạn; nếu vẫn thất bại, chuyển vào topic logs.dlq để Admin xem/retry/discard thủ công.                   |

## **4.5. Log Search**

| **Mã**     | **Yêu cầu**        | **Actor**                | **Mô tả**                                                                                                                    |
| ---------- | ------------------ | ------------------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| FR-SRCH-01 | Tìm kiếm full-text | Developer, DevOps, Admin | Tìm log theo từ khóa (keyword) trong message.                                                                                |
| FR-SRCH-02 | Lọc nhiều tiêu chí | Developer, DevOps, Admin | Lọc log theo service, environment, log_level, khoảng thời gian, trace_id, span_id, host, status_code, http_method, endpoint. |

## **4.6. Trace Correlation**

| **Mã**    | **Yêu cầu**            | **Actor**         | **Mô tả**                                                                                                                 |
| --------- | ---------------------- | ----------------- | ------------------------------------------------------------------------------------------------------------------------- |
| FR-TRC-01 | Gom vết log            | Developer, DevOps | Lọc và nhóm toàn bộ log event có cùng trace_id.                                                                           |
| FR-TRC-02 | Timeline xuyên dịch vụ | Developer, DevOps | Trả về danh sách log theo đúng thứ tự thời gian xuất hiện xuyên suốt nhiều microservices để tái hiện luồng giao dịch lỗi. |

## **4.7. Alert Management**

| **Mã**    | **Yêu cầu**                   | **Actor**         | **Mô tả**                                                                                                                                                                                                                                |
| --------- | ----------------------------- | ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-ALT-01 | Windowing & Pattern Detection | Hệ thống          | Rule 1 (Spike Threshold): Tumbling Window 1 phút, ERROR > ngưỡng cấu hình/phút → Critical Alert. Rule 2 (Pattern Error): phát hiện từ khóa nguy hiểm (OutOfMemoryError, DatabaseConnectionRefused) → cảnh báo tức thì, không qua window. |
| FR-ALT-02 | Alert Rule Management         | Admin, DevOps     | Tạo/sửa/bật-tắt/xóa Alert Rule (service, environment, condition, threshold, window, severity, notificationChannels) thay vì hard-code ngưỡng trong code.                                                                                 |
| FR-ALT-03 | Alert Lifecycle               | Developer, DevOps | Alert đi qua vòng đời TRIGGERED → OPEN → ACKNOWLEDGED → RESOLVED; hệ thống lưu triggeredAt, acknowledgedAt, resolvedAt, occurrenceCount.                                                                                                 |
| FR-ALT-04 | Suppression & Deduplication   | Hệ thống          | Khi một alert được kích hoạt cho một service, hệ thống ghi Alert-Lock vào Redis với TTL 5 phút; trong thời gian này lỗi tương tự chỉ tăng counter, không gửi thêm thông báo.                                                             |

## **4.8. Notification**

| **Mã**     | **Yêu cầu**                | **Actor** | **Mô tả**                                                                                                        |
| ---------- | -------------------------- | --------- | ---------------------------------------------------------------------------------------------------------------- |
| FR-NOTI-01 | Gửi cảnh báo đa kênh       | Hệ thống  | Tích hợp Slack Webhook và Telegram Bot để gửi cảnh báo.                                                          |
| FR-NOTI-02 | Đẩy dữ liệu thời gian thực | Hệ thống  | Tích hợp WebSocket/STOMP để đẩy metric (log/giây, biểu đồ lỗi, alert mới) lên Dashboard không cần refresh trang. |

## **4.9. Dashboard**

| **Mã**     | **Yêu cầu**                         | **Actor**                | **Mô tả**                                                                                                                                 |
| ---------- | ----------------------------------- | ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------- |
| FR-DASH-01 | Chỉ số tổng quan                    | Developer, DevOps, Admin | Hiển thị Logs/sec, Error Rate, số Alert đang mở theo thời gian thực.                                                                      |
| FR-DASH-02 | Biểu đồ Log Throughput & Error Rate | Developer, DevOps, Admin | Biểu đồ dạng time-series cho throughput và tỉ lệ lỗi.                                                                                     |
| FR-DASH-03 | Service Health View                 | Developer, DevOps, Admin | Hiển thị trạng thái từng service (HEALTHY/DEGRADED/UNAVAILABLE), tính toán theo ngưỡng xác định tại mục 4.9.1 (không suy diễn định tính). |

### **4.9.1. Ngưỡng xác định Service Health (deterministic)**

Sửa đổi so với v2.0: trạng thái Service Health trước đây chỉ nêu định tính ("dựa trên last log time và error rate"), chưa có ngưỡng cụ thể nên không thể tính toán deterministic. Bảng dưới đây định nghĩa rõ điều kiện; các mốc số là giá trị khuyến nghị mặc định, có thể cấu hình lại theo từng service.

| **Trạng thái** | **Điều kiện**                                                                 |
| -------------- | ----------------------------------------------------------------------------- |
| HEALTHY        | lastSeen < 30 giây VÀ errorRate < 5%                                          |
| DEGRADED       | lastSeen < 2 phút HOẶC errorRate ≥ 5% (và chưa rơi vào điều kiện UNAVAILABLE) |
| UNAVAILABLE    | lastSeen ≥ 2 phút                                                             |

Thứ tự đánh giá: kiểm tra UNAVAILABLE trước (ưu tiên cao nhất), sau đó DEGRADED, còn lại là HEALTHY — đảm bảo mỗi service tại một thời điểm chỉ có đúng một trạng thái xác định (không mập mờ).

## **4.10. Data Retention**

| **Mã**    | **Yêu cầu**                     | **Actor** | **Mô tả**                                                                                                 |
| --------- | ------------------------------- | --------- | --------------------------------------------------------------------------------------------------------- |
| FR-RET-01 | Chính sách vòng đời index (ILM) | Hệ thống  | Tự động chuyển index qua các pha HOT → WARM → COLD → DELETE theo chính sách cấu hình (chi tiết mục 12.3). |

## **4.11. Audit**

| **Mã**    | **Yêu cầu**        | **Actor**                | **Mô tả**                                                                                                                                                              |
| --------- | ------------------ | ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-AUD-01 | Ghi nhận audit log | Hệ thống                 | Mọi thao tác thay đổi cấu hình quan trọng (tạo/sửa Alert Rule, thay đổi Service Registry, thay đổi quyền) được ghi lại: user, action, resource, timestamp, ip, result. |
| FR-AUD-02 | Tra cứu audit log  | Admin, DevOps (giới hạn) | Cho phép tra cứu lịch sử thao tác theo user, resource, khoảng thời gian.                                                                                               |

## **4.12. Acceptance Criteria (tiêu chí chấp nhận)**

Bổ sung so với v2.0: đây là mảnh ghép còn thiếu để một Functional Requirement có thể kiểm thử được (testable), viết theo cú pháp Given/When/Then cho các FR trọng yếu nhất. Các FR còn lại áp dụng cùng nguyên tắc khi chuyển sang giai đoạn System Design/Test Case chi tiết.

### **FR-ING-01 — Nhận log đơn lẻ**

```
Valid case:
  Given valid API Key
  And valid LogEvent (đủ trường bắt buộc theo BR-001)
  When POST /api/v1/telemetry/logs
  Then HTTP 202 Accepted
  And event xuất hiện trong Kafka topic raw-logs
  And eventId là duy nhất

Invalid case:
  Given API Key không hợp lệ
  When POST /api/v1/telemetry/logs
  Then HTTP 401 Unauthorized
  And không có message nào được đẩy vào Kafka
```

### **FR-ALT-01 / FR-ALT-02 — Kích hoạt Alert theo Rule**

```
Given số lượng log ERROR của một service > threshold đã cấu hình trong AlertRule
When window (Tumbling Window) đóng lại
Then một Alert mới được tạo với status = TRIGGERED
And topic system-alerts nhận được AlertEvent tương ứng
And notification được dispatch tới các notificationChannels cấu hình (BR-004)
```

### **Failure Scenario — Elasticsearch gián đoạn**

```
Given Elasticsearch không khả dụng
When log hợp lệ tiếp tục được gửi đến hệ thống
Then Ingestion API vẫn nhận log bình thường (trả 202)
And Kafka giữ lại (buffer) toàn bộ event trong raw-logs

Given Elasticsearch phục hồi hoạt động trở lại
Then Indexer Worker tiếp tục xử lý (resume) từ vị trí Kafka offset đã lưu
And toàn bộ event hợp lệ được index đầy đủ, không mất bản ghi (NFR-REL-01)
```

# **5\. Quy tắc nghiệp vụ (Business Rules)**

Đây là phần trước đây SRS v1.0 chưa có — bổ sung để tài liệu thực sự trở thành business/system specification, không chỉ là mô tả kỹ thuật.

| **Mã** | **Quy tắc**                                                                                                                                                                                                                             |
| ------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| BR-001 | Một log event hợp lệ bắt buộc phải có: timestamp, serviceName, environment, level, message.                                                                                                                                             |
| BR-002 | Service phải được đăng ký (Service Registry) và có API Key hợp lệ trước khi được phép gửi log.                                                                                                                                          |
| BR-003 | Log ở environment production không được phép chứa dữ liệu nhạy cảm dạng plain-text: password, access_token, credit_card — bắt buộc phải qua Data Masking trước khi lưu.                                                                 |
| BR-004 | Notification latency P95 < 1 giây, đo từ thời điểm alert được tạo (AlertEvent.triggeredAt, mục 7.2) đến thời điểm notification được dispatch — điểm bắt đầu và kết thúc phép đo phải rõ ràng, không tính mốc "< 1 giây" một cách mơ hồ. |
| BR-005 | Một service/user không được vượt quá ngưỡng rate limit cấu hình theo thuật toán Token Bucket (mục 13.4); vượt ngưỡng bị từ chối với HTTP 429.                                                                                           |
| BR-006 | Một alert tương tự (cùng rule, cùng service) không được gửi lại notification trong vòng 5 phút kể từ lần gửi gần nhất (suppression window).                                                                                             |
| BR-007 | Một log event đã được xử lý thành công không được tạo document trùng lặp trong Elasticsearch (đảm bảo idempotency theo eventId).                                                                                                        |
| BR-008 | Log event không đạt canonical schema hoặc lỗi xử lý sau số lần retry cấu hình sẵn phải được chuyển vào Dead Letter Queue, không được retry vô hạn.                                                                                      |
| BR-009 | Mọi thao tác thay đổi cấu hình hệ thống (Alert Rule, Service Registry, phân quyền) phải được ghi Audit Log, không có ngoại lệ.                                                                                                          |

# **6\. Yêu cầu phi chức năng (Non-Functional Requirements)**

## **6.1. Hiệu năng (Performance)**

SRS v1.0 nêu các mốc hiệu năng (throughput, latency) nhưng thiếu định nghĩa đo lường rõ ràng (percentile, dataset, điều kiện đo). Phiên bản này chuẩn hóa lại thành NFR đo lường được (measurable NFR):

| **Chỉ số**                     | **Mục tiêu**                          | **Điều kiện đo**                                                                                                                              |
| ------------------------------ | ------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| Ingestion capacity             | ≥ 10,000 logs/giây (TPS)              | Trên phần cứng tiêu chuẩn tham chiếu, batch ingestion bật                                                                                     |
| Độ trễ API Ingestion           | P50 < 5ms, P95 < 10ms, P99 < 30ms     | Cơ chế bất đồng bộ (asynchronous/non-blocking)                                                                                                |
| Search latency (Elasticsearch) | P50 < 100ms, P95 < 200ms, P99 < 500ms | Dataset 10 triệu bản ghi; truy vấn kết hợp service + level + timestamp + keyword; xác định rõ số lượng concurrent users khi benchmark thực tế |

Đây là dạng NFR có thể kiểm chứng bằng benchmark thực tế (chi tiết mục 17), thay vì công bố con số chưa đo được.

## **6.2. Khả năng mở rộng (Scalability)**

- Kiến trúc cho phép mở rộng theo chiều ngang (horizontal scaling) ở lớp Ingestion Service, Kafka Consumer Group và Elasticsearch cluster.
- Kafka partition được thiết kế để tăng số consumer instance mà không cần thay đổi logic nghiệp vụ (xem mục 10.2).

## **6.3. Độ tin cậy & Khả năng chịu lỗi (Resilience & Reliability)**

Yêu cầu "Zero Data Loss" trong bản v1.0 cần được viết lại chính xác hơn — trong hệ thống phân tán thực tế, không nên tuyên bố tuyệt đối. Yêu cầu được định nghĩa lại như sau:

**NFR-REL-01 — No data loss after successful ingestion acknowledgment:** Hệ thống đảm bảo không mất dữ liệu sau khi Kafka đã acknowledge message (broker ACK), trong phạm vi các failure scenario được hỗ trợ (mục 15). Client chỉ nhận HTTP 202 Accepted sau khi có ACK từ Kafka; nếu chưa có ACK, hệ thống trả 5xx để client retry.

- Backpressure: khi Elasticsearch quá tải hoặc gián đoạn, Kafka giữ vai trò buffer hứng log, đảm bảo Ingestion API không bị chặn.
- Self-healing: khi một Ingestion Service instance crash, Consumer Group trong Kafka tự động rebalance để tiếp tục xử lý.
- Idempotency: xử lý downstream theo mô hình at-least-once delivery kết hợp deduplication theo eventId, đảm bảo không tạo document trùng lặp.
- Retry & DLQ: log lỗi được retry có giới hạn, sau đó chuyển vào Dead Letter Queue thay vì retry vô hạn hoặc bị loại bỏ âm thầm.

## **6.4. Tối ưu hóa tài nguyên (Resource Efficiency)**

Mô tả "giảm 80% RAM Thread Allocation" trong v1.0 là một con số chưa có benchmark chứng minh và dễ bị chất vấn khi phỏng vấn/đánh giá. Yêu cầu được viết lại theo hướng định tính, kèm kế hoạch benchmark để đưa ra số liệu thực tế sau khi đo:

**NFR-RES-01:** Sử dụng Java 21 Virtual Threads để tăng khả năng xử lý các workload I/O-bound đồng thời và giảm chi phí quản lý platform threads so với mô hình thread truyền thống. Số liệu định lượng cụ thể (ví dụ: "giảm X% bộ nhớ sử dụng dưới Y kết nối đồng thời") chỉ được công bố sau khi có kết quả benchmark thực tế Platform Threads vs Virtual Threads (xem mục 17.2).

## **6.5. Bảo mật (Security)**

Data Masking không đồng nghĩa với Security — đây là điểm yếu lớn nhất của SRS v1.0. Phiên bản này bổ sung đầy đủ lớp bảo mật:

- Authentication: người dùng đăng nhập, hệ thống cấp JWT Access Token (kèm Refresh Token, thời hạn xác định).
- Authorization (RBAC): 4 vai trò ADMIN, DEVOPS, DEVELOPER, VIEWER với ma trận quyền theo API (chi tiết mục 13.2).
- API Key cho Service Producer: mỗi service có API Key riêng, thu hồi/khóa được, không dùng chung.
- Rate Limiting: giới hạn số request/giây theo service hoặc theo user để chống lạm dụng và bảo vệ hệ thống.
- Data Masking: che thông tin nhạy cảm bằng regex pattern (thẻ tín dụng, mật khẩu, token) trước khi lưu trữ.
- Audit Log: ghi nhận đầy đủ hành động thay đổi cấu hình nhạy cảm.

## **6.6. Khả năng quan sát (Observability)**

Một hệ thống quan sát (observability platform) nhưng không tự quan sát chính mình là một nghịch lý cần tránh. Nền tảng phải giám sát chính nó: Kafka Consumer Lag, Kafka Throughput, tỉ lệ index thành công/thất bại của Elasticsearch, tình trạng Redis, API latency/error rate, JVM Memory & CPU (chi tiết mục 19).

## **6.7. Khả năng bảo trì (Maintainability)**

- Canonical Event Schema thống nhất giúp giảm chi phí bảo trì khi thêm service mới.
- Alert Rule được cấu hình qua dữ liệu (data-driven), không hard-code điều kiện trong mã nguồn, giúp thay đổi ngưỡng cảnh báo mà không cần deploy lại.

# **7\. Đặc tả sự kiện (Event Specification)**

SRS v1.0 chỉ liệt kê rời rạc các trường dữ liệu. Phiên bản này chuẩn hóa thành Canonical Event Schema — yếu tố quan trọng để đảm bảo tính nhất quán trên toàn bộ Kafka pipeline.

## **7.1. LogEvent**

```
{
  "eventId": "uuid-v4",
  "timestamp": "2026-08-31T10:15:32.123Z",
  "serviceName": "payment-service",
  "environment": "production",
  "level": "ERROR",
  "message": "Database connection refused",
  "traceId": "abc123",
  "spanId": "span-456",
  "host": "ip-10-0-1-25",
  "instanceId": "payment-service-7d9",
  "logger": "com.company.payment.PaymentService",
  "httpMethod": "POST",
  "endpoint": "/api/payment",
  "statusCode": 500,
  "durationMs": 245,
  "metadata": {}
}
```

eventId là khóa idempotency: Elasticsearch lập chỉ mục theo \_id = eventId (hoặc cơ chế deduplication tương đương) để đảm bảo BR-007.

## **7.2. AlertEvent**

```
{
  "alertId": "uuid-v4",
  "ruleId": "rule-001",
  "serviceId": "payment-service",
  "environment": "production",
  "severity": "CRITICAL",
  "status": "TRIGGERED",
  "condition": "ERROR_COUNT > 100",
  "windowSeconds": 60,
  "occurrenceCount": 1,
  "triggeredAt": "2026-08-31T10:16:00Z",
  "acknowledgedAt": null,
  "resolvedAt": null,
  "notificationChannels": ["slack", "websocket"]
}
```

## **7.3. ServiceEvent**

```
{
  "serviceId": "payment-service",
  "status": "HEALTHY",
  "lastSeenAt": "2026-08-31T10:20:00Z",
  "errorRatePercent": 3.2,
  "logsPerSecond": 450,
  "environment": "production"
}
```

# **8\. Đặc tả API (API Specification)**

SRS v1.0 chưa có phần đặc tả API — đây là một trong những khoảng trống lớn cần bổ sung. Danh sách dưới đây liệt kê các endpoint chính; mỗi endpoint tuân theo khuôn: Purpose, Actor, Request, Response, Validation, Authentication, Authorization, Error Codes, Business Rules liên quan.

| **Method** | **Endpoint**                    | **Mục đích**                    | **Actor / Quyền**                       |
| ---------- | ------------------------------- | ------------------------------- | --------------------------------------- |
| POST       | /api/v1/auth/login              | Đăng nhập, trả JWT              | Tất cả user nội bộ                      |
| POST       | /api/v1/telemetry/logs          | Nhận 1 log event                | Microservice (API Key) — BR-001, BR-002 |
| POST       | /api/v1/telemetry/logs/batch    | Nhận log theo lô                | Microservice (API Key)                  |
| GET        | /api/v1/logs                    | Tìm kiếm/lọc log                | VIEWER trở lên                          |
| GET        | /api/v1/logs/{id}               | Xem chi tiết 1 log              | VIEWER trở lên                          |
| GET        | /api/v1/traces/{traceId}        | Timeline log theo trace_id      | VIEWER trở lên                          |
| GET        | /api/v1/alerts                  | Danh sách alert                 | VIEWER trở lên                          |
| GET        | /api/v1/alerts/{id}             | Chi tiết alert                  | VIEWER trở lên                          |
| POST       | /api/v1/alerts/{id}/acknowledge | Ghi nhận alert (ACK)            | DEVELOPER trở lên                       |
| POST       | /api/v1/alerts/{id}/resolve     | Đóng alert (RESOLVE)            | DEVOPS trở lên                          |
| GET        | /api/v1/services                | Danh sách service đã đăng ký    | DEVOPS trở lên                          |
| POST       | /api/v1/services                | Đăng ký service mới             | ADMIN                                   |
| GET        | /api/v1/alert-rules             | Danh sách alert rule            | DEVOPS trở lên                          |
| POST       | /api/v1/alert-rules             | Tạo alert rule                  | ADMIN, DEVOPS                           |
| PUT        | /api/v1/alert-rules/{id}        | Cập nhật alert rule             | ADMIN, DEVOPS                           |
| GET        | /api/v1/dashboard/metrics       | Chỉ số dashboard thời gian thực | VIEWER trở lên                          |
| GET        | /api/v1/audit-logs              | Tra cứu audit log               | ADMIN, DEVOPS (giới hạn)                |

Quy ước mã lỗi chung: 400 (dữ liệu không hợp lệ), 401 (chưa xác thực/API Key sai), 403 (không đủ quyền theo RBAC), 404 (không tìm thấy tài nguyên), 409 (xung đột — ví dụ eventId trùng), 429 (vượt rate limit — BR-005), 5xx (lỗi hệ thống, client cần retry theo BR liên quan).

# **9\. Mô hình dữ liệu (Data Model)**

## **9.1. Ranh giới lưu trữ dữ liệu (Storage Boundary)**

Một điểm thiếu nhất quán ở bản v2.0 là gộp chung LogEvent (dữ liệu khối lượng lớn, ghi trên Elasticsearch) với các thực thể nghiệp vụ có quan hệ (operational metadata, phù hợp RDBMS) trong cùng một sơ đồ ERD. Phiên bản 2.1 tách rõ theo nguyên tắc: dữ liệu vận hành/nghiệp vụ có quan hệ chặt (User, Role, Service, AlertRule, Alert, AuditLog) → RDBMS; dữ liệu log khối lượng lớn, chủ yếu ghi-và-tìm-kiếm (LogEvent) → Elasticsearch; dữ liệu tạm thời/cache/khóa → Redis.

```
PostgreSQL / MySQL (RDBMS)
├── User
├── Role
├── UserRole      (bảng trung gian N:N)
├── Service
├── AlertRule
├── Alert
└── AuditLog

Elasticsearch
└── LogEvent      (logs-YYYY.MM.DD, khối lượng lớn, full-text search)

Redis
├── RateLimit     (ratelimit:service:<serviceId>)
├── AlertLock     (alert:<serviceId>:<ruleId>, cooldown/suppression)
├── Cache         (dashboard metrics cache)
└── ServiceState  (online/last-seen status)
```

Lý do: operational metadata (ai được cấp quyền gì, service nào đã đăng ký, rule nào đang bật, alert nào đang mở) cần tính toàn vẹn quan hệ (referential integrity) và transaction — phù hợp RDBMS. Log event có khối lượng ghi rất lớn và chủ yếu phục vụ tìm kiếm/lọc theo thời gian thực — phù hợp Elasticsearch hơn RDBMS.

## **9.2. Quan hệ thực thể (RDBMS)**

- User (n) — (n) Role, thông qua bảng trung gian UserRole (user_id, role_id): một user có thể có nhiều role, một role gán cho nhiều user.
- Service (1) — (n) AlertRule: một service có thể có nhiều alert rule áp dụng.
- AlertRule (1) — (n) Alert: một rule có thể kích hoạt nhiều lần theo thời gian, mỗi lần sinh một bản ghi Alert.
- Alert (n) — (1) Service: mỗi alert gắn với một service cụ thể.
- User (1) — (n) AuditLog: mỗi hành động của user sinh ra một bản ghi audit.
- LogEvent không có quan hệ khóa ngoại với các thực thể RDBMS ở tầng cơ sở dữ liệu — liên kết với Service chỉ qua giá trị serviceName/serviceId ở tầng ứng dụng (logical reference), vì LogEvent nằm trên Elasticsearch.

## **9.3. Thực thể chính (RDBMS)**

| **Thực thể** | **Thuộc tính chính**                                                                                                        |
| ------------ | --------------------------------------------------------------------------------------------------------------------------- |
| User         | id, username, passwordHash, createdAt (không còn thuộc tính role đơn — vai trò được gán qua UserRole, xem mục 9.2)          |
| Role         | id, name (ADMIN/DEVOPS/DEVELOPER/VIEWER), description                                                                       |
| UserRole     | userId, roleId (bảng trung gian, khóa chính kép)                                                                            |
| Service      | id, name, description, team, environment, status, apiKeyHash, createdAt (không lưu apiKey plain-text — xem mục 13.3)        |
| AlertRule    | id, name, service, environment, condition, threshold, window, severity, enabled, notificationChannels, createdBy, createdAt |
| Alert        | id, ruleId, serviceId, severity, status, triggeredAt, acknowledgedAt, resolvedAt, occurrenceCount, lastOccurrenceAt         |
| AuditLog     | id, user, action, resource, timestamp, ip, result                                                                           |

## **9.4. Elasticsearch Index Mapping (tóm tắt — xem chi tiết Index Template ở mục 12.2)**

```
logs-YYYY.MM.DD
  eventId:      keyword (dùng làm _id — đảm bảo idempotency, BR-007)
  timestamp:    date
  serviceName:  keyword
  environment:  keyword
  level:        keyword
  message:      text (full-text search)
  traceId:      keyword
  spanId:       keyword
  host:         keyword
  statusCode:   integer
  durationMs:   integer
```

# **10\. Thiết kế Kafka (Kafka Design)**

## **10.1. Topics**

| **Topic**     | **Mục đích**                                                   |
| ------------- | -------------------------------------------------------------- |
| raw-logs      | Log thô sau khi Ingestion Service nhận và validate cơ bản      |
| system-alerts | Sự kiện alert được sinh ra từ Kafka Streams Engine             |
| logs.dlq      | Log lỗi sau khi vượt số lần retry cho phép (Dead Letter Queue) |
| logs.retry    | Log đang trong vòng retry trước khi rơi vào DLQ                |

## **10.2. Partitions & Partitioning Strategy**

SRS v2.0 mới chỉ nêu partition cho phép scale consumer nhưng chưa xác định partition key — đây là một câu hỏi kiến trúc quan trọng cần chốt rõ, không để ngỏ.

**Quyết định: partition key = serviceId.** Ví dụ: payment-service → partition 0, order-service → partition 1, auth-service → partition 2 (theo hash(serviceId) mod số partition).

| **Chiến lược**                               | **Ưu điểm**                                                                                                              | **Đánh đổi (Trade-off)**                                                                                      |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------- |
| partition key = serviceId (được chọn)        | Log của cùng một service được xử lý theo đúng thứ tự tương đối trong cùng một partition; dễ scale theo số lượng service  | Một service có traffic rất lớn có thể gây lệch tải (hot partition) nếu không tăng partition hoặc dùng sub-key |
| partition key = traceId (phương án thay thế) | Toàn bộ log cùng một trace được đảm bảo thứ tự tuyệt đối trong cùng partition, thuận lợi cho Trace Correlation (mục 4.6) | Log của cùng một service bị phân tán ngẫu nhiên qua nhiều partition, khó tối ưu theo service khi cần          |

Số lượng partition của raw-logs được cấu hình theo số consumer instance tối đa dự kiến ở Indexer Worker và Kafka Streams Engine, cho phép mở rộng song song mà không cần thay đổi logic nghiệp vụ.

## **10.3. Consumer Groups**

- indexer-group: đọc raw-logs, ghi vào Elasticsearch (Indexer Worker).
- analytics-group: đọc raw-logs, thực hiện windowing/pattern detection (Kafka Streams Engine).
- alert-consumer-group: đọc system-alerts để xử lý deduplication/suppression và gửi notification.

## **10.4. Retry**

Khi Indexer Worker xử lý một log event thất bại (lỗi tạm thời), event được đẩy vào logs.retry với backoff tăng dần theo số lần thử. Sau khi vượt số lần retry cấu hình sẵn, event được chuyển sang logs.dlq (BR-008).

## **10.5. Dead Letter Queue (DLQ)**

- Admin có thể xem danh sách Failed Events trong DLQ.
- Admin có thể Retry Event (đẩy lại vào raw-logs) hoặc Discard Event (loại bỏ vĩnh viễn, có ghi Audit Log).

# **11\. Thiết kế Redis (Redis Design)**

SRS v1.0 chỉ dùng Redis cho một use case (Alert-Lock TTL 5 phút). Phiên bản này mở rộng vai trò của Redis trong toàn hệ thống:

| **Use case**                   | **Ví dụ key**                                         |
| ------------------------------ | ----------------------------------------------------- |
| Alert Deduplication / Cooldown | alert:payment:database-down (TTL 5 phút)              |
| Rate Limiting                  | ratelimit:service:payment-service                     |
| Distributed Lock               | lock:indexer:partition-3                              |
| Dashboard Metrics Cache        | service:payment:error-rate, service:payment:last-seen |
| Online Service Status          | service:payment:status                                |

# **12\. Thiết kế Elasticsearch (Elasticsearch Design)**

## **12.1. Index Strategy**

Index được tạo theo ngày (logs-YYYY.MM.DD) để thuận tiện áp dụng chính sách vòng đời (ILM) và giới hạn kích thước từng index.

## **12.2. Index Template**

Bổ sung so với v2.0: thay vì tạo mapping thủ công cho từng index theo ngày, hệ thống định nghĩa một Index Template áp dụng tự động cho mọi index khớp pattern logs-\*, đảm bảo mọi index mới sinh ra đều có cùng mapping/settings/ILM policy mà không cần cấu hình lại.

```
Index Template: logs-template
  index_patterns: ["logs-*"]
  mappings:  (xem mục 9.4 — eventId dùng làm _id)
  settings:  number_of_shards, number_of_replicas
  ilm_policy: logs-ilm-policy   (HOT → WARM → COLD → DELETE, xem mục 12.3)
```

Trường message dùng kiểu text để hỗ trợ full-text search; các trường định danh (serviceName, environment, level, traceId…) dùng keyword để lọc chính xác và tăng tốc truy vấn.

## **12.3. Retention Policy (nâng cấp từ 3 pha lên 4 pha)**

Chính sách 3 pha của SRS v1.0 (HOT 0–3 ngày, WARM 4–14 ngày, DELETE > 14 ngày) là tư duy đúng cho hệ thống log vì log rất tốn dung lượng lưu trữ. Phiên bản 2.0 mở rộng kiến trúc để có khả năng thêm pha COLD, dù không bắt buộc phải triển khai toàn bộ ngay từ đầu:

| **Pha** | **Thời gian** | **Đặc điểm**                                                 |
| ------- | ------------- | ------------------------------------------------------------ |
| HOT     | 0–3 ngày      | Lưu trên SSD, ghi nhanh (high write), tìm kiếm tức thì       |
| WARM    | 4–30 ngày     | Nén dữ liệu, chuyển read-only, phục vụ tra cứu sự cố gần đây |
| COLD    | 31–90 ngày    | Lưu trữ chi phí thấp, phục vụ tra cứu ít thường xuyên        |
| DELETE  | \> 90 ngày    | Tự động xóa index để giải phóng dung lượng                   |

Ghi chú: mốc thời gian trên là cấu hình mặc định khuyến nghị; hệ thống cho phép Admin điều chỉnh theo chính sách lưu trữ thực tế của doanh nghiệp (FR-RET-01).

# **13\. Kiến trúc bảo mật (Security Architecture)**

## **13.1. Luồng xác thực (Authentication Flow)**

```
User → Login (username/password)
     → Auth Service xác thực
     → Phát hành JWT Access Token + Refresh Token
     → Client gửi Access Token trong header Authorization: Bearer <token>
       cho mọi request tiếp theo
```

## **13.2. Ma trận phân quyền RBAC**

| **API / Chức năng** | **ADMIN** | **DEVOPS**   | **DEVELOPER** | **VIEWER** |
| ------------------- | --------- | ------------ | ------------- | ---------- |
| Search Logs         | ✔         | ✔            | ✔             | ✔          |
| View Alerts         | ✔         | ✔            | ✔             | ✔          |
| Acknowledge Alert   | ✔         | ✔            | ✔             | ✘          |
| Resolve Alert       | ✔         | ✔            | ✘             | ✘          |
| Create Alert Rule   | ✔         | ✔            | ✘             | ✘          |
| Manage Services     | ✔         | ✘            | ✘             | ✘          |
| System Config       | ✔         | ✘            | ✘             | ✘          |
| View Audit Log      | ✔         | ✔ (giới hạn) | ✘             | ✘          |

## **13.3. Xác thực Service Producer & Quản lý API Key (apiKeyHash)**

Sửa đổi so với v2.0: API Key không được lưu ở dạng plain-text trong Service Registry — chỉ hiển thị một lần cho Admin tại thời điểm tạo, sau đó hệ thống chỉ lưu bản băm (apiKeyHash).

```
Tạo API Key (một lần):
  Generate API Key (random, đủ độ dài entropy)
       → Hiển thị 1 lần cho Admin (không thể xem lại sau đó)
       → Hash (ví dụ SHA-256/BCrypt)
       → Lưu apiKeyHash vào Service Registry

Xác thực khi ingest:
  Service (payment-service) → gửi API Key trong header
       → Ingestion Service hash API Key nhận được
       → So khớp (compare) với apiKeyHash trong Service Registry
       → Hợp lệ      → đẩy vào Kafka (raw-logs)
       → Không hợp lệ → trả 401 Unauthorized
```

## **13.4. Rate Limiting — thuật toán & tham số**

Sửa đổi so với v2.0: chốt rõ thuật toán thay vì chỉ nêu yêu cầu định tính. Thuật toán được chọn: Token Bucket (mỗi service/user có một bucket riêng, token được nạp lại theo tốc độ cố định, cho phép burst trong giới hạn) — đơn giản để triển khai với Redis (INCR + TTL hoặc Lua script), phù hợp quy mô dự án này.

| **Tham số**               | **Ví dụ giá trị**                                                             |
| ------------------------- | ----------------------------------------------------------------------------- |
| Đối tượng giới hạn        | Theo serviceId (API Key) cho ingestion; theo userId (JWT) cho các API còn lại |
| limit (tốc độ nạp token)  | 1000 request/giây (ví dụ cho một service)                                     |
| burst (dung lượng bucket) | 200 request                                                                   |
| Khi vượt ngưỡng           | HTTP 429 Too Many Requests (liên quan BR-005)                                 |
| Nơi lưu trạng thái        | Redis — key dạng ratelimit:service:&lt;serviceId&gt; (mục 11)                 |

# **14\. Kiến trúc hệ thống (System Architecture)**

Kiến trúc luồng dữ liệu tổng thể (nâng cấp từ workflow v1.0, bổ sung Auth, Service Registry, DLQ, Retry, Audit Log, Platform Monitoring):

```
[Microservices]
      │  API Key / JWT
      ▼
[Ingestion Service — Java 21 Virtual Threads]
      │  Validate / Mask / Assign eventId
      ▼
[Kafka: raw-logs] ──────────────┬───────────────────────┐
      │                          │                       │
      ▼                          ▼                       │
[Kafka Streams Engine]     [Indexer Worker] ──(fail)──► [logs.retry] ──(exceed)──► [logs.dlq]
 (Windowing / Pattern)           │
      │ (Error Spike)            ▼ (success)
      ▼                    [Elasticsearch] ──► [Rest Query / Search API]
[Kafka: system-alerts]
      │
      ▼
[Alert Engine] ──(Redis: Lock/Cooldown/RateLimit)
      │
      ├──► [Slack]
      ├──► [Telegram]
      └──► [WebSocket] ──► [Dashboard]

Song song:
  [Auth Service]  — cấp JWT, quản lý RBAC
  [Service Registry] — quản lý service & API Key
  [Audit Log Service] — ghi nhận thao tác cấu hình
  [Platform Monitoring] — Kafka lag, ES failure rate, Redis health, API latency
```

Ghi chú thiết kế (điểm 28 trong biên bản đánh giá): không nên chia hệ thống thành quá nhiều microservice siêu nhỏ chỉ để "khoe" số lượng service. Với mục tiêu portfolio Backend Junior/Intern, giá trị cốt lõi cần chứng minh là: high-throughput ingestion, event-driven architecture, xử lý tin cậy (reliable processing), xử lý lỗi phân tán (distributed failure handling), tìm kiếm, cảnh báo thời gian thực, bảo mật và kiểm thử — không phải số lượng service.

# **15\. Kịch bản sự cố (Failure Scenarios)**

| **Kịch bản (Scenario)**              | **Kết quả mong đợi (Expected)**                                       |
| ------------------------------------ | --------------------------------------------------------------------- |
| Elasticsearch ngừng hoạt động        | Kafka giữ vai trò buffer, log không bị mất (backpressure)             |
| Kafka broker restart                 | Consumer tự phục hồi và tiếp tục xử lý                                |
| Consumer crash                       | Consumer Group rebalance tự động                                      |
| Redis restart                        | Alert Engine phục hồi, cooldown/lock được tái tạo hoặc bỏ qua an toàn |
| Sự kiện trùng lặp (duplicate event)  | Không tạo document trùng trong Elasticsearch (idempotency)            |
| Sự kiện không hợp lệ (invalid event) | Chuyển vào Dead Letter Queue sau khi hết số lần retry                 |
| Traffic cao đột biến                 | P95 latency vẫn đạt mục tiêu đã cấu hình (mục 6.1)                    |
| WebSocket bị ngắt kết nối            | Client tự động kết nối lại (reconnect)                                |
| Alert lặp lại liên tục               | Bị suppression/deduplication theo BR-006                              |
| Gọi API không có quyền               | Trả về 401 (chưa xác thực) hoặc 403 (không đủ quyền)                  |

# **16\. Chiến lược kiểm thử (Testing Strategy)**

SRS v1.0 chỉ có Load Test và Chaos Test. Một System-Level Product cần bộ kiểm thử đầy đủ hơn:

| **Loại kiểm thử** | **Mục tiêu**                                                                                    |
| ----------------- | ----------------------------------------------------------------------------------------------- |
| Functional Test   | Xác nhận từng FR trong mục 4 hoạt động đúng theo mô tả                                          |
| Integration Test  | Xác nhận luồng dữ liệu xuyên suốt Ingestion → Kafka → Indexer → Elasticsearch                   |
| Load Test         | Xác nhận hệ thống đáp ứng NFR hiệu năng dưới tải chuẩn                                          |
| Stress Test       | Xác định ngưỡng chịu tải tối đa trước khi hệ thống suy giảm                                     |
| Soak Test         | Xác nhận hệ thống ổn định khi chạy liên tục trong thời gian dài (rò rỉ bộ nhớ, leak connection) |
| Chaos Test        | Xác nhận khả năng chịu lỗi khi một thành phần trong hệ thống gặp sự cố                          |
| Recovery Test     | Xác nhận hệ thống phục hồi đúng và đầy đủ dữ liệu sau sự cố                                     |
| Security Test     | Xác nhận RBAC, xác thực API Key, rate limiting hoạt động đúng, chống truy cập trái phép         |

## **16.1. Kịch bản Load Test (JMeter/Locust)**

- Giả lập 500 Virtual Users đồng thời gửi 1,000 request/giây trong 10 phút.
- Tiêu chí đạt: hệ thống không trả lỗi 5xx; CPU/RAM của Ingestion Service ổn định; Kafka không tồn đọng (lag) vượt ngưỡng cho phép.

## **16.2. Kịch bản Chaos Test (mô phỏng sự cố)**

- Đang nhận tải cao, chủ động dừng container Elasticsearch (docker stop elasticsearch).
- Tiêu chí đạt: Ingestion API vẫn nhận log bình thường và đẩy vào Kafka; khi Elasticsearch hoạt động trở lại, Indexer Worker tiếp tục ghi log từ Kafka sang Elasticsearch mà không mất bản ghi nào (trong phạm vi NFR-REL-01).

# **17\. Đo lường hiệu năng (Performance Benchmark)**

## **17.1. Nguyên tắc**

Mọi con số hiệu năng đưa vào tài liệu chính thức hoặc CV chỉ được công bố sau khi có kết quả benchmark thực tế, không sử dụng số liệu ước lượng chưa kiểm chứng (xem điểm 14 trong biên bản đánh giá).

## **17.2. Benchmark Virtual Threads vs Platform Threads**

So sánh hai mô hình dưới cùng một kịch bản I/O-bound (ghi log, gọi Elasticsearch), đo các chỉ số:

- Throughput (requests/giây)
- Latency (P50/P95/P99)
- CPU usage
- Memory usage
- Khả năng chịu tải đồng thời (concurrency)

Kết quả đo được sẽ thay thế cho các mô tả định tính ở mục 6.4, ví dụ: "Giảm X% bộ nhớ sử dụng dưới Y kết nối đồng thời" — trong đó X là số đo thực tế.

## **17.3. Benchmark Elasticsearch Search Latency**

Đo P50/P95/P99 theo mục 6.1 trên dataset 10 triệu bản ghi, với các loại truy vấn: lọc theo service + level + timestamp range, tìm kiếm full-text theo keyword, tra cứu theo trace_id.

# **18\. Kiến trúc triển khai (Deployment Architecture)**

Không nhất thiết phải triển khai một hệ thống production thật với hàng chục server. Có thể xây dựng phiên bản single-node/local Docker, miễn là architecture, requirement, failure model và benchmark được thiết kế theo tư duy production system.

- Triển khai bằng Docker Compose: Ingestion Service, Kafka + Zookeeper (hoặc KRaft), Kafka Streams app, Indexer Worker, Elasticsearch, Redis, Alert Engine, Auth Service.
- Cấu hình biến môi trường riêng cho từng environment: development, staging, production.
- Có thể mở rộng lên Kubernetes ở giai đoạn sau (xem mục 20).

# **19\. Giám sát & khả năng quan sát nền tảng (Monitoring & Observability)**

Nền tảng phải tự giám sát chính nó (self-monitoring) — một hệ thống observability mà không quan sát được chính nó là một điểm yếu nghiêm trọng cần tránh.

| **Chỉ số nền tảng (Platform Metric)** | **Ví dụ**                                                       |
| ------------------------------------- | --------------------------------------------------------------- |
| Kafka Consumer Lag                    | payment-indexer: 124, alert-engine: 32 → alert nếu lag > ngưỡng |
| Kafka Throughput                      | Số message/giây theo topic                                      |
| Elasticsearch Indexing Rate           | Số document được index thành công/giây                          |
| Elasticsearch Failure Rate            | Tỉ lệ document lỗi index / tổng số document                     |
| Redis Health                          | Trạng thái kết nối, độ trễ lệnh (command latency)               |
| API Latency                           | P50/P95/P99 theo từng endpoint                                  |
| API Error Rate                        | Tỉ lệ response 4xx/5xx theo endpoint                            |
| JVM Memory & CPU                      | Heap usage, GC pause time, CPU load                             |

# **20\. Khả năng mở rộng trong tương lai (Future Scalability)**

- Chuyển đổi từ single-node/local Docker sang Kubernetes để tự động scale theo tải.
- Áp dụng Machine Learning cho anomaly detection thay vì chỉ dùng rule-based threshold.
- Mở rộng chính sách retention sang lưu trữ lạnh (cold storage) chi phí thấp (ví dụ object storage) cho dữ liệu > 90 ngày.
- Hỗ trợ multi-region để giảm độ trễ ingestion cho các microservices phân tán theo vùng địa lý.
- Bổ sung SDK/agent thu thập log gắn trực tiếp cho các ngôn ngữ lập trình phổ biến, giảm phụ thuộc vào tích hợp REST API thủ công.
- Mở rộng Alert Engine hỗ trợ multi-condition rule (kết hợp nhiều điều kiện) và escalation policy (leo thang cảnh báo nếu không được acknowledge sau khoảng thời gian quy định).

# **Phụ lục: Thứ tự triển khai đề xuất (Implementation Roadmap)**

Thứ tự ưu tiên triển khai theo 5 giai đoạn, tránh làm tất cả cùng lúc:

### **Phase 1 — Core Platform**

- Authentication
- Service Registry
- Log Ingestion
- Kafka
- Elasticsearch
- Log Search

### **Phase 2 — Distributed Processing**

- Kafka Streams
- Windowing
- Alert Engine
- Redis Deduplication
- Retry
- DLQ

### **Phase 3 — Observability**

- Trace Correlation
- Dashboard
- WebSocket
- Service Health
- Kafka Lag Monitoring

### **Phase 4 — Enterprise**

- RBAC
- Alert Rule Management
- Alert Lifecycle
- Audit Log
- Retention Policy
- Rate Limiting

### **Phase 5 — Proof (Kiểm chứng)**

- JUnit
- Integration Test
- Testcontainers
- JMeter Load Test
- Chaos Testing
- Benchmark Virtual Threads
- Benchmark Elasticsearch