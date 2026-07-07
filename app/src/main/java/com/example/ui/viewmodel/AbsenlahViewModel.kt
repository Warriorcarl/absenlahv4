package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AbsenlahDatabase
import com.example.data.entity.*
import com.example.data.repository.*
import com.example.data.rule.RuleEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AbsenlahViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AbsenlahDatabase.getDatabase(application)
    val repository = AbsenlahRepository(db)

    // AUTH STATE
    private val _loggedInUser = MutableStateFlow<Pekerja?>(null)
    val loggedInUser: StateFlow<Pekerja?> = _loggedInUser.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    // ATTENDANCE LOGS
    private val _allLogs = MutableStateFlow<List<AttendanceLog>>(emptyList())
    val allLogs: StateFlow<List<AttendanceLog>> = _allLogs.asStateFlow()

    private val _pekerjaLogs = MutableStateFlow<List<AttendanceLog>>(emptyList())
    val pekerjaLogs: StateFlow<List<AttendanceLog>> = _pekerjaLogs.asStateFlow()

    private val _todayLog = MutableStateFlow<AttendanceLog?>(null)
    val todayLog: StateFlow<AttendanceLog?> = _todayLog.asStateFlow()

    // WORKERS FLOW (For Courier Monitoring)
    private val _allPekerja = MutableStateFlow<List<Pekerja>>(emptyList())
    val allPekerja: StateFlow<List<Pekerja>> = _allPekerja.asStateFlow()

    // CONFIG & SITES
    val allConfigs: StateFlow<List<DynamicConfig>> = repository.getAllConfigs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSites: StateFlow<List<GeofencedSite>> = repository.getAllSites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // LEAVE REQUESTS
    private val _pekerjaLeaves = MutableStateFlow<List<LeaveRequest>>(emptyList())
    val pekerjaLeaves: StateFlow<List<LeaveRequest>> = _pekerjaLeaves.asStateFlow()

    private val _divisionLeaves = MutableStateFlow<List<LeaveRequest>>(emptyList())
    val divisionLeaves: StateFlow<List<LeaveRequest>> = _divisionLeaves.asStateFlow()

    private val _allLeaves = MutableStateFlow<List<LeaveRequest>>(emptyList())
    val allLeaves: StateFlow<List<LeaveRequest>> = _allLeaves.asStateFlow()

    // USER STATS
    private val _userStats = MutableStateFlow<UserStats?>(null)
    val userStats: StateFlow<UserStats?> = _userStats.asStateFlow()

    // COURIER TASK TRACKING
    private val _courierTasks = MutableStateFlow<List<CourierTask>>(emptyList())
    val courierTasks: StateFlow<List<CourierTask>> = _courierTasks.asStateFlow()

    private val _allCourierTasks = MutableStateFlow<List<CourierTask>>(emptyList())
    val allCourierTasks: StateFlow<List<CourierTask>> = _allCourierTasks.asStateFlow()

    // SYSTEM ANNOUNCEMENTS
    private val _announcements = MutableStateFlow<List<Announcement>>(emptyList())
    val announcements: StateFlow<List<Announcement>> = _announcements.asStateFlow()

    private val _allAnnouncements = MutableStateFlow<List<Announcement>>(emptyList())
    val allAnnouncements: StateFlow<List<Announcement>> = _allAnnouncements.asStateFlow()

    // Liveness Detection Step State Machine
    enum class LivenessStep { IDLE, PROMPT_BLINK, PROMPT_SMILE, VERIFYING, COMPLETED, FAILED }
    private val _livenessState = MutableStateFlow(LivenessStep.IDLE)
    val livenessState: StateFlow<LivenessStep> = _livenessState.asStateFlow()

    private val _isLivenessPassed = MutableStateFlow(false)
    val isLivenessPassed: StateFlow<Boolean> = _isLivenessPassed.asStateFlow()

    // Push/Broadcast notifications list (simulated)
    private val _notifications = MutableStateFlow<List<String>>(emptyList())
    val notifications: StateFlow<List<String>> = _notifications.asStateFlow()

    // General operational status messages
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.seedDatabase()
            // Observe overall attendance logs
            repository.getAllLogs().collect { _allLogs.value = it }
        }
        viewModelScope.launch {
            repository.getAllLeaves().collect { _allLeaves.value = it }
        }
        viewModelScope.launch {
            repository.getAllPekerja().collect { _allPekerja.value = it }
        }
        viewModelScope.launch {
            repository.getActiveAnnouncements().collect { _announcements.value = it }
        }
        viewModelScope.launch {
            repository.getAllAnnouncements().collect { _allAnnouncements.value = it }
        }
        viewModelScope.launch {
            repository.getAllCourierTasks().collect { _allCourierTasks.value = it }
        }
    }

    // AUTHENTICATION
    fun login(username: String, passwordRaw: String, onPasswordChangeRequired: () -> Unit, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _loginError.value = null
            when (val result = repository.login(username, passwordRaw)) {
                is LoginResult.Success -> {
                    val user = result.pekerja
                    _loggedInUser.value = user
                    loadUserData(user)
                    if (user.mustChangePassword) {
                        onPasswordChangeRequired()
                    } else {
                        onSuccess()
                    }
                }
                is LoginResult.Failure -> {
                    _loginError.value = result.message
                }
            }
        }
    }

    fun loginWithGoogle(email: String, googleId: String, name: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _loginError.value = null
            when (val result = repository.loginWithGoogle(email, googleId, name)) {
                is LoginResult.Success -> {
                    val user = result.pekerja
                    _loggedInUser.value = user
                    loadUserData(user)
                    _statusMessage.value = "Berhasil Masuk via Google Auth!"
                    onSuccess()
                }
                is LoginResult.Failure -> {
                    _loginError.value = result.message
                }
            }
        }
    }

    fun changePassword(newPasswordRaw: String, onSuccess: () -> Unit) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val updatedUser = user.copy(passwordHash = newPasswordRaw, mustChangePassword = false)
            repository.updatePekerja(updatedUser)
            _loggedInUser.value = updatedUser
            _statusMessage.value = "Password berhasil diubah!"
            onSuccess()
        }
    }

    fun changePasswordDirectly(newPasswordRaw: String) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val updatedUser = user.copy(passwordHash = newPasswordRaw)
            repository.updatePekerja(updatedUser)
            _loggedInUser.value = updatedUser
            _statusMessage.value = "Password berhasil diperbarui di Pengaturan!"
        }
    }

    fun updateProfilePhoto(avatarPath: String) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val updatedUser = user.copy(profilePhotoPath = avatarPath)
            repository.updatePekerja(updatedUser)
            _loggedInUser.value = updatedUser
            _statusMessage.value = "Foto profil berhasil diubah!"
        }
    }

    fun unbindUserDevice(pekerjaId: Int) {
        viewModelScope.launch {
            val success = repository.releaseDeviceBinding(pekerjaId)
            if (success) {
                _statusMessage.value = "Device binding berhasil dilepas oleh Admin!"
                // Refresh list
                repository.getAllPekerja().collect { _allPekerja.value = it }
            }
        }
    }

    fun logout() {
        _loggedInUser.value = null
        _pekerjaLogs.value = emptyList()
        _todayLog.value = null
        _pekerjaLeaves.value = emptyList()
        _divisionLeaves.value = emptyList()
        _userStats.value = null
        _courierTasks.value = emptyList()
        resetLiveness()
    }

    fun loadUserData(user: Pekerja) {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val monthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())

        // Fetch logs for user
        viewModelScope.launch {
            repository.getLogsForPekerja(user.id).collect {
                _pekerjaLogs.value = it
            }
        }

        // Fetch today's log
        viewModelScope.launch {
            repository.getLogForDate(user.id, todayStr).collect {
                _todayLog.value = it
            }
        }

        // Fetch user stats
        viewModelScope.launch {
            repository.getUserStats(user.id, monthStr).collect {
                _userStats.value = it
            }
        }

        // Fetch leaves
        viewModelScope.launch {
            repository.getLeavesForPekerja(user.id).collect {
                _pekerjaLeaves.value = it
            }
        }

        // Fetch same-division leaves for the leave information center
        viewModelScope.launch {
            repository.getLeavesForDivision(user.division).collect {
                _divisionLeaves.value = it
            }
        }

        // Fetch courier task tracking logs if courier
        if (user.position.equals("Courier", ignoreCase = true)) {
            viewModelScope.launch {
                repository.getTasksForCourier(user.id).collect {
                    _courierTasks.value = it
                }
            }
        }
    }

    // LIVENESS STEP TRIGGER
    fun startLivenessVerification() {
        viewModelScope.launch {
            _isLivenessPassed.value = false
            _livenessState.value = LivenessStep.PROMPT_BLINK
        }
    }

    fun advanceLiveness(action: String) {
        viewModelScope.launch {
            if (action == "BLINK" && _livenessState.value == LivenessStep.PROMPT_BLINK) {
                _livenessState.value = LivenessStep.PROMPT_SMILE
            } else if (action == "SMILE" && _livenessState.value == LivenessStep.PROMPT_SMILE) {
                _livenessState.value = LivenessStep.VERIFYING
                kotlinx.coroutines.delay(1200)
                _livenessState.value = LivenessStep.COMPLETED
                _isLivenessPassed.value = true
            } else {
                _livenessState.value = LivenessStep.FAILED
                _isLivenessPassed.value = false
            }
        }
    }

    fun resetLiveness() {
        _livenessState.value = LivenessStep.IDLE
        _isLivenessPassed.value = false
    }

    // ATTENDANCE METHODS
    fun performCheckIn(
        latitude: Double,
        longitude: Double,
        selfiePath: String?,
        deviceId: String,
        onResult: (String) -> Unit
    ) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val response = repository.checkIn(
                pekerjaId = user.id,
                latitude = latitude,
                longitude = longitude,
                selfiePath = selfiePath,
                isLivenessVerified = _isLivenessPassed.value,
                deviceId = deviceId
            )
            when (response) {
                is CheckInResponse.Success -> {
                    _statusMessage.value = response.message
                    onResult(response.message)
                    loadUserData(user)
                }
                is CheckInResponse.Failure -> {
                    _statusMessage.value = response.message
                    onResult(response.message)
                }
            }
            resetLiveness()
        }
    }

    fun performCheckOut(
        latitude: Double,
        longitude: Double,
        onResult: (String) -> Unit
    ) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val response = repository.checkOut(
                pekerjaId = user.id,
                latitude = latitude,
                longitude = longitude
            )
            when (response) {
                is CheckOutResponse.Success -> {
                    _statusMessage.value = response.message
                    onResult(response.message)
                    loadUserData(user)
                }
                is CheckOutResponse.Failure -> {
                    _statusMessage.value = response.message
                    onResult(response.message)
                }
            }
        }
    }

    // COURIER SPECIFIC MANUAL ARRIVAL LOGIC
    fun courierConfirmDelay() {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val ok = repository.confirmCourierDelay(user.id)
            if (ok) {
                _statusMessage.value = "Keterlambatan telah dikonfirmasi ke Atasan! Anda bebas dari penalti denda s/d Jam 14.00."
                loadUserData(user)
            }
        }
    }

    fun registerCourierWarehouseArrival(latitude: Double, longitude: Double) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val ok = repository.registerCourierWarehouseArrival(user.id, latitude, longitude)
            if (ok) {
                _statusMessage.value = "Selamat! Kedatangan fisik Anda di Gudang telah terverifikasi ✔"
                loadUserData(user)
            } else {
                _statusMessage.value = "Gagal: Anda terdeteksi masih berada diluar radius Geofence Gudang!"
            }
        }
    }

    fun forceVerifyCourierPenalties() {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val msg = repository.verifyAndApplyCourierManualCheckInPenalties(user.id)
            _statusMessage.value = msg
            loadUserData(user)
        }
    }

    // COURIER FORCE LOCATION TRACKING SENSORS
    fun toggleCourierLocationForceOn(forceOn: Boolean) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            repository.setLocationForceOn(user.id, forceOn)
            _loggedInUser.value = _loggedInUser.value?.copy(isLocationForceOn = forceOn)
            _statusMessage.value = if (forceOn) "Force-On Lokasi Aktif (GPS terus melacak)." else "Force-On Lokasi Mati (Menghemat baterai)."
        }
    }

    fun simulateCourierLiveMovement(lat: Double, lon: Double) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            repository.updateCourierLocation(user.id, lat, lon)
            _loggedInUser.value = _loggedInUser.value?.copy(lastKnownLatitude = lat, lastKnownLongitude = lon)
        }
    }

    // LEAVE REQUEST METHODS
    fun submitLeave(
        dateString: String,
        leaveType: String,
        emergencyProofPath: String?,
        onResult: (String) -> Unit
    ) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val result = repository.submitLeaveRequest(
                pekerjaId = user.id,
                dateString = dateString,
                leaveType = leaveType,
                emergencyPhotoProofPath = emergencyProofPath
            )
            when (result) {
                is LeaveSubmitResult.Success -> {
                    _statusMessage.value = result.message
                    onResult(result.message)
                    loadUserData(user)
                }
                is LeaveSubmitResult.Failure -> {
                    _statusMessage.value = result.message
                    onResult(result.message)
                }
            }
        }
    }

    fun cancelLeave(leaveId: Int, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = repository.cancelLeaveRequest(leaveId)
            when (result) {
                is LeaveCancelResult.Success -> {
                    _statusMessage.value = result.message
                    onResult(result.message)
                    val divisionMessage = "PERINGATAN DIVISI: Rekan kerja Anda membatalkan cuti pada tanggal ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}."
                    _notifications.value = _notifications.value + divisionMessage
                    _loggedInUser.value?.let { loadUserData(it) }
                }
                is LeaveCancelResult.Failure -> {
                    _statusMessage.value = result.message
                    onResult(result.message)
                }
            }
        }
    }

    // SUPERVISOR / ADMIN WORKFLOWS
    fun approveManualCourier(logId: Int, approved: Boolean, onResult: (Boolean) -> Unit) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val success = repository.approveManualAttendance(logId, user.id, approved)
            onResult(success)
            repository.getAllLogs().collect { _allLogs.value = it }
        }
    }

    fun approveOvertimeLog(logId: Int, approved: Boolean) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            repository.approveOvertime(logId, user.id, approved)
            repository.getAllLogs().collect { _allLogs.value = it }
        }
    }

    fun approveEarlyDepartureLog(logId: Int, approved: Boolean) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            repository.approveEarlyDeparture(logId, user.id, approved)
            repository.getAllLogs().collect { _allLogs.value = it }
        }
    }

    fun updateLeaveStatus(leaveId: Int, status: String, reason: String? = null) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            repository.updateLeaveStatus(leaveId, status, user.id, reason)
            repository.getAllLeaves().collect { _allLeaves.value = it }
        }
    }

    fun mitigateLateness(logId: Int, mitigationType: String) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val success = repository.mitigateLateness(logId, mitigationType, user.id)
            if (success) {
                _statusMessage.value = "Mitigasi Keterlambatan Berhasil Ditetapkan!"
                repository.getAllLogs().collect { _allLogs.value = it }
            }
        }
    }

    fun applyEmergencyLateness(logId: Int, reason: String, photoPath: String, onResult: (Boolean) -> Unit) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val success = repository.applyEmergencyLateness(logId, reason, photoPath)
            if (success) {
                _statusMessage.value = "Jatah Darurat Telat Berhasil Diaplikasikan ✔"
                loadUserData(user)
                onResult(true)
            } else {
                _statusMessage.value = "Gagal: Jatah Darurat Telat telah habis atau log tidak ditemukan!"
                onResult(false)
            }
        }
    }

    // COURIER JOB TASK COMMANDS
    fun createCourierTask(courierId: Int, courierName: String, title: String, notes: String?, address: String, lat: Double, lon: Double) {
        viewModelScope.launch {
            val newTask = CourierTask(
                courierId = courierId,
                courierName = courierName,
                title = title,
                notes = notes,
                destinationAddress = address,
                destinationLatitude = lat,
                destinationLongitude = lon
            )
            repository.createCourierTask(newTask)
            _statusMessage.value = "Penugasan pengiriman berhasil dibuat untuk $courierName!"
            repository.getAllCourierTasks().collect { _allCourierTasks.value = it }
            repository.getAllPekerja().collect { _allPekerja.value = it }
        }
    }

    fun startCourierTask(taskId: Int, photoPath: String) {
        viewModelScope.launch {
            repository.startCourierTask(taskId, photoPath)
            _statusMessage.value = "Pengiriman Paket Dimulai! Status kurir diatur menjadi 'Dalam Tugas'."
            _loggedInUser.value?.let { loadUserData(it) }
            repository.getAllCourierTasks().collect { _allCourierTasks.value = it }
            repository.getAllPekerja().collect { _allPekerja.value = it }
        }
    }

    fun completeCourierTask(taskId: Int, photoPath: String, currentLat: Double, currentLon: Double) {
        viewModelScope.launch {
            repository.completeCourierTask(taskId, photoPath, currentLat, currentLon)
            _statusMessage.value = "Pengiriman Selesai! Bukti Pengiriman (POD) berhasil diunggah."
            _loggedInUser.value?.let { loadUserData(it) }
            repository.getAllCourierTasks().collect { _allCourierTasks.value = it }
            repository.getAllPekerja().collect { _allPekerja.value = it }
        }
    }

    fun manualOverrideCourierAvailability(courierId: Int, isAvailable: Boolean) {
        viewModelScope.launch {
            repository.overrideCourierAvailability(courierId, isAvailable)
            _statusMessage.value = "Status ketersediaan kurir berhasil diubah secara manual."
            repository.getAllPekerja().collect { _allPekerja.value = it }
        }
    }

    // SYSTEM ANNOUNCEMENT MANAGEMENT
    fun createAnnouncement(title: String, content: String, targetDivision: String) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val newAnn = Announcement(
                title = title,
                content = content,
                publishedAt = System.currentTimeMillis(),
                senderName = user.name,
                targetDivision = targetDivision
            )
            repository.publishAnnouncement(newAnn)
            _statusMessage.value = "Pengumuman dan Notifikasi Berhasil Diterbitkan!"
            
            // Simulating push notification to notifications feed
            val pushAlert = "PUSH NOTIFIKASI: $title - $content"
            _notifications.value = listOf(pushAlert) + _notifications.value

            repository.getActiveAnnouncements().collect { _announcements.value = it }
            repository.getAllAnnouncements().collect { _allAnnouncements.value = it }
        }
    }

    fun deleteAnnouncement(ann: Announcement) {
        viewModelScope.launch {
            repository.deleteAnnouncement(ann)
            _statusMessage.value = "Pengumuman berhasil dihapus."
            repository.getActiveAnnouncements().collect { _announcements.value = it }
            repository.getAllAnnouncements().collect { _allAnnouncements.value = it }
        }
    }

    // CONFIG & GEOLOCATION MANAGE
    fun saveConfigValue(key: String, newValue: String, valueType: String, description: String) {
        viewModelScope.launch {
            repository.updateConfig(DynamicConfig(key, newValue, description, valueType))
            _statusMessage.value = "Konfigurasi $key berhasil diperbarui!"
        }
    }

    fun addGeofencedSite(name: String, latitude: Double, longitude: Double, radius: Double) {
        viewModelScope.launch {
            repository.addSite(GeofencedSite(name = name, latitude = latitude, longitude = longitude, radiusInMeters = radius))
            _statusMessage.value = "Geofence $name berhasil ditambahkan!"
        }
    }

    fun deleteGeofencedSite(site: GeofencedSite) {
        viewModelScope.launch {
            repository.deleteSite(site)
            _statusMessage.value = "Geofence ${site.name} berhasil dihapus!"
        }
    }

    // Submit report for > 2 hours delayed arrival
    fun submitOutsideArrivalReport(logId: Int, photoPath: String, location: String) {
        viewModelScope.launch {
            val success = repository.submitOutsideArrivalReport(logId, photoPath, location)
            if (success) {
                _statusMessage.value = "Laporan Bukti Keberangkatan / Perjalanan Berhasil Terkirim ✔"
                repository.getAllLogs().collect { _allLogs.value = it }
            }
        }
    }

    // Approve report for > 2 hours delayed arrival
    fun approveOutsideArrivalReport(logId: Int, approved: Boolean) {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val success = repository.approveOutsideArrivalReport(logId, approved, user.id)
            if (success) {
                _statusMessage.value = if (approved) "Laporan Bukti Disetujui!" else "Laporan Bukti Ditolak!"
                repository.getAllLogs().collect { _allLogs.value = it }
            }
        }
    }

    // Manual Override Entry by Supervisor/Admin (doesn't reduce late quota)
    fun overrideAttendanceManual(pekerjaId: Int, dateString: String, checkInTime: Long, checkOutTime: Long?) {
        viewModelScope.launch {
            val success = repository.overrideAttendanceManual(pekerjaId, dateString, checkInTime, checkOutTime)
            if (success) {
                _statusMessage.value = "Koreksi Absensi Manual untuk Tanggal $dateString Berhasil Disimpan!"
                repository.getAllLogs().collect { _allLogs.value = it }
            }
        }
    }

    // Scan missed days and auto-deduct leave quota
    fun autoProcessAbsence(pekerjaId: Int) {
        viewModelScope.launch {
            val updatedUser = repository.autoProcessAbsence(pekerjaId)
            if (updatedUser != null) {
                _loggedInUser.value = updatedUser
                _statusMessage.value = "Sanksi Absensi Kosong (Alfa) otomatis diaplikasikan! Jatah cuti berkurang."
                repository.getAllLogs().collect { _allLogs.value = it }
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun clearLoginError() {
        _loginError.value = null
    }
}
