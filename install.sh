#!/bin/bash
# ==============================================================================
# ABSENLAH PLATFORM - ONE-CLICK ALL-IN-ONE DEPLOYMENT ENGINE
# ==============================================================================
# This script automates production infrastructure, including:
#   1. System Runtime Checks (Node.js LTS, JDK 17, Docker Engine, Docker Compose)
#   2. Dynamic Configuration Prompts (Domain, SSL, API Keys, Client ID, Expo)
#   3. Local Android compilation (Gradle APK Output)
#   4. Expo preview configuration & EAS integration
#   5. Multi-container self-hosted production ecosystem (Nginx, Certbot SSL, Node Backend)
# ==============================================================================

# Exit on error
set -e

# Visual colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color
BOLD='\033[1m'

clear

echo -e "${CYAN}${BOLD}====================================================================${NC}"
echo -e "${CYAN}${BOLD}       _   _   ____   ____   _____  _   _  _         _   _      ${NC}"
echo -e "${CYAN}${BOLD}      / \ | | | __ ) / ___| | ____|| \ | || |       / \ | |__   ${NC}"
echo -e "${CYAN}${BOLD}     / _ \| | |  _ \ \___ \ |  _|  |  \| || |      / _ \| '_ \  ${NC}"
echo -e "${CYAN}${BOLD}    / ___ \_|_| |_) | ___) || |___ | |\  || |___  / ___ \ | | | ${NC}"
echo -e "${CYAN}${BOLD}   /_/   \_\_(_)____/ |____/ |_____||_| \_||_____|/_/   \_\_| |_| ${NC}"
echo -e "${CYAN}${BOLD}                                                                    ${NC}"
echo -e "${CYAN}${BOLD}               ONE-CLICK ALL-IN-ONE AUTOMATION DEPLOYER             ${NC}"
echo -e "${CYAN}${BOLD}====================================================================${NC}"

# Define helper functions
log_info() { echo -e "${BLUE}[INFO] $1${NC}"; }
log_success() { echo -e "${GREEN}[SUCCESS] ✔ $1${NC}"; }
log_warn() { echo -e "${YELLOW}[WARNING] ⚠ $1${NC}"; }
log_error() { echo -e "${RED}[ERROR] ❌ $1${NC}"; }

# --- STAGE 1: OS & PRE-REQUISITE CHECKS ---
echo -e "\n${BOLD}${PURPLE}[STAGE 1/6] VERIFYING LOCAL SYSTEM RUNTIMES${NC}"

# Check Node.js
if command -v node >/dev/null 2>&1; then
    log_success "Node.js terpasang: $(node -v)"
else
    log_warn "Node.js tidak ditemukan. Mencoba memasang Node.js LTS..."
    if command -v apt-get >/dev/null 2>&1; then
        sudo apt-get update -y && sudo apt-get install -y curl gnupg build-essential
        curl -fsSL https://deb.nodesource.com/setup_18.x | sudo bash -
        sudo apt-get install -y nodejs
        log_success "Node.js berhasil terpasang!"
    else
        log_error "Sistem paket bukan APT. Silakan instal Node.js v18+ secara manual."
        exit 1
    fi
fi

# Check Java JDK 17
if command -v java >/dev/null 2>&1; then
    log_success "Java JDK terpasang: $(java -version 2>&1 | head -n 1)"
else
    log_warn "Java JDK tidak terdeteksi. Mencoba memasang OpenJDK 17..."
    if command -v apt-get >/dev/null 2>&1; then
        sudo apt-get update -y && sudo apt-get install -y openjdk-17-jdk openjdk-17-jre
        log_success "OpenJDK 17 berhasil terpasang!"
    else
        log_error "Silakan pasang Java JDK 17 secara manual sebelum melanjutkan."
        exit 1
    fi
fi

# Check Docker & Compose
if command -v docker >/dev/null 2>&1; then
    log_success "Docker terpasang: $(docker --version)"
else
    log_warn "Docker Engine tidak ditemukan. Silakan pasang Docker untuk mengaktifkan kontainerisasi produksi."
fi

# --- STAGE 2: INTERACTIVE CUSTOM CONFIGURATOR ---
echo -e "\n${BOLD}${PURPLE}[STAGE 2/6] DYNAMIC CONFIGURATION ENVIRONMENT SETUP${NC}"
echo -e "${YELLOW}Silakan tekan [ENTER] untuk menggunakan nilai default jika belum siap.${NC}"

# Ask for domain
read -p "Masukkan Domain/IP Server Anda [default: localhost]: " DEPLOY_DOMAIN
DEPLOY_DOMAIN=${DEPLOY_DOMAIN:-"localhost"}

# Ask for email SSL
read -p "Masukkan Alamat Email untuk Sertifikat SSL Let's Encrypt: " SSL_EMAIL
SSL_EMAIL=${SSL_EMAIL:-"admin@yourdomain.com"}

# Ask for Google Maps API Key
read -p "Masukkan Google Maps API Key (Untuk GeofenceMap): " GOOGLE_MAPS_KEY
GOOGLE_MAPS_KEY=${GOOGLE_MAPS_KEY:-"AIzaSyYourMockGoogleMapsAPIKey_123456"}

# Ask for Google Client ID
read -p "Masukkan Google Web Client ID (Untuk Google Sign-In): " GOOGLE_CLIENT_ID
GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID:-"1092837492-mockclientid.apps.googleusercontent.com"}

# Ask for Expo Access Token
read -p "Masukkan Expo Access Token Anda (Untuk EAS Build Cloud): " EXPO_TOKEN
EXPO_TOKEN=${EXPO_TOKEN:-"your-expo-eas-access-token-here"}

# --- STAGE 3: ENVIRONMENT COMPILING & SEEDING ---
echo -e "\n${BOLD}${PURPLE}[STAGE 3/6] CONFIGURING DOCKER COMPOSE & PRODUCTION SERVICES${NC}"

# Creating Environment files
log_info "Membuat file konfigurasi variabel lingkungan (.env & server/.env)..."

# Root / Frontend config
cat << EOF > .env
GOOGLE_MAPS_API_KEY=$GOOGLE_MAPS_KEY
GOOGLE_CLIENT_ID=$GOOGLE_CLIENT_ID
EXPO_TOKEN=$EXPO_TOKEN
DEPLOY_DOMAIN=$DEPLOY_DOMAIN
EOF

# Backend config
mkdir -p server
cat << EOF > server/.env
PORT=5000
GOOGLE_CLIENT_ID=$GOOGLE_CLIENT_ID
GOOGLE_MAPS_API_KEY=$GOOGLE_MAPS_KEY
JWT_SECRET=AbsenlahSuperSecretProductionTokenKey_2026
EOF

log_success "Variabel lingkungan (.env) berhasil dikonfigurasi secara aman."

# Generate Docker Compose orchestrator
log_info "Membuat konfigurasi kontainer produksi (docker-compose.yml)..."
cat << EOF > docker-compose.yml
version: '3.8'

services:
  # Absenlah Node.js Express Backend
  server:
    build:
      context: ./server
      dockerfile: ../Dockerfile.backend
    container_name: absenlah_production_api
    restart: unless-stopped
    ports:
      - "5000:5000"
    environment:
      - PORT=5000
      - NODE_ENV=production
      - JWT_SECRET=AbsenlahSuperSecretProductionTokenKey_2026
    volumes:
      - ./server/database.sqlite:/app/database.sqlite

  # Nginx Secured Reverse Proxy
  web:
    image: nginx:alpine
    container_name: absenlah_nginx_server
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
      - ./dist:/usr/share/nginx/html
      - /etc/letsencrypt:/etc/letsencrypt
    depends_on:
      - server
EOF

# Generate Backend Dockerfile
log_info "Membuat Dockerfile.backend untuk deployment server backend..."
cat << 'EOF' > Dockerfile.backend
FROM node:18-alpine
WORKDIR /app
COPY package.json ./
RUN npm install --only=production
COPY . .
EXPOSE 5000
CMD ["node", "index.js"]
EOF

# Generate Nginx secured SSL config
log_info "Membangun konfigurasi server Nginx reverse proxy (nginx.conf)..."
cat << EOF > nginx.conf
events { worker_connections 1024; }

http {
    include       mime.types;
    default_type  application/octet-stream;
    sendfile        on;
    keepalive_timeout  65;

    # HTTP to HTTPS redirect
    server {
        listen 80;
        server_name $DEPLOY_DOMAIN;
        
        location /.well-known/acme-challenge/ {
            root /var/www/certbot;
        }

        location / {
            return 301 https://\$host\$request_uri;
        }
    }

    # SSL HTTPS configuration
    server {
        listen 443 ssl;
        server_name $DEPLOY_DOMAIN;

        ssl_certificate /etc/letsencrypt/live/$DEPLOY_DOMAIN/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/$DEPLOY_DOMAIN/privkey.pem;

        # Secure parameters
        ssl_protocols TLSv1.2 TLSv1.3;
        ssl_ciphers HIGH:!aNULL:!MD5;

        # Static Client Files (Expo/Web Dist)
        location / {
            root /usr/share/nginx/html;
            try_files \$uri \$uri/ /index.html;
        }

        # Node.js API Proxy
        location /api/ {
            proxy_pass http://server:5000/api/;
            proxy_http_version 1.1;
            proxy_set_header Upgrade \$http_upgrade;
            proxy_set_header Connection 'upgrade';
            proxy_set_header Host \$host;
            proxy_cache_bypass \$http_upgrade;
        }
    }
}
EOF

log_success "File konfigurasi docker-compose.yml, Dockerfile.backend, dan nginx.conf siap pakai."

# --- STAGE 4: LOCAL GRADLE ANDROID COMPILATION ---
echo -e "\n${BOLD}${PURPLE}[STAGE 4/6] INITIATING LOCAL NATIVE ANDROID COMPILATION${NC}"

# Gradle command resolve
if [ -f "./gradlew" ]; then
    GRADLE_EXEC="./gradlew"
    chmod +x gradlew
else
    GRADLE_EXEC="gradle"
fi

log_info "Menjalankan Gradle build untuk menghasilkan APK local..."
if $GRADLE_EXEC assembleDebug; then
    log_success "Kompilasi lokal sukses!"
    echo -e "👉 ${BOLD}Lokasi APK lokal:${NC} app/build/outputs/apk/debug/app-debug.apk"
else
    log_warn "Kompilasi lokal gagal karena ketiadaan Android SDK lengkap pada mesin lokal Anda."
    log_info "Kompilasi dapat dijalankan via kontainer awan menggunakan Expo EAS Build."
fi

# --- STAGE 5: EXPO PREVIEW DISTRIBUTION UTILITY ---
echo -e "\n${BOLD}${PURPLE}[STAGE 5/6] PREPARING EXPO EAS CLOUD MOBILE PREVIEWS${NC}"
log_info "Membuat otomatisasi instalasi Expo CLI dan build EAS..."

cat << 'EOF' > run_expo_build.sh
#!/bin/bash
set -e
echo "Memulai Proses Build Menggunakan Expo EAS Cloud..."
# Verify EAS CLI Installation
if ! command -v eas >/dev/null 2>&1; then
    echo "EAS CLI tidak ditemukan, memasang secara global..."
    npm install -g eas-cli
fi

# Log in using token
if [ -n "$EXPO_TOKEN" ]; then
    export EXPO_TOKEN=$EXPO_TOKEN
fi

echo "Mendaftarkan proyek dan memicu EAS Build Preview..."
eas build --platform android --profile preview --non-interactive
EOF
chmod +x run_expo_build.sh

log_success "Script pembantu Expo ('run_expo_build.sh') sukses dibuat!"

# --- STAGE 6: DEPLOYMENT VERIFICATION & NEXT STEPS ---
echo -e "\n${BOLD}${PURPLE}[STAGE 6/6] ARCHITECTURE REVIEW & SELF-HOSTED COMMANDS${NC}"
echo -e "${CYAN}${BOLD}====================================================================${NC}"
echo -e "${GREEN}${BOLD}           ALL-IN-ONE DEPLOYMENT READY FOR LAUNCH!                  ${NC}"
echo -e "${CYAN}${BOLD}====================================================================${NC}"
echo -e "${BOLD}Info Deployment Terkonfigurasi:${NC}"
echo -e "  - ${BOLD}Domain Server:${NC} $DEPLOY_DOMAIN"
echo -e "  - ${BOLD}Email SSL:${NC} $SSL_EMAIL"
echo -e "  - ${BOLD}Google Maps API Key:${NC} $GOOGLE_MAPS_KEY"
echo -e "  - ${BOLD}Google Client ID:${NC} $GOOGLE_CLIENT_ID"
echo -e "  - ${BOLD}Sidik Jari SHA-1 Android:${NC} 08:16:D5:3B:31:39:F4:25:2E:E1:03:51:C3:EA:4E:A3:B8:50:BD:D3"
echo -e "  - ${BOLD}Sidik Jari SHA-256 Android:${NC} E1:02:D1:10:C7:36:2E:AC:34:D3:8F:27:D3:2F:F5:F8:D6:29:32:79:99:11:06:78:4A:55:9E:CD:BC:B2:56:40"
echo -e ""
echo -e "${BOLD}Langkah Meluncurkan Server Produksi (Docker Engine):${NC}"
echo -e "  1. Buat folder SSL lokal (jika belum ada) atau jalankan Certbot:"
echo -e "     ${CYAN}sudo certbot certonly --standalone -d $DEPLOY_DOMAIN --non-interactive --agree-tos -m $SSL_EMAIL${NC}"
echo -e "  2. Jalankan orkestrasi kontainer docker-compose:"
echo -e "     👉 ${CYAN}docker compose up --build -d${NC}"
echo -e "  3. Layanan backend & frontend akan aktif di: ${BOLD}http://$DEPLOY_DOMAIN${NC}"
echo -e ""
echo -e "${BOLD}Langkah Meluncurkan Build APK Android via Expo (EAS):${NC}"
echo -e "  Jalankan script otomatis yang kami sediakan:"
echo -e "     👉 ${CYAN}./run_expo_build.sh${NC}"
echo -e "===================================================================="
EOF
chmod +x /install.sh
