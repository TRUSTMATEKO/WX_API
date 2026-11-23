# 프로젝트 개발 기준

Java 17, Ant, TX24_LIB와 SharedMap을 사용한다. 원본 T8과 TX24_EXT는 읽기 전용이다. 런타임 설정은 보호된 conf/inet.json.property에 둔다. 연결 검증에는 --check 또는 WX_API의 ai/tools/verify-isolated.ps1을 사용한다. 실제 송금이나 운영 마감을 자동 검증에서 실행하지 않는다. 원장 쓰기는 이 프로젝트가 담당한다. 고객사 요청과 본사 화면 요청 모두 WX_API의 DB 트랜잭션에서 확정된다. 원장 코드는 src/kr/tx24/wx/ledger 에 있고 업무 계층은 WalletLedgerRps 만 호출한다. kr.tx24.wx 아래 ledger 패키지는 WX_TASK·WX_ADM 과 글자 그대로 같은 사본이므로 한쪽만 고치지 않는다. 내부 구간 INet 수신 계약은 받는 쪽이 WX_API 하나뿐이라 kr.tx24.wx.api.utils.INetUtils 에 둔다. 그 전문 모양을 고치면 WX_ADM·WX_TASK 의 송신부도 같이 고친다. 원장의 거절 코드와 예외는 그 패키지 안의 LedgerCode·LedgerEx 다. WX_ADM 의 ResultCode·AppEx 는 화면 전용이므로 가져오지 않는다. 고칠 때 다른 프로젝트의 같은 파일도 같이 바꾸고 diff 로 확인한다. 이 프로젝트만의 코드는 kr.tx24.wx.api 아래에 둔다. 본사 화면은 /api/internal/* 로 들어온다. WX_ADM을 호출하지 않는다.

연동·검증 기준은 WX_ADM의 doc/wx-integration.md를 따른다.

NGINX와 TX24_HTTP가 전달한 단일 접속 IP는 WX의 MCHT_IP와 대조한다. HYPHEN 원본 패키지는 유지하며 시험 프로필에 연결하기 전 운영 요청을 닫는다. 고객사 웹훅은 이 프로세스가 보내지 않는다. `TRX_WEBHOOK` 에 적재하고 `WX:TASK:WEBHOOK` 큐에 id 를 올리면 WX_TASK 가 발송한다(OPL_API·OPL_TASK 와 같은 구조).

송금의 공개 계약은 `docs/index.html`과 `docs/wallet-database.openapi.json`에 기록한다. 등록 IP 확인 후 Authorization 키로 HMAC-SHA256을 검증한다. 서명에는 서버에서 식별한 Wallet ID와 모든 지급 관련 요청값을 사용한다. 키 원문, checksum, 전체 계좌번호를 내부 점검 요청이나 로그에 남기지 않는다.

소스가 아닌 자료는 ai/ 아래에 둔다. 문서는 ai/doc, 검증 도구는 ai/tools 다. conf·lib 는 런타임이 읽으므로 옮기지 않고 out 은 빌드 산출물이라 두지 않는다.
