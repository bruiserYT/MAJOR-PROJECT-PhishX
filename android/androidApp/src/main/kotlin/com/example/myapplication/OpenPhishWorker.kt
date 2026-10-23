package com.example.myapplication

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that periodically refreshes the OpenPhish community feed.
 *
 * Scheduled every 12 hours (minimum WorkManager periodic interval is 15 min;
 * 12 h is a reasonable balance between freshness and battery/data usage).
 * The actual execution time is not guaranteed — WorkManager may defer it to
 * meet OS background restrictions, Doze mode, and battery optimisations.
 *
 * Requires a network connection (CONNECTED constraint) so the device does
 * not attempt a download when offline.
 */
class OpenPhishWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting OpenPhish feed refresh")
        val success = OpenPhishRepository.refreshFeed(applicationContext)
        return if (success) {
            Log.i(TAG, "Feed refresh succeeded")
            Result.success()
        } else {
            // Retry lets WorkManager back off and try again later.
            Log.w(TAG, "Feed refresh failed — will retry")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "OpenPhishWorker"
        private const val WORK_NAME = "openphish-feed-refresh"

        /** Enqueue a periodic feed-refresh job. Safe to call multiple times. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<OpenPhishWorker>(
                12, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
