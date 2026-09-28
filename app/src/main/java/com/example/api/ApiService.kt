package com.example.api

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

@JsonClass(generateAdapter = true)
data class TransactionRequest(
    val transactionId: String,
    val amount: Double,
    val senderName: String = "",
    val senderPhone: String = "",
    val timestamp: String = ""
)

@JsonClass(generateAdapter = true)
data class ReceivedTransactionResponse(
    val outcome: String? = null,
    val reason: String? = null
)

interface ApiService {
    @POST("api/received-transaction")
    suspend fun syncTransaction(@Body transaction: TransactionRequest): Response<ReceivedTransactionResponse>
}
