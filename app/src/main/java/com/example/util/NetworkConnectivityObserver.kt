package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.security.SettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class ServerReachability {
    UNKNOWN,
    CHECKING,
    REACHABLE,
    UNREACHABLE,
    NOT_CONFIGURED
}

data class NetworkStatus(
    val isInternetAvailable: Boolean = false,
    val serverReachability: ServerReachability = ServerReachability.UNKNOWN,
    val latencyMs: Long? = null,
    val errorMessage: String? = null,
    val lastCheckedTimestamp: Long = 0L
)

class NetworkConnectivityObserver(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _status = MutableStateFlow(
        NetworkStatus(isInternetAvailable = checkCurrentInternet())
    )
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthCheckJob: Job? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _status.value = _status.value.copy(isInternetAvailable = true)
            triggerServerCheck()
        }

        override fun onLost(network: Network) {
            val isStillConnected = checkCurrentInternet()
            _status.value = _status.value.copy(
                isInternetAvailable = isStillConnected,
                serverReachability = if (!isStillConnected) ServerReachability.UNREACHABLE else _status.value.serverReachability,
                errorMessage = if (!isStillConnected) "No internet connection" else null
            )
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (hasInternet != _status.value.isInternetAvailable) {
                _status.value = _status.value.copy(isInternetAvailable = hasInternet)
                if (hasInternet) {
                    triggerServerCheck()
                }
            }
        }
    }

    init {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            android.util.Log.e("NetworkObserver", "Failed to register network callback", e)
        }
        triggerServerCheck()
    }

    fun checkCurrentInternet(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    fun triggerServerCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            val hasNet = checkCurrentInternet()
            if (!hasNet) {
                _status.value = NetworkStatus(
                    isInternetAvailable = false,
                    serverReachability = ServerReachability.UNREACHABLE,
                    errorMessage = "No internet connection",
                    lastCheckedTimestamp = System.currentTimeMillis()
                )
                return@launch
            }

            val rawUrl = SettingsManager.getApiUrl(context).trim()
            if (rawUrl.isEmpty() || rawUrl == SettingsManager.DEFAULT_URL || rawUrl.contains("yourserver.com") || rawUrl.contains("your-bot-server.com")) {
                _status.value = NetworkStatus(
                    isInternetAvailable = true,
                    serverReachability = ServerReachability.NOT_CONFIGURED,
                    errorMessage = "Default placeholder URL configured",
                    lastCheckedTimestamp = System.currentTimeMillis()
                )
                return@launch
            }

            val normalizedUrl = com.example.api.ApiClient.normalizeBaseUrl(rawUrl)
            val apiKey = SettingsManager.getApiToken(context).trim()
            _status.value = _status.value.copy(
                isInternetAvailable = true,
                serverReachability = ServerReachability.CHECKING
            )

            val startTime = System.currentTimeMillis()
            try {
                // Test GET request to the server endpoint
                val request = Request.Builder()
                    .url(normalizedUrl)
                    .addHeader("User-Agent", "TelebirrSync-HealthCheck/1.0")
                    .apply {
                        if (apiKey.isNotEmpty()) {
                            addHeader("X-API-Key", apiKey)
                        }
                    }
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - startTime
                    // Any HTTP response (including 401, 403, 404, 200) means the server is reachable and responsive
                    _status.value = NetworkStatus(
                        isInternetAvailable = true,
                        serverReachability = ServerReachability.REACHABLE,
                        latencyMs = elapsed,
                        errorMessage = null,
                        lastCheckedTimestamp = System.currentTimeMillis()
                    )
                }
            } catch (e: IOException) {
                val elapsed = System.currentTimeMillis() - startTime
                _status.value = NetworkStatus(
                    isInternetAvailable = true,
                    serverReachability = ServerReachability.UNREACHABLE,
                    latencyMs = elapsed,
                    errorMessage = e.message ?: "Failed to reach server",
                    lastCheckedTimestamp = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                _status.value = NetworkStatus(
                    isInternetAvailable = true,
                    serverReachability = ServerReachability.UNREACHABLE,
                    errorMessage = e.localizedMessage ?: "Invalid URL or connection failure",
                    lastCheckedTimestamp = System.currentTimeMillis()
                )
            }
        }
    }

    fun cleanup() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignored
        }
        scope.cancel()
    }
}
