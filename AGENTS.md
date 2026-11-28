# 프로젝트 개발 기준

Java 빌더를 변수에 대입할 때는 빌더 생성 호출 뒤에서 줄을 바꾼다. 이후 메서드는 `.build()`까지 한 줄에 하나씩 쓰고, 각 줄의 점을 대입식 오른쪽의 빌더 타입 첫 글자에 맞춘다.

Java 17, Ant, TX24_LIB와 SharedMap을 사용한다. 원본 T8과 TX24_EXT는 읽기 전용이다. 런타임 설정은 보호된 conf/inet.json.property에 둔다. 연결 검증에는 ai/tools/VerifyWalletStartup.java 또는 ai/tools/verify-isolated.ps1을 사용한다. 실제 송금이나 운영 마감을 자동 검증에서 실행하지 않는다. 원장 쓰기는 이 프로젝트가 담당한다. 고객사 요청과 본사 화면 요청 모두 WX_API의 DB 트랜잭션에서 확정된다. 원장 코드는 `src/kr/tx24/wx/ledger` 아래 `rps`(DB 접근), `utils`(공통 처리), `cst`(상수), `enm`(업무 코드), `ex`(예외)에 둔다. 업무 계층은 WalletLedgerRps만 호출한다. kr.tx24.wx 아래 ledger 패키지는 WX_TASK·WX_ADM 과 글자 그대로 같은 사본이므로 한쪽만 고치지 않는다. 내부 구간 INet 수신 계약은 받는 쪽이 WX_API 하나뿐이라 kr.tx24.wx.api.utils.INetUtils 에 둔다. 그 전문 모양을 고치면 WX_ADM·WX_TASK 의 송신부도 같이 고친다. 원장의 거절 코드와 예외는 `ledger.enm.LedgerCode`와 `ledger.ex.LedgerEx`를 사용한다. WX_ADM 의 ResultCode·AppEx 는 화면 전용이므로 가져오지 않는다. 고칠 때 다른 프로젝트의 같은 파일도 같이 바꾸고 diff 로 확인한다. 이 프로젝트만의 코드는 kr.tx24.wx.api 아래에 둔다. 본사 화면은 /api/internal/* 로 들어온다. WX_ADM을 호출하지 않는다.

연동·검증 기준은 WX_ADM의 doc/wx-integration.md를 따른다.

SLF4J로 로그를 남기는 클래스는 클래스 상단에 `private static final Logger logger = LoggerFactory.getLogger(클래스명.class);`를 선언하고 모든 로그 호출에 이 필드를 사용한다.

- `logger.warn`은 정상 처리 범위에서 관리자가 확인해야 하는 운영 상황에 사용한다.
- `logger.error`는 관리자가 즉시 확인하고 조치해야 하는 긴급 상황에 사용한다.
- `logger.info`는 일반적인 요청 처리, 업무 결과, 정책상 거절과 재시도 내역에 사용한다.
- `logger.debug`는 개발 중 원인 분석에 필요한 처리 단계, 내부 값과 예외 상세에 사용한다.

Map 타입의 변수와 매개변수 이름은 `Map`, List 타입은 `List`로 끝낸다. 예를 들어 `acceptedMap`, `transferList`로 작성한다.

날짜·시간의 조회, 변환, 포맷에는 TX24_LIB의 `DateUtils`를 우선 사용한다. JVM과 데이터베이스의 기본 업무 시간대를 사용하며, 원장 거래일과 기록 시각은 같은 DB 시각에서 산정한다. 엄격한 전문·요청 날짜 검증과 경과 시간 측정에는 해당 동작을 보장하는 API를 사용한다.

검색 폼의 첫 번째 날짜 조건은 업무 의미에 맞는 `YYYYMMDD` 정수형 일자 컬럼으로 조회한다. 등록일·접수일·수신일에는 `regDay`를 사용하고, 거래일·집계일은 해당 일자 컬럼을 사용한다. 날짜 단독 조회를 지원하도록 해당 일자 컬럼으로 시작하는 인덱스를 확인한다. `regDate` 같은 Timestamp 컬럼은 시각 표시·정렬 및 시각 범위를 명시적으로 조회하는 조건에 사용한다.

NGINX와 TX24_HTTP가 전달한 단일 접속 IP는 WX의 MCHT_IP와 대조한다. HYPHEN 원본 패키지는 유지하며 시험 프로필에 연결하기 전 운영 요청을 닫는다. 고객사 웹훅은 이 프로세스가 보내지 않는다. `TRX_WEBHOOK` 에 적재하고 `WX:TASK:WEBHOOK` 큐에 id 를 올리면 WX_TASK 가 발송한다(OPL_API·OPL_TASK 와 같은 구조).

고객사 송금 계약은 `docs/index.html`과 `docs/wallet-database.openapi.json`에 기록한다. 등록 IP 확인 후 Authorization 키로 HMAC-SHA256을 검증한다. 서명에는 서버에서 식별한 Wallet ID와 모든 지급 관련 요청값을 사용한다. 키 원문, checksum, 전체 계좌번호를 내부 점검 요청이나 로그에 남기지 않는다.

소스가 아닌 자료는 ai/ 아래에 둔다. 문서는 ai/doc, 검증 도구는 ai/tools 다. conf·lib 는 런타임이 읽으므로 옮기지 않고 out 은 빌드 산출물이라 두지 않는다.
