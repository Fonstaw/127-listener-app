package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.SyncStatus
import com.example.data.Transaction
import com.example.data.TransactionRepository
import com.example.security.SettingsManager
import com.example.service.ScanResult
import com.example.service.SmsInboxHelper
import com.example.parser.SmsParser
import com.example.util.NetworkConnectivityObserver
import com.example.util.NetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TransactionFilter {
    ALL,
    SYNCED,
    PENDING,
    FAILED
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).transactionDao()
    private val repository = TransactionRepository(application, dao)
    private val networkObserver = NetworkConnectivityObserver(application)

    val networkStatus: StateFlow<NetworkStatus> = networkObserver.status

    private val _isSetupRequired = MutableStateFlow(computeIsSetupRequired())
    val isSetupRequired: StateFlow<Boolean> = _isSetupRequired.asStateFlow()

    fun computeIsSetupRequired(): Boolean {
        val app = getApplication<Application>()
        // Mandatory setup on first launch is only required until a custom passcode is set
        return !SettingsManager.hasCustomPasscode(app)
    }

    fun refreshSetupState() {
        _isSetupRequired.value = computeIsSetupRequired()
    }

    fun saveInitialPasscode(passcode: String, apiUrl: String? = null, apiToken: String? = null): Boolean {
        val app = getApplication<Application>()
        val cleanPasscode = passcode.trim()

        if (cleanPasscode.length != 4 || !cleanPasscode.all { it.isDigit() }) return false

        SettingsManager.saveDeletePasscode(app, cleanPasscode)

        // Optionally save API URL and Token if provided
        if (!apiUrl.isNullOrBlank() && (apiUrl.trim().startsWith("http://") || apiUrl.trim().startsWith("https://"))) {
            SettingsManager.saveApiUrl(app, com.example.api.ApiClient.normalizeBaseUrl(apiUrl))
        }
        if (!apiToken.isNullOrBlank() && apiToken.trim() != SettingsManager.DEFAULT_TOKEN) {
            SettingsManager.saveApiToken(app, apiToken.trim())
        }

        refreshSetupState()
        refreshNetworkStatus()
        return true
    }

    fun refreshNetworkStatus() {
        networkObserver.triggerServerCheck()
    }

    override fun onCleared() {
        super.onCleared()
        networkObserver.cleanup()
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow(TransactionFilter.ALL)
    val selectedFilter: StateFlow<TransactionFilter> = _selectedFilter.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isScanningInbox = MutableStateFlow(false)
    val isScanningInbox: StateFlow<Boolean> = _isScanningInbox.asStateFlow()

    val rawTransactions: StateFlow<List<Transaction>> = repository.allTransactions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val uiState: StateFlow<List<Transaction>> = combine(
        rawTransactions,
        _searchQuery,
        _selectedFilter
    ) { transactions, query, filter ->
        transactions.filter { tx ->
            val matchesFilter = when (filter) {
                TransactionFilter.ALL -> true
                TransactionFilter.SYNCED -> tx.syncStatus == SyncStatus.SYNCED
                TransactionFilter.PENDING -> tx.syncStatus == SyncStatus.PENDING
                TransactionFilter.FAILED -> tx.syncStatus == SyncStatus.FAILED
            }
            val matchesQuery = query.isBlank() ||
                tx.transactionId.contains(query, ignoreCase = true) ||
                tx.senderName.contains(query, ignoreCase = true) ||
                tx.senderPhone.contains(query, ignoreCase = true) ||
                tx.amount.toString().contains(query, ignoreCase = true) ||
                tx.timestamp.contains(query, ignoreCase = true)

            matchesFilter && matchesQuery
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onFilterSelected(filter: TransactionFilter) {
        _selectedFilter.value = filter
    }

    fun scanSmsInbox(forceFullScan: Boolean = false, onComplete: (ScanResult) -> Unit = {}) {
        viewModelScope.launch {
            _isScanningInbox.value = true
            try {
                val result = SmsInboxHelper.scanInboxForTelebirrTransactions(
                    context = getApplication(),
                    forceFullScan = forceFullScan
                )
                onComplete(result)
            } finally {
                _isScanningInbox.value = false
            }
        }
    }

    fun resetScanPosition() {
        SettingsManager.resetScanCheckpoint(getApplication())
    }

    fun syncPending() {
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                repository.syncPendingTransactions()
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun retrySingleTransaction(transaction: Transaction) {
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                repository.syncSingleTransaction(transaction)
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun deleteTransaction(id: String) {
        viewModelScope.launch {
            repository.deleteTransaction(id)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    fun addSampleTransaction() {
        viewModelScope.launch {
            val sampleId = "DHE" + (1000000..9999999).random().toString()
            val sampleSenders = listOf(
                "abezaheg gossaye" to "2519****7163",
                "kebede Lemma" to "2519****4421",
                "fatuma ahmed" to "2519****8890",
                "tewodros kassahun" to "2517****3312",
                "selamawit yohannes" to "2519****9012"
            )
            val sampleAmounts = listOf(1.00, 50.00, 250.00, 500.00, 1200.50, 3500.00)
            
            val (randomName, randomPhone) = sampleSenders.random()
            val randomAmount = sampleAmounts.random()
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            val dateStr = dateFormat.format(Date())
            
            val rawSms = "Dear Abezahegn \nYou have received ETB ${String.format(Locale.US, "%.2f", randomAmount)} from $randomName($randomPhone)  on $dateStr. Your transaction number is $sampleId. Your current E-Money Account balance is ETB 199.19.\nThank you for using telebirr\nEthio telecom"

            val parsed = SmsParser.parseTelebirrSms(rawSms)
            if (parsed != null) {
                val tx = Transaction(
                    transactionId = parsed.transactionId,
                    amount = parsed.amount,
                    senderName = parsed.senderName,
                    senderPhone = parsed.senderPhone,
                    timestamp = parsed.timestamp,
                    rawSms = rawSms,
                    syncStatus = SyncStatus.PENDING
                )
                repository.saveAndSyncTransaction(tx)
            }
        }
    }
}
