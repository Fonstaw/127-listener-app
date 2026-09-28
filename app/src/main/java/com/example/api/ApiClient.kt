package com.example.api

import android.content.Context
import com.example.BuildConfig
import com.example.security.SettingsManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object ApiClient {
    private var cachedService: ApiService? = null
    private var cachedUrl: String? = null
    private var cachedToken: String? = null

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    /**
     * Normalizes the base URL entered by the user:
     * - Ensures protocol (https://)
     * - Strips redundant trailing endpoint paths (e.g. /api/, /api, /api/received-transaction)
     * - Guarantees exactly one trailing slash so Retrofit can append "api/received-transaction" correctly.
     */
    fun normalizeBaseUrl(rawUrl: String): String {
        var trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return SettingsManager.DEFAULT_URL
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://$trimmed"
        }

        // Strip endpoint path if the user pasted full endpoint
        if (trimmed.endsWith("/api/received-transaction")) {
            trimmed = trimmed.removeSuffix("/api/received-transaction")
        } else if (trimmed.endsWith("/api/received-transaction/")) {
            trimmed = trimmed.removeSuffix("/api/received-transaction/")
        }

        // Strip trailing /api/ or /api to prevent double paths like /api/api/received-transaction
        if (trimmed.endsWith("/api/")) {
            trimmed = trimmed.removeSuffix("api/")
        } else if (trimmed.endsWith("/api")) {
            trimmed = trimmed.removeSuffix("api")
        }

        if (!trimmed.endsWith("/")) {
            trimmed = "$trimmed/"
        }
        return trimmed
    }

    @Synchronized
    fun getApiService(context: Context): ApiService {
        val rawUrl = SettingsManager.getApiUrl(context)
        val normalizedUrl = normalizeBaseUrl(rawUrl)
        val apiKey = SettingsManager.getApiToken(context).trim()

        if (cachedService != null && cachedUrl == normalizedUrl && cachedToken == apiKey) {
            return cachedService!!
        }

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            // Redact sensitive headers so API keys are never leaked into logs
            redactHeader("X-API-Key")
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("X-API-Key", apiKey)
                    .build()
                chain.proceed(request)
            }
            .build()

        val service = try {
            Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(ApiService::class.java)
        } catch (t: Throwable) {
            Retrofit.Builder()
                .baseUrl(SettingsManager.DEFAULT_URL)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(ApiService::class.java)
        }

        cachedService = service
        cachedUrl = normalizedUrl
        cachedToken = apiKey
        return service
    }
}

