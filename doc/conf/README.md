# WX_API 설정 파일 (doc/conf)

이 폴더는 배포 서버 `conf/`에 들어갈 설정 한 벌이다. 작업기에서는 레포 루트의 `conf/`를 쓰며,
그 디렉터리는 `.gitignore`가 제외한다.

| 파일 | 용도 |
|------|------|
| `inet.json` | INet 서버 설정 |
| `db.json` | 원장 DB 접속 정보 |
| `code.json` | 은행 코드표 |
| `nlb.json` | 내부 호출 대상 주소. `__EXT_HOST__`·`__WX_API_HOST__`는 배포 시 채운다 |

기동은 `kr.tx24.inet.server.INetServer`가 `-DCONF`의 `inet.json`을 읽고 컨트롤러 적재 중 `WX.CONFIG.apiEnv`를 확인한다.
설정 파일이 없거나 기관 연동 환경이 유효하지 않으면 수신 포트를 열기 전에 종료한다.

## inet.json 서버 설정

`host`와 `port`는 INet 수신 주소이며 WX_ADM·WX_TASK의 `nlb.json`에 등록한 WX_API 주소와 일치해야 한다. `basePackage`는 컨트롤러를 검색할 `kr.tx24.wx.api.ctl`이다.

## 기동 설정과 운영 설정

다음 값은 WX_ADM의 **설정 → 시스템 설정**에서 관리한다.

| 설정 | 적용 대상 |
|---|---|
| 게이트웨이 허용 IP | 고객사 API를 전달하는 게이트웨이의 접속 주소. 고객사별 허용 IP는 Wallet에서 관리한다. |
| 내부 허용 IP | 내부 API 호출자의 접속 주소. 숫자 IP 또는 CIDR을 쉼표로 구분한다. |
| 장애 알림 수신자 | 장애 알림 SMS를 받을 전화번호를 쉼표로 구분한다. |
| 기관 연동 환경 | `test` 또는 `prod`. 변경 전 요청 유입을 중단하고 미결 거래를 정리한 뒤 모든 WX_API 인스턴스를 재기동한다. |
| 내부 연동 인증키 | WX_ADM과 WX_TASK가 WX_API 호출에 사용한다. 새 키 적용 후 모든 호출자를 확인하고 이전 키를 폐기한다. |

WX.CONFIG가 설정 원본이며 Redis에는 변경 버전을 저장한다.

WX_SVC 포털의 웹훅 재접수에는 JVM 시스템 속성 `portal.webhook.key`(WX_SVC와 같은 32자 이상 전용 키)와 `portal.allowedIps`(포털 서버의 접속 주소)를 사용한다. 허용 주소를 생략하면 `127.0.0.1`을 사용한다.

## 점검

배포 서버에서 점검할 때는 저장소의 `ai/tools/VerifyWalletStartup.java`를 아래 경로에 둔다.

```bash
java -DPROC=WX_API -DCONF=/home/tx24/WX_API/conf -cp WX_API.jar \
     ai/tools/VerifyWalletStartup.java
```

수신 포트를 열지 않고 서버 설정, 기관 연동 환경, 원장 연결과 업무 스키마를 확인한다.
