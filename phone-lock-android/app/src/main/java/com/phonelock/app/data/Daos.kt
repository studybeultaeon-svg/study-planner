package com.phonelock.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppGroupDao {
    @Query("SELECT * FROM app_group ORDER BY id")
    fun observeAll(): Flow<List<AppGroup>>

    @Query("SELECT * FROM app_group WHERE id = :id")
    suspend fun getById(id: Long): AppGroup?

    @Query("SELECT * FROM app_group WHERE enabled = 1")
    suspend fun getAllEnabled(): List<AppGroup>

    @Query("SELECT * FROM app_group ORDER BY id")
    suspend fun getAllOnce(): List<AppGroup>

    @Insert
    suspend fun insert(group: AppGroup): Long

    @Update
    suspend fun update(group: AppGroup)

    @Delete
    suspend fun delete(group: AppGroup)

    @Query("DELETE FROM app_group")
    suspend fun deleteAll()
}

@Dao
interface GroupMemberDao {
    @Query("SELECT * FROM group_member WHERE groupId = :groupId")
    fun observeMembers(groupId: Long): Flow<List<GroupMember>>

    @Query("SELECT * FROM group_member WHERE groupId = :groupId")
    suspend fun getMembers(groupId: Long): List<GroupMember>

    @Query("SELECT * FROM group_member WHERE packageName = :packageName LIMIT 1")
    suspend fun findByPackage(packageName: String): GroupMember?

    @Query("SELECT * FROM group_member WHERE packageName = :packageName")
    suspend fun findAllByPackage(packageName: String): List<GroupMember>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(member: GroupMember)

    @Query("DELETE FROM group_member WHERE groupId = :groupId AND packageName = :packageName")
    suspend fun remove(groupId: Long, packageName: String)

    @Query("DELETE FROM group_member WHERE groupId = :groupId")
    suspend fun clearGroup(groupId: Long)

    @Query("DELETE FROM group_member")
    suspend fun deleteAllMembers()
}

@Dao
interface UsageRecordDao {
    @Query("SELECT * FROM usage_record WHERE groupId = :groupId AND date = :date LIMIT 1")
    suspend fun get(groupId: Long, date: String): UsageRecord?

    @Query("SELECT * FROM usage_record ORDER BY date DESC")
    suspend fun getAllOnce(): List<UsageRecord>

    @Query("SELECT * FROM usage_record WHERE groupId = :groupId AND date >= :fromDate AND date < :toDate")
    suspend fun getInRangeExclusiveEnd(groupId: Long, fromDate: String, toDate: String): List<UsageRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: UsageRecord)

    @Query("DELETE FROM usage_record WHERE date < :cutoffDate")
    suspend fun deleteBefore(cutoffDate: String): Int
}

@Dao
interface GroupSiteDao {
    @Query("SELECT * FROM group_site WHERE groupId = :groupId ORDER BY domain")
    fun observeSites(groupId: Long): Flow<List<GroupSite>>

    @Query("SELECT * FROM group_site WHERE groupId = :groupId")
    suspend fun getSites(groupId: Long): List<GroupSite>

    @Query("SELECT * FROM group_site")
    suspend fun getAllOnce(): List<GroupSite>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(site: GroupSite)

    @Query("DELETE FROM group_site WHERE groupId = :groupId")
    suspend fun clearGroup(groupId: Long)

    @Query("DELETE FROM group_site")
    suspend fun deleteAllSites()
}

@Dao
interface ConfirmEscalationDao {
    @Query("SELECT * FROM confirm_escalation WHERE groupId = :groupId LIMIT 1")
    suspend fun get(groupId: Long): ConfirmEscalation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ConfirmEscalation)
}

@Dao
interface ConfirmCounterDao {
    @Query("SELECT * FROM confirm_counter WHERE groupId = :groupId AND date = :date LIMIT 1")
    suspend fun get(groupId: Long, date: String): ConfirmCounter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ConfirmCounter)

    @Query("DELETE FROM confirm_counter WHERE date < :cutoffDate")
    suspend fun deleteBefore(cutoffDate: String): Int
}

@Dao
interface StudyLogEntryDao {
    @Query("SELECT * FROM study_log_entry WHERE dateKey = :dateKey ORDER BY startedAt")
    suspend fun getByDate(dateKey: String): List<StudyLogEntry>

    /** 모임 멤버 상세의 캘린더 날짜 상세에서 "그 날 얼마나 공부했는지"를 보여주기 위한 범위 조회. */
    @Query("SELECT * FROM study_log_entry WHERE dateKey BETWEEN :fromKey AND :toKey")
    suspend fun getInRange(fromKey: String, toKey: String): List<StudyLogEntry>

    @Insert
    suspend fun insert(entry: StudyLogEntry)

    @Query("DELETE FROM study_log_entry WHERE dateKey < :cutoffDate")
    suspend fun deleteBefore(cutoffDate: String): Int
}

@Dao
interface CalendarTaskDao {
    @Query("SELECT * FROM calendar_task WHERE dateKey = :dateKey ORDER BY sortOrder")
    suspend fun getByDate(dateKey: String): List<CalendarTask>

    @Query("SELECT * FROM calendar_task WHERE dateKey BETWEEN :fromKey AND :toKey ORDER BY dateKey, sortOrder")
    suspend fun getByDateRange(fromKey: String, toKey: String): List<CalendarTask>

    @Query("SELECT * FROM calendar_task")
    suspend fun getAllOnce(): List<CalendarTask>

    @Insert
    suspend fun insert(task: CalendarTask): Long

    @Update
    suspend fun update(task: CalendarTask)

    @Delete
    suspend fun delete(task: CalendarTask)

    @Query("DELETE FROM calendar_task WHERE dateKey < :cutoffKey")
    suspend fun deleteBefore(cutoffKey: String): Int

    @Query("DELETE FROM calendar_task")
    suspend fun deleteAll()
}

@Dao
interface CalcTaskDao {
    @Query("SELECT * FROM calc_task ORDER BY sortOrder")
    suspend fun getAll(): List<CalcTask>

    @Insert
    suspend fun insert(task: CalcTask): Long

    @Update
    suspend fun update(task: CalcTask)

    @Delete
    suspend fun delete(task: CalcTask)

    @Query("DELETE FROM calc_task")
    suspend fun deleteAll()
}

@Dao
interface CalcSavedItemDao {
    @Query("SELECT * FROM calc_saved_item ORDER BY sortOrder")
    suspend fun getAll(): List<CalcSavedItem>

    @Insert
    suspend fun insert(item: CalcSavedItem): Long

    @Update
    suspend fun update(item: CalcSavedItem)

    @Delete
    suspend fun delete(item: CalcSavedItem)

    @Query("DELETE FROM calc_saved_item")
    suspend fun deleteAll()
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routine WHERE archived = 0 ORDER BY sortOrder")
    fun observeAll(): Flow<List<Routine>>

    @Query("SELECT * FROM routine WHERE archived = 0 ORDER BY sortOrder")
    suspend fun getAll(): List<Routine>

    @Query("SELECT * FROM routine WHERE id = :id")
    suspend fun getById(id: Long): Routine?

    @Insert
    suspend fun insert(routine: Routine): Long

    @Update
    suspend fun update(routine: Routine)

    @Delete
    suspend fun delete(routine: Routine)

    @Query("DELETE FROM routine")
    suspend fun deleteAll()
}

@Dao
interface RoutineLogDao {
    @Query("SELECT * FROM routine_log WHERE dateKey = :dateKey")
    suspend fun getByDate(dateKey: String): List<RoutineLog>

    @Query("SELECT * FROM routine_log WHERE routineId = :routineId ORDER BY dateKey")
    suspend fun getByRoutine(routineId: Long): List<RoutineLog>

    @Query("SELECT * FROM routine_log")
    suspend fun getAllOnce(): List<RoutineLog>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: RoutineLog)

    @Query("DELETE FROM routine_log WHERE routineId = :routineId AND dateKey = :dateKey")
    suspend fun delete(routineId: Long, dateKey: String)

    @Query("DELETE FROM routine_log WHERE routineId = :routineId")
    suspend fun deleteForRoutine(routineId: Long)

    @Query("DELETE FROM routine_log")
    suspend fun deleteAll()
}
