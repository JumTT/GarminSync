package com.tools.garminsync.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 账号表：region = "CN" | "GLOBAL"
 * 密码与会话均经 Android Keystore 派生密钥 AES-GCM 加密后落库
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val region: String,
    val username: String,
    val passwordEnc: String,
    val sessionEnc: String?,
    val displayName: String?,
    val updatedAt: Long,
)

/**
 * 已同步活动表：唯一 ID = 国际区 activityId
 * status = "SUCCESS"（上传成功）| "DUPLICATE"（服务器判定已存在）
 */
@Entity(tableName = "synced_activities")
data class SyncedActivityEntity(
    @PrimaryKey val activityId: String,
    val activityName: String,
    val startTimeLocal: String,
    val status: String,
    val uploadedAt: Long,
)

/**
 * 已同步健康数据表：唯一 ID = 日期（yyyy-MM-dd）
 * status = "SUCCESS"（上传成功）| "DUPLICATE"（服务器判定已存在）
 */
@Entity(tableName = "synced_wellness")
data class SyncedWellnessEntity(
    @PrimaryKey val date: String,
    val status: String,
    val fileCount: Int,
    val uploadedAt: Long,
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE region = :region")
    suspend fun get(region: String): AccountEntity?

    @Upsert
    suspend fun upsert(account: AccountEntity)
}

@Dao
interface WellnessDao {
    @Query("SELECT * FROM synced_wellness WHERE date = :date")
    suspend fun get(date: String): SyncedWellnessEntity?

    @Upsert
    suspend fun upsert(entity: SyncedWellnessEntity)
}

@Dao
interface SyncedDao {
    @Query("SELECT * FROM synced_activities WHERE activityId = :id")
    suspend fun get(id: String): SyncedActivityEntity?

    @Query("SELECT * FROM synced_activities")
    suspend fun all(): List<SyncedActivityEntity>

    @Upsert
    suspend fun upsert(entity: SyncedActivityEntity)
}

@Database(
    entities = [AccountEntity::class, SyncedActivityEntity::class, SyncedWellnessEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun syncedDao(): SyncedDao
    abstract fun wellnessDao(): WellnessDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v2: 新增 synced_wellness 表（保留账号与活动同步记录） */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS synced_wellness (" +
                        "date TEXT NOT NULL PRIMARY KEY, " +
                        "status TEXT NOT NULL, " +
                        "fileCount INTEGER NOT NULL, " +
                        "uploadedAt INTEGER NOT NULL)",
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "garmin_sync.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
