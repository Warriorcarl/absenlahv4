package com.example.data.dao

import androidx.room.*
import com.example.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PekerjaDao {
    @Query("SELECT * FROM pekerja WHERE id = :id")
    fun getPekerjaById(id: Int): Flow<Pekerja?>

    @Query("SELECT * FROM pekerja WHERE username = :username LIMIT 1")
    suspend fun getPekerjaByUsername(username: String): Pekerja?

    @Query("SELECT * FROM pekerja WHERE email = :email LIMIT 1")
    suspend fun getPekerjaByEmail(email: String): Pekerja?

    @Query("SELECT * FROM pekerja WHERE googleId = :googleId LIMIT 1")
    suspend fun getPekerjaByGoogleId(googleId: String): Pekerja?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPekerja(pekerja: Pekerja)

    @Query("SELECT * FROM pekerja")
    fun getAllPekerja(): Flow<List<Pekerja>>

    @Update
    suspend fun updatePekerja(pekerja: Pekerja)
}

@Dao
interface UserStatsDao {
    @Query("SELECT * FROM user_stats WHERE pekerjaId = :pekerjaId AND month = :month LIMIT 1")
    fun getUserStats(pekerjaId: Int, month: String): Flow<UserStats?>

    @Query("SELECT * FROM user_stats WHERE pekerjaId = :pekerjaId AND month = :month LIMIT 1")
    suspend fun getUserStatsSync(pekerjaId: Int, month: String): UserStats?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserStats(userStats: UserStats)

    @Update
    suspend fun updateUserStats(userStats: UserStats)
}

@Dao
interface DynamicConfigDao {
    @Query("SELECT * FROM dynamic_config WHERE `key` = :key LIMIT 1")
    fun getConfigByKey(key: String): Flow<DynamicConfig?>

    @Query("SELECT * FROM dynamic_config WHERE `key` = :key LIMIT 1")
    suspend fun getConfigByKeySync(key: String): DynamicConfig?

    @Query("SELECT * FROM dynamic_config")
    fun getAllConfigs(): Flow<List<DynamicConfig>>

    @Query("SELECT * FROM dynamic_config")
    suspend fun getAllConfigsSync(): List<DynamicConfig>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: DynamicConfig)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfigs(configs: List<DynamicConfig>)
}

@Dao
interface GeofencedSiteDao {
    @Query("SELECT * FROM geofenced_sites")
    fun getAllSites(): Flow<List<GeofencedSite>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSite(site: GeofencedSite)

    @Delete
    suspend fun deleteSite(site: GeofencedSite)
}

@Dao
interface AttendanceLogDao {
    @Query("SELECT * FROM attendance_logs WHERE pekerjaId = :pekerjaId ORDER BY date DESC")
    fun getLogsForPekerja(pekerjaId: Int): Flow<List<AttendanceLog>>

    @Query("SELECT * FROM attendance_logs WHERE pekerjaId = :pekerjaId AND date = :date LIMIT 1")
    fun getLogForDate(pekerjaId: Int, date: String): Flow<AttendanceLog?>

    @Query("SELECT * FROM attendance_logs WHERE pekerjaId = :pekerjaId AND date = :date LIMIT 1")
    suspend fun getLogForDateSync(pekerjaId: Int, date: String): AttendanceLog?

    @Query("SELECT * FROM attendance_logs ORDER BY date DESC, checkInTime DESC")
    fun getAllLogs(): Flow<List<AttendanceLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AttendanceLog)

    @Update
    suspend fun updateLog(log: AttendanceLog)
}

@Dao
interface LeaveRequestDao {
    @Query("SELECT * FROM leave_requests WHERE division = :division ORDER BY date DESC")
    fun getLeavesByDivision(division: String): Flow<List<LeaveRequest>>

    @Query("SELECT * FROM leave_requests WHERE pekerjaId = :pekerjaId ORDER BY date DESC")
    fun getLeavesByPekerja(pekerjaId: Int): Flow<List<LeaveRequest>>

    @Query("SELECT * FROM leave_requests WHERE date = :date AND position = :position AND status = 'APPROVED'")
    suspend fun getApprovedLeavesForDateAndPosition(date: String, position: String): List<LeaveRequest>

    @Query("SELECT * FROM leave_requests ORDER BY date DESC")
    fun getAllLeaves(): Flow<List<LeaveRequest>>

    @Query("SELECT * FROM leave_requests WHERE id = :id LIMIT 1")
    fun getLeaveById(id: Int): Flow<LeaveRequest?>

    @Query("SELECT * FROM leave_requests WHERE id = :id LIMIT 1")
    suspend fun getLeaveByIdSync(id: Int): LeaveRequest?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLeaveRequest(leave: LeaveRequest)

    @Update
    suspend fun updateLeaveRequest(leave: LeaveRequest)
}

@Dao
interface CourierTaskDao {
    @Query("SELECT * FROM courier_tasks WHERE courierId = :courierId ORDER BY id DESC")
    fun getTasksForCourier(courierId: Int): Flow<List<CourierTask>>

    @Query("SELECT * FROM courier_tasks ORDER BY id DESC")
    fun getAllTasks(): Flow<List<CourierTask>>

    @Query("SELECT * FROM courier_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskByIdSync(id: Int): CourierTask?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: CourierTask)

    @Update
    suspend fun updateTask(task: CourierTask)
}

@Dao
interface AnnouncementDao {
    @Query("SELECT * FROM announcements WHERE isActive = 1 ORDER BY publishedAt DESC")
    fun getActiveAnnouncements(): Flow<List<Announcement>>

    @Query("SELECT * FROM announcements ORDER BY publishedAt DESC")
    fun getAllAnnouncements(): Flow<List<Announcement>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnouncement(announcement: Announcement)

    @Delete
    suspend fun deleteAnnouncement(announcement: Announcement)
}
