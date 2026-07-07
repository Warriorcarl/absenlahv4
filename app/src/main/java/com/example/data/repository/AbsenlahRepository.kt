package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.db.AbsenlahDatabase
import com.example.data.entity.*
import com.example.data.rule.RuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class AbsenlahRepository(private val db: AbsenlahDatabase) {

    private val pekerjaDao = db.pekerjaDao()
    private val userStatsDao = db.userStatsDao()
    private val dynamicConfigDao = db.dynamicConfigDao()
    private val geofencedSiteDao = db.geofencedSiteDao()
    private val attendanceLogDao = db.attendanceLogDao()
    private val leaveRequestDao = db.leaveRequestDao()
    private val courierTaskDao = db.courierTaskDao()
    private val announcementDao = db.announcementDao()

    // Initialize database default values
    suspend fun seedDatabase() = withContext(Dispatchers.IO) {
        // Seed dynamic config if empty
        val existingConfigs = dynamicConfigDao.getAllConfigsSync()
        if (existingConfigs.isEmpty()) {
            val defaultConfigList = listOf(
                DynamicConfig("shift_start_hour", "10", "Standard shift start hour", "INT"),
                DynamicConfig("shift_start_minute", "0", "Standard shift start minute", "INT"),
                DynamicConfig("shift_end_hour", "20", "Standard shift end hour (08:00 PM)", "INT"),
                DynamicConfig("shift_end_minute", "0", "Standard shift end minute", "INT"),
                DynamicConfig("lateness_threshold_mins", "11", "Mins after shift start when lateness fine starts", "INT"),
                DynamicConfig("discipline_bonus_amt", "20000", "Discipline bonus in IDR", "INT"),
                
                // Fines
                DynamicConfig("fine_11_30", "5000", "Lateness fine for 10:11 - 10:30", "INT"),
                DynamicConfig("fine_31_60", "10000", "Lateness fine for 10:31 - 11:00", "INT"),
                DynamicConfig("fine_61_90", "15000", "Lateness fine for 11:01 - 11:30", "INT"),
                DynamicConfig("fine_91_120", "20000", "Lateness fine for 11:31 - 12:00", "INT"),
                DynamicConfig("fine_121_150", "30000", "Lateness fine for 12:01 - 12:30", "INT"),
                DynamicConfig("fine_151_180", "40000", "Lateness fine for 12:31 - 01:00", "INT"),
                DynamicConfig("fine_181_210", "50000", "Lateness fine for 01:01 - 01:30", "INT"),
                DynamicConfig("fine_incremental_per_30m", "10000", "Incremental fine per 30m after 01:30", "INT"),

                // Overtime Rates
                DynamicConfig("ot_30m", "5000", "Overtime rate for 30m", "INT"),
                DynamicConfig("ot_60m", "10000", "Overtime rate for 60m", "INT"),
                DynamicConfig("ot_90m", "15000", "Overtime rate for 90m", "INT"),
                DynamicConfig("ot_120m", "20000", "Overtime rate for 120m", "INT"),
                DynamicConfig("ot_150m", "30000", "Overtime rate for 150m", "INT"),
                DynamicConfig("ot_180m", "30000", "Overtime rate for 180m", "INT"),
                DynamicConfig("ot_210m", "40000", "Overtime rate for 210m", "INT"),
                DynamicConfig("ot_240m", "50000", "Overtime rate for 240m", "INT"),
                DynamicConfig("ot_incremental_per_30m", "10000", "Overtime incremental rate per 30m after 4 hours", "INT")
            )
            dynamicConfigDao.insertConfigs(defaultConfigList)
        }

        // Seed default administrator if not exists
        val existingAdmin = pekerjaDao.getPekerjaByUsername("administrator")
        if (existingAdmin == null) {
            val adminUser = Pekerja(
                username = "administrator",
                passwordHash = "admin123", // Default plain-text password (force change required)
                name = "Administrator Utama",
                division = "HQ",
                position = "Admin",
                deviceId = null,
                role = "admin",
                mustChangePassword = true,
                totalLeaveQuota = 4,
                email = "admin@absenlah.com"
            )
            pekerjaDao.insertPekerja(adminUser)

            // Seed a supervisor and a courier for demo purposes
            val supervisorUser = Pekerja(
                username = "supervisor",
                passwordHash = "super123",
                name = "Ahmad Supervisor",
                division = "Logistics",
                position = "Supervisor",
                deviceId = null,
                role = "supervisor",
                mustChangePassword = false,
                totalLeaveQuota = 4,
                email = "supervisor@absenlah.com"
            )
            pekerjaDao.insertPekerja(supervisorUser)

            val courierUser = Pekerja(
                username = "kurir1",
                passwordHash = "kurir123",
                name = "Budi Kurir",
                division = "Logistics",
                position = "Courier",
                deviceId = null,
                role = "pekerja",
                mustChangePassword = false,
                totalLeaveQuota = 4,
                email = "kurir1@absenlah.com",
                isAvailable = true
            )
            pekerjaDao.insertPekerja(courierUser)
        }

        // Seed initial announcement
        val existingAnnouncements = announcementDao.getAllAnnouncements().firstOrNull() ?: emptyList()
        if (existingAnnouncements.isEmpty()) {
            announcementDao.insertAnnouncement(
                Announcement(
                    title = "Selamat Datang di Absenlah Enterprise!",
                    content = "SOP Kerja Berlaku Mulai Hari Ini. Harap verifikasi liveness wajah Anda saat melakukan Check-In. Kuota cuti bulanan Anda disetel ke 4 kali per bulan secara ketat.",
                    publishedAt = System.currentTimeMillis(),
                    senderName = "System",
                    targetDivision = "ALL"
                )
            )
        }

        // Seed a default geofenced warehouse site
        val existingSites = geofencedSiteDao.getAllSites().firstOrNull() ?: emptyList()
        if (existingSites.isEmpty()) {
            geofencedSiteDao.insertSite(
                GeofencedSite(
                    name = "Gudang Utama Jakarta",
                    latitude = -6.2088, // Central Jakarta coordinates
                    longitude = 106.8456,
                    radiusInMeters = 100.0
                )
            )
        }
    }

    // Helper to fetch dynamic configuration map
    private suspend fun getConfigMap(): Map<String, String> {
        return dynamicConfigDao.getAllConfigsSync().associate { it.key to it.value }
    }

    // Helper to await Google Play services Task
    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                cont.resume(task.result, onCancellation = {})
            } else {
                cont.resumeWithException(task.exception ?: RuntimeException("Task failed"))
            }
        }
    }

    // AUTH & DEVISE BINDING METHODS
    suspend fun login(identifier: String, passwordRaw: String): LoginResult = withContext(Dispatchers.IO) {
        // Try logging in via Firebase Auth first (using email if possible)
        if (identifier.contains("@")) {
            try {
                val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                val taskResult = auth.signInWithEmailAndPassword(identifier, passwordRaw).awaitTask()
                val firebaseUser = taskResult.user
                if (firebaseUser != null) {
                    var user = pekerjaDao.getPekerjaByEmail(identifier)
                    if (user == null) {
                        user = Pekerja(
                            username = identifier.substringBefore("@"),
                            passwordHash = passwordRaw,
                            name = identifier.substringBefore("@").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                            division = "Logistics",
                            position = "Courier",
                            deviceId = null,
                            role = "pekerja",
                            mustChangePassword = false,
                            googleId = null,
                            email = identifier
                        )
                        pekerjaDao.insertPekerja(user)
                        user = pekerjaDao.getPekerjaByEmail(identifier)
                    }
                    if (user != null) {
                        return@withContext LoginResult.Success(user)
                    }
                }
            } catch (e: Exception) {
                // If it is an IllegalStateException (Firebase default not initialized), fall back gracefully
                if (e is IllegalStateException || e.message?.contains("Default FirebaseApp") == true) {
                    Log.d("FirebaseLogin", "Firebase not initialized, falling back to secure local DB")
                } else {
                    return@withContext LoginResult.Failure("Firebase Auth Error: ${e.localizedMessage ?: "Gagal terhubung."}")
                }
            }
        }

        // Secure offline local fallback authentication
        val user = pekerjaDao.getPekerjaByUsername(identifier)
            ?: pekerjaDao.getPekerjaByEmail(identifier)
            ?: return@withContext LoginResult.Failure("User tidak ditemukan (User not found)")
        
        if (user.passwordHash == passwordRaw) {
            return@withContext LoginResult.Success(user)
        } else {
            return@withContext LoginResult.Failure("Password salah (Incorrect password)")
        }
    }

    suspend fun loginWithGoogle(email: String, googleId: String, name: String): LoginResult = withContext(Dispatchers.IO) {
        var user = pekerjaDao.getPekerjaByGoogleId(googleId)
            ?: pekerjaDao.getPekerjaByEmail(email)
        
        if (user == null) {
            user = Pekerja(
                username = email.substringBefore("@"),
                passwordHash = "google_auth_linked_" + System.currentTimeMillis(),
                name = name,
                division = "Logistics",
                position = "Courier",
                deviceId = null,
                role = "pekerja",
                mustChangePassword = false,
                totalLeaveQuota = 4,
                email = email,
                googleId = googleId,
                isAvailable = true
            )
            pekerjaDao.insertPekerja(user)
            user = pekerjaDao.getPekerjaByGoogleId(googleId)!!
        }
        return@withContext LoginResult.Success(user)
    }

    suspend fun registerPekerja(pekerja: Pekerja) = withContext(Dispatchers.IO) {
        pekerjaDao.insertPekerja(pekerja)
    }

    suspend fun updatePekerja(pekerja: Pekerja) = withContext(Dispatchers.IO) {
        pekerjaDao.updatePekerja(pekerja)
    }

    suspend fun releaseDeviceBinding(pekerjaId: Int): Boolean = withContext(Dispatchers.IO) {
        val userFlow = pekerjaDao.getPekerjaById(pekerjaId).firstOrNull()
        if (userFlow != null) {
            pekerjaDao.updatePekerja(userFlow.copy(deviceId = null))
            return@withContext true
        }
        return@withContext false
    }

    fun getPekerjaFlow(id: Int): Flow<Pekerja?> = pekerjaDao.getPekerjaById(id)

    fun getAllPekerja(): Flow<List<Pekerja>> = pekerjaDao.getAllPekerja()

    // LOCATION METHODS
    fun getAllSites(): Flow<List<GeofencedSite>> = geofencedSiteDao.getAllSites()

    suspend fun addSite(site: GeofencedSite) = withContext(Dispatchers.IO) {
        geofencedSiteDao.insertSite(site)
    }

    suspend fun deleteSite(site: GeofencedSite) = withContext(Dispatchers.IO) {
        geofencedSiteDao.deleteSite(site)
    }

    // CONFIG METHODS
    fun getAllConfigs(): Flow<List<DynamicConfig>> = dynamicConfigDao.getAllConfigs()

    suspend fun updateConfig(config: DynamicConfig) = withContext(Dispatchers.IO) {
        dynamicConfigDao.insertConfig(config)
    }

    // USER STATS METHODS
    fun getUserStats(pekerjaId: Int, month: String): Flow<UserStats?> {
        return userStatsDao.getUserStats(pekerjaId, month)
    }

    private suspend fun getOrCreateUserStats(pekerjaId: Int, month: String): UserStats {
        var stats = userStatsDao.getUserStatsSync(pekerjaId, month)
        if (stats == null) {
            stats = UserStats(pekerjaId = pekerjaId, month = month)
            userStatsDao.insertUserStats(stats)
            stats = userStatsDao.getUserStatsSync(pekerjaId, month)!!
        }
        return stats
    }

    // ATTENDANCE LOGS METHODS
    fun getLogsForPekerja(pekerjaId: Int): Flow<List<AttendanceLog>> = attendanceLogDao.getLogsForPekerja(pekerjaId)

    fun getAllLogs(): Flow<List<AttendanceLog>> = attendanceLogDao.getAllLogs()

    fun getLogForDate(pekerjaId: Int, date: String): Flow<AttendanceLog?> = attendanceLogDao.getLogForDate(pekerjaId, date)

    // COURIER FORCE LOCATION TRACKING
    suspend fun updateCourierLocation(pekerjaId: Int, lat: Double, lon: Double) = withContext(Dispatchers.IO) {
        val user = pekerjaDao.getPekerjaById(pekerjaId).firstOrNull()
        if (user != null) {
            var finalAvailable = user.isAvailable
            
            // Check if they came back to warehouse to auto toggle "AVAILABLE" if they are currently not
            if (!user.isAvailable) {
                val sites = geofencedSiteDao.getAllSites().firstOrNull() ?: emptyList()
                for (site in sites) {
                    val distance = calculateDistanceInMeters(lat, lon, site.latitude, site.longitude)
                    if (distance <= site.radiusInMeters) {
                        finalAvailable = true
                        break
                    }
                }
            }

            pekerjaDao.updatePekerja(user.copy(
                lastKnownLatitude = lat,
                lastKnownLongitude = lon,
                isAvailable = finalAvailable
            ))
        }
    }

    suspend fun setLocationForceOn(pekerjaId: Int, forceOn: Boolean) = withContext(Dispatchers.IO) {
        val user = pekerjaDao.getPekerjaById(pekerjaId).firstOrNull()
        if (user != null) {
            pekerjaDao.updatePekerja(user.copy(isLocationForceOn = forceOn))
        }
    }

    // CORE ATTENDANCE SOP: CHECK-IN
    suspend fun checkIn(
        pekerjaId: Int,
        latitude: Double,
        longitude: Double,
        selfiePath: String?,
        isLivenessVerified: Boolean,
        deviceId: String
    ): CheckInResponse = withContext(Dispatchers.IO) {
        val dateString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val monthString = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())

        val existingLog = attendanceLogDao.getLogForDateSync(pekerjaId, dateString)
        if (existingLog != null) {
            return@withContext CheckInResponse.Failure("Anda sudah melakukan check-in hari ini!")
        }

        val pekerja = db.pekerjaDao().getPekerjaById(pekerjaId).firstOrNull()
            ?: return@withContext CheckInResponse.Failure("Pekerja tidak ditemukan!")

        // Device Binding enforcement
        if (pekerja.deviceId != null && pekerja.deviceId != deviceId) {
            return@withContext CheckInResponse.Failure(
                "Gagal: Akun terikat pada perangkat lain! (Bound device ID: ${pekerja.deviceId})"
            )
        }

        // Auto bind on first check-in
        if (pekerja.deviceId == null) {
            pekerjaDao.updatePekerja(pekerja.copy(deviceId = deviceId))
        }

        // Geofencing Site check
        val sites = geofencedSiteDao.getAllSites().firstOrNull() ?: emptyList()
        var insideGeofence = false
        for (site in sites) {
            val distance = calculateDistanceInMeters(latitude, longitude, site.latitude, site.longitude)
            if (distance <= site.radiusInMeters) {
                insideGeofence = true
                break
            }
        }

        val isCourier = pekerja.position.equals("Courier", ignoreCase = true)
        val requiresManualApproval = !insideGeofence && isCourier

        if (!insideGeofence && !isCourier) {
            return@withContext CheckInResponse.Failure(
                "Gagal: Anda berada di luar radius geofence!"
            )
        }

        // Evaluate SOP timings using RuleEngine
        val nowMs = System.currentTimeMillis()
        val configs = getConfigMap()
        val stats = getOrCreateUserStats(pekerjaId, monthString)

        // RuleEngine evaluation
        val expectedCheckOutMs = RuleEngine.calculateExpectedCheckOut(nowMs, configs)
        val checkInEval = RuleEngine.evaluateCheckIn(
            checkInTimeMs = nowMs,
            latenessCountThisMonth = stats.latenessCount,
            hasLeaveQuota = pekerja.totalLeaveQuota > 0,
            configMap = configs
        )

        // Shift type
        val isShiftStartHour = configs["shift_start_hour"]?.toIntOrNull() ?: 10
        val shiftStartCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, isShiftStartHour)
            set(Calendar.MINUTE, 0)
        }
        val isDynamic = nowMs < shiftStartCal.timeInMillis
        val shiftType = if (isDynamic) "DYNAMIC" else "STANDARD"

        // 1. Check if check-in is after 14:00 (2 PM)
        val checkInCalendar = Calendar.getInstance().apply {
            timeInMillis = nowMs
        }
        val isAfter2PM = checkInCalendar.get(Calendar.HOUR_OF_DAY) >= 14

        var finalQuota = pekerja.totalLeaveQuota
        val isLate = checkInEval.status == "LATE" || isAfter2PM

        var leaveQuotaDeducted = false
        val finalLatenessMitigationType = when {
            isAfter2PM -> {
                leaveQuotaDeducted = true
                "REDUCE_LEAVE_2PM"
            }
            checkInEval.convertedToLeaveDeduction -> {
                leaveQuotaDeducted = true
                "REDUCE_LEAVE_3RD"
            }
            isLate -> "PENDING"
            else -> null
        }

        if (leaveQuotaDeducted) {
            finalQuota = (finalQuota - 1).coerceAtLeast(0)
            pekerjaDao.updatePekerja(pekerja.copy(totalLeaveQuota = finalQuota))
        }

        val finalCheckInStatus = if (isLate) "LATE" else "ON_TIME"

        val log = AttendanceLog(
            pekerjaId = pekerjaId,
            date = dateString,
            checkInTime = nowMs,
            checkInLatitude = latitude,
            checkInLongitude = longitude,
            checkInSelfiePath = selfiePath,
            checkInLivenessVerified = isLivenessVerified,
            checkInDeviceId = deviceId,
            checkInStatus = finalCheckInStatus,
            checkInFine = if (isAfter2PM) 0 else checkInEval.fine,
            checkInBonus = if (isLate) 0 else checkInEval.bonus,
            shiftType = shiftType,
            expectedCheckOutTime = expectedCheckOutMs,
            isManualEntry = requiresManualApproval,
            manualArrivalReason = if (requiresManualApproval) {
                "Kurir check-in luar radius geofence"
            } else if (isAfter2PM) {
                "Absen lewat jam 2 siang dianggap Libur"
            } else {
                null
            },
            manualArrivalConfirmed = false,
            arrivedAtWarehouse = !requiresManualApproval,
            latenessMitigationType = finalLatenessMitigationType
        )

        attendanceLogDao.insertLog(log)

        // Update UserStats: we DO NOT increment latenessCount for standard LATE because it's PENDING review!
        // We only update total fines / bonuses, and increment leaveQuotasUsed if a deduction occurred.
        val updatedStats = stats.copy(
            totalFinesAmount = stats.totalFinesAmount + (if (leaveQuotaDeducted) 0 else checkInEval.fine),
            totalBonusesAmount = stats.totalBonusesAmount + (if (isLate) 0 else checkInEval.bonus),
            leaveQuotasUsed = if (leaveQuotaDeducted) stats.leaveQuotasUsed + 1 else stats.leaveQuotasUsed,
            latenessCount = if (checkInEval.convertedToLeaveDeduction) stats.latenessCount + 1 else stats.latenessCount
        )
        userStatsDao.updateUserStats(updatedStats)

        val message = if (requiresManualApproval) {
            "Check-in Darurat Diajukan! Status: Diluar radius. Wajib sampai di gudang maks 2 jam!"
        } else if (isAfter2PM) {
            "Check-in Terlambat! Dianggap Libur karena melewati jam 14:00 (Potong Cuti)."
        } else {
            "Check-in Berhasil! Status: ${checkInEval.status}, Bonus Disiplin: Rp${checkInEval.bonus}, Denda: Rp${checkInEval.fine}"
        }

        return@withContext CheckInResponse.Success(message, log)
    }

    // COURIER MANUAL ARRIVAL ACTIONS
    suspend fun confirmCourierDelay(pekerjaId: Int): Boolean = withContext(Dispatchers.IO) {
        val dateString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val log = attendanceLogDao.getLogForDateSync(pekerjaId, dateString)
        if (log != null && log.isManualEntry) {
            attendanceLogDao.insertLog(log.copy(manualArrivalConfirmed = true))
            return@withContext true
        }
        return@withContext false
    }

    suspend fun registerCourierWarehouseArrival(pekerjaId: Int, latitude: Double, longitude: Double): Boolean = withContext(Dispatchers.IO) {
        val dateString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val log = attendanceLogDao.getLogForDateSync(pekerjaId, dateString)
        if (log != null) {
            // Verify if user is indeed in any geofence
            val sites = geofencedSiteDao.getAllSites().firstOrNull() ?: emptyList()
            var insideGeofence = false
            for (site in sites) {
                val distance = calculateDistanceInMeters(latitude, longitude, site.latitude, site.longitude)
                if (distance <= site.radiusInMeters) {
                    insideGeofence = true
                    break
                }
            }

            if (insideGeofence) {
                attendanceLogDao.insertLog(log.copy(
                    arrivedAtWarehouse = true,
                    arrivalTimeAtWarehouse = System.currentTimeMillis()
                ))
                return@withContext true
            }
        }
        return@withContext false
    }

    // CHECK & APPLY COURIER DEADLINE PENALTY (e.g. at Checkout or manually triggered)
    suspend fun verifyAndApplyCourierManualCheckInPenalties(pekerjaId: Int): String = withContext(Dispatchers.IO) {
        val dateString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val log = attendanceLogDao.getLogForDateSync(pekerjaId, dateString)
            ?: return@withContext "Belum absen hari ini."

        if (!log.isManualEntry || log.courierDelayPenalized || log.arrivedAtWarehouse) {
            return@withContext "Tidak ada penalti keterlambatan manual yang perlu diproses."
        }

        val now = System.currentTimeMillis()
        val timeDiffMins = (now - log.checkInTime) / (1000 * 60)

        // Check if more than 2 hours (120 mins) elapsed AND did not confirm delay
        val overTwoHoursAndNotConfirmed = timeDiffMins > 120 && !log.manualArrivalConfirmed

        // Check if past 14:00 (02:00 PM) today
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val isPast14 = currentHour >= 14

        if (overTwoHoursAndNotConfirmed || isPast14) {
            // Penalize: Denda denda 50k and potong jatah libur
            val pekerja = pekerjaDao.getPekerjaById(pekerjaId).firstOrNull() ?: return@withContext "Pekerja tidak ditemukan."
            val updatedQuota = (pekerja.totalLeaveQuota - 1).coerceAtLeast(0)
            pekerjaDao.updatePekerja(pekerja.copy(totalLeaveQuota = updatedQuota))

            val monthString = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
            val stats = getOrCreateUserStats(pekerjaId, monthString)
            userStatsDao.updateUserStats(stats.copy(
                totalFinesAmount = stats.totalFinesAmount + 50000,
                leaveQuotasUsed = stats.leaveQuotasUsed + 1
            ))

            attendanceLogDao.insertLog(log.copy(
                courierDelayPenalized = true,
                checkOutFine = log.checkOutFine + 50000
            ))

            return@withContext "Penalti Diberlakukan! Denda Rp50.000 & Pemotongan 1 Jatah Libur karena terlambat tiba di gudang (>2 jam / melewati jam 14:00)."
        }

        return@withContext "SOP Aman. Sisa waktu menuju batas kedatangan fisik: ${120 - timeDiffMins} menit."
    }

    // CHECK-OUT
    suspend fun checkOut(
        pekerjaId: Int,
        latitude: Double,
        longitude: Double
    ): CheckOutResponse = withContext(Dispatchers.IO) {
        val dateString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val monthString = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())

        // Run Courier penalties check before allowing checkout to ensure calculations are solid
        verifyAndApplyCourierManualCheckInPenalties(pekerjaId)

        val log = attendanceLogDao.getLogForDateSync(pekerjaId, dateString)
            ?: return@withContext CheckOutResponse.Failure("Gagal: Anda belum melakukan check-in hari ini!")

        if (log.checkOutTime != null) {
            return@withContext CheckOutResponse.Failure("Gagal: Anda sudah melakukan check-out hari ini!")
        }

        val pekerja = pekerjaDao.getPekerjaById(pekerjaId).firstOrNull()
            ?: return@withContext CheckOutResponse.Failure("Pekerja tidak ditemukan!")

        val nowMs = System.currentTimeMillis()
        val configs = getConfigMap()
        val stats = getOrCreateUserStats(pekerjaId, monthString)

        // Evaluate early checkout and overtime
        val earlyEval = RuleEngine.evaluateCheckOutEarly(nowMs, log.expectedCheckOutTime, configs)
        val otEval = RuleEngine.calculateOvertime(nowMs, log.expectedCheckOutTime, configs)

        var finalBonusDeduction = 0
        var updatedQuota = pekerja.totalLeaveQuota
        var isEarlyDeparture = earlyEval.isEarlyDeparture

        // Apply Early Departure Penalties:
        if (earlyEval.isViolationBefore5PM) {
            finalBonusDeduction = log.checkInBonus
            if (pekerja.totalLeaveQuota > 0) {
                updatedQuota = (updatedQuota - 1).coerceAtLeast(0)
                pekerjaDao.updatePekerja(pekerja.copy(totalLeaveQuota = updatedQuota))
            }
        }

        val updatedLog = log.copy(
            checkOutTime = nowMs,
            checkOutLatitude = latitude,
            checkOutLongitude = longitude,
            checkOutStatus = if (isEarlyDeparture) "EARLY_DEPARTURE" else if (otEval.overtimeMins > 0) "OVERTIME" else "NORMAL",
            checkOutFine = log.checkOutFine + (if (earlyEval.isViolationBefore5PM) 10000 else 0), // Add early check-out fine
            checkOutBonusDeducted = finalBonusDeduction,
            overtimeMins = otEval.overtimeMins,
            overtimeFineBonusDeduction = earlyEval.isViolationBefore5PM,
            overtimeApproved = if (otEval.overtimeMins > 0) false else null, 
            earlyDepartureApproved = if (isEarlyDeparture) false else null
        )

        attendanceLogDao.insertLog(updatedLog)

        // Update UserStats
        val updatedStats = stats.copy(
            earlyDeparturesCount = if (isEarlyDeparture) stats.earlyDeparturesCount + 1 else stats.earlyDeparturesCount,
            totalBonusesAmount = (stats.totalBonusesAmount - finalBonusDeduction).coerceAtLeast(0),
            totalFinesAmount = stats.totalFinesAmount + (if (earlyEval.isViolationBefore5PM) 10000 else 0),
            leaveQuotasUsed = if (earlyEval.isViolationBefore5PM && pekerja.totalLeaveQuota > 0) stats.leaveQuotasUsed + 1 else stats.leaveQuotasUsed
        )
        userStatsDao.updateUserStats(updatedStats)

        val detailMessage = buildString {
            append("Check-out Berhasil!")
            if (isEarlyDeparture) {
                append("\n- Peringatan: Pulang Cepat!")
                if (earlyEval.isViolationBefore5PM) {
                    append("\n- Pelanggaran SOP (sebelum 05:00 PM): Potong Bonus Disiplin & Potong 1 Jatah Libur!")
                }
            } else if (otEval.overtimeMins > 0) {
                append("\n- Lembur terdeteksi: ${otEval.overtimeMins} menit. Menunggu persetujuan Supervisor.")
            }
        }

        return@withContext CheckOutResponse.Success(detailMessage, updatedLog)
    }

    // LATENESS CUSTOM MITIGATION SELECTION BY SUPERVISOR/ADMIN
    suspend fun mitigateLateness(
        logId: Int,
        mitigationType: String, // "REDUCE_LATENESS", "REDUCE_LEAVE", "REDUCE_EMERGENCY", "EXCUSED"
        supervisorId: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val log = db.attendanceLogDao().getAllLogs().firstOrNull()?.find { it.id == logId }
            ?: return@withContext false

        val pekerja = pekerjaDao.getPekerjaById(log.pekerjaId).firstOrNull() ?: return@withContext false
        val monthString = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        val stats = getOrCreateUserStats(log.pekerjaId, monthString)

        var finalQuota = pekerja.totalLeaveQuota
        var finalStats = stats
        var finalFine = log.checkInFine
        var resolvedMitigationType = mitigationType

        when (mitigationType) {
            "REDUCE_LATENESS" -> {
                // jatah darurat telat hanya di gunakan jika jatah telat normal sudah habis.
                // kalau masih ada jatah telat, memotong jatah telat yang ada.
                if (stats.latenessCount < 3) {
                    finalStats = stats.copy(latenessCount = stats.latenessCount + 1)
                    resolvedMitigationType = "REDUCE_LATENESS"
                } else {
                    // Jatah telat normal habis (>= 3). Check emergency quota!
                    if (stats.emergencyLogsCount < 2) {
                        finalStats = stats.copy(
                            emergencyLogsCount = stats.emergencyLogsCount + 1,
                            totalFinesAmount = (stats.totalFinesAmount - log.checkInFine).coerceAtLeast(0)
                        )
                        finalFine = 0
                        resolvedMitigationType = "REDUCE_EMERGENCY"
                    } else {
                        // Emergency quota is also exhausted! Reduce leave quota
                        finalQuota = (finalQuota - 1).coerceAtLeast(0)
                        pekerjaDao.updatePekerja(pekerja.copy(totalLeaveQuota = finalQuota))
                        finalStats = stats.copy(leaveQuotasUsed = stats.leaveQuotasUsed + 1)
                        resolvedMitigationType = "REDUCE_LEAVE"
                    }
                }
            }
            "REDUCE_LEAVE" -> {
                finalQuota = (finalQuota - 1).coerceAtLeast(0)
                pekerjaDao.updatePekerja(pekerja.copy(totalLeaveQuota = finalQuota))
                finalStats = stats.copy(leaveQuotasUsed = stats.leaveQuotasUsed + 1)
                resolvedMitigationType = "REDUCE_LEAVE"
            }
            "REDUCE_EMERGENCY" -> {
                finalStats = stats.copy(
                    emergencyLogsCount = (stats.emergencyLogsCount + 1).coerceAtMost(2),
                    totalFinesAmount = (stats.totalFinesAmount - log.checkInFine).coerceAtLeast(0)
                )
                finalFine = 0
                resolvedMitigationType = "REDUCE_EMERGENCY"
            }
            "EXCUSED" -> {
                // Excused: does not reduce anything!
                finalStats = stats.copy(
                    totalFinesAmount = (stats.totalFinesAmount - log.checkInFine).coerceAtLeast(0)
                )
                finalFine = 0
                resolvedMitigationType = "EXCUSED"
            }
        }

        userStatsDao.updateUserStats(finalStats)
        attendanceLogDao.insertLog(log.copy(
            latenessMitigationType = resolvedMitigationType,
            latenessApprovedBy = supervisorId,
            checkInFine = finalFine
        ))

        return@withContext true
    }

    // SUPERVISOR CORRECTIONS
    suspend fun approveManualAttendance(
        logId: Int,
        supervisorId: Int,
        approved: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val log = db.attendanceLogDao().getAllLogs().firstOrNull()?.find { it.id == logId }
            ?: return@withContext false

        val now = System.currentTimeMillis()
        val timeDiffMins = (now - log.checkInTime) / (1000 * 60)

        // Strict 2-hour physical arrival limit at the warehouse
        if (timeDiffMins > 120L && !log.manualArrivalConfirmed) {
            Log.e("AbsenlahRepo", "Gagal approve: Batas 2 jam kedatangan fisik di gudang terlampaui.")
            return@withContext false
        }

        val updatedLog = log.copy(
            isManualEntry = false,
            manualApprovedBy = supervisorId,
            manualApprovedTime = now,
            manualArrivalReason = if (approved) "DISETUJUI SUPERVISOR" else "DITOLAK SUPERVISOR"
        )
        attendanceLogDao.insertLog(updatedLog)
        return@withContext true
    }

    suspend fun approveOvertime(
        logId: Int,
        supervisorId: Int,
        approved: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val log = db.attendanceLogDao().getAllLogs().firstOrNull()?.find { it.id == logId }
            ?: return@withContext false

        val updatedLog = log.copy(
            overtimeApproved = approved,
            overtimeApprovalBy = supervisorId
        )
        attendanceLogDao.insertLog(updatedLog)
        return@withContext true
    }

    suspend fun approveEarlyDeparture(
        logId: Int,
        supervisorId: Int,
        approved: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val log = db.attendanceLogDao().getAllLogs().firstOrNull()?.find { it.id == logId }
            ?: return@withContext false

        val updatedLog = log.copy(
            earlyDepartureApproved = approved,
            earlyDepartureApprovalBy = supervisorId
        )
        attendanceLogDao.insertLog(updatedLog)
        return@withContext true
    }

    // LEAVE REQUESTS
    fun getLeavesForDivision(division: String): Flow<List<LeaveRequest>> = leaveRequestDao.getLeavesByDivision(division)

    fun getLeavesForPekerja(pekerjaId: Int): Flow<List<LeaveRequest>> = leaveRequestDao.getLeavesByPekerja(pekerjaId)

    fun getAllLeaves(): Flow<List<LeaveRequest>> = leaveRequestDao.getAllLeaves()

    suspend fun submitLeaveRequest(
        pekerjaId: Int,
        dateString: String,
        leaveType: String, // "STANDARD", "EMERGENCY"
        emergencyPhotoProofPath: String?
    ): LeaveSubmitResult = withContext(Dispatchers.IO) {
        val pekerja = pekerjaDao.getPekerjaById(pekerjaId).firstOrNull()
            ?: return@withContext LeaveSubmitResult.Failure("Pekerja tidak ditemukan!")

        val monthString = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        val stats = getOrCreateUserStats(pekerjaId, monthString)

        // Same-position overlap prevention
        val overlappingLeaves = leaveRequestDao.getApprovedLeavesForDateAndPosition(dateString, pekerja.position)
        if (overlappingLeaves.isNotEmpty()) {
            return@withContext LeaveSubmitResult.Failure(
                "Gagal: Jatah libur hari ini sudah diambil oleh pekerja lain dengan posisi yang sama (${pekerja.position})!"
            )
        }

        val nowMs = System.currentTimeMillis()
        val configs = getConfigMap()
        val startHour = configs["shift_start_hour"]?.toIntOrNull() ?: 10
        val startMin = configs["shift_start_minute"]?.toIntOrNull() ?: 0

        val leaveFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val parsedLeaveDate = try { leaveFormat.parse(dateString) } catch (e: Exception) { Date() }

        val targetShiftStart = Calendar.getInstance().apply {
            time = parsedLeaveDate
            set(Calendar.HOUR_OF_DAY, startHour)
            set(Calendar.MINUTE, startMin)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Leave timing check
        if (leaveType == "STANDARD") {
            val twoHoursMs = 2 * 60 * 60 * 1000L
            if ((targetShiftStart.timeInMillis - nowMs) < twoHoursMs) {
                return@withContext LeaveSubmitResult.Failure(
                    "Gagal: Pengajuan cuti standard harus dilakukan minimal 2 jam sebelum shift dimulai!"
                )
            }
        } else if (leaveType == "EMERGENCY") {
            // Emergency: max 2 instances per 6 months
            if (stats.emergencyLogsCount >= 2) {
                return@withContext LeaveSubmitResult.Failure(
                    "Gagal: Limit jatah Emergency (2 kali per 6 bulan) telah habis!"
                )
            }
            if (emergencyPhotoProofPath.isNullOrEmpty()) {
                return@withContext LeaveSubmitResult.Failure(
                    "Gagal: Pengajuan Emergency wajib mengunggah bukti foto (Photo proof is mandatory)!"
                )
            }
        }

        val request = LeaveRequest(
            pekerjaId = pekerjaId,
            pekerjaName = pekerja.name,
            division = pekerja.division,
            position = pekerja.position,
            date = dateString,
            shiftStartTime = targetShiftStart.timeInMillis,
            createdAtTime = nowMs,
            leaveType = leaveType,
            emergencyPhotoProofPath = emergencyPhotoProofPath,
            status = "PENDING"
        )

        leaveRequestDao.insertLeaveRequest(request)

        // Increment stats if EMERGENCY
        if (leaveType == "EMERGENCY") {
            userStatsDao.updateUserStats(stats.copy(emergencyLogsCount = stats.emergencyLogsCount + 1))
        }

        return@withContext LeaveSubmitResult.Success("Cuti berhasil diajukan!", request)
    }

    suspend fun cancelLeaveRequest(leaveId: Int): LeaveCancelResult = withContext(Dispatchers.IO) {
        val leave = leaveRequestDao.getLeaveByIdSync(leaveId)
            ?: return@withContext LeaveCancelResult.Failure("Pengajuan cuti tidak ditemukan!")

        val now = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L

        if ((leave.shiftStartTime - now) < oneDayMs) {
            return@withContext LeaveCancelResult.Failure(
                "Gagal: Pembatalan hanya bisa dilakukan maksimal H-1 sebelum hari cuti dimulai!"
            )
        }

        val updatedLeave = leave.copy(status = "CANCELLED")
        leaveRequestDao.insertLeaveRequest(updatedLeave)

        return@withContext LeaveCancelResult.Success(
            "Cuti dibatalkan! Notifikasi push dikirim ke divisi ${leave.division}.",
            leave.division
        )
    }

    suspend fun updateLeaveStatus(leaveId: Int, status: String, approvedBy: Int, reason: String? = null) = withContext(Dispatchers.IO) {
        val leave = leaveRequestDao.getLeaveByIdSync(leaveId)
        if (leave != null) {
            val updated = leave.copy(status = status, approvedBy = approvedBy, rejectReason = reason)
            leaveRequestDao.insertLeaveRequest(updated)

            // Deduct standard leave quota
            if (status == "APPROVED" && leave.leaveType == "STANDARD") {
                val pekerja = pekerjaDao.getPekerjaById(leave.pekerjaId).firstOrNull()
                if (pekerja != null && pekerja.totalLeaveQuota > 0) {
                    pekerjaDao.updatePekerja(pekerja.copy(totalLeaveQuota = pekerja.totalLeaveQuota - 1))
                    
                    val monthString = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
                    val stats = getOrCreateUserStats(leave.pekerjaId, monthString)
                    userStatsDao.updateUserStats(stats.copy(leaveQuotasUsed = stats.leaveQuotasUsed + 1))
                }
            }
        }
    }

    // COURIER TASK TRACKING METHODS
    fun getTasksForCourier(courierId: Int): Flow<List<CourierTask>> = courierTaskDao.getTasksForCourier(courierId)

    fun getAllCourierTasks(): Flow<List<CourierTask>> = courierTaskDao.getAllTasks()

    suspend fun createCourierTask(task: CourierTask) = withContext(Dispatchers.IO) {
        courierTaskDao.insertTask(task)
        // Set courier availability to false because they now have a pending task assignment
        val courier = pekerjaDao.getPekerjaById(task.courierId).firstOrNull()
        if (courier != null) {
            pekerjaDao.updatePekerja(courier.copy(isAvailable = false))
        }
    }

    suspend fun startCourierTask(taskId: Int, photoPath: String) = withContext(Dispatchers.IO) {
        val task = courierTaskDao.getTaskByIdSync(taskId)
        if (task != null) {
            courierTaskDao.updateTask(task.copy(
                status = "ON_THE_WAY",
                startTime = System.currentTimeMillis(),
                startPhotoProof = photoPath
            ))
            
            // Set courier availability to false (Busy delivering packages)
            val courier = pekerjaDao.getPekerjaById(task.courierId).firstOrNull()
            if (courier != null) {
                pekerjaDao.updatePekerja(courier.copy(isAvailable = false))
            }
        }
    }

    suspend fun completeCourierTask(taskId: Int, photoPath: String, currentLat: Double, currentLon: Double) = withContext(Dispatchers.IO) {
        val task = courierTaskDao.getTaskByIdSync(taskId)
        if (task != null) {
            courierTaskDao.updateTask(task.copy(
                status = "DELIVERED",
                endTime = System.currentTimeMillis(),
                endPhotoProof = photoPath
            ))

            // Auto-detect if courier location is back at warehouse to toggle available status
            val sites = geofencedSiteDao.getAllSites().firstOrNull() ?: emptyList()
            var returnedToWarehouse = false
            for (site in sites) {
                val distance = calculateDistanceInMeters(currentLat, currentLon, site.latitude, site.longitude)
                if (distance <= site.radiusInMeters) {
                    returnedToWarehouse = true
                    break
                }
            }

            val courier = pekerjaDao.getPekerjaById(task.courierId).firstOrNull()
            if (courier != null) {
                pekerjaDao.updatePekerja(courier.copy(
                    isAvailable = returnedToWarehouse, // Auto changes to true if returned
                    lastKnownLatitude = currentLat,
                    lastKnownLongitude = currentLon
                ))
            }
        }
    }

    suspend fun overrideCourierAvailability(courierId: Int, isAvailable: Boolean) = withContext(Dispatchers.IO) {
        val courier = pekerjaDao.getPekerjaById(courierId).firstOrNull()
        if (courier != null) {
            pekerjaDao.updatePekerja(courier.copy(isAvailable = isAvailable))
        }
    }

    // SYSTEM ANNOUNCEMENT METHODS
    fun getActiveAnnouncements(): Flow<List<Announcement>> = announcementDao.getActiveAnnouncements()

    fun getAllAnnouncements(): Flow<List<Announcement>> = announcementDao.getAllAnnouncements()

    suspend fun publishAnnouncement(announcement: Announcement) = withContext(Dispatchers.IO) {
        announcementDao.insertAnnouncement(announcement)
    }

    suspend fun deleteAnnouncement(announcement: Announcement) = withContext(Dispatchers.IO) {
        announcementDao.deleteAnnouncement(announcement)
    }

    // EXCEL REPORT CSV EXPORTER (Complete enterprise reports)
    suspend fun exportAttendanceReportCsv(): String = withContext(Dispatchers.IO) {
        val logs = attendanceLogDao.getAllLogs().firstOrNull() ?: emptyList()
        val workers = pekerjaDao.getAllPekerja().firstOrNull() ?: emptyList()
        val workerMap = workers.associateBy { it.id }

        val sb = java.lang.StringBuilder()
        sb.append("ID,Tanggal,Nama Pekerja,Posisi,Divisi,Check-In Time,Status Check-In,Denda Masuk (Rp),Bonus Disiplin (Rp),Check-Out Time,Status Check-Out,Denda Pulang (Rp),Overtime (Mins),SOP Status\n")

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        for (log in logs) {
            val worker = workerMap[log.pekerjaId]
            val workerName = worker?.name ?: "Unknown"
            val position = worker?.position ?: "Unknown"
            val division = worker?.division ?: "Unknown"

            val cin = sdf.format(Date(log.checkInTime))
            val cout = log.checkOutTime?.let { sdf.format(Date(it)) } ?: "--:--"
            val sopStatus = if (log.isManualEntry) "Pending Manual Approval" else if (log.checkInStatus == "LATE") "Late" else "On Time"

            sb.append("${log.id},${log.date},$workerName,$position,$division,$cin,${log.checkInStatus},${log.checkInFine},${log.checkInBonus},$cout,${log.checkOutStatus ?: "N/A"},${log.checkOutFine},${log.overtimeMins},$sopStatus\n")
        }

        return@withContext sb.toString()
    }

    suspend fun exportLeaveReportCsv(): String = withContext(Dispatchers.IO) {
        val leaves = leaveRequestDao.getAllLeaves().firstOrNull() ?: emptyList()
        val sb = java.lang.StringBuilder()
        sb.append("ID,Nama Pekerja,Divisi,Posisi,Tanggal Cuti,Tipe Cuti,Status Pengajuan,Disetujui Oleh\n")
        for (leave in leaves) {
            sb.append("${leave.id},${leave.pekerjaName},${leave.division},${leave.position},${leave.date},${leave.leaveType},${leave.status},${leave.approvedBy ?: "N/A"}\n")
        }
        return@withContext sb.toString()
    }

    // Haversine Distance
    private fun calculateDistanceInMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
                Math.cos(phi1) * Math.cos(phi2) *
                Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return r * c
    }

    // APPLY EMERGENCY LATENESS MITIGATION (By Worker)
    suspend fun applyEmergencyLateness(logId: Int, reason: String, photoPath: String): Boolean = withContext(Dispatchers.IO) {
        val log = attendanceLogDao.getAllLogs().firstOrNull()?.find { it.id == logId }
            ?: return@withContext false

        val pekerja = pekerjaDao.getPekerjaById(log.pekerjaId).firstOrNull() ?: return@withContext false
        val monthString = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        val stats = getOrCreateUserStats(log.pekerjaId, monthString)

        if (stats.emergencyLogsCount >= 2) {
            return@withContext false
        }

        val updatedStats = stats.copy(
            emergencyLogsCount = stats.emergencyLogsCount + 1,
            totalFinesAmount = (stats.totalFinesAmount - log.checkInFine).coerceAtLeast(0)
        )
        userStatsDao.updateUserStats(updatedStats)

        val updatedLog = log.copy(
            checkInFine = 0,
            latenessMitigationType = "REDUCE_EMERGENCY",
            manualArrivalReason = "Darurat Telat: $reason (Bukti: $photoPath)"
        )
        attendanceLogDao.insertLog(updatedLog)
        return@withContext true
    }

    // SUBMIT REPORT: Workers checked in outside but not arrived after 2 hours
    suspend fun submitOutsideArrivalReport(logId: Int, photoPath: String, location: String): Boolean = withContext(Dispatchers.IO) {
        val log = db.attendanceLogDao().getAllLogs().firstOrNull()?.find { it.id == logId }
            ?: return@withContext false

        val updatedLog = log.copy(
            outsideProofPhoto = photoPath,
            outsideProofLocation = location,
            outsideProofStatus = "PENDING",
            outsideProofTime = System.currentTimeMillis(),
            outsideProofSubmitted = true
        )
        attendanceLogDao.insertLog(updatedLog)
        return@withContext true
    }

    // APPROVE REPORT: Admin approves or rejects the delayed outside check-in report
    suspend fun approveOutsideArrivalReport(logId: Int, approved: Boolean, supervisorId: Int): Boolean = withContext(Dispatchers.IO) {
        val log = db.attendanceLogDao().getAllLogs().firstOrNull()?.find { it.id == logId }
            ?: return@withContext false

        val updatedLog = log.copy(
            outsideProofStatus = if (approved) "APPROVED" else "REJECTED",
            arrivedAtWarehouse = if (approved) true else log.arrivedAtWarehouse
        )
        attendanceLogDao.insertLog(updatedLog)
        return@withContext true
    }

    // MANUAL ATTENDANCE OVERRIDE: Create or override log manually without reducing late quota
    suspend fun overrideAttendanceManual(pekerjaId: Int, dateString: String, checkInTime: Long, checkOutTime: Long?): Boolean = withContext(Dispatchers.IO) {
        val existingLog = attendanceLogDao.getLogForDateSync(pekerjaId, dateString)
        val log = existingLog?.copy(
            checkInTime = checkInTime,
            checkOutTime = checkOutTime,
            checkInStatus = "ON_TIME", // manual entry doesn't count as late
            latenessMitigationType = "MANUAL_OVERRIDE",
            isManualEntry = true
        ) ?: AttendanceLog(
            pekerjaId = pekerjaId,
            date = dateString,
            checkInTime = checkInTime,
            checkInLatitude = -6.2088,
            checkInLongitude = 106.8456,
            checkInSelfiePath = "manual_entry.png",
            checkInLivenessVerified = true,
            checkInDeviceId = "MANUAL",
            checkInStatus = "ON_TIME",
            shiftType = "STANDARD",
            expectedCheckOutTime = checkInTime + (10 * 60 * 60 * 1000L),
            checkOutTime = checkOutTime,
            checkOutStatus = if (checkOutTime != null) "NORMAL" else null,
            isManualEntry = true,
            latenessMitigationType = "MANUAL_OVERRIDE"
        )
        attendanceLogDao.insertLog(log)
        return@withContext true
    }

    // Scan missed days and auto-deduct leave quota
    suspend fun autoProcessAbsence(pekerjaId: Int): Pekerja? = withContext(Dispatchers.IO) {
        val user = pekerjaDao.getPekerjaById(pekerjaId).firstOrNull() ?: return@withContext null
        val logs = attendanceLogDao.getLogsForPekerja(pekerjaId).firstOrNull() ?: emptyList()
        val leaves = leaveRequestDao.getLeavesByPekerja(pekerjaId).firstOrNull() ?: emptyList()

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val calendar = Calendar.getInstance()
        val todayDay = calendar.get(Calendar.DAY_OF_MONTH)
        val currentYear = calendar.get(Calendar.YEAR)
        val currentMonth = calendar.get(Calendar.MONTH)

        var updatedPekerja = user
        var quotaChanged = false

        for (day in 1 until todayDay) {
            val checkCal = Calendar.getInstance().apply {
                set(Calendar.YEAR, currentYear)
                set(Calendar.MONTH, currentMonth)
                set(Calendar.DAY_OF_MONTH, day)
            }
            val checkDateStr = sdf.format(checkCal.time)

            val hasLog = logs.any { it.date == checkDateStr }
            val hasLeave = leaves.any { it.date == checkDateStr && it.status == "APPROVED" }

            if (!hasLog && !hasLeave) {
                val absentLog = AttendanceLog(
                    pekerjaId = pekerjaId,
                    date = checkDateStr,
                    checkInTime = 0L,
                    checkInLatitude = 0.0,
                    checkInLongitude = 0.0,
                    checkInSelfiePath = null,
                    checkInDeviceId = "SYSTEM",
                    checkInStatus = "ABSENT",
                    shiftType = "STANDARD",
                    expectedCheckOutTime = 0L,
                    latenessMitigationType = "REDUCE_LEAVE",
                    manualArrivalReason = "Tidak ada absen (Alfa) - Potong Cuti Otomatis"
                )
                attendanceLogDao.insertLog(absentLog)

                if (updatedPekerja.totalLeaveQuota > 0) {
                    updatedPekerja = updatedPekerja.copy(totalLeaveQuota = updatedPekerja.totalLeaveQuota - 1)
                    quotaChanged = true
                }
            }
        }
        if (quotaChanged) {
            pekerjaDao.updatePekerja(updatedPekerja)
            return@withContext updatedPekerja
        }
        return@withContext null
    }
}

// Result Wrappers
sealed class LoginResult {
    data class Success(val pekerja: Pekerja) : LoginResult()
    data class Failure(val message: String) : LoginResult()
}

sealed class CheckInResponse {
    data class Success(val message: String, val log: AttendanceLog) : CheckInResponse()
    data class Failure(val message: String) : CheckInResponse()
}

sealed class CheckOutResponse {
    data class Success(val message: String, val log: AttendanceLog) : CheckOutResponse()
    data class Failure(val message: String) : CheckOutResponse()
}

sealed class LeaveSubmitResult {
    data class Success(val message: String, val leave: LeaveRequest) : LeaveSubmitResult()
    data class Failure(val message: String) : LeaveSubmitResult()
}

sealed class LeaveCancelResult {
    data class Success(val message: String, val division: String) : LeaveCancelResult()
    data class Failure(val message: String) : LeaveCancelResult()
}
