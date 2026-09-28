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

        // Process all pending transactions; loop until no more pending work is found
        while (true) {
            val pendingList = dao.getTransactionsByStatus(SyncStatus.PENDING)
            val retryableFailedList = dao.getTransactionsByStatus(SyncStatus.FAILED)
            val toProcess = (pendingList + retryableFailedList).distinctBy { it.transactionId }

            if (toProcess.isEmpty()) {
                Log.d(TAG, "No pending or retryable transactions to sync.")
                break
            }

            var processedInIteration = 0
            for (tx in toProcess) {
                // Skip if already marked synced by a concurrent thread/action
                val current = dao.getTransactionById(tx.transactionId)
                if (current == null || current.syncStatus == SyncStatus.SYNCED) {
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
                    }
                    is SyncResult.PermanentError -> {
                        Log.e(TAG, "SyncWorker permanent error for txId=${tx.transactionId}: ${result.message}. Marked as FAILED (no retry).")
                    }
                }
            }

            // If nothing was processed in this pass or if we hit a temporary failure, stop the loop
            if (processedInIteration == 0 || hasTemporaryFailure) {
                break
            }
        }

        Log.i(TAG, "SyncWorker finished pass: processed=$processedCount, temporaryFailures=$hasTemporaryFailure")

        return if (hasTemporaryFailure) {
            if (runAttemptCount < MAX_RETRIES) {
                Log.w(TAG, "Enqueuing automatic retry with exponential backoff (attempt $runAttemptCount of $MAX_RETRIES)")
                Result.retry()
            } else {
                Log.e(TAG, "Max retry attempts reached ($MAX_RETRIES). Transactions remain safely in database for manual or next network sync.")
                Result.failure()
            }
        } else {
            Result.success()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val UNIQUE_WORK_NAME = "telebirr_transaction_sync"
        private const val MAX_RETRIES = 6

        /**
         * Enqueues durable synchronization work with WorkManager.
         * - Requires network connectivity
         * - Uses exponential backoff (starting at 15s)
         * - Unique work policy ensures multiple rapid SMS messages coalesce cleanly
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
