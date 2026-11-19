# WX_API

Java 17 / Ant. T8_API에서 INet Router, 설정 로더, 제한된 전문 수신과 ctl → svc 구조를 참조했다. `ant compile`로 컴파일한다. `conf/inet.example.json`을 `conf/inet.json`으로 복사해 enabled와 키를 설정한 후 `ant run`으로 로컬 시연 수신을 시작한다.

## ADM 연계

WX_ADM의 `conf/inet.json`을 활성화하고 `payout.key`를 API의 `adm.key`와 일치시킨다. 별도 `client.key`를 사용한다. 키는 서로 다르고 각각 32자 이상이어야 한다. 서버 설정의 `client.company`로 고객사 범위를 고정한다.

`/api/internal/demo/payout`은 보호된 INet으로만 제공한다. 요청 JSON은 requestId, walletId, amount, recipient, outcome(success/failed/unknown)을 받는다. ADM이 소유권, API 운영시간, 잔액, 멱등성, 원장을 검증한다. 로컬 시연 포트는 기본 18098이다.

## HYPHEN

`TX24_EXT/src/kr/tx24/ext/hyphen`의 Java 16개를 원본 바이트 그대로 복사했다. 컴파일 의존성인 `ExtCode.java`도 별도 편입했다. `HyphenSvc`는 원본 INet 계약과 보호 프로필로 `HyphenPisp.transfer`·`balance` 및 원거래 번호를 받는 `inquery`를 감싼다. 시연 라우트는 가상 송금만 수행한다.

기관 송금 가동 전에는 보호된 기관 프로필·응답코드 자료·별도 Redis 계약, 접수 및 원장 예약, 원거래 일자·전문번호의 송신 전 영속화, 기관 결과 재조회와 운영 코드 매핑이 필요하다. 원본은 전역 Redis 순번과 비원자적 상한 리셋을 포함한다. 운영 동시성 및 중복 처리 검증이 필요하다. 실제 기관 접속과 송금은 검증 범위에서 제외한다.

다른 두 프로젝트가 같은 상위 폴더에 있을 때 PowerShell 7로 ./tools/verify-integrated.ps1을 실행하면 세 프로젝트를 임시 설정·포트로 검증한다.

## Wallet REST 규격

- [Wallet API HTML 문서](docs/index.html)
- [OpenAPI 3.1 JSON](docs/wallet-rest.openapi.json)

개발 WX REST는 API 전용 conf/inet.json의 property에 profile=database, enabled=true, rest.port, adm.port, adm.key를 설정해 실행한다. ADM은 database 프로필에서 -InternalConfig로 기동하며 payout.key에 API의 adm.key를 지정한다. TX24_LIB는 실행 폴더의 conf를 읽는다. 명시하려면 JVM에 -DCONF를 지정한다. 이 구성은 로컬 IPv4 직접 연결, 활성 test 키와 고객사·키의 등록 CIDR을 사용한다. 잔액 및 송금 장부 조회 계약은 docs/wallet-database.openapi.json이다. HYPHEN 시험 프로필과 응답코드 수령 전 송금·예금주 조회는 인증 후 503으로 끝난다.

로컬 REST는 rest.port 및 REST 전용 rest.key로 활성화한다. POST /api/v1/wallet/transfer와 /api/v1/wallet/holder가 같은 고객사 원장으로 연결된다. 신규 송금은 HUB_ADM과 동일하게 IDUtils.getUnique("T")로 체번한다. 샘플 통합 실행은 ../WX_ADM/tools/demo-stack.ps1, 세 프로젝트의 검증 인계는 ../WX_ADM/doc/claude-review.md를 사용한다.
