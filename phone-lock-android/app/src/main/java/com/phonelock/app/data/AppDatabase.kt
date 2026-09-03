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
        Routine::class, RoutineLog::class, QuoteOutcome::class
    ],
    // 82차: v30(calc_task autoGenEnabled/autoGenBatchSize) / v31(study_log_entry tag) / v32(quote_outcome
    // 신규 테이블) / v33(app_group selfMessageText) — 전부 아래 MIGRATION_29_33에서 명시적 ALTER/CREATE로
    // 처리한다. **중요 정정**: Room의 fallbackToDestructiveMigration()은 스키마 전체(모든 테이블)를 지우고
    // 새로 만든다 — "Firebase 동기화 테이블만 파괴적으로 마이그레이션된다"는 건 착각이었다(82차 작업 중
    // 스스로 발견). app_group처럼 동기화 안 되는 테이블도 다른 테이블 스키마가 바뀌면 폴백에 함께 쓸려
    // 나가므로, v29 이후 모든 스키마 변경은 반드시 명시적 마이그레이션을 거친다.
    // 83차: v34(다회독 상세화) — calc_task에 passCount/passIntervalsCsv, calendar_task에
    // passIndex/passTotal/passIntervalsCsv 추가. 기존 색상(red/yellow/green) 기준으로 passIndex를
    // 역산해 채워 넣어 진행 중이던 회독 상태를 보존한다(MIGRATION_33_34 참고).
    version = 34,
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
    abstract fun quoteOutcomeDao(): QuoteOutcomeDao

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

        /** 82차 발견: fallbackToDestructiveMigration()은 DB 전체를 지운다 — 아래 4단계는 전부 명시적으로 처리. */
        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calc_task ADD COLUMN autoGenEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE calc_task ADD COLUMN autoGenBatchSize INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE study_log_entry ADD COLUMN tag TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS quote_outcome (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "tier INTEGER NOT NULL, quoteText TEXT NOT NULL, choice TEXT NOT NULL, timestampMillis INTEGER NOT NULL" +
                        ")"
                )
            }
        }
        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_group ADD COLUMN selfMessageText TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calc_task ADD COLUMN passCount INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE calc_task ADD COLUMN passIntervalsCsv TEXT NOT NULL DEFAULT '3,4'")
                db.execSQL("ALTER TABLE calendar_task ADD COLUMN passIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE calendar_task ADD COLUMN passTotal INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE calendar_task ADD COLUMN passIntervalsCsv TEXT NOT NULL DEFAULT '3,4'")
                // 기존 색상 기준으로 진행 중이던 회독 위치를 보존(그 외 레거시 색상은 기본값 0/3 그대로 둠).
                db.execSQL("UPDATE calendar_task SET passIndex = 1 WHERE color = 'yellow'")
                db.execSQL("UPDATE calendar_task SET passIndex = 2 WHERE color = 'green'")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "phone_lock.db"
                ).addMigrations(
                    MIGRATION_27_28, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33,
                    MIGRATION_33_34
                )
                    .fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
