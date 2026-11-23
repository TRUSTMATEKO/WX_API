# WX_API

Java 17, Ant, TX24_LIB 및 INet을 사용한다.

구동은 OPL_API와 같다. 전용 Launcher 없이 범용 `kr.tx24.inet.server.INetServer`를 Main Class로 띄우고,
`Router`가 `basePackage`(`kr.tx24.wx.api.ctl`) 아래 `@Controller`를 적재한다. 업무 설정은 그 적재 시점에
`WalletConfig`가 `inet.json`의 `property`에서 한 번 읽는다(OPL_API의 `CoreConstants`와 같은 자리).
`kr.tx24.wx.api.main.Launcher`는 기동에 쓰지 않는 `--check` 전용 진단 도구다.

## 연결 설정

- `conf/inet.json`의 내부 경로는 `internal.allowedIps`와 `internal_api.key`로 받는다. WX_ADM·WX_TASK·입금 통지 시스템이 같은 키를 쓴다. 두 호출자는 이 `port`를 각자 `conf/nlb.json`의 `WX_API` 그룹에 둔다. Redis 주소는 실행 스크립트가 `-DREDIS`로 준다.
- `host`, `port`, `gateway.allowedIps`는 TX24_HTTP 게이트웨이의 실제 연결 범위에 맞춘다. `env`는 `test` 또는 `prod`다. 둘 중 하나가 아니면 송금을 접수하지 않고 조회만 제공한다.
- NGINX에서는 `proxy_set_header X-Forwarded-For $remote_addr;`로 값을 덮어쓰고 TX24_HTTP가 INet에 전달한다. TX24_HTTP 라우트가 authorization 헤더를 전달해야 한다. 이어진 IP 문자열은 거절한다. WX DB의 인증 키에 저장된 `MCHT_WALLET_API_KEY.ips`의 IPv4·IPv6 CIDR 규칙과 대조한다.
- 배포 전후 점검은 `java -DPROC=WX_API -DCONF=<conf> -cp WX_API.jar kr.tx24.wx.api.main.Launcher --check`로 한다. 수신 포트를 열지 않고 설정·원장 연결·업무 스키마만 확인한다.
- `ai/tools/verify-isolated.ps1`은 일회성 포트·키로 WX_API·WX_TASK의 원장 연결을 검증한다. 수신부는 TX24_HTTP가 보내는 INet 경로 하나다.

## 공개 Wallet 계약

- TX24_DOC 기준으로 Authorization 헤더에 wk_ 키를 넣는다. 키 소속과 등록 IP를 확인한 후 지정된 Wallet에서 처리한다.
- balance는 빈 JSON 객체를 받는다. inquiry는 trackId와 서울 기준 완료일 또는 접수일 trxDay를 받는다.
- 공개 요청 본문의 walletId는 처리 범위에 사용하지 않는다. 인증이 완료된 업무 응답에는 연결 Wallet ID를 포함한다.
- 본사 고객사 조회의 Wallet API 연결 화면에서 기존 키를 해당 고객사의 API 사용 Wallet에 연결한다. 배포에는 WX_ADM의 ai/db/WX_SCHEMA.sql과 호환되는 업무 스키마가 필요하다.
- 송금은 Authorization 키와 등록 IP 확인 후, 연결 Wallet ID와 지급 관련 입력 전체로 계산한 HMAC-SHA256 `checksum`을 검증한다. 필드 순서와 예제는 Wallet API HTML의 송금 절에 있다.
- 업무 JSON은 `resultCd`, `resultMsg`(간략), `advanceMsg`(상세), `createAt`을 항상 제공한다. 송금과 원거래 조회는 원거래 `requestedAt`과 `createAt`, 마스킹 `accntNo` 및 같은 거래 필드를 사용한다.

## HYPHEN 가동 조건

TX24_EXT의 `hyphen` 원본 패키지를 사용한다. 기관 시험 환경·응답코드표와 원거래 재조회 계약이 확정될 때까지 운영 송금·예금주 조회에는 HTTP 200과 업무 코드 W003을 반환한다. 기관 연결을 열기 전 송신 전에 전문번호와 예약을 저장하고, 결과 불명은 원거래 재조회로 확정하며, 최종 원장 반영의 중복을 차단해야 한다.

- [Wallet API HTML](docs/index.html)
- [운영 조회 OpenAPI](docs/wallet-database.openapi.json)
- [TX24_HTTP 경로 예제](docs/tx24-http.routes.example.json)

