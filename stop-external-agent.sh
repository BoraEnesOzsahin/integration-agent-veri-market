#!/usr/bin/env bash
# =============================================================================
# Ayrotek Veri Marketi — Kurum İçi Düğüm Durdurucu (Linux / macOS)
# =============================================================================

AGENT_PORT="${1:-8083}"
MANAGER_PORT="${2:-8093}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo -e "\033[33m=================================================================\033[0m"
echo -e "\033[33m  AYROTEK VERİ MARKETİ - KURUM İÇİ DÜĞÜMÜ DURDURMA               \033[0m"
echo -e "\033[33m=================================================================\033[0m\n"

# 1. Native Java Süreçlerini Durdur
echo -e "\033[36m>>> [1/2] Port ${AGENT_PORT} (Agent) ve ${MANAGER_PORT} (Manager) Süreçleri Kapatılıyor...\033[0m"

fuser -k "${AGENT_PORT}/tcp" 2>/dev/null || true
fuser -k "${MANAGER_PORT}/tcp" 2>/dev/null || true

pkill -f "agent-running.jar" 2>/dev/null || true
pkill -f "agent_core" 2>/dev/null || true
pkill -f "manager_service" 2>/dev/null || true

echo -e "  \033[32m[OK] Native Java süreçleri sonlandırıldı.\033[0m"

# 2. Varsa Docker Konteynerlerini Durdur
if command -v docker &> /dev/null; then
    COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.agent-node.yml"
    if [ -f "$COMPOSE_FILE" ]; then
        echo -e "\n\033[36m>>> [2/2] Docker Düğüm Konteynerleri Kontrol Ediliyor...\033[0m"
        docker compose -f "$COMPOSE_FILE" down 2>/dev/null || true
        echo -e "  \033[32m[OK] Varsa Docker konteynerleri durduruldu.\033[0m"
    fi
fi

echo -e "\n\033[32m=================================================================\033[0m"
echo -e "\033[32m  [BAŞARILI] Kurum içi DMZ düğüm servisleri tamamen kapatıldı.   \033[0m"
echo -e "\033[32m=================================================================\033[0m\n"
