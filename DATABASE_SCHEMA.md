# Absenlah Enterprise Database Schema

This document outlines the database schema and enterprise logic for the Absenlah attendance application. The system is built using Room Persistence Library (SQLite) on the mobile side, mimicking the enterprise-grade complexity of Hadirr.com.

## Core Entities & Tables

### 1. `pekerja` (Employees)
Stores user profiles, roles, and security bindings.
- `id` (PK): Unique worker ID.
- `username` / `email`: Login credentials.
- `passwordHash`: Password storage (force change on first login).
- `role`: `admin`, `supervisor`, or `pekerja`.
- `deviceId`: **Hardware Binding ID**. Restricts account to one physical device.
- `totalLeaveQuota`: Remaining leave days (SOP starts at 4/month).
- `isAvailable`: Real-time status for Couriers.
- `lastKnownLatitude` / `lastKnownLongitude`: Last tracked GPS position.

### 2. `attendance_logs` (Daily Records)
Tracks all check-in/out events and calculated SOP penalties.
- `pekerjaId` (FK): Link to `pekerja`.
- `date`: YYYY-MM-DD.
- `checkInTime` / `checkOutTime`: Timestamps.
- `checkInStatus`: `ON_TIME` or `LATE`.
- `shiftType`: `STANDARD` or `DYNAMIC` (Dynamic locks 10h window if check-in < 10 AM).
- `checkInFine`: Tiered fines (Rp5k - Rp50k+).
- `checkInBonus`: **Discipline Bonus** (Rp20,000 if on-time).
- `latenessMitigationType`: Tracks how lateness was resolved (`REDUCE_LATENESS`, `REDUCE_LEAVE`, etc.).
- `isManualEntry`: Flag for Courier outside-geofence check-ins.
- `arrivedAtWarehouse`: Verification for courier physical arrival.

### 3. `user_stats` (Monthly Counters)
Real-time stats used to enforce monthly SOP limits.
- `pekerjaId` (FK): Link to `pekerja`.
- `month`: YYYY-MM.
- `latenessCount`: Counter for the **3rd Lateness Rule** (3rd late = leave deduction).
- `earlyDeparturesCount`: Max 3 per month limit.
- `emergencyLogsCount`: Max 2 per 6 months limit.
- `totalFinesAmount` / `totalBonusesAmount`: Financial summaries.

### 4. `dynamic_config` (Enterprise Rule Engine)
Linked to the Admin UI for real-time logic updates without code changes.
- `key` (PK): Config key (e.g., `discipline_bonus_amt`, `fine_11_30`).
- `value`: Current value.
- `valueType`: Data type for parsing (INT, STRING).

### 5. `leave_requests` (ESS Portal)
- `pekerjaId` (FK): Link to `pekerja`.
- `leaveType`: `STANDARD` or `EMERGENCY`.
- `status`: `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`.
- `emergencyPhotoProofPath`: Mandatory for emergency leave.

---

## Enterprise Logic Flows

### Security & Anti-Spoofing
1. **Device ID Binding**: On first login, the `deviceId` is captured and saved to the `pekerja` table. Subsequent logins on different hardware are rejected.
2. **Liveness Detection**: During selfie check-ins, `checkInLivenessVerified` is set only after successful blink/smile detection via ML Kit.

### The 3rd Lateness Rule
- When a worker is `LATE`, the system checks `user_stats.latenessCount`.
- If count is `2`, the 3rd lateness (current) triggers a deduction in `pekerja.totalLeaveQuota` instead of a cash fine.
- If count is `3+`, standard tiered fines resume.

### Dynamic Shift Locking
- **Standard**: 10:00 AM - 08:00 PM.
- **Dynamic**: If `checkInTime` < 10:00 AM, `expectedCheckOutTime` is automatically set to `checkInTime + 10 hours`. Overtime calculation starts 1 minute after this window.
