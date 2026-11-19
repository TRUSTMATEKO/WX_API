# 프로젝트 개발 기준

Java 17 / Ant / TX24_LIB SharedMap을 사용한다. 기존 T8과 TX24_EXT는 읽기 전용 참조다. 런타임 설정은 conf/inet.json.property에 둔다. 보호 키와 운영 자료는 Git에서 제외한다. API와 TASK는 원장을 직접 쓰지 않고 ADM의 내부 INet만 호출한다. README에 실행·시나리오·검증 범위를 기록한다. 변경 경로만 컴파일·검증한다. Codex와 Claude Code는 별도 Git 작업 폴더를 사용한다.

database 프로필의 REST는 WX 테스트 키와 직접 접속 주소를 ADM에서 확인한다. 인증키 원문·계좌번호는 내부 조회 요청에서 제외한다. 미설정 기관 호출은 TX503으로 끝낸다. API 계약은 docs, 공통 인계 프롬프트는 ../WX_ADM/doc/claude-review.md에 있다.

HYPHEN 아래 원본 16개는 TX24_EXT에서 그대로 이관한다. 원본과 차이가 필요한 업무 흐름은 WX 소스에서 처리한다. 기관 입출금은 시연 경로 및 자동 검증에서 실행하지 않는다.
