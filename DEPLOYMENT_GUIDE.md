# Panduan Konfigurasi Produksi All-in-One Absenlah Platform

Panduan komprehensif ini menyediakan petunjuk operasional langkah-demi-langkah untuk menyiapkan, menyesuaikan, dan menyebarkan (deploy) platform presensi logistik & gudang pintar **Absenlah** di server Linux produksi Anda secara mandiri menggunakan kontainer Docker.

---

## 1. Daftar Kunci & Variabel yang Dibutuhkan

Sebelum menjalankan installer otomatis (`/deploy.sh` atau `/install.sh`), pastikan Anda telah menyiapkan parameter penting berikut:

| Kunci / Parameter | Deskripsi | Diperoleh Dari | Contoh Nilai / Format |
|---|---|---|---|
| **Domain Server** | Alamat domain publik Anda yang diarahkan (A Record) ke IP server Anda. | Registrar Domain (Cloudflare, GoDaddy, dll.) | `absenlah.perusahaan.com` |
| **Email SSL** | Alamat email administratif untuk pendaftaran SSL otomatis Certbot Let's Encrypt. | Email Anda | `admin@perusahaan.com` |
| **Google Maps API Key** | Kunci API untuk merender modul peta interaktif, melakukan geofencing, dan menghitung radius presensi pekerja. | [Google Cloud Console](https://console.cloud.google.com) | `AIzaSyB1-Xo2YmPz987654...` |
| **Google Client ID** | Kredensial OAuth 2.0 Web Client ID untuk memproses alur masuk aman satu-klik Google Sign-In. | [Google Cloud Credentials](https://console.cloud.google.com/apis/credentials) | `1092837492-xxxyyy.apps.googleusercontent.com` |
| **Firebase Project ID** | Pengenal unik proyek Firebase untuk sinkronisasi database awan & otentikasi. | [Firebase Console Settings](https://console.firebase.google.com) | `absenlah-prod-123` |
| **Expo EAS Credentials** | Token otentikasi cloud untuk menjalankan EAS Build (Expo Application Services) jika mengompilasi APK lewat awan. | [Expo Dev Dashboard](https://expo.dev) | `eas-token-abc-123` |
| **Sidik Jari SHA-1** | Sidik jari sertifikat penandatanganan aplikasi untuk dipasang di Firebase Console (Google Sign-In). | Output Gradle Keystore | `08:16:D5:3B:31:39:F4:25:2E:E1:03:51:C3:EA:4E:A3:B8:50:BD:D3` |
| **Sidik Jari SHA-256** | Diperlukan oleh Google Play Console & Integrasi Deep Linking / App Links. | Output Gradle Keystore | `E1:02:D1:10:C7:36:2E:AC:34:D3:8F:27:D3:2F:F5:F8:D6:29:32:79:99:11:06:78:4A:55:9E:CD:BC:B2:56:40` |

---

## 2. Persiapan Server Linux (Debian/Ubuntu)

Installer akan mengotomatiskan seluruh alur kerja, namun Anda perlu memastikan server Anda dalam kondisi segar dan memiliki akses internet publik stabil.

### Langkah 1: Kloning Repositori & Hak Akses
Salin berkas ke server Anda, lalu atur izin eksekusi berkas script installer:
```bash
chmod +x deploy.sh
```

### Langkah 2: Jalankan Installer All-in-One
Cukup jalankan satu perintah berikut di terminal server Anda:
```bash
sudo ./deploy.sh
```

Ikuti panduan interaktif di layar terminal untuk mengisi seluruh kunci/kredensial yang telah Anda siapkan di tabel atas.

---

## 3. Konfigurasi SSL Let's Encrypt & Nginx

Script akan menghasilkan file `nginx.conf` terenkripsi SSL dengan pengalihan lalu lintas otomatis dari HTTP (Port 80) ke HTTPS (Port 443).

Untuk mendapatkan sertifikat SSL gratis secara otomatis, jalankan Certbot di server Anda:
```bash
# Pasang Certbot
sudo apt-get update
sudo apt-get install -y certbot

# Ambil Sertifikat SSL Standalone Let's Encrypt
sudo certbot certonly --standalone -d absenlah.perusahaan.com --agree-tos -m admin@perusahaan.com --non-interactive
```

*Catatan: Lokasi sertifikat SSL akan disimpan secara standar di `/etc/letsencrypt/live/absenlah.perusahaan.com/` yang akan langsung dimuat secara dinamis oleh Docker Compose ke dalam kontainer.*

---

## 4. Orkestrasi Kontainer Docker Produksi

Setelah installer memproduksi `.env`, `Dockerfile`, dan `docker-compose.yml`, Anda hanya perlu menyalakan seluruh tumpukan (stack) layanan hanya dengan satu baris:

```bash
docker compose up --build -d
```

### Struktur Layanan Kontainer yang Berjalan:
1. **absenlah_platform_runner (Port 80/443)**: Server web berkinerja tinggi Nginx yang menyajikan bundel static web Expo dan membagikan unduhan APK Android langsung.
2. **absenlah_production_api (Port 5000)**: Backend REST API Node.js/Express yang menyimpan log presensi dan binding perangkat keras pekerja ke SQLite Database secara mandiri.

---

## 5. Pemasangan di Konsol Google & Firebase

Untuk memastikan fitur Google Sign-In bekerja dengan sempurna pada aplikasi Android:

1. Buka [Firebase Console](https://console.firebase.google.com).
2. Tambahkan Proyek Baru, atau hubungkan proyek yang sudah ada.
3. Masuk ke **Project Settings** > tab **General**.
4. Klik **Add App** > Pilih ikon **Android**.
5. Isi nama paket Android: `com.aistudio.absenlah.vqkhr` (atau sesuaikan dengan paket ID Anda).
6. Tempelkan **SHA-1 Fingerprint** berikut ke kolom pendaftaran:
   `08:16:D5:3B:31:39:F4:25:2E:E1:03:51:C3:EA:4E:A3:B8:50:BD:D3`
7. Unduh file `google-services.json` dan taruh di dalam folder `/app/` proyek Anda sebelum melakukan build produksi final.
8. Pastikan **Google Sign-In** diaktifkan pada menu **Authentication** > tab **Sign-in method** di konsol Firebase Anda.

---

## 6. Penyusunan APK Seluler (Expo EAS)

Untuk mendistribusikan build mobile langsung ke tangan pekerja via udara (Over-the-Air):
1. Masuk ke akun Expo Anda via terminal:
   ```bash
   npx expo login
   ```
2. Jalankan script build EAS terintegrasi yang telah kami siapkan:
   ```bash
   ./run_expo_build.sh
   ```
3. Unduh berkas APK preview final yang dihasilkan di dashboard Expo Anda dan pasang pada perangkat pekerja Anda secara aman.

---
Dengan tumpukan arsitektur ini, platform presensi cerdas Absenlah Anda kini sepenuhnya berjalan secara swadaya (self-hosted), berkecepatan tinggi, dienkripsi SSL end-to-end, dan siap digunakan oleh ratusan pengemudi dan staf logistik di gudang Anda!
