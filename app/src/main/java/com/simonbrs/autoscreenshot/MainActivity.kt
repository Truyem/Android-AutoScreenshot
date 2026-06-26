package com.simonbrs.autoscreenshot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.PowerManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.simonbrs.autoscreenshot.service.ScreenshotService
import com.simonbrs.autoscreenshot.ui.theme.AutoScreenshotTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val STORAGE_PERMISSION_CODE = 100
        private const val OVERLAY_PERMISSION_CODE = 101
        private const val BATTERY_OPTIMIZATION_PERMISSION_CODE = 102
        private const val PREFS_NAME = "AutoScreenshotPrefs"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val AUTO_START_SERVICE = "AUTO_START_SERVICE"
        private const val KEY_SCREENSHOT_INTERVAL_SECONDS = "screenshot_interval_seconds"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_WEBHOOK_MESSAGE = "webhook_message"
        private const val KEY_WEBHOOK_TIMEZONE = "webhook_timezone"
        private const val KEY_WEBHOOK_DELETE_PREVIOUS = "webhook_delete_previous"
        private const val DEFAULT_SCREENSHOT_INTERVAL_SECONDS = 10L
    }
    
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var prefs: SharedPreferences
    
    private var isServiceRunning by mutableStateOf(false)
    private var shouldAutoStart = false
    private var screenshotIntervalSeconds by mutableStateOf(DEFAULT_SCREENSHOT_INTERVAL_SECONDS)
    private var webhookUrl by mutableStateOf("")
    private var webhookMessage by mutableStateOf("")
    private var webhookTimezone by mutableStateOf("Asia/Ho_Chi_Minh")
    private var deletePreviousWebhookMessage by mutableStateOf(false)
    private val webhookExecutor = Executors.newSingleThreadExecutor()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isServiceRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false)
        shouldAutoStart = intent?.getBooleanExtra(AUTO_START_SERVICE, false) ?: false
        screenshotIntervalSeconds = prefs.getLong(KEY_SCREENSHOT_INTERVAL_SECONDS, DEFAULT_SCREENSHOT_INTERVAL_SECONDS)
        webhookUrl = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        webhookMessage = prefs.getString(KEY_WEBHOOK_MESSAGE, "") ?: ""
        webhookTimezone = prefs.getString(KEY_WEBHOOK_TIMEZONE, "Asia/Ho_Chi_Minh") ?: "Asia/Ho_Chi_Minh"
        deletePreviousWebhookMessage = prefs.getBoolean(KEY_WEBHOOK_DELETE_PREVIOUS, false)
        
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                continuePermissionFlow()
            } else {
                handleBlockedPermission("Storage or notification permission was denied")
            }
        }
        
        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                // Start screenshot service
                val serviceIntent = Intent(this, ScreenshotService::class.java)
                serviceIntent.putExtra(ScreenshotService.EXTRA_RESULT_DATA, result.data)
                serviceIntent.putExtra(ScreenshotService.EXTRA_INTERVAL_SECONDS, screenshotIntervalSeconds)
                serviceIntent.putExtra(ScreenshotService.EXTRA_WEBHOOK_URL, webhookUrl)
                serviceIntent.putExtra(ScreenshotService.EXTRA_WEBHOOK_MESSAGE, webhookMessage)
                serviceIntent.putExtra(ScreenshotService.EXTRA_WEBHOOK_TIMEZONE, webhookTimezone)
                serviceIntent.putExtra(ScreenshotService.EXTRA_WEBHOOK_DELETE_PREVIOUS, deletePreviousWebhookMessage)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                
                // Mark the service as running
                isServiceRunning = true
                saveServiceRunningState(true)
                
                Toast.makeText(this, "Screenshot service started - saving to /storage/emulated/0/Screenshot/YYYY/MM/DD/", Toast.LENGTH_LONG).show()
            } else {
                handleBlockedPermission("Screen capture permission was denied")
            }
        }
        
        setContent {
            AutoScreenshotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScreenshotScreen(
                        isServiceRunning = isServiceRunning,
                        initialIntervalSeconds = screenshotIntervalSeconds.toString(),
                        initialWebhookUrl = webhookUrl,
                        initialWebhookMessage = webhookMessage,
                        initialWebhookTimezone = webhookTimezone,
                        initialDeletePreviousWebhookMessage = deletePreviousWebhookMessage,
                        onSettingsChanged = { intervalSeconds, webhook, message, timezone, deletePrevious ->
                            saveCaptureSettings(intervalSeconds, webhook, message, timezone, deletePrevious)
                        },
                        onStartService = { startScreenshotCapture() },
                        onStopService = { stopScreenshotService() }
                    )
                }
            }
        }
        
        // Auto-start if coming from boot receiver
        if (shouldAutoStart) {
            startScreenshotCapture()
        }
    }

    override fun onDestroy() {
        webhookExecutor.shutdown()
        super.onDestroy()
    }
    
    override fun onResume() {
        super.onResume()
        
        // Check if we need to continue the permission flow after external storage or overlay
        if (shouldAutoStart) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                // Wait for user to grant storage permission
                return
            }
            
            if (!Settings.canDrawOverlays(this)) {
                // Wait for user to grant overlay permission
                return
            }
            
            if (isBatteryOptimizationBlocking()) {
                requestIgnoreBatteryOptimization()
                return
            }
            // All permissions are granted, continue with media projection
            requestMediaProjection()
        }
    }
    
    private fun saveServiceRunningState(running: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, running).apply()
    }

    private fun saveCaptureSettings(
        intervalSeconds: Long,
        webhook: String,
        message: String,
        timezone: String,
        deletePrevious: Boolean
    ) {
        screenshotIntervalSeconds = intervalSeconds.coerceAtLeast(1L)
        webhookUrl = webhook.trim()
        webhookMessage = message
        webhookTimezone = timezone.trim().ifBlank { "Asia/Ho_Chi_Minh" }
        deletePreviousWebhookMessage = deletePrevious
        prefs.edit()
            .putLong(KEY_SCREENSHOT_INTERVAL_SECONDS, screenshotIntervalSeconds)
            .putString(KEY_WEBHOOK_URL, webhookUrl)
            .putString(KEY_WEBHOOK_MESSAGE, webhookMessage)
            .putString(KEY_WEBHOOK_TIMEZONE, webhookTimezone)
            .putBoolean(KEY_WEBHOOK_DELETE_PREVIOUS, deletePreviousWebhookMessage)
            .apply()
    }
    
    private fun startScreenshotCapture() {
        // Check for required permissions
        if (checkAndRequestPermissions()) {
            continuePermissionFlow()
        }
    }
    
    private fun stopScreenshotService() {
        stopService(Intent(this, ScreenshotService::class.java))
        isServiceRunning = false
        saveServiceRunningState(false)
        Toast.makeText(this, "Screenshot service stopped", Toast.LENGTH_SHORT).show()
    }
    
    private fun requestMediaProjection() {
        // Make sure we have storage permission first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestManageExternalStoragePermission()
            return
        }
        
        // Make sure we have overlay permission
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        if (isBatteryOptimizationBlocking()) {
            requestIgnoreBatteryOptimization()
            return
        }
        
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }
    
    private fun requestOverlayPermission() {
        Toast.makeText(this, "Please grant overlay permission for service stability", Toast.LENGTH_SHORT).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
    }
    
    private fun checkAndRequestPermissions(): Boolean {
        val permissionsToRequest = mutableListOf<String>()
        
        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Check storage permissions through Android 10. Android 10 still needs the
        // legacy storage runtime grants when writing directly to /storage/emulated/0.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
            return false
        }
        
        // For Android 11+, check if we have manage external storage permission.
        // Android 10 uses requestLegacyExternalStorage plus READ/WRITE_EXTERNAL_STORAGE.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestManageExternalStoragePermission()
                return false
            }
        }
        
        return true
    }

    private fun continuePermissionFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestManageExternalStoragePermission()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        if (isBatteryOptimizationBlocking()) {
            requestIgnoreBatteryOptimization()
            return
        }

        requestMediaProjection()
    }

    private fun isBatteryOptimizationBlocking(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return !powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        try {
            Toast.makeText(this, "Please allow battery optimization exemption for background screenshots", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivityForResult(intent, BATTERY_OPTIMIZATION_PERMISSION_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Battery optimization permission is blocked", Toast.LENGTH_LONG).show()
            handleBlockedPermission("Battery optimization exemption is blocked")
        }
    }
    
    private fun requestManageExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                Toast.makeText(this, "Please grant storage permission", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == OVERLAY_PERMISSION_CODE) {
            if (Settings.canDrawOverlays(this)) {
                continuePermissionFlow()
            } else {
                handleBlockedPermission("Overlay permission was denied")
            }
        } else if (requestCode == BATTERY_OPTIMIZATION_PERMISSION_CODE) {
            if (isBatteryOptimizationBlocking()) {
                handleBlockedPermission("Battery optimization exemption was denied")
            } else {
                continuePermissionFlow()
            }
        }
    }

    private fun handleBlockedPermission(reason: String) {
        Toast.makeText(this, "$reason. Webhook has been disabled.", Toast.LENGTH_LONG).show()
        sendPermissionBlockedWebhook(reason)
        webhookUrl = ""
        prefs.edit().putString(KEY_WEBHOOK_URL, webhookUrl).apply()
    }

    private fun sendPermissionBlockedWebhook(reason: String) {
        val url = webhookUrl.trim()
        if (url.isBlank()) {
            return
        }

        webhookExecutor.execute {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                connection.outputStream.use { output ->
                    OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                        writer.write("{\"content\":\"Auto Screenshot stopped webhook because permission was blocked: ${escapeJson(reason)}\"}")
                        writer.flush()
                    }
                }

                val responseCode = connection.responseCode
                connection.disconnect()
                if (responseCode !in 200..299) {
                    android.util.Log.e("MainActivity", "Permission blocked webhook failed with HTTP $responseCode")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error sending permission blocked webhook", e)
            }
        }
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}

@Composable
fun ScreenshotScreen(
    isServiceRunning: Boolean,
    initialIntervalSeconds: String,
    initialWebhookUrl: String,
    initialWebhookMessage: String,
    initialWebhookTimezone: String,
    initialDeletePreviousWebhookMessage: Boolean,
    onSettingsChanged: (Long, String, String, String, Boolean) -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    var intervalText by remember { mutableStateOf(initialIntervalSeconds) }
    var webhookText by remember { mutableStateOf(initialWebhookUrl) }
    var webhookMessageText by remember { mutableStateOf(initialWebhookMessage) }
    var webhookTimezoneText by remember { mutableStateOf(initialWebhookTimezone) }
    var deletePreviousWebhookMessage by remember { mutableStateOf(initialDeletePreviousWebhookMessage) }

    LaunchedEffect(intervalText, webhookText, webhookMessageText, webhookTimezoneText, deletePreviousWebhookMessage) {
        val intervalSeconds = intervalText.toLongOrNull()?.coerceAtLeast(1L) ?: 10L
        onSettingsChanged(
            intervalSeconds,
            webhookText,
            webhookMessageText,
            webhookTimezoneText,
            deletePreviousWebhookMessage
        )
    }
    
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Auto Screenshot",
                style = MaterialTheme.typography.headlineMedium
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "This app will take screenshots on your chosen interval, organize them by date, remove duplicates, and optionally send new screenshots to a webhook.",
                style = MaterialTheme.typography.bodyMedium
            )
            
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = intervalText,
                onValueChange = { intervalText = it.filter(Char::isDigit).ifBlank { "1" } },
                label = { Text("Interval (seconds)") },
                enabled = !isServiceRunning,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = webhookText,
                onValueChange = { webhookText = it },
                label = { Text("Webhook URL (optional)") },
                enabled = !isServiceRunning,
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = webhookMessageText,
                onValueChange = { webhookMessageText = it },
                label = { Text("Webhook message (optional)") },
                placeholder = { Text("Hiện tại {day} {time}") },
                supportingText = { Text("Placeholders: {time}, {day}, {date}, {timezone}, {filename}") },
                enabled = !isServiceRunning,
                minLines = 2
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = webhookTimezoneText,
                onValueChange = { webhookTimezoneText = it },
                label = { Text("Time.Now timezone") },
                placeholder = { Text("Asia/Ho_Chi_Minh") },
                supportingText = { Text("Use an IANA timezone from time.now, e.g. Asia/Ho_Chi_Minh") },
                enabled = !isServiceRunning,
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { deletePreviousWebhookMessage = !deletePreviousWebhookMessage },
                enabled = !isServiceRunning,
                colors = ButtonDefaults.buttonColors()
            ) {
                Text(if (deletePreviousWebhookMessage) "Delete previous webhook message: On" else "Delete previous webhook message: Off")
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            if (isServiceRunning) {
                Button(onClick = onStopService) {
                    Text("Stop Screenshot Service")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Service is running in the background.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Button(onClick = onStartService) {
                    Text("Start Screenshot Service")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Service is not running.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ScreenshotScreenPreview() {
    AutoScreenshotTheme {
        ScreenshotScreen(
            isServiceRunning = false,
            initialIntervalSeconds = "10",
            initialWebhookUrl = "",
            initialWebhookMessage = "Hiện tại {day} {time}",
            initialWebhookTimezone = "Asia/Ho_Chi_Minh",
            initialDeletePreviousWebhookMessage = false,
            onSettingsChanged = { _, _, _, _, _ -> },
            onStartService = {},
            onStopService = {}
        )
    }
}
