# Marketplace Aggregator — Architecture

## 개요

판매자가 상품을 한 번 등록하면 여러 마켓플레이스(eBay 등)에 자동으로 발행되고,
판매/댓글 등 모든 이벤트가 하나의 activity feed로 집약되는 시스템.

레퍼런스 마켓플레이스: **eBay**
- OAuth 2.0 인증, REST Inventory API
- 비동기 상품 등록 처리
- Platform Notification으로 webhook 발송
- 실제 연동은 구현하지 않고 mock으로 대체

---

## 전체 아키텍처 다이어그램

```
┌─────────────────────────────────────────────────────────────────────┐
│                           AWS Cloud                                  │
│                                                                      │
│  ┌──────────┐    ┌─────────────┐                                    │
│  │    S3    │    │ CloudFront  │◄────── 판매자 브라우저              │
│  │ (HTML/JS)│◄───│   (CDN)     │                                    │
│  └──────────┘    └──────┬──────┘                                    │
│                         │ API 요청                                   │
│                         ▼                                           │
│                  ┌─────────────┐                                    │
│                  │ API Gateway │                                    │
│                  └──────┬──────┘                                    │
│                         │                                           │
│                         ▼                                           │
│              ┌──────────────────────┐                               │
│              │  Lambda (Spring Boot) │                               │
│              │  + SnapStart (Java21) │                               │
│              │                      │                               │
│              │  POST /listings       │                               │
│              │  GET  /listings       │                               │
│              │  POST /webhooks       │                               │
│              └────────┬─────────────┘                               │
│                       │                                             │
│           ┌───────────┼───────────────────┐                        │
│           │           │                   │                        │
│           ▼           ▼                   ▼                        │
│    ┌────────────┐ ┌──────────┐  ┌──────────────────┐              │
│    │  DynamoDB  │ │ Secrets  │  │  SQS             │              │
│    │            │ │ Manager  │  │  (publish queue) │              │
│    │ listings   │ │          │  └────────┬─────────┘              │
│    │ activity   │ │ - webhook│           │                         │
│    │ _events    │ │   secret │           │                         │
│    │            │ │ - mock   │           │                         │
│    └────────────┘ │   api key│           │                         │
│                   └──────────┘           │                         │
│                                          │                         │
│  ┌───────────────────────────────────────┼───────────────────────┐ │
│  │  Mock Marketplace (별도 모듈)          │                       │ │
│  │                                       ▼                       │ │
│  │  별도 API Gateway                                              │ │
│  │      │                                                         │ │
│  │      ▼                                                         │ │
│  │  ┌────────────────────┐                                        │ │
│  │  │ Lambda             │  POST /mock/listings/publish           │ │
│  │  │ (Publish Receiver) │  ← 우리 SQS consumer가 호출           │ │
│  │  └─────────┬──────────┘                                        │ │
│  │            │ 202 Accepted + 자기 Delay Queue에 넣음            │ │
│  │            ▼                                                   │ │
│  │  ┌────────────────────┐                                        │ │
│  │  │ SQS Delay Queue    │  5~30초 딜레이 (비동기 시뮬레이션)     │ │
│  │  └─────────┬──────────┘                                        │ │
│  │            │                                                   │ │
│  │            ▼                                                   │ │
│  │  ┌────────────────────┐                                        │ │
│  │  │ Lambda             │                                        │ │
│  │  │ (Event Emitter)    │                                        │ │
│  │  │                    │                                        │ │
│  │  │ 80% → webhook 발송 │                                        │ │
│  │  │ 20% → 실패 → DLQ   │                                        │ │
│  │  └─────────┬──────────┘                                        │ │
│  └────────────┼───────────────────────────────────────────────── ┘ │
│               │ HMAC 서명된 POST /webhooks                          │
│               ▼                                                     │
│       메인 Lambda (signature 검증 → DynamoDB activity_events 기록)  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 데이터 흐름 (step-by-step)

```
① 판매자가 브라우저에서 상품 등록 (title, description, price)
        │
        ▼
② POST /listings
   → DynamoDB listings 테이블에 저장 (status: PENDING)
   → SQS publish queue에 메시지 넣음
   → 판매자에게 즉시 200 응답  ← 판매자는 기다리지 않음
        │
        ▼  (비동기 ①: 우리가 marketplace에 보내는 방식)
③ SQS Consumer Lambda
   → mock marketplace POST /mock/listings/publish 호출
        │
        ▼
④ Mock Marketplace Publish Receiver
   → 202 Accepted 응답
   → 자기 내부 SQS Delay Queue에 메시지 넣음
        │
        ▼  (5~30초 후, 비동기 ②: marketplace가 결과를 알려주는 방식)
⑤ Mock Event Emitter Lambda
   → 80% 확률: HMAC 서명 첨부 후 POST /webhooks 발송
               body: { "event": "item_sold" } or { "event": "new_comment" }
   → 20% 확률: 실패 → SQS 자동 재시도 (최대 3회) → DLQ
        │
        ▼
⑥ POST /webhooks 수신
   → HMAC 서명 검증 (불일치 시 401 거부)
   → DynamoDB activity_events 테이블에 기록
        │
        ▼
⑦ 판매자 GET /listings
   → 상품 목록 + 각 상품의 activity feed 반환
```

---

## AWS 서비스 선택 근거

| 서비스 | 대안 | 선택 이유 |
|--------|------|-----------|
| Lambda + SnapStart | ECS Fargate | Fargate는 24/7 과금, Lambda는 pay-per-request. SnapStart로 Java cold start 해결 |
| DynamoDB on-demand | Aurora Serverless | Aurora도 최소 과금 존재. DynamoDB on-demand는 요청 없으면 0원 |
| SQS | Kafka (MSK) | Kafka 클러스터 자체 비용이 월 수십만원 이상. SQS는 첫 1M 요청 무료 |
| API Gateway HTTP API | REST API | HTTP API가 REST API 대비 약 70% 저렴 |
| S3 + CloudFront | EC2 + Nginx | 정적 파일 서빙에 서버 불필요. 거의 무료 |
| CDK (TypeScript) | SAM / Terraform | 단일 `cdk deploy` 명령, IaC as code |
| Secrets Manager | 환경변수 하드코딩 | 코드에 시크릿 노출 방지, 과제 명시 요구사항 |

---

## 프로젝트 구조

```
marketplace-aggregator/
├── APPROACH.md                   # 과제 제출용 접근 방식 문서
├── README.md                     # 배포/삭제/비용 가이드
├── architecture.md               # 이 파일
├── cdk/                          # AWS 인프라 정의 (TypeScript)
│   ├── package.json
│   └── lib/
│       └── marketplace-stack.ts  # Lambda, DynamoDB, SQS, CloudFront 등
├── backend/                      # Spring Boot 메인 앱 (Java 21)
│   ├── pom.xml
│   └── src/main/java/com/marketplace/
│       ├── listing/              # POST /listings, GET /listings
│       ├── webhook/              # POST /webhooks (수신 + 검증)
│       ├── activity/             # activity feed 조회
│       └── common/               # DynamoDB client, HMAC util
└── mock-marketplace/             # 독립 Lambda 모듈 (Java 21)
    ├── pom.xml
    └── src/main/java/com/marketplace/mock/
        ├── PublishReceiver.java   # POST /mock/listings/publish
        └── EventEmitter.java     # SQS → webhook 발송 (20% 실패율)
```

---

## DynamoDB 테이블 설계

### listings 테이블 (상품 원본 정보)
```
PK: listingId (UUID)

{
  "listingId":   "listing-001",
  "sellerId":    "seller-001",
  "title":       "맥북 프로 14인치",
  "description": "2023년형, 상태 좋음",
  "price":       1500000,
  "createdAt":   "2026-04-23T10:00:00Z",
  "updatedAt":   "2026-04-23T10:00:00Z"
}
```

> status 필드 없음. 마켓플레이스마다 상태가 다를 수 있기 때문에 marketplace_listings 테이블에서 관리.

### marketplace_listings 테이블 (마켓플레이스별 발행 상태)
```
PK: listingId
SK: marketplaceId

{
  "listingId":         "listing-001",
  "marketplaceId":     "ebay",
  "status":            "PENDING | PUBLISHED | FAILED",
  "externalListingId": "ebay-12345",
  "publishedAt":       "2026-04-23T10:05:00Z",
  "failReason":        "",
  "createdAt":         "2026-04-23T10:00:00Z",
  "updatedAt":         "2026-04-23T10:05:00Z"
}
```

> 같은 listing을 eBay에는 성공, Facebook에는 실패로 각각 관리 가능.

### activity_events 테이블
```
PK: listingId
SK: timestamp#eventId  (시간순 정렬 가능)

{
  "listingId":     "listing-001",
  "eventId":       "evt-001",
  "marketplaceId": "ebay",
  "timestamp":     "2026-04-23T10:05:00Z",
  "eventType":     "item_sold | new_comment | publish_failed",
  "data":          { "buyerName": "홍길동", "price": 1500000 }
}
```

> SK는 변경 없음. 기본 쿼리 패턴(listingId로 조회 + 시간순 정렬)은 그대로 유지.
> 마켓플레이스별 필터링이 필요하면 앱 레벨에서 처리.

---

## 엔드포인트 정의

### 메인 백엔드

| Method | Path | 호출 주체 | 설명 |
|--------|------|-----------|------|
| POST | /listings | 브라우저 | 상품 등록 + publish 큐 적재 |
| GET | /listings | 브라우저 | 상품 목록 + activity feed 조회 |
| POST | /webhooks | Mock Marketplace | 이벤트 수신 (HMAC 검증 필수) |

### Mock Marketplace

| Method | Path | 호출 주체 | 설명 |
|--------|------|-----------|------|
| POST | /mock/listings/publish | 우리 SQS Consumer | 상품 발행 요청 수신 → 202 반환 |

---

## 인증 설계

### Webhook HMAC 서명 검증 (필수 — 과제 명시 요구사항)

```
사전 준비:
  - 랜덤 secret key 생성
  - Secrets Manager에 저장
  - mock marketplace Lambda와 메인 Lambda 둘 다 같은 키 읽음

발송 (mock Event Emitter):
  signature = HMAC-SHA256(requestBody, secretKey)
  헤더 첨부: X-Marketplace-Signature: sha256={signature}

수신 검증 (메인 /webhooks):
  expected = HMAC-SHA256(requestBody, secretKey)
  MessageDigest.isEqual(received, expected)  ← timing attack 방지
  불일치 시 → 401 Unauthorized
```

> 일반 `String.equals()` 대신 `MessageDigest.isEqual()` 사용.
> 이유: 일반 비교는 앞글자부터 비교하다 틀리면 즉시 중단 → 응답 시간으로 서명 값 유추 가능 (timing attack).

### Mock API Key (선택)

```
Secrets Manager에 "mock-api-key-xxxx" 저장
우리 SQS Consumer → mock endpoint 호출 시 헤더에 첨부
Authorization: Bearer mock-api-key-xxxx

실제 환경에서는 이 자리에 eBay OAuth access_token이 들어감
```

---

## Idempotency (중복 처리 방지)

### 핵심 원칙: listingId(PK)가 곧 idempotency key

DynamoDB Conditional Write의 원자성 보장은 **같은 PK를 가진 item 단위**로만 작동한다.
따라서 idempotency key가 PK가 되어야 한다. 우리 설계에서는 `listingId`가 PK이면서 idempotency key 역할을 겸한다.

### 상품 등록 중복 방지 — 동시 요청 처리 흐름

```
클라이언트 A ── POST /listings (listingId: "abc") ──► Lambda 1 시작
                                                           │
클라이언트 B ── POST /listings (listingId: "abc") ──► Lambda 2 시작
(네트워크 재시도 or 중복 클릭)                             │
                                                           │
                       ┌───────────────────────────────────┤
                       │                                   │
                       ▼                                   ▼
                 [Lambda 1]                          [Lambda 2]
                 비즈니스 로직                        비즈니스 로직
                 유효성 검사 등                       유효성 검사 등
                       │                                   │
                       ▼                                   ▼
                 DynamoDB                           DynamoDB
                 Conditional Write 시도             Conditional Write 시도
                 PUT item                           PUT item
                 IF attribute_not_exists            IF attribute_not_exists
                 (listingId)                        (listingId)
                       │                                   │
                       └──────────┬────────────────────────┘
                                  │
                                  ▼
                           ┌─────────────┐
                           │  DynamoDB   │
                           │  원자적 처리 │
                           │  (파티션    │
                           │   레벨 직렬화)│
                           └──────┬──────┘
                                  │
                 ┌────────────────┴────────────────┐
                 │                                 │
                 ▼                                 ▼
           먼저 처리된 요청                  나중에 처리된 요청
                 │                                 │
                 ▼                                 ▼
           ✅ 쓰기 성공                    ❌ ConditionalCheck
           item 저장됨                        FailedException
                 │                                 │
                 ▼                                 ▼
           SQS publish                       409 Conflict 반환
           메시지 적재                        "이미 처리된 요청"
                 │
                 ▼
           200 OK 반환
```

> **RDB의 "커밋 전 상태"와 다른 점:**
> DynamoDB conditional write는 "조건 확인 + 쓰기"가 하나의 원자적 연산이다.
> Lambda 1이 write 응답을 아직 못 받은 상태(in-flight)여도,
> DynamoDB 내부에서는 이미 item이 존재하는 것으로 처리되어 Lambda 2는 즉시 실패한다.
> RDB처럼 "읽고 나서 쓰기 전 타이밍에 끼어드는" 순간이 없다.

### Marketplace Publish 중복 방지

```
activity_events 저장 시:
  SK = timestamp#eventId
  eventId는 webhook body에서 온 고유값 사용
  → 같은 eventId가 두 번 오면 DynamoDB 조건부 쓰기로 거부
```

---

## 여러 마켓플레이스 — 팩토리 패턴

SQS 메시지에 marketplaceId를 포함시키고, Consumer Lambda에서 팩토리로 분기한다.

```json
SQS 메시지: { "listingId": "listing-001", "marketplaceId": "ebay" }
```

```java
interface MarketplaceAdapter {
    void publish(Listing listing);
}

class EbayAdapter implements MarketplaceAdapter { ... }
class FacebookAdapter implements MarketplaceAdapter { ... }

class MarketplaceAdapterFactory {
    MarketplaceAdapter get(String marketplaceId) {
        return switch (marketplaceId) {
            case "ebay"     -> new EbayAdapter();
            case "facebook" -> new FacebookAdapter();
            default -> throw new UnsupportedMarketplaceException(marketplaceId);
        };
    }
}

// Consumer Lambda
MarketplaceAdapter adapter = factory.get(message.getMarketplaceId());
adapter.publish(listing);
```

---

## 비동기 재처리 전략 (SQS + DLQ)

```
POST /listings
      │
      ▼
  Lambda
  DynamoDB 저장
      │
      ▼
┌─────────────────────────────────────────────────────┐
│  SQS Publish Queue                                  │
│                                                     │
│  ┌─────────────────────────────────────────────┐   │
│  │  Message: { listingId: "abc", ... }         │   │
│  └─────────────────┬───────────────────────────┘   │
└────────────────────┼────────────────────────────────┘
                     │ trigger
                     ▼
               SQS Consumer Lambda
               mock marketplace 호출
                     │
         ┌───────────┴───────────┐
         │                       │
         ▼                       ▼
      ✅ 성공                 ❌ 실패
         │                (타임아웃, 5xx 등)
         ▼                       │
    메시지 삭제                   ▼
    (처리 완료)        visibility timeout 후
                       메시지가 큐로 돌아옴
                               │
                     ┌─────────┴──────────┐
                     │  재시도 횟수 체크   │
                     └─────────┬──────────┘
                                │
              ┌─────────────────┴─────────────────┐
              │                                   │
              ▼                                   ▼
        재시도 < 3회                         재시도 = 3회
              │                            (maxReceiveCount)
              ▼                                   │
        다시 처리 시도                             ▼
                                    ┌─────────────────────┐
                                    │  DLQ                │
                                    │  (Dead Letter Queue)│
                                    └──────────┬──────────┘
                                               │
                                               ▼
                                    CloudWatch Alarm
                                               │
                                               ▼
                                    SNS → 이메일 / 슬랙
```

> **visibility timeout:**
> 메시지가 Lambda에 전달되는 순간 일시적으로 다른 Lambda에게 숨겨진다.
> 처리 성공 → 메시지 삭제 / 처리 실패 또는 타임아웃 → 숨김 해제 후 재시도.

### SQS 실패의 두 가지 레이어

```
레이어 ①: 마켓플레이스에 요청 자체가 실패
  Consumer Lambda → marketplace 호출
    → 네트워크 오류, 타임아웃, 5xx 응답
    → Lambda exception 발생 → SQS 재시도  ← 이게 SQS retry의 역할

레이어 ②: 마켓플레이스가 202 수락했지만 내부 처리 실패
  Consumer Lambda → marketplace 호출
    → 202 Accepted  ← Lambda 입장에서 성공, 메시지 삭제됨
    → 이후 webhook으로 결과가 와야 하는데 안 오면 → listing PENDING stuck
```

### publish_failed 처리 — 자동 재시도 안 함

publish_failed webhook이 왔다는 것은 마켓플레이스가 처리까지 했는데 실패했다는 의미다.
실패 원인이 데이터 오류(필수 필드 누락, 카테고리 코드 잘못됨 등)일 수 있어서
원인 확인 없이 자동 재시도하면 무한루프 + rate limit 소진 위험이 있다.

```
publish_failed webhook 수신
  → marketplace_listings: status = FAILED
  → activity_events: publish_failed 기록
  → 판매자 피드에 "발행 실패" 표시
  → 판매자가 직접 재시도 (nice-to-have: UI 재시도 버튼)
  → 개발자 알림 (CloudWatch → Slack)
```

### PENDING stuck (webhook 안 오는 경우)

우리가 mock marketplace를 직접 만들기 때문에 Mock DLQ Consumer가 항상 publish_failed webhook을 발송하도록 설계할 수 있다. 따라서 이 과제에서는 stuck이 구조적으로 발생하지 않는다.

> **실제 eBay 연동 시에는 must-have:**
> eBay 내부 장애로 webhook이 영구적으로 오지 않을 수 있다.
> EventBridge Scheduler로 PENDING 상태가 N분 이상인 marketplace_listings를 주기적으로 조회해 FAILED 처리하는 로직이 필요하다.
> 현재는 mock이 webhook을 보장하므로 미구현.

Mock Marketplace의 20% 실패율 흐름:
```
Event Emitter Lambda 실패
  → SQS Delay Queue에서 재시도 (최대 3회)
  → 3회 실패 → Mock DLQ
  → Mock DLQ Consumer → publish_failed webhook 발송
  → activity_events에 "publish_failed" 기록
```

---

## 비용 추정 (10 sellers / 1k listings / 10k events / month)

| 서비스 | 예상 비용 |
|--------|-----------|
| Lambda | ~$0 (월 1M 요청 무료 tier) |
| API Gateway HTTP API | ~$0.01 |
| DynamoDB on-demand | ~$0.03 |
| SQS | ~$0 (월 1M 요청 무료) |
| S3 + CloudFront | ~$0.05 |
| Secrets Manager | ~$0.80 (secret 2개 × $0.40) |
| **합계** | **~$1/month** |

첫 비용 벽: 월 수백만 DynamoDB read, Lambda 1M 초과 시.

---

---

# Q&A — 헷갈렸던 개념 정리

---

**Q. CloudFront 서빙이 뭔가? S3에 정적 호스팅을 해야 하나?**

A. 맞다. S3가 원본 파일 저장소(HTML, JS, CSS)이고, CloudFront가 그 앞에서 전세계 엣지 서버에 캐싱해서 빠르게 서빙한다.
"정적 호스팅"이라고 부르는 이유는 서버 프로세스 없이 파일을 그대로 내려주기 때문이다.
React 앱도 `npm build` 하면 결국 `.html`, `.js` 파일들이 나오는데, 그걸 S3에 올리는 것과 같다.

---

**Q. Lambda는 EC2 같은 건가? pay-per-request라서 선호하는 건가?**

A. 같은 "실행 환경"이지만 개념이 다르다.
EC2는 내가 빌린 가상 서버로 24시간 켜져 있고 시간당 과금된다.
Lambda는 요청이 올 때만 코드가 실행되고, 끝나면 꺼진다. 요청 수 + 실행 시간 기준으로 과금된다.
이 과제에서 비용 절감이 평가 기준이라 Lambda가 적합하다.

---

**Q. Lambda cold start 문제 있지 않나? Java는 특히 느리다고 하는데.**

A. 맞다. 일반 Java Lambda cold start는 3~8초로 심각하다.
해결책은 Java 21부터 지원하는 **Lambda SnapStart**다.
Lambda 배포 시 JVM 부팅 상태를 스냅샷으로 찍어두고, 요청이 오면 스냅샷에서 복원한다.
cold start가 ~200ms 수준으로 줄어들며 코드 변경 없이 설정만으로 적용된다.

---

**Q. mock marketplace가 "비동기"라는 말은 카프카 같은 걸 수신한다는 건가?**

A. 아니다. REST API 호출이 맞고, 비동기의 의미가 다르다.
동기는 요청하면 처리 완료까지 기다렸다가 결과를 받는 것이고,
비동기는 요청하면 "접수했어(202)"만 하고 끊은 뒤, 나중에 처리 결과를 webhook으로 알려주는 것이다.
실제 eBay API도 상품 등록 요청 시 즉시 완료가 아니라 "접수됨" 상태를 반환하고 실제 처리는 비동기로 한다.

---

**Q. webhook이 뭔가?**

A. webhook = 상대방이 나한테 HTTP 요청을 거는 것.
일반 API 호출은 내가 상대방 서버에 요청하는 거지만, webhook은 반대다.
상대방에서 뭔가 이벤트가 생겼을 때 내 서버로 HTTP POST를 쏴준다.
비유하면, 내가 은행에 전화해서 잔액을 묻는 게 일반 API이고, 은행이 나한테 전화해서 "입금됐어요" 알려주는 게 webhook이다.
아키텍처 개념이고, 구현은 그냥 HTTP POST다.

---

**Q. /webhooks 하나로 퉁쳐도 되나? /item-sold, /comment 따로 있어야 하지 않나?**

A. /webhooks 하나로 퉁쳐도 된다. body에 event 타입을 담으면 된다.
```json
{ "event": "item_sold", "listingId": "...", "data": {} }
{ "event": "new_comment", "listingId": "...", "data": {} }
```
받는 쪽에서 event 필드 보고 분기 처리한다.
실제 eBay, Stripe, GitHub 다 이 방식을 사용한다.

---

**Q. DynamoDB는 MongoDB 같은 건가?**

A. 둘 다 NoSQL이고 JSON 형태로 데이터를 저장하는 건 비슷하다.
차이는 DynamoDB는 AWS 완전관리형이라 서버가 없고, 요청 수 기반으로 과금된다.
핵심 제약은 PK(파티션 키)와 SK(정렬 키)로만 효율적으로 검색 가능하다는 점이다.
"price가 100만원 이상인 것 다 줘" 같은 쿼리는 비효율적(전체 스캔)이다.
이 과제에서 필요한 쿼리는 listingId로 조회하거나 시간순 정렬이므로 DynamoDB로 충분하다.

---

**Q. SQS는 카프카 같은 메시지 큐? AWS 안에 있나?**

A. 맞다. 카프카와 같은 메시지 큐 개념이고 AWS 완전관리형이다.
카프카는 직접 서버를 설치/운영해야 하고 강력하지만 복잡하다.
SQS는 설정이 거의 없고 pay-per-request라 이 규모에서 훨씬 적합하다.
카프카 클러스터는 자체 비용이 월 수십만원 이상 나온다.

---

**Q. Secrets Manager도 AWS 안에 있나?**

A. 맞다. AWS 완전관리형 서비스다.
비밀값(DB 비번, API 키, webhook secret)을 코드에 하드코딩하지 않고 여기 저장해두고, Lambda 실행 시 꺼내 쓴다.
이 과제 요구사항에 명시된 사항이기도 하다.

---

**Q. Mock API key가 토큰인가?**

A. 실제 OAuth처럼 만료/갱신이 있는 토큰이 아니라, 미리 약속한 고정 문자열이다.
실제 eBay라면 client_id + client_secret으로 OAuth 토큰을 발급받아야 하지만,
mock이니까 "mock-api-key-1234" 같은 문자열을 Secrets Manager에 저장해두고 헤더에 넣는다.
"실제 환경이라면 이 자리에 OAuth access_token이 들어간다"는 걸 보여주는 자리표시자 역할이다.

---

**Q. webhook secret key는 어떻게 교환하나?**

A. 실제 서비스(eBay 등)는 개발자 포털에서 일방적으로 발급해주고, 받는 쪽이 Secrets Manager에 저장한다.
이 과제에서는 mock이라 우리가 직접 secret을 생성해서 Secrets Manager에 저장하고,
메인 Lambda와 mock marketplace Lambda가 같은 AWS 계정 안에서 같은 Secrets Manager를 읽어 공유한다.

---

**Q. webhook signature 비교가 문자열 비교인가?**

A. 맞다. 문자열 비교지만 일반 `equals()`를 쓰면 안 된다.
`MessageDigest.isEqual()`을 써야 한다.
이유: 일반 문자열 비교는 앞에서부터 비교하다가 틀리면 즉시 중단하는데, 공격자가 응답 시간을 측정해서 올바른 서명을 한 글자씩 유추할 수 있다(timing attack).
`MessageDigest.isEqual()`은 항상 끝까지 비교해서 응답 시간이 동일하므로 정보를 얻을 수 없다.

---

**Q. 이 과제에서 꼭 써야 하는 인증이 있다면?**

A. Webhook HMAC 서명 검증이 필수다. 과제 평가 기준에 명시되어 있다.
`/webhooks` 엔드포인트는 인터넷에 열려 있어서 서명 검증 없으면 누구든 가짜 이벤트를 주입할 수 있다.
판매자 로그인(JWT/Cognito)은 nice-to-have, mock API key는 있으면 좋음 수준이다.

---

**Q. 우리가 marketplace에 listing 요청 보낼 때도 메시지 큐로 처리하나?**

A. 맞다. 두 개의 비동기가 있다.
비동기 ①: 우리가 marketplace에 보내는 방식 — 판매자 요청을 받으면 즉시 200 응답하고, SQS에 메시지를 넣어서 별도 Lambda가 비동기로 marketplace에 publish 요청을 보낸다.
비동기 ②: marketplace가 결과를 알려주는 방식 — marketplace가 처리 완료 후 webhook으로 우리에게 알린다.
이유: marketplace API가 느리거나 실패해도 판매자는 기다릴 필요 없고, 재시도도 SQS가 자동으로 처리한다.

---

**Q. idempotency key를 클라이언트가 만들어서 헤더에 넣는 건가?**

A. 두 가지 패턴이 있다.
클라이언트가 UUID를 만들어 헤더로 보내는 방식(Stripe가 이 방식)은 네트워크 오류로 응답을 못 받았을 때 재시도가 가능하다는 장점이 있다.
서버가 비즈니스 키로 만드는 방식(전 직장 방식)은 클라이언트 구현이 불필요하고 비즈니스적으로 명확하다.
둘 다 맞는 방법이다.
이 과제에서는 listingId를 서버에서 UUID로 생성하고, DynamoDB conditional write(`attribute_not_exists(listingId)`)로 중복을 막는다.

---

**Q. 비동기 재처리 로직을 직접 구현(MongoDB + processYN + 배치)하는 게 일반적인가?**

A. 그 방식도 정석이다. 플랫폼에 종속되지 않고 직접 제어가 가능하다는 장점이 있다.
AWS에서는 SQS + DLQ가 같은 역할을 인프라 레벨에서 대신 해준다.
SQS가 재시도 횟수 관리, DLQ가 최종 실패 메시지 보관, CloudWatch가 알림을 맡아서 코드가 거의 없어도 된다.
이 과제에서는 AWS 역량을 보여주는 자리라 SQS + DLQ를 쓰는 게 적합하다.

---

**Q. Idempotency — 동시 요청이 들어왔을 때 DynamoDB는 어떻게 처리하나? RDB의 "커밋 전" 문제는 없나?**

A. DynamoDB conditional write는 "조건 확인 + 쓰기"가 하나의 원자적 연산이라 RDB의 커밋 전 문제가 없다.
RDB에서 SELECT-then-INSERT 패턴을 쓰면, 두 트랜잭션이 둘 다 "없음"을 확인한 뒤 둘 다 INSERT를 시도하는 레이스 컨디션이 생긴다.
DynamoDB는 파티션 레벨에서 직렬화하기 때문에, Lambda 1이 write 응답을 아직 못 받은 상태(in-flight)여도 DynamoDB 내부에서는 이미 item이 존재하는 것으로 처리되어 Lambda 2는 즉시 ConditionalCheckFailedException을 받는다.

---

**Q. RDB의 트랜잭션 격리 수준(isolation level)에 따라 lock table 방식이 어떻게 달라지나?**

A. 구현 방식 A(INSERT + UNIQUE 제약)와 방식 B(SELECT → INSERT)에 따라 다르다.

방식 A — INSERT + UNIQUE 제약:
격리 수준과 무관하게 안전하다. DB가 INSERT 시점에 유니크 제약을 원자적으로 체크하기 때문이다.

방식 B — SELECT 후 INSERT (레이스 컨디션 발생 가능):
```
READ UNCOMMITTED : Dirty Read 발생
  → Lambda 1이 커밋 안 한 INSERT를 Lambda 2가 읽음
  → Lambda 1이 롤백해도 Lambda 2는 이미 "있다"고 판단 → 데이터 유실

READ COMMITTED   : 대부분 DB 기본값 (PostgreSQL 등)
  → Lambda 2는 커밋된 데이터만 읽음
  → 둘 다 "없음" 확인 후 둘 다 INSERT 시도 가능
  → UNIQUE 제약 없으면 중복 처리 발생

REPEATABLE READ  : MySQL InnoDB 기본값
  → MySQL은 Gap Lock으로 범위 INSERT를 블로킹 → 어느 정도 보호
  → PostgreSQL은 Gap Lock 없어서 여전히 위험

SERIALIZABLE     : 완전 직렬화
  → 안전하지만 Deadlock 빈발, 동시 처리량 급감
```

결론: SELECT-then-INSERT를 쓰려면 반드시 UNIQUE 제약을 함께 써야 한다.
UNIQUE 제약이 있으면 READ COMMITTED에서도 한 쪽만 성공한다.

---

**Q. DynamoDB Conditional Write에서 idempotency key가 PK여야 하나?**

A. 맞다. DynamoDB의 `attribute_not_exists` 원자성 보장은 같은 PK를 가진 item 단위로만 작동한다.
PK가 다른 두 item에 대해서는 충돌 감지가 되지 않는다.

선택지는 두 가지다:
- 선택지 1: idempotency key를 PK로 쓴다 (우리 설계 — `listingId`가 PK이면서 idempotency key 겸용)
- 선택지 2: 별도 idempotency 테이블을 만들고 거기서 PK로 중복 차단, listings 테이블은 내부 UUID를 PK로 사용

이 과제에서는 선택지 1로 설계했다.

---

**Q. SQS를 설정하면 Consumer Lambda까지 자동으로 설정되나?**

A. 아니다. SQS Queue 생성과 Lambda Event Source Mapping은 별개로 설정해야 한다.
CDK에서는 `queue.addEventSource(new SqsEventSource(lambda))` 한 줄로 연결하지만, 내부적으로는 두 개의 별개 리소스다.

---

**Q. visibility timeout은 서버(Lambda)가 여러 개일 때를 대비한 건가?**

A. 맞다. Lambda는 동시 요청이 오면 여러 인스턴스가 뜨는데, 같은 메시지를 두 인스턴스가 동시에 처리하는 것을 막는다.
메시지가 Lambda에 전달되는 순간 일시적으로 다른 Lambda에게 숨겨진다.
처리 성공 → 메시지 삭제. 처리 실패 또는 타임아웃 → 숨김 해제 후 재노출.

---

**Q. SQS에서 여러 서비스가 같은 메시지를 받을 수 있나? 카프카처럼 입하도 처리하고 상품도 처리하는 식으로.**

A. SQS는 메시지를 단 하나의 Consumer만 처리한다. 카프카와 설계 철학이 다르다.
카프카는 Consumer Group별로 독립적인 offset을 관리해서 여러 서비스가 같은 메시지를 읽을 수 있다.
SQS에서 여러 서비스가 같은 메시지를 받으려면 SNS를 앞에 두고 SNS → 여러 SQS 큐로 팬아웃해야 한다.
각 SQS 큐는 독립적으로 처리하고 서로 기다리지 않는다.

---

**Q. SQS 재시도 횟수는 어디서 체크하나? Lambda 코드에서 직접 카운팅해야 하나?**

A. SQS가 직접 카운팅한다. Lambda 코드에서 체크할 필요 없다.
SQS 설정 시 `maxReceiveCount`를 지정하면, 해당 횟수만큼 전달됐는데 삭제가 안 되면 SQS가 자동으로 DLQ로 이동시킨다.
메시지의 `ApproximateReceiveCount` 속성으로 Lambda에서 읽을 수는 있지만 굳이 안 봐도 된다.

---

**Q. 각 Lambda는 DynamoDB에 무엇을 저장하나?**

A. Lambda마다 역할이 다르다.
- POST /listings Lambda → listings 테이블에 listing 저장 + SQS에 메시지 적재
- SQS Consumer Lambda → mock marketplace 호출 후 marketplace_listings 테이블의 status 업데이트
- POST /webhooks Lambda → activity_events 테이블에 이벤트 저장

---

**Q. publish_failed로 온 것은 재처리 안 해도 되나?**

A. 자동 재처리는 안 하는 게 맞다.
실패 원인이 일시적 장애(transient)일 수도 있고, 데이터 오류(permanent)일 수도 있다.
원인 확인 없이 자동 재시도하면 데이터 오류인 경우 무한루프 + rate limit 소진이 발생한다.
판매자 피드에 실패를 표시하고, 판매자가 직접 재시도하거나 개발자가 원인을 파악한 뒤 처리한다.

---

**Q. webhook이 안 오는 stuck PENDING 케이스는 must-have인가?**

A. 이 과제에서는 nice-to-have다.
우리가 mock marketplace를 직접 만들기 때문에 Mock DLQ Consumer가 항상 publish_failed webhook을 발송하도록 설계할 수 있어 stuck이 구조적으로 발생하지 않는다.
실제 eBay 연동이라면 must-have다. eBay 내부 장애로 webhook이 영구적으로 오지 않을 수 있기 때문에 EventBridge Scheduler로 PENDING timeout 체크가 필요하다.

---

**Q. 여러 마켓플레이스를 지원할 때 listings 테이블 설계는 어떻게 바뀌나?**

A. listings 테이블에서 status 필드를 제거하고, marketplace_listings 테이블을 분리한다.
status가 마켓플레이스마다 다를 수 있기 때문이다 (eBay는 PUBLISHED, Facebook은 FAILED 등).
marketplace_listings 테이블은 PK=listingId, SK=marketplaceId로 설계해서 마켓플레이스별 상태를 독립적으로 관리한다.

---

**Q. RDB에서도 INSERT + UNIQUE 제약 방식이 트랜잭션 안에서 동작하나?**

A. 동작한다. UNIQUE 제약은 트랜잭션 범위와 무관하게 INSERT 시점에 원자적으로 체크된다.
첫 번째 트랜잭션이 커밋 전이더라도, 두 번째 트랜잭션의 INSERT는 첫 번째가 커밋/롤백할 때까지 대기한다.
첫 번째가 커밋하면 → 두 번째 Duplicate key error. 첫 번째가 롤백하면 → 두 번째 INSERT 성공.

---

**Q. lock_table에 처리 완료 후 삭제하면 두 번째 방어선이 필요한가?**

A. 맞다. lock_table 삭제 방식은 두 번째 방어선이 필수다.
첫 번째 요청이 lock 삭제 후 커밋하면, lock_table에 더 이상 키가 없어서 두 번째 요청이 lock INSERT에 성공한다.
이때 메인 테이블의 UNIQUE 제약이 두 번째 방어선 역할을 해서 중복 처리를 막는다.
결국 lock_table 삭제 방식을 쓰려면 메인 테이블에도 UNIQUE 제약이 필수다.
그렇다면 lock_table 없이 메인 테이블 UNIQUE만으로도 충분하다는 결론이 나온다.

---

**Q. lock_table은 계속 쌓이지 않나?**

A. 삭제를 안 하는 방식(idempotency 기록으로 유지)이면 쌓인다. 두 가지 선택지가 있다.
선택 A: 그냥 쌓아두고 주기적 배치로 오래된 것 삭제. "이 키로 언제 처리됐다"는 기록이 남는 장점이 있다.
선택 B: 처리 완료 후 삭제. 테이블이 안 쌓이지만 재요청이 오면 메인 테이블 UNIQUE에서만 막히는 구조가 된다.
DynamoDB는 TTL 기능이 내장돼 있어서 ttl 속성에 만료 timestamp를 넣으면 자동 삭제된다.

---

**Q. lock_table을 AOP로 관리하는 방식은 어떤 건가?**

A. `@Idempotent` 같은 커스텀 어노테이션을 만들고 AOP로 전처리/후처리를 넣는 방식이다.
```
@Idempotent 어노테이션
  Before:        lock INSERT
  After:         lock DELETE (성공 시)
  AfterThrowing: lock DELETE (실패 시)
```
비즈니스 로직에 lock 코드가 섞이지 않고 어노테이션만 붙이면 되는 장점이 있다.
단, 서버 강제 종료(OOM, kill -9) 시에는 AfterThrowing도 실행되지 않아 lock이 남는다.
이 경우를 대비해 주기적 배치로 오래된 lock을 청소하는 것이 실무 패턴이다.
TTL 방식보다 배치가 나은 점은, TTL 만료 전까지 재요청이 막히는 반면 배치 주기(예: 1분)마다 청소해서 더 빠르게 lock을 해제할 수 있다는 것이다.
