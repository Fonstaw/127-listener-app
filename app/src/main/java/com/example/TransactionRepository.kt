package com.example.data

import android.content.Context
import android.util.Log
import com.example.api.ApiClient
import com.example.api.TransactionRequest
import kotlinx.coroutines.flow.Flow

class TransactionRepository(
    private val context: Context,
    private val transactionDao: TransactionDao
) {

    val allTransactions: Flow<List<Transaction>> = transactionDao.getAllTransactions()

    /**
     * Saves a transaction to Room DB if it doesn't already exist,
     * and attempts to sync it to the backend server.
     * If the transaction already exists (already processed/synced), it is safely ignored.
     */
    suspend fun saveAndSyncTransaction(transaction: Transaction): Boolean {
        // Validate required fields before persisting or sending
        if (transaction.transactionId.isBlank() || transaction.amount <= 0.0) {
            Log.w("TransactionRepo", "Invalid transaction skipped: ID='${transaction.transactionId}', Amount=${transaction.amount}")
            return false
        }

        // 1. Check if already recorded in Room DB
        val existing = transactionDao.getTransactionById(transaction.transactionId)
        if (existing != null) {
            Log.d("TransactionRepo", "Transaction ${transaction.transactionId} already exists with status: ${existing.syncStatus}. Skipping duplicate.")
            return false
        }

        // 2. Save to local Room database
        val rowId = transactionDao.insertTransaction(transaction)
        if (rowId == -1L) {
            Log.d("TransactionRepo", "Duplicate insert ignored by Room for: ${transaction.transactionId}")
            return false
        }

        Log.d("TransactionRepo", "Saved new transaction ${transaction.transactionId} to Room. Initiating sync to /api/received-transaction...")

        // 3. Try to sync to backend
        syncTransaction(transaction)
        return true
    }

    suspend fun syncPendingTransactions() {
        val pending = transactionDao.getTransactionsByStatus(SyncStatus.PENDING)
        val failed = transactionDao.getTransactionsByStatus(SyncStatus.FAILED)
        
        val toSync = pending + failed
        Log.d("TransactionRepo", "Syncing ${toSync.size} pending/failed transactions to /api/received-transaction...")
        for (transaction in toSync) {
            syncTransaction(transaction)
        }
    }

    suspend fun syncSingleTransaction(transaction: Transaction) {
        transactionDao.updateSyncStatus(transaction.transactionId, SyncStatus.PENDING)
        syncTransaction(transaction)
    }

    suspend fun deleteTransaction(id: String) {
        transactionDao.deleteTransaction(id)
    }

    suspend fun clearAll() {
        transactionDao.clearAll()
    }

    private suspend fun syncTransaction(transaction: Transaction) {
        if (transaction.transactionId.isBlank() || transaction.amount <= 0.0) {
            Log.w("TransactionRepo", "Cannot sync invalid transaction (ID: '${transaction.transactionId}', Amount: ${transaction.amount})")
            transactionDao.updateSyncStatus(transaction.transactionId, SyncStatus.FAILED)
            return
        }

        try {
            val request = TransactionRequest(
                transactionId = transaction.transactionId,
                amount = transaction.amount,
                senderName = transaction.senderName,
                senderPhone = transaction.senderPhone,
                timestamp = transaction.timestamp
            )
            Log.d("TransactionRepo", "Dispatching POST /api/received-transaction for TxID=${transaction.transactionId}, Amount=${transaction.amount}")
            val response = ApiClient.getApiService(context).syncTransaction(request)
            val statusCode = response.code()
            if (response.isSuccessful) {
                val body = response.body()
                val outcome = body?.outcome ?: "success"
                val reason = body?.reason
                Log.i("TransactionRepo", "Sync SUCCESS: endpoint=/api/received-transaction, txId=${transaction.transactionId}, httpStatus=$statusCode, outcome=$outcome, reason=$reason")
                transactionDao.updateSyncStatus(transaction.transactionId, SyncStatus.SYNCED)
            } else {
                Log.w("TransactionRepo", "Sync FAILED: endpoint=/api/received-transaction, txId=${transaction.transactionId}, httpStatus=$statusCode (kept pending for retry)")
                transactionDao.updateSyncStatus(transaction.transactionId, SyncStatus.FAILED)
            }
        } catch (e: Exception) {
            Log.e("TransactionRepo", "Sync ERROR: endpoint=/api/received-transaction, txId=${transaction.transactionId}, error=${e.message} (kept pending for retry)")
            transactionDao.updateSyncStatus(transaction.transactionId, SyncStatus.FAILED)
        }
    }
}
