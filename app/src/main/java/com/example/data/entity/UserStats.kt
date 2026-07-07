package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_stats")
data class UserStats(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pekerjaId: Int,
    val month: String, // format "YYYY-MM"
    val leaveQuotasUsed: Int = 0,
    val latenessCount: Int = 0, // Used for "3rd lateness converts to leave day deduction"
    val earlyDeparturesCount: Int = 0, // Max 3 instances/month
    val emergencyLogsCount: Int = 0, // Max 2 instances per 6 months
    val totalFinesAmount: Int = 0, // Fines accumulated in Rupiah
    val totalBonusesAmount: Int = 0 // Discipline bonuses accumulated in Rupiah
)
