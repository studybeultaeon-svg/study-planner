package com.phonelock.app.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * AppDatabase(Room)가 fallbackToDestructiveMigration()을 쓰고 있어서, 스키마 버전이 바뀌면
 * (앱 업데이트로 새 버전이 설치되어 Room이 DB를 처음 여는 순간) 로컬 DB 전체가 삭제된다.
 * Repository/Room을 통해 백업하면 그 접근 자체가 이미 Room을 열어버려서 늦기 때문에, 여기서는
 * Room을 아예 거치지 않고 원본 SQLite 파일을 직접 읽어 JSON으로 백업한다.
 *
 * 반드시 PhoneLockRepository(=Room)를 생성하기 이전에 호출해야 한다.
 */
object PreMigrationBackup {

    private val TABLES = listOf(
        "app_group", "group_member", "usage_record", "group_site", "confirm_escalation",
        "study_log_entry", "calendar_task", "calc_task", "calc_saved_item", "confirm_counter",
        "routine", "routine_log"
    )

    /**
     * 마지막으로 기록된 versionCode와 현재 versionCode가 다르면(=앱이 업데이트됐으면) 원본 DB 파일을
     * 통째로 백업해서 파일로 저장한다. 첫 설치(기록 없음)이거나 버전이 그대로면 백업하지 않는다.
     * 어느 경우든 마지막에는 현재 versionCode를 기록해 다음 실행과 비교할 수 있게 한다.
     *
     * @return 실제로 백업 파일이 만들어졌으면 그 File, 아니면 null.
     */
    fun backupIfVersionChanged(context: Context): File? {
        val prefs = AppPreferences(context)
        val currentVersionCode = currentVersionCode(context)
        val last = prefs.lastKnownVersionCode
        val shouldBackup = last != -1L && last != currentVersionCode
        if (!shouldBackup) {
            prefs.lastKnownVersionCode = currentVersionCode
            return null
        }

        val dbFile = context.getDatabasePath("phone_lock.db")
        if (!dbFile.exists()) {
            prefs.lastKnownVersionCode = currentVersionCode
            return null
        }

        val backupJson = runCatching { dumpRawDatabase(dbFile) }.getOrNull()
        prefs.lastKnownVersionCode = currentVersionCode
        if (backupJson == null) return null

        return runCatching {
            val backupDir = File(context.getExternalFilesDir(null), "auto_backups")
            backupDir.mkdirs()
            val outFile = File(backupDir, "backup_v${last}_to_v${currentVersionCode}_${System.currentTimeMillis()}.json")
            outFile.writeText(backupJson.toString(2))
            outFile
        }.getOrNull()
    }

    /** auto_backups 폴더의 백업 파일들을 최신순으로 나열한다(52차, 그룹 데이터 복구 UI용). */
    fun listBackups(context: Context): List<File> {
        val backupDir = File(context.getExternalFilesDir(null), "auto_backups")
        return backupDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun currentVersionCode(context: Context): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * 원본 SQLite 파일을 읽기 전용으로 직접 열어, 알려진 테이블들을 각각 통째로 덤프한다. 테이블마다
     * 실제 컬럼 목록을 PRAGMA table_info로 동적으로 조회하므로 스키마 버전과 무관하게 동작하고,
     * 테이블 자체가 없는 옛 버전이라도 해당 테이블만 건너뛰고 나머지는 최대한 백업한다.
     */
    private fun dumpRawDatabase(dbFile: File): JSONObject {
        val result = JSONObject()
        val database = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
        database.use { db ->
            TABLES.forEach { table ->
                runCatching {
                    val rowsJson = JSONArray()
                    db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val row = JSONObject()
                            for (i in 0 until cursor.columnCount) {
                                val name = cursor.getColumnName(i)
                                when (cursor.getType(i)) {
                                    Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                                    Cursor.FIELD_TYPE_INTEGER -> row.put(name, cursor.getLong(i))
                                    Cursor.FIELD_TYPE_FLOAT -> row.put(name, cursor.getDouble(i))
                                    else -> row.put(name, cursor.getString(i))
                                }
                            }
                            rowsJson.put(row)
                        }
                    }
                    result.put(table, rowsJson)
                }
            }
        }
        return result
    }
}
