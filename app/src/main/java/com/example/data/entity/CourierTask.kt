package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "courier_tasks")
data class CourierTask(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val courierId: Int,
    val courierName: String,
    val title: String,
    val notes: String?,
    val destinationAddress: String,
    val destinationLatitude: Double,
    val destinationLongitude: Double,
    val status: String = "PENDING", // "PENDING", "ON_THE_WAY", "DELIVERED"
    val startTime: Long? = null,
    val endTime: Long? = null,
    val startPhotoProof: String? = null,
    val endPhotoProof: String? = null
)
