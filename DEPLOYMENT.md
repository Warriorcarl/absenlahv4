# Absenlah Enterprise Deployment Guide

This document provides step-by-step instructions for deploying the Absenlah Enterprise attendance system on a private Ubuntu 22.04 LTS server.

## Server Prerequisites

- **OS**: Ubuntu 20.04/22.04 LTS (Recommended)
- **RAM**: Minimum 4GB (8GB recommended for build pipeline)
- **Docker**: Version 20.10+
- **Node.js**: Version 18.x
- **Ports**: 80 (HTTP), 443 (HTTPS), 8080 (Backend API)

## Automated Deployment via All-in-One Script

We provide a comprehensive script that automates OS updates, dependency installation (Docker, Node, Nginx), backend deployment, and Android release build.

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/your-repo/absenlah.git
   cd absenlah
   ```

2. **Run the Deployer Script as Root**:
   ```bash
   chmod +x app/setup_absenlah.sh
   sudo ./app/setup_absenlah.sh
   ```

### Deployment Flow:
- **Environment Wizard**: The script will prompt you for your Domain, Email (for SSL), and Backend Port.
- **Docker Orchestration**: Starts Postgres and the Node.js backend container.
- **Reverse Proxy**: Configures Nginx to point to the backend and sets up placeholders for SSL.
- **Android Build**: Compiles the project using Gradle and outputs a **Release APK** to `build_output/absenlah_enterprise_release.apk`.

## Manual Configuration

If you wish to configure the environment manually:

1. **Backend**: Navigate to `backend/`, run `npm install`, then `npm start`.
2. **Database**: Start a Postgres instance and set `DB_URI` in `.env`.
3. **Docker**: Run `docker-compose up -d`.

## Security Features

1. **Device ID Binding**: Captured automatically on first login. Admins can unbind IDs via the mobile Admin Dashboard.
2. **Liveness Verification**: Powered by ML Kit Face Detection. Ensure the mobile app has Camera permissions.
3. **Force Password Change**: Default admin is `administrator` / `admin123`. The system forces a password reset on first login.
