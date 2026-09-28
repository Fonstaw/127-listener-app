package com.example.util

import com.example.data.Transaction
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DailyVolume(
    val dateLabel: String,         // e.g. "Today", "Yesterday", "Thu (Aug 13)"
    val shortDayName: String,      // e.g. "Today", "Thu", "Wed"
    val dateKey: String,          // e.g. "2026-08-14"
    val formattedDate: String,    // e.g. "Aug 14, 2026"
    val totalVolume: Double,
    val transactionCount: Int,
    val isToday: Boolean
)

object VolumeAnalyticsHelper {

    private val DATE_PARSERS = listOf(
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US),
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
        SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US),
        SimpleDateFormat("dd/MM/yyyy", Locale.US),
        SimpleDateFormat("yyyy-MM-dd", Locale.US),
        SimpleDateFormat("dd-MM-yyyy", Locale.US)
    )

    private val KEY_FORMATTER = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val DAY_NAME_FORMATTER = SimpleDateFormat("EEE", Locale.US)
    private val DISPLAY_DATE_FORMATTER = SimpleDateFormat("MMM d, yyyy", Locale.US)

    /**
     * Parses the transaction timestamp string into a Date object.
     */
    fun parseTransactionDate(timestamp: String): Date? {
        val clean = timestamp.trim()
        for (parser in DATE_PARSERS) {
            try {
                val date = parser.parse(clean)
                if (date != null) return date
            } catch (_: Exception) { }
        }
        return null
    }

    /**
     * Calculates the volume of transactions from the last 24 hours (or today).
     */
    fun calculateLast24HoursVolume(transactions: List<Transaction>): Double {
        val nowMillis = System.currentTimeMillis()
        val twentyFourHoursAgo = nowMillis - (24 * 60 * 60 * 1000L)
        
        return transactions.filter { tx ->
            val date = parseTransactionDate(tx.timestamp)
            if (date != null) {
                date.time in twentyFourHoursAgo..nowMillis
            } else {
                // If timestamp cannot be parsed, treat it as today if not older
                false
            }
        }.sumOf { it.amount }
    }

    /**
     * Calculates the 24-hour transaction count.
     */
    fun calculateLast24HoursCount(transactions: List<Transaction>): Int {
        val nowMillis = System.currentTimeMillis()
        val twentyFourHoursAgo = nowMillis - (24 * 60 * 60 * 1000L)

        return transactions.count { tx ->
            val date = parseTransactionDate(tx.timestamp)
            if (date != null) {
                date.time in twentyFourHoursAgo..nowMillis
            } else {
                false
            }
        }
    }

    /**
     * Computes the daily volume breakdown for the last 7 calendar days (Today, Yesterday, ..., 6 days ago).
     */
    fun calculateLast7DaysHistory(transactions: List<Transaction>): List<DailyVolume> {
        val calendar = Calendar.getInstance()
        val daysList = mutableListOf<DailyVolume>()

        // Group transactions by "yyyy-MM-dd"
        val txByDay = mutableMapOf<String, MutableList<Transaction>>()
        for (tx in transactions) {
            val date = parseTransactionDate(tx.timestamp)
            if (date != null) {
                val key = KEY_FORMATTER.format(date)
                txByDay.getOrPut(key) { mutableListOf() }.add(tx)
            }
        }

        // Build list for the last 7 days starting from today down to 6 days ago
        for (offset in 0 until 7) {
            val dayCal = (calendar.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, -offset)
            }
            val date = dayCal.time
            val key = KEY_FORMATTER.format(date)
            val dayTxList = txByDay[key] ?: emptyList()

            val totalVolume = dayTxList.sumOf { it.amount }
            val count = dayTxList.size
            val isToday = offset == 0
            val isYesterday = offset == 1

            val dateLabel = when {
                isToday -> "Today"
                isYesterday -> "Yesterday"
                else -> "${DAY_NAME_FORMATTER.format(date)} (${SimpleDateFormat("MMM d", Locale.US).format(date)})"
            }

            val shortDay = when {
                isToday -> "Today"
                isYesterday -> "Yest"
                else -> DAY_NAME_FORMATTER.format(date)
            }

            daysList.add(
                DailyVolume(
                    dateLabel = dateLabel,
                    shortDayName = shortDay,
                    dateKey = key,
                    formattedDate = DISPLAY_DATE_FORMATTER.format(date),
                    totalVolume = totalVolume,
                    transactionCount = count,
                    isToday = isToday
                )
            )
        }

        return daysList
    }
}
