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
 * Runs at a sensible, non-aggressive interval (every 12 hours) with battery and network constraints.
 */
class InboxRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting periodic incremental SMS recovery scan...")

        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "READ_SMS permission not granted, skipping recovery scan.")
            return Result.success()
        }

        try {
            // Incremental scan from stored checkpoint
            val result = SmsInboxHelper.scanInboxForTelebirrTransactions(
                context = applicationContext,
                forceFullScan = false
            )
            Log.i(TAG, "Recovery scan complete: scanned=${result.totalScanned}, newCount=${result.newCount}")

            if (result.newCount > 0) {
                // If any missed transactions were found and saved, trigger sync worker
                SyncWorker.enqueueSync(applicationContext, replaceExisting = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during periodic recovery scan", e)
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "InboxRecoveryWorker"
        const val PERIODIC_WORK_NAME = "telebirr_inbox_recovery"

        /**
         * Schedules a 12-hour periodic recovery scan.
         * Uses battery not low and network connected constraints to be friendly to battery.
         */
        fun schedulePeriodicRecovery(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val recoveryRequest = PeriodicWorkRequestBuilder<InboxRecoveryWorker>(
                12, TimeUnit.HOURS,
                30, TimeUnit.MINUTES // 30-minute flex window
            )
                .setConstraints(constraints)
                .addTag("telebirr_recovery")
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                recoveryRequest
            )
            Log.d(TAG, "Scheduled periodic recovery scan every 12 hours (policy=KEEP)")
        }
    }
}
