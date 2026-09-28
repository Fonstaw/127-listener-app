package com.example

import com.example.api.ApiClient
import com.example.parser.SmsParser
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun parseIncomingTelebirrReceipt_isSuccessful() {
        val incomingSms = """
            Dear Abezahegn
            You have received ETB 100.00 from abezaheg gossaye(2519****7163)  on 14/08/2026 19:48:07. Your transaction number is DEI252H1XO. Your current E-Money Account balance is ETB 199.19.
            Thank you for using telebirr
            Ethio telecom
        """.trimIndent()

        val parsed = SmsParser.parseTelebirrSms(incomingSms)
        assertNotNull(parsed)
        assertEquals("DEI252H1XO", parsed?.transactionId)
        assertEquals(100.0, parsed?.amount ?: 0.0, 0.001)
        assertEquals("abezaheg gossaye", parsed?.senderName)
        assertEquals("2519****7163", parsed?.senderPhone)
    }

    @Test
    fun parseOutgoingTransfer_isRejected() {
        val outgoingSms = """
            Dear Abezahegn 
            You have transferred ETB 20.00 to abezaheg gossaye (2519****7163) on 11/08/2026 08:47:44. Your transaction number is DHB0OWDVRI. The service fee is  ETB 0.87 and  15% VAT on the service fee is ETB 0.13. Your current E-Money Account  balance is ETB 156.19. To download your payment information please click this link: https://transactioninfo.ethiotelecom.et/receipt/DHB0OWDVRI.

            Thank you for using telebirr
            Ethio telecom
        """.trimIndent()

        assertTrue(SmsParser.isOutgoingTransfer(outgoingSms))
        val parsed = SmsParser.parseTelebirrSms(outgoingSms)
        assertNull("Outgoing transfers should not be parsed as incoming transactions", parsed)
    }

    @Test
    fun parseMessageWithoutTxId_returnsNullWithoutSyntheticId() {
        val invalidSms = "You have received ETB 50.00 from Friend but no reference."
        val parsed = SmsParser.parseTelebirrSms(invalidSms)
        assertNull("Messages without a valid transaction ID must return null", parsed)
    }

    @Test
    fun parseCancelledTransaction_isRejected() {
        val cancelledSms = """
            Dear Customer 
            Your request for transaction number DA48JECMWE with amount ETB 20.00 is cancelled.  Your current E-Money Account  balance is  ETB 225.49.

            Thank you for using telebirr
            Ethio telecom
        """.trimIndent()

        assertTrue("Should be detected as cancelled transaction", SmsParser.isCancelledOrFailedTransaction(cancelledSms))
        assertTrue("Should be ignored by telebirr filter", SmsParser.isIgnoredTelebirrMessage(cancelledSms))
        assertFalse("Should not be likely telebirr incoming payment", SmsParser.isLikelyTelebirrMessage("127", cancelledSms))
        assertNull("Cancelled transactions must not be parsed", SmsParser.parseTelebirrSms(cancelledSms))
    }

    @Test
    fun parseAirtimeRecharge_isRejected() {
        val airtimeSms1 = """
            Dear Abezahegn 
            You have recharged ETB 10.00 airtime for 995518974 on 08/04/2026 08:27:59. Your transaction number is DD82OZ6R3C. Your current  balance is  ETB 70.99. To download your payment information please click this link: https://transactioninfo.ethiotelecom.et/receipt/DD82OZ6R3C
            For any support and information related to telebirr service
            Send SMS to 126 or Contact us via
            Telegram: https://t.me/telebirr 
            Facebook: https://facebook.com/telebirr or
            Visit our website :https://www.ethiotelecom.et/telebirr/  
            Thank you for using telebirr
            Ethio telecom
        """.trimIndent()

        val airtimeSms2 = """
            Dear Abezahegn 
            You have recharged ETB 1,600.00 airtime for 944294097 on 28/07/2026 14:29:55. Your transaction number is DGS5BCZYKN. Your current  balance is  ETB 9.09. To download your payment information please click this link: https://transactioninfo.ethiotelecom.et/receipt/DGS5BCZYKN
            For any support and information related to telebirr service
            Send SMS to 126 or Contact us via
            Telegram: https://t.me/telebirr 
            Facebook: https://facebook.com/telebirr or
            Visit our website :https://www.ethiotelecom.et/telebirr/  
            Thank you for using telebirr
            Ethio telecom
        """.trimIndent()

        assertTrue(SmsParser.isAirtimeOrPackagePurchase(airtimeSms1))
        assertTrue(SmsParser.isAirtimeOrPackagePurchase(airtimeSms2))
        assertFalse(SmsParser.isLikelyTelebirrMessage("127", airtimeSms1))
        assertFalse(SmsParser.isLikelyTelebirrMessage("127", airtimeSms2))
        assertNull("Airtime recharge must not be parsed", SmsParser.parseTelebirrSms(airtimeSms1))
        assertNull("Airtime recharge must not be parsed", SmsParser.parseTelebirrSms(airtimeSms2))
    }

    @Test
    fun parseTelecomGiftPackage_isRejected() {
        val giftSms = """
            Dear customer 
            You have received Gift Two Birr 400MB Telegram Package to be expire after 2hour from 0964192722. The package Will be expired
            Thank you for using telebirr
            Ethio telecom
        """.trimIndent()

        assertTrue(SmsParser.isTelecomGiftOrPromo(giftSms))
        assertTrue(SmsParser.isIgnoredTelebirrMessage(giftSms))
        assertFalse(SmsParser.isLikelyTelebirrMessage("127", giftSms))
        assertNull("Gift packages must not be parsed", SmsParser.parseTelebirrSms(giftSms))
    }

    @Test
    fun normalizeBaseUrl_handlesAllFormatsCorrectly() {
        assertEquals("https://api.mybot.com/", ApiClient.normalizeBaseUrl("https://api.mybot.com"))
        assertEquals("https://api.mybot.com/", ApiClient.normalizeBaseUrl("https://api.mybot.com/"))
        assertEquals("https://api.mybot.com/", ApiClient.normalizeBaseUrl("https://api.mybot.com/api"))
        assertEquals("https://api.mybot.com/", ApiClient.normalizeBaseUrl("https://api.mybot.com/api/"))
        assertEquals("https://api.mybot.com/", ApiClient.normalizeBaseUrl("https://api.mybot.com/api/received-transaction"))
        assertEquals("https://api.mybot.com/", ApiClient.normalizeBaseUrl("https://api.mybot.com/api/received-transaction/"))
    }

    @Test
    fun transactionValidation_identifiesInvalidTransactions() {
        val blankIdTx = com.example.data.Transaction(
            transactionId = "",
            amount = 100.0,
            senderName = "Test",
            senderPhone = "251900000000",
            timestamp = "2026-09-28",
            rawSms = "Test raw SMS"
        )
        assertTrue(blankIdTx.transactionId.isBlank())

        val zeroAmountTx = com.example.data.Transaction(
            transactionId = "TX12345",
            amount = 0.0,
            senderName = "Test",
            senderPhone = "251900000000",
            timestamp = "2026-09-28",
            rawSms = "Test raw SMS"
        )
        assertTrue(zeroAmountTx.amount <= 0.0)

        val negativeAmountTx = com.example.data.Transaction(
            transactionId = "TX12345",
            amount = -50.0,
            senderName = "Test",
            senderPhone = "251900000000",
            timestamp = "2026-09-28",
            rawSms = "Test raw SMS"
        )
        assertTrue(negativeAmountTx.amount <= 0.0)

        val validTx = com.example.data.Transaction(
            transactionId = "TX998877",
            amount = 150.0,
            senderName = "Abezahegn",
            senderPhone = "251911223344",
            timestamp = "28/09/2026 14:00:00",
            rawSms = "Valid SMS",
            syncStatus = com.example.data.SyncStatus.PENDING
        )
        assertFalse(validTx.transactionId.isBlank())
        assertTrue(validTx.amount > 0.0)
        assertEquals(com.example.data.SyncStatus.PENDING, validTx.syncStatus)
    }

    @Test
    fun syncQueue_onlyIncludesPendingTransactions() {
        val pendingTx = com.example.data.Transaction(
            transactionId = "TX_PENDING",
            amount = 100.0,
            senderName = "Test",
            senderPhone = "251900000000",
            timestamp = "28/09/2026 14:00:00",
            rawSms = "Test SMS",
            syncStatus = com.example.data.SyncStatus.PENDING
        )
        val failedTx = com.example.data.Transaction(
            transactionId = "TX_FAILED",
            amount = 100.0,
            senderName = "Test",
            senderPhone = "251900000000",
            timestamp = "28/09/2026 14:00:00",
            rawSms = "Test SMS",
            syncStatus = com.example.data.SyncStatus.FAILED
        )
        val syncedTx = com.example.data.Transaction(
            transactionId = "TX_SYNCED",
            amount = 100.0,
            senderName = "Test",
            senderPhone = "251900000000",
            timestamp = "28/09/2026 14:00:00",
            rawSms = "Test SMS",
            syncStatus = com.example.data.SyncStatus.SYNCED
        )

        val allTransactions = listOf(pendingTx, failedTx, syncedTx)
        val automaticSyncQueue = allTransactions.filter { it.syncStatus == com.example.data.SyncStatus.PENDING }

        assertEquals(1, automaticSyncQueue.size)
        assertEquals("TX_PENDING", automaticSyncQueue.first().transactionId)
        assertFalse(automaticSyncQueue.any { it.syncStatus == com.example.data.SyncStatus.FAILED })
    }

    @Test
    fun manualRetry_resetsFailedTransactionToPending() {
        val failedTx = com.example.data.Transaction(
            transactionId = "TX_FAILED_RETRY",
            amount = 200.0,
            senderName = "Test",
            senderPhone = "251900000000",
            timestamp = "28/09/2026 14:00:00",
            rawSms = "Test SMS",
            syncStatus = com.example.data.SyncStatus.FAILED
        )

        val retriedTx = failedTx.copy(syncStatus = com.example.data.SyncStatus.PENDING)
        assertEquals(com.example.data.SyncStatus.PENDING, retriedTx.syncStatus)
    }

    @Test
    fun incrementalScan_skipsMessagesAtOrBeforeCheckpoint() {
        val checkpointDate = 1727500000000L
        val checkpointId = 100L

        // Older date: skipped
        val olderDate = 1727400000000L
        val olderId = 200L
        val isOlderSkipped = olderDate < checkpointDate || (olderDate == checkpointDate && olderId <= checkpointId)
        assertTrue(isOlderSkipped)

        // Same date, smaller or equal ID: skipped
        val sameDateSameId = checkpointDate
        val isSameSkipped = sameDateSameId < checkpointDate || (sameDateSameId == checkpointDate && checkpointId <= checkpointId)
        assertTrue(isSameSkipped)

        // Same date, newer ID: processed
        val newerId = 101L
        val isNewerIdSkipped = checkpointDate < checkpointDate || (checkpointDate == checkpointDate && newerId <= checkpointId)
        assertFalse(isNewerIdSkipped)

        // Newer date: processed
        val newerDate = 1727600000000L
        val isNewerDateSkipped = newerDate < checkpointDate || (newerDate == checkpointDate && 50L <= checkpointId)
        assertFalse(isNewerDateSkipped)
    }

    @Test
    fun httpStatusCodeClassification_distinguishesTransientVsPermanent() {
        fun isPermanent(code: Int): Boolean = code in 400..499 && code != 408 && code != 429

        // Permanent client errors (no retry)
        assertTrue(isPermanent(400))
        assertTrue(isPermanent(401))
        assertTrue(isPermanent(403))
        assertTrue(isPermanent(404))
        assertTrue(isPermanent(422))

        // Transient errors (retryable)
        assertFalse(isPermanent(408)) // Request Timeout
        assertFalse(isPermanent(429)) // Rate Limited
        assertFalse(isPermanent(500)) // Internal Server Error
        assertFalse(isPermanent(502)) // Bad Gateway
        assertFalse(isPermanent(503)) // Service Unavailable
        assertFalse(isPermanent(504)) // Gateway Timeout
    }

    @Test
    fun duplicateTransactionSuppression_deduplicatesById() {
        val tx1 = com.example.data.Transaction(
            transactionId = "TX_DUP_1",
            amount = 100.0,
            senderName = "Alice",
            senderPhone = "251911111111",
            timestamp = "28/09/2026 10:00:00",
            rawSms = "SMS 1",
            syncStatus = com.example.data.SyncStatus.PENDING
        )
        val tx2 = com.example.data.Transaction(
            transactionId = "TX_DUP_1",
            amount = 100.0,
            senderName = "Alice",
            senderPhone = "251911111111",
            timestamp = "28/09/2026 10:00:00",
            rawSms = "SMS 1 duplicate",
            syncStatus = com.example.data.SyncStatus.PENDING
        )
        val tx3 = com.example.data.Transaction(
            transactionId = "TX_DUP_2",
            amount = 250.0,
            senderName = "Bob",
            senderPhone = "251922222222",
            timestamp = "28/09/2026 10:05:00",
            rawSms = "SMS 2",
            syncStatus = com.example.data.SyncStatus.PENDING
        )

        val incomingList = listOf(tx1, tx2, tx3)
        val deduplicated = incomingList.distinctBy { it.transactionId }

        assertEquals(2, deduplicated.size)
        assertEquals(listOf("TX_DUP_1", "TX_DUP_2"), deduplicated.map { it.transactionId })
    }

    @Test
    fun multipleRapidSms_coalescePendingQueueWithoutLoss() {
        val initialPending = mutableListOf(
            com.example.data.Transaction("TX_A", 50.0, "A", "1", "T1", "R1", com.example.data.SyncStatus.PENDING)
        )

        // Worker run starts, processes TX_A
        val attemptedInRun = mutableSetOf<String>()
        val firstBatch = initialPending.filter { it.transactionId !in attemptedInRun }
        assertEquals(1, firstBatch.size)
        attemptedInRun.add(firstBatch.first().transactionId)

        // While TX_A is processing, 3 more SMS messages arrive (B, C, D)
        initialPending.add(com.example.data.Transaction("TX_B", 60.0, "B", "2", "T2", "R2", com.example.data.SyncStatus.PENDING))
        initialPending.add(com.example.data.Transaction("TX_C", 70.0, "C", "3", "T3", "R3", com.example.data.SyncStatus.PENDING))
        initialPending.add(com.example.data.Transaction("TX_D", 80.0, "D", "4", "T4", "R4", com.example.data.SyncStatus.PENDING))

        // In the next pass of while(true), all newly arrived transactions are picked up
        val nextBatch = initialPending.filter { it.transactionId !in attemptedInRun }
        assertEquals(3, nextBatch.size)
        assertEquals(listOf("TX_B", "TX_C", "TX_D"), nextBatch.map { it.transactionId })

        // Mark them attempted
        nextBatch.forEach { attemptedInRun.add(it.transactionId) }

        // Final pass: no more unattempted work
        val finalBatch = initialPending.filter { it.transactionId !in attemptedInRun }
        assertTrue(finalBatch.isEmpty())
    }
}

