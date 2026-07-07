package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pekerja")
data class Pekerja(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val username: String,
    val passwordHash: String, // Plain-text or hashed password for demo
    val name: String,
    val division: String, // e.g., "Operations", "Logistics", "IT"
    val position: String, // e.g., "Courier", "Supervisor", "Admin"
    val deviceId: String?, // Device binding ID
    val role: String, // "pekerja", "supervisor", "admin"
    val mustChangePassword: Boolean = true,
    val totalLeaveQuota: Int = 4, // Cuti or libur is 4 times per month
    val email: String? = null,
    val googleId: String? = null,
    val profilePhotoPath: String? = null,
    val isAvailable: Boolean = true, // For couriers ("Tersedia" or "Tidak Tersedia")
    val lastKnownLatitude: Double = -6.2088,
    val lastKnownLongitude: Double = 106.8456,
    val isLocationForceOn: Boolean = false // Toggle for power-saving GPS
)
