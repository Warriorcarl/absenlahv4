package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.db.AbsenlahDatabase
import com.example.data.repository.AbsenlahRepository
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class LocationTrackingService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var repository: AbsenlahRepository

    private val CHANNEL_ID = "location_tracking_channel"
    private val NOTIFICATION_ID = 8881

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        val db = AbsenlahDatabase.getDatabase(this)
        repository = AbsenlahRepository(db)

        createNotificationChannel()
        val notification = buildNotification("Mencari lokasi GPS...")
        startForeground(NOTIFICATION_ID, notification)

        setupLocationUpdates()
    }

    private fun setupLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L).apply {
            setMinUpdateIntervalMillis(5000L)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                updateLocationInDb(location)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (unlikely: SecurityException) {
            Log.e("LocationService", "No location permission: $unlikely")
        }
    }

    private fun updateLocationInDb(location: Location) {
        scope.launch {
            // Find currently logged-in workers who are Couriers or have tracking on
            val workers = repository.getAllPekerja().firstOrNull() ?: emptyList()
            // We update the location of any courier who is active or has force location tracking on
            for (worker in workers) {
                if (worker.division.uppercase() == "KURIR" || worker.isLocationForceOn) {
                    repository.updateCourierLocation(worker.id, location.latitude, location.longitude)
                    Log.d("LocationService", "Updated location for worker ID ${worker.id}: ${location.latitude}, ${location.longitude}")
                }
            }

            // Update notification content
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification("Lokasi GPS Terkini: %.5f, %.5f".format(location.latitude, location.longitude)))
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pelacakan Kurir Aktif")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Layanan Lokasi Latar Belakang",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Saluran untuk pelacakan lokasi kurir secara real-time"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        job.cancel()
    }
}
