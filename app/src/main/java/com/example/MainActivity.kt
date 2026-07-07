package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.data.entity.*
import com.example.ui.SimulatedMap
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AbsenlahViewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: AbsenlahViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = androidx.lifecycle.ViewModelProvider(this)[AbsenlahViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainContent(viewModel, this)
            }
        }
    }

    private var googleSignInSuccessCallback: ((String, String, String) -> Unit)? = null
    private var googleSignInFailureCallback: ((String) -> Unit)? = null

    private val googleSignInLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            if (account != null) {
                val email = account.email ?: ""
                val id = account.id ?: ""
                val name = account.displayName ?: "User Google"
                if (email.isNotEmpty()) {
                    googleSignInSuccessCallback?.invoke(email, id, name)
                } else {
                    googleSignInFailureCallback?.invoke("Email Google tidak dapat diakses.")
                }
            } else {
                googleSignInFailureCallback?.invoke("Google Sign-In gagal (account null).")
            }
        } catch (e: com.google.android.gms.common.api.ApiException) {
            googleSignInFailureCallback?.invoke("Google Sign-In Error: ${e.statusCode} (${e.localizedMessage})")
        }
    }

    fun startGoogleSignIn(onSuccess: (String, String, String) -> Unit, onFailure: (String) -> Unit) {
        googleSignInSuccessCallback = onSuccess
        googleSignInFailureCallback = onFailure
        val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        val googleSignInClient = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(this, gso)
        
        // First, sign out so user can pick account
        googleSignInClient.signOut().addOnCompleteListener {
            val signInIntent = googleSignInClient.signInIntent
            googleSignInLauncher.launch(signInIntent)
        }
    }

    // Helper to fetch actual device GPS for the "Use Current Location" button
    fun fetchDeviceLocation(onLocationResult: (Double, Double) -> Unit) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                1001
            )
            Toast.makeText(this, "Mohon izinkan GPS & coba lagi", Toast.LENGTH_LONG).show()
            return
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    onLocationResult(location.latitude, location.longitude)
                } else {
                    Toast.makeText(this, "Tidak dapat membaca GPS. Coba lagi", Toast.LENGTH_SHORT).show()
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(viewModel: AbsenlahViewModel, activity: MainActivity) {
    val context = LocalContext.current
    val loggedInUser by viewModel.loggedInUser.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val announcements by viewModel.announcements.collectAsState()

    // Automatically trigger checking for unrecorded absence (missed days / alfa) for logged-in workers
    LaunchedEffect(loggedInUser) {
        loggedInUser?.let {
            if (it.role == "pekerja") {
                viewModel.autoProcessAbsence(it.id)
            }
        }
        
        // Start or stop real-time background location tracking service
        val user = loggedInUser
        if (user != null && (user.division.uppercase() == "KURIR" || user.isLocationForceOn)) {
            val intent = android.content.Intent(context, com.example.service.LocationTrackingService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            val intent = android.content.Intent(context, com.example.service.LocationTrackingService::class.java)
            context.stopService(intent)
        }
    }

    var isIndonesian by remember { mutableStateOf(true) }
    var currentTab by remember { mutableStateOf("dashboard") } // "dashboard", "history", "leave", "admin", "notifications"
    var showPasswordChangeDialog by remember { mutableStateOf(false) }

    // Manual Attendance Override States (Supervisor Input)
    var showManualAttendanceOverrideDialog by remember { mutableStateOf(false) }
    var manualOverridePekerjaId by remember { mutableStateOf(0) }
    var manualOverrideDate by remember { mutableStateOf("") }
    var manualOverrideCheckInTime by remember { mutableStateOf("") }
    var manualOverrideCheckOutTime by remember { mutableStateOf("") }

    // Popup Announcement Active State
    var activeAnnouncementPopup by remember { mutableStateOf<Announcement?>(null) }

    // Show popup if any active announcements exist and user is logged in
    LaunchedEffect(announcements, loggedInUser) {
        if (loggedInUser != null && announcements.isNotEmpty()) {
            val userDiv = loggedInUser?.division ?: ""
            val applicable = announcements.firstOrNull {
                it.targetDivision == "ALL" || it.targetDivision.equals(userDiv, ignoreCase = true)
            }
            activeAnnouncementPopup = applicable
        } else {
            activeAnnouncementPopup = null
        }
    }

    // Multi-Language Strings Maps
    val t = remember(isIndonesian) {
        if (isIndonesian) {
            mapOf(
                "title" to "Absenlah Enterprise",
                "subtitle" to "Hadiri dengan Disiplin & Integritas",
                "username" to "Username atau Email",
                "password" to "Kata Sandi (Password)",
                "login" to "Masuk Akun",
                "logout" to "Keluar",
                "must_change_pass" to "Wajib Ganti Password Pertama Kali",
                "new_pass" to "Password Baru",
                "save" to "Simpan",
                "dashboard" to "Absen & Tugas",
                "history" to "Riwayat",
                "leave" to "Cuti ESS",
                "admin_panel" to "Kelola",
                "site_title" to "Lokasi Gudang",
                "geofence_status" to "Radius Geofence",
                "checkin" to "Check-In Masuk",
                "checkout" to "Check-Out Pulang",
                "today_log" to "Absensi Hari Ini",
                "discipline_bonus" to "Bonus Disiplin Hari Ini",
                "lateness_fine" to "Denda Keterlambatan",
                "shift_info" to "Jam Kerja",
                "leave_center" to "Pusat Info Cuti (Divisi)",
                "apply_leave" to "Ajukan Cuti",
                "leave_date" to "Tanggal Cuti (YYYY-MM-DD)",
                "leave_type" to "Tipe Cuti",
                "photo_proof" to "Unggah Foto Bukti (Darurat)",
                "submit" to "Kirim Pengajuan",
                "cancel" to "Batalkan",
                "on_time" to "Tepat Waktu",
                "late" to "Terlambat",
                "pending" to "Menunggu",
                "approved" to "Disetujui",
                "rejected" to "Ditolak",
                "not_checked_in" to "Belum Absen",
                "use_current_loc" to "Gunakan Lokasi Asli (GPS)",
                "simulate_location" to "Simulasi Geofence (Offline Testing)",
                "in_site" to "Di Dalam Gudang",
                "out_site" to "Di Luar Gudang (Off-Site)",
                "liveness_title" to "Verifikasi Wajah Liveness",
                "liveness_blink" to "Silakan Berkedip (Blink)",
                "liveness_smile" to "Silakan Tersenyum (Smile)",
                "liveness_verifying" to "Memverifikasi Wajah...",
                "liveness_passed" to "Liveness Terverifikasi ✔",
                "device_id" to "Perangkat ID Anda",
                "stats_title" to "Statistik Pekerja Bulan Ini",
                "stats_lateness" to "Jumlah Terlambat",
                "stats_leave" to "Cuti Dipakai",
                "stats_early" to "Pulang Cepat",
                "stats_emergency" to "Log Darurat",
                "total_quota" to "Sisa Jatah Libur",
                "rupee" to "Rp",
                "overtime" to "Lembur",
                "supervisor_menu" to "Persetujuan Supervisor (Courier/OT/Cuti)",
                "notif_title" to "Notifikasi Divisi"
            )
        } else {
            mapOf(
                "title" to "Absenlah Enterprise",
                "subtitle" to "Attend with Discipline & Integrity",
                "username" to "Username or Email",
                "password" to "Password",
                "login" to "Log In",
                "logout" to "Log Out",
                "must_change_pass" to "Must Change Password On First Login",
                "new_pass" to "New Password",
                "save" to "Save",
                "dashboard" to "Absen & Tasks",
                "history" to "History",
                "leave" to "Leave ESS",
                "admin_panel" to "Manage",
                "site_title" to "Warehouse Site",
                "geofence_status" to "Geofence Radius",
                "checkin" to "Check-In Now",
                "checkout" to "Check-Out Now",
                "today_log" to "Today's Attendance",
                "discipline_bonus" to "Today's Discipline Bonus",
                "lateness_fine" to "Lateness Fine",
                "shift_info" to "Shift Schedule",
                "leave_center" to "Leave Info Center (Division)",
                "apply_leave" to "Request Leave",
                "leave_date" to "Leave Date (YYYY-MM-DD)",
                "leave_type" to "Leave Type",
                "photo_proof" to "Upload Photo Proof (Emergency)",
                "submit" to "Submit Request",
                "cancel" to "Cancel",
                "on_time" to "On Time",
                "late" to "Late",
                "pending" to "Pending",
                "approved" to "Approved",
                "rejected" to "Rejected",
                "not_checked_in" to "Not Checked In",
                "use_current_loc" to "Use Real Location (GPS)",
                "simulate_location" to "Simulate Geofence (Offline Testing)",
                "in_site" to "Inside Warehouse",
                "out_site" to "Outside Warehouse (Off-Site)",
                "liveness_title" to "Liveness Face Verification",
                "liveness_blink" to "Blink your eyes",
                "liveness_smile" to "Smile widely now",
                "liveness_verifying" to "Verifying Face...",
                "liveness_passed" to "Liveness Verified ✔",
                "device_id" to "Your Hardware Device ID",
                "stats_title" to "Your Statistics This Month",
                "stats_lateness" to "Lateness Counts",
                "stats_leave" to "Leaves Taken",
                "stats_early" to "Early Departures",
                "stats_emergency" to "Emergency Logs",
                "total_quota" to "Remaining Leave Days",
                "rupee" to "IDR",
                "overtime" to "Overtime",
                "supervisor_menu" to "Supervisor Approval Pipeline",
                "notif_title" to "Division Alerts"
            )
        }
    }

    // Toast status handler
    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = t["title"] ?: "Absenlah",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)
                        )
                        Text(
                            text = if (loggedInUser != null) "Logged in as ${loggedInUser?.name} (${loggedInUser?.position})" else t["subtitle"] ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color.LightGray)
                        )
                    }
                },
                actions = {
                    // Language Switcher
                    TextButton(onClick = { isIndonesian = !isIndonesian }) {
                        Text(
                            text = if (isIndonesian) "EN" else "ID",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (loggedInUser != null) {
                        IconButton(onClick = { viewModel.logout() }) {
                            Icon(Icons.Default.ExitToApp, contentDescription = "Log Out", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            if (loggedInUser != null) {
                NavigationBar(modifier = Modifier.testTag("bottom_nav")) {
                    NavigationBarItem(
                        selected = currentTab == "dashboard",
                        onClick = { currentTab = "dashboard" },
                        icon = { Icon(Icons.Default.CheckCircle, contentDescription = "Dashboard") },
                        label = { Text(t["dashboard"] ?: "Dashboard") }
                    )
                    NavigationBarItem(
                        selected = currentTab == "history",
                        onClick = { currentTab = "history" },
                        icon = { Icon(Icons.Default.List, contentDescription = "History") },
                        label = { Text(t["history"] ?: "History") }
                    )
                    NavigationBarItem(
                        selected = currentTab == "leave",
                        onClick = { currentTab = "leave" },
                        icon = { Icon(Icons.Default.DateRange, contentDescription = "Leave ESS") },
                        label = { Text(t["leave"] ?: "Leave") }
                    )
                    // Supervisor or Admin Panels
                    val role = loggedInUser?.role ?: "pekerja"
                    if (role == "admin" || role == "supervisor") {
                        NavigationBarItem(
                            selected = currentTab == "admin",
                            onClick = { currentTab = "admin" },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Manage") },
                            label = { Text(t["admin_panel"] ?: "Manage") }
                        )
                    }
                    // Notifications and push bulletin
                    NavigationBarItem(
                        selected = currentTab == "notifications",
                        onClick = { currentTab = "notifications" },
                        icon = { Icon(Icons.Default.Notifications, contentDescription = "Alerts") },
                        label = { Text("Alerts") }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val user = loggedInUser
            if (user == null) {
                LoginScreen(viewModel, t, activity, onPasswordChangeRequired = {
                    showPasswordChangeDialog = true
                })
            } else {
                when (currentTab) {
                    "dashboard" -> WorkerDashboardScreen(
                        viewModel = viewModel,
                        user = user,
                        t = t,
                        activity = activity,
                        showManualAttendanceOverrideDialog = showManualAttendanceOverrideDialog,
                        onShowManualAttendanceOverrideDialogChange = { showManualAttendanceOverrideDialog = it },
                        manualOverridePekerjaId = manualOverridePekerjaId,
                        onManualOverridePekerjaIdChange = { manualOverridePekerjaId = it },
                        manualOverrideDate = manualOverrideDate,
                        onManualOverrideDateChange = { manualOverrideDate = it },
                        manualOverrideCheckInTime = manualOverrideCheckInTime,
                        onManualOverrideCheckInTimeChange = { manualOverrideCheckInTime = it },
                        manualOverrideCheckOutTime = manualOverrideCheckOutTime,
                        onManualOverrideCheckOutTimeChange = { manualOverrideCheckOutTime = it },
                        onNavigateToTab = { currentTab = it }
                    )
                    "history" -> HistoryScreen(viewModel, user, t)
                    "leave" -> LeaveScreen(viewModel, user, t)
                    "admin" -> AdminPanelScreen(viewModel, user, t, activity) {
                        manualOverridePekerjaId = 0
                        manualOverrideDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                        manualOverrideCheckInTime = "10:00"
                        manualOverrideCheckOutTime = ""
                        showManualAttendanceOverrideDialog = true
                    }
                    "notifications" -> NotificationsScreen(viewModel, t)
                }
            }

            // System Announcement Popup modal
            activeAnnouncementPopup?.let { ann ->
                AlertDialog(
                    onDismissRequest = { activeAnnouncementPopup = null },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                    title = { Text(ann.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) },
                    text = {
                        Column {
                            Text(ann.content, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Diterbitkan oleh: ${ann.senderName}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                        }
                    },
                    confirmButton = {
                        Button(onClick = { activeAnnouncementPopup = null }) {
                            Text("Saya Mengerti & Tutup")
                        }
                    }
                )
            }

            // Must change password first dialog
            if (showPasswordChangeDialog) {
                PasswordChangeDialog(
                    viewModel = viewModel,
                    t = t,
                    onDismiss = { showPasswordChangeDialog = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: AbsenlahViewModel,
    t: Map<String, String>,
    activity: MainActivity,
    onPasswordChangeRequired: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val loginError by viewModel.loginError.collectAsState()
    var showGoogleBypassDialog by remember { mutableStateOf(false) }

    var isRegisterMode by remember { mutableStateOf(false) }
    var fullName by remember { mutableStateOf("") }
    var selectedDivision by remember { mutableStateOf("Logistics") }
    var selectedPosition by remember { mutableStateOf("Courier") }
    var registerError by remember { mutableStateOf<String?>(null) }

    if (showGoogleBypassDialog) {
        AlertDialog(
            onDismissRequest = { showGoogleBypassDialog = false },
            title = { Text("Google Sign-In Error (Code 10)") },
            text = {
                Column {
                    Text(
                        "Error ini (DEVELOPER_ERROR) terjadi karena tanda tangan SHA-1 dari sertifikat aplikasi Anda belum didaftarkan di Firebase atau Google Cloud Console untuk Client ID Google Auth.\n\n" +
                        "Untuk melanjutkan pengujian dengan mudah, Anda dapat membypass otentikasi Google ini menggunakan profil demo langsung.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Pilih Akun untuk Masuk:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalAlignment = Alignment.End) {
                    Button(
                        onClick = {
                            showGoogleBypassDialog = false
                            viewModel.loginWithGoogle("warriorcarl@yahoo.com", "google_demo_warriorcarl", "Carl Warrior") {}
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Masuk via Carl (warriorcarl@yahoo.com)")
                    }
                    OutlinedButton(
                        onClick = {
                            showGoogleBypassDialog = false
                            viewModel.loginWithGoogle("kurir1@absenlah.com", "google_demo_kurir1", "Budi Kurir") {}
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text("Masuk via Budi Kurir")
                    }
                    TextButton(
                        onClick = { showGoogleBypassDialog = false },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text("Batal")
                    }
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Face,
            contentDescription = "App Logo",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Absenlah Enterprise",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        )
        Text(
            text = "Geofencing, ESS Cuti, Job Tracking & Multi-device Binding",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        Spacer(modifier = Modifier.height(24.dp))

        if (isRegisterMode) {
            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it },
                label = { Text("Nama Lengkap") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(if (isRegisterMode) "Email / Username Baru" else (t["username"] ?: "Username / Email")) },
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("username_input"),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(t["password"] ?: "Password") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("password_input"),
            singleLine = true
        )

        if (isRegisterMode) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Pilih Divisi Kerja:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                listOf("Logistics", "HR", "Finance", "Operations", "IT").forEach { div ->
                    val isSelected = selectedDivision == div
                    Surface(
                        onClick = { selectedDivision = div },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = div,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Pilih Jabatan Kerja:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                listOf("Courier", "Supervisor", "Admin", "Manager").forEach { pos ->
                    val isSelected = selectedPosition == pos
                    Surface(
                        onClick = { selectedPosition = pos },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = pos,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        val displayError = if (isRegisterMode) registerError else loginError
        if (displayError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = displayError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Start)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (isRegisterMode) {
            Button(
                onClick = {
                    if (username.isBlank() || password.isBlank() || fullName.isBlank()) {
                        registerError = "Mohon lengkapi semua bidang!"
                    } else {
                        registerError = null
                        viewModel.registerPekerja(
                            email = username,
                            passwordRaw = password,
                            fullName = fullName,
                            division = selectedDivision,
                            position = selectedPosition,
                            onSuccess = {
                                isRegisterMode = false
                                registerError = null
                            },
                            onFailure = { err ->
                                registerError = err
                            }
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Daftar Akun Baru")
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = { isRegisterMode = false }) {
                Text("Sudah punya akun? Masuk di sini")
            }
        } else {
            Button(
                onClick = {
                    viewModel.login(username, password, onPasswordChangeRequired) { }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("login_button")
            ) {
                Text(t["login"] ?: "Log In")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Real Google Sign-In Button
            OutlinedButton(
                onClick = {
                    activity.startGoogleSignIn(
                        onSuccess = { email, id, name ->
                            viewModel.loginWithGoogle(email, id, name) { }
                        },
                        onFailure = { errorMsg ->
                            if (errorMsg.contains("10") || errorMsg.contains("DEVELOPER_ERROR") || errorMsg.contains("gagal") || errorMsg.contains("failed")) {
                                showGoogleBypassDialog = true
                            } else {
                                android.widget.Toast.makeText(activity, errorMsg, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Masuk via Google")
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = { isRegisterMode = true }) {
                Text("Belum punya akun? Daftar di sini")
            }
        }
    }
}

@Composable
fun PasswordChangeDialog(viewModel: AbsenlahViewModel, t: Map<String, String>, onDismiss: () -> Unit) {
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { },
        title = { Text(t["must_change_pass"] ?: "Change Password") },
        text = {
            Column {
                Text("Demi keamanan akun enterprise, ganti password default Anda.", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text(t["new_pass"] ?: "New Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Konfirmasi Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                if (errorMsg != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (newPassword.isEmpty() || newPassword == "admin123") {
                    errorMsg = "Sandi tidak boleh kosong/sama dengan sandi lama!"
                } else if (newPassword != confirmPassword) {
                    errorMsg = "Password konfirmasi tidak cocok!"
                } else {
                    viewModel.changePassword(newPassword) {
                        onDismiss()
                    }
                }
            }) {
                Text(t["save"] ?: "Save")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerDashboardScreen(
    viewModel: AbsenlahViewModel,
    user: Pekerja,
    t: Map<String, String>,
    activity: MainActivity,
    showManualAttendanceOverrideDialog: Boolean,
    onShowManualAttendanceOverrideDialogChange: (Boolean) -> Unit,
    manualOverridePekerjaId: Int,
    onManualOverridePekerjaIdChange: (Int) -> Unit,
    manualOverrideDate: String,
    onManualOverrideDateChange: (String) -> Unit,
    manualOverrideCheckInTime: String,
    onManualOverrideCheckInTimeChange: (String) -> Unit,
    manualOverrideCheckOutTime: String,
    onManualOverrideCheckOutTimeChange: (String) -> Unit,
    onNavigateToTab: (String) -> Unit = {}
) {
    val todayLog by viewModel.todayLog.collectAsState()
    val userStats by viewModel.userStats.collectAsState()
    val allSites by viewModel.allSites.collectAsState()
    val courierTasks by viewModel.courierTasks.collectAsState()

    // Geolocation Testing States
    var useRealGps by remember { mutableStateOf(false) }
    var simulateInsideGeofence by remember { mutableStateOf(true) }
    var currentLatitude by remember { mutableStateOf(-6.2088) }
    var currentLongitude by remember { mutableStateOf(106.8456) }

    // Settings Profile Dialog States
    var showSettingsDialog by remember { mutableStateOf(false) }
    var profilePhotoPredefined by remember { mutableStateOf(user.profilePhotoPath ?: "🚚") }
    var settingNewPassword by remember { mutableStateOf("") }

    // Job POD Upload Dialogs
    var activeTaskForPod by remember { mutableStateOf<CourierTask?>(null) }
    var activeTaskForStart by remember { mutableStateOf<CourierTask?>(null) }

    // Face Liveness Animation / States
    val context = LocalContext.current
    val hasCameraPermission = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission.value = isGranted
        if (isGranted) {
            viewModel.startLivenessVerification()
        } else {
            Toast.makeText(context, "Izin kamera diperlukan untuk verifikasi liveness wajah.", Toast.LENGTH_LONG).show()
        }
    }

    val livenessState by viewModel.livenessState.collectAsState()
    val isLivenessPassed by viewModel.isLivenessPassed.collectAsState()

    // Emergency Lateness States
    var showEmergencyLatenessDialog by remember { mutableStateOf(false) }
    var emergencyLatenessReason by remember { mutableStateOf("Ban Bocor") }
    var emergencyLatenessPhotoPath by remember { mutableStateOf<String?>(null) }
    var showLatenessCameraProofDialog by remember { mutableStateOf(false) }

    // 2-Hour Outside Check-in Proof States
    var outsideArrivalPhotoPath by remember { mutableStateOf<String?>(null) }
    var showOutsideArrivalCameraDialog by remember { mutableStateOf(false) }

    val isInsideGeofence = remember(currentLatitude, currentLongitude, allSites) {
        if (allSites.isEmpty()) {
            true
        } else {
            var inside = false
            for (site in allSites) {
                val r = 6371e3
                val phi1 = Math.toRadians(currentLatitude)
                val phi2 = Math.toRadians(site.latitude)
                val deltaPhi = Math.toRadians(site.latitude - currentLatitude)
                val deltaLambda = Math.toRadians(site.longitude - currentLongitude)

                val a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
                        Math.cos(phi1) * Math.cos(phi2) *
                        Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2)
                val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
                val distance = r * c
                if (distance <= site.radiusInMeters) {
                    inside = true
                    break
                }
            }
            inside
        }
    }

    // Synchronize coordinates
    LaunchedEffect(simulateInsideGeofence, allSites) {
        if (!useRealGps && allSites.isNotEmpty()) {
            val site = allSites.first()
            if (simulateInsideGeofence) {
                currentLatitude = site.latitude
                currentLongitude = site.longitude
            } else {
                currentLatitude = site.latitude + 0.003
                currentLongitude = site.longitude + 0.003
            }
        }
    }

    // Periodic simulation triggers location updates to monitor
    LaunchedEffect(currentLatitude, currentLongitude, user.isLocationForceOn) {
        if (user.isLocationForceOn) {
            viewModel.simulateCourierLiveMovement(currentLatitude, currentLongitude)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Welcome Header Profile Card & Change Avatar
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Predefined Profile Emoji Photo Display
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(user.profilePhotoPath ?: "👨‍💼", fontSize = 32.sp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(user.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("Divisi: ${user.division} | Posisi: ${user.position}", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        // Gear Button to Open Settings
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Edit Profil", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = Color.White.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Device Binding ID: ${user.deviceId ?: "Unbound"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // 1. Current Attendance Status & Quick Actions Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Status & Tindakan Cepat",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Current attendance status
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (todayLog == null) Color(0xFFF3F4F6) else Color(0xFFECFDF5),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Kehadiran Hari Ini:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Text(
                                text = if (todayLog == null) "Belum Check-In" else "Sudah Hadir (${todayLog!!.checkInStatus})",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (todayLog == null) Color.Gray else Color(0xFF10B981)
                            )
                        }
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (todayLog != null) {
                                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("Masuk: ${sdf.format(Date(todayLog!!.checkInTime))}") },
                                    colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color.White)
                                )
                                if (todayLog!!.checkOutTime != null) {
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text("Pulang: ${sdf.format(Date(todayLog!!.checkOutTime!!))}") },
                                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color.White)
                                    )
                                }
                            } else {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("Shift STANDARD") },
                                    colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color.White)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Quick Action buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onNavigateToTab("leave")
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ajukan Cuti", style = MaterialTheme.typography.labelMedium)
                        }
                        
                        OutlinedButton(
                            onClick = {
                                onNavigateToTab("notifications")
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Notifikasi", style = MaterialTheme.typography.labelMedium)
                        }

                        OutlinedButton(
                            onClick = {
                                onNavigateToTab("history")
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Riwayat", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // 2. Interactive Shift Calendar View & Details with Overtime Markers
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Kalender Jadwal Kerja & Shift",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Pilih hari untuk melihat detail tugas, shift reguler, dan jam lembur.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Simple interactive horizontal week selection
                    var selectedDayIndex by remember { mutableStateOf(0) }
                    val weekDays = listOf(
                        Triple("Sen", "06", "STANDARD"),
                        Triple("Sel", "07", "STANDARD"),
                        Triple("Rab", "08", "OVERTIME"),
                        Triple("Kam", "09", "STANDARD"),
                        Triple("Jum", "10", "LEAVE"),
                        Triple("Sab", "11", "OFF"),
                        Triple("Min", "12", "OFF")
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        weekDays.forEachIndexed { index, day ->
                            val isSelected = selectedDayIndex == index
                            val hasOvertime = day.third == "OVERTIME"
                            val isLeave = day.third == "LEAVE"
                            val isOff = day.third == "OFF"
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isSelected -> MaterialTheme.colorScheme.primary
                                            isLeave -> Color(0xFFE8F5E9)
                                            hasOvertime -> Color(0xFFFFF3E0)
                                            isOff -> Color(0xFFF3F4F6)
                                            else -> Color.Transparent
                                        }
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedDayIndex = index }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = day.first,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) Color.White else Color.Gray,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = day.second,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelected) Color.White else Color.Black,
                                        fontWeight = FontWeight.Bold
                                    )
                                    
                                    // Custom markers
                                    if (hasOvertime) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFF57C00))
                                        )
                                    } else if (isLeave) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF2E7D32))
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Selected Day Shift details card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            val activeDay = weekDays[selectedDayIndex]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Detail Shift: Hari ${activeDay.first} (${activeDay.second} Juli 2026)",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(activeDay.third) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = when (activeDay.third) {
                                            "OVERTIME" -> Color(0xFFFFF3E0)
                                            "LEAVE" -> Color(0xFFE8F5E9)
                                            "OFF" -> Color(0xFFF3F4F6)
                                            else -> Color(0xFFE3F2FD)
                                        },
                                        labelColor = when (activeDay.third) {
                                            "OVERTIME" -> Color(0xFFF57C00)
                                            "LEAVE" -> Color(0xFF2E7D32)
                                            "OFF" -> Color.Gray
                                            else -> Color(0xFF1565C0)
                                        }
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            when (activeDay.third) {
                                "STANDARD" -> {
                                    Text("• Jam Kerja: 08:00 AM - 05:00 PM (Shift Standar)", style = MaterialTheme.typography.bodySmall)
                                    Text("• SOP Toleransi Terlambat: Maksimal 15 menit.", style = MaterialTheme.typography.bodySmall)
                                    Text("• Status Kehadiran: Sesuai Jadwal", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                "OVERTIME" -> {
                                    Text("• Jam Kerja: 08:00 AM - 05:00 PM (Shift Standar)", style = MaterialTheme.typography.bodySmall)
                                    Text("• Lembur Terjadwal (Approved): 05:00 PM - 07:00 PM (+2 Jam)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                                    Text("• Kompensasi Lembur: Bonus lembur Rp100.000 terdeteksi otomatis oleh sistem.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE65100))
                                }
                                "LEAVE" -> {
                                    Text("• Status: Cuti Bersama Disetujui (ESS)", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                                    Text("• Deskripsi: Cuti Tahunan yang disubmit dan disetujui supervisor via portal ESS.", style = MaterialTheme.typography.bodySmall)
                                }
                                "OFF" -> {
                                    Text("• Status: Hari Libur Kerja", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    Text("• Tidak wajib melakukan Check-In / Check-Out.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        // COURIER MANUAL CHECK-IN EMERGENCY NOTIFIER PANEL
        if (todayLog != null && todayLog!!.isManualEntry && user.position.equals("Courier", ignoreCase = true)) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)), // amber light
                    border = BorderStroke(1.5.dp, Color(0xFFD97706)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD97706))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Absen Manual (Luar Geofence) Terdeteksi!", fontWeight = FontWeight.Bold, color = Color(0xFF92400E))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Ketentuan: Wajib tiba secara fisik di gudang dalam waktu maksimal 2 jam setelah check-in, atau konfirmasi keterlambatan jika darurat.",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFF78350F)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Delay Confirmation button
                            Button(
                                onClick = { viewModel.courierConfirmDelay() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                enabled = !todayLog!!.manualArrivalConfirmed,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (todayLog!!.manualArrivalConfirmed) "Delay Dikonfirmasi" else "Konfirmasi Delay Atasan", fontSize = 10.sp)
                            }

                            // Physical Arrival button
                            Button(
                                onClick = { viewModel.registerCourierWarehouseArrival(currentLatitude, currentLongitude) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                enabled = !todayLog!!.arrivedAtWarehouse,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (todayLog!!.arrivedAtWarehouse) "Telah Tiba di Gudang ✔" else "Saya Sudah Sampai Gudang", fontSize = 10.sp)
                            }
                        }

                        // Status of manual entries
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Konfirmasi Delay Atasan: ${if (todayLog!!.manualArrivalConfirmed) "YA ✔" else "BELUM"}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Text("Verifikasi Tiba Fisik Gudang: ${if (todayLog!!.arrivedAtWarehouse) "SUDAH TIBA ✔" else "BELUM"}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)

                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = { viewModel.forceVerifyCourierPenalties() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Cek Evaluasi Penalti SOP Manual (Maks Jam 14:00)")
                        }
                    }
                }
            }
        }

        // Attendance status section
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        t["today_log"] ?: "Today's Attendance",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (todayLog == null) {
                        Text(
                            t["not_checked_in"] ?: "Not Checked In yet today",
                            color = Color.Red,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        val log = todayLog!!
                        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        val inTime = sdf.format(Date(log.checkInTime))
                        val outTime = log.checkOutTime?.let { sdf.format(Date(it)) } ?: "--:--"

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("Check-In: $inTime") },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    labelColor = if (log.checkInStatus == "LATE") Color.Red else Color.Green
                                )
                            )
                            if (log.checkOutTime != null) {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("Check-Out: $outTime") }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Tipe Shift: ${log.shiftType}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        val expectedOut = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.expectedCheckOutTime))
                        Text("Batas Minimum Check-out: $expectedOut", style = MaterialTheme.typography.bodySmall)

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Bonus Disiplin: Rp${log.checkInBonus}", color = if (log.checkInBonus > 0) Color(0xFF388E3C) else Color.Gray, fontWeight = FontWeight.Bold)
                        if (log.checkInFine > 0) {
                            Text("Denda Keterlambatan: Rp${log.checkInFine}", color = Color.Red, fontWeight = FontWeight.Bold)
                            if (log.latenessMitigationType == "PENDING") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            "Terlambat Masuk! (Menunggu Tinjauan Supervisor)",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        
                                        val normalLateRemaining = (3 - (userStats?.latenessCount ?: 0)).coerceAtLeast(0)
                                        if (normalLateRemaining > 0) {
                                            Text(
                                                "Anda masih memiliki jatah telat normal ($normalLateRemaining/3 tersisa bulan ini). Supervisor akan menyetujui pemotongan jatah telat normal Anda agar tidak terkena denda.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                            )
                                        } else {
                                            Text(
                                                "Jatah telat normal Anda bulan ini telah habis! Gunakan Jatah Darurat Telat (${2 - (userStats?.emergencyLogsCount ?: 0)}/2 tersisa 6-bulanan) agar keterlambatan ini tidak memotong jatah libur (cuti) tahunan Anda.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Button(
                                                onClick = { showEmergencyLatenessDialog = true },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                                modifier = Modifier.fillMaxWidth(),
                                                enabled = (userStats?.emergencyLogsCount ?: 0) < 2
                                            ) {
                                                Icon(Icons.Default.Warning, contentDescription = null)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Ajukan Jatah Darurat Telat")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (log.latenessMitigationType != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Mitigasi Terlambat: ${log.latenessMitigationType}", color = Color.Blue, fontWeight = FontWeight.Bold)
                            if (log.manualArrivalReason != null) {
                                Text("Detail: ${log.manualArrivalReason}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                        if (log.checkOutFine > 0) {
                            Text("Denda Pulang Cepat: Rp${log.checkOutFine}", color = Color.Red, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Live Simulated Map Display on check-in
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Boundary Geofence Map Viewer", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    val activeSite = allSites.firstOrNull() ?: GeofencedSite(name = "Gudang Utama", latitude = -6.2088, longitude = 106.8456, radiusInMeters = 100.0)
                    
                    SimulatedMap(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        warehouseCenter = Pair(activeSite.latitude, activeSite.longitude),
                        warehouseRadiusMeters = activeSite.radiusInMeters,
                        userLocation = Pair(currentLatitude, currentLongitude)
                    )
                }
            }
        }

        // Geofencing Site status & Switcher
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Status Lokasi GPS & Kehadiran",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // GPS Status Indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Status Wilayah", style = MaterialTheme.typography.bodyMedium)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isInsideGeofence) Color(0xFF10B981) else Color(0xFFEF4444))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isInsideGeofence) "DI DALAM GEOFENCE" else "DI LUAR GEOFENCE",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            activity.fetchDeviceLocation { lat, lon ->
                                currentLatitude = lat
                                currentLongitude = lon
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Perbarui GPS")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "GPS Terbaca: %.6f, %.6f".format(currentLatitude, currentLongitude),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )

                    // COURIER FORCE LOCATION TRACKING BATTERY TOGGLE
                    if (user.position.equals("Courier", ignoreCase = true)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Lacak Lokasi Kurir Real-Time (Force GPS)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                Text("Matikan saat tidak bertugas untuk menghemat baterai ponsel.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                            Switch(
                                checked = user.isLocationForceOn,
                                onCheckedChange = { viewModel.toggleCourierLocationForceOn(it) }
                            )
                        }
                    }
                }
            }
        }

        // COURIER JOB TRACKING & PROOF OF DELIVERY (POD)
        if (user.position.equals("Courier", ignoreCase = true)) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Tugas Pengiriman Kurir (Job Tracking)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Lacak & kirim paket serta unggah bukti foto saat memulai dan selesai.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Spacer(modifier = Modifier.height(12.dp))

                        if (courierTasks.isEmpty()) {
                            Text("Belum ada tugas pengiriman paket terdaftar.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        } else {
                            courierTasks.forEach { task ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, Color.LightGray),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(task.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                            SuggestionChip(
                                                onClick = {},
                                                label = { Text(task.status) },
                                                colors = SuggestionChipDefaults.suggestionChipColors(
                                                    labelColor = when (task.status) {
                                                        "PENDING" -> Color.Gray
                                                        "ON_THE_WAY" -> Color.Blue
                                                        else -> Color.Green
                                                    }
                                                )
                                            )
                                        }
                                        Text("Tujuan: ${task.destinationAddress}", style = MaterialTheme.typography.bodySmall)
                                        if (task.notes != null) {
                                            Text("Catatan: ${task.notes}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Mini task map
                                        SimulatedMap(
                                            modifier = Modifier.fillMaxWidth().height(100.dp),
                                            warehouseCenter = Pair(-6.2088, 106.8456),
                                            deliveryDestination = Pair(task.destinationLatitude, task.destinationLongitude),
                                            userLocation = Pair(currentLatitude, currentLongitude)
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        if (task.status == "PENDING") {
                                            Button(
                                                onClick = { activeTaskForStart = task },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Mulai Tugas (Ambil Foto)")
                                            }
                                        } else if (task.status == "ON_THE_WAY") {
                                            Button(
                                                onClick = { activeTaskForPod = task },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Selesaikan Tugas (Kirim Bukti POD)")
                                            }
                                        } else {
                                            Text("Selesai pada: ${task.endTime?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("Start: [Foto ✔]", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                                Text("End POD: [Foto ✔]", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Interactive camera liveness anti-spoof
        if (todayLog == null) {
            item {
                if (!isInsideGeofence) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                t["liveness_title"] ?: "Liveness Selfie Verification",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Liveness diperlukan karena Anda berada di luar area gudang.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Box(
                                modifier = Modifier
                                    .size(240.dp, 190.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black)
                                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (livenessState == AbsenlahViewModel.LivenessStep.IDLE) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Face, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                                        Text("Kamera Siap", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                    }
                                } else {
                                    // Live Camera Preview running real-time Face Detection
                                    com.example.ui.components.LivenessCameraPreview(
                                        viewModel = viewModel,
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    // Guide overlay text on top of camera
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.3f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(2.dp)
                                                .background(Color.Green)
                                                .align(Alignment.TopCenter)
                                        )

                                        when (livenessState) {
                                            AbsenlahViewModel.LivenessStep.PROMPT_BLINK -> {
                                                Column(
                                                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        "KEDIPKAN MATA",
                                                        color = Color.Yellow,
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                    Text(
                                                        "Kedipkan kedua mata Anda bersamaan",
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                                    )
                                                }
                                            }
                                            AbsenlahViewModel.LivenessStep.PROMPT_SMILE -> {
                                                Column(
                                                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        "SENYUM SEKARANG",
                                                        color = Color.Cyan,
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                    Text(
                                                        "Tunjukkan senyuman Anda ke kamera",
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                                    )
                                                }
                                            }
                                            AbsenlahViewModel.LivenessStep.VERIFYING -> {
                                                CircularProgressIndicator(
                                                    color = Color.Green,
                                                    modifier = Modifier.align(Alignment.Center)
                                                )
                                            }
                                            AbsenlahViewModel.LivenessStep.COMPLETED -> {
                                                Column(
                                                    modifier = Modifier.align(Alignment.Center),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Green, modifier = Modifier.size(36.dp))
                                                    Text(t["liveness_passed"] ?: "Verified!", color = Color.Green, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            else -> {}
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (livenessState == AbsenlahViewModel.LivenessStep.IDLE && !isLivenessPassed) {
                                Button(onClick = {
                                    if (hasCameraPermission.value) {
                                        viewModel.startLivenessVerification()
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                }) {
                                    Text("Mulai Verifikasi Liveness")
                                }
                            } else if (isLivenessPassed) {
                                Text(t["liveness_passed"] ?: "Face verified ✔", color = Color(0xFF388E3C), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Di Dalam Area Gudang", fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                                Text("Verifikasi liveness otomatis dilewati.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                            }
                        }
                    }
                }
            }
        }

        // Check-in and Check-out buttons
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val realDeviceId = android.provider.Settings.Secure.getString(
                            activity.contentResolver,
                            android.provider.Settings.Secure.ANDROID_ID
                        ) ?: "UNKNOWN_DEVICE"
                        viewModel.performCheckIn(
                            latitude = currentLatitude,
                            longitude = currentLongitude,
                            selfiePath = "/sdcard/selfie_${System.currentTimeMillis()}.png",
                            deviceId = realDeviceId,
                            onResult = { }
                        )
                    },
                    enabled = todayLog == null && (isInsideGeofence || isLivenessPassed),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("check_in_button")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(t["checkin"] ?: "Check-In")
                }

                Button(
                    onClick = {
                        viewModel.performCheckOut(
                            latitude = currentLatitude,
                            longitude = currentLongitude,
                            onResult = { }
                        )
                    },
                    enabled = todayLog != null && todayLog?.checkOutTime == null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("check_out_button")
                ) {
                    Icon(Icons.Default.ExitToApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(t["checkout"] ?: "Check-Out")
                }
            }
        }

        // ESS Stats overview
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        t["stats_title"] ?: "My Monthly Quotas & Stats",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Visualisasi pemantauan kepatuhan SOP bulanan Anda.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val items = listOf(
                        StatIndicatorItem(
                            t["stats_lateness"] ?: "Terlambat",
                            "${userStats?.latenessCount ?: 0}/3",
                            (userStats?.latenessCount ?: 0) / 3f,
                            Icons.Default.AccessTime,
                            if ((userStats?.latenessCount ?: 0) >= 3) Color.Red else Color(0xFFE65100)
                        ),
                        StatIndicatorItem(
                            t["stats_early"] ?: "Pulang Cepat",
                            "${userStats?.earlyDeparturesCount ?: 0}/3",
                            (userStats?.earlyDeparturesCount ?: 0) / 3f,
                            Icons.Default.ExitToApp,
                            if ((userStats?.earlyDeparturesCount ?: 0) >= 3) Color.Red else Color(0xFFF57C00)
                        ),
                        StatIndicatorItem(
                            t["stats_leave"] ?: "Cuti Terpakai",
                            "${userStats?.leaveQuotasUsed ?: 0}/4",
                            (userStats?.leaveQuotasUsed ?: 0) / 4f,
                            Icons.Default.DateRange,
                            Color(0xFF2E7D32)
                        ),
                        StatIndicatorItem(
                            t["stats_emergency"] ?: "Darurat Telat",
                            "${userStats?.emergencyLogsCount ?: 0}/2",
                            (userStats?.emergencyLogsCount ?: 0) / 2f,
                            Icons.Default.Warning,
                            Color(0xFFC62828)
                        )
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items.chunked(2).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                rowItems.forEach { indicator ->
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(indicator.icon, contentDescription = null, tint = indicator.color, modifier = Modifier.size(20.dp))
                                                Text(
                                                    indicator.progressText,
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = indicator.color
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(indicator.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(
                                                progress = indicator.ratio.coerceIn(0f, 1f),
                                                color = indicator.color,
                                                trackColor = indicator.color.copy(alpha = 0.2f),
                                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Jatah Libur Tersisa:", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
                            Text("${user.totalLeaveQuota} Hari", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Jatah Darurat Telat:", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
                            Text("${2 - (userStats?.emergencyLogsCount ?: 0)} / 2 Kali", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                        }
                    }
                }
            }
        }
    }

    // Settings Profile Dialog (Predefined avatars and passwords)
    if (showEmergencyLatenessDialog && todayLog != null) {
        AlertDialog(
            onDismissRequest = { showEmergencyLatenessDialog = false },
            title = { Text("Ajukan Jatah Darurat Telat") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Pilih Alasan Darurat Keterlambatan:")
                    Spacer(modifier = Modifier.height(8.dp))
                    val reasons = listOf("Ban Bocor", "Mogok", "Macet Parah", "Hujan Deras")
                    reasons.forEach { r ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { emergencyLatenessReason = r }
                        ) {
                            RadioButton(selected = emergencyLatenessReason == r, onClick = { emergencyLatenessReason = r })
                            Text(r)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Bukti Foto Darurat (Wajib):")
                    Button(
                        onClick = { showLatenessCameraProofDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(if (emergencyLatenessPhotoPath == null) "Ambil Foto Kendala" else "Foto Bukti Terunggah ✔")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val path = emergencyLatenessPhotoPath ?: "/sdcard/late_emergency_${System.currentTimeMillis()}.png"
                        viewModel.applyEmergencyLateness(todayLog!!.id, emergencyLatenessReason, path) { success ->
                            if (success) {
                                showEmergencyLatenessDialog = false
                                emergencyLatenessPhotoPath = null
                            }
                        }
                    },
                    enabled = emergencyLatenessPhotoPath != null
                ) {
                    Text("Kirim")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyLatenessDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    if (showLatenessCameraProofDialog) {
        AlertDialog(
            onDismissRequest = { showLatenessCameraProofDialog = false },
            title = { Text("Kamera Bukti Emergency") },
            text = { Text("Ambil gambar kendala ban bocor, mogok, dll. untuk validasi.") },
            confirmButton = {
                Button(onClick = {
                    emergencyLatenessPhotoPath = "/sdcard/late_emergency_${System.currentTimeMillis()}.png"
                    showLatenessCameraProofDialog = false
                }) {
                    Text("Ambil Foto")
                }
            }
        )
    }

    // POP-UP: Kirim bukti foto & lokasi jika absen di luar gudang dan belum sampai setelah 2 jam
    // We auto-show this popup if todayLog is not null, not arrived at warehouse, and 2 hours have passed since check-in
    // AND they have not submitted proof yet!
    val isOutsideDelayed = todayLog != null && 
            !todayLog!!.arrivedAtWarehouse && 
            (System.currentTimeMillis() - todayLog!!.checkInTime > 2 * 60 * 60 * 1000L) &&
            !todayLog!!.outsideProofSubmitted

    if (isOutsideDelayed) {
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss without action/proof submission! */ },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red) },
            title = { Text("⚠ PERINGATAN KELUAR GUDANG (> 2 JAM)", color = Color.Red, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Anda telah melakukan Absen Masuk di luar wilayah gudang / kantor lebih dari 2 Jam yang lalu, namun belum melakukan konfirmasi kedatangan di lokasi fisik gudang.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Sesuai regulasi perusahaan, Anda wajib mengunggah bukti berupa foto dan koordinat lokasi saat ini untuk verifikasi admin / supervisor.",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Display current coordinates
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Koordinat GPS Anda:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text("Lat: $currentLatitude, Lon: $currentLongitude", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { showOutsideArrivalCameraDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (outsideArrivalPhotoPath == null) "📸 Ambil Foto Selfie Lokasi" else "📸 Foto Selfie Lokasi OK ✔")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val path = outsideArrivalPhotoPath ?: "/sdcard/outside_proof_${System.currentTimeMillis()}.png"
                        viewModel.submitOutsideArrivalReport(todayLog!!.id, path, "Lat: $currentLatitude, Lon: $currentLongitude")
                    },
                    enabled = outsideArrivalPhotoPath != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) {
                    Text("Kirim Laporan Bukti")
                }
            }
        )
    }

    if (showOutsideArrivalCameraDialog) {
        AlertDialog(
            onDismissRequest = { showOutsideArrivalCameraDialog = false },
            title = { Text("Kamera Selfie Lokasi") },
            text = { Text("Ambil foto selfie di lokasi saat ini dengan latar belakang jalan atau armada kurir.") },
            confirmButton = {
                Button(onClick = {
                    outsideArrivalPhotoPath = "/sdcard/outside_proof_${System.currentTimeMillis()}.png"
                    showOutsideArrivalCameraDialog = false
                }) {
                    Text("Ambil Foto Selfie")
                }
            }
        )
    }

    // DIALOG: Supervisor Manual Attendance Override Input
    if (showManualAttendanceOverrideDialog) {
        AlertDialog(
            onDismissRequest = { onShowManualAttendanceOverrideDialogChange(false) },
            title = { Text("Koreksi / Absen Manual Atasan") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Gunakan form ini untuk membuat atau memperbaiki jam absensi pekerja. Absen manual ini disetujui atasan secara langsung dan TIDAK mengurangi jatah telat pekerja.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // ID Pekerja
                    OutlinedTextField(
                        value = if (manualOverridePekerjaId == 0) "" else manualOverridePekerjaId.toString(),
                        onValueChange = { onManualOverridePekerjaIdChange(it.toIntOrNull() ?: 0) },
                        label = { Text("ID Pekerja") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Tanggal
                    OutlinedTextField(
                        value = manualOverrideDate,
                        onValueChange = onManualOverrideDateChange,
                        placeholder = { Text("YYYY-MM-DD") },
                        label = { Text("Tanggal Absen") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Jam Masuk (Check-In Time)
                    OutlinedTextField(
                        value = manualOverrideCheckInTime,
                        onValueChange = onManualOverrideCheckInTimeChange,
                        placeholder = { Text("10:00") },
                        label = { Text("Jam Check-In") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Jam Pulang (Check-Out Time)
                    OutlinedTextField(
                        value = manualOverrideCheckOutTime,
                        onValueChange = onManualOverrideCheckOutTimeChange,
                        placeholder = { Text("20:00 (Kosongkan jika belum checkout)") },
                        label = { Text("Jam Check-Out") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        try {
                            val checkInFullStr = "$manualOverrideDate $manualOverrideCheckInTime"
                            val checkInLong = sdf.parse(checkInFullStr)?.time ?: System.currentTimeMillis()
                            
                            val checkOutLong = if (manualOverrideCheckOutTime.isNotEmpty()) {
                                val checkOutFullStr = "$manualOverrideDate $manualOverrideCheckOutTime"
                                sdf.parse(checkOutFullStr)?.time
                            } else {
                                null
                            }
                            
                            viewModel.overrideAttendanceManual(manualOverridePekerjaId, manualOverrideDate, checkInLong, checkOutLong)
                            onShowManualAttendanceOverrideDialogChange(false)
                        } catch (e: Exception) {
                            // ignore / handle
                        }
                    },
                    enabled = manualOverridePekerjaId > 0 && manualOverrideDate.isNotEmpty() && manualOverrideCheckInTime.isNotEmpty()
                ) {
                    Text("Simpan Absensi")
                }
            },
            dismissButton = {
                TextButton(onClick = { onShowManualAttendanceOverrideDialogChange(false) }) {
                    Text("Batal")
                }
            }
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Pengaturan Profil & Sandi") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Pilih Foto Profil (Emoji Avatar):", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val avatars = listOf("👨‍💼", "🚚", "👩‍💻", "👷", "🕵️", "👩‍⚕️", "🦊", "🌟")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        avatars.forEach { avatar ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (profilePhotoPredefined == avatar) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .border(1.dp, Color.Gray, CircleShape)
                                    .clickable {
                                        profilePhotoPredefined = avatar
                                        viewModel.updateProfilePhoto(avatar)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(avatar, fontSize = 20.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Ganti Kata Sandi Baru:", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settingNewPassword,
                        onValueChange = { settingNewPassword = it },
                        label = { Text("Kata Sandi Baru") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (settingNewPassword.isNotEmpty()) {
                        viewModel.changePasswordDirectly(settingNewPassword)
                    }
                    showSettingsDialog = false
                }) {
                    Text("Simpan Perubahan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Tutup")
                }
            }
        )
    }

    // Job START Dialog
    if (activeTaskForStart != null) {
        var simulatedCameraTaken by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { activeTaskForStart = null },
            title = { Text("Mulai Pengiriman Paket") },
            text = {
                Column {
                    Text("Wajib mengambil foto paket di depan gudang sebelum berangkat.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(Color.Black, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (simulatedCameraTaken) {
                            Text("Foto Paket Berhasil Diambil ✔", color = Color.Green)
                        } else {
                            Button(onClick = { simulatedCameraTaken = true }) {
                                Text("Ambil Foto (Simulasi Kamera)")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.startCourierTask(activeTaskForStart!!.id, "/sdcard/task_start_${activeTaskForStart!!.id}.png")
                        activeTaskForStart = null
                    },
                    enabled = simulatedCameraTaken
                ) {
                    Text("Berangkat Kirim Paket")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeTaskForStart = null }) {
                    Text("Batal")
                }
            }
        )
    }

    // Job POD Ending Dialog
    if (activeTaskForPod != null) {
        var simulatedCameraTaken by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { activeTaskForPod = null },
            title = { Text("Selesaikan Pengiriman (POD)") },
            text = {
                Column {
                    Text("Unggah Bukti Pengiriman (POD) foto paket di lokasi pelanggan.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(Color.Black, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (simulatedCameraTaken) {
                            Text("Foto Bukti Penerima Selesai ✔", color = Color.Green)
                        } else {
                            Button(onClick = { simulatedCameraTaken = true }) {
                                Text("Ambil Foto POD (Simulasi Kamera)")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.completeCourierTask(activeTaskForPod!!.id, "/sdcard/pod_${activeTaskForPod!!.id}.png", currentLatitude, currentLongitude)
                        activeTaskForPod = null
                    },
                    enabled = simulatedCameraTaken
                ) {
                    Text("Kirim POD")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeTaskForPod = null }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
fun HistoryScreen(viewModel: AbsenlahViewModel, user: Pekerja, t: Map<String, String>) {
    val logs by viewModel.pekerjaLogs.collectAsState()
    val leaves by viewModel.pekerjaLeaves.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            t["history"] ?: "ESS My History",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(12.dp))

        var selectedSubTab by remember { mutableStateOf("absensi") } // "absensi", "cuti"
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { selectedSubTab = "absensi" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedSubTab == "absensi") MaterialTheme.colorScheme.primary else Color.LightGray
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Daftar Absensi")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { selectedSubTab = "cuti" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedSubTab == "cuti") MaterialTheme.colorScheme.primary else Color.LightGray
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Daftar Cuti")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedSubTab == "absensi") {
                if (logs.isEmpty()) {
                    item {
                        Text("Belum ada riwayat absensi terdaftar.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                } else {
                    items(logs) { log ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(log.date, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = log.checkInStatus,
                                        color = if (log.checkInStatus == "LATE") Color.Red else Color.Green,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text("Check-In: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.checkInTime))}")
                                Text("Check-Out: ${log.checkOutTime?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: "--:--"}")
                                if (log.latenessMitigationType != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Mitigasi Telat Supervisor: ${log.latenessMitigationType}", fontWeight = FontWeight.Bold, color = Color.Blue, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            } else {
                if (leaves.isEmpty()) {
                    item {
                        Text("Belum ada pengajuan cuti terdaftar.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                } else {
                    items(leaves) { leave ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(leave.date, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = leave.status,
                                        color = when (leave.status) {
                                            "APPROVED" -> Color.Green
                                            "REJECTED" -> Color.Red
                                            "CANCELLED" -> Color.Gray
                                            else -> Color.Blue
                                        },
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text("Tipe: ${leave.leaveType}")
                                if (leave.leaveType == "EMERGENCY") {
                                    Text("Bukti Darurat: Terunggah ✔", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                if (leave.status == "PENDING" || leave.status == "APPROVED") {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { viewModel.cancelLeave(leave.id) { } },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Batalkan Cuti (SOP Maks H-1)")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaveScreen(viewModel: AbsenlahViewModel, user: Pekerja, t: Map<String, String>) {
    val userStats by viewModel.userStats.collectAsState()
    val allLeaves by viewModel.allLeaves.collectAsState()
    val allPekerja by viewModel.allPekerja.collectAsState()

    var dateString by remember { mutableStateOf("") }
    var leaveType by remember { mutableStateOf("STANDARD") } // "STANDARD", "EMERGENCY"
    var emergencyReason by remember { mutableStateOf("Ban Bocor") } // "Ban Bocor", "Mogok", "Macet Parah", "Hujan Deras"
    var emergencyPhotoPath by remember { mutableStateOf<String?>(null) }
    var showCameraProofDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                t["apply_leave"] ?: "Apply For Leave ESS",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text("Ajukan libur cuti. Maksimal kuota cuti disetel 4 kali per bulan secara ketat.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Kuota Cuti Bulan Ini", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Jatah Libur Tersisa: ${user.totalLeaveQuota} Hari (Maksimal 4x sebulan)")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Kalender Cuti Bersama - Juli 2026",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Pilih tanggal di bawah ini untuk mengajukan cuti. Hari bertanda merah telah dipesan oleh pekerja lain.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Calendar Header days of week
                    val daysOfWeek = listOf("Sn", "Sl", "Rb", "Km", "Jm", "Sb", "Mg")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        daysOfWeek.forEach { dayName ->
                            Text(
                                text = dayName,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // July 2026 starts on Wednesday (Index 2 if 0-indexed Mon-Sun)
                    val totalDays = 31
                    val startOffset = 2
                    val totalCells = totalDays + startOffset

                    val rows = (totalCells + 6) / 7
                    for (row in 0 until rows) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            for (col in 0..6) {
                                val cellIndex = row * 7 + col
                                val dayNum = cellIndex - startOffset + 1

                                if (cellIndex < startOffset || dayNum > totalDays) {
                                    // Empty cells
                                    Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                                } else {
                                    val formattedDate = "2026-07-${String.format("%02d", dayNum)}"
                                    val isSelected = dateString == formattedDate
                                    
                                    // Check leaves of OTHER workers on this date
                                    val otherWorkerLeaves = allLeaves.filter { 
                                        it.date == formattedDate && it.pekerjaId != user.id 
                                    }
                                    val hasOtherLeaves = otherWorkerLeaves.isNotEmpty()

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .padding(2.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when {
                                                    isSelected -> MaterialTheme.colorScheme.primary
                                                    hasOtherLeaves -> Color(0xFFFFEBEE)
                                                    dayNum == 4 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                    else -> Color.Transparent
                                                }
                                            )
                                            .border(
                                                width = if (dayNum == 4 && !isSelected) 1.dp else 0.dp,
                                                color = if (dayNum == 4 && !isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                shape = CircleShape
                                            )
                                            .clickable {
                                                dateString = formattedDate
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = dayNum.toString(),
                                                color = when {
                                                    isSelected -> Color.White
                                                    hasOtherLeaves -> Color.Red
                                                    dayNum == 4 -> MaterialTheme.colorScheme.primary
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                },
                                                fontWeight = if (dayNum == 4 || isSelected) FontWeight.Bold else FontWeight.Normal,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            if (hasOtherLeaves && !isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(4.dp)
                                                        .clip(CircleShape)
                                                        .background(Color.Red)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (dateString.isNotEmpty()) {
                        val leavesOnSelectedDay = allLeaves.filter { it.date == dateString }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Tanggal Terpilih: $dateString",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                val others = leavesOnSelectedDay.filter { it.pekerjaId != user.id }
                                if (others.isEmpty()) {
                                    Text("✔ Belum ada pekerja lain yang mengambil cuti pada tanggal ini.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                                } else {
                                    Text("⚠ Pekerja lain yang mengambil cuti pada tanggal ini:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                                    others.forEach { leave ->
                                        val workerName = allPekerja.find { it.id == leave.pekerjaId }?.name ?: "Pekerja Lain"
                                        Text("- $workerName (${leave.leaveType})", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Formulir Pengajuan Cuti",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text("Pilih Tipe Cuti / Alasan:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    val reasons = listOf(
                        "Cuti Tahunan" to "STANDARD",
                        "Sakit (Surat Dokter)" to "SICK",
                        "Acara Keluarga" to "CASUAL",
                        "Urusan Mendesak" to "EMERGENCY"
                    )
                    var selectedLeaveTypeIndex by remember { mutableStateOf(0) }
                    
                    reasons.forEachIndexed { index, pair ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedLeaveTypeIndex = index }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedLeaveTypeIndex == index,
                                onClick = { selectedLeaveTypeIndex = index }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(pair.first, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    var notifyManager by remember { mutableStateOf(true) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = notifyManager,
                            onCheckedChange = { notifyManager = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Kirim Notifikasi Langsung ke Manajer", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text("Simulasi kirim alert Whatsapp / push notification ke Supervisor.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        if (dateString.isEmpty()) "Silakan pilih tanggal pada kalender cuti di atas terlebih dahulu." 
                        else "Anda akan mengajukan cuti (${reasons[selectedLeaveTypeIndex].first}) pada tanggal: $dateString",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (dateString.isEmpty()) Color.Red else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val type = reasons[selectedLeaveTypeIndex].second
                            viewModel.submitLeave(dateString, type, null) { msg ->
                                if (notifyManager) {
                                    viewModel.addNotification(
                                        "PENGAJUAN CUTI",
                                        "Karyawan ${user.name} mengajukan cuti (${reasons[selectedLeaveTypeIndex].first}) untuk tanggal $dateString. Notifikasi telah terkirim ke Manajer."
                                    )
                                }
                                dateString = ""
                            }
                        },
                        enabled = dateString.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(t["submit"] ?: "Kirim Pengajuan Cuti")
                    }
                }
            }
        }
    }
}

@Composable
fun AdminPanelScreen(
    viewModel: AbsenlahViewModel, 
    user: Pekerja, 
    t: Map<String, String>, 
    activity: MainActivity,
    onOpenManualOverride: () -> Unit
) {
    val allLogs by viewModel.allLogs.collectAsState()
    val allLeaves by viewModel.allLeaves.collectAsState()
    val allSites by viewModel.allSites.collectAsState()
    val allPekerja by viewModel.allPekerja.collectAsState()
    val allCourierTasks by viewModel.allCourierTasks.collectAsState()
    val announcements by viewModel.announcements.collectAsState()

    var selectedAdminSubTab by remember { mutableStateOf("monitoring") } // "monitoring", "site", "override", "mitigasi", "excel", "announcement"

    // Site edit states
    var siteName by remember { mutableStateOf("") }
    var siteLat by remember { mutableStateOf(-6.2088) }
    var siteLon by remember { mutableStateOf(106.8456) }
    var siteRadius by remember { mutableStateOf("100") }

    // Announcement Draft state
    var annTitle by remember { mutableStateOf("") }
    var annContent by remember { mutableStateOf("") }
    var annDivision by remember { mutableStateOf("ALL") }

    // Announcement Configuration Settings
    var requireReadBeforeCheckIn by remember { mutableStateOf(false) }
    var announcementSoundAlert by remember { mutableStateOf(true) }
    var displayPopupSeconds by remember { mutableStateOf("10") }

    // Task builder state
    var courierTaskId by remember { mutableStateOf(0) }
    var taskTitle by remember { mutableStateOf("") }
    var taskNotes by remember { mutableStateOf("") }
    var taskAddress by remember { mutableStateOf("") }
    var taskLat by remember { mutableStateOf(-6.2088) }
    var taskLon by remember { mutableStateOf(106.8456) }

    // Excel report holders
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var csvPreviewText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "Panel Supervisor & Administrator",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal scrolling subtabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ElevatedFilterChip(selected = selectedAdminSubTab == "monitoring", onClick = { selectedAdminSubTab = "monitoring" }, label = { Text("Monitoring Kurir") })
            ElevatedFilterChip(selected = selectedAdminSubTab == "site", onClick = { selectedAdminSubTab = "site" }, label = { Text("Lokasi Gudang") })
            ElevatedFilterChip(selected = selectedAdminSubTab == "mitigasi", onClick = { selectedAdminSubTab = "mitigasi" }, label = { Text("Mitigasi Telat") })
            ElevatedFilterChip(selected = selectedAdminSubTab == "override", onClick = { selectedAdminSubTab = "override" }, label = { Text("Persetujuan SOP") })
            ElevatedFilterChip(selected = selectedAdminSubTab == "excel", onClick = { selectedAdminSubTab = "excel" }, label = { Text("Excel Laporan") })
            ElevatedFilterChip(selected = selectedAdminSubTab == "announcement", onClick = { selectedAdminSubTab = "announcement" }, label = { Text("Pengumuman") })
        }
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // 1. COURIER REAL-TIME MONITORING MAP & AVAILABILITY
            if (selectedAdminSubTab == "monitoring") {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Radar Live GPS Monitoring Kurir", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Memantau sebaran lokasi kurir yang sedang aktif.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Spacer(modifier = Modifier.height(10.dp))

                            // Compile list of couriers pins
                            val courierPins = allPekerja.filter { it.position.equals("Courier", ignoreCase = true) }
                                .map { it.name to Pair(it.lastKnownLatitude, it.lastKnownLongitude) }

                            SimulatedMap(
                                modifier = Modifier.fillMaxWidth().height(200.dp),
                                warehouseCenter = Pair(-6.2088, 106.8456),
                                couriers = courierPins
                            )
                        }
                    }
                }

                item {
                    Text("Daftar Kurir & Status Ketersediaan", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                }

                val couriersList = allPekerja.filter { it.position.equals("Courier", ignoreCase = true) }
                if (couriersList.isEmpty()) {
                    item {
                        Text("Tidak ada kurir terdaftar.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                } else {
                    items(couriersList) { courier ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(courier.name, fontWeight = FontWeight.Bold)
                                        Text("Force GPS: ${if (courier.isLocationForceOn) "AKTIF (Power)" else "NONAKTIF (Sore)"}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        Text("Lokasi: %.4f, %.4f".format(courier.lastKnownLatitude, courier.lastKnownLongitude), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    }

                                    // Availability indicator badge & Override switch
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SuggestionChip(
                                            onClick = { },
                                            label = { Text(if (courier.isAvailable) "Tersedia (Gudang)" else "Sedang Tugas") },
                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                labelColor = if (courier.isAvailable) Color.Green else Color.Red
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Switch(
                                            checked = courier.isAvailable,
                                            onCheckedChange = { viewModel.manualOverrideCourierAvailability(courier.id, it) }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Divider()
                                Spacer(modifier = Modifier.height(8.dp))

                                // Device Binding Control for this courier
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Perangkat: ${courier.deviceId ?: "Unbound"}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    if (courier.deviceId != null) {
                                        Button(
                                            onClick = { viewModel.unbindUserDevice(courier.id) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Lepas Binding", fontSize = 8.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Quick Delivery builder for this courier
                                Button(
                                    onClick = {
                                        courierTaskId = courier.id
                                        taskTitle = "Kirim Paket Kluster B"
                                        taskAddress = "Jl. Sudirman No 12"
                                        taskLat = -6.2100
                                        taskLon = 106.8500
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Buat Tugas Pengiriman Baru", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }

                // Courier Task Creator dialog / build pane
                if (courierTaskId > 0) {
                    item {
                        Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Pembuat Tugas Kurir Baru", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = taskTitle, onValueChange = { taskTitle = it }, label = { Text("Judul Tugas") })
                                OutlinedTextField(value = taskAddress, onValueChange = { taskAddress = it }, label = { Text("Alamat Tujuan") })
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Tentukan Koordinat Tujuan di Peta:")
                                SimulatedMap(
                                    modifier = Modifier.fillMaxWidth().height(120.dp),
                                    onMapClick = { lat, lon ->
                                        taskLat = lat
                                        taskLon = lon
                                    }
                                )
                                Text("Koordinat Terpilih: %.4f, %.4f".format(taskLat, taskLon), fontSize = 10.sp, fontFamily = FontFamily.Monospace)

                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        val courierName = allPekerja.find { it.id == courierTaskId }?.name ?: "Kurir"
                                        viewModel.createCourierTask(courierTaskId, courierName, taskTitle, taskNotes, taskAddress, taskLat, taskLon)
                                        courierTaskId = 0
                                    }) {
                                        Text("Terbitkan Tugas")
                                    }
                                    TextButton(onClick = { courierTaskId = 0 }) {
                                        Text("Batal")
                                    }
                                }
                            }
                        }
                    }
                }
                
                item {
                    Divider(modifier = Modifier.padding(vertical = 12.dp))
                    Text("Manajemen Device Binding (Semua Pekerja)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                
                val nonCourierList = allPekerja
                if (nonCourierList.isEmpty()) {
                    item {
                        Text("Tidak ada pekerja terdaftar.", modifier = Modifier.padding(8.dp), color = Color.Gray)
                    }
                } else {
                    items(nonCourierList) { pekerja ->
                        var isEditing by remember(pekerja.id) { mutableStateOf(false) }
                        var editDivision by remember(pekerja.id) { mutableStateOf(pekerja.division) }
                        var editPosition by remember(pekerja.id) { mutableStateOf(pekerja.position) }
                        var editRole by remember(pekerja.id) { mutableStateOf(pekerja.role) }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(pekerja.name, fontWeight = FontWeight.Bold)
                                        Text("Email: ${pekerja.email ?: "-"}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        Text("Divisi: ${pekerja.division} | Jabatan: ${pekerja.position} (${pekerja.role})", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        Text("Device Bound ID: ${pekerja.deviceId ?: "UNBOUND"}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    }
                                    
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { isEditing = !isEditing }) {
                                            Icon(
                                                imageVector = if (isEditing) Icons.Default.Close else Icons.Default.Edit,
                                                contentDescription = "Ubah Jabatan",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        if (pekerja.deviceId != null) {
                                            Button(
                                                onClick = { viewModel.unbindUserDevice(pekerja.id) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text("Reset ID", fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }

                                if (isEditing) {
                                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                                    Text("Ubah Penugasan Pekerja", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(modifier = Modifier.height(8.dp))

                                    OutlinedTextField(
                                        value = editDivision,
                                        onValueChange = { editDivision = it },
                                        label = { Text("Divisi (contoh: Logistics, HR, IT)") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))

                                    OutlinedTextField(
                                        value = editPosition,
                                        onValueChange = { editPosition = it },
                                        label = { Text("Jabatan (contoh: Courier, Supervisor, Admin)") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Role Akses: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        RadioButton(
                                            selected = editRole == "pekerja",
                                            onClick = { editRole = "pekerja" }
                                        )
                                        Text("Pekerja", style = MaterialTheme.typography.bodySmall)
                                        Spacer(modifier = Modifier.width(16.dp))
                                        RadioButton(
                                            selected = editRole == "admin",
                                            onClick = { editRole = "admin" }
                                        )
                                        Text("Admin", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = { isEditing = false }) {
                                            Text("Batal")
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                viewModel.updatePekerjaRolePosition(
                                                    pekerja.id,
                                                    editDivision,
                                                    editPosition,
                                                    editRole
                                                )
                                                isEditing = false
                                            }
                                        ) {
                                            Text("Simpan")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. GEOFENCE SITE SETTING WITH TAP SELECTOR MAP
            if (selectedAdminSubTab == "site") {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Pengaturan Geofence Gudang", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(value = siteName, onValueChange = { siteName = it }, label = { Text("Nama Site Gudang") })
                            OutlinedTextField(value = siteRadius, onValueChange = { siteRadius = it }, label = { Text("Radius (Meters)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Ketuk peta untuk menentukan koordinat Geofence baru:")
                            
                            SimulatedMap(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                onMapClick = { lat, lon ->
                                    siteLat = lat
                                    siteLon = lon
                                }
                            )

                            Text("Posisi Terpilih: %.5f, %.5f".format(siteLat, siteLon), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            Spacer(modifier = Modifier.height(12.dp))

                            Button(onClick = {
                                val r = siteRadius.toDoubleOrNull() ?: 100.0
                                viewModel.addGeofencedSite(siteName, siteLat, siteLon, r)
                                siteName = ""
                            }) {
                                Text("Simpan Lokasi Gudang")
                            }
                        }
                    }
                }

                item {
                    Text("Daftar Geofence Gudang Aktif", fontWeight = FontWeight.Bold)
                }

                items(allSites) { site ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(site.name, fontWeight = FontWeight.Bold)
                                Text("Radius: ${site.radiusInMeters} meter", style = MaterialTheme.typography.bodySmall)
                                Text("Coord: %.4f, %.4f".format(site.latitude, site.longitude), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                            IconButton(onClick = { viewModel.deleteGeofencedSite(site) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Hapus Site", tint = Color.Red)
                            }
                        }
                    }
                }
            }

            // 3. LATENESS CUSTOM MITIGATION OVERRIDES
            if (selectedAdminSubTab == "mitigasi") {
                item {
                    Text("Daftar Evaluasi Keterlambatan Absensi", fontWeight = FontWeight.Bold)
                    Text("Supervisor dapat menentukan mitigasi Latency.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }

                val lateLogs = allLogs.filter { it.checkInStatus == "LATE" && it.latenessMitigationType == "PENDING" }
                val resolvedLogs = allLogs.filter { it.checkInStatus == "LATE" && it.latenessMitigationType != "PENDING" }
                
                if (lateLogs.isEmpty() && resolvedLogs.isEmpty()) {
                    item {
                        Text("Tidak ada keterlambatan terdeteksi hari ini.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                } else {
                    if (lateLogs.isNotEmpty()) {
                        item {
                            Text("Menunggu Tinjauan Keterlambatan:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        }
                        items(lateLogs) { log ->
                            val workerName = allPekerja.find { it.id == log.pekerjaId }?.name ?: "Unknown"
                            val position = allPekerja.find { it.id == log.pekerjaId }?.position ?: "Unknown"
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("$workerName ($position)", fontWeight = FontWeight.Bold)
                                        Text(log.date, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("Waktu Check-In: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.checkInTime))}", style = MaterialTheme.typography.bodySmall)
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text("Pilih Tindakan Atasan:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.mitigateLateness(log.id, "REDUCE_LATENESS") },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Potong Jatah", fontSize = 10.sp)
                                        }
                                        Button(
                                            onClick = { viewModel.mitigateLateness(log.id, "EXCUSED") },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Maklumi (Bebas)", fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (resolvedLogs.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Riwayat Tinjauan Keterlambatan:", fontWeight = FontWeight.Bold, color = Color.Gray)
                        }
                        items(resolvedLogs) { log ->
                            val workerName = allPekerja.find { it.id == log.pekerjaId }?.name ?: "Unknown"
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(workerName, fontWeight = FontWeight.Bold)
                                        Text(log.date, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("Check-In: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.checkInTime))}", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "Keputusan: ${
                                            when(log.latenessMitigationType) {
                                                "REDUCE_LATENESS" -> "Potong Jatah Telat Bulanan"
                                                "REDUCE_EMERGENCY" -> "Potong Jatah Darurat Telat"
                                                "REDUCE_LEAVE" -> "Potong Jatah Cuti (Libur)"
                                                "EXCUSED" -> "Dimaklumi / Bebas Sanksi ✔"
                                                else -> log.latenessMitigationType
                                            }
                                        }",
                                        color = if (log.latenessMitigationType == "EXCUSED") Color(0xFF059669) else Color.Red,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. SUPERVISOR APPROVALS (OVERTIME, ESS CUTI, COURIERS)
            if (selectedAdminSubTab == "override") {
                item {
                    Column {
                        Text("Pipeline Persetujuan Supervisor", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onOpenManualOverride,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("➕ Input / Koreksi Absen Manual")
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // 4d. Outside Delayed Logs Proof Verification (> 2 hours)
                val outsideDelayedProofs = allLogs.filter { it.outsideProofSubmitted && it.outsideProofStatus == "PENDING" }
                if (outsideDelayedProofs.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Persetujuan Bukti Delay Luar Gudang > 2 Jam:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = Color.Red)
                    }
                    items(outsideDelayedProofs) { log ->
                        val workerName = allPekerja.find { it.id == log.pekerjaId }?.name ?: "Unknown"
                        val position = allPekerja.find { it.id == log.pekerjaId }?.position ?: "Unknown"
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)), // light red background
                            border = BorderStroke(1.dp, Color(0xFFFCA5A5))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("$workerName ($position)", fontWeight = FontWeight.Bold, color = Color(0xFF991B1B))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Tanggal Check-In: ${log.date}", style = MaterialTheme.typography.bodySmall)
                                Text("Waktu Kirim Bukti: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.outsideProofTime ?: 0L))}", style = MaterialTheme.typography.bodySmall)
                                Text("Koordinat GPS Bukti: ${log.outsideProofLocation}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text("File Selfie Bukti: ${log.outsideProofPhoto}", style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = { viewModel.approveOutsideArrivalReport(log.id, true) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Setujui", fontSize = 10.sp)
                                    }
                                    Button(
                                        onClick = { viewModel.approveOutsideArrivalReport(log.id, false) },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Tolak", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // 4a. Manual Entries for Couriers
                val courierManualLogs = allLogs.filter { it.isManualEntry }
                if (courierManualLogs.isNotEmpty()) {
                    item {
                        Text("Daftar Absen Manual Kurir (Luar Geofence):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    items(courierManualLogs) { log ->
                        val workerName = allPekerja.find { it.id == log.pekerjaId }?.name ?: "Unknown"
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("$workerName (Check-In Manual)", fontWeight = FontWeight.Bold)
                                Text("Alasan: ${log.manualArrivalReason ?: "Darurat"}", style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { viewModel.approveManualCourier(log.id, true) { } }) {
                                        Text("Setujui")
                                    }
                                    Button(onClick = { viewModel.approveManualCourier(log.id, false) { } }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                        Text("Tolak")
                                    }
                                }
                            }
                        }
                    }
                }

                // 4b. Overtime logs requiring approval
                val pendingOtLogs = allLogs.filter { it.overtimeApproved == false && it.overtimeMins > 0 }
                if (pendingOtLogs.isNotEmpty()) {
                    item {
                        Text("Pengajuan Overtime Lembur Kerja:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    items(pendingOtLogs) { log ->
                        val workerName = allPekerja.find { it.id == log.pekerjaId }?.name ?: "Unknown"
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("$workerName (Lembur ${log.overtimeMins} Mins)", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { viewModel.approveOvertimeLog(log.id, true) }) {
                                        Text("Setujui Lembur")
                                    }
                                    Button(onClick = { viewModel.approveOvertimeLog(log.id, false) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                        Text("Tolak Lembur")
                                    }
                                }
                            }
                        }
                    }
                }

                // 4c. Leave requests approval
                val pendingLeaves = allLeaves.filter { it.status == "PENDING" }
                if (pendingLeaves.isNotEmpty()) {
                    item {
                        Text("Pengajuan Cuti ESS Pending:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    items(pendingLeaves) { leave ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("${leave.pekerjaName} (Cuti ${leave.leaveType})", fontWeight = FontWeight.Bold)
                                Text("Tanggal: ${leave.date}", style = MaterialTheme.typography.bodySmall)
                                if (leave.leaveType == "EMERGENCY") {
                                    Text("Bukti Foto Darurat: Terlampir ✔", style = MaterialTheme.typography.bodySmall, color = Color.Red, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { viewModel.updateLeaveStatus(leave.id, "APPROVED") }) {
                                        Text("Setujui Cuti")
                                    }
                                    Button(onClick = { viewModel.updateLeaveStatus(leave.id, "REJECTED", "Ketentuan Overlap Posisi / Over quota") }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                        Text("Tolak Cuti")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. EXCEL REPORTS EXPORTER TAB
            if (selectedAdminSubTab == "excel") {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Ekpor Laporan Enterprise Excel / CSV", fontWeight = FontWeight.Bold)
                            Text("Unduh atau salin rekapitulasi data absensi dan cuti bulanan.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            csvPreviewText = viewModel.repository.exportAttendanceReportCsv()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Export Rekap Absensi", fontSize = 10.sp)
                                }

                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            csvPreviewText = viewModel.repository.exportLeaveReportCsv()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Export Rekap Cuti", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }

                if (csvPreviewText.isNotEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Hasil Ekspor Laporan (Excel CSV Format):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                    IconButton(onClick = {
                                        // Copy to clipboard simulation
                                        Toast.makeText(context, "Laporan disalin ke clipboard!", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.Share, contentDescription = "Copy text")
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .background(Color.Black, RoundedCornerShape(4.dp))
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState())
                                        .padding(8.dp)
                                ) {
                                    Text(csvPreviewText, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 6. SYSTEM POPUP ANNOUNCEMENT GENERATOR
            if (selectedAdminSubTab == "announcement") {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Terbitkan Pengumuman Popup & Push", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(value = annTitle, onValueChange = { annTitle = it }, label = { Text("Judul Pengumuman") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = annContent, onValueChange = { annContent = it }, label = { Text("Konten Detail") }, modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Divisi Sasaran:")
                            val divisions = listOf("ALL", "Logistics", "Operations", "HQ")
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                divisions.forEach { div ->
                                    ElevatedFilterChip(
                                        selected = annDivision == div,
                                        onClick = { annDivision = div },
                                        label = { Text(div) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    if (annTitle.isNotEmpty() && annContent.isNotEmpty()) {
                                        viewModel.createAnnouncement(annTitle, annContent, annDivision)
                                        annTitle = ""
                                        annContent = ""
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Terbitkan Ke Seluruh Ponsel Pekerja")
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Pengaturan Fitur Pengumuman", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Konfigurasikan SOP penyampaian informasi penting.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Wajibkan Membaca Sebelum Check-In", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = requireReadBeforeCheckIn,
                                    onCheckedChange = { requireReadBeforeCheckIn = it }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Bunyi Alert Notifikasi Keras", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = announcementSoundAlert,
                                    onCheckedChange = { announcementSoundAlert = it }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = displayPopupSeconds,
                                onValueChange = { displayPopupSeconds = it },
                                label = { Text("Durasi Tampilan Popup (Detik)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Daftar Pengumuman Aktif", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }

                if (announcements.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                                Text("Belum ada pengumuman aktif diterbitkan.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                            }
                        }
                    }
                } else {
                    items(announcements) { ann ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(ann.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                        Text("Target: ${ann.targetDivision} | Dari: ${ann.senderName}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteAnnouncement(ann) }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(ann.content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationsScreen(viewModel: AbsenlahViewModel, t: Map<String, String>) {
    val alerts by viewModel.notifications.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            t["notif_title"] ?: "Division Alerts Feed",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text("Daftar notifikasi push divisi.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(12.dp))

        if (alerts.isEmpty()) {
            Text("Tidak ada pemberitahuan baru.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(alerts) { alert ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(alert, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        }
    }
}

data class StatIndicatorItem(
    val label: String,
    val progressText: String,
    val ratio: Float,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: androidx.compose.ui.graphics.Color
)
