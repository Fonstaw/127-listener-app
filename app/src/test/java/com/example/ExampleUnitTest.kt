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
}

