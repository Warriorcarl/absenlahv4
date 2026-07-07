package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "attendance_logs")
data class AttendanceLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pekerjaId: Int,
    val date: String, // format "YYYY-MM-DD"
    
    // Check-in info
    val checkInTime: Long, // timestamp in ms
    val checkInLatitude: Double,
    val checkInLongitude: Double,
    val checkInSelfiePath: String?,
    val checkInLivenessVerified: Boolean = false,
    val checkInDeviceId: String,
    val checkInStatus: String, // "ON_TIME", "LATE"
    val checkInFine: Int = 0,
    val checkInBonus: Int = 0, // "Bonus Disiplin"
    val shiftType: String = "STANDARD", // "STANDARD", "DYNAMIC"
    val expectedCheckOutTime: Long, // Locks 10 hours from check-in if checked in before 10 AM, otherwise standard shift-end (08:00 PM)

    // Check-out info
    val checkOutTime: Long? = null,
    val checkOutLatitude: Double? = null,
    val checkOutLongitude: Double? = null,
    val checkOutStatus: String? = null, // "NORMAL", "EARLY_DEPARTURE", "OVERTIME"
    val checkOutFine: Int = 0,
    val checkOutBonusDeducted: Int = 0, // Did early departure deduct the Bonus Disiplin?
    val overtimeMins: Int = 0,
    val overtimeFineBonusDeduction: Boolean = false, // Early departure violations deduct leave/bonus
    val overtimeApproved: Boolean? = null, // pending supervisor approval
    val overtimeApprovalBy: Int? = null,
    val earlyDepartureApproved: Boolean? = null, // pending supervisor approval
    val earlyDepartureApprovalBy: Int? = null,

    // Supervisor override for Couriers
    val isManualEntry: Boolean = false,
    val manualApprovedBy: Int? = null,
    val manualApprovedTime: Long? = null,
    val manualArrivalReason: String? = null, // Courier supervisor arrival approved within 2-hour physical limit

    // New Courier manual entry arrival trackers
    val manualArrivalConfirmed: Boolean = false, // True if courier confirmed delay within 2 hours
    val arrivedAtWarehouse: Boolean = false, // True when courier physically arrives
    val arrivalTimeAtWarehouse: Long? = null,
    val courierDelayPenalized: Boolean = false, // True if penalized after 14:00 without arrival or confirmation

    // Lateness Choice trackers
    val latenessMitigationType: String? = null, // "REDUCE_LATENESS" (reduces allowed latenesses), "REDUCE_LEAVE" (deducts leave quota), "REDUCE_EMERGENCY" (deducts emergency quota)
    val latenessApprovedBy: Int? = null,

    // Outside check-in arrival proof tracker (for > 2 hours delayed arrival)
    val outsideProofPhoto: String? = null,
    val outsideProofLocation: String? = null,
    val outsideProofStatus: String? = null, // "PENDING", "APPROVED", "REJECTED"
    val outsideProofTime: Long? = null,
    val outsideProofSubmitted: Boolean = false
)
