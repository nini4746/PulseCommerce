# PulseCommerce

Spring Boot 3.3 기반 마켓플레이스 MVP. 원 명세(395줄)는 마이크로서비스·ELK·Argo 등 대규모지만, 본 구현은 운영 골격만 추출한 단일 모듈 MVP.

## MVP 범위

- **역할**: BUYER / SELLER / ADMIN
- **인증**: JWT(HS256) 액세스(15분) + 서버 저장 리프레시 토큰(rotation, revoke), BCrypt 비밀번호, 로그인 IP 토큰버킷 레이트리밋(429), `/auth/refresh`·`/auth/logout`
- **상품**: 등록(SELLER), 조회(공개), 본인 상품 수정·삭제(SELLER), 강제 삭제(ADMIN), 본인 상품 목록(`/products/mine`)
- **주문**: 생성(BUYER, 자기 상품 차단), 본인 주문 목록, 본인 주문 취소(재고 환원, 사유 코드), `Idempotency-Key` 멱등 처리, 상태머신(PLACED→PAID→SHIPPED→DELIVERED), 셀러 자기 상품 주문 조회(`/orders/seller`)
- **클레임/환불**: 취소 사유 enum(`CancelReason`), 환불 상태머신(NONE/REQUESTED/APPROVED/REJECTED/REFUNDED), `/orders/{id}/refund` (셀러 본인 상품 또는 ADMIN — APPROVE/REJECT/REFUND)
- **셀러 KPI**: `/seller/kpi` — 본인 상품 기준 GMV(취소 제외 매출 합), 주문수, 취소수, 취소율
- **관리자**: 판매자 목록(`/admin/sellers`), 정지/해제, 정지된 계정 로그인 거부
- **도메인 이벤트**: `OrderPlacedEvent`, `OrderCancelledEvent` 발행
- **관측**: JSON 콘솔 로깅, 모든 응답에 `X-Request-Id` 발급/전파, Prometheus(`/actuator/prometheus`), OpenTelemetry 트레이싱 브릿지

## 의도적으로 보류한 항목

마이크로서비스 분해, Kafka, ELK, Argo, Spark/HDFS, Feature Store, Backstage, Step-up MFA, OAuth2, ZAP/Trivy CI, k6 부하 테스트, Canary 배포, RTO/RPO 복구 리허설, 정산, Export 통제. 모두 본 MVP에서는 의도적으로 다루지 않음. (클레임 기본 흐름·환불 상태머신은 v0.2에서 도입)

## 빌드 및 실행

```bash
# Java 17 필요. Maven 미설치 시 동봉된 ./mvnw 사용 가능.
./mvnw test               # 38건 통합 테스트 실행
./mvnw spring-boot:run    # 8080에서 실행, 데이터는 ./data/pulse 에 H2 파일 DB
# (전역 mvn 설치된 경우) mvn test / mvn spring-boot:run 도 동일하게 동작
```

기본 관리자 계정이 부트 시 시드됨: `admin@pulse.local` / `admin12345`.

## 호출 예시

```bash
# 1) 판매자 회원가입 + 로그인
curl -X POST localhost:8080/auth/signup -H 'content-type: application/json' \
  -d '{"email":"sel@x.com","password":"passpass1","role":"SELLER"}'
TOKEN_S=$(curl -s -X POST localhost:8080/auth/login -H 'content-type: application/json' \
  -d '{"email":"sel@x.com","password":"passpass1"}' | jq -r .token)

# 2) 상품 등록
curl -X POST localhost:8080/products -H "Authorization: Bearer $TOKEN_S" \
  -H 'content-type: application/json' -d '{"name":"Apple","priceCents":1000,"stock":5}'

# 3) 구매자 회원가입 + 주문
curl -X POST localhost:8080/auth/signup -H 'content-type: application/json' \
  -d '{"email":"buy@x.com","password":"passpass1","role":"BUYER"}'
TOKEN_B=$(curl -s -X POST localhost:8080/auth/login -H 'content-type: application/json' \
  -d '{"email":"buy@x.com","password":"passpass1"}' | jq -r .token)
curl -X POST localhost:8080/orders -H "Authorization: Bearer $TOKEN_B" \
  -H 'content-type: application/json' -d '{"productId":1,"quantity":2}'

# 4) 관리자가 판매자 제재
TOKEN_A=$(curl -s -X POST localhost:8080/auth/login -H 'content-type: application/json' \
  -d '{"email":"admin@pulse.local","password":"admin12345"}' | jq -r .token)
curl -X POST localhost:8080/admin/sellers/1/suspend -H "Authorization: Bearer $TOKEN_A"
curl -X POST localhost:8080/admin/sellers/1/unsuspend -H "Authorization: Bearer $TOKEN_A"
curl localhost:8080/admin/sellers -H "Authorization: Bearer $TOKEN_A"

# 5) 멱등 주문 (같은 키 재호출 시 같은 주문 반환)
curl -X POST localhost:8080/orders -H "Authorization: Bearer $TOKEN_B" \
  -H 'content-type: application/json' -H 'Idempotency-Key: order-2026-04-30-001' \
  -d '{"productId":1,"quantity":1}'

# 6) 주문 상태 전이
curl -X POST localhost:8080/orders/1/pay     -H "Authorization: Bearer $TOKEN_B"
curl -X POST localhost:8080/orders/1/ship    -H "Authorization: Bearer $TOKEN_S"
curl -X POST localhost:8080/orders/1/deliver -H "Authorization: Bearer $TOKEN_S"

# 7) 셀러 본인 데이터 조회
curl localhost:8080/products/mine -H "Authorization: Bearer $TOKEN_S"
curl localhost:8080/orders/seller -H "Authorization: Bearer $TOKEN_S"

# 8) 본인 상품 수정·삭제
curl -X PATCH localhost:8080/products/1 -H "Authorization: Bearer $TOKEN_S" \
  -H 'content-type: application/json' -d '{"priceCents":1500,"stock":10}'
curl -X DELETE localhost:8080/products/1 -H "Authorization: Bearer $TOKEN_S"

# 9) 리프레시 토큰 회전·로그아웃
RESP=$(curl -s -X POST localhost:8080/auth/login -H 'content-type: application/json' \
  -d '{"email":"buy@x.com","password":"passpass1"}')
ACCESS=$(echo $RESP | jq -r .accessToken)
REFRESH=$(echo $RESP | jq -r .refreshToken)
# 새 액세스+리프레시 발급, 기존 리프레시는 즉시 무효화
curl -X POST localhost:8080/auth/refresh -H 'content-type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}"
# 로그아웃 — 해당 사용자의 활성 리프레시 모두 revoke
curl -X POST localhost:8080/auth/logout -H "Authorization: Bearer $ACCESS"

# 10) 셀러 KPI
curl localhost:8080/seller/kpi -H "Authorization: Bearer $TOKEN_S"
# {"orderCount":N,"cancelledCount":M,"gmvCents":...,"cancelRate":0.x}

# 11) 클레임 — 사유 코드와 환불 상태머신
curl -X POST localhost:8080/orders/1/cancel -H "Authorization: Bearer $TOKEN_B" \
  -H 'content-type: application/json' -d '{"reason":"DELIVERY_DELAYED","note":"3일 지연"}'
# 결제완료 후 취소 시 refundStatus=REQUESTED. 셀러/관리자가 처리:
curl -X POST localhost:8080/orders/1/refund -H "Authorization: Bearer $TOKEN_S" \
  -H 'content-type: application/json' -d '{"action":"APPROVE"}'
curl -X POST localhost:8080/orders/1/refund -H "Authorization: Bearer $TOKEN_S" \
  -H 'content-type: application/json' -d '{"action":"REFUND"}'   # APPROVED→REFUNDED
# 또는 REJECT (REQUESTED→REJECTED)
```

## 테스트 결과

`./mvnw test` 전체 38건 0실패. 주요 시나리오:

- 인증/권한: signup·login·role 차단(BUYER/SELLER/ADMIN), 정지 계정 로그인 거부, 로그인 레이트리밋(429)
- 리프레시 토큰: rotation(이전 토큰 즉시 무효화), 잘못된 토큰 401, 로그아웃 후 모든 리프레시 무효화
- 상품: 등록(SELLER 전용), 본인 상품 수정·삭제, 다른 셀러 차단(403), 셀러별 목록 분리, ADMIN 강제 삭제
- 주문: 생성·재고 차감·자기상품 차단·페이지네이션·취소·재고 환원, 멱등키 중복 방지, 동시성 oversell 방지(낙관적 잠금→409), 상태머신(pay/ship/deliver) 전이 검증, 다른 셀러 ship 차단
- 클레임/환불: 사유 코드 기록, 결제완료 취소 시 REQUESTED 자동 생성, 셀러 APPROVE→REFUND·REJECT 흐름, 다른 셀러 차단(403), 잘못된 사유 400, ADMIN 강제 환불
- 셀러 KPI: 본인 상품 기준 GMV(취소 제외) / 주문수 / 취소율 집계, 타 셀러 격리, 빈 상태(0) 처리
- 관리자: 셀러 목록·정지·해제, 정지 후 재로그인 가능 검증
- 이벤트: OrderPlaced/OrderCancelled 발행 캡처
