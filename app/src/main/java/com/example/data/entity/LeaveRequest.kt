package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "leave_requests")
data class LeaveRequest(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pekerjaId: Int,
    val pekerjaName: String,
    val division: String, // locked to same division for visibility
    val position: String, // to prevent same-position overlap: "auto-reject if another worker in the same position is already on leave"
    val date: String, // format "YYYY-MM-DD"
    val shiftStartTime: Long, // timestamp for shift start to check "must submit >= 2 hours before shift"
    val createdAtTime: Long, // to verify submission timing
    val leaveType: String, // "STANDARD", "EMERGENCY"
    val emergencyPhotoProofPath: String?, // photo proof for emergency logs
    val status: String, // "PENDING", "APPROVED", "REJECTED", "CANCELLED"
    val approvedBy: Int? = null,
    val rejectReason: String? = null
)
