package com.phonelock.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppGroup::class, GroupMember::class, UsageRecord::class,
        GroupSite::class, ConfirmEscalation::class, StudyLogEntry::class, CalendarTask::class,
        CalcTask::class, CalcSavedItem::class, ConfirmCounter::class,
        Routine::class, RoutineLog::class
    ],
    version = 28,
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

        /** 그룹(app_group)은 Firebase에 동기화되지 않는 순수 로컬 데이터라 destructive migration으로
         *  날리면 사용자가 직접 만든 차단 그룹을 통째로 잃는다(HANDOFF.md 경고 참고) — description 컬럼
         *  하나만 추가하는 단순 변경이라 파괴적 마이그레이션 대신 ALTER TABLE로 안전하게 처리한다. */
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_group ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "phone_lock.db"
                ).addMigrations(MIGRATION_27_28).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
