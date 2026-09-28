package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey val transactionId: String,
    val amount: Double,
    val senderName: String,
    val senderPhone: String,
    val timestamp: String,
    val rawSms: String,
    val syncStatus: SyncStatus = SyncStatus.PENDING
)

enum class SyncStatus {
    PENDING,
    SYNCED,
    FAILED
}
