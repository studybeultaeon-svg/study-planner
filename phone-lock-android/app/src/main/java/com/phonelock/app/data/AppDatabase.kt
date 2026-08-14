package com.phonelock.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AppGroup::class, GroupMember::class, UsageRecord::class,
        GroupSite::class, ConfirmEscalation::class, StudyLogEntry::class, CalendarTask::class,
        CalcTask::class, CalcSavedItem::class, ConfirmCounter::class,
        Routine::class, RoutineLog::class
    ],
    version = 27,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appGroupDao(): AppGroupDao
    abstract fun groupMemberDao(): GroupMemberDao
    abstract fun usageRecordDao(): UsageRecordDao
    abstract fun groupSiteDao(): GroupSiteDao
    abstract fun confirmEscalationDao(): ConfirmEscalationDao
    abstract fun studyLogEntryDao(): StudyLogEntryDao
    abstract fun calendarTaskDao(): CalendarTaskDao
    abstract fun calcTaskDao(): CalcTaskDao
    abstract fun calcSavedItemDao(): CalcSavedItemDao
    abstract fun confirmCounterDao(): ConfirmCounterDao
    abstract fun routineDao(): RoutineDao
    abstract fun routineLogDao(): RoutineLogDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "phone_lock.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
