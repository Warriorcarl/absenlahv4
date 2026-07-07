package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.dao.*
import com.example.data.entity.*

@Database(
    entities = [
        Pekerja::class,
        UserStats::class,
        DynamicConfig::class,
        GeofencedSite::class,
        AttendanceLog::class,
        LeaveRequest::class,
        CourierTask::class,
        Announcement::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AbsenlahDatabase : RoomDatabase() {
    abstract fun pekerjaDao(): PekerjaDao
    abstract fun userStatsDao(): UserStatsDao
    abstract fun dynamicConfigDao(): DynamicConfigDao
    abstract fun geofencedSiteDao(): GeofencedSiteDao
    abstract fun attendanceLogDao(): AttendanceLogDao
    abstract fun leaveRequestDao(): LeaveRequestDao
    abstract fun courierTaskDao(): CourierTaskDao
    abstract fun announcementDao(): AnnouncementDao

    companion object {
        @Volatile
        private var INSTANCE: AbsenlahDatabase? = null

        fun getDatabase(context: Context): AbsenlahDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AbsenlahDatabase::class.java,
                    "absenlah_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
