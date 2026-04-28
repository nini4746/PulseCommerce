# PulseCommerce

Spring Boot 3.3 기반 마켓플레이스 MVP. 원 명세(395줄)는 마이크로서비스·ELK·Argo 등 대규모지만, 본 구현은 운영 골격만 추출한 단일 모듈 MVP.

## MVP 범위

- **역할**: BUYER / SELLER / ADMIN
- **인증**: JWT(HS256) + BCrypt 비밀번호
- **상품**: 등록(SELLER), 조회(공개)
- **주문**: 생성(BUYER, 자기 상품 주문 차단), 본인 주문 목록, 본인 주문 취소(재고 환원)
- **관리자 제재**: 판매자 정지(`/admin/sellers/{id}/suspend`) → 정지된 계정 로그인 거부
- **관측**: JSON 콘솔 로깅, 모든 응답에 `X-Request-Id` 발급/전파

## 의도적으로 보류한 항목

마이크로서비스 분해, Kafka, ELK, Argo, OpenTelemetry, Spark/HDFS, Feature Store, Backstage, Step-up MFA, OAuth2, ZAP/Trivy CI, k6 부하 테스트, Canary 배포, RTO/RPO 복구 리허설, 분쟁/정산/클레임, Export 통제. 모두 본 MVP에서는 의도적으로 다루지 않음.

## 빌드 및 실행

```bash
# Java 17 / Maven 3.9 필요 (sdkman 권장)
mvn test                  # 9건 통합 테스트 실행
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
```

## 테스트 결과

| 케이스 | 결과 |
|---|---|
| `health_isPublic` | ✅ |
| `signup_login_buy_full_flow` | ✅ |
| `buyer_cannot_create_product` | ✅ (403) |
| `seller_cannot_order` | ✅ (403) |
| `admin_cannot_be_signed_up` | ✅ (403) |
| `admin_can_suspend_seller_and_seller_cannot_login` | ✅ |
| `non_admin_cannot_suspend` | ✅ (403) |
| `cancel_order_restocks` | ✅ |
| `other_buyer_cannot_cancel` | ✅ (403) |

`mvn test` 전체 9건 0실패.
