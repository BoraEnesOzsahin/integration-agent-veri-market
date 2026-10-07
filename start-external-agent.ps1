param(
    [Parameter(Mandatory=$false)]
    [string]$CoordinatorIp = "localhost",

    [Parameter(Mandatory=$false)]
    [int]$CoordinatorPort = 8081,

    [Parameter(Mandatory=$false)]
    [string]$MyIp = "",

    [Parameter(Mandatory=$false)]
    [string]$NodeId = "Banka_B",

    [Parameter(Mandatory=$false)]
    [string]$NodeName = "Banka B Harici Entegrasyon Ajanı",

    [Parameter(Mandatory=$false)]
    [int]$AgentPort = 8083,

    [Parameter(Mandatory=$false)]
    [int]$ManagerPort = 8093,

    [Parameter(Mandatory=$false)]
    [string]$SupportedDatasets = "bankalar,finans,kredi,consortium"
)

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "  AYROTEK VERİ MARKETİ - HARİCİ MAKİNE AJAN BAŞLATICI           " -ForegroundColor Cyan
Write-Host "=================================================================`n" -ForegroundColor Cyan

# 1. Kendi yerel IP adresini otomatik tespit et (Belirtilmemişse)
if ([string]::IsNullOrWhiteSpace($MyIp) -or $MyIp -eq "auto") {
    $MyIp = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { 
        $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" 
    } | Select-Object -First 1).IPAddress

    if ([string]::IsNullOrWhiteSpace($MyIp)) {
        $MyIp = "localhost"
    }
}

$CoordinatorUrl = "http://${CoordinatorIp}:${CoordinatorPort}"
$AgentPublicUrl = "http://${MyIp}:${AgentPort}"
$ManagerPublicUrl = "http://${MyIp}:${ManagerPort}"

Write-Host "  [BİLGİ] Bu Makinenin IP'si      : $MyIp" -ForegroundColor Green
Write-Host "  [BİLGİ] Kurum Kimliği (NodeId) : $NodeId ($NodeName)" -ForegroundColor Green
Write-Host "  [BİLGİ] Ajan İletişim URL'i    : $AgentPublicUrl" -ForegroundColor Green
Write-Host "  [BİLGİ] Manager İletişim URL'i : $ManagerPublicUrl" -ForegroundColor Green
Write-Host "  [BİLGİ] Hedef Coordinator      : $CoordinatorUrl" -ForegroundColor Yellow

# 2. Coordinator Erişilebilirlik Testi
Write-Host "`n>>> [ADIM 1] Coordinator Bağlantısı Test Ediliyor..." -ForegroundColor Cyan
try {
    $coordCheck = Invoke-RestMethod -Uri "$CoordinatorUrl/api/v1/mpc/orchestrator/health" -TimeoutSec 3 -ErrorAction Stop
    Write-Host "  [OK] Coordinator erişilebilir durumda! ($($coordCheck.service))" -ForegroundColor Green
} catch {
    Write-Host "  [UYARI] $CoordinatorUrl adresindeki Coordinator'a ulaşılamadı: $($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host "          (Coordinator henüz başlatılmamış olabilir veya Güvenlik Duvarı port $CoordinatorPort'i engelliyor olabilir.)" -ForegroundColor Gray
}

# 3. JAR Dosyalarını Kontrol Et ve Gerekirse Derle
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$AgentJar = Join-Path $ScriptDir "target\integration_agent_node-1.0.0-SNAPSHOT.jar"
$ManagerJar = Join-Path $ScriptDir "manager_service\target\manager_service-1.0.0-SNAPSHOT.jar"

Write-Host "`n>>> [ADIM 2] JAR Paketleri Kontrol Ediliyor..." -ForegroundColor Cyan

if (-not (Test-Path $AgentJar)) {
    Write-Host "  [DERLEME] integration_agent_node derleniyor..." -ForegroundColor Gray
    Push-Location $ScriptDir
    mvn clean package -DskipTests | Out-Null
    Pop-Location
}

if (-not (Test-Path $ManagerJar)) {
    Write-Host "  [DERLEME] manager_service derleniyor..." -ForegroundColor Gray
    Push-Location (Join-Path $ScriptDir "manager_service")
    mvn clean package -DskipTests | Out-Null
    Pop-Location
}

Write-Host "  [OK] Agent ve Manager JAR paketleri hazır." -ForegroundColor Green

# 4. Agent ve Manager Servislerini Başlat
Write-Host "`n>>> [ADIM 3] Agent & Sidecar Manager Başlatılıyor..." -ForegroundColor Cyan

# Manager Başlat (Coordinator'a kayıt ve heartbeat atan servis)
$ManagerArgs = @(
    "-jar", $ManagerJar,
    "--server.port=$ManagerPort",
    "--node.id=$NodeId",
    "--node.name=$NodeName",
    "--node.agent-url=$AgentPublicUrl",
    "--node.manager-url=$ManagerPublicUrl",
    "--node.supported-datasets=$SupportedDatasets",
    "--node.coordinator-url=$CoordinatorUrl",
    "--node.heartbeat.enabled=true",
    "--node.heartbeat.interval-ms=10000"
)

# Agent Başlat (DMZ hesaplama ve ZKP üreten servis)
$AgentArgs = @(
    "-jar", $AgentJar,
    "--server.port=$AgentPort",
    "--agent.institution-id=$NodeId",
    "--agent.institution-name=$NodeName",
    "--agent.public-url=$AgentPublicUrl",
    "--coordinator.url=$CoordinatorUrl"
)

Write-Host "  [BAŞLATILIYOR] Manager Servisi Port $ManagerPort üzerinde başlatılıyor..." -ForegroundColor Gray
$mgrProc = Start-Process -FilePath "java" -ArgumentList $ManagerArgs -PassThru -NoNewWindow

Write-Host "  [BAŞLATILIYOR] Agent Servisi Port $AgentPort üzerinde başlatılıyor..." -ForegroundColor Gray
$agentProc = Start-Process -FilePath "java" -ArgumentList $AgentArgs -PassThru -NoNewWindow

Start-Sleep -Seconds 4

Write-Host "`n=================================================================" -ForegroundColor Green
Write-Host "  AJAN VE MANAGER BAŞARIYLA AYAĞA KALKTI!                         " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Green
Write-Host "  Manager PID : $($mgrProc.Id)" -ForegroundColor White
Write-Host "  Agent PID   : $($agentProc.Id)" -ForegroundColor White
Write-Host "  Kayıt URL'i : $CoordinatorUrl/api/v1/nodes/register" -ForegroundColor White
Write-Host "`n  İşlem başarıyla başlatıldı. Coordinator arayüzünden ($CoordinatorUrl) " -ForegroundColor Cyan
Write-Host "  bu kurumu ([$NodeId] $NodeName) 'ONLINE' olarak görebilirsiniz." -ForegroundColor Cyan
Write-Host "=================================================================`n" -ForegroundColor Green
