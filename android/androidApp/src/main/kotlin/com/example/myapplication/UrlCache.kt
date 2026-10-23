package com.example.myapplication

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Entity(tableName = "url_verdicts")
data class CachedVerdict(
    @PrimaryKey
    val urlHash: String,
    val verdict: String,
    val confidence: Double,
    val checkedAt: Long
)

@Dao
interface UrlVerdictDao {
    @Query(
        """
        SELECT * FROM url_verdicts
        WHERE urlHash = :hash AND checkedAt >= :validAfter
        LIMIT 1
        """
    )
    suspend fun findFresh(hash: String, validAfter: Long): CachedVerdict?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(verdict: CachedVerdict): Long

    @Query("DELETE FROM url_verdicts WHERE checkedAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM url_verdicts")
    suspend fun clear(): Int
}

@Database(
    entities = [CachedVerdict::class],
    version = 1,
    exportSchema = false
)
abstract class PhishXDatabase : RoomDatabase() {

    abstract fun urlVerdicts(): UrlVerdictDao

    companion object {
        @Volatile
        private var instance: PhishXDatabase? = null

        fun get(context: Context): PhishXDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhishXDatabase::class.java,
                    "phishx_cache.db"
                ).build().also { database ->
                    instance = database
                }
            }
        }
    }
}

/** Hashes the URL so the original URL does not need to be stored in the cache. */
fun hashUrl(url: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))

    return bytes.joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }
}

class CacheCleanupWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        return try {
            val cutoff = System.currentTimeMillis() -
                    TimeUnit.HOURS.toMillis(24)

            PhishXDatabase.get(applicationContext)
                .urlVerdicts()
                .deleteOlderThan(cutoff)

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<CacheCleanupWorker>(
                    24,
                    TimeUnit.HOURS
                ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "phishx-cache-cleanup",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
