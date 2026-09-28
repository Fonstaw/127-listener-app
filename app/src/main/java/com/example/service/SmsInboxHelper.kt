package com.example.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.AppDatabase
import com.example.data.SyncStatus
import com.example.data.Transaction
import com.example.data.TransactionRepository
import com.example.parser.SmsParser
import com.example.security.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScanResult(
    val totalScanned: Int,
    val parsedCount: Int,
    val newCount: Int,
    val alreadySyncedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val isIncremental: Boolean = true,
    val scanDurationMs: Long = 0L
)

object SmsInboxHelper {

    private const val TAG = "SmsInboxHelper"

    /**
     * Scans the device's SMS inbox for Telebirr transactions (Sender: 127).
     * 
     * - Uses incremental scanning based on stored (lastScannedDate, lastScannedSmsId) checkpoint.
     * - Queries only messages newer than the last scan position using an optimized ContentResolver query.
     * - Handles identical timestamp collisions gracefully using row ID (_ID).
     * - Processes in ascending order and persists checkpoint progress to be crash-resilient.
     * - Deduplicates against existing Room records without re-syncing.
     * - Does NOT log sensitive SMS message contents.
     */
    suspend fun scanInboxForTelebirrTransactions(
        context: Context,
        forceFullScan: Boolean = false
    ): ScanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_SMS permission not granted, skipping inbox scan")
            return@withContext ScanResult(
                totalScanned = 0,
                parsedCount = 0,
                newCount = 0,
                alreadySyncedCount = 0,
                skippedCount = 0,
                failedCount = 0,
                isIncremental = !forceFullScan,
                scanDurationMs = 0L
            )
        }

        val dao = AppDatabase.getDatabase(context).transactionDao()
        val repository = TransactionRepository(context, dao)

        // 1. In-memory cache of all existing transaction IDs for O(1) duplicate checks
        val existingIds = dao.getAllTransactionIds().toMutableSet()

        // 2. Read persistent scan checkpoint & determine scan window
        val isQuickScan = !forceFullScan
        val (checkpointDate, checkpointId) = if (forceFullScan) {
            Pair(0L, 0L)
        } else {
            SettingsManager.getScanCheckpoint(context)
        }

        // For Quick Mode:
        // - If a previous checkpoint exists, scan messages from that checkpoint.
        // - If no previous checkpoint exists, scan recent SMS (last 7 days) by default instead of the entire inbox history.
        val defaultQuickScanStartDate = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000L)
        val effectiveStartDate = when {
            forceFullScan -> 0L
            checkpointDate > 0L -> checkpointDate
            else -> defaultQuickScanStartDate
        }
        val isIncremental = isQuickScan

        var totalScanned = 0
        var parsedCount = 0
        var newImportedCount = 0
        var skippedCount = 0
        var failedCount = 0

        var maxProcessedDate = checkpointDate
        var maxProcessedId = checkpointId
        var uncommittedCheckpointChanges = 0

        val uri: Uri = Telephony.Sms.Inbox.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms.Inbox._ID,
            Telephony.Sms.Inbox.DATE,
            Telephony.Sms.Inbox.ADDRESS,
            Telephony.Sms.Inbox.BODY
        )

        // Optimized query: filter at ContentResolver level when running Quick scan
        val selection: String?
        val selectionArgs: Array<String>?
        if (isQuickScan && effectiveStartDate > 0L) {
            selection = "${Telephony.Sms.Inbox.DATE} >= ?"
            selectionArgs = arrayOf(effectiveStartDate.toString())
        } else {
            selection = null
            selectionArgs = null
        }

        // Ascending order allows safe incremental progress commits
        val sortOrder = "${Telephony.Sms.Inbox.DATE} ASC, ${Telephony.Sms.Inbox._ID} ASC"

        try {
            val cursor = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(Telephony.Sms.Inbox._ID)
                val dateIdx = c.getColumnIndex(Telephony.Sms.Inbox.DATE)
                val addressIdx = c.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.Inbox.BODY)

                while (c.moveToNext()) {
                    val id = if (idIdx != -1) c.getLong(idIdx) else 0L
                    val date = if (dateIdx != -1) c.getLong(dateIdx) else 0L
                    val address = if (addressIdx != -1) c.getString(addressIdx) else null
                    val body = if (bodyIdx != -1) c.getString(bodyIdx) else null

                    totalScanned++

                    // If Quick scan, handle checkpoint/date boundaries
                    if (isQuickScan) {
                        if (checkpointDate > 0L) {
                            if (date < checkpointDate || (date == checkpointDate && id <= checkpointId)) {
                                skippedCount++
                                continue
                            }
                        } else {
                            if (date < effectiveStartDate) {
                                skippedCount++
                                continue
                            }
                        }
                    }

                    // Update high watermark position
                    if (date > maxProcessedDate || (date == maxProcessedDate && id > maxProcessedId)) {
                        maxProcessedDate = date
                        maxProcessedId = id
                    }

                    // Fast check for Telebirr sender or keyword heuristics
                    if (body.isNullOrBlank() || !SmsParser.isLikelyTelebirrMessage(address, body)) {
                        skippedCount++
                        uncommittedCheckpointChanges++
                        if (uncommittedCheckpointChanges >= 50) {
                            SettingsManager.saveScanCheckpoint(context, maxProcessedDate, maxProcessedId)
                            uncommittedCheckpointChanges = 0
                        }
                        continue
                    }

                    val parsed = SmsParser.parseTelebirrSms(body)
                    if (parsed != null) {
                        parsedCount++
                        // Deduplication guard: Check against known IDs in Room database
                        if (existingIds.contains(parsed.transactionId)) {
                            skippedCount++
                        } else {
                            val transaction = Transaction(
                                transactionId = parsed.transactionId,
                                amount = parsed.amount,
                                senderName = parsed.senderName,
                                senderPhone = parsed.senderPhone,
                                timestamp = parsed.timestamp,
                                rawSms = body,
                                syncStatus = SyncStatus.PENDING
                            )

                            // Save and sync new transaction
                            val saved = repository.saveAndSyncTransaction(transaction)
                            if (saved) {
                                existingIds.add(parsed.transactionId)
                                newImportedCount++
                            } else {
                                skippedCount++
                            }
                        }
                    } else {
                        // Failed to parse despite matching preliminary heuristics
                        failedCount++
                        Log.w(TAG, "Failed to parse candidate Telebirr SMS at rowId=$id (sender=$address, length=${body.length})")
                    }

                    uncommittedCheckpointChanges++
                    // Persist checkpoint progress periodically to prevent re-processing on crash
                    if (uncommittedCheckpointChanges >= 25) {
                        SettingsManager.saveScanCheckpoint(context, maxProcessedDate, maxProcessedId)
                        uncommittedCheckpointChanges = 0
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying SMS inbox", e)
        }

        // Commit final checkpoint and scan timestamp
        if (maxProcessedDate > 0L || maxProcessedId > 0L) {
            SettingsManager.saveScanCheckpoint(context, maxProcessedDate, maxProcessedId)
        }
        val durationMs = System.currentTimeMillis() - startTime
        SettingsManager.saveLastScanTimestamp(context, System.currentTimeMillis())

        Log.i(
            TAG,
            "SMS scan completed in ${durationMs}ms: scanned=$totalScanned, parsed=$parsedCount, imported=$newImportedCount, skipped=$skippedCount, failed=$failedCount (incremental=$isIncremental)"
        )

        return@withContext ScanResult(
            totalScanned = totalScanned,
            parsedCount = parsedCount,
            newCount = newImportedCount,
            alreadySyncedCount = skippedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            isIncremental = isIncremental,
            scanDurationMs = durationMs
        )
    }
}
