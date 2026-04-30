# PulseCommerce

Spring Boot 3.3 기반 마켓플레이스 MVP. 원 명세(395줄)는 마이크로서비스·ELK·Argo 등 대규모지만, 본 구현은 운영 골격만 추출한 단일 모듈 MVP.

## MVP 범위

- **역할**: BUYER / SELLER / ADMIN
- **인증**: JWT(HS256) + BCrypt 비밀번호, 로그인 IP 토큰버킷 레이트리밋(429)
- **상품**: 등록(SELLER), 조회(공개), 본인 상품 수정·삭제(SELLER), 강제 삭제(ADMIN), 본인 상품 목록(`/products/mine`)
- **주문**: 생성(BUYER, 자기 상품 차단), 본인 주문 목록, 본인 주문 취소(재고 환원), `Idempotency-Key` 멱등 처리, 상태머신(PLACED→PAID→SHIPPED→DELIVERED), 셀러 자기 상품 주문 조회(`/orders/seller`)
- **관리자**: 판매자 목록(`/admin/sellers`), 정지/해제, 정지된 계정 로그인 거부
- **도메인 이벤트**: `OrderPlacedEvent`, `OrderCancelledEvent` 발행
- **관측**: JSON 콘솔 로깅, 모든 응답에 `X-Request-Id` 발급/전파, Prometheus(`/actuator/prometheus`), OpenTelemetry 트레이싱 브릿지

## 의도적으로 보류한 항목

마이크로서비스 분해, Kafka, ELK, Argo, OpenTelemetry, Spark/HDFS, Feature Store, Backstage, Step-up MFA, OAuth2, ZAP/Trivy CI, k6 부하 테스트, Canary 배포, RTO/RPO 복구 리허설, 분쟁/정산/클레임, Export 통제. 모두 본 MVP에서는 의도적으로 다루지 않음.

## 빌드 및 실행

```bash
# Java 17 / Maven 3.9 필요 (sdkman 권장)
mvn test                  # 23건 통합 테스트 실행
mvn spring-boot:run       # 8080에서 실행, 데이터는 ./data/pulse 에 H2 파일 DB
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
```

## 테스트 결과

`mvn test` 전체 23건 0실패. 주요 시나리오:

- 인증/권한: signup·login·role 차단(BUYER/SELLER/ADMIN), 정지 계정 로그인 거부, 로그인 레이트리밋(429)
- 상품: 등록(SELLER 전용), 본인 상품 수정·삭제, 다른 셀러 차단(403), 셀러별 목록 분리, ADMIN 강제 삭제
- 주문: 생성·재고 차감·자기상품 차단·페이지네이션·취소·재고 환원, 멱등키 중복 방지, 동시성 oversell 방지(낙관적 잠금→409), 상태머신(pay/ship/deliver) 전이 검증, 다른 셀러 ship 차단
- 관리자: 셀러 목록·정지·해제, 정지 후 재로그인 가능 검증
- 이벤트: OrderPlaced/OrderCancelled 발행 캡처
