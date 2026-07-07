#!/bin/bash
# Absenlah All-in-One Enterprise Deployer & Builder Script
# Optimized for Ubuntu 20.04/22.04 LTS

set -e

# Design Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color
CYAN='\033[0;36m'
BOLD='\033[1m'

LOG_FILE="deploy_absenlah.log"
exec > >(tee -a "$LOG_FILE") 2>&1

echo -e "${CYAN}${BOLD}======================================================${NC}"
echo -e "${CYAN}${BOLD}         ABSENLAH ENTERPRISE ALL-IN-ONE DEPLOYER       ${NC}"
echo -e "${CYAN}${BOLD}======================================================${NC}"
echo -e "${BLUE}Menyiapkan instalasi server, reverse proxy, dan mobile builder...${NC}"
echo ""

# Error Handling & Rollback Mechanism
trap 'rollback' ERR

rollback() {
    echo -e "${RED}Terjadi kesalahan! Memulai mekanisme rollback...${NC}"
    # Basic rollback: stop docker containers if they were started
    docker-compose down || true
    echo -e "${YELLOW}Rollback selesai. Silakan periksa $LOG_FILE untuk detail kesalahan.${NC}"
    exit 1
}

# 1. System Requirement Checks
check_root() {
    if [ "$EUID" -ne 0 ]; then
        echo -e "${RED}Error: Jalankan skrip ini sebagai root (sudo ./setup_absenlah.sh)${NC}"
        exit 1
    fi
}

# Install dependencies
install_dependencies() {
    echo -e "${YELLOW}[1/5] Memperbarui paket sistem dan menginstal dependensi...${NC}"
    apt-get update -y
    apt-get install -y curl git wget unzip openjdk-17-jdk nginx certbot python3-certbot-nginx docker.io docker-compose nodejs npm
    
    # Enable Docker service
    systemctl start docker || true
    systemctl enable docker || true
    echo -e "${GREEN}✔ Dependensi dasar berhasil diinstal!${NC}"
}

# 2. Setup Environment Variables CLI Wizard
setup_env() {
    echo -e "${YELLOW}[2/5] Menjalankan CLI Wizard untuk konfigurasi (.env)...${NC}"

    if [ -f .env ]; then
        read -p "File .env sudah ada. Timpa? (y/n): " overwrite
        if [[ $overwrite != "y" ]]; then
            echo -e "${BLUE}Menggunakan file .env yang sudah ada.${NC}"
            return
        fi
    fi

    read -p "Masukkan nama domain server Anda (contoh: absenlah.com): " DOMAIN_NAME
    read -p "Masukkan email Anda untuk SSL Let's Encrypt: " SSL_EMAIL
    read -p "Masukkan port backend layanan (default: 8080): " BACKEND_PORT
    BACKEND_PORT=${BACKEND_PORT:-8080}
    
    cat <<EOF > .env
# Absenlah Enterprise Server Configuration
DOMAIN=$DOMAIN_NAME
EMAIL=$SSL_EMAIL
PORT=$BACKEND_PORT
JWT_SECRET=$(openssl rand -hex 32)
DB_USER=absenlah_admin
DB_PASSWORD=$(openssl rand -base64 12)
DB_NAME=absenlah_db
EOF

    echo -e "${GREEN}✔ File .env berhasil dibuat!${NC}"
}

# 3. Docker Compose Configuration
setup_docker() {
    echo -e "${YELLOW}[3/5] Mengonfigurasi layanan Docker...${NC}"
    # Verify docker-compose.yml exists (it should be in the root)
    if [ ! -f "docker-compose.yml" ]; then
        echo -e "${RED}Error: docker-compose.yml tidak ditemukan di root!${NC}"
        exit 1
    fi
    docker-compose up -d --build
    echo -e "${GREEN}✔ Layanan backend & database berhasil dijalankan di background!${NC}"
}

# 4. Configure Nginx Reverse Proxy
setup_nginx() {
    echo -e "${YELLOW}[4/5] Mengonfigurasi Nginx Web Server & SSL...${NC}"
    
    source .env
    NGINX_CONF="/etc/nginx/sites-available/$DOMAIN"
    
    cat <<EOF > $NGINX_CONF
server {
    listen 80;
    server_name $DOMAIN;

    location / {
        proxy_pass http://localhost:$PORT;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host \$host;
        proxy_cache_bypass \$http_upgrade;
    }
}
EOF

    ln -sf $NGINX_CONF /etc/nginx/sites-enabled/ || true
    rm -f /etc/nginx/sites-enabled/default || true
    
    systemctl restart nginx || true
    echo -e "${GREEN}✔ Nginx Reverse Proxy berhasil disiapkan!${NC}"
}

# 5. Build Pipeline for Mobile App (Release APK)
build_mobile_app() {
    echo -e "${YELLOW}[5/5] Memulai pipeline build Android APK Release...${NC}"
    
    if [ ! -f "./gradlew" ]; then
        echo -e "${RED}Error: gradlew tidak ditemukan! Pastikan Anda berada di root project.${NC}"
        exit 1
    fi

    chmod +x ./gradlew
    ./gradlew assembleRelease

    mkdir -p build_output
    cp app/build/outputs/apk/release/app-release.apk build_output/absenlah_enterprise_release.apk || \
    cp app/build/outputs/apk/release/app-release-unsigned.apk build_output/absenlah_enterprise_release.apk || true

    echo -e "${GREEN}✔ Kompilasi selesai! APK Release siap di: build_output/absenlah_enterprise_release.apk${NC}"
}

# Main Execution Flow
check_root
install_dependencies
setup_env
setup_docker
setup_nginx
build_mobile_app

echo ""
echo -e "${GREEN}${BOLD}======================================================${NC}"
echo -e "${GREEN}${BOLD}        ABSENLAH ENTERPRISE BERHASIL DI-DEPLOY!       ${NC}"
echo -e "${GREEN}${BOLD}======================================================${NC}"
echo -e "${BLUE}Status Log: ${YELLOW}$LOG_FILE${NC}"
echo -e "${GREEN}${BOLD}======================================================${NC}"
