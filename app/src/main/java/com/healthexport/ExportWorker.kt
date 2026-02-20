package com.healthexport

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.healthexport.models.ExportConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val channelId = "health_export_autorun"
    private val notificationId = 2001

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(createForegroundInfo())

            val prefs = applicationContext.getSharedPreferences("health_export_prefs", Context.MODE_PRIVATE)

            val spreadsheetId = prefs.getString("spreadsheet_id", null)
            val accountName = prefs.getString("account_name", null)
            if (spreadsheetId.isNullOrBlank() || accountName.isNullOrBlank()) {
                Log.e("ExportWorker", "Missing spreadsheet_id or account_name")
                return@withContext Result.failure()
            }

            val base = ExportConfig(
                includeActiveCalories = prefs.getBoolean("includeActiveCalories", false),
                includeDistance = prefs.getBoolean("includeDistance", false),
                includeElevationGained = prefs.getBoolean("includeElevationGained", false),
                includeExercise = prefs.getBoolean("includeExercise", false),
                includeFloorsClimbed = prefs.getBoolean("includeFloorsClimbed", false),
                includePower = prefs.getBoolean("includePower", false),
                includeSpeed = prefs.getBoolean("includeSpeed", false),
                includeSteps = prefs.getBoolean("includeSteps", false),
                includeTotalCalories = prefs.getBoolean("includeTotalCalories", false),
                includeVO2Max = prefs.getBoolean("includeVO2Max", false),
                includeWheelchairPushes = prefs.getBoolean("includeWheelchairPushes", false),

                includeBodyFat = prefs.getBoolean("includeBodyFat", false),
                includeBoneMass = prefs.getBoolean("includeBoneMass", false),
                includeHeight = prefs.getBoolean("includeHeight", false),
                includeLeanBodyMass = prefs.getBoolean("includeLeanBodyMass", false),
                includeWeight = prefs.getBoolean("includeWeight", false),

                includeCervicalMucus = prefs.getBoolean("includeCervicalMucus", false),
                includeMenstruation = prefs.getBoolean("includeMenstruation", false),
                includeOvulationTest = prefs.getBoolean("includeOvulationTest", false),
                includeSexualActivity = prefs.getBoolean("includeSexualActivity", false),

                includeHydration = prefs.getBoolean("includeHydration", false),
                includeNutrition = prefs.getBoolean("includeNutrition", false),

                includeSleepSession = prefs.getBoolean("includeSleepSession", false),

                includeBloodGlucose = prefs.getBoolean("includeBloodGlucose", false),
                includeBloodPressure = prefs.getBoolean("includeBloodPressure", false),
                includeBodyTemperature = prefs.getBoolean("includeBodyTemperature", false),
                includeHeartRate = prefs.getBoolean("includeHeartRate", false),
                includeHeartRateVariability = prefs.getBoolean("includeHeartRateVariability", false),
                includeOxygenSaturation = prefs.getBoolean("includeOxygenSaturation", false),
                includeRespiratoryRate = prefs.getBoolean("includeRespiratoryRate", false),
                includeRestingHeartRate = prefs.getBoolean("includeRestingHeartRate", false),

                daysBack = 1
            )

            val autoConfig = base.copy(
                includeBloodGlucose = false,
                includeBloodPressure = false,
                includeBodyTemperature = false,
                includeHeartRate = false,
                includeHeartRateVariability = false,
                includeOxygenSaturation = false,
                includeRespiratoryRate = false
            )

            val healthManager = HealthConnectManager(applicationContext)
            val safer = prefs.getBoolean("safer_export_enabled", false)
            healthManager.setSaferExportMode(safer)

            val permController = androidx.health.connect.client.HealthConnectClient
                .getOrCreate(applicationContext)
                .permissionController

            val granted = permController.getGrantedPermissions()
            val required = healthManager.permissions
            val missing = required.filter { it !in granted }
            if (missing.isNotEmpty()) {
                Log.e("ExportWorker", "Missing Health Connect permissions: $missing")
                return@withContext Result.retry()
            }

            val dailyData = healthManager.readHealthDataByDay(autoConfig)

            val sheetsManager = SheetsManager(applicationContext)
            sheetsManager.initializeCredential(accountName)

            val result = sheetsManager.exportToSheets(spreadsheetId, dailyData)

            if (result.isSuccess) {
                prefs.edit().putLong("last_export_auto", System.currentTimeMillis()).apply()
                Result.success()
            } else {
                Log.e("ExportWorker", "Sheets export failed: ${result.exceptionOrNull()?.message}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("ExportWorker", "Auto export failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Auto export running")
            .setContentText("Exporting Health Connect data…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return ForegroundInfo(notificationId, notification)
    }
}