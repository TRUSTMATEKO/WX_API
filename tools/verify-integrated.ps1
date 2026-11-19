$ErrorActionPreference = 'Stop'
$api = (Resolve-Path (Split-Path -Parent $PSScriptRoot)).Path
$adm = (Resolve-Path (Join-Path $api '../WX_ADM')).Path
$task = (Resolve-Path (Join-Path $api '../WX_TASK')).Path
foreach ($project in $adm,$api,$task) {
    ant -f (Join-Path $project 'build.xml') compile
    if ($LASTEXITCODE -ne 0) { throw "컴파일 실패: $project" }
}
function LocalPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
do {
    $ports = @{adm=(LocalPort); api=(LocalPort); web=(LocalPort); rest=(LocalPort)}
} until (($ports.Values | Sort-Object -Unique).Count -eq 4)
$dir = Join-Path $api ('out/integration/' + [guid]::NewGuid())
$testApi = Join-Path $dir 'api'
$testAdm = Join-Path $dir 'adm'
$testTask = Join-Path $dir 'task'
foreach ($project in $testApi,$testAdm,$testTask) {
    New-Item -ItemType Directory -Force -Path (Join-Path $project 'conf') | Out-Null
}
$payoutKey = [guid]::NewGuid().ToString('N')
$taskKey = [guid]::NewGuid().ToString('N')
$clientKey = [guid]::NewGuid().ToString('N')
$restKey = [guid]::NewGuid().ToString('N')
function Config($Directory, $Value) {
    $Value | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 (Join-Path $Directory 'conf/inet.json')
}
Config $testAdm @{host='127.0.0.1'; port=$ports.adm; property=@{enabled=$true; 'payout.key'=$payoutKey; 'task.key'=$taskKey}}
Config $testApi @{host='127.0.0.1'; port=$ports.api; property=@{enabled=$true; 'client.company'='M001'; 'client.key'=$clientKey; 'rest.port'=$ports.rest; 'rest.key'=$restKey; 'adm.port'=$ports.adm; 'adm.key'=$payoutKey}}
Config $testTask @{host='127.0.0.1'; basePackage='kr.tx24.wx.task'; property=@{enabled=$true; 'adm.port'=$ports.adm; 'adm.key'=$taskKey}}
Copy-Item -LiteralPath (Join-Path $adm 'webroot') -Destination $testAdm -Recurse
function StartWx($Project, $Directory, $Main, $Options = @()) {
    $cp = '"' + $Project + '/out/classes;' + $Project + '/lib/*"'
    $args = $Options + @('-cp', $cp, $Main)
    Start-Process java -ArgumentList $args -WorkingDirectory $Directory -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $Directory 'stdout.log') -RedirectStandardError (Join-Path $Directory 'stderr.log')
}
function WaitLocal($Port, $Process) {
    foreach ($attempt in 1..25) {
        if ($Process.HasExited) { break }
        $socket = [System.Net.Sockets.TcpClient]::new()
        try {
            if (!$socket.ConnectAsync('127.0.0.1', $Port).Wait(250)) { continue }
            return
        } catch {
            Start-Sleep -Milliseconds 200
        } finally {
            $socket.Dispose()
        }
    }
    throw "로컬 포트 $Port 시작 실패"
}
$processes = @()
try {
    $admProcess = StartWx $adm $testAdm 'kr.tx24.wx.main.Launcher' @("-Dwx.port=$($ports.web)")
    $processes += $admProcess
    WaitLocal $ports.adm $admProcess
    $base = "http://127.0.0.1:$($ports.web)"
    $login = Invoke-WebRequest "$base/sign/in" -SessionVariable web
    $token = [regex]::Match($login.Content,'name="token" value="([^"]+)"').Groups[1].Value
    $signedInPage = Invoke-WebRequest "$base/sign/in" -WebSession $web -Method Post -Body @{actor='ADMIN';token=$token}
    $csrf = [regex]::Match($signedInPage.Content,'name="csrf-token" content="([^"]+)"').Groups[1].Value
    foreach ($body in @(
        @{action='routing';walletId='W001';bankId='B001';sender='DEMO'},
        @{action='deposit';sender='DEMO';amount=1000;externalId='INTEGRATION-IN'},
        @{action='policy';revision=1;start='23:50';end='00:10';enabled=$false;manualStop=$false;fee=305;holderFee=15}
    )) {
        $body.requestId = [guid]::NewGuid().ToString()
        Invoke-RestMethod "$base/api/action" -Method Post -WebSession $web -Headers @{'X-CSRF-Token'=$csrf} -ContentType 'application/json' -Body ($body | ConvertTo-Json) | Out-Null
    }
    $apiProcess = StartWx $api $testApi 'kr.tx24.wx.api.main.Launcher'
    $processes += $apiProcess
    WaitLocal $ports.api $apiProcess
    javac -encoding UTF-8 -cp "$api/out/classes;$api/lib/*" -d $dir (Join-Path $PSScriptRoot 'VerifyPayout.java')
    if ($LASTEXITCODE -ne 0) { throw 'API 검증 클라이언트 컴파일 실패' }
    java -cp "$dir;$api/out/classes;$api/lib/*" VerifyPayout $ports.api $clientKey
    if ($LASTEXITCODE -ne 0) { throw 'API 지급 연계 실패' }
    $restBase = "http://127.0.0.1:$($ports.rest)/api/v1/wallet"
    $headers = @{'X-WX-Key'=$restKey;'X-Trace-Id'='WXREST01'}
    $restBody = @{requestId='WX-API-INTEGRATION';walletId='W001';amount='1000';recipient='가상 수취인';outcome='success'} | ConvertTo-Json
    $restReply = Invoke-WebRequest "$restBase/transfer" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $restBody
    $restData = $restReply.Content | ConvertFrom-Json
    if (!$restData.data.replayed -or $restData.data.id -notmatch '^T[0-9]{11}[0-9A-F]{6}$' -or $restData.traceId -ne 'WXREST01') { throw 'REST 재접수·체번 오류' }
    $denied = Invoke-WebRequest "$restBase/transfer" -Method Post -ContentType 'application/json' -Body $restBody -SkipHttpErrorCheck
    if ($denied.StatusCode -ne 401) { throw 'REST 인증 검증 실패' }
    $holder = Invoke-RestMethod "$restBase/holder" -Method Post -Headers $headers -ContentType 'application/json' -Body '{"requestId":"WX-HOLDER-mismatch","walletId":"W001","outcome":"mismatch"}'
    if (!$holder.data.replayed -or $holder.data.outcome -ne 'mismatch') { throw 'REST 예금주 조회 검증 실패' }
    Write-Output 'PASS REST authentication, trace, HUB identifiers and HTTP/INet replay'
    Push-Location $testTask
    try {
        java -cp "$task/out/classes;$task/lib/*" kr.tx24.wx.task.Launcher --once
        if ($LASTEXITCODE -ne 0) { throw 'TASK 일별 집계 연계 실패' }
    } finally { Pop-Location }
    $state = Get-Content (Join-Path $testAdm 'data/demo.json') -Raw | ConvertFrom-Json
    if ($state.transfers.Count -ne 1 -or $state.banks[0].balance -ne 0 -or $state.holders.Count -ne 2 -or $state.fees.Count -ne 2) { throw '통합 원장 입증 실패' }
    $midnight = @{action='midnight'; requestId=[guid]::NewGuid().ToString()}
    Invoke-RestMethod "$base/api/action" -Method Post -WebSession $web -Headers @{'X-CSRF-Token'=$csrf} -ContentType 'application/json' -Body ($midnight | ConvertTo-Json) | Out-Null
    $state = Get-Content (Join-Path $testAdm 'data/demo.json') -Raw | ConvertFrom-Json
    if ($state.wallets[0].balance -ne -352 -or ($state.fees | Measure-Object vat -Sum).Sum -ne 32) { throw '고객사 단위 VAT 또는 음수 Wallet 차감 실패' }
    Write-Output 'PASS API and TASK use the ADM ledger; confirmed holder outcome and daily grouped VAT'
} catch {
    Get-ChildItem $dir -Filter '*.log' -Recurse | ForEach-Object { Get-Content $_.FullName -Tail 15 }
    throw
} finally {
    foreach ($process in $processes) {
        if (!$process.HasExited) { Stop-Process -Id $process.Id }
    }
}
