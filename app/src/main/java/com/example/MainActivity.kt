package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.security.BiometricHelper
import com.example.security.SettingsManager
import com.example.service.ForegroundService
import com.example.ui.MainViewModel
import com.example.ui.TransactionsScreen
import com.example.ui.theme.MyApplicationTheme
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var isBatteryOptimizing by mutableStateOf(true)

    // Callback for direct permission requests
    private var onPermissionResultAction: (() -> Unit)? = null

    fun requestPermissionsSafely(permissions: Array<String>, onComplete: () -> Unit) {
        onPermissionResultAction = onComplete
        try {
            ActivityCompat.requestPermissions(this, permissions, 101)
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "Error in requestPermissionsSafely", t)
        }
    }

    fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            if (pm?.isIgnoringBatteryOptimizations(packageName) == false) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (t: Throwable) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallbackIntent)
            } catch (ignored: Throwable) {
                android.util.Log.e("MainActivity", "Failed to launch battery optimization request", t)
            }
        }
    }

    private fun startBackgroundListenerService() {
        try {
            val serviceIntent = Intent(this, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "Handled background service startup: ${t.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onPermissionResultAction?.invoke()
    }

    override fun onResume() {
        super.onResume()
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            isBatteryOptimizing = pm?.isIgnoringBatteryOptimizations(packageName) != true
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "Failed to check battery optimization in onResume", t)
            isBatteryOptimizing = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val permissionsList = remember {
                    val list = mutableListOf(
                        Manifest.permission.RECEIVE_SMS,
                        Manifest.permission.READ_SMS
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        list.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    list
                }
                
                var allPermissionsGranted by remember {
                    mutableStateOf(
                        permissionsList.all { perm ->
                            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                        }
                    )
                }

                val requestPermissions = {
                    requestPermissionsSafely(permissionsList.toTypedArray()) {
                        allPermissionsGranted = permissionsList.all { perm ->
                            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                        }
                    }
                }

                val isSetupRequired by viewModel.isSetupRequired.collectAsState()
                var currentScreen by remember { mutableStateOf(AppScreen.Dashboard) }

                // Authentication Dialog state when clicking Settings
                var showAuthDialog by remember { mutableStateOf(false) }

                val openSettingsWithAuth = {
                    if (BiometricHelper.canAuthenticate(this@MainActivity)) {
                        BiometricHelper.showBiometricPrompt(
                            activity = this@MainActivity,
                            title = "Unlock Settings",
                            subtitle = "Touch fingerprint sensor or enter passcode",
                            negativeButtonText = "Use Passcode",
                            onSuccess = {
                                currentScreen = AppScreen.Settings
                            },
                            onError = { _ ->
                                showAuthDialog = true
                            },
                            onFailed = {
                                Toast.makeText(context, "Biometric not recognized. Try again or use PIN.", Toast.LENGTH_SHORT).show()
                            }
                        )
                    } else {
                        showAuthDialog = true
                    }
                }

                LaunchedEffect(allPermissionsGranted) {
                    if (allPermissionsGranted) {
                        android.util.Log.d("MainActivity", "All required SMS permissions granted. Starting background listener...")
                        startBackgroundListenerService()
                        if (isBatteryOptimizing) {
                            requestIgnoreBatteryOptimizations()
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    if (!allPermissionsGranted) {
                        try {
                            requestPermissions()
                        } catch (t: Throwable) {
                            android.util.Log.e("MainActivity", "Failed to launch permission prompt", t)
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(
                            title = { 
                                Text(
                                    when {
                                        isSetupRequired -> "Passcode Setup Required"
                                        currentScreen == AppScreen.Settings -> "API Connection Settings"
                                        else -> "Telebirr Transactions"
                                    }, 
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                ) 
                            },
                            navigationIcon = {
                                if (!isSetupRequired && currentScreen == AppScreen.Settings) {
                                    IconButton(onClick = { currentScreen = AppScreen.Dashboard }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack, 
                                            contentDescription = "Back to Dashboard"
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (!isSetupRequired && currentScreen == AppScreen.Dashboard) {
                                    IconButton(
                                        onClick = { openSettingsWithAuth() }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings, 
                                            contentDescription = "Open Settings (Protected)"
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                titleContentColor = MaterialTheme.colorScheme.onBackground
                            )
                        )
                    },
                    floatingActionButton = {
                        if (!isSetupRequired && currentScreen == AppScreen.Dashboard && allPermissionsGranted) {
                            ExtendedFloatingActionButton(
                                onClick = { viewModel.syncPending() },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = "Manual Sync")
                                Spacer(Modifier.width(8.dp))
                                Text("Sync Pending")
                            }
                        }
                    }
                ) { innerPadding ->
                    if (isSetupRequired) {
                        Box(
                            modifier = Modifier
                                .padding(innerPadding)
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            MandatorySetupScreenContent(
                                viewModel = viewModel,
                                onCompleted = {
                                    currentScreen = AppScreen.Dashboard
                                }
                            )
                        }
                    } else if (currentScreen == AppScreen.Settings) {
                        Box(
                            modifier = Modifier
                                .padding(innerPadding)
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            SettingsScreenContent(
                                activity = this@MainActivity,
                                onSaved = { viewModel.refreshNetworkStatus() },
                                onBack = { currentScreen = AppScreen.Dashboard }
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .padding(innerPadding)
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            if (!allPermissionsGranted) {
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = "Permissions",
                                            modifier = Modifier.size(64.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            "SMS Permission Required", 
                                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), 
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "Telebirr Sync needs SMS reading and Notification permissions to detect incoming transaction receipts and store them in the Room database.", 
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(24.dp))
                                        Button(
                                            onClick = { requestPermissions() },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Grant Permissions", modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp))
                                        }
                                    }
                                }
                            } else {
                                if (isBatteryOptimizing) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = "Warning",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    "Background Service Limited",
                                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                                Text(
                                                    "Disable battery optimizations for continuous 24/7 Telebirr processing.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                            }
                                            TextButton(
                                                onClick = {
                                                    try {
                                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                                        intent.data = Uri.parse("package:${context.packageName}")
                                                        context.startActivity(intent)
                                                    } catch (e: Exception) {
                                                        android.util.Log.e("MainActivity", "Launch battery settings failed", e)
                                                    }
                                                }
                                            ) {
                                                Text("Fix", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }

                                // Main Room Database Transactions Screen
                                TransactionsScreen(
                                    viewModel = viewModel,
                                    activity = this@MainActivity,
                                    onOpenSettings = { openSettingsWithAuth() },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Authentication Dialog to protect Settings Screen
                if (showAuthDialog) {
                    UnlockSettingsDialog(
                        activity = this@MainActivity,
                        onSuccess = {
                            showAuthDialog = false
                            currentScreen = AppScreen.Settings
                        },
                        onDismiss = {
                            showAuthDialog = false
                        }
                    )
                }
            }
        }
    }
}

enum class AppScreen { Dashboard, Settings }

/**
 * Authentication dialog required to access the Settings screen.
 */
@Composable
fun UnlockSettingsDialog(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var isPinVisible by remember { mutableStateOf(false) }
    val hasBiometrics = remember { BiometricHelper.canAuthenticate(context) }
    val isCustomized = remember { SettingsManager.hasCustomPasscode(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text("Admin Authentication", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isCustomized) {
                        "Enter your 4-digit admin passcode or use fingerprint to unlock settings."
                    } else {
                        "Initial setup: The default passcode is 1234. Enter 1234 to proceed to Settings and configure your security PIN."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { input ->
                        if (input.length <= 4 && input.all { it.isDigit() }) {
                            pinInput = input
                            pinError = null
                        }
                    },
                    label = { Text("4-Digit Passcode") },
                    placeholder = { Text("1234") },
                    visualTransformation = if (isPinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = pinError != null,
                    supportingText = {
                        if (pinError != null) {
                            Text(pinError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(if (isCustomized) "Enter your custom 4-digit PIN" else "Default PIN: 1234")
                        }
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Pin, contentDescription = null)
                    },
                    trailingIcon = {
                        IconButton(onClick = { isPinVisible = !isPinVisible }) {
                            Icon(
                                imageVector = if (isPinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle PIN Visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Shortcut quick-fill for initial setup if not customized
                if (!isCustomized) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            pinInput = "1234"
                            pinError = null
                        }
                    ) {
                        Text("Fill Default (1234)", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (hasBiometrics) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            BiometricHelper.showBiometricPrompt(
                                activity = activity,
                                title = "Unlock Settings",
                                subtitle = "Scan fingerprint to access Settings",
                                onSuccess = onSuccess,
                                onError = { _ -> },
                                onFailed = {
                                    Toast.makeText(context, "Biometric not recognized", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Use Fingerprint")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = pinInput.trim()
                    if (clean.length != 4) {
                        pinError = "Passcode must be 4 digits"
                        return@Button
                    }
                    if (SettingsManager.verifyDeletePasscode(context, clean)) {
                        onSuccess()
                    } else {
                        pinError = "Incorrect passcode (Default: 1234)"
                    }
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SettingsScreenContent(
    activity: FragmentActivity,
    onSaved: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var urlInput by remember { mutableStateOf(SettingsManager.getApiUrl(context)) }
    var tokenInput by remember { mutableStateOf(SettingsManager.getApiToken(context)) }
    var isTokenVisible by remember { mutableStateOf(false) }

    // Change Passcode Fields
    val isCustomized = remember { SettingsManager.hasCustomPasscode(context) }
    var currentPinInput by remember { mutableStateOf(if (!isCustomized) "1234" else "") }
    var newPinInput by remember { mutableStateOf("") }
    var confirmPinInput by remember { mutableStateOf("") }
    var isNewPinVisible by remember { mutableStateOf(false) }
    var changePinSuccessMsg by remember { mutableStateOf<String?>(null) }
    var changePinErrorMsg by remember { mutableStateOf<String?>(null) }

    val hasBiometrics = remember { BiometricHelper.canAuthenticate(context) }

    val isUrlValid = urlInput.isBlank() || urlInput.trim().startsWith("http://") || urlInput.trim().startsWith("https://")
    val canSave = urlInput.isNotBlank() && tokenInput.isNotBlank() && isUrlValid

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Expository text card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f))
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Security",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top=2.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "Admin Protected Settings",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Manage your backend API destination and change your 4-digit security PIN for deletions and admin access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Text(
            "Telegram Bot API Backend Config",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )

        // URL Field
        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text("Telegram Bot Backend Base URL") },
            placeholder = { Text("https://your-bot-server.com/") },
            isError = !isUrlValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                if (!isUrlValid) {
                    Text("URL must start with http:// or https://", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Your backend server root URL. The app automatically dispatches to /api/received-transaction.")
                }
            },
            leadingIcon = {
                Icon(Icons.Default.Link, contentDescription = null)
            },
            shape = RoundedCornerShape(12.dp)
        )

        // API Key Field
        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("Telegram Bot API Key") },
            placeholder = { Text("Enter API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            supportingText = {
                Text("Must match the API_KEY secret configured in your Telegram Membership Bot backend.")
            },
            leadingIcon = {
                Icon(Icons.Default.Key, contentDescription = null)
            },
            trailingIcon = {
                IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                    Icon(
                        imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle API key visibility"
                    )
                }
            },
            shape = RoundedCornerShape(12.dp)
        )

        // Save URL/Token Button
        Button(
            onClick = {
                if (canSave) {
                    val normalizedUrl = com.example.api.ApiClient.normalizeBaseUrl(urlInput)
                    SettingsManager.saveApiUrl(context, normalizedUrl)
                    SettingsManager.saveApiToken(context, tokenInput.trim())
                    onSaved()
                    Toast.makeText(context, "Telegram Bot API settings saved securely!", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save API Connection")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Background Running & Battery Optimizations Section
        Text(
            "Background Execution & Battery",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BatteryChargingFull,
                        contentDescription = "Background Execution",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "24/7 Background SMS Service",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            "Ensures incoming Telebirr (127) receipts are captured even when the screen is locked or the app is closed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(
                    onClick = {
                        if (activity is MainActivity) {
                            activity.requestIgnoreBatteryOptimizations()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Request Unrestricted Battery Permission")
                }
            }
        }
        Text(
            "Change 4-Digit Passcode",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            if (!SettingsManager.hasCustomPasscode(context)) {
                "Your passcode is currently set to the default: 1234. Set a new 4-digit PIN below."
            } else {
                "Enter your current 4-digit passcode followed by your new PIN."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = currentPinInput,
            onValueChange = { input ->
                if (input.length <= 4 && input.all { it.isDigit() }) currentPinInput = input
            },
            label = { Text("Current 4-Digit Passcode") },
            placeholder = { Text(if (!SettingsManager.hasCustomPasscode(context)) "1234" else "••••") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                if (!SettingsManager.hasCustomPasscode(context)) {
                    Text("Pre-filled with default: 1234", color = MaterialTheme.colorScheme.primary)
                }
            },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            shape = RoundedCornerShape(12.dp)
        )

        OutlinedTextField(
            value = newPinInput,
            onValueChange = { input ->
                if (input.length <= 4 && input.all { it.isDigit() }) newPinInput = input
            },
            label = { Text("New 4-Digit Passcode") },
            placeholder = { Text("e.g. 5892") },
            visualTransformation = if (isNewPinVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { isNewPinVisible = !isNewPinVisible }) {
                    Icon(
                        imageVector = if (isNewPinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle new PIN visibility"
                    )
                }
            },
            shape = RoundedCornerShape(12.dp)
        )

        OutlinedTextField(
            value = confirmPinInput,
            onValueChange = { input ->
                if (input.length <= 4 && input.all { it.isDigit() }) confirmPinInput = input
            },
            label = { Text("Confirm New 4-Digit Passcode") },
            placeholder = { Text("e.g. 5892") },
            visualTransformation = if (isNewPinVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
            shape = RoundedCornerShape(12.dp)
        )

        if (changePinErrorMsg != null) {
            Text(
                text = changePinErrorMsg!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (changePinSuccessMsg != null) {
            Text(
                text = changePinSuccessMsg!!,
                color = Color(0xFF2E7D32),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
            )
        }

        Button(
            onClick = {
                changePinErrorMsg = null
                changePinSuccessMsg = null

                if (!SettingsManager.verifyDeletePasscode(context, currentPinInput.trim())) {
                    changePinErrorMsg = "Current passcode is incorrect. If you haven't set one yet, use 1234."
                    return@Button
                }
                if (newPinInput.length != 4) {
                    changePinErrorMsg = "New passcode must be exactly 4 digits."
                    return@Button
                }
                if (newPinInput != confirmPinInput) {
                    changePinErrorMsg = "New passcode and confirmation do not match."
                    return@Button
                }

                SettingsManager.saveDeletePasscode(context, newPinInput.trim())
                currentPinInput = newPinInput
                newPinInput = ""
                confirmPinInput = ""
                changePinSuccessMsg = "Passcode updated successfully to your new 4-digit PIN!"
                Toast.makeText(context, "Passcode updated successfully!", Toast.LENGTH_SHORT).show()
            },
            enabled = currentPinInput.isNotBlank() && newPinInput.length == 4 && confirmPinInput.length == 4,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Default.Password, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Set / Update Passcode")
        }

        if (hasBiometrics) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = "Biometrics Enabled",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            "Biometric Fingerprint Active",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            "Device fingerprint authentication is available to unlock settings and authorize deletions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun MandatorySetupScreenContent(
    viewModel: MainViewModel,
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    var newPinInput by remember { mutableStateOf("") }
    var confirmPinInput by remember { mutableStateOf("") }
    var isPinVisible by remember { mutableStateOf(false) }

    // Optional API configuration fields
    var showOptionalApi by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf(SettingsManager.getApiUrl(context).let { if (it == SettingsManager.DEFAULT_URL) "" else it }) }
    var tokenInput by remember { mutableStateOf("") }
    var isTokenVisible by remember { mutableStateOf(false) }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    val isPinValid = newPinInput.length == 4 && newPinInput.all { it.isDigit() }
    val isConfirmValid = confirmPinInput == newPinInput && confirmPinInput.isNotEmpty()
    val canProceed = isPinValid && isConfirmValid

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Welcome & Security Mandatory Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Passcode Setup",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        "Set Security Passcode",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Please create a 4-digit PIN to protect your transaction records, authorize deletions, and guard app settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
            }
        }

        Text(
            "4-Digit Security Passcode",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )

        OutlinedTextField(
            value = newPinInput,
            onValueChange = { input ->
                if (input.length <= 4 && input.all { it.isDigit() }) {
                    newPinInput = input
                    errorMessage = null
                }
            },
            label = { Text("New 4-Digit Passcode *") },
            placeholder = { Text("Enter 4 digits (e.g. 7391)") },
            visualTransformation = if (isPinVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { isPinVisible = !isPinVisible }) {
                    Icon(
                        imageVector = if (isPinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle PIN visibility"
                    )
                }
            },
            shape = RoundedCornerShape(12.dp)
        )

        OutlinedTextField(
            value = confirmPinInput,
            onValueChange = { input ->
                if (input.length <= 4 && input.all { it.isDigit() }) {
                    confirmPinInput = input
                    errorMessage = null
                }
            },
            label = { Text("Confirm 4-Digit Passcode *") },
            placeholder = { Text("Repeat 4-digit PIN") },
            visualTransformation = if (isPinVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = confirmPinInput.isNotBlank() && !isConfirmValid,
            supportingText = {
                if (confirmPinInput.isNotBlank() && !isConfirmValid) {
                    Text("Passcodes do not match", color = MaterialTheme.colorScheme.error)
                } else if (isConfirmValid) {
                    Text("Passcode confirmed ✓", color = MaterialTheme.colorScheme.primary)
                }
            },
            leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
            shape = RoundedCornerShape(12.dp)
        )

        // Optional Backend API Config Accordion
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "API Sync Configuration (Optional)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                    TextButton(onClick = { showOptionalApi = !showOptionalApi }) {
                        Text(if (showOptionalApi) "Hide" else "Configure Now")
                    }
                }

                if (showOptionalApi) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "You can also configure or change your API endpoint anytime in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("Telegram Bot Backend Base URL") },
                        placeholder = { Text("https://your-bot-server.com/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("Telegram Bot API Key") },
                        placeholder = { Text("Enter API Key (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                                Icon(
                                    imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle API key visibility"
                                )
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = {
                errorMessage = null
                if (!isPinValid) {
                    errorMessage = "Please enter a 4-digit numerical passcode."
                    return@Button
                }
                if (!isConfirmValid) {
                    errorMessage = "Passcode and confirmation PIN do not match."
                    return@Button
                }

                val optionalUrl = if (showOptionalApi && urlInput.isNotBlank()) urlInput.trim() else null
                val optionalToken = if (showOptionalApi && tokenInput.isNotBlank()) tokenInput.trim() else null

                val success = viewModel.saveInitialPasscode(
                    passcode = newPinInput.trim(),
                    apiUrl = optionalUrl,
                    apiToken = optionalToken
                )

                if (success) {
                    Toast.makeText(context, "Passcode configured successfully!", Toast.LENGTH_SHORT).show()
                    onCompleted()
                } else {
                    errorMessage = "Failed to save passcode. Please try again."
                }
            },
            enabled = canProceed,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Set Passcode & Start", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

