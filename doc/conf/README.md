# WX_API 설정 파일 (doc/conf)

이 폴더는 배포 서버 `conf/`에 들어갈 설정 한 벌이다. 작업기에서는 레포 루트의 `conf/`를 쓰며,
그 디렉터리는 `.gitignore`가 제외한다.

| 파일 | 용도 |
|------|------|
| `inet.json` | INet 서버 설정과 `property` (내부 키 포함) |
| `db.json` | 원장 DB 접속 정보 |
| `code.json` | 은행 코드표 |
| `nlb.json` | 내부 호출 대상 주소. `__EXT_HOST__`·`__WX_API_HOST__`는 배포 시 채운다 |

기동은 `kr.tx24.inet.server.INetServer`가 `-DCONF`의 `inet.json`을 읽어 시작한다. 파일이 없으면
`inet.json is not found` 한 줄을 남기고 **즉시 종료**한다. 배포 시 jar만 올리고 `conf/`를 빠뜨리는
일이 가장 흔한 기동 실패 원인이다.

## inet.json 최상위

| 키 | 용도 |
|----|------|
| `host`, `port` | INet 수신 주소. 호출자(WX_ADM·WX_TASK)의 `nlb.json` `WX_API` 그룹과 같아야 한다 |
| `basePackage` | `kr.tx24.wx.api.ctl` 고정. `Router`가 이 아래 `@Controller`를 적재한다 |

## inet.json `property` 에 입력해야 하는 키

비밀값은 코드에 두지 않고 `inet.json`의 `"property": { ... }`에 둔다. `WalletConfig`가 컨트롤러
적재 시점에 한 번 읽는다(OPL_API의 `CoreConstants`와 같은 자리). 값이 없으면 던지지 않고 해당
업무만 거절하므로, 기동 로그에서 아래 경고를 확인한다.

| 키 | 필수 | 용도 | 값이 비어 있을 때 |
|----|------|------|------------------|
| `enabled` | 필수 | `true`여야 업무 경로가 응답한다 | 모든 업무 경로를 거절하고 기동 로그에 `WX_API 가 비활성` |
| `internal_api.key` | 필수 | `/api/internal/**`가 확인하는 `x-wx-key` 공유 키. WX_ADM·WX_TASK·입금 통지 시스템이 같은 값을 쓴다. 32자 이상 | 내부 경로가 모두 `TX503`으로 거절된다 |
| `internal.allowedIps` | 선택 | 내부 경로의 허용 접속 주소. 쉼표 목록과 IPv4 CIDR | 루프백만 허용 |
| `gateway.allowedIps` | 선택 | 고객사 경로(`/api/v1/wallet/**`)를 보내는 TX24_HTTP 주소. 고객사 IP는 원장이 `MCHT_WALLET_API_KEY.ips`로 따로 대조한다 | 루프백만 허용 |
| `env` | 필수 | `test`면 HYPHEN과 통신하지 않고 더미 응답, `prod`면 실제 전문을 보낸다 | 송금 접수를 중지하고 조회 업무만 제공 |
| `alert.receiver` | 선택 | 미매칭 입금 SMS 수신자. 쉼표로 나누고 10명까지 | SMS를 보내지 않고 경고만 남긴다 |

허용 주소는 쉼표 목록과 `a.b.c.d/prefix` CIDR을 받는다. **IPv4만 판정한다** — `::1`로 들어오면
`127.0.0.1`을 허용해도 거절된다. 와일드카드(`192.168.1.*`)와 대시 범위는 지원하지 않으며 조용히
불일치 처리된다. 호스트 이름은 요청마다 DNS 조회가 일어나므로 쓰지 않는다.

## 점검

```bash
java -DPROC=WX_API -DCONF=/home/tx24/WX_API/conf -cp WX_API.jar \
     kr.tx24.wx.api.main.Launcher --check
```

수신 포트를 열지 않고 설정·원장 연결·업무 스키마만 확인한다. 운영 기동에는 쓰지 않는다.
