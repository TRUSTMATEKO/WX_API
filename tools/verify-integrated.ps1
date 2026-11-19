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
    $ports = @{adm=(LocalPort); api=(LocalPort); web=(LocalPort)}
} until (($ports.Values | Sort-Object -Unique).Count -eq 3)
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
function Config($Directory, $Value) {
    $Value | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 (Join-Path $Directory 'conf/inet.json')
}
Config $testAdm @{host='127.0.0.1'; port=$ports.adm; property=@{enabled=$true; 'payout.key'=$payoutKey; 'task.key'=$taskKey}}
Config $testApi @{host='127.0.0.1'; port=$ports.api; property=@{enabled=$true; 'client.company'='M001'; 'client.key'=$clientKey; 'adm.port'=$ports.adm; 'adm.key'=$payoutKey}}
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
    $apiProcess = StartWx $api $testApi 'kr.tx24.wx.api.main.Launcher'
    $processes += $apiProcess
    WaitLocal $ports.api $apiProcess
    java -cp "$api/out/classes;$api/lib/*" (Join-Path $PSScriptRoot 'VerifyPayout.java') $ports.api $clientKey
    if ($LASTEXITCODE -ne 0) { throw 'API 지급 연계 실패' }
    Push-Location $testTask
    try {
        java -cp "$task/out/classes;$task/lib/*" kr.tx24.wx.task.Launcher --once
        if ($LASTEXITCODE -ne 0) { throw 'TASK 일별 집계 연계 실패' }
    } finally { Pop-Location }
    $state = Get-Content (Join-Path $testAdm 'data/demo.json') -Raw | ConvertFrom-Json
    if ($state.transfers.Count -ne 1 -or $state.bank -ne 30149000) { throw '통합 원장 입증 실패' }
    Write-Output 'PASS API and TASK use the ADM ledger'
} catch {
    Get-ChildItem $dir -Filter '*.log' -Recurse | ForEach-Object { Get-Content $_.FullName -Tail 15 }
    throw
} finally {
    foreach ($process in $processes) {
        if (!$process.HasExited) { Stop-Process -Id $process.Id }
    }
}
