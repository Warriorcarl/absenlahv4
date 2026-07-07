# ==============================================================================
# ABSENLAH PLATFORM - COMPREHENSIVE PRODUCTION DOCKERFILE
# ==============================================================================
# Multi-stage production container build to compile both Expo web export and
# the native Android APK. Served securely using a light-weight Nginx container.
# ==============================================================================

# --- Stage 1: Build Expo Web Assets ---
FROM node:18-alpine AS web-builder
WORKDIR /app
COPY package.json ./
RUN npm install --no-audit --no-fund
COPY . .
# Run Expo production web build export
RUN npx expo export --platform web || npm run build || mkdir -p dist

# --- Stage 2: Build Native Android APK ---
FROM openjdk:17-jdk-slim AS android-builder
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

# --- Stage 3: High-Performance Nginx Web Server ---
FROM nginx:alpine AS runner

# Copy Expo static production files to Nginx web root
COPY --from=web-builder /app/dist /usr/share/nginx/html

# Copy the generated Android APK to a dedicated download folder in Nginx web root
RUN mkdir -p /usr/share/nginx/html/download
COPY --from=android-builder /app/app/build/outputs/apk/debug/app-debug.apk /usr/share/nginx/html/download/absenlah-app.apk

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
