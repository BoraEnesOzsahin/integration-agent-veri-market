#!/usr/bin/env bash
# =================================================================
# AYROTEK VERİ MARKETİ - HARİCİ MAKİNE AJAN BAŞLATICI (Linux/macOS)
# =================================================================

COORDINATOR_IP="${1:-localhost}"
COORDINATOR_PORT="${2:-8081}"
MY_IP="${3:-auto}"
NODE_ID="${4:-Banka_B}"
NODE_NAME="${5:-Banka B Harici Entegrasyon Ajanı}"
AGENT_PORT="${6:-8083}"
MANAGER_PORT="${7:-8093}"
SUPPORTED_DATASETS="${8:-bankalar,finans,kredi,consortium}"

if [ "$MY_IP" = "auto" ] || [ -z "$MY_IP" ]; then
    MY_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
    if [ -z "$MY_IP" ]; then
        MY_IP="localhost"
    fi
fi

COORDINATOR_URL="http://${COORDINATOR_IP}:${COORDINATOR_PORT}"
AGENT_PUBLIC_URL="http://${MY_IP}:${AGENT_PORT}"
MANAGER_PUBLIC_URL="http://${MY_IP}:${MANAGER_PORT}"

echo "================================================================="
echo "  AYROTEK VERİ MARKETİ - HARİCİ AJAN BAŞLATICI"
echo "================================================================="
echo "  Bu Makinenin IP'si       : $MY_IP"
echo "  Kurum Kimliği (NodeId)  : $NODE_ID ($NODE_NAME)"
echo "  Ajan İletişim URL'i     : $AGENT_PUBLIC_URL"
echo "  Manager İletişim URL'i  : $MANAGER_PUBLIC_URL"
echo "  Hedef Coordinator       : $COORDINATOR_URL"
echo "================================================================="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_JAR="$SCRIPT_DIR/agent_core/target/agent_core-1.0.0-SNAPSHOT.jar"
if [ ! -f "$AGENT_JAR" ]; then
    AGENT_JAR="$SCRIPT_DIR/target/integration_agent_node-1.0.0-SNAPSHOT.jar"
fi
MANAGER_JAR="$SCRIPT_DIR/manager_service/target/manager_service-1.0.0-SNAPSHOT.jar"

if [ ! -f "$AGENT_JAR" ] || [ ! -f "$MANAGER_JAR" ]; then
    echo ">>> Multi-module proje (agent_core & manager_service) derleniyor..."
    (cd "$SCRIPT_DIR" && mvn clean package -DskipTests)
    AGENT_JAR="$SCRIPT_DIR/agent_core/target/agent_core-1.0.0-SNAPSHOT.jar"
fi

echo ">>> Servisler başlatılıyor..."
java -jar "$MANAGER_JAR" \
    --server.port="$MANAGER_PORT" \
    --node.id="$NODE_ID" \
    --node.name="$NODE_NAME" \
    --node.agent-url="$AGENT_PUBLIC_URL" \
    --node.manager-url="$MANAGER_PUBLIC_URL" \
    --node.supported-datasets="$SUPPORTED_DATASETS" \
    --node.coordinator-url="$COORDINATOR_URL" \
    --node.heartbeat.enabled=true \
    --node.heartbeat.interval-ms=10000 > manager.log 2>&1 &

MGR_PID=$!

java -jar "$AGENT_JAR" \
    --server.port="$AGENT_PORT" \
    --agent.institution-id="$NODE_ID" \
    --agent.institution-name="$NODE_NAME" \
    --agent.public-url="$AGENT_PUBLIC_URL" \
    --coordinator.url="$COORDINATOR_URL" > agent.log 2>&1 &

AGENT_PID=$!

echo "================================================================="
echo "  SERVİSLER BAŞARIYLA BAŞLATILDI!"
echo "  Manager PID: $MGR_PID (Log: manager.log)"
echo "  Agent PID  : $AGENT_PID (Log: agent.log)"
echo "================================================================="
