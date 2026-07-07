package com.example.data.rule

import com.example.data.entity.DynamicConfig
import com.example.data.entity.AttendanceLog
import java.text.SimpleDateFormat
import java.util.*

object RuleEngine {

    // Evaluate shift timing: Standard is 10:00 AM - 08:00 PM.
    // Lateness starts at 10:11 AM.
    // Dynamic Shift: If check-in is before 10:00 AM, the shift duration is exactly 10 hours from check-in.
    // Otherwise standard check-out is 08:00 PM.
    fun calculateExpectedCheckOut(
        checkInTimeMs: Long,
        configMap: Map<String, String>
    ): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = checkInTimeMs
        }

        val shiftStartHour = configMap["shift_start_hour"]?.toIntOrNull() ?: 10
        val shiftStartMin = configMap["shift_start_minute"]?.toIntOrNull() ?: 0
        val shiftEndHour = configMap["shift_end_hour"]?.toIntOrNull() ?: 20
        val shiftEndMin = configMap["shift_end_minute"]?.toIntOrNull() ?: 0

        // Create standard shift-start threshold for today
        val todayShiftStart = Calendar.getInstance().apply {
            timeInMillis = checkInTimeMs
            set(Calendar.HOUR_OF_DAY, shiftStartHour)
            set(Calendar.MINUTE, shiftStartMin)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        return if (calendar.before(todayShiftStart)) {
            // Checked in "sebelum" (before) 10:00 AM -> Dynamic shift locks to exactly 10 hours from check-in
            checkInTimeMs + (10 * 60 * 60 * 1000L)
        } else {
            // Checked in "setelah" (at or after) 10:00 AM -> Standard shift end (08:00 PM today)
            val todayShiftEnd = Calendar.getInstance().apply {
                timeInMillis = checkInTimeMs
                set(Calendar.HOUR_OF_DAY, shiftEndHour)
                set(Calendar.MINUTE, shiftEndMin)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            todayShiftEnd.timeInMillis
        }
    }

    // Lateness Calculation: Lateness starts at 10:11 AM
    // Returns status ("ON_TIME" or "LATE"), fine amount, and discipline bonus
    fun evaluateCheckIn(
        checkInTimeMs: Long,
        latenessCountThisMonth: Int, // current count (e.g., if already 2, this is the 3rd)
        hasLeaveQuota: Boolean,
        configMap: Map<String, String>
    ): CheckInResult {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = checkInTimeMs
        }

        val shiftStartHour = configMap["shift_start_hour"]?.toIntOrNull() ?: 10
        val shiftStartMin = configMap["shift_start_minute"]?.toIntOrNull() ?: 0
        val latenessThresholdMins = configMap["lateness_threshold_mins"]?.toIntOrNull() ?: 11
        val disciplineBonusAmt = configMap["discipline_bonus_amt"]?.toIntOrNull() ?: 20000

        // Threshold for lateness (10:11 AM)
        val latenessLimitCalendar = Calendar.getInstance().apply {
            timeInMillis = checkInTimeMs
            set(Calendar.HOUR_OF_DAY, shiftStartHour)
            set(Calendar.MINUTE, shiftStartMin + latenessThresholdMins)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Base shift-start for calculating exact lateness minutes (relative to 10:00 AM)
        val baseShiftStartCalendar = Calendar.getInstance().apply {
            timeInMillis = checkInTimeMs
            set(Calendar.HOUR_OF_DAY, shiftStartHour)
            set(Calendar.MINUTE, shiftStartMin)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (calendar.before(latenessLimitCalendar)) {
            // Checked in before 10:11 AM -> ON_TIME
            return CheckInResult(
                status = "ON_TIME",
                fine = 0,
                bonus = disciplineBonusAmt,
                convertedToLeaveDeduction = false
            )
        }

        // Worker is late. Calculate late duration in minutes
        val lateMins = ((checkInTimeMs - baseShiftStartCalendar.timeInMillis) / (60 * 1000)).toInt()

        // Apply 3rd Lateness Rule: The 3rd lateness in a month converts into a leave day (jatah libur)
        // deduction instead of a cash fine (only applies once if leave quota exists).
        // The 4th+ lateness reverts to standard fines calculated from 10:00 AM.
        // If latenessCountThisMonth is exactly 2, this incoming late is the 3rd.
        if (latenessCountThisMonth == 2 && hasLeaveQuota) {
            return CheckInResult(
                status = "LATE",
                fine = 0,
                bonus = 0,
                convertedToLeaveDeduction = true
            )
        }

        // Standard lateness fines calculation
        val fine = calculateLatenessFine(lateMins, configMap)
        return CheckInResult(
            status = "LATE",
            fine = fine,
            bonus = 0,
            convertedToLeaveDeduction = false
        )
    }

    private fun calculateLatenessFine(lateMins: Int, configMap: Map<String, String>): Int {
        // Fines:
        // 10:11-10:30 (11-30 mins): 5k
        // 10:31-11:00 (31-60 mins): 10k
        // 11:01-11:30 (61-90 mins): 15k
        // 11:31-12:00 (91-120 mins): 20k
        // 12:01-12:30 (121-150 mins): 30k
        // 12:31-01:00 (151-180 mins): 40k
        // 01:01-01:30 (181-210 mins): 50k
        // +10k per 30 mins after
        return when {
            lateMins <= 30 -> configMap["fine_11_30"]?.toIntOrNull() ?: 5000
            lateMins <= 60 -> configMap["fine_31_60"]?.toIntOrNull() ?: 10000
            lateMins <= 90 -> configMap["fine_61_90"]?.toIntOrNull() ?: 15000
            lateMins <= 120 -> configMap["fine_91_120"]?.toIntOrNull() ?: 20000
            lateMins <= 150 -> configMap["fine_121_150"]?.toIntOrNull() ?: 30000
            lateMins <= 180 -> configMap["fine_151_180"]?.toIntOrNull() ?: 40000
            lateMins <= 210 -> configMap["fine_181_210"]?.toIntOrNull() ?: 50000
            else -> {
                val baseFine = configMap["fine_181_210"]?.toIntOrNull() ?: 50000
                val extraIntervals = (lateMins - 210 + 29) / 30 // ceil division
                val incrementalFine = configMap["fine_incremental_per_30m"]?.toIntOrNull() ?: 10000
                baseFine + (extraIntervals * incrementalFine)
            }
        }
    }

    // Progressive Overtime: Starts 1 min after expected checkout time.
    // Overtime rates: 30m (5k), 60m (10k), 90m (15k), 120m (20k), 150m (30k), 180m (30k), 210m (40k), 240m (50k), +10k per 30m after.
    fun calculateOvertime(
        checkOutTimeMs: Long,
        expectedCheckOutTimeMs: Long,
        configMap: Map<String, String>
    ): OvertimeResult {
        if (checkOutTimeMs <= expectedCheckOutTimeMs) {
            return OvertimeResult(overtimeMins = 0, overtimePay = 0)
        }

        val otMins = ((checkOutTimeMs - expectedCheckOutTimeMs) / (60 * 1000)).toInt()
        if (otMins <= 0) return OvertimeResult(0, 0)

        val pay = when {
            otMins <= 30 -> configMap["ot_30m"]?.toIntOrNull() ?: 5000
            otMins <= 60 -> configMap["ot_60m"]?.toIntOrNull() ?: 10000
            otMins <= 90 -> configMap["ot_90m"]?.toIntOrNull() ?: 15000
            otMins <= 120 -> configMap["ot_120m"]?.toIntOrNull() ?: 20000
            otMins <= 150 -> configMap["ot_150m"]?.toIntOrNull() ?: 30000
            otMins <= 180 -> configMap["ot_180m"]?.toIntOrNull() ?: 30000
            otMins <= 210 -> configMap["ot_210m"]?.toIntOrNull() ?: 40000
            otMins <= 240 -> configMap["ot_240m"]?.toIntOrNull() ?: 50000
            else -> {
                val basePay = configMap["ot_240m"]?.toIntOrNull() ?: 50000
                val extraIntervals = (otMins - 240 + 29) / 30 // ceil
                val incrementalPay = configMap["ot_incremental_per_30m"]?.toIntOrNull() ?: 10000
                basePay + (extraIntervals * incrementalPay)
            }
        }

        return OvertimeResult(overtimeMins = otMins, overtimePay = pay)
    }

    // Early Departure Evaluation: Cannot leave before 05:00 PM.
    // Violations deduct the Discipline Bonus and Leave Quota.
    fun evaluateCheckOutEarly(
        checkOutTimeMs: Long,
        expectedCheckOutTimeMs: Long,
        configMap: Map<String, String>
    ): EarlyCheckOutResult {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = checkOutTimeMs
        }

        // Limit of early departure: 05:00 PM
        val limitCalendar = Calendar.getInstance().apply {
            timeInMillis = checkOutTimeMs
            set(Calendar.HOUR_OF_DAY, 17) // 05:00 PM
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val leavesBefore5PM = calendar.before(limitCalendar)
        val leavesBeforeExpected = checkOutTimeMs < expectedCheckOutTimeMs

        return if (leavesBeforeExpected) {
            EarlyCheckOutResult(
                isEarlyDeparture = true,
                isViolationBefore5PM = leavesBefore5PM,
                description = if (leavesBefore5PM) {
                    "Early departure before 05:00 PM (SOP VIOLATION: Deducts Discipline Bonus & Leave Quota)"
                } else {
                    "Early departure before expected check-out"
                }
            )
        } else {
            EarlyCheckOutResult(
                isEarlyDeparture = false,
                isViolationBefore5PM = false,
                description = "Normal check-out"
            )
        }
    }

    data class CheckInResult(
        val status: String,
        val fine: Int,
        val bonus: Int,
        val convertedToLeaveDeduction: Boolean
    )

    data class OvertimeResult(
        val overtimeMins: Int,
        val overtimePay: Int
    )

    data class EarlyCheckOutResult(
        val isEarlyDeparture: Boolean,
        val isViolationBefore5PM: Boolean,
        val description: String
    )

    fun validateAttendanceAgainstSOP(
        log: AttendanceLog,
        configMap: Map<String, String>
    ): SOPValidationResult {
        val violations = mutableListOf<String>()
        var isCompliant = true

        // 1. Check early check-out
        if (log.checkOutTime != null) {
            val earlyCheckOutResult = evaluateCheckOutEarly(log.checkOutTime, log.expectedCheckOutTime, configMap)
            if (earlyCheckOutResult.isEarlyDeparture) {
                if (log.earlyDepartureApproved != true) {
                    isCompliant = false
                    violations.add("Early check-out without approval (Expected ${formatTimeMs(log.expectedCheckOutTime)}, Actual ${formatTimeMs(log.checkOutTime)})")
                }
            }
        }

        // 2. Check lateness
        if (log.checkInStatus == "LATE" && log.latenessMitigationType == null && log.isManualEntry == false) {
            isCompliant = false
            violations.add("Late check-in without mitigation or supervisor approval")
        }

        // 3. Check unauthorized overtime
        if (log.checkOutTime != null && log.checkOutTime > log.expectedCheckOutTime) {
            val otMins = ((log.checkOutTime - log.expectedCheckOutTime) / (60 * 1000)).toInt()
            if (otMins > 0) {
                if (log.overtimeApproved != true) {
                    isCompliant = false
                    violations.add("Unauthorized Overtime of $otMins minutes (No supervisor approval)")
                }
            }
        }

        return SOPValidationResult(
            isCompliant = isCompliant,
            violations = violations,
            statusSummary = if (isCompliant) "COMPLIANT WITH SOP" else "SOP VIOLATION DETECTED"
        )
    }

    private fun formatTimeMs(timeMs: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))
    }

    data class SOPValidationResult(
        val isCompliant: Boolean,
        val violations: List<String>,
        val statusSummary: String
    )
}
