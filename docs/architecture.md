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

### listings 테이블
```
PK: listingId (UUID)

{
  "listingId":   "listing-001",
  "sellerId":    "seller-001",
  "title":       "맥북 프로 14인치",
  "description": "2023년형, 상태 좋음",
  "price":       1500000,
  "status":      "PENDING | PUBLISHED | FAILED",
  "createdAt":   "2026-04-23T10:00:00Z"
}
```

### activity_events 테이블
```
PK: listingId
SK: timestamp#eventId  (시간순 정렬 가능)

{
  "listingId": "listing-001",
  "eventId":   "evt-001",
  "timestamp": "2026-04-23T10:05:00Z",
  "eventType": "item_sold | new_comment | publish_failed",
  "data":      { "buyerName": "홍길동", "price": 1500000 }
}
```

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

### 상품 등록 중복 방지

DynamoDB Conditional Write 사용:

```
조건: attribute_not_exists(listingId)
→ 같은 listingId로 이미 저장된 항목이 있으면 쓰기 실패
→ ConditionalCheckFailedException → 409 Conflict 반환
```

### Marketplace Publish 중복 방지

```
activity_events 저장 시:
  SK = timestamp#eventId
  eventId는 webhook body에서 온 고유값 사용
  → 같은 eventId가 두 번 오면 DynamoDB 조건부 쓰기로 거부
```

---

## 비동기 재처리 전략 (SQS + DLQ)

```
SQS publish queue
  → 처리 실패 시 visibility timeout 후 자동 재노출
  → 최대 재시도 3회 설정

DLQ (Dead Letter Queue)
  → 3회 모두 실패한 메시지가 여기로 이동
  → CloudWatch Alarm → SNS → 이메일/슬랙 알림
  → 사람이 직접 확인 후 재처리 or 폐기
```

Mock Marketplace의 20% 실패율도 동일한 흐름:
```
Event Emitter Lambda 실패
  → SQS Delay Queue에서 재시도
  → 3회 실패 → Mock DLQ로 이동
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
