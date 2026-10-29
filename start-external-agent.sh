#!/usr/bin/env bash
# =============================================================================
# Ayrotek Veri Marketi — Tam Kurum İçi (DMZ) Düğümü Başlatıcı (Linux / macOS)
# =============================================================================

COORDINATOR_IP="${1:-192.168.1.100}"
COORDINATOR_PORT="${2:-8081}"
MY_IP="${3:-auto}"
NODE_ID="${4:-Banka_Harici_1}"
NODE_NAME="${5:-Harici Banka A.Ş. (On-Premise DMZ)}"
AGENT_PORT="${6:-8083}"
MANAGER_PORT="${7:-8093}"
MODE="${8:-auto}"

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-postgres}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-verimarket_password}"
SUPPORTED_DATASETS="${SUPPORTED_DATASETS:-gelir_gider,bankalar,finans,kredi,consortium}"

EFFECTIVE_DB_URL="${DB_URL:-jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}}"

echo -e "\033[36m=================================================================\033[0m"
echo -e "\033[36m  AYROTEK VERİ MARKETİ - TAM KURUM İÇİ (DMZ) DÜĞÜMÜ BAŞLATICI   \033[0m"
echo -e "\033[36m  Gerçek Hayat Mimarisi: DB + AnonCreds ZKP + Manager + Agent    \033[0m"
echo -e "\033[36m=================================================================\033[0m\n"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. Kendi yerel IP adresini tespit et
if [ "$MY_IP" == "auto" ] || [ -z "$MY_IP" ]; then
    MY_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
    if [ -z "$MY_IP" ]; then
        MY_IP=$(ip -4 addr show scope global | grep -oP '(?<=inet\s)\d+(\.\d+){3}' | head -n 1)
    fi
    if [ -z "$MY_IP" ]; then
        MY_IP="localhost"
    fi
fi

COORDINATOR_URL="http://${COORDINATOR_IP}:${COORDINATOR_PORT}"
AGENT_PUBLIC_URL="http://${MY_IP}:${AGENT_PORT}"
MANAGER_PUBLIC_URL="http://${MY_IP}:${MANAGER_PORT}"

echo -e "\033[32m  [BİLGİ] Bu Makinenin Yerel IP'si: ${MY_IP}\033[0m"
echo -e "\033[32m  [BİLGİ] Kurum Kimliği (NodeId) : ${NODE_ID}\033[0m"
echo -e "\033[32m  [BİLGİ] Kurum Adı              : ${NODE_NAME}\033[0m"
echo -e "\033[32m  [BİLGİ] Ajan İletişim URL'i    : ${AGENT_PUBLIC_URL}\033[0m"
echo -e "\033[32m  [BİLGİ] Manager İletişim URL'i : ${MANAGER_PUBLIC_URL}\033[0m"
echo -e "\033[33m  [BİLGİ] Hedef Coordinator      : ${COORDINATOR_URL}\033[0m\n"

# 2. Çalışma Modu
HAS_DOCKER=false
if command -v docker &> /dev/null && docker info &> /dev/null; then
    HAS_DOCKER=true
fi

SELECTED_MODE="$MODE"
if [ "$SELECTED_MODE" == "auto" ]; then
    if [ "$HAS_DOCKER" = true ]; then
        SELECTED_MODE="docker"
    else
        SELECTED_MODE="native"
    fi
fi

if [ "$SELECTED_MODE" == "docker" ]; then
    echo -e "\033[36m>>> Docker Modu Seçildi. Tam DMZ Stack Ayağa Kaldırılıyor...\033[0m"
    cat <<EOF > "${SCRIPT_DIR}/.env"
COORDINATOR_URL=${COORDINATOR_URL}
MY_IP=${MY_IP}
NODE_ID=${NODE_ID}
NODE_NAME=${NODE_NAME}
DB_NAME=${DB_NAME}
DB_USER=${DB_USER}
DB_PASSWORD=${DB_PASSWORD}
DB_PORT=${DB_PORT}
SUPPORTED_DATASETS=${SUPPORTED_DATASETS}
EOF

    docker compose -f "${SCRIPT_DIR}/docker-compose.agent-node.yml" up -d --build
    echo -e "\n\033[32m[BAŞARILI] Tüm kurum içi DMZ servisleri (DB, ZKP, Agent, Manager) ayağa kalktı!\033[0m"
    exit 0
fi

# Native Mod
echo -e "\033[36m>>> Native Java Modu Başlatılıyor...\033[0m"
AGENT_JAR="${SCRIPT_DIR}/agent_core/target/agent_core-1.0.0-SNAPSHOT.jar"
if [ ! -f "$AGENT_JAR" ]; then
    AGENT_JAR="${SCRIPT_DIR}/../../agent-running.jar"
fi
MANAGER_JAR="${SCRIPT_DIR}/manager_service/target/manager_service-1.0.0-SNAPSHOT.jar"

nohup java -jar "$MANAGER_JAR" \
  --server.port="$MANAGER_PORT" \
  --node.id="$NODE_ID" \
  --node.name="$NODE_NAME" \
  --node.agent-url="$AGENT_PUBLIC_URL" \
  --node.manager-url="$MANAGER_PUBLIC_URL" \
  --node.supported-datasets="$SUPPORTED_DATASETS" \
  --node.coordinator-url="$COORDINATOR_URL" \
  --node.heartbeat.enabled=true > "${SCRIPT_DIR}/manager.log" 2>&1 &

nohup java -jar "$AGENT_JAR" \
  --server.port="$AGENT_PORT" \
  --agent.institution-id="$NODE_ID" \
  --agent.institution-name="$NODE_NAME" \
  --agent.public-url="$AGENT_PUBLIC_URL" \
  --coordinator.url="$COORDINATOR_URL" \
  --agent.db.url="$EFFECTIVE_DB_URL" \
  --agent.db.username="$DB_USER" \
  --agent.db.password="$DB_PASSWORD" \
  --spring.datasource.url="$EFFECTIVE_DB_URL" \
  --spring.datasource.username="$DB_USER" \
  --spring.datasource.password="$DB_PASSWORD" > "${SCRIPT_DIR}/agent.log" 2>&1 &

echo -e "\033[32m[BAŞARILI] Native Agent ve Manager arka planda başlatıldı!\033[0m"
