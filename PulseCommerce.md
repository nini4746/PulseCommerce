# PulseCommerce — 입점형(마켓플레이스) 프로젝트 명세서 (v1.0)

실시간 행동 로그 기반 **개인화 추천/검색**과 **데이터 레이크(HDFS/Spark)**, **운영 자동화(Jenkins/Kubernetes)**, **관측(ELK + Tracing)**, **고성능 캐시/정합성 계층(Redis)**를 결합한 **운영 가능한 입점형 커머스 플랫폼**.

- 생성일: 2026-02-11

---

## 0. 한눈에 보는 구성

- **Internal Developer Platform(IDP)**: Backstage 기반 서비스 카탈로그/템플릿/런북/대시보드 링크를 단일 포털로 제공

- **3자 포털**: Buyer Web / Seller Center / Admin Console  
- **Core Domain**: Seller Onboarding·심사, 상품 심사/노출, 주문/결제, 클레임(취소/반품/교환), 정산(수수료/주기/보류/재계산), 리뷰/Q&A  
- **Security**: RBAC + Tenant Scope, MFA(특히 Seller/Admin), DLP/Export 통제, 감사로그, 키/시크릿 로테이션  
- **Reliability**: 무중단 배포(rolling/canary), DB 무중단 마이그레이션, ES alias 스위치, 백업/PITR + 복구 리허설(RTO/RPO)  
- **Observability**: ELK(로그) + OpenTelemetry Tracing(분산 추적) + 대시보드/알림 + 런북 + 게임데이
- **Data**: 이벤트 수집 → HDFS(Parquet) 적재 → Spark 배치(리포트/추천/리스크/품질) → 서빙(ES/DB/Redis) + 데이터 카탈로그/라인리지
- **Delivery**: Jenkins CI + GitOps CD(Argo CD) + Progressive Delivery(Argo Rollouts) + Policy-as-code(Kyverno) + Supply-chain 보안(Trivy/SBOM/서명)

---

# 1. 목표와 범위

## 1.1 목표
1) “동작하는 웹사이트”가 아니라 **운영 가능한 플랫폼**을 구축한다.  
2) 입점형 커머스의 현실(심사/제재/클레임/정산/분쟁/어뷰징)을 시스템으로 흡수한다.  
3) 데이터 기반 기능(검색/개인화/리스크/리포트)이 **재처리 가능**하고 **검증 가능**하게 만든다.

## 1.2 In-Scope(필수)
- 회원/인증/인가: JWT + Refresh, RBAC, MFA(Seller/Admin), 토큰/세션 revoke
- 입점/심사: Seller onboarding, Admin 승인/반려/보류, 제재/정산보류
- 상품: 등록/수정/재고/가격, 심사(PENDING/APPROVED/REJECTED/HIDDEN), 검색 인덱싱
- 주문/결제: 주문 생성, 결제 시뮬레이터(실PG 대체), Idempotency, 상태머신
- 클레임: 취소/반품/교환 + 분쟁/강제 환불(운영자)
- 정산: 수수료 정책(버전), 정산주기, 예상/확정/보류, 재계산(리컴퓨트)
- 검색/개인화: ES 기반 검색 + 트렌드/전환 기반 랭킹 + 개인화 재랭킹 + A/B 테스트
- Seller KPI: 매출/GMV, 전환, 출고/지연, 클레임 품질, 상품 성과(노출/클릭/CTR/키워드), 정산 리포트
- 보안: DLP(PII 최소화), Export 통제(사유/승인/워터마크/만료), 감사로그
- 운영/관측: ELK + Tracing, 알림, 런북, 장애 주입(게임데이), 성능/부하테스트(k6)
- 데이터: 이벤트 스키마/버전, HDFS Parquet 파티션, Spark 배치, 데이터 품질 게이트
- 배포: 무중단 배포(rolling/canary), GitOps CD, 자동 롤백, DB 무중단 마이그레이션

## 1.3 Out-of-Scope(초기 제외)
- 실결제(PG) 연동(단, 실패코드/재시도/Idempotency/보정 로직은 동일한 수준으로 구현)
- 멀티 리전 액티브-액티브(단, DR 시나리오/복구 리허설은 포함)
- 딥러닝 기반 추천(룰+집계+간단 모델 위주)

---

# 2. Actor(역할)와 포털

## 2.1 Actor 정의
- **BUYER**: 탐색/구매/결제/클레임/리뷰/문의
- **SELLER**: 입점/상품·재고·가격/프로모션/주문처리(송장/배송)/클레임 응대/정산·지표
- **ADMIN**: 판매자 심사·제재/상품 심사/정책(수수료·정산)/환불 강제/분쟁/감사·데이터 통제

## 2.2 포털 구성
- **Buyer Web**: B2C 화면(홈/검색/상세/장바구니/주문/마이페이지)
- **Seller Center**: 판매자 전용 콘솔(tenant=seller_id)
- **Admin Console**: 운영자 전용 콘솔(고위험 작업 보호/감사 강화)

---

# 3. 권한/보안 모델 (RBAC + Tenant Scope + Step-up)

## 3.1 원칙
- **RBAC** + **Tenant Scope**(seller는 자기 seller_id 리소스만)
- **Admin 고위험 작업**(환불 강제/정산 변경/제재/대량 export)은 **step-up** + **사유** + **감사로그**(+ 선택적 2인 승인)
- 프론트 권한 표시와 무관하게, 서버에서 **항상 강제**한다.

## 3.2 권한 매트릭스(요약)

| 리소스 | BUYER | SELLER | ADMIN | 제약 |
|---|---|---|---|---|
| Seller Onboarding | - | create/read(own) | approve/reject/ban | 본인 신청만 |
| Product | read | create/update/read(own) | approve/reject/hide/delete | 승인 후 노출 |
| Inventory/Price | - | update(own) | override/audit | 변경 이력 |
| Order | create/read(own) | read/update(own) | read/update/force-cancel | seller scope |
| Shipment | - | create/update(own) | read/audit | SLA 추적 |
| Claim | create/read(own) | respond/update(own) | approve/force-refund | 분쟁 처리 |
| Settlement | - | read(own) | configure/export | export guarded |
| Review/Q&A | create/read(own) | respond(own) | moderate/delete | 스팸/악성 |
| Audit Log | - | read(own-limited) | read/export(guarded) | 사유/승인 |

## 3.3 인증/세션
- Access Token: JWT(짧은 TTL)
- Refresh Token: 서버 저장(Redis/DB) + rotation + revoke 가능
- **MFA**: Seller/Admin 기본(특히 정산계좌 변경/대량 작업 시 강제 step-up)
- 로그인 보호: rate limit + 계정 락(옵션) + 새 디바이스/지역 탐지(옵션)

## 3.4 키/시크릿 관리
- 시크릿은 코드/이미지에 포함 금지, K8s Secret/External Secret로 주입
- **키 로테이션**: JWT 서명키/암호화키/외부 API 키를 버전 관리(구키/신키 공존 기간) + 폐기(runbook 포함)

---

# 4. 정보 유출(Exfiltration) 방지 설계

## 4.1 위협 시나리오와 통제
- 계정 탈취: MFA, 세션 revoke, 로그인 보호(credential stuffing)
- 수평 권한 상승(IDOR): seller scope 강제(where seller_id), 리소스 소유권 검증
- 스크래핑/대량 수집: 다층 레이트리밋, 페이지네이션 강제, 응답 최소화
- 내부자/운영자 export: 기본 마스킹, 사유/승인/step-up, 만료 링크, 횟수 제한, 워터마크, 감사로그
- 취약점 기반 유출: 입력 검증/출력 인코딩/CSP, CI 취약점 스캔 게이트

## 4.2 Export 통제(필수)
- 기본값: 마스킹된 뷰 제공(PII 최소)
- export는 **권한 + 사유 + 승인** + step-up
- 결과물: 만료(짧게), 다운로드 횟수 제한, 워터마크(요청자/시간/작업ID)
- 알림: 대량 export 시 즉시 경보 + 런북 링크

---


## 4.3 Runtime Security(선택)
- 컨테이너 런타임 이상 행위 탐지(Falco 등): 비정상 프로세스 실행, 권한 상승 시도, 의심 네트워크 행위
- 탐지 이벤트는 ELK/알림 채널로 연동하고, 즉시 차단/격리 절차는 런북으로 정의


# 4A. 웹 취약점 진단(AppSec SDLC) 및 공격 로그 분석

> Burp Suite를 필수 도구로 채택하지 않더라도, **동일한 수준의 진단 강도**를 목표로 한다.  
> 핵심은 “도구”가 아니라 **릴리스 게이트(자동화) + 침투형 회귀 테스트(권한/세션) + 운영 로그 탐지(ELK)**를 결합하는 것이다.

## 4A.1 진단 목표(프로젝트 특화)
- 입점형 핵심 리스크인 **테넌시 격리(IDOR)**, **세션/리보크**, **Admin 고위험 작업(export/정산/제재/강제환불)**을 최우선으로 검증한다.
- 입력 기반 취약점(XSS/SQLi/Traversal/SSRF), 파일 업로드 악성 시나리오, 레이트리밋/브루트포스를 표준 테스트 범주로 포함한다.

## 4A.2 CI 기본 게이트(항상 실행)
- **SAST**: Semgrep 규칙 기반 점검(금지 패턴/취약 패턴)
- **SCA/SBOM**: SBOM 생성 + Trivy 취약점 스캔(기준 초과 시 실패)
- **Secret Scan**: Gitleaks(토큰/키/비밀정보 커밋 차단)
- 결과는 Jenkins 파이프라인 리포트로 축적하고, 예외 허용은 “사유/승인/만료”를 필수로 한다.

## 4A.3 DAST(스테이징 기반 동적 진단)
- **OWASP ZAP**을 사용하여 스테이징 환경에서 자동 스파이더/액티브 스캔을 수행한다.
- 스캔은 Buyer/Seller/Admin 각각 로그인 세션을 확보한 뒤, 아래 “핵심 경로”를 타깃으로 실행한다.
  - Buyer: 로그인/회원가입/결제/클레임/리뷰·Q&A
  - Seller: 주문 조회/송장 등록/고객정보 조회/정산 리포트
  - Admin: export/정산 정책 변경/제재/강제 환불
- 취약점 발견 시 **재현 가능한 PoC + 영향 범위 + 수정 PR + 회귀 테스트 추가**를 표준 산출물로 남긴다.

## 4A.4 침투형 회귀 테스트(권한/테넌시/세션: Burp급 효용의 핵심)
- 자동화 테스트로 “절대 깨지면 안 되는 규칙”을 CI에서 강제한다.
  - Seller A 토큰으로 Seller B 리소스(주문/정산/고객) 접근 → **항상 403**
  - Admin export/정산 변경/제재/강제 환불 → step-up 없이 **항상 401/403**
  - refresh token 재사용 → **항상 무효화**, revoke 후 즉시 차단
- 구현 수단: JUnit/RestAssured 또는 k6 시나리오 테스트 중 택1(또는 병행)

## 4A.5 운영 환경 공격 로그 분석(ELK)
- 수집 소스:
  - Ingress/Nginx access log(필수), Application access log(필수)
  - (선택) WAF/NGFW threat/traffic 로그, Runtime Security(Falco) 이벤트
- 탐지 범주(예):
  - SQLi/XSS/Traversal/LFI/RCE 시그니처 패턴
  - 고속 스캐닝: 짧은 시간 내 다수 404/403, 다양한 path 탐색(/admin, /.git, /actuator 등)
  - 비정상 UA/헤더, 파라미터 길이 급증, URL 인코딩 난사, Base64 payload 빈도 급증
- 대시보드:
  - Top 공격 IP/ASN, Top 공격 URI, Top payload 키워드, 4xx/5xx 스파이크, WAF/NGFW 차단률(선택)
- 대응 런북:
  - 레이트리밋 강화 → 정책/WAF 룰 강화 → 단기 차단 → 계정 보호 조치(revoke/잠금) → 영향 범위 산정(export/대량조회 여부)


# 5. 도메인 플로우(운영 현실)

## 5.1 Seller Onboarding & 제재
- 신청 → 심사(승인/반려/보류) → 활성화
- 제재: 임시정지/영구정지/정산 보류(리스크/분쟁)

## 5.2 상품 심사/노출
- 상태: PENDING → APPROVED(노출) / REJECTED / HIDDEN(운영자 강제)
- 반려 사유 템플릿 + 재심사 가능
- ES 노출은 APPROVED만(동기화 지연 허용치 명시)

## 5.3 주문/결제 상태 머신
- 정상: CREATED → PAYMENT_PENDING → PAID → PACKING → SHIPPED → DELIVERED
- 예외: CANCEL_REQUESTED/CANCELLED, RETURN_REQUESTED/RETURNED/REFUNDED, EXCHANGE_REQUESTED/EXCHANGED
- 상태 전이는 권한과 정책으로 제한, 전이 이벤트는 감사/추적 대상

## 5.4 재고/동시성(oversell 방지)
- 재고는 “예약(결제대기)”과 “확정(결제완료)” 정책을 명시
- 원자성: DB 낙관락/비관락 또는 Redis 원자 연산(선택) + 실패 시 재시도 정책
- 결제 실패/타임아웃 시 예약 재고 복원

## 5.5 클레임(취소/반품/교환) & 분쟁
- BUYER 생성 → SELLER 응대 → ADMIN 분쟁/강제 환불/정책 예외
- 환불/취소는 Idempotency 필수(중복 요청/재시도 대비)

## 5.6 정산(Settlement)
- 수수료 정책(버전): 카테고리/등급별 수수료, 변경 이력/적용 시점 기록
- 정산 주기: 주/월, 지급 보류(리스크/반품 대기/분쟁)
- SELLER: 예상/확정/보류 분리 표기, 정산 캘린더 제공
- 재계산(리컴퓨트): 정책 변경/오류 발견 시 영향 범위 통제 후 재산출 가능

---

# 6. 검색/개인화/A·B 테스트

## 6.1 검색(Elasticsearch)
- 기본: 텍스트 검색 + 필터(카테고리/가격/배송) + 정렬
- 품질: synonym/오타 허용(간단), 인기/전환 기반 부스팅
- 인덱스 전략: index+alias 스위치로 무중단 재색인, snapshot 기반 복구

## 6.2 개인화/랭킹
- 실시간: 트렌드/최근 행동 기반 랭킹(Redis)
- 배치: Spark 후보 생성 + 세그먼트 + 리포트, 서빙 반영(ES/DB/Redis)

## 6.3 A/B 테스트
- 실험 단위: 사용자 또는 세션(고정), 실험 노출 로그 필수
- 지표: CTR/전환/주문/GMV, 유의성(간단한 통계 또는 최소 표본 규칙)
- 롤백: 실험이 악화되면 즉시 중단(피처 플래그)

---

# 7. Seller Center KPI & 성장 도구

## 7.1 KPI(필수)
- 매출/GMV: 일/주/월, 상품/카테고리별 Top N
- 전환: 방문→장바구니→구매(가능 범위)
- 주문 처리: 평균 출고 시간, 배송 지연율, 취소율
- 클레임 품질: 반품율/환불율/분쟁율, 사유 분포
- 상품 성과: 노출/클릭/CTR, 검색 유입 키워드, 리뷰 평점
- 정산: 예상/확정/보류, 수수료, 정산 캘린더

## 7.2 성장 도구(지표→행동)
- 출고 지연 상승 시: SLA 경고 + 원인(재고/인력/택배) 체크리스트
- CTR 낮음: 키워드/이미지/가격 포지셔닝 개선 가이드(룰 기반)
- 반품율 높음: 상세설명/사이즈/품질 안내 템플릿 제안

---

# 8. Fraud/어뷰징 대응

## 8.1 리스크 시그널(룰 기반)
- 신규 계정 단기간 고액 주문 반복, 환불 반복
- 동일 IP/디바이스 다계정 행위(선택)
- 쿠폰 남용(한 사용자/기기 다수 계정)

## 8.2 대응
- 리스크 점수(Redis/DB) 누적 → 주문 보류/추가 인증/정산 보류/계정 제한
- 모든 조치/해제는 감사로그 대상

---

# 9. 데이터/이벤트/파이프라인

## 9.1 이벤트 스키마(버전)
- event_version, event_id, actor_type, actor_id, seller_id(optional), request_id/trace_id, ts
- 이벤트 타입: CLICK/SEARCH/ADD_CART/PURCHASE + ADMIN_ACTION + SELLER_ACTION

## 9.2 수집/적재
- Online: Event Collector → Redis 실시간 집계/피처 → HDFS 원본 적재
- HDFS 경로: `/events/dt=YYYY-MM-DD/hour=HH/part-*.parquet`
- 원본은 불변(append-only), 재처리 가능

## 9.3 Spark 배치(최소)
- Seller KPI Daily(판매자별 매출/주문/클레임/SLA)
- Funnel(전체+판매자별)
- 추천 후보 생성(상호작용 집계)
- Fraud 리포트(룰 집계)
- (선택) 수요 예측(간단)

## 9.4 데이터 품질 게이트
- 스키마 변경/필드 누락/타입 변경 감지
- 이벤트 수 급감/급증 알림
- 핵심 KPI(주문/매출) 0/비정상 값 알림

## 9.5 데이터 카탈로그 & 라인리지
- OpenMetadata 또는 DataHub 중 택1로 데이터 자산(테이블/파이프라인/대시보드)을 카탈로그화
- KPI/리포트 산식, 원천 파티션, 잡 버전(run_id), 산출물 버전 및 품질 게이트 결과를 연결
- 운영자는 “이 KPI는 어디서 왔나”를 클릭 한 번으로 추적 가능해야 함

## 9.6 온라인-오프라인 피처 일관성(Feature Store 라이트)(Feature Store 라이트)
- 배치 피처(정확) + 실시간 보정 피처(최근 행동)
- 피처 버전/TTL/소스 명시, 모델/룰이 어느 피처를 쓰는지 기록

---

# 10. Redis 설계(캐시 + 정합성)

- 트렌드 TopK: `trend:*`
- 사용자/판매자 단기 피처: `user:feature:*`, `seller:feature:*`
- 랭킹 캐시: `ranking:*`
- 레이트리밋/쿼터: `rl:*` (정책 테이블 기반)
- Idempotency: `order:idem:*`, `refund:idem:*`
- 리스크 점수: `risk:user:*`, `risk:seller:*` (선택)

---

# 11. 관측/운영 (ELK + OpenTelemetry + 런북)

## 11.1 로그 표준(구조화 JSON)
- 필수: service, env, request_id, trace_id, actor_type/id, seller_id(optional), path, status, latency_ms, error_code
- 금지: 토큰/비밀키/PII 원문

## 11.2 Tracing(OpenTelemetry)
- 서비스는 OpenTelemetry instrumentation → OTel Collector로 수집
- 핵심 트랜잭션(주문/결제/환불/정산)에 trace 수집, 지연 원인을 다운스트림(DB/ES/Redis/외부)로 분해

## 11.3 대시보드/알림(예)
- 결제 실패율(코드별), 환불 실패율
- seller SLA: 출고 지연율/배송 지연율
- Admin 고위험 작업: 환불 강제/정산 변경/제재/export
- exfiltration 징후: 대량 조회/다운로드, 로그인 실패 급증
- p95/p99 지연 + 에러율 조합 알림

## 11.4 런북(필수)
- 결제 장애, Redis latency 상승, ES 인덱스 이상, 데이터 유출 의심, 재고 oversell, 정산 오류
- 각 런북: 대응 절차 + 대시보드 링크 + 로그 쿼리 템플릿 + 롤백/리보크 절차

## 11.5 게임데이
- Redis 느려짐/ES 다운/DB 지연/배치 실패를 주입하고 degrade/fallback이 동작하는지 검증

# 12. 배포/DevOps (무중단 + GitOps)

## 12.1 CI(Jenkins)
- Lint/Format → Unit → Integration(Testcontainers) → Build Image → SBOM/취약점 스캔 → Push

## 12.2 CD(GitOps)
- 배포 선언(Helm/Kustomize)을 Git에 두고 Argo CD로 동기화
- 승인/롤백/이력 관리가 Git 기반으로 남는다.

## 12.3 Progressive Delivery(Argo Rollouts)
- 카나리/블루그린을 릴리스 표준으로 운영(주문/결제는 보수적, 검색/추천은 빠른 실험)
- 메트릭 기준 자동 승급/자동 롤백(에러율, p95, 결제 실패율 등)

## 12.4 무중단 배포(Zero-downtime)(Zero-downtime)
- RollingUpdate 기본 + 핵심 서비스 Canary
- readiness/liveness/startup probe 필수
- Graceful shutdown(SIGTERM) + drain + terminationGracePeriodSeconds
- 실패 시 자동 롤백(지표 기반)

## 12.5 DB 무중단 마이그레이션
- Expand → Migrate → Contract 원칙
- 파괴적 변경(즉시 drop/rename) 금지
- 마이그레이션은 배포 파이프라인과 분리/통제

## 12.6 ES 무중단 재색인
- 새 인덱스 생성 → reindex/backfill → alias 스위치 → 롤백 가능

---

# 13. 데이터 복원/롤백/리보크(운영 요구사항)

## 13.1 롤백 분리
- **Release rollback**: 배포 버전 되돌림
- **Data recovery/backout**: 데이터 오류 복원(백업/PITR/보정 트랜잭션)

## 13.2 보정 트랜잭션(Compensation)
- 주문/결제/정산은 “되돌리기”보다 “보정”이 핵심
- 상태 전이 로그/이벤트로 추적 가능해야 함

## 13.3 백업/복구
- RDBMS: 스냅샷 + WAL/바이너리 로그로 PITR 가능
- ES: snapshot/restore
- HDFS/Spark: 불변 원본 + 재처리, 산출물 버전/포인터 스위치

## 13.4 복구 리허설(RTO/RPO)
- 정기 복구 훈련을 통해 RTO/RPO를 측정/기록
- 우선순위: 주문/정산 > 상품/검색 > 캐시

## 13.5 Revocation(철회)
- 토큰/세션 revoke
- 데이터 접근 revoke(export 만료/승인 취소)
- 비즈니스 오브젝트 철회(상품 숨김/정산 보류/강제 취소)

---

# 14. 개발자 플랫폼/거버넌스(Backstage + Policy-as-code)

## 14.1 IDP(Backstage)
- 서비스 카탈로그: owner/oncall, 문서, SLO, 대시보드, 런북 링크를 표준 메타데이터로 관리
- 템플릿: 신규 서비스 생성 시 기본 보안/관측/배포(Helm/Argo) 스캐폴딩을 자동 생성
- 운영 연결: 서비스 페이지에서 로그/트레이스/대시보드/알림/런북으로 즉시 이동 가능

## 14.2 Policy-as-code(Kyverno)
- 배포 정책을 코드로 강제(예: privileged 금지, 이미지 레지스트리 제한, resource limit 필수, 네임스페이스 격리)
- 위반 시 배포 차단 + 감사 로그 기록
- 권한/테넌시 규칙은 애플리케이션 테스트(권한 회귀 방지)와 함께 CI에서 자동 검증

## 14.3 Supply-chain 보안(Trivy/SBOM/서명)
- Jenkins에서 SBOM 생성 → 취약점 스캔(Trivy) → 기준 초과 시 빌드/배포 차단
- (선택) 이미지 서명(cosign) 및 정책 기반 검증으로 무단 이미지 배포 차단

# 15. 레포지토리 구조(권장)

```
pulsecommerce/
  docs/        # 아키텍처, RBAC, 상태머신, KPI 산식, SLO, 런북, DR/복구 리허설
  infra/       # helm/k8s, gitops, elk, tracing, redis, hadoop/spark
  backend/     # spring services (seller/catalog/order/claim/settlement/reco/event)
  frontend/    # react (buyer, seller-center, admin-console)
  pipelines/   # jenkins, scripts, test policies
```
