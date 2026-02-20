package com.healthexport

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Spanned
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.api.services.sheets.v4.SheetsScopes
import com.healthexport.models.DailyHealthData
import com.healthexport.models.ExportConfig
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    // Category checkboxes
    private lateinit var checkCategoryActivity: CheckBox
    private lateinit var checkCategoryBodyMeasurements: CheckBox
    private lateinit var checkCategoryCycleTracking: CheckBox
    private lateinit var checkCategoryNutrition: CheckBox
    private lateinit var checkCategorySleep: CheckBox
    private lateinit var checkCategoryVitals: CheckBox

    // Activity sub-checkboxes
    private lateinit var checkActiveCalories: CheckBox
    private lateinit var checkDistance: CheckBox
    private lateinit var checkElevationGained: CheckBox
    private lateinit var checkExercise: CheckBox
    private lateinit var checkFloorsClimbed: CheckBox
    private lateinit var checkPower: CheckBox
    private lateinit var checkSpeed: CheckBox
    private lateinit var checkSteps: CheckBox
    private lateinit var checkTotalCalories: CheckBox
    private lateinit var checkVO2Max: CheckBox
    private lateinit var checkWheelchairPushes: CheckBox

    // Body Measurements
    private lateinit var checkBodyFat: CheckBox
    private lateinit var checkBoneMass: CheckBox
    private lateinit var checkHeight: CheckBox
    private lateinit var checkLeanBodyMass: CheckBox
    private lateinit var checkWeight: CheckBox

    // Cycle Tracking
    private lateinit var checkCervicalMucus: CheckBox
    private lateinit var checkMenstruation: CheckBox
    private lateinit var checkOvulationTest: CheckBox
    private lateinit var checkSexualActivity: CheckBox

    // Nutrition
    private lateinit var checkHydration: CheckBox
    private lateinit var checkNutrition: CheckBox

    // Sleep
    private lateinit var checkSleepSession: CheckBox

    // Vitals
    private lateinit var checkBloodGlucose: CheckBox
    private lateinit var checkBloodPressure: CheckBox
    private lateinit var checkBodyTemperature: CheckBox
    private lateinit var checkHeartRate: CheckBox
    private lateinit var checkHeartRateVariability: CheckBox
    private lateinit var checkOxygenSaturation: CheckBox
    private lateinit var checkRespiratoryRate: CheckBox
    private lateinit var checkRestingHeartRate: CheckBox

    // Expandable layouts
    private lateinit var layoutActivitySubtypes: LinearLayout
    private lateinit var layoutBodyMeasurementsSubtypes: LinearLayout
    private lateinit var layoutCycleTrackingSubtypes: LinearLayout
    private lateinit var layoutNutritionSubtypes: LinearLayout
    private lateinit var layoutSleepSubtypes: LinearLayout
    private lateinit var layoutVitalsSubtypes: LinearLayout

    // Other UI
    private lateinit var spinnerDateRange: Spinner
    private lateinit var spinnerFrequency: Spinner
    private lateinit var spinnerCsvSplit: Spinner
    private lateinit var switchAutoExport: SwitchMaterial
    private lateinit var switchSaferExport: SwitchMaterial

    // Daily Manual Export UI
    private lateinit var switchManualExportReminder: SwitchMaterial
    private lateinit var layoutManualExportControls: LinearLayout
    private lateinit var txtReminderTime: TextView
    private lateinit var btnPickReminderTime: Button
    private lateinit var btnExportAll3Days: Button

    private lateinit var editSheetId: TextInputEditText
    private lateinit var btnConnectGoogle: Button
    private lateinit var btnExport: Button
    private lateinit var btnExportCsv: Button
    private lateinit var btnCsvGuide: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtLastExport: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var sheetsManager: SheetsManager

    private var googleAccount: GoogleSignInAccount? = null
    private val prefs by lazy { getSharedPreferences("health_export_prefs", MODE_PRIVATE) }

    private val exportInProgress = AtomicBoolean(false)

    // Cache
    private var cachedKey: String? = null
    private var cachedAtMs: Long = 0L
    private var cachedDailyData: Map<String, DailyHealthData>? = null
    private val cacheTtlMs: Long = 5 * 60 * 1000L

    private val allTimePosition = 4
    private fun isAllTimeSelected(): Boolean = spinnerDateRange.selectedItemPosition == allTimePosition

    // Notification channels
    private val reminderChannelId = "health_export_reminder"
    private val autoExportChannelId = "health_export_autorun"

    private val healthPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) showStatus("Health permissions granted")
        else showStatus("Some Health Connect permissions were denied")
    }

    private val postNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) showStatus("Notification permission denied (daily reminders may not show)")
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            googleAccount = task.result
            googleAccount?.let { account ->
                val email = account.email ?: return@let
                sheetsManager.initializeCredential(email)
                prefs.edit().putString("account_name", email).apply()
                btnExport.isEnabled = true
                btnConnectGoogle.text = "Connected: $email"
                showStatus("Google connected")
            }
        } catch (e: Exception) {
            showStatus("Google sign-in failed: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        healthConnectManager = HealthConnectManager(this)
        sheetsManager = SheetsManager(this)

        bindViews()
        setupCategoryExpansion()

        createReminderNotificationChannel()
        createAutoExportNotificationChannel()

        loadPrefsIntoUi()
        applyAsterisks()
        setupListeners()
        requestHealthPermissions()
    }

    override fun onPause() {
        super.onPause()
        saveUiToPrefs()
    }

    private fun bindViews() {
        checkCategoryActivity = findViewById(R.id.checkCategoryActivity)
        checkCategoryBodyMeasurements = findViewById(R.id.checkCategoryBodyMeasurements)
        checkCategoryCycleTracking = findViewById(R.id.checkCategoryCycleTracking)
        checkCategoryNutrition = findViewById(R.id.checkCategoryNutrition)
        checkCategorySleep = findViewById(R.id.checkCategorySleep)
        checkCategoryVitals = findViewById(R.id.checkCategoryVitals)

        checkActiveCalories = findViewById(R.id.checkActiveCalories)
        checkDistance = findViewById(R.id.checkDistance)
        checkElevationGained = findViewById(R.id.checkElevationGained)
        checkExercise = findViewById(R.id.checkExercise)
        checkFloorsClimbed = findViewById(R.id.checkFloorsClimbed)
        checkPower = findViewById(R.id.checkPower)
        checkSpeed = findViewById(R.id.checkSpeed)
        checkSteps = findViewById(R.id.checkSteps)
        checkTotalCalories = findViewById(R.id.checkTotalCalories)
        checkVO2Max = findViewById(R.id.checkVO2Max)
        checkWheelchairPushes = findViewById(R.id.checkWheelchairPushes)

        checkBodyFat = findViewById(R.id.checkBodyFat)
        checkBoneMass = findViewById(R.id.checkBoneMass)
        checkHeight = findViewById(R.id.checkHeight)
        checkLeanBodyMass = findViewById(R.id.checkLeanBodyMass)
        checkWeight = findViewById(R.id.checkWeight)

        checkCervicalMucus = findViewById(R.id.checkCervicalMucus)
        checkMenstruation = findViewById(R.id.checkMenstruation)
        checkOvulationTest = findViewById(R.id.checkOvulationTest)
        checkSexualActivity = findViewById(R.id.checkSexualActivity)

        checkHydration = findViewById(R.id.checkHydration)
        checkNutrition = findViewById(R.id.checkNutrition)

        checkSleepSession = findViewById(R.id.checkSleepSession)

        checkBloodGlucose = findViewById(R.id.checkBloodGlucose)
        checkBloodPressure = findViewById(R.id.checkBloodPressure)
        checkBodyTemperature = findViewById(R.id.checkBodyTemperature)
        checkHeartRate = findViewById(R.id.checkHeartRate)
        checkHeartRateVariability = findViewById(R.id.checkHeartRateVariability)
        checkOxygenSaturation = findViewById(R.id.checkOxygenSaturation)
        checkRespiratoryRate = findViewById(R.id.checkRespiratoryRate)
        checkRestingHeartRate = findViewById(R.id.checkRestingHeartRate)

        layoutActivitySubtypes = findViewById(R.id.layoutActivitySubtypes)
        layoutBodyMeasurementsSubtypes = findViewById(R.id.layoutBodyMeasurementsSubtypes)
        layoutCycleTrackingSubtypes = findViewById(R.id.layoutCycleTrackingSubtypes)
        layoutNutritionSubtypes = findViewById(R.id.layoutNutritionSubtypes)
        layoutSleepSubtypes = findViewById(R.id.layoutSleepSubtypes)
        layoutVitalsSubtypes = findViewById(R.id.layoutVitalsSubtypes)

        spinnerDateRange = findViewById(R.id.spinnerDateRange)
        spinnerCsvSplit = findViewById(R.id.spinnerCsvSplit)
        spinnerFrequency = findViewById(R.id.spinnerFrequency)

        switchAutoExport = findViewById(R.id.switchAutoExport)
        switchSaferExport = findViewById(R.id.switchSaferExport)

        switchManualExportReminder = findViewById(R.id.switchManualExportReminder)
        layoutManualExportControls = findViewById(R.id.layoutManualExportControls)
        txtReminderTime = findViewById(R.id.txtReminderTime)
        btnPickReminderTime = findViewById(R.id.btnPickReminderTime)
        btnExportAll3Days = findViewById(R.id.btnExportAll3Days)

        editSheetId = findViewById(R.id.editSheetId)
        btnConnectGoogle = findViewById(R.id.btnConnectGoogle)
        btnExport = findViewById(R.id.btnExport)
        btnExportCsv = findViewById(R.id.btnExportCsv)
        btnCsvGuide = findViewById(R.id.btnCsvGuide)
        txtStatus = findViewById(R.id.txtStatus)
        txtLastExport = findViewById(R.id.txtLastExport)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun setupCategoryExpansion() {
        checkCategoryActivity.setOnCheckedChangeListener { _, checked ->
            layoutActivitySubtypes.visibility = if (checked) View.VISIBLE else View.GONE
        }
        checkCategoryBodyMeasurements.setOnCheckedChangeListener { _, checked ->
            layoutBodyMeasurementsSubtypes.visibility = if (checked) View.VISIBLE else View.GONE
        }
        checkCategoryCycleTracking.setOnCheckedChangeListener { _, checked ->
            layoutCycleTrackingSubtypes.visibility = if (checked) View.VISIBLE else View.GONE
        }
        checkCategoryNutrition.setOnCheckedChangeListener { _, checked ->
            layoutNutritionSubtypes.visibility = if (checked) View.VISIBLE else View.GONE
        }
        checkCategorySleep.setOnCheckedChangeListener { _, checked ->
            layoutSleepSubtypes.visibility = if (checked) View.VISIBLE else View.GONE
        }
        checkCategoryVitals.setOnCheckedChangeListener { _, checked ->
            layoutVitalsSubtypes.visibility = if (checked) View.VISIBLE else View.GONE
        }
    }

    private fun createReminderNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                reminderChannelId,
                "Export reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Daily reminder to manually export Health Connect data"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun createAutoExportNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                autoExportChannelId,
                "Auto export",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification shown while auto export is running"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun ensurePostNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun updateLastExportLabels() {
        val df = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

        val manual = prefs.getLong("last_export_manual", 0L)
        val auto = prefs.getLong("last_export_auto", 0L)

        val manualText = if (manual > 0L) df.format(Date(manual)) else "Never"
        val autoText = if (auto > 0L) df.format(Date(auto)) else "Never"

        txtLastExport.text = "Last manual export: $manualText\nLast auto export: $autoText"
    }

    private fun loadPrefsIntoUi() {
        editSheetId.setText(prefs.getString("spreadsheet_id", ""))

        val savedAccount = prefs.getString("account_name", null)
        if (!savedAccount.isNullOrBlank()) {
            btnConnectGoogle.text = "Connected: $savedAccount"
            btnExport.isEnabled = true
            sheetsManager.initializeCredential(savedAccount)
        }

        updateLastExportLabels()

        val saferEnabled = prefs.getBoolean("safer_export_enabled", false)
        switchSaferExport.isChecked = saferEnabled
        healthConnectManager.setSaferExportMode(saferEnabled)

        val autoEnabled = prefs.getBoolean("auto_export_enabled", false)
        switchAutoExport.isChecked = autoEnabled
        spinnerFrequency.isEnabled = autoEnabled

        val enabledDailyManual = prefs.getBoolean("manual_reminder_enabled", false)
        switchManualExportReminder.isChecked = enabledDailyManual
        layoutManualExportControls.visibility = if (enabledDailyManual) View.VISIBLE else View.GONE
        updateReminderTimeLabel()

        // Categories
        checkCategoryActivity.isChecked = prefs.getBoolean("cat_activity", true)
        checkCategoryBodyMeasurements.isChecked = prefs.getBoolean("cat_body", true)
        checkCategoryCycleTracking.isChecked = prefs.getBoolean("cat_cycle", true)
        checkCategoryNutrition.isChecked = prefs.getBoolean("cat_nutrition", true)
        checkCategorySleep.isChecked = prefs.getBoolean("cat_sleep", true)
        checkCategoryVitals.isChecked = prefs.getBoolean("cat_vitals", true)

        // Activity
        checkActiveCalories.isChecked = prefs.getBoolean("includeActiveCalories", true)
        checkDistance.isChecked = prefs.getBoolean("includeDistance", true)
        checkElevationGained.isChecked = prefs.getBoolean("includeElevationGained", true)
        checkExercise.isChecked = prefs.getBoolean("includeExercise", true)
        checkFloorsClimbed.isChecked = prefs.getBoolean("includeFloorsClimbed", true)
        checkPower.isChecked = prefs.getBoolean("includePower", true)
        checkSpeed.isChecked = prefs.getBoolean("includeSpeed", true)
        checkSteps.isChecked = prefs.getBoolean("includeSteps", true)
        checkTotalCalories.isChecked = prefs.getBoolean("includeTotalCalories", true)
        checkVO2Max.isChecked = prefs.getBoolean("includeVO2Max", true)
        checkWheelchairPushes.isChecked = prefs.getBoolean("includeWheelchairPushes", true)

        // Body
        checkBodyFat.isChecked = prefs.getBoolean("includeBodyFat", true)
        checkBoneMass.isChecked = prefs.getBoolean("includeBoneMass", true)
        checkHeight.isChecked = prefs.getBoolean("includeHeight", true)
        checkLeanBodyMass.isChecked = prefs.getBoolean("includeLeanBodyMass", true)
        checkWeight.isChecked = prefs.getBoolean("includeWeight", true)

        // Cycle
        checkCervicalMucus.isChecked = prefs.getBoolean("includeCervicalMucus", true)
        checkMenstruation.isChecked = prefs.getBoolean("includeMenstruation", true)
        checkOvulationTest.isChecked = prefs.getBoolean("includeOvulationTest", true)
        checkSexualActivity.isChecked = prefs.getBoolean("includeSexualActivity", true)

        // Nutrition
        checkHydration.isChecked = prefs.getBoolean("includeHydration", true)
        checkNutrition.isChecked = prefs.getBoolean("includeNutrition", true)

        // Sleep
        checkSleepSession.isChecked = prefs.getBoolean("includeSleepSession", true)

        // Vitals defaults: BG/BP/Temp OFF
        checkBloodGlucose.isChecked = prefs.getBoolean("includeBloodGlucose", false)
        checkBloodPressure.isChecked = prefs.getBoolean("includeBloodPressure", false)
        checkBodyTemperature.isChecked = prefs.getBoolean("includeBodyTemperature", false)

        // High volume defaults off
        checkHeartRate.isChecked = prefs.getBoolean("includeHeartRate", false)
        checkHeartRateVariability.isChecked = prefs.getBoolean("includeHeartRateVariability", false)
        checkOxygenSaturation.isChecked = prefs.getBoolean("includeOxygenSaturation", false)
        checkRespiratoryRate.isChecked = prefs.getBoolean("includeRespiratoryRate", false)

        // Resting HR default on
        checkRestingHeartRate.isChecked = prefs.getBoolean("includeRestingHeartRate", true)

        // Expand/collapse to match saved category state
        layoutActivitySubtypes.visibility = if (checkCategoryActivity.isChecked) View.VISIBLE else View.GONE
        layoutBodyMeasurementsSubtypes.visibility = if (checkCategoryBodyMeasurements.isChecked) View.VISIBLE else View.GONE
        layoutCycleTrackingSubtypes.visibility = if (checkCategoryCycleTracking.isChecked) View.VISIBLE else View.GONE
        layoutNutritionSubtypes.visibility = if (checkCategoryNutrition.isChecked) View.VISIBLE else View.GONE
        layoutSleepSubtypes.visibility = if (checkCategorySleep.isChecked) View.VISIBLE else View.GONE
        layoutVitalsSubtypes.visibility = if (checkCategoryVitals.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateReminderTimeLabel() {
        val h = prefs.getInt("manual_reminder_hour", -1)
        val m = prefs.getInt("manual_reminder_minute", -1)
        if (h < 0 || m < 0) {
            txtReminderTime.text = "Time: Not set"
            return
        }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
        }
        val label = SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
        txtReminderTime.text = "Time: $label"
    }

    private fun applyAsterisks() {
        fun hv(label: String): Spanned =
            HtmlCompat.fromHtml("$label <font color='#D32F2F'>*</font>", HtmlCompat.FROM_HTML_MODE_LEGACY)

        checkBloodGlucose.text = hv("Blood glucose")
        checkBloodPressure.text = hv("Blood pressure")
        checkBodyTemperature.text = hv("Body temperature")

        checkHeartRate.text = hv("Heart rate (min/max/avg)")
        checkHeartRateVariability.text = hv("Heart rate variability (min/max/avg)")
        checkOxygenSaturation.text = hv("Oxygen saturation (min/max/avg)")
        checkRespiratoryRate.text = hv("Respiratory rate (min/max/avg)")
    }

    private fun setupListeners() {
        btnConnectGoogle.setOnClickListener { signInWithGoogle() }
        btnCsvGuide.setOnClickListener { startActivity(Intent(this, CsvGuidanceActivity::class.java)) }

        switchSaferExport.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean("safer_export_enabled", enabled).apply()
            healthConnectManager.setSaferExportMode(enabled)
        }

        // Toggle daily manual export section
        switchManualExportReminder.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean("manual_reminder_enabled", enabled).apply()
            layoutManualExportControls.visibility = if (enabled) View.VISIBLE else View.GONE

            if (enabled) {
                ensurePostNotificationsPermissionIfNeeded()

                val hour = prefs.getInt("manual_reminder_hour", -1)
                val min = prefs.getInt("manual_reminder_minute", -1)
                if (hour < 0 || min < 0) {
                    showStatus("Pick a reminder time")
                } else {
                    scheduleManualReminder()
                    showStatus("Daily reminder scheduled")
                }
            } else {
                cancelManualReminder()
                showStatus("Daily reminder disabled")
            }
        }

        btnPickReminderTime.setOnClickListener {
            val now = Calendar.getInstance()
            val initialH = prefs.getInt("manual_reminder_hour", now.get(Calendar.HOUR_OF_DAY))
            val initialM = prefs.getInt("manual_reminder_minute", now.get(Calendar.MINUTE))

            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    prefs.edit()
                        .putInt("manual_reminder_hour", hourOfDay)
                        .putInt("manual_reminder_minute", minute)
                        .apply()
                    updateReminderTimeLabel()

                    if (switchManualExportReminder.isChecked) {
                        ensurePostNotificationsPermissionIfNeeded()
                        scheduleManualReminder()
                    }
                },
                initialH,
                initialM,
                false // 12-hour (AM/PM)
            ).show()
        }

        btnExportAll3Days.setOnClickListener {
            if (!exportInProgress.compareAndSet(false, true)) {
                showStatus("Export already in progress")
                return@setOnClickListener
            }
            saveUiToPrefs()
            exportAllDataLast3DaysToSheets { exportInProgress.set(false) }
        }

        btnExport.setOnClickListener {
            if (!exportInProgress.compareAndSet(false, true)) {
                showStatus("Export already in progress")
                return@setOnClickListener
            }
            saveUiToPrefs()
            val run = { exportToSheets { exportInProgress.set(false) } }
            if (isAllTimeSelected()) showAllTimeWarningDialog { run() } else run()
        }

        btnExportCsv.setOnClickListener {
            if (!exportInProgress.compareAndSet(false, true)) {
                showStatus("Export already in progress")
                return@setOnClickListener
            }
            saveUiToPrefs()
            val run = { exportToCsv { exportInProgress.set(false) } }
            if (isAllTimeSelected()) showAllTimeWarningDialog { run() } else run()
        }

        switchAutoExport.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean("auto_export_enabled", enabled).apply()
            spinnerFrequency.isEnabled = enabled
            saveUiToPrefs()
            if (enabled) scheduleAutoExport() else cancelAutoExport()
        }

        spinnerFrequency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!switchAutoExport.isChecked) return
                scheduleAutoExport()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun requestHealthPermissions() {
        healthPermissionLauncher.launch(
            healthConnectManager.permissions.map { it.toString() }.toTypedArray()
        )
    }

    private fun signInWithGoogle() {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SheetsScopes.SPREADSHEETS))
            .build()

        val client = GoogleSignIn.getClient(this, signInOptions)
        googleSignInLauncher.launch(client.signInIntent)
    }

    private fun saveUiToPrefs() {
        val e = prefs.edit()
        e.putString("spreadsheet_id", editSheetId.text?.toString()?.trim() ?: "")

        e.putBoolean("cat_activity", checkCategoryActivity.isChecked)
        e.putBoolean("cat_body", checkCategoryBodyMeasurements.isChecked)
        e.putBoolean("cat_cycle", checkCategoryCycleTracking.isChecked)
        e.putBoolean("cat_nutrition", checkCategoryNutrition.isChecked)
        e.putBoolean("cat_sleep", checkCategorySleep.isChecked)
        e.putBoolean("cat_vitals", checkCategoryVitals.isChecked)

        e.putBoolean("includeActiveCalories", checkActiveCalories.isChecked)
        e.putBoolean("includeDistance", checkDistance.isChecked)
        e.putBoolean("includeElevationGained", checkElevationGained.isChecked)
        e.putBoolean("includeExercise", checkExercise.isChecked)
        e.putBoolean("includeFloorsClimbed", checkFloorsClimbed.isChecked)
        e.putBoolean("includePower", checkPower.isChecked)
        e.putBoolean("includeSpeed", checkSpeed.isChecked)
        e.putBoolean("includeSteps", checkSteps.isChecked)
        e.putBoolean("includeTotalCalories", checkTotalCalories.isChecked)
        e.putBoolean("includeVO2Max", checkVO2Max.isChecked)
        e.putBoolean("includeWheelchairPushes", checkWheelchairPushes.isChecked)

        e.putBoolean("includeBodyFat", checkBodyFat.isChecked)
        e.putBoolean("includeBoneMass", checkBoneMass.isChecked)
        e.putBoolean("includeHeight", checkHeight.isChecked)
        e.putBoolean("includeLeanBodyMass", checkLeanBodyMass.isChecked)
        e.putBoolean("includeWeight", checkWeight.isChecked)

        e.putBoolean("includeCervicalMucus", checkCervicalMucus.isChecked)
        e.putBoolean("includeMenstruation", checkMenstruation.isChecked)
        e.putBoolean("includeOvulationTest", checkOvulationTest.isChecked)
        e.putBoolean("includeSexualActivity", checkSexualActivity.isChecked)

        e.putBoolean("includeHydration", checkHydration.isChecked)
        e.putBoolean("includeNutrition", checkNutrition.isChecked)

        e.putBoolean("includeSleepSession", checkSleepSession.isChecked)

        e.putBoolean("includeBloodGlucose", checkBloodGlucose.isChecked)
        e.putBoolean("includeBloodPressure", checkBloodPressure.isChecked)
        e.putBoolean("includeBodyTemperature", checkBodyTemperature.isChecked)
        e.putBoolean("includeHeartRate", checkHeartRate.isChecked)
        e.putBoolean("includeHeartRateVariability", checkHeartRateVariability.isChecked)
        e.putBoolean("includeOxygenSaturation", checkOxygenSaturation.isChecked)
        e.putBoolean("includeRespiratoryRate", checkRespiratoryRate.isChecked)
        e.putBoolean("includeRestingHeartRate", checkRestingHeartRate.isChecked)

        e.apply()
    }

    private fun buildConfigFromUi(): ExportConfig {
        return ExportConfig(
            includeActiveCalories = checkActiveCalories.isChecked,
            includeDistance = checkDistance.isChecked,
            includeElevationGained = checkElevationGained.isChecked,
            includeExercise = checkExercise.isChecked,
            includeFloorsClimbed = checkFloorsClimbed.isChecked,
            includePower = checkPower.isChecked,
            includeSpeed = checkSpeed.isChecked,
            includeSteps = checkSteps.isChecked,
            includeTotalCalories = checkTotalCalories.isChecked,
            includeVO2Max = checkVO2Max.isChecked,
            includeWheelchairPushes = checkWheelchairPushes.isChecked,

            includeBodyFat = checkBodyFat.isChecked,
            includeBoneMass = checkBoneMass.isChecked,
            includeHeight = checkHeight.isChecked,
            includeLeanBodyMass = checkLeanBodyMass.isChecked,
            includeWeight = checkWeight.isChecked,

            includeCervicalMucus = checkCervicalMucus.isChecked,
            includeMenstruation = checkMenstruation.isChecked,
            includeOvulationTest = checkOvulationTest.isChecked,
            includeSexualActivity = checkSexualActivity.isChecked,

            includeHydration = checkHydration.isChecked,
            includeNutrition = checkNutrition.isChecked,

            includeSleepSession = checkSleepSession.isChecked,

            includeBloodGlucose = checkBloodGlucose.isChecked,
            includeBloodPressure = checkBloodPressure.isChecked,
            includeBodyTemperature = checkBodyTemperature.isChecked,
            includeHeartRate = checkHeartRate.isChecked,
            includeHeartRateVariability = checkHeartRateVariability.isChecked,
            includeOxygenSaturation = checkOxygenSaturation.isChecked,
            includeRespiratoryRate = checkRespiratoryRate.isChecked,
            includeRestingHeartRate = checkRestingHeartRate.isChecked,

            daysBack = when (spinnerDateRange.selectedItemPosition) {
                0 -> 7
                1 -> 30
                2 -> 90
                3 -> 365
                4 -> -1
                else -> 7
            }
        )
    }

    private fun cacheKeyFor(config: ExportConfig): String =
        "daysBack=${config.daysBack}|safer=${switchSaferExport.isChecked}"

    private suspend fun getDailyDataWithCache(config: ExportConfig): Map<String, DailyHealthData> {
        val now = System.currentTimeMillis()
        val key = cacheKeyFor(config)
        val hit = (cachedKey == key) && (cachedDailyData != null) && (now - cachedAtMs <= cacheTtlMs)
        if (hit) return cachedDailyData!!

        val data = healthConnectManager.readHealthDataByDay(config)
        cachedKey = key
        cachedAtMs = now
        cachedDailyData = data
        return data
    }

    private fun exportToSheets(onFinished: () -> Unit) {
        val sheetId = editSheetId.text?.toString()?.trim().orEmpty()
        if (sheetId.isBlank()) {
            showStatus("Please enter a Google Sheet ID")
            onFinished()
            return
        }

        showProgress(true)
        showStatus("Exporting to Google Sheets...")

        lifecycleScope.launch {
            try {
                val config = buildConfigFromUi()
                val dailyData = getDailyDataWithCache(config)
                val result = sheetsManager.exportToSheets(sheetId, dailyData)

                showProgress(false)
                if (result.isSuccess) {
                    val now = System.currentTimeMillis()
                    prefs.edit().putLong("last_export_manual", now).apply()
                    updateLastExportLabels()
                    showStatus(result.getOrNull() ?: "Export complete")
                } else {
                    showStatus("Export failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}")
                }
            } catch (e: Exception) {
                showProgress(false)
                showStatus("Error: ${e.message}")
            } finally {
                exportInProgress.set(false)
                onFinished()
            }
        }
    }

    private fun exportAllDataLast3DaysToSheets(onFinished: () -> Unit) {
        val sheetId = editSheetId.text?.toString()?.trim().orEmpty()
        if (sheetId.isBlank()) {
            showStatus("Please enter a Google Sheet ID")
            onFinished()
            return
        }

        showProgress(true)
        showStatus("Exporting ALL data (last 3 days)... Keep screen on")

        lifecycleScope.launch {
            try {
                val config = ExportConfig(
                    includeActiveCalories = true,
                    includeDistance = true,
                    includeElevationGained = true,
                    includeExercise = true,
                    includeFloorsClimbed = true,
                    includePower = true,
                    includeSpeed = true,
                    includeSteps = true,
                    includeTotalCalories = true,
                    includeVO2Max = true,
                    includeWheelchairPushes = true,

                    includeBodyFat = true,
                    includeBoneMass = true,
                    includeHeight = true,
                    includeLeanBodyMass = true,
                    includeWeight = true,

                    includeCervicalMucus = true,
                    includeMenstruation = true,
                    includeOvulationTest = true,
                    includeSexualActivity = true,

                    includeHydration = true,
                    includeNutrition = true,

                    includeSleepSession = true,

                    includeBloodGlucose = true,
                    includeBloodPressure = true,
                    includeBodyTemperature = true,
                    includeHeartRate = true,
                    includeHeartRateVariability = true,
                    includeOxygenSaturation = true,
                    includeRespiratoryRate = true,
                    includeRestingHeartRate = true,

                    daysBack = 3
                )

                val dailyData = healthConnectManager.readHealthDataByDay(config)
                val result = sheetsManager.exportToSheets(sheetId, dailyData)

                showProgress(false)
                if (result.isSuccess) {
                    val now = System.currentTimeMillis()
                    prefs.edit().putLong("last_export_manual", now).apply()
                    updateLastExportLabels()
                    showStatus(result.getOrNull() ?: "Export complete")
                } else {
                    showStatus("Export failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}")
                }
            } catch (e: Exception) {
                showProgress(false)
                showStatus("Error: ${e.message}")
            } finally {
                exportInProgress.set(false)
                onFinished()
            }
        }
    }

    private fun exportToCsv(onFinished: () -> Unit) {
        showProgress(true)
        showStatus("Exporting CSV to Downloads...")

        lifecycleScope.launch {
            try {
                val config = buildConfigFromUi()
                val dailyData = getDailyDataWithCache(config)

                val splitMode = when (spinnerCsvSplit.selectedItemPosition) {
                    1 -> CsvExporter.SplitMode.BY_MONTH
                    2 -> CsvExporter.SplitMode.BY_YEAR
                    else -> CsvExporter.SplitMode.NONE
                }

                val exporter = CsvExporter(this@MainActivity)
                val res = exporter.exportSelectedToDownloads(
                    dailyData = dailyData,
                    splitMode = if (isAllTimeSelected()) splitMode else CsvExporter.SplitMode.NONE,
                    includeActivity = dailyData.values.any { it.activityData.isNotEmpty() },
                    includeBody = dailyData.values.any { it.bodyMeasurementData.isNotEmpty() },
                    includeSleep = dailyData.values.any { it.sleepData.isNotEmpty() },
                    includeVitals = dailyData.values.any { it.vitalsData.isNotEmpty() },
                    includeNutrition = dailyData.values.any { it.nutritionData.isNotEmpty() },
                    includeCycle = dailyData.values.any { it.cycleTrackingData.isNotEmpty() }
                )

                showProgress(false)
                if (res.files.isEmpty()) showStatus("No data to export")
                else {
                    val now = System.currentTimeMillis()
                    prefs.edit().putLong("last_export_manual", now).apply()
                    updateLastExportLabels()
                    showStatus("CSV exported: ${res.files.size} file(s) to Downloads")
                }
            } catch (e: Exception) {
                showProgress(false)
                showStatus("Error: ${e.message}")
            } finally {
                exportInProgress.set(false)
                onFinished()
            }
        }
    }

    private fun showAllTimeWarningDialog(onContinue: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("All time export")
            .setMessage(
                "Exporting all time data can take a long time and use more battery/data. " +
                        "It may also hit Google Sheets limits.\n\nContinue?"
            )
            .setPositiveButton("Continue") { _, _ -> onContinue() }
            .setNegativeButton("Cancel") { _, _ -> exportInProgress.set(false) }
            .show()
    }

    private fun scheduleAutoExport() {
        val (interval, unit) = when (spinnerFrequency.selectedItemPosition) {
            0 -> 15L to TimeUnit.MINUTES
            1 -> 30L to TimeUnit.MINUTES
            2 -> 1L to TimeUnit.HOURS
            3 -> 4L to TimeUnit.HOURS
            4 -> 8L to TimeUnit.HOURS
            5 -> 12L to TimeUnit.HOURS
            6 -> 24L to TimeUnit.HOURS
            else -> 1L to TimeUnit.HOURS
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ExportWorker>(interval, unit)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "health_export_work",
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )

        showStatus("Auto-export scheduled: ${spinnerFrequency.selectedItem}")
    }

    private fun cancelAutoExport() {
        WorkManager.getInstance(this).cancelUniqueWork("health_export_work")
        showStatus("Auto-export cancelled")
    }

    private fun scheduleManualReminder() {
        val hour = prefs.getInt("manual_reminder_hour", -1)
        val minute = prefs.getInt("manual_reminder_minute", -1)
        if (hour < 0 || minute < 0) return

        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }

        val delayMs = next.timeInMillis - now.timeInMillis

        val req = OneTimeWorkRequestBuilder<ManualExportReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "manual_export_reminder",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    private fun cancelManualReminder() {
        WorkManager.getInstance(this).cancelUniqueWork("manual_export_reminder")
    }

    private fun showProgress(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        btnExport.isEnabled = !show
        btnExportCsv.isEnabled = !show
        btnExportAll3Days.isEnabled = !show
    }

    private fun showStatus(message: String) {
        txtStatus.text = "Status: $message"
    }
}