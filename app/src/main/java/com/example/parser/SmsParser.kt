package com.example.parser

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SmsParser {

    data class ParsedData(
        val transactionId: String,
        val amount: Double,
        val senderName: String,
        val senderPhone: String,
        val timestamp: String,
        val recipientName: String = "",
        val balance: Double? = null,
        val transactionType: String = "RECEIVED"
    )

    /**
     * Checks if the sender address is Telebirr (official shortcode is 127).
     * Handles 127, +251127, 0127, etc.
     */
    fun isTelebirrSender(sender: String?): Boolean {
        if (sender.isNullOrBlank()) return false
        val clean = sender.trim().replace("+251", "").replace("-", "").replace(" ", "")
        return clean == "127" || clean.endsWith("127")
    }

    /**
     * Checks if the message is an outgoing transfer (money sent/transferred to others from my side).
     * e.g. "Dear Abezahegn You have transferred ETB 20.00 to abezaheg gossaye (2519****7163)..."
     */
    fun isOutgoingTransfer(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val b = body.lowercase()
        return b.contains("you have transferred") ||
                b.contains("you transferred") ||
                b.contains("have transferred") ||
                b.contains("transferred etb") ||
                b.contains("transferred birr") ||
                b.contains("transferred to") ||
                b.contains("you have sent") ||
                b.contains("you have paid") ||
                b.contains("you paid") ||
                b.contains("payment to") ||
                (b.contains("transferred") && b.contains("to ") && !b.contains("received"))
    }

    /**
     * Checks if the message is a cancellation, failure, or reversal notification.
     * e.g. "Your request for transaction number DA48JECMWE with amount ETB 20.00 is cancelled..."
     */
    fun isCancelledOrFailedTransaction(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val b = body.lowercase()
        return b.contains("is cancelled") ||
                b.contains("is canceled") ||
                b.contains("has been cancelled") ||
                b.contains("has been canceled") ||
                b.contains("was cancelled") ||
                b.contains("was canceled") ||
                b.contains("cancelled.") ||
                b.contains("canceled.") ||
                b.contains("cancelled") ||
                b.contains("canceled") ||
                b.contains("transaction failed") ||
                b.contains("request failed") ||
                b.contains("reversed") ||
                b.contains("declined")
    }

    /**
     * Checks if the message is an airtime recharge or package purchase (outflow).
     * e.g. "You have recharged ETB 10.00 airtime for 995518974 on 08/04/2026..."
     */
    fun isAirtimeOrPackagePurchase(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val b = body.lowercase()
        return b.contains("recharged") ||
                b.contains("recharge") ||
                b.contains("airtime") ||
                b.contains("bought airtime") ||
                b.contains("purchased airtime") ||
                b.contains("recharged etb") ||
                b.contains("recharged birr") ||
                b.contains("bought package") ||
                b.contains("purchased package")
    }

    /**
     * Checks if the message is a telecom gift package, bundle expiration, or promo.
     * e.g. "You have received Gift Two Birr 400MB Telegram Package to be expire after 2hour..."
     */
    fun isTelecomGiftOrPromo(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val b = body.lowercase()
        return b.contains("received gift") ||
                b.contains("gift") ||
                b.contains("package to be expire") ||
                b.contains("will be expired") ||
                b.contains("package will be expired") ||
                b.contains("expired on") ||
                b.contains("telegram package") ||
                b.contains("internet package") ||
                b.contains("voice package") ||
                b.contains("data package") ||
                b.contains("happy holiday") ||
                (b.contains("package") && (b.contains("mb") || b.contains("gb") || b.contains("expire")))
    }

    /**
     * Checks whether an SMS should be strictly ignored (not an incoming monetary payment).
     * Covers: cancellations, airtime recharges, gift packages, outgoing transfers, OTPs, ATM cashouts.
     */
    fun isIgnoredTelebirrMessage(body: String?): Boolean {
        if (body.isNullOrBlank()) return true
        val b = body.lowercase()

        // 1. Cancelled, failed, or reversed transactions
        if (isCancelledOrFailedTransaction(body)) return true

        // 2. Airtime recharges or airtime purchases
        if (isAirtimeOrPackagePurchase(body)) return true

        // 3. Telecom gift packages, bundles, or promotional messages
        if (isTelecomGiftOrPromo(body)) return true

        // 4. Outgoing transfers (money sent to other users or merchants)
        if (isOutgoingTransfer(body)) return true

        // 5. One-Time Passwords / Verification PINs
        if (b.contains("otp") || b.contains("verification code") || b.contains("security code") ||
            (b.contains("pin") && b.contains("code")) || b.contains("do not share")) {
            return true
        }

        // 6. Cash withdrawal / ATM cash out
        if (b.contains("withdrawn") || b.contains("withdrawal") || b.contains("cash out")) {
            return true
        }

        return false
    }

    /**
     * Checks whether an incoming SMS is a valid incoming Telebirr transaction receipt.
     * Strictly rejects cancellations, airtime recharges, gift packages, and outgoing transfers.
     */
    fun isLikelyTelebirrMessage(sender: String?, body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        if (isIgnoredTelebirrMessage(body)) return false

        val b = body.lowercase()

        // Incoming payments MUST indicate money received or credited
        val hasIncomingKeyword = b.contains("received etb") ||
                b.contains("received birr") ||
                (b.contains("you have received") && (b.contains("etb") || b.contains("birr"))) ||
                b.contains("credited with") ||
                (b.contains("credited") && (b.contains("etb") || b.contains("birr")))

        if (!hasIncomingKeyword) return false

        if (isTelebirrSender(sender)) {
            return true
        }

        return b.contains("telebirr") || b.contains("ethio telecom")
    }

    /**
     * Primary Regex matching the exact Ethiopian Telebirr 127 notification structure:
     * "Dear Abezahegn
     * You have received ETB 1.00 from abezaheg gossaye(2519****7163)  on 14/08/2026 19:48:07. Your transaction number is DHE2SEHW24. Your current E-Money Account balance is ETB 99.19.
     * Thank you for using telebirr
     * Ethio telecom"
     */
    private val PRIMARY_TELEBIRR_REGEX = Regex(
        """(?:Dear\s+([A-Za-z0-9\s]+?)\s+)?You have received ETB\s+([0-9,]+(?:\.[0-9]+)?)\s+from\s+([^(]+?)\s*\(([^)]+)\)\s+on\s+([0-9/:\s-]+?)\.\s+Your transaction number is\s+([A-Za-z0-9]+)\.(?:\s+Your current E-Money Account balance is ETB\s+([0-9,]+(?:\.[0-9]+)?))?""",
        RegexOption.IGNORE_CASE
    )

    // Fallback regex patterns
    private val AMOUNT_PATTERNS = listOf(
        Regex("""(?:received|credited with|credited)\s+(?:ETB|Birr)\s*([0-9,]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE),
        Regex("""(?:received|credited with|credited)\s*([0-9,]+(?:\.[0-9]+)?)\s*(?:ETB|Birr)""", RegexOption.IGNORE_CASE),
        Regex("""(?:ETB|Birr)\s*([0-9,]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE),
        Regex("""([0-9,]+(?:\.[0-9]+)?)\s*(?:ETB|Birr)""", RegexOption.IGNORE_CASE)
    )

    private val TX_ID_PATTERNS = listOf(
        Regex("""transaction\s+(?:number|id|no)\s+is\s+([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:transaction\s+(?:number|id|no)|txn\s*(?:id|no)|txnid|ref\s*(?:id|no))\s*[:=.]?\s*([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE),
        Regex("""\b(D[A-Z0-9]{8,12})\b"""),
        Regex("""\b([A-Z0-9]{10})\b""")
    )

    private val SENDER_WITH_PHONE_PATTERNS = listOf(
        Regex("""(?:from)\s+([^(]+?)\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE),
        Regex("""(?:from)\s+([^(]+?)\s+([0-9+*]{9,14})""", RegexOption.IGNORE_CASE)
    )

    private val TIMESTAMP_PATTERNS = listOf(
        Regex("""(?:on|at|date)\s+(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}\s+\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AaPp][Mm])?)""", RegexOption.IGNORE_CASE),
        Regex("""(\d{1,2}/\d{1,2}/\d{4}\s+\d{1,2}:\d{2}:\d{2})""")
    )

    private fun logD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] $msg")
        }
    }

    private fun logW(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] $msg")
        }
    }

    private fun logE(tag: String, msg: String, t: Throwable? = null) {
        try {
            Log.e(tag, msg, t)
        } catch (_: Throwable) {
            println("[$tag] $msg ${t?.message ?: ""}")
        }
    }

    fun parseTelebirrSms(message: String): ParsedData? {
        if (message.isBlank()) return null
        if (isIgnoredTelebirrMessage(message)) {
            logD("SmsParser", "Ignoring non-incoming or unwanted Telebirr SMS (cancelled, airtime, gift, outgoing, promo)")
            return null
        }

        try {
            val normalizedMessage = message.replace("\r\n", " ").replace("\n", " ").replace("\r", " ").trim()

            // 1. Try Primary Match against official Telebirr 127 template
            val primaryMatch = PRIMARY_TELEBIRR_REGEX.find(normalizedMessage)
            if (primaryMatch != null) {
                val recipient = primaryMatch.groups[1]?.value?.trim() ?: ""
                val amountStr = primaryMatch.groups[2]?.value?.replace(",", "")?.trim() ?: "0.0"
                val senderName = primaryMatch.groups[3]?.value?.trim() ?: "Telebirr User"
                val senderPhone = primaryMatch.groups[4]?.value?.trim() ?: ""
                val timestamp = primaryMatch.groups[5]?.value?.trim() ?: ""
                val txId = primaryMatch.groups[6]?.value?.trim() ?: ""
                val balanceStr = primaryMatch.groups[7]?.value?.replace(",", "")?.trim()

                val amount = amountStr.toDoubleOrNull() ?: 0.0
                val balance = balanceStr?.toDoubleOrNull()

                if (txId.isBlank() || amount <= 0.0) {
                    logW("SmsParser", "Primary regex matched but invalid TxID or Amount: TxID='$txId', Amount=$amount")
                    return null
                }

                logD("SmsParser", "Successfully parsed 127 SMS with primary regex: TxID=$txId, Amount=$amount, Sender=$senderName ($senderPhone), Time=$timestamp")

                return ParsedData(
                    transactionId = txId,
                    amount = amount,
                    senderName = senderName,
                    senderPhone = senderPhone,
                    timestamp = timestamp,
                    recipientName = recipient,
                    balance = balance,
                    transactionType = "RECEIVED"
                )
            }

            // 2. Resilient Fallback Extraction (Only for messages that actually indicate incoming funds)
            val b = normalizedMessage.lowercase()
            val hasIncomingKeyword = b.contains("received") || b.contains("credited")
            if (!hasIncomingKeyword) {
                logD("SmsParser", "Fallback rejected: Message does not indicate incoming funds (no 'received' or 'credited')")
                return null
            }

            var parsedAmount: Double? = null
            for (pattern in AMOUNT_PATTERNS) {
                val match = pattern.find(normalizedMessage)
                if (match != null && match.groupValues.size > 1) {
                    val rawStr = match.groupValues[1].replace(",", "").trim()
                    val d = rawStr.toDoubleOrNull()
                    if (d != null && d > 0) {
                        parsedAmount = d
                        break
                    }
                }
            }

            if (parsedAmount == null || parsedAmount <= 0.0) {
                logW("SmsParser", "Could not extract valid positive amount from: $normalizedMessage")
                return null
            }

            var parsedTxId: String? = null
            for (pattern in TX_ID_PATTERNS) {
                val match = pattern.find(normalizedMessage)
                if (match != null) {
                    val candidate = if (match.groupValues.size > 1) match.groupValues[1].trim() else match.value.trim()
                    val upper = candidate.uppercase()
                    if (upper != "TELEBIRR" && upper != "ETB" && upper != "BIRR" && upper != "CUSTOMER" && upper != "ACCOUNT" && candidate.length >= 6) {
                        parsedTxId = candidate
                        break
                    }
                }
            }

            if (parsedTxId.isNullOrBlank()) {
                logW("SmsParser", "Could not extract valid real Telebirr transaction ID. Rejecting message.")
                return null
            }

            val finalTxId = parsedTxId

            var parsedSenderName = "Telebirr User"
            var parsedSenderPhone = ""

            for (pattern in SENDER_WITH_PHONE_PATTERNS) {
                val match = pattern.find(normalizedMessage)
                if (match != null && match.groupValues.size >= 3) {
                    parsedSenderName = match.groupValues[1].trim()
                    parsedSenderPhone = match.groupValues[2].trim()
                    break
                }
            }

            var parsedTimestamp: String? = null
            for (pattern in TIMESTAMP_PATTERNS) {
                val match = pattern.find(normalizedMessage)
                if (match != null && match.groupValues.size >= 2) {
                    parsedTimestamp = match.groupValues[1].trim()
                    break
                }
            }

            val finalTimestamp = parsedTimestamp ?: run {
                val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                sdf.format(Date())
            }

            return ParsedData(
                transactionId = finalTxId,
                amount = parsedAmount,
                senderName = parsedSenderName,
                senderPhone = parsedSenderPhone,
                timestamp = finalTimestamp,
                transactionType = "RECEIVED"
            )
        } catch (e: Exception) {
            logE("SmsParser", "Unexpected parsing error", e)
            return null
        }
    }
}
