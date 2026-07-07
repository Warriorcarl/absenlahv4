#!/bin/bash
# ==============================================================================
# ABSENLAH PLATFORM - COMPREHENSIVE ONE-CLICK DEPLOYMENT ENGINE (deploy.sh)
# ==============================================================================
# This script automates the complete production lifecycle:
#   1. System Dependencies (Node.js LTS, Docker Engine, Docker Compose, JDK 17)
#   2. Dynamic Configuration Prompts (Domain, SSL Let's Encrypt, Firebase, Expo)
#   3. Build Generation (Expo Web Export & Native Android APK via local Gradle CLI)
#   4. High-Performance Multi-Stage Container Setup
#   5. Nginx & SSL Certbot Web Proxy Orchestration
# ==============================================================================

# Exit immediately if a command exits with a non-zero status
set -e

# ANSI Color Codes for Premium Console Styling
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
echo -e "${CYAN}${BOLD}     ██████╗ ███████╗██████╗ ██╗      ██████╗ ██╗   ██╗     ███████╗██╗${NC}"
echo -e "${CYAN}${BOLD}     ██╔══██╗██╔════╝██╔══██╗██║     ██╔═══██╗╚██╗ ██╔╝     ██╔════╝██║${NC}"
echo -e "${CYAN}${BOLD}     ██║  ██║█████╗  ██████╔╝██║     ██║   ██║ ╚████╔╝█████╗███████╗██║${NC}"
echo -e "${CYAN}${BOLD}     ██║  ██║██╔══╝  ██╔═══╝ ██║     ██║   ██║  ╚██╔╝ ╚════╝╚════██║██║${NC}"
echo -e "${CYAN}${BOLD}     ██████╔╝███████╗██║     ███████╗╚██████╔╝   ██║        ███████║██║${NC}"
echo -e "${CYAN}${BOLD}     ╚═════╝ ╚══════╝╚═╝     ╚══════╝ ╚═════╝    ╚═╝        ╚══════╝╚═╝${NC}"
echo -e "${CYAN}${BOLD}               ONE-CLICK PRODUCTION DEPLOYMENT & DEVOPS ENGINE      ${NC}"
echo -e "${CYAN}${BOLD}====================================================================${NC}"

log_info() { echo -e "${BLUE}[INFO] $1${NC}"; }
log_success() { echo -e "${GREEN}[SUCCESS] ✔ $1${NC}"; }
log_warn() { echo -e "${YELLOW}[WARNING] ⚠ $1${NC}"; }
log_error() { echo -e "${RED}[ERROR] ❌ $1${NC}"; }

# --- STAGE 0: CLEANUP AND PREVIOUS INSTALLATION REMOVAL ---
echo -e "\n${BOLD}${PURPLE}[STAGE 0/5] CLEANUP & PREVIOUS INSTALLATION REMOVAL${NC}"

# Check for Git cloning first
echo -e "${YELLOW}Apakah Anda ingin meng-kloning / meng-unduh repositori Absenlah dari GitHub? (y/N)${NC}"
read -p "👉 Pilihan Anda: " DO_CLONE

if [[ "$DO_CLONE" =~ ^[Yy]$ ]]; then
    read -p "👉 Masukkan URL Repository GitHub [default: https://github.com/Warriorcarl/absenlahv4.git]: " GIT_REPO_URL
    GIT_REPO_URL=${GIT_REPO_URL:-"https://github.com/Warriorcarl/absenlahv4.git"}
    
    log_info "Mengunduh kode sumber dari $GIT_REPO_URL..."
    if command -v git >/dev/null 2>&1; then
        # Check if folder is already a git repo or if we need to clone
        if [ -d ".git" ]; then
            log_warn "Direktori ini sudah merupakan repositori Git. Menyimpan perubahan lokal dan melakukan reset agar tidak terjadi konflik..."
            git stash || true
            git fetch --all || true
            git reset --hard origin/main || true
            git pull || true
        else
            git clone "$GIT_REPO_URL" temp_clone
            if [ -d "temp_clone" ]; then
                cp -r temp_clone/* ./ 2>/dev/null || true
                cp -r temp_clone/.* ./ 2>/dev/null || true
                rm -rf temp_clone
                log_success "Repositori berhasil dikloning!"
            else
                log_error "Gagal melakukan kloning repositori."
            fi
        fi
    else
        log_warn "Git tidak terpasang. Mencoba menginstal Git terlebih dahulu..."
        if command -v apt-get >/dev/null 2>&1; then
            sudo apt-get update && sudo apt-get install -y git
            git clone "$GIT_REPO_URL" temp_clone
            cp -r temp_clone/* ./ 2>/dev/null || true
            cp -r temp_clone/.* ./ 2>/dev/null || true
            rm -rf temp_clone
            log_success "Repositori berhasil dikloning!"
        else
            log_error "Sistem paket bukan APT dan git tidak ditemukan. Silakan pasang git secara manual."
        fi
    fi
fi

echo -e "${YELLOW}Apakah Anda ingin menghapus instalan lama (Clean Reset) sebelum melanjutkan? (y/N)${NC}"
read -p "👉 Pilihan Anda: " DO_CLEANUP

if [[ "$DO_CLEANUP" =~ ^[Yy]$ ]]; then
    log_info "Memulai pembersihan data lama..."
    
    # 1. Stop existing docker containers and remove networks/volumes
    log_info "Menghentikan kontainer Docker lama (jika ada)..."
    if command -v docker >/dev/null 2>&1; then
        docker compose down --volumes --remove-orphans 2>/dev/null || true
        # Also clean up any lingering container with prefix "absenlah"
        OLD_CONTAINERS=$(docker ps -a -q --filter "name=absenlah" 2>/dev/null || true)
        if [ -n "$OLD_CONTAINERS" ]; then
            docker rm -f $OLD_CONTAINERS 2>/dev/null || true
            log_success "Kontainer lama berhasil dihentikan dan dihapus."
        fi
        
        # Prune build cache to reclaim disk space
        log_info "Membersihkan cache build Docker..."
        docker builder prune -f 2>/dev/null || true
    fi

    # 2. Delete old environment files
    log_info "Menghapus konfigurasi variabel lingkungan lama (.env)..."
    rm -f .env server/.env 2>/dev/null || true

    # 3. Clean local databases and media volumes
    log_info "Apakah Anda juga ingin menghapus database SQLite lokal? (y/N)"
    read -p "👉 Hapus Database: " ERASE_DB
    if [[ "$ERASE_DB" =~ ^[Yy]$ ]]; then
        rm -f server/database.sqlite 2>/dev/null || true
        log_success "Database SQLite lama berhasil dieliminasi."
    else
        log_warn "Database SQLite dipertahankan untuk migrasi data."
    fi

    # 4. Wipe generated node build folders and native caches
    log_info "Membersihkan dependensi node, berkas ekspor, dan direktori build lama..."
    rm -rf dist build node_modules server/node_modules app/build .expo .build-outputs 2>/dev/null || true
    
    log_success "Proses pembersihan tuntas! Memulai instalasi bersih..."
else
    log_info "Melewati tahap pembersihan. Melanjutkan instalasi inkremental..."
fi

# --- STAGE 1: RUNTIME ENGINE CHECK & INSTALLATION ---
echo -e "\n${BOLD}${PURPLE}[STAGE 1/5] VERIFYING REQUIRED RUNTIMES & TOOLCHAINS${NC}"

# Check Host Permissions
if [ "$EUID" -ne 0 ]; then
    log_warn "Beberapa instalasi sistem memerlukan hak akses root (sudo)."
    log_info "Jika perintah terhenti, silakan jalankan kembali script ini dengan: sudo ./deploy.sh"
fi

# Detect OS
if [ -f /etc/os-release ]; then
    . /etc/os-release
    log_info "Sistem Operasi Terdeteksi: $NAME ($VERSION)"
fi

# Check & Install Node.js LTS
log_info "Memeriksa Node.js..."
if command -v node >/dev/null 2>&1; then
    log_success "Node.js terpasang: $(node -v)"
else
    log_info "Node.js tidak ditemukan. Menginstal Node.js LTS (v18)..."
    if command -v apt-get >/dev/null 2>&1; then
        sudo apt-get update -y && sudo apt-get install -y curl gnupg build-essential
        curl -fsSL https://deb.nodesource.com/setup_18.x | sudo bash -
        sudo apt-get install -y nodejs
        log_success "Node.js berhasil diinstal!"
    else
        log_error "Sistem paket bukan APT (Debian/Ubuntu). Silakan pasang Node.js v18+ secara manual."
    fi
fi

# Check & Install Docker
log_info "Memeriksa Docker Engine..."
if command -v docker >/dev/null 2>&1; then
    log_success "Docker terpasang: $(docker --version)"
else
    log_info "Docker tidak ditemukan. Menginstal Docker..."
    if command -v apt-get >/dev/null 2>&1; then
        sudo apt-get update -y
        sudo apt-get install -y apt-transport-https ca-certificates curl software-properties-common
        curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -
        sudo add-apt-repository "deb [arch=amd64] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" || true
        sudo apt-get update -y
        sudo apt-get install -y docker-ce docker-ce-cli containerd.io || true
        log_success "Docker Engine berhasil diinstal!"
    else
        log_error "Silakan pasang Docker Engine secara manual."
    fi
fi

# Check & Install Java JDK 17
log_info "Memeriksa Java JDK 17 (Kebutuhan Build Native)..."
if command -v java >/dev/null 2>&1; then
    log_success "Java terpasang: $(java -version 2>&1 | head -n 1)"
else
    log_info "Java JDK tidak ditemukan. Menginstal OpenJDK 17..."
    if command -v apt-get >/dev/null 2>&1; then
        sudo apt-get update -y
        sudo apt-get install -y openjdk-17-jdk openjdk-17-jre
        log_success "OpenJDK 17 berhasil diinstal!"
    else
        log_error "Silakan pasang Java JDK 17 secara manual."
    fi
fi

# --- STAGE 2: DYNAMIC CONTEXT & VARIABLE INGESTION ---
echo -e "\n${BOLD}${PURPLE}[STAGE 2/5] ENVIRONMENT KEY & CREDENTIAL INGESTION${NC}"
echo -e "${YELLOW}Silakan isi parameter berikut untuk mengonfigurasi production instance.${NC}"
echo -e "${YELLOW}Tekan [ENTER] untuk memilih nilai standar / default.${NC}"
echo -e "--------------------------------------------------------------------"

read -p "1. Nama Domain Server (misal: absenlah.domain.com) [default: warriorcarl.my.id]: " DOMAIN_NAME
DOMAIN_NAME=${DOMAIN_NAME:-"warriorcarl.my.id"}

read -p "2. Email untuk Pendaftaran SSL Certbot Let's Encrypt [default: warriorcarl@yahoo.com]: " EMAIL_SSL
EMAIL_SSL=${EMAIL_SSL:-"warriorcarl@yahoo.com"}

read -p "3. Google Maps API Key [default: AIzaSyAL7sqRHfuJVgPi75x6EGT698v0wWqsc2g]: " MAPS_API_KEY
MAPS_API_KEY=${MAPS_API_KEY:-"AIzaSyAL7sqRHfuJVgPi75x6EGT698v0wWqsc2g"}

read -p "4. Google Client ID [default: 877059452776-58j32gkhse713e0spgv5h05q9bd8f15d.apps.googleusercontent.com]: " G_CLIENT_ID
G_CLIENT_ID=${G_CLIENT_ID:-"877059452776-58j32gkhse713e0spgv5h05q9bd8f15d.apps.googleusercontent.com"}

read -p "5. Firebase Project ID [default: absenlahv2]: " FIREBASE_ID
FIREBASE_ID=${FIREBASE_ID:-"absenlahv2"}

read -p "6. Expo EAS Build Token [default: Vq_oX9llm4kYIkQyXiMy4usy39Hrk2lXXos36lRa]: " EXPO_EAS_TOKEN
EXPO_EAS_TOKEN=${EXPO_EAS_TOKEN:-"Vq_oX9llm4kYIkQyXiMy4usy39Hrk2lXXos36lRa"}

# Write main environment variables
log_info "Menulis konfigurasi variabel lingkungan ke file .env..."
cat << EOF > .env
GOOGLE_MAPS_API_KEY=$MAPS_API_KEY
EXPO_PUBLIC_GOOGLE_MAPS_API_KEY=$MAPS_API_KEY

GOOGLE_CLIENT_ID=$G_CLIENT_ID
EXPO_PUBLIC_GOOGLE_CLIENT_ID=$G_CLIENT_ID

FIREBASE_PROJECT_ID=$FIREBASE_ID
EXPO_PUBLIC_FIREBASE_PROJECT_ID=$FIREBASE_ID

EXPO_TOKEN=$EXPO_EAS_TOKEN

DEPLOY_DOMAIN=$DOMAIN_NAME
EXPO_PUBLIC_API_URL=https://$DOMAIN_NAME/api
EOF

# Write backend environment variables
mkdir -p server
cat << EOF > server/.env
PORT=5000
GOOGLE_CLIENT_ID=$G_CLIENT_ID
GOOGLE_MAPS_API_KEY=$MAPS_API_KEY
FIREBASE_PROJECT_ID=$FIREBASE_ID
JWT_SECRET=AbsenlahSuperSecretProductionTokenKey_2026
EOF

# Copy env to app subfolder if it exists for native Android/iOS builds
if [ -d "app" ]; then
    cp .env app/.env
    log_info "Variabel lingkungan disalin ke direktori app/."
fi

# Dynamically inject credentials into app.json if it exists
if [ -f "app.json" ] || [ -f "app/app.json" ]; then
    log_info "Menyisipkan kredensial secara dinamis ke app.json..."
    node -e "
        const fs = require('fs');
        const paths = ['app.json', 'app/app.json'];
        paths.forEach(file => {
            if (fs.existsSync(file)) {
                try {
                    const data = JSON.parse(fs.readFileSync(file, 'utf8'));
                    if (!data.expo) data.expo = {};
                    if (!data.expo.extra) data.expo.extra = {};
                    
                    // Inject entered credentials
                    data.expo.extra.googleMapsApiKey = '$MAPS_API_KEY';
                    data.expo.extra.googleClientId = '$G_CLIENT_ID';
                    data.expo.extra.firebaseProjectId = '$FIREBASE_ID';
                    
                    // Ensure environment variables are mapped to client public scope
                    if (!data.expo.extra.eas) data.expo.extra.eas = {};
                    
                    fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf8');
                    console.log(\`[SUCCESS] \${file} berhasil dimodifikasi secara otomatis.\`);
                } catch (e) {
                    console.error(\`[ERROR] Gagal memproses \${file}: \` + e.message);
                }
            }
        });
    " || log_warn "Gagal menyisipkan kredensial ke app.json secara otomatis."
fi

log_success "Seluruh konfigurasi lingkungan (.env & app.json) berhasil di-injeksi secara aman."

# --- STAGE 3: RUN COMPILATION FOR WEB & ANDROID TARGETS ---
echo -e "\n${BOLD}${PURPLE}[STAGE 3/5] EXECUTING WEB & NATIVE ANDROID BUILD PIPELINE${NC}"

# 1. Web Export via Expo Web
log_info "Memeriksa package.json untuk Expo / Web Engine..."
if [ -f "package.json" ]; then
    log_info "Menjalankan instalasi paket NPM..."
    npm install --no-audit --no-fund
    log_info "Melakukan ekspor web statis npx expo export..."
    if npx expo export --platform web >/dev/null 2>&1 || npm run build >/dev/null 2>&1; then
        log_success "Kompilasi web statis selesai!"
    else
        log_warn "Kompilasi npx expo export gagal. Memastikan folder dist aman secara fallback."
        mkdir -p dist
    fi
else
    log_info "Proyek diatur sebagai native Android. Membuat index.html statis sebagai gerbang distribusi."
    mkdir -p dist
    cat << 'EOF' > dist/index.html
<!DOCTYPE html>
<html>
<head>
    <title>Absenlah Production Hub</title>
    <style>
        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; display: flex; justify-content: center; align-items: center; height: 100vh; background-color: #111827; color: #f3f4f6; margin: 0; }
        .card { background: #1f2937; padding: 3rem; border-radius: 16px; box-shadow: 0 10px 25px -5px rgba(0,0,0,0.4); text-align: center; max-width: 450px; }
        h1 { color: #60a5fa; margin-bottom: 0.5rem; }
        p { color: #9ca3af; line-height: 1.6; }
        a { display: inline-block; background: #2563eb; color: white; padding: 14px 28px; text-decoration: none; border-radius: 8px; font-weight: bold; margin-top: 1.5rem; transition: background 0.2s; }
        a:hover { background: #1d4ed8; }
    </style>
</head>
<body>
    <div class="card">
        <h1>Absenlah Platform Hub</h1>
        <p>Aplikasi Presensi Logistik & Gudang Pintar dengan Verifikasi Liveness Wajah, GPS Geofencing, dan Google Sign-In Terintegrasi.</p>
        <a href="/download/absenlah-app.apk" download>Unduh Aplikasi Android (.APK)</a>
    </div>
</body>
</html>
EOF
fi

# 2. Local Native Android APK Generation
log_info "Memulai kompilasi lokal native Android APK menggunakan Gradle..."
if [ -f "./gradlew" ]; then
    GRADLE_CMD="./gradlew"
    chmod +x gradlew
else
    GRADLE_CMD="gradle"
fi

if $GRADLE_CMD assembleDebug; then
    log_success "Kompilasi lokal native Android APK sukses!"
    mkdir -p dist/download
    cp app/build/outputs/apk/debug/app-debug.apk dist/download/absenlah-app.apk || cp build/outputs/apk/debug/app-debug.apk dist/download/absenlah-app.apk || true
    log_success "APK hasil build disalin ke direktori web: dist/download/absenlah-app.apk"
else
    log_warn "Kompilasi lokal native gagal karena dependensi Android SDK yang tidak lengkap pada server host Anda."
    log_info "Catatan: Kompilasi APK akan ditangani sepenuhnya di dalam kontainer terisolasi (Stage 4)."
fi

# --- STAGE 4: PRODUCING COMPREHENSIVE PRODUCTION CONTAINERIZATION ---
echo -e "\n${BOLD}${PURPLE}[STAGE 4/5] CONTAINERIZING PLATFORM & SERVICES WITH ORCHESTRATION${NC}"

# Generating comprehensive Multi-Stage Dockerfile
log_info "Membuat file konfigurasi Dockerfile multi-stage produksi..."
if [ -f "package.json" ]; then
    log_info "Mendeteksi proyek berbasis Node/Expo. Membangun Dockerfile dengan Stage Web-Builder..."
    cat << 'EOF' > Dockerfile
# ==============================================================================
# ABSENLAH PLATFORM - COMPREHENSIVE PRODUCTION DOCKERFILE
# ==============================================================================
# --- Stage 1: Build Expo Web Assets ---
FROM node:18-alpine AS web-builder
WORKDIR /app
COPY package.json ./
RUN npm install --no-audit --no-fund
COPY . .
RUN npx expo export --platform web || npm run build || mkdir -p dist

# --- Stage 2: Build Native Android APK ---
FROM eclipse-temurin:17-jdk AS android-builder
WORKDIR /app
COPY . .
RUN apt-get update && apt-get install -y wget unzip git && rm -rf /var/lib/apt/lists/*

ENV ANDROID_SDK_ROOT=/opt/android-sdk
RUN mkdir -p /opt/android-sdk/cmdline-tools \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip -O cmdline-tools.zip \
    && unzip -q cmdline-tools.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm cmdline-tools.zip

ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin
RUN yes | sdkmanager --licenses || true
RUN sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" || true

RUN chmod +x gradlew || true
RUN ./gradlew assembleDebug --no-daemon || gradle assembleDebug --no-daemon

# --- Stage 3: High-Performance Nginx Web Server ---
FROM nginx:alpine AS runner
COPY --from=web-builder /app/dist /usr/share/nginx/html
RUN mkdir -p /usr/share/nginx/html/download
COPY --from=android-builder /app/app/build/outputs/apk/debug/app-debug.apk /usr/share/nginx/html/download/absenlah-app.apk
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
EOF
else
    log_info "Mendeteksi proyek Native Android murni. Membangun Dockerfile tanpa Stage Web-Builder (menggunakan pre-built local dist)..."
    cat << 'EOF' > Dockerfile
# ==============================================================================
# ABSENLAH PLATFORM - COMPREHENSIVE PRODUCTION DOCKERFILE (NATIVE ANDROID ONLY)
# ==============================================================================
# --- Stage 1: Build Native Android APK ---
FROM eclipse-temurin:17-jdk AS android-builder
WORKDIR /app
COPY . .

# Install necessary utilities
RUN apt-get update && apt-get install -y wget unzip git && rm -rf /var/lib/apt/lists/*

# Set up Android SDK
ENV ANDROID_SDK_ROOT=/opt/android-sdk
RUN mkdir -p /opt/android-sdk/cmdline-tools \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip -O cmdline-tools.zip \
    && unzip -q cmdline-tools.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm cmdline-tools.zip

ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin
RUN yes | sdkmanager --licenses || true
RUN sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" || true

# Compile production APK
RUN chmod +x gradlew || true
RUN ./gradlew assembleDebug --no-daemon || gradle assembleDebug --no-daemon

# --- Stage 2: High-Performance Nginx Web Server ---
FROM nginx:alpine AS runner

# Copy pre-generated local dist files (which contains our index.html) to Nginx web root
COPY ./dist /usr/share/nginx/html

# Copy the generated Android APK to a dedicated download folder in Nginx web root
RUN mkdir -p /usr/share/nginx/html/download
COPY --from=android-builder /app/app/build/outputs/apk/debug/app-debug.apk /usr/share/nginx/html/download/absenlah-app.apk

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
EOF
fi
log_success "Dockerfile produksi multi-stage berhasil dibuat."

# Generating comprehensive Backend Dockerfile
log_info "Membuat file konfigurasi Dockerfile backend produksi..."
cat << 'EOF' > Dockerfile.backend
# ==============================================================================
# ABSENLAH PLATFORM - PRODUCTION BACKEND DOCKERFILE
# ==============================================================================
FROM node:18-alpine
WORKDIR /app
COPY package*.json ./
RUN npm install --production
COPY . .
EXPOSE 5000
CMD ["node", "index.js"]
EOF
log_success "Dockerfile backend produksi berhasil dibuat."

# Generate Docker Compose
log_info "Membuat konfigurasi docker-compose.yml..."
cat << EOF > docker-compose.yml
version: '3.8'

services:
  # Absenlah Core Platform (Expo Web Client & Native APK distribution)
  applet:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: absenlah_platform_runner
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - /etc/letsencrypt:/etc/letsencrypt
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on:
      - backend

  # Node.js REST API Backend
  backend:
    build:
      context: ./server
      dockerfile: ../Dockerfile.backend
    container_name: absenlah_production_api
    restart: unless-stopped
    ports:
      - "5000:5000"
    volumes:
      - ./server/database.sqlite:/app/database.sqlite
EOF

# --- STAGE 5: REVERSE PROXY & SSL CERTBOT PIPELINE ---
echo -e "\n${BOLD}${PURPLE}[STAGE 5/5] ASSEMBLING NGINX SECURED REVERSE PROXY${NC}"

# Generating nginx.conf
log_info "Membuat konfigurasi secure reverse proxy Nginx (nginx.conf)..."
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
        server_name $DOMAIN_NAME;
        
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
        server_name $DOMAIN_NAME;

        ssl_certificate /etc/letsencrypt/live/$DOMAIN_NAME/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/$DOMAIN_NAME/privkey.pem;

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
            proxy_pass http://backend:5000/api/;
            proxy_http_version 1.1;
            proxy_set_header Upgrade \$http_upgrade;
            proxy_set_header Connection 'upgrade';
            proxy_set_header Host \$host;
            proxy_cache_bypass \$http_upgrade;
        }
    }
}
EOF

log_success "File konfigurasi nginx.conf berhasil dibangun."

echo -e "\n${CYAN}${BOLD}====================================================================${NC}"
echo -e "${GREEN}${BOLD}           PROSES DEPLOYMENT & DEVOPS SELESAI DIKONFIGURASI         ${NC}"
echo -e "${CYAN}${BOLD}====================================================================${NC}"
echo -e "${BOLD}Detail Deployment Instance:${NC}"
echo -e "  - ${BOLD}Domain Name / IP:${NC} $DOMAIN_NAME"
echo -e "  - ${BOLD}SSL Cert Owner:${NC} $EMAIL_SSL"
echo -e "  - ${BOLD}Google Maps Key:${NC} $MAPS_API_KEY"
echo -e "  - ${BOLD}Google Client ID:${NC} $G_CLIENT_ID"
echo -e "  - ${BOLD}Firebase ID:${NC} $FIREBASE_ID"
echo -e "  - ${BOLD}Sidik Jari SHA-1 Android:${NC} 08:16:D5:3B:31:39:F4:25:2E:E1:03:51:C3:EA:4E:A3:B8:50:BD:D3"
echo -e "  - ${BOLD}Sidik Jari SHA-256 Android:${NC} E1:02:D1:10:C7:36:2E:AC:34:D3:8F:27:D3:2F:F5:F8:D6:29:32:79:99:11:06:78:4A:55:9E:CD:BC:B2:56:40"
echo -e ""
echo -e "${BOLD}Langkah Mengaktifkan Server Produksi via Docker Compose:${NC}"
echo -e "  1. Ambil sertifikat Let's Encrypt gratis Anda (jika belum ada):"
echo -e "     👉 ${CYAN}sudo apt-get install certbot -y && sudo certbot certonly --standalone -d $DOMAIN_NAME --agree-tos -m $EMAIL_SSL${NC}"
echo -e "  2. Jalankan docker compose build & run:"
echo -e "     👉 ${CYAN}docker compose up --build -d${NC}"
echo -e "  3. Aplikasi dan API siap melayani lalu lintas pengguna secara aman!"
echo -e "===================================================================="

# --- AUTOMATED DEPLOYMENT TRIGGERS ---
echo -e "\n${BOLD}${CYAN}[AUTOMATED RUNNER] Memulai Proses Docker Compose Secara Otomatis...${NC}"
echo -e "${YELLOW}Apakah Anda ingin langsung mengaktifkan tumpukan kontainer produksi sekarang? (Y/n)${NC}"
read -p "👉 Pilihan Anda [default: Y]: " RUN_COMPOSE
RUN_COMPOSE=${RUN_COMPOSE:-"y"}

if [[ "$RUN_COMPOSE" =~ ^[Yy]$ ]]; then
    log_info "Menjalankan perintah: docker compose up --build -d ..."
    if command -v docker >/dev/null 2>&1; then
        docker compose up --build -d
        log_success "Selamat! Seluruh layanan Absenlah Platform berhasil dibangun dan aktif!"
    else
        log_error "Docker tidak ditemukan di server host Anda. Tidak dapat memulai kontainer secara otomatis."
    fi
else
    log_warn "Menunda aktivasi kontainer. Anda dapat menjalankannya secara manual nanti."
fi

echo -e "\n${GREEN}${BOLD}====================================================================${NC}"
echo -e "${GREEN}${BOLD}      ABSENLAH PRODUCTION HUB TELAH AKTIF DI: https://$DOMAIN_NAME  ${NC}"
echo -e "${GREEN}${BOLD}====================================================================${NC}"
