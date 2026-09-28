package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.SyncStatus
import com.example.data.Transaction
import com.example.security.BiometricHelper
import com.example.security.SettingsManager
import com.example.util.DailyVolume
import com.example.util.VolumeAnalyticsHelper
import com.example.ui.components.NetworkConnectionBanner
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.fragment.app.FragmentActivity
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    viewModel: MainViewModel,
    activity: FragmentActivity? = null,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allTransactions by viewModel.rawTransactions.collectAsStateWithLifecycle()
    val filteredTransactions by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isScanningInbox by viewModel.isScanningInbox.collectAsStateWithLifecycle()
    val networkStatus by viewModel.networkStatus.collectAsStateWithLifecycle()

    var selectedTransactionForDetails by remember { mutableStateOf<Transaction?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<Transaction?>(null) }
    var deletePasscodeInput by remember { mutableStateOf("") }
    var deletePasscodeError by remember { mutableStateOf<String?>(null) }
    var showHistoryDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Visual Status Indicator for Internet & API Server Reachability
        NetworkConnectionBanner(
            networkStatus = networkStatus,
            onRefresh = { viewModel.refreshNetworkStatus() },
            onOpenSettings = onOpenSettings
        )

        // Top 24-Hour Overview Card & Last 7 Days Quick Bar
        TransactionMetricsHeader(
            allTransactions = allTransactions,
            isSyncing = isSyncing,
            isScanning = isScanningInbox,
            onOpenHistory = { showHistoryDialog = true },
            onScanInbox = { forceFull ->
                viewModel.scanSmsInbox(forceFullScan = forceFull) { result ->
                    val modeLabel = if (result.isIncremental) "Quick Scan" else "Entire Inbox Scan"
                    val summary = buildString {
                        append("$modeLabel completed: ")
                        if (result.newCount > 0) {
                            append("${result.newCount} new imported, ")
                        } else {
                            append("0 new receipts, ")
                        }
                        append("${result.skippedCount} skipped")
                        if (result.failedCount > 0) {
                            append(", ${result.failedCount} failed")
                        }
                        append(" (${result.totalScanned} scanned in ${result.scanDurationMs}ms)")
                    }
                    Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
                }
            }
        )

        // Search & Filter Section
        SearchAndFilterSection(
            searchQuery = searchQuery,
            onSearchQueryChanged = { viewModel.onSearchQueryChanged(it) },
            selectedFilter = selectedFilter,
            onFilterSelected = { viewModel.onFilterSelected(it) },
            allTransactions = allTransactions
        )

        // Transactions List or Empty State
        if (filteredTransactions.isEmpty()) {
            EmptyTransactionsView(
                hasAnyTransactions = allTransactions.isNotEmpty(),
                searchQuery = searchQuery,
                selectedFilter = selectedFilter,
                isScanning = isScanningInbox,
                onResetFilters = {
                    viewModel.onSearchQueryChanged("")
                    viewModel.onFilterSelected(TransactionFilter.ALL)
                },
                onScanInbox = { forceFull ->
                    viewModel.scanSmsInbox(forceFullScan = forceFull) { result ->
                        val modeLabel = if (result.isIncremental) "Quick Scan" else "Entire Inbox Scan"
                        val summary = buildString {
                            append("$modeLabel completed: ")
                            if (result.newCount > 0) {
                                append("${result.newCount} new imported, ")
                            } else {
                                append("0 new receipts, ")
                            }
                            append("${result.skippedCount} skipped")
                            if (result.failedCount > 0) {
                                append(", ${result.failedCount} failed")
                            }
                            append(" (${result.totalScanned} scanned in ${result.scanDurationMs}ms)")
                        }
                        Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
                    }
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredTransactions, key = { it.transactionId }) { tx ->
                    TransactionItemCard(
                        transaction = tx,
                        onClick = { selectedTransactionForDetails = tx },
                        onRetrySync = { viewModel.retrySingleTransaction(tx) }
                    )
                }
            }
        }
    }

    // 7-Day History Dialog / Sheet
    if (showHistoryDialog) {
        SevenDayHistoryDialog(
            allTransactions = allTransactions,
            onDismiss = { showHistoryDialog = false }
        )
    }

    // Detail Bottom Sheet
    selectedTransactionForDetails?.let { tx ->
        TransactionDetailSheet(
            transaction = tx,
            onDismiss = { selectedTransactionForDetails = null },
            onRetrySync = {
                viewModel.retrySingleTransaction(tx)
                selectedTransactionForDetails = null
            },
            onDelete = {
                deletePasscodeInput = ""
                deletePasscodeError = null
                showDeleteConfirmDialog = tx
                selectedTransactionForDetails = null
            }
        )
    }

    // Delete Confirmation Dialog (Requires 4-Digit Passcode or Biometrics for ALL Messages: Synced, Pending, Failed)
    showDeleteConfirmDialog?.let { tx ->
        val statusLabel = when (tx.syncStatus) {
            SyncStatus.SYNCED -> "SYNCED"
            SyncStatus.PENDING -> "PENDING"
            SyncStatus.FAILED -> "FAILED"
        }

        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = null
                deletePasscodeInput = ""
                deletePasscodeError = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    "Passcode Required to Delete",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Transaction #${tx.transactionId} [$statusLabel] will be permanently deleted from the local database. Enter your 4-digit security PIN or scan your fingerprint to authorize deletion.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = deletePasscodeInput,
                        onValueChange = { input ->
                            if (input.length <= 4 && input.all { it.isDigit() }) {
                                deletePasscodeInput = input
                                deletePasscodeError = null
                            }
                        },
                        label = { Text("4-Digit Passcode") },
                        placeholder = { Text("••••") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        isError = deletePasscodeError != null,
                        supportingText = {
                            if (deletePasscodeError != null) {
                                Text(deletePasscodeError!!, color = MaterialTheme.colorScheme.error)
                            } else {
                                Text(if (SettingsManager.hasCustomPasscode(context)) "Enter your 4-digit security PIN" else "Default passcode: 1234 (changeable in Settings)")
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Pin, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    if (activity != null && BiometricHelper.canAuthenticate(context)) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                BiometricHelper.showBiometricPrompt(
                                    activity = activity,
                                    title = "Authorize Deletion",
                                    subtitle = "Scan fingerprint to delete transaction #${tx.transactionId}",
                                    negativeButtonText = "Use Passcode",
                                    onSuccess = {
                                        viewModel.deleteTransaction(tx.transactionId)
                                        showDeleteConfirmDialog = null
                                        deletePasscodeInput = ""
                                        deletePasscodeError = null
                                        Toast.makeText(context, "Transaction deleted", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { _ -> },
                                    onFailed = {
                                        Toast.makeText(context, "Fingerprint not recognized", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Delete with Fingerprint")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanInput = deletePasscodeInput.trim()
                        if (cleanInput.length != 4) {
                            deletePasscodeError = "Passcode must be exactly 4 digits"
                            return@Button
                        }
                        if (!SettingsManager.verifyDeletePasscode(context, cleanInput)) {
                            deletePasscodeError = "Incorrect passcode. Please try again."
                            return@Button
                        }
                        viewModel.deleteTransaction(tx.transactionId)
                        showDeleteConfirmDialog = null
                        deletePasscodeInput = ""
                        deletePasscodeError = null
                        Toast.makeText(context, "Transaction deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirmDialog = null
                    deletePasscodeInput = ""
                    deletePasscodeError = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun TransactionMetricsHeader(
    allTransactions: List<Transaction>,
    isSyncing: Boolean,
    isScanning: Boolean,
    onOpenHistory: () -> Unit,
    onScanInbox: (forceFullScan: Boolean) -> Unit
) {
    var showScanMenu by remember { mutableStateOf(false) }

    // 24-hour volume calculation
    val volume24h = remember(allTransactions) {
        VolumeAnalyticsHelper.calculateLast24HoursVolume(allTransactions)
    }
    val count24h = remember(allTransactions) {
        VolumeAnalyticsHelper.calculateLast24HoursCount(allTransactions)
    }
    val last7Days = remember(allTransactions) {
        VolumeAnalyticsHelper.calculateLast7DaysHistory(allTransactions)
    }
    
    val totalCount = allTransactions.size
    val syncedCount = allTransactions.count { it.syncStatus == SyncStatus.SYNCED }
    val pendingCount = allTransactions.count { it.syncStatus == SyncStatus.PENDING }
    val failedCount = allTransactions.count { it.syncStatus == SyncStatus.FAILED }

    val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top Row: Title, 24h tag and Scan SMS Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "24-Hour Volume",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "24h Reset",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = if (count24h == 1) "1 receipt received in last 24h" else "$count24h receipts received in last 24h",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Action Button: Quick Scan SMS by default (with Quick vs Entire Inbox menu)
                Box {
                    FilledTonalButton(
                        onClick = { onScanInbox(false) },
                        enabled = !isScanning,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(15.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isScanning) "Scanning..." else "Quick Scan", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(modifier = Modifier.width(2.dp))
                        IconButton(
                            onClick = { showScanMenu = true },
                            modifier = Modifier.size(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Scan Options",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showScanMenu,
                        onDismissRequest = { showScanMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Quick Scan (Default)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                    Text("Recent SMS & updates since last check", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                showScanMenu = false
                                onScanInbox(false)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Entire Inbox Scan", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                    Text("Scan all historical messages in SMS inbox", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
                            onClick = {
                                showScanMenu = false
                                onScanInbox(true)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main Volume Display (Last 24 Hours)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatter.format(volume24h),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = " ETB",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp, start = 4.dp)
                    )
                }

                // 7-Day History Button
                TextButton(
                    onClick = onOpenHistory,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "7-Day History",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(15.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Mini 7-Day Sparkline / Bar Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .clickable { onOpenHistory() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Reversed so oldest (6 days ago) is on the left and Today is on the right
                val reversed7Days = remember(last7Days) { last7Days.reversed() }
                val maxVol = remember(reversed7Days) {
                    (reversed7Days.maxOfOrNull { it.totalVolume } ?: 1.0).coerceAtLeast(10.0)
                }

                reversed7Days.forEach { day ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        // Bar indicator
                        val heightFraction = (day.totalVolume / maxVol).toFloat().coerceIn(0.12f, 1f)
                        Box(
                            modifier = Modifier
                                .width(14.dp)
                                .height(26.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(heightFraction)
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(
                                        if (day.isToday) MaterialTheme.colorScheme.primary
                                        else if (day.totalVolume > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = day.shortDayName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(10.dp))

            // Stat breakdown chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatBadge(
                    label = "Total SMS",
                    count = totalCount.toString(),
                    icon = Icons.Default.ReceiptLong,
                    color = MaterialTheme.colorScheme.primary
                )
                StatBadge(
                    label = "Synced",
                    count = syncedCount.toString(),
                    icon = Icons.Default.CheckCircle,
                    color = Color(0xFF059669)
                )
                StatBadge(
                    label = "Pending",
                    count = pendingCount.toString(),
                    icon = Icons.Default.Sync,
                    color = Color(0xFFD97706)
                )
                if (failedCount > 0) {
                    StatBadge(
                        label = "Failed",
                        count = failedCount.toString(),
                        icon = Icons.Default.Warning,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 7-Day History Dialog showing daily totals breakdown for each of the last seven days.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SevenDayHistoryDialog(
    allTransactions: List<Transaction>,
    onDismiss: () -> Unit
) {
    val dailyList = remember(allTransactions) {
        VolumeAnalyticsHelper.calculateLast7DaysHistory(allTransactions)
    }
    val total7DayVolume = remember(dailyList) {
        dailyList.sumOf { it.totalVolume }
    }
    val total7DayCount = remember(dailyList) {
        dailyList.sumOf { it.transactionCount }
    }

    val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "7-Day Volume History",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Daily revenue breakdown for the past week",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 7-Day Aggregate Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Past 7 Days Total",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${formatter.format(total7DayVolume)} ETB",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            text = "$total7DayCount transactions",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Daily Breakdown",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(10.dp))

            // List of 7 individual days
            dailyList.forEach { day ->
                DailyHistoryItem(day = day, formatter = formatter)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun DailyHistoryItem(
    day: DailyVolume,
    formatter: NumberFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (day.isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = day.dateLabel,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.SemiBold
                        ),
                        color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (day.isToday) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "Current 24h",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${day.formattedDate} • ${day.transactionCount} transaction${if (day.transactionCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${formatter.format(day.totalVolume)} ETB",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (day.totalVolume > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
fun StatBadge(
    label: String,
    count: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = count,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SearchAndFilterSection(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    selectedFilter: TransactionFilter,
    onFilterSelected: (TransactionFilter) -> Unit,
    allTransactions: List<Transaction>
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { 
                Text(
                    "Search by sender, ID, phone, or date...",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp)
                ) 
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChanged("") }) {
                        Icon(
                            Icons.Default.Clear, 
                            contentDescription = "Clear search",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Horizontal Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val totalCount = allTransactions.size
            val syncedCount = allTransactions.count { it.syncStatus == SyncStatus.SYNCED }
            val pendingCount = allTransactions.count { it.syncStatus == SyncStatus.PENDING }
            val failedCount = allTransactions.count { it.syncStatus == SyncStatus.FAILED }

            FilterChip(
                selected = selectedFilter == TransactionFilter.ALL,
                onClick = { onFilterSelected(TransactionFilter.ALL) },
                label = { Text("All ($totalCount)", fontWeight = if (selectedFilter == TransactionFilter.ALL) FontWeight.Bold else FontWeight.Normal) },
                leadingIcon = {
                    if (selectedFilter == TransactionFilter.ALL) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                    }
                },
                shape = RoundedCornerShape(10.dp)
            )

            FilterChip(
                selected = selectedFilter == TransactionFilter.SYNCED,
                onClick = { onFilterSelected(TransactionFilter.SYNCED) },
                label = { Text("Synced ($syncedCount)", fontWeight = if (selectedFilter == TransactionFilter.SYNCED) FontWeight.Bold else FontWeight.Normal) },
                leadingIcon = {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (selectedFilter == TransactionFilter.SYNCED) Color.Unspecified else Color(0xFF059669),
                        modifier = Modifier.size(15.dp)
                    )
                },
                shape = RoundedCornerShape(10.dp)
            )

            FilterChip(
                selected = selectedFilter == TransactionFilter.PENDING,
                onClick = { onFilterSelected(TransactionFilter.PENDING) },
                label = { Text("Pending ($pendingCount)") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        tint = if (selectedFilter == TransactionFilter.PENDING) Color.Unspecified else Color(0xFFF57C00),
                        modifier = Modifier.size(16.dp)
                    )
                },
                shape = RoundedCornerShape(10.dp)
            )

            FilterChip(
                selected = selectedFilter == TransactionFilter.FAILED,
                onClick = { onFilterSelected(TransactionFilter.FAILED) },
                label = { Text("Failed ($failedCount)") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (selectedFilter == TransactionFilter.FAILED) Color.Unspecified else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                },
                shape = RoundedCornerShape(10.dp)
            )
        }
    }
}

@Composable
fun TransactionItemCard(
    transaction: Transaction,
    onClick: () -> Unit,
    onRetrySync: () -> Unit
) {
    val context = LocalContext.current
    val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    val (statusColor, statusBgColor, statusIcon, statusLabel) = when (transaction.syncStatus) {
        SyncStatus.SYNCED -> StatusVisuals(
            color = Color(0xFF059669),
            bgColor = Color(0xFF059669).copy(alpha = 0.12f),
            icon = Icons.Default.CheckCircle,
            label = "SYNCED"
        )
        SyncStatus.PENDING -> StatusVisuals(
            color = Color(0xFFD97706),
            bgColor = Color(0xFFD97706).copy(alpha = 0.12f),
            icon = Icons.Default.Sync,
            label = "PENDING"
        )
        SyncStatus.FAILED -> StatusVisuals(
            color = MaterialTheme.colorScheme.error,
            bgColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            icon = Icons.Default.Warning,
            label = "FAILED"
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Row 1: Sender Avatar & Name + Amount
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Inflow avatar circle
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF059669).copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Inflow",
                            tint = Color(0xFF059669),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = transaction.senderName.ifBlank { "Telebirr Payment" },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = transaction.senderPhone.ifBlank { "Telebirr" },
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Amount
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "+${formatter.format(transaction.amount)}",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 17.5.sp,
                            color = Color(0xFF059669)
                        )
                    )
                    Text(
                        text = "ETB",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(8.dp))

            // Row 2: Date, Transaction ID & Sync Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Date & Copyable ID
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AccessTime,
                            contentDescription = "Date",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = transaction.timestamp,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Transaction ID", transaction.transactionId)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "ID copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ID: ${transaction.transactionId}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy ID",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                }

                // Sync Status Badge & Action
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(statusBgColor)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = statusLabel,
                                tint = statusColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.4.sp,
                                    fontSize = 10.5.sp
                                ),
                                color = statusColor
                            )
                        }
                    }

                    if (transaction.syncStatus != SyncStatus.SYNCED) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onRetrySync,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry Sync",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class StatusVisuals(
    val color: Color,
    val bgColor: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheet(
    transaction: Transaction,
    onDismiss: () -> Unit,
    onRetrySync: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Transaction Details",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Highlight Amount Box
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Received Amount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "+${formatter.format(transaction.amount)} ETB",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Metadata fields
            DetailRow(label = "Transaction ID", value = transaction.transactionId, canCopy = true)
            DetailRow(label = "Sender / Source", value = transaction.senderName.ifBlank { "Telebirr" })
            DetailRow(label = "Sender Phone", value = transaction.senderPhone.ifBlank { "N/A" }, canCopy = true)
            DetailRow(label = "Timestamp / Date", value = transaction.timestamp)
            DetailRow(
                label = "Sync Status",
                value = transaction.syncStatus.name,
                statusColor = when (transaction.syncStatus) {
                    SyncStatus.SYNCED -> Color(0xFF2E7D32)
                    SyncStatus.PENDING -> Color(0xFFF57C00)
                    SyncStatus.FAILED -> MaterialTheme.colorScheme.error
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Raw SMS Section
            Text(
                text = "Raw SMS Message",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = transaction.rawSms,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Raw SMS", transaction.rawSms)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "SMS copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy SMS")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete")
                }

                Button(
                    onClick = onRetrySync,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (transaction.syncStatus == SyncStatus.SYNCED) "Resync" else "Sync Now")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun DetailRow(
    label: String,
    value: String,
    statusColor: Color? = null,
    canCopy: Boolean = false
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor ?: MaterialTheme.colorScheme.onSurface
                )
            )
            if (canCopy) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(label, value)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyTransactionsView(
    hasAnyTransactions: Boolean,
    searchQuery: String,
    selectedFilter: TransactionFilter,
    isScanning: Boolean,
    onResetFilters: () -> Unit,
    onScanInbox: (forceFullScan: Boolean) -> Unit
) {
    var showScanMenu by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!hasAnyTransactions) {
                // High-quality custom 3D illustration asset
                Image(
                    painter = painterResource(id = R.drawable.img_empty_tx),
                    contentDescription = "Telebirr Sync",
                    modifier = Modifier
                        .size(175.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SearchOff,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = if (!hasAnyTransactions) "Ready for Telebirr Receipts" else "No Matching Transactions",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (!hasAnyTransactions) {
                    "Incoming Telebirr SMS receipts from shortcode 127 are automatically captured, saved locally in Room DB, and synced to your Telegram Bot backend."
                } else {
                    "No transactions match filter '${selectedFilter.name}' or search '$searchQuery'."
                },
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (hasAnyTransactions) {
                OutlinedButton(
                    onClick = onResetFilters,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FilterAltOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear Filter & Search")
                }
            } else {
                Box {
                    FilledTonalButton(
                        onClick = { onScanInbox(false) },
                        enabled = !isScanning,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isScanning) "Scanning Inbox..." else "Quick Scan SMS",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { showScanMenu = true },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Scan Options",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showScanMenu,
                        onDismissRequest = { showScanMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Quick Scan (Default)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                    Text("Recent SMS & updates since last check", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                showScanMenu = false
                                onScanInbox(false)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Entire Inbox Scan", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                    Text("Scan all historical messages in SMS inbox", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
                            onClick = {
                                showScanMenu = false
                                onScanInbox(true)
                            }
                        )
                    }
                }
            }
        }
    }
}
