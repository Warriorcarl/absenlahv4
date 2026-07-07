package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "announcements")
data class Announcement(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val content: String,
    val publishedAt: Long,
    val isActive: Boolean = true,
    val senderName: String,
    val targetDivision: String = "ALL" // "ALL", "Logistics", "Operations", etc.
)
