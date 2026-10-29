param(
    [Parameter(Mandatory=$false)]
    [string]$CoordinatorIp = "192.168.1.100",

    [Parameter(Mandatory=$false)]
    [int]$CoordinatorPort = 8081,

    [Parameter(Mandatory=$false)]
    [string]$MyIp = "",

    [Parameter(Mandatory=$false)]
    [string]$NodeId = "Banka_Harici_1",

    [Parameter(Mandatory=$false)]
    [string]$NodeName = "Harici Banka A.Ş. (On-Premise DMZ)",

    [Parameter(Mandatory=$false)]
    [int]$AgentPort = 8083,

    [Parameter(Mandatory=$false)]
    [int]$ManagerPort = 8093,

    [Parameter(Mandatory=$false)]
    [string]$SupportedDatasets = "gelir_gider,bankalar,finans,kredi,consortium",

    [Parameter(Mandatory=$false)]
    [string]$DbHost = "localhost",

    [Parameter(Mandatory=$false)]
    [int]$DbPort = 5432,

    [Parameter(Mandatory=$false)]
    [string]$DbName = "postgres",

    [Parameter(Mandatory=$false)]
    [string]$DbUser = "postgres",

    [Parameter(Mandatory=$false)]
    [string]$DbPassword = "verimarket_password",

    [Parameter(Mandatory=$false)]
    [string]$DbUrl = "",

    [Parameter(Mandatory=$false)]
    [ValidateSet("auto", "docker", "native")]
    [string]$Mode = "auto"
)

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "  AYROTEK VERİ MARKETİ - TAM KURUM İÇİ (DMZ) DÜĞÜMÜ BAŞLATICI   " -ForegroundColor Cyan
Write-Host "  Gerçek Hayat Mimarisi: DB + AnonCreds ZKP + Manager + Agent    " -ForegroundColor Cyan
Write-Host "=================================================================`n" -ForegroundColor Cyan

# 1. Kendi yerel IP adresini otomatik tespit et
if ([string]::IsNullOrWhiteSpace($MyIp) -or $MyIp -eq "auto") {
    $foundIp = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { 
        $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" 
    } | Select-Object -First 1).IPAddress

    if (-not [string]::IsNullOrWhiteSpace($foundIp)) {
        $MyIp = $foundIp
    } else {
        $MyIp = "localhost"
    }
}

$effectiveDbUrl = $DbUrl
if ([string]::IsNullOrWhiteSpace($effectiveDbUrl)) {
    $effectiveDbUrl = "jdbc:postgresql://${DbHost}:${DbPort}/${DbName}"
}

$CoordinatorUrl = "http://${CoordinatorIp}:${CoordinatorPort}"
$AgentPublicUrl = "http://${MyIp}:${AgentPort}"
$ManagerPublicUrl = "http://${MyIp}:${ManagerPort}"

Write-Host "  [BİLGİ] Bu Makinenin Yerel IP'si: $MyIp" -ForegroundColor Green
Write-Host "  [BİLGİ] Kurum Kimliği (NodeId) : $NodeId" -ForegroundColor Green
Write-Host "  [BİLGİ] Kurum Adı              : $NodeName" -ForegroundColor Green
Write-Host "  [BİLGİ] Ajan İletişim URL'i    : $AgentPublicUrl" -ForegroundColor Green
Write-Host "  [BİLGİ] Manager İletişim URL'i : $ManagerPublicUrl" -ForegroundColor Green
Write-Host "  [BİLGİ] Hedef Coordinator      : $CoordinatorUrl" -ForegroundColor Yellow
Write-Host "  [BİLGİ] Veritabanı URL (JDBC)  : $effectiveDbUrl" -ForegroundColor Cyan
Write-Host "  [BİLGİ] Veritabanı Kullanıcısı : $DbUser" -ForegroundColor Cyan
Write-Host "  [BİLGİ] Veritabanı Adı / Port  : $DbName (Port: $DbPort)" -ForegroundColor Cyan
Write-Host "  [BİLGİ] Desteklenen Veri Seti  : $SupportedDatasets" -ForegroundColor Cyan

# 2. Coordinator Erişilebilirlik Testi
Write-Host "`n>>> [ADIM 1] Coordinator Bağlantısı Test Ediliyor..." -ForegroundColor Cyan
try {
    $coordCheck = Invoke-RestMethod -Uri "$CoordinatorUrl/api/v1/mpc/orchestrator/health" -TimeoutSec 3 -ErrorAction Stop
    Write-Host "  [OK] Coordinator erişilebilir durumda! ($($coordCheck.service))" -ForegroundColor Green
} catch {
    Write-Host "  [UYARI] $CoordinatorUrl adresindeki Coordinator'a doğrudan ping atılamadı." -ForegroundColor Yellow
    Write-Host "          (Eğer Coordinator henüz başlamadıysa düğüm başladığında otomatik bağlanmayı deneyecektir.)" -ForegroundColor Gray
}

# 3. Çalışma Modu Kararı (Docker vs Native)
$hasDocker = $false
try {
    $dockerCheck = docker info 2>&1
    if ($LASTEXITCODE -eq 0) {
        $hasDocker = $true
    }
} catch {
    $hasDocker = $false
}

$selectedMode = $Mode
if ($selectedMode -eq "auto") {
    if ($hasDocker) {
        $selectedMode = "docker"
    } else {
        $selectedMode = "native"
    }
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# =============================================================================
# MOD 1: DOCKER COMPOSE İLE TAM ON-PREMISE STACK (ÖNERİLEN)
# =============================================================================
if ($selectedMode -eq "docker") {
    Write-Host "`n>>> [ADIM 2] Docker Modu Seçildi. Tam DMZ Stack Ayağa Kaldırılıyor..." -ForegroundColor Cyan
    Write-Host "  Konteynerler:" -ForegroundColor Gray
    Write-Host "    - 1. agent-node-postgres (Kurumun izole veritabanı + gelir_gider gerçek verileri)" -ForegroundColor Gray
    Write-Host "    - 2. agent-node-zkp      (Kurum içi Hyperledger AnonCreds ZKP motoru)" -ForegroundColor Gray
    Write-Host "    - 3. agent-node-manager  (Coordinator ile el sıkışma + Heartbeat)" -ForegroundColor Gray
    Write-Host "    - 4. agent-node-core     (Shamir MPC + RSA-OAEP tüketici şifreleme)" -ForegroundColor Gray

    # .env Dosyasını Hazırla
    $envContent = @"
COORDINATOR_URL=$CoordinatorUrl
MY_IP=$MyIp
NODE_ID=$NodeId
NODE_NAME=$NodeName
DB_NAME=$DbName
DB_USER=$DbUser
DB_PASSWORD=$DbPassword
DB_PORT=$DbPort
SUPPORTED_DATASETS=$SupportedDatasets
"@
    Set-Content -Path (Join-Path $ScriptDir ".env") -Value $envContent -Encoding UTF8

    $composeFile = Join-Path $ScriptDir "docker-compose.agent-node.yml"

    Write-Host "`n>>> Docker Compose başlatılıyor..." -ForegroundColor Yellow
    docker compose -f $composeFile up -d --build

    if ($LASTEXITCODE -eq 0) {
        Write-Host "`n=================================================================" -ForegroundColor Green
        Write-Host "  TÜM GERÇEK HAYAT GEREKSİNİMLERİ BAŞARIYLA AYAĞA KALKTI!         " -ForegroundColor Green
        Write-Host "=================================================================" -ForegroundColor Green
        Write-Host "  1. Kurum Veritabanı (PostgreSQL) : Port 5432 (gelir_gider tablosu mühürlü)" -ForegroundColor White
        Write-Host "  2. AnonCreds ZKP Motoru         : Port 3000 (Local CL-Signatures)" -ForegroundColor White
        Write-Host "  3. Kurum İçi Entegrasyon Ajanı  : Port $AgentPort (Shamir MPC + Kripto)" -ForegroundColor White
        Write-Host "  4. Kurum Yöneticisi (Manager)   : Port $ManagerPort (Coordinator Kayıt & Heartbeat)" -ForegroundColor White
        Write-Host "`n  Coordinator ($CoordinatorUrl) artık bu kurumu ONLINE olarak görecektir." -ForegroundColor Cyan
        Write-Host "  Durdurmak için: docker compose -f $composeFile down" -ForegroundColor Gray
        Write-Host "=================================================================`n" -ForegroundColor Green
    } else {
        Write-Host "[HATA] Docker compose başlatılamadı. Docker Desktop'ın çalıştığından emin olun." -ForegroundColor Red
    }
    return
}

# =============================================================================
# MOD 2: YEREL JAVA / NATIVE PROCESS MODU (Docker Yoksa)
# =============================================================================
Write-Host "`n>>> [ADIM 2] Native Java Modu Seçildi. JAR Dosyaları Kontrol Ediliyor..." -ForegroundColor Cyan

$AgentJar = Join-Path $ScriptDir "agent_core\target\agent_core-1.0.0-SNAPSHOT.jar"
if (-not (Test-Path $AgentJar)) {
    $AgentJar = Join-Path $ScriptDir "target\integration_agent_node-1.0.0-SNAPSHOT.jar"
}
$RootDir = Split-Path -Parent (Split-Path -Parent $ScriptDir)
$RootAgentJar = Join-Path $RootDir "agent-running.jar"
if ((-not (Test-Path $AgentJar)) -and (Test-Path $RootAgentJar)) {
    $AgentJar = $RootAgentJar
}

$ManagerJar = Join-Path $ScriptDir "manager_service\target\manager_service-1.0.0-SNAPSHOT.jar"

if (-not (Test-Path $AgentJar) -or -not (Test-Path $ManagerJar)) {
    Write-Host "  [DERLEME] JAR paketleri derleniyor..." -ForegroundColor Gray
    Push-Location $ScriptDir
    mvn clean package -DskipTests | Out-Null
    Pop-Location
    $AgentJar = Join-Path $ScriptDir "agent_core\target\agent_core-1.0.0-SNAPSHOT.jar"
}

Write-Host "  [OK] Agent ve Manager JAR paketleri hazır." -ForegroundColor Green

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

# Agent Başlat (DMZ hesaplama, ZKP ve yerel DB sorguları yürüten servis)
$AgentArgs = @(
    "-jar", $AgentJar,
    "--server.port=$AgentPort",
    "--agent.institution-id=$NodeId",
    "--agent.institution-name=$NodeName",
    "--agent.public-url=$AgentPublicUrl",
    "--coordinator.url=$CoordinatorUrl",
    "--agent.db.url=$effectiveDbUrl",
    "--agent.db.username=$DbUser",
    "--agent.db.password=$DbPassword",
    "--spring.datasource.url=$effectiveDbUrl",
    "--spring.datasource.username=$DbUser",
    "--spring.datasource.password=$DbPassword"
)

Write-Host "`n>>> [ADIM 3] Agent & Sidecar Manager Başlatılıyor..." -ForegroundColor Cyan
Write-Host "  [BAŞLATILIYOR] Manager Servisi Port $ManagerPort üzerinde başlatılıyor..." -ForegroundColor Gray
$mgrProc = Start-Process -FilePath "java" -ArgumentList $ManagerArgs -PassThru -NoNewWindow

Write-Host "  [BAŞLATILIYOR] Agent Servisi Port $AgentPort üzerinde başlatılıyor..." -ForegroundColor Gray
$agentProc = Start-Process -FilePath "java" -ArgumentList $AgentArgs -PassThru -NoNewWindow

Start-Sleep -Seconds 3

Write-Host "`n=================================================================" -ForegroundColor Green
Write-Host "  AJAN VE MANAGER BAŞARIYLA BAŞLATILDI!                           " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Green
Write-Host "  Manager PID : $($mgrProc.Id) (Port $ManagerPort)" -ForegroundColor White
Write-Host "  Agent PID   : $($agentProc.Id) (Port $AgentPort)" -ForegroundColor White
Write-Host "  Coordinator : $CoordinatorUrl" -ForegroundColor White
Write-Host "=================================================================`n" -ForegroundColor Green
