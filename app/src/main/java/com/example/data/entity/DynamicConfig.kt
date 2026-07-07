package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dynamic_config")
data class DynamicConfig(
    @PrimaryKey val key: String, // Unique config keys like "discipline_bonus", "lateness_threshold"
    val value: String,
    val description: String,
    val valueType: String // "INT", "STRING", "FLOAT", "JSON"
)
