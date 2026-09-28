package com.example.data

import android.content.Context
import android.util.Log
import com.example.api.ApiClient
import com.example.api.TransactionRequest
import com.example.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface SyncResult {
    data object Success : SyncResult
    data class TransientError(val message: String) : SyncResult
    data class PermanentError(val message: String) : SyncResult
}

class TransactionRepository(
    private val context: Context,
    private val transactionDao: TransactionDao
) {

    val allTransactions: Flow<List<Transaction>> = transactionDao.getAllTransactions()

    /**
     * Safely persists a new transaction to the local Room database with PENDING status.
     * Guaranteed to be idempotent and safe against race conditions.
     * Returns true if a new transaction was stored, false if it was already recorded or invalid.
     */
    suspend fun insertPendingTransaction(transaction: Transaction): Boolean = withContext(Dispatchers.IO) {
        if (transaction.transactionId.isBlank() || transaction.amount <= 0.0) {
            Log.w(TAG, "Cannot persist invalid transaction: ID='${transaction.transactionId}', Amount=${transaction.amount}")
            return@withContext false
        }

        // Check if already in Room database
        val existing = transactionDao.getTransactionById(transaction.transactionId)
        if (existing != null) {
            Log.d(TAG, "Transaction ${transaction.transactionId} already exists (status: ${existing.syncStatus}). Skipping.")
            return@withContext false
        }

        val rowId = transactionDao.insertTransaction(
            transaction.copy(syncStatus = SyncStatus.PENDING)
        )
        if (rowId == -1L) {
            Log.d(TAG, "Duplicate insert ignored by Room primary key for: ${transaction.transactionId}")
            return@withContext false
        }

        Log.i(TAG, "Successfully persisted new transaction ${transaction.transactionId} to Room as PENDING.")
        return@withContext true
    }

    /**
     * Inserts the transaction locally and enqueues durable WorkManager background synchronization.
     * Returns true if newly inserted, false if duplicate.
     */
    suspend fun saveAndSyncTransaction(transaction: Transaction): Boolean {
        val inserted = insertPendingTransaction(transaction)
        if (inserted) {
            // Trigger durable background synchronization
            SyncWorker.enqueueSync(context, replaceExisting = true)
        }
        return inserted
    }

    /**
     * Synchronizes a single transaction with the backend API.
     * Returns a [SyncResult] indicating Success, TransientError (retryable), or PermanentError.
     */
    suspend fun syncTransaction(transaction: Transaction): SyncResult = withContext(Dispatchers.IO) {
        // Fast-path: Already synced
        val latest = transactionDao.getTransactionById(transaction.transactionId)
        if (latest?.syncStatus == SyncStatus.SYNCED) {
            Log.d(TAG, "Transaction ${transaction.transactionId} is already marked SYNCED. Skipping network call.")
            return@withContext SyncResult.Success
        }

        // Validate payload before network request
        if (transaction.transactionId.isBlank() || transaction.amount <= 0.0) {
            Log.w(TAG, "Cannot sync invalid transaction (ID: '${transaction.transactionId}', Amount: ${transaction.amount})")
            transactionDao.updateSyncStatus(transaction.transactionId, SyncStatus.FAILED)
            return@withContext SyncResult.PermanentError("Invalid transaction fields")
        }

        try {
            val request = TransactionRequest(
                transactionId = transaction.transactionId,
                amount = transaction.amount,
                senderName = transaction.senderName,
                senderPhone = transaction.senderPhone,
                timestamp = transaction.timestamp
            )

            Log.d(TAG, "Dispatching POST /api/received-transaction for txId=${transaction.transactionId}, amount=${transaction.amount}")
            val response = ApiClient.getApiService(context).syncTransaction(request)
            val statusCode = response.code()

            if (response.isSuccessful) {
                val body = response.body()
                val outcome = body?.outcome ?: "success"
                val reason = body?.reason
                Log.i(TAG, "Sync SUCCESS: txId=${transaction.transactionId}, httpStatus=$statusCode, outcome=$outcome, reason=$reason")
                transactionDao.updateSyncStatus(transaction.transactionId, SyncStatus.SYNCED)
                return@withContext SyncResult.Success
            }

            // Distinguish permanent client errors vs temporary server/rate-limiting errors
            if (statusCode in 400..499 && statusCode != 408 && statusCode != 429) {
                val errorMsg = "HTTP $statusCode (Client/Validation error): ${response.errorBody()?.string()?.take(200)}"
                Log.e(TAG, "Sync PERMANENT FAILURE: txId=${transaction.transactionId}, $errorMsg")
                transactionDao.updateSyncStatus(transaction.transactionId, SyncStatus.FAILED)
                return@withContext SyncResult.PermanentError(errorMsg)
            } else {
                val errorMsg = "HTTP $statusCode (Temporary server or rate-limit issue)"
                Log.w(TAG, "Sync TEMPORARY FAILURE: txId=${transaction.transactionId}, $errorMsg")
                // Keep marked as PENDING so it will be retried automatically
                transactionDao.updateSyncStatus(transaction.transactionId, SyncStatus.PENDING)
                return@withContext SyncResult.TransientError(errorMsg)
            }
        } catch (e: IOException) {
            val msg = "Network I/O error: ${e.message}"
            Log.w(TAG, "Sync NETWORK FAILURE: txId=${transaction.transactionId}, $msg (kept PENDING for retry)")
            transactionDao.updateSyncStatus(transaction.transactionId, SyncStatus.PENDING)
            return@withContext SyncResult.TransientError(msg)
        } catch (e: Exception) {
            val msg = "Unexpected error during sync: ${e.message}"
            Log.e(TAG, "Sync ERROR: txId=${transaction.transactionId}, $msg", e)
            transactionDao.updateSyncStatus(transaction.transactionId, SyncStatus.FAILED)
            return@withContext SyncResult.TransientError(msg)
        }
    }

    /**
     * Synchronizes all currently PENDING and FAILED transactions.
     * Also enqueues WorkManager sync to ensure durable delivery if network fails mid-way.
     */
    suspend fun syncPendingTransactions(): Int = withContext(Dispatchers.IO) {
        val pending = transactionDao.getTransactionsByStatus(SyncStatus.PENDING)
        val failed = transactionDao.getTransactionsByStatus(SyncStatus.FAILED)
        val toSync = (pending + failed).distinctBy { it.transactionId }

        Log.d(TAG, "Manually syncing ${toSync.size} pending/failed transactions...")
        var successCount = 0

        for (tx in toSync) {
            val result = syncTransaction(tx)
            if (result is SyncResult.Success) {
                successCount++
            }
        }

        // If any failed, also make sure WorkManager is enqueued to retry when connectivity improves
        if (successCount < toSync.size) {
            SyncWorker.enqueueSync(context, replaceExisting = false)
        }

        return@withContext successCount
    }

    suspend fun syncSingleTransaction(transaction: Transaction) {
        transactionDao.updateSyncStatus(transaction.transactionId, SyncStatus.PENDING)
        val result = syncTransaction(transaction)
        if (result is SyncResult.TransientError) {
            SyncWorker.enqueueSync(context, replaceExisting = false)
        }
    }

    suspend fun deleteTransaction(id: String) = withContext(Dispatchers.IO) {
        transactionDao.deleteTransaction(id)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        transactionDao.clearAll()
    }

    companion object {
        private const val TAG = "TransactionRepo"
    }
}
