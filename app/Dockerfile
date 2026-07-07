# ==============================================================================
# Dockerfile to build Absenlah APK (Android Kotlin/Compose Environment)
# ==============================================================================
# This container encapsulates the Java 17 and Android SDK build environment.
# Run it to compile the Android app without installing Android Studio locally.
#
# Build command:
#   docker build -t absenlah-builder .
#
# Run command (compiles and outputs the APK to your local directory):
#   docker run --rm -v $(pwd)/dist:/app/app-dist absenlah-builder
# ==============================================================================

FROM openjdk:17-jdk-slim

# Install system dependencies including Node.js and npm
RUN apt-get update && apt-get install -y \
    wget \
    unzip \
    git \
    curl \
    gnupg \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Install Node.js v18 LTS
RUN curl -fsSL https://deb.nodesource.com/setup_18.x | bash - \
    && apt-get install -y nodejs \
    && node -v && npm -v

# Set Environment Variables
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Download and Install Android Command Line Tools
WORKDIR /opt
RUN mkdir -p android-sdk/cmdline-tools \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip -O cmdline-tools.zip \
    && unzip -q cmdline-tools.zip -d android-sdk/cmdline-tools \
    && mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest \
    && rm cmdline-tools.zip

# Accept Android SDK licenses
RUN mkdir -p ~/.android && touch ~/.android/repositories.cfg \
    && yes | sdkmanager --licenses

# Install essential SDK platforms and build tools
RUN sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# Set up Workspace
WORKDIR /workspace
COPY . .

# Run NPM install, Expo export preparation, and Gradle compilation by default
RUN if [ -f "package.json" ]; then npm install && (npx expo export --platform web || npm run build || true); fi

# Run compilation by default and export APK
CMD ./gradlew assembleDebug && \
    mkdir -p /app/app-dist && \
    cp app/build/outputs/apk/debug/app-debug.apk /app/app-dist/absenlah-app.apk && \
    echo "==========================================================" && \
    echo "✔ BERHASIL! APK disalin ke folder lokal /dist/absenlah-app.apk" && \
    echo "=========================================================="
