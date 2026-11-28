param([switch] $SkipCompile)

# WX_API의 원장 연결을 점검하고 임시 INet 서버의 수신 포트와 경로 적재를 확인한다.
# 실제 송금과 일별 마감은 실행하지 않는다.

$ErrorActionPreference = 'Stop'
$api  = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$SkipCompile) {
    # WX_API 는 루트 build.xml 이 없다. 배포 빌드(doc/bin/build.xml)는 서버 디렉터리 배치를 전제로
    # 하므로 작업기에서는 javac 로 같은 out/classes 를 만든다.
    $classes = Join-Path $api 'out/classes'
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    $sources = Get-ChildItem -Path (Join-Path $api 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    & javac --release 17 -parameters -encoding UTF-8 -cp "$api/lib/*" -d $classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'WX_API 컴파일 실패' }
}
function LocalPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
function Save-Config($Root, $Config) {
    New-Item -ItemType Directory -Force -Path $Root | Out-Null
    $Config | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 (Join-Path $Root 'inet.json')
}
function Wait-Listener($Port, $Process) {
    foreach ($attempt in 1..80) {
        if ($Process.HasExited) { throw "대상 프로세스가 종료되었습니다. Port=$Port" }
        $socket = [Net.Sockets.TcpClient]::new()
        try {
            if ($socket.ConnectAsync('127.0.0.1',$Port).Wait(250)) { return }
        } catch {
            Start-Sleep -Milliseconds 200
        } finally {
            $socket.Dispose()
        }
    }
    throw "로컬 포트 $Port 시작 실패"
}
function Start-Java($Name, $JavaArgs) {
    $dir = Join-Path $output $Name
    $argsList = @($JavaArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
    $start = @{
        FilePath=(Get-Command java.exe).Source; ArgumentList=$argsList; WorkingDirectory=$dir; WindowStyle='Hidden'; PassThru=$true
        RedirectStandardOutput=(Join-Path $dir 'stdout.log'); RedirectStandardError=(Join-Path $dir 'stderr.log')
    }
    Start-Process @start
}
$apiPort = LocalPort
$output = Join-Path $api ('out/integration/' + [guid]::NewGuid().ToString('N'))
Save-Config (Join-Path $output 'api') @{host='127.0.0.1'; port=$apiPort; basePackage='kr.tx24.wx.api.ctl'}

$apiArgs = @('-DPROC=WX_API',"-DCONF=$(Join-Path $output 'api')","-DDBSET=$(Join-Path $api 'conf/db.json')",
             '-Dfile.encoding=UTF-8','-Duser.timezone=Asia/Seoul','-cp',"$api/out/classes;$api/lib/*")
$apiCheck = $apiArgs + (Join-Path $PSScriptRoot 'VerifyWalletStartup.java')
$apiServer = $apiArgs + 'kr.tx24.inet.server.INetServer'
$processes = @()
try {
    # 수신 포트를 열지 않고 기관 환경과 원장 DB 연결 및 업무 스키마를 확인한다.
    & java @apiCheck
    if ($LASTEXITCODE -ne 0) { throw 'WX_API 원장 연결 진단 실패' }

    # INet 서버가 기관 환경을 확인한 뒤 수신을 시작하는지 확인한다.
    $gateway = Start-Java 'api' $apiServer
    $processes += $gateway
    Wait-Listener $apiPort $gateway
    Write-Output "PASS INet 수신 포트 개방 127.0.0.1:$apiPort"

    $stdout = Join-Path $output 'api/stdout.log'
    $routes = $null
    foreach ($attempt in 1..40) {
        if (Test-Path $stdout) { $routes = Select-String -Path $stdout -Pattern 'Registered (\d+) routes' | Select-Object -First 1 }
        if ($routes) { break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $routes) { throw '경로 적재 로그를 찾지 못했습니다.' }
    Write-Output "PASS $($routes.Matches[0].Value)"
    if (-not (Select-String -Path $stdout -Pattern 'WalletConfig')) { throw '실행 설정이 기동 시점에 확정되지 않았습니다.' }
    Write-Output 'PASS 수신 전에 실행 설정 확인 완료'
    Write-Output 'PASS WX_API isolated startup'

} finally {
    foreach ($child in $processes) {
        if (!$child.HasExited) { Stop-Process -Id $child.Id -Force }
    }
}
