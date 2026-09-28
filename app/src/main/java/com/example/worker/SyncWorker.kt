package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.data.AppDatabase
import com.example.data.SyncResult
import com.example.data.SyncStatus
import com.example.data.TransactionRepository
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting durable transaction sync work (attempt: $runAttemptCount)...")
        val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
        val repository = TransactionRepository(applicationContext, dao)

        var hasTemporaryFailure = false
        var processedCount = 0
        val attemptedInThisWorkerRun = mutableSetOf<String>()

        // Process all eligible PENDING transactions; loop until no more eligible pending work is found
        while (true) {
            val pendingList = dao.getTransactionsByStatus(SyncStatus.PENDING)
            // Filter to only unattempted transactions in this run to prevent endless loops
            val toProcess = pendingList.filter { it.transactionId !in attemptedInThisWorkerRun }

            if (toProcess.isEmpty()) {
                Log.d(TAG, "No more eligible PENDING transactions to sync in this pass.")
                break
            }

            var processedInIteration = 0
            for (tx in toProcess) {
                attemptedInThisWorkerRun.add(tx.transactionId)

                // Skip if no longer in PENDING state (e.g. synced or deleted concurrently)
                val current = dao.getTransactionById(tx.transactionId)
                if (current == null || current.syncStatus != SyncStatus.PENDING) {
                    Log.d(TAG, "Skipping txId=${tx.transactionId}: status is ${current?.syncStatus}")
                    continue
                }

                Log.d(TAG, "Attempting sync for txId=${tx.transactionId}, amount=${tx.amount}")
                val result = repository.syncTransaction(tx)
                processedInIteration++
                processedCount++

                when (result) {
                    is SyncResult.Success -> {
                        Log.i(TAG, "SyncWorker successfully synced txId=${tx.transactionId}")
                    }
                    is SyncResult.TransientError -> {
                        Log.w(TAG, "SyncWorker transient error for txId=${tx.transactionId}: ${result.message}")
                        hasTemporaryFailure = true
                        // Stop further processing in this run; transient error indicates network/server unavailable
                        break
                    }
                    is SyncResult.PermanentError -> {
                        Log.e(TAG, "SyncWorker permanent error for txId=${tx.transactionId}: ${result.message}. Marked as FAILED (no automatic retry).")
                    }
                }
            }

            // If we hit a temporary failure or didn't process any transactions in this pass, exit loop
            if (hasTemporaryFailure || processedInIteration == 0) {
                break
            }
        }

        Log.i(TAG, "SyncWorker finished pass: processed=$processedCount, temporaryFailures=$hasTemporaryFailure")

        return if (hasTemporaryFailure) {
            Log.w(TAG, "Transient failure encountered (runAttemptCount=$runAttemptCount). WorkManager will retry with exponential backoff.")
            Result.retry()
        } else {
            // Safety check: if a new pending transaction arrived at the end of the loop, schedule a follow-up
            val unhandled = dao.getTransactionsByStatus(SyncStatus.PENDING).filter { it.transactionId !in attemptedInThisWorkerRun }
            if (unhandled.isNotEmpty()) {
                Log.d(TAG, "Found ${unhandled.size} newly arrived pending transactions, enqueuing follow-up sync.")
                enqueueSync(applicationContext, replaceExisting = false)
            }
            Result.success()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val UNIQUE_WORK_NAME = "telebirr_transaction_sync"

        /**
         * Enqueues durable synchronization work with WorkManager.
         * - Requires network connectivity
         * - Uses exponential backoff (starting at 15s)
         * - Unique work policy defaults to KEEP so multiple rapid SMS messages coalesce cleanly
         *   without cancelling an actively running sync worker.
         */
        fun enqueueSync(context: Context, replaceExisting: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .addTag("telebirr_sync")
                .build()

            val policy = if (replaceExisting) {
                ExistingWorkPolicy.REPLACE
            } else {
                ExistingWorkPolicy.KEEP
            }

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                workRequest
            )
            Log.d(TAG, "Enqueued unique sync work request (policy=$policy)")
        }
    }
}
