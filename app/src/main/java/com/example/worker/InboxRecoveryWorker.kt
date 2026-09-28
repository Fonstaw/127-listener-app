package com.example.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.service.SmsInboxHelper
import java.util.concurrent.TimeUnit

/**
 * Lightweight periodic background worker that performs an incremental recovery scan
 * of the SMS inbox to catch any transactions that may have slipped past SMS_RECEIVED
 * broadcasts (e.g. while the phone was rebooting or under heavy system memory pressure).
 *
 * Runs locally on device without network constraints at a 12-hour interval.
 * Transactions discovered are saved locally as PENDING and enqueued for SyncWorker,
 * which handles network availability and backend synchronization.
 */
class InboxRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting periodic incremental SMS recovery scan (attempt: $runAttemptCount)...")

        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "READ_SMS permission not granted, skipping recovery scan.")
            return Result.success()
        }

        try {
            // Incremental scan from stored checkpoint; enqueueSync=false to avoid duplicate enqueuing
            val result = SmsInboxHelper.scanInboxForTelebirrTransactions(
                context = applicationContext,
                forceFullScan = false,
                enqueueSync = false
            )
            Log.i(TAG, "Recovery scan complete: scanned=${result.totalScanned}, newCount=${result.newCount}")

            if (result.newCount > 0) {
                // If any missed transactions were found and saved as PENDING, enqueue SyncWorker
                SyncWorker.enqueueSync(applicationContext, replaceExisting = false)
            }
            return Result.success()
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_SMS permission missing or revoked during recovery scan, not retrying.", e)
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during periodic recovery scan (attempt: $runAttemptCount), requesting retry with backoff.", e)
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "InboxRecoveryWorker"
        const val PERIODIC_WORK_NAME = "telebirr_inbox_recovery"

        /**
         * Schedules a 12-hour periodic recovery scan.
         * Runs locally on device without requiring network connectivity.
         * Constrained only by battery not low to remain lightweight.
         */
        fun schedulePeriodicRecovery(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val recoveryRequest = PeriodicWorkRequestBuilder<InboxRecoveryWorker>(
                12, TimeUnit.HOURS,
                30, TimeUnit.MINUTES // 30-minute flex window
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .addTag("telebirr_recovery")
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                recoveryRequest
            )
            Log.d(TAG, "Scheduled periodic recovery scan every 12 hours (policy=KEEP, batteryNotLow=true, local-only)")
        }
    }
}
