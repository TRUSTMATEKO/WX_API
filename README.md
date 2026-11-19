# WX_API

Java 17 / Ant. T8_API의 설정 로더와 ctl → svc 구조를 참조했다. `conf/inet.example.json`을 `conf/inet.json`으로 복사하고 enabled, rest.port, adm.port, adm.key를 설정한 뒤 `ant run`으로 시작한다. 기본 프로필은 database다.

## ADM 연계

ADM은 database 프로필로 기동하고 payout.key에 API의 adm.key를 지정한다. REST는 로컬 IPv4 직접 연결과 고객사·키의 등록 CIDR을 검증한다. ADM의 merchant.environment 기본값은 live이며 test 지정 시 테스트 키만 승인한다. 문의·잔액 조회는 고객사 범위를 적용한다.

## HYPHEN

`TX24_EXT/src/kr/tx24/ext/hyphen`의 Java 16개를 원본 바이트 그대로 복사했다. 컴파일 의존성인 `ExtCode.java`도 별도 편입했다. `HyphenSvc`는 원본 INet 계약과 보호 프로필로 `HyphenPisp.transfer`·`balance` 및 원거래 번호를 받는 `inquery`를 감싼다. 시연 라우트는 가상 송금만 수행한다.

기관 송금 가동 전에는 보호된 기관 프로필·응답코드 자료·별도 Redis 계약, 접수 및 원장 예약, 원거래 일자·전문번호의 송신 전 영속화, 기관 결과 재조회와 운영 코드 매핑이 필요하다. 원본은 전역 Redis 순번과 비원자적 상한 리셋을 포함한다. 운영 동시성 및 중복 처리 검증이 필요하다. 실제 기관 접속과 송금은 검증 범위에서 제외한다.

다른 두 프로젝트가 같은 상위 폴더에 있을 때 PowerShell 7로 ./tools/verify-integrated.ps1을 실행하면 세 프로젝트를 임시 설정·포트로 검증한다.

## Wallet REST 규격

- [Wallet API HTML 문서](docs/index.html)
- [OpenAPI 3.1 JSON](docs/wallet-rest.openapi.json)

TX24_LIB의 설정 디렉터리는 JVM -DCONF로 지정할 수 있다. 잔액 및 송금 장부 조회 계약은 docs/wallet-database.openapi.json이다. HYPHEN 시험 프로필과 응답코드 수령 전 송금·예금주 조회는 인증 후 503으로 끝난다.

가상 원장의 회귀 검증은 명시적으로 profile=demo를 지정하고 rest.port 및 REST 전용 rest.key를 사용한다. POST /api/v1/wallet/transfer와 /api/v1/wallet/holder가 같은 고객사 원장으로 연결된다. 신규 송금은 HUB_ADM과 동일하게 IDUtils.getUnique("T")로 체번한다. 샘플 통합 실행은 ../WX_ADM/tools/demo-stack.ps1, 세 프로젝트의 검증 인계는 ../WX_ADM/doc/claude-review.md를 사용한다.
