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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)

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

            Log.d("SmsReceiver", "Incoming SMS broadcast from sender: [$sender]")

            if (SmsParser.isLikelyTelebirrMessage(sender, body)) {
                Log.d("SmsReceiver", "Valid Telebirr (127) SMS detected. Parsing content...")
                val parsedData = SmsParser.parseTelebirrSms(body)

                if (parsedData != null) {
                    val transaction = Transaction(
                        transactionId = parsedData.transactionId,
                        amount = parsedData.amount,
                        senderName = parsedData.senderName,
                        senderPhone = parsedData.senderPhone,
                        timestamp = parsedData.timestamp,
                        rawSms = body,
                        syncStatus = SyncStatus.PENDING
                    )

                    val dao = AppDatabase.getDatabase(context.applicationContext).transactionDao()
                    val repo = TransactionRepository(context.applicationContext, dao)

                    scope.launch {
                        val isNew = repo.saveAndSyncTransaction(transaction)
                        if (isNew) {
                            Log.d("SmsReceiver", "Successfully processed & queued new transaction ${transaction.transactionId}")
                        } else {
                            Log.d("SmsReceiver", "Ignored duplicate transaction ${transaction.transactionId}")
                        }
                    }
                } else {
                    Log.w("SmsReceiver", "Failed to extract required fields from 127 SMS (sender: $sender, length: ${body.length})")
                }
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error processing SMS broadcast", e)
        }
    }
}
