package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.SyncStatus
import com.example.data.Transaction
import com.example.data.TransactionRepository
import com.example.parser.SmsParser
import com.example.worker.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Event-driven BroadcastReceiver for SMS_RECEIVED intents.
 * 
 * Flow:
 * SMS_RECEIVED
 * -> Quick parse & validate
 * -> Safely persist to Room (PENDING) using goAsync()
 * -> Enqueue durable WorkManager sync
 * -> pendingResult.finish()
 * 
 * Never makes direct network calls.
 * Never keeps an always-on service alive.
 * Dormant when no SMS arrives.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            action != "android.provider.Telephony.SMS_DELIVER") {
            return
        }

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val sender = messages.firstOrNull()?.displayOriginatingAddress ?: ""
            val fullBodyBuilder = StringBuilder()
            for (msg in messages) {
                fullBodyBuilder.append(msg.displayMessageBody ?: "")
            }
            val body = fullBodyBuilder.toString()

            Log.d(TAG, "Received incoming SMS broadcast from: [$sender]")

            if (!SmsParser.isLikelyTelebirrMessage(sender, body)) {
                // Not a Telebirr transaction receipt; ignore without waking background threads
                return
            }

            val parsedData = SmsParser.parseTelebirrSms(body)
            if (parsedData == null) {
                Log.w(TAG, "Received SMS from $sender but failed to parse transaction fields.")
                return
            }

            val transaction = Transaction(
                transactionId = parsedData.transactionId,
                amount = parsedData.amount,
                senderName = parsedData.senderName,
                senderPhone = parsedData.senderPhone,
                timestamp = parsedData.timestamp,
                rawSms = body,
                syncStatus = SyncStatus.PENDING
            )

            // Use goAsync() for the brief database write and WorkManager enqueue
            val pendingResult = goAsync()
            val appContext = context.applicationContext

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dao = AppDatabase.getDatabase(appContext).transactionDao()
                    val repo = TransactionRepository(appContext, dao)
                    val isNew = repo.insertPendingTransaction(transaction)

                    if (isNew) {
                        Log.i(TAG, "Safely saved transaction ${transaction.transactionId}. Enqueuing durable sync...")
                        SyncWorker.enqueueSync(appContext, replaceExisting = true)
                    } else {
                        Log.d(TAG, "Transaction ${transaction.transactionId} was already persisted. Skipping sync enqueue.")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error storing transaction from SMS_RECEIVED", t)
                } finally {
                    try {
                        pendingResult.finish()
                    } catch (finishErr: Throwable) {
                        Log.e(TAG, "Error finishing pendingResult", finishErr)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onReceive handling SMS", e)
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
