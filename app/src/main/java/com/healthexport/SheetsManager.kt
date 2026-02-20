package com.healthexport

import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.*
import com.healthexport.models.BodyMeasurementData
import com.healthexport.models.DailyHealthData
import com.healthexport.models.ExerciseData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class SheetsManager(private val context: Context) {

    private var credential: GoogleAccountCredential? = null
    private var sheetsService: Sheets? = null
    private lateinit var healthConnectManager: HealthConnectManager

    fun initializeCredential(accountName: String) {
        credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(SheetsScopes.SPREADSHEETS)
        ).apply { selectedAccountName = accountName }

        sheetsService = Sheets.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Health Connect Exporter")
            .build()

        healthConnectManager = HealthConnectManager(context)
    }

    suspend fun exportToSheets(
        spreadsheetId: String,
        dailyDataMap: Map<String, DailyHealthData>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val service = sheetsService
                ?: return@withContext Result.failure(Exception("Google Sheets not initialized"))

            val hasActivity = dailyDataMap.values.any { it.activityData.isNotEmpty() }
            val hasBodyMeasurements = dailyDataMap.values.any { it.bodyMeasurementData.isNotEmpty() }
            val hasCycleTracking = dailyDataMap.values.any { it.cycleTrackingData.isNotEmpty() }
            val hasNutrition = dailyDataMap.values.any { it.nutritionData.isNotEmpty() }
            val hasSleep = dailyDataMap.values.any { it.sleepData.isNotEmpty() }
            val hasVitals = dailyDataMap.values.any { it.vitalsData.isNotEmpty() }

            ensureSheetsExist(
                service, spreadsheetId,
                hasActivity, hasBodyMeasurements, hasCycleTracking, hasNutrition, hasSleep, hasVitals
            )

            var totalRecords = 0
            if (hasActivity) totalRecords += appendOrUpdateActivityData(service, spreadsheetId, dailyDataMap)
            if (hasBodyMeasurements) totalRecords += appendOrUpdateBodyMeasurementData(service, spreadsheetId, dailyDataMap)
            if (hasCycleTracking) totalRecords += appendOrUpdateCycleTrackingData(service, spreadsheetId, dailyDataMap)
            if (hasNutrition) totalRecords += appendOrUpdateNutritionData(service, spreadsheetId, dailyDataMap)
            if (hasSleep) totalRecords += appendOrUpdateSleepData(service, spreadsheetId, dailyDataMap)
            if (hasVitals) totalRecords += appendOrUpdateVitalsData(service, spreadsheetId, dailyDataMap)

            Result.success("Exported/Updated $totalRecords records")
        } catch (t: Throwable) {
            t.printStackTrace()
            Result.failure(t)
        }
    }

    private fun ensureSheetsExist(
        service: Sheets,
        spreadsheetId: String,
        hasActivity: Boolean,
        hasBodyMeasurements: Boolean,
        hasCycleTracking: Boolean,
        hasNutrition: Boolean,
        hasSleep: Boolean,
        hasVitals: Boolean
    ) {
        try {
            val existingSheets = service.spreadsheets().get(spreadsheetId).execute().sheets
            val existingSheetNames = existingSheets?.map { it.properties.title }?.toSet().orEmpty()

            val requests = mutableListOf<Request>()
            if (hasActivity && "Activity" !in existingSheetNames) requests.add(createSheetRequest("Activity"))
            if (hasBodyMeasurements && "Body Measurements" !in existingSheetNames) requests.add(createSheetRequest("Body Measurements"))
            if (hasCycleTracking && "Cycle Tracking" !in existingSheetNames) requests.add(createSheetRequest("Cycle Tracking"))
            if (hasNutrition && "Nutrition" !in existingSheetNames) requests.add(createSheetRequest("Nutrition"))
            if (hasSleep && "Sleep" !in existingSheetNames) requests.add(createSheetRequest("Sleep"))
            if (hasVitals && "Vitals" !in existingSheetNames) requests.add(createSheetRequest("Vitals"))

            if (requests.isNotEmpty()) {
                val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(requests)
                service.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun createSheetRequest(sheetName: String): Request =
        Request().setAddSheet(AddSheetRequest().setProperties(SheetProperties().setTitle(sheetName)))

    /**
     * date -> row number (1-indexed). If date appears multiple times, last wins.
     */
    private fun getExistingData(
        service: Sheets,
        spreadsheetId: String,
        sheetName: String,
        dateColumnIndex: Int = 0
    ): Map<String, Int> {
        return try {
            val range = "$sheetName!A:A"
            val values = service.spreadsheets().values().get(spreadsheetId, range).execute().getValues().orEmpty()

            val map = LinkedHashMap<String, Int>()
            values.drop(1).forEachIndexed { idx, row ->
                val date = row.getOrNull(dateColumnIndex)?.toString().orEmpty()
                if (date.isNotBlank()) map[date] = idx + 2
            }
            map
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun getRowValues(
        service: Sheets,
        spreadsheetId: String,
        range: String
    ): List<Any> {
        val values = service.spreadsheets().values().get(spreadsheetId, range).execute().getValues().orEmpty()
        return values.firstOrNull().orEmpty()
    }

    /**
     * Merge rule: incoming blanks do not overwrite existing.
     */
    private fun mergeRow(existing: List<Any>, incoming: List<Any>): List<Any> {
        val max = maxOf(existing.size, incoming.size)
        return (0 until max).map { i ->
            val newVal = incoming.getOrNull(i)?.toString().orEmpty()
            if (newVal.isBlank()) existing.getOrNull(i) ?: "" else incoming.getOrNull(i) ?: ""
        }
    }

    // -------------------------
    // Activity (dedupe + 1 row/day unless multiple exercises)
    // -------------------------

    private fun getExistingActivityKeys(
        service: Sheets,
        spreadsheetId: String
    ): Set<String> {
        return try {
            // IMPORTANT: This range must include the Start Date/Time column.
            val range = "Activity!A:H"
            val values = service.spreadsheets().values().get(spreadsheetId, range).execute().getValues().orEmpty()

            values.drop(1).mapNotNull { row ->
                val date = row.getOrNull(0)?.toString().orEmpty()
                val start = row.getOrNull(7)?.toString().orEmpty()
                if (date.isBlank()) null
                else if (start.isBlank()) "$date|NO_EXERCISE"
                else "$date|$start"
            }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun appendOrUpdateActivityData(
        service: Sheets,
        spreadsheetId: String,
        dailyDataMap: Map<String, DailyHealthData>
    ): Int {
        val dataWithActivity = dailyDataMap.filter { it.value.activityData.isNotEmpty() }
        if (dataWithActivity.isEmpty()) return 0

        val timezone = healthConnectManager.getTimeZone()

        // Updated header to include new checkbox-backed fields
        val header = listOf(
            "Date",
            "Source(s)",
            "Timezone",
            "Steps",
            "Distance (m)",
            "Elevation (m)",
            "Floors climbed",
            "Total Calories (kcal)",
            "Active Calories (kcal)",
            "Power min (W)",
            "Power max (W)",
            "Power avg (W)",
            "Speed min (m/s)",
            "Speed max (m/s)",
            "Speed avg (m/s)",
            "VO2 max min (ml/min/kg)",
            "VO2 max max (ml/min/kg)",
            "VO2 max avg (ml/min/kg)",
            "Wheelchair pushes",
            "Start Date/Time",
            "Exercise Name",
            "Duration (min)"
        )

        val existingHeaderValues = try {
            service.spreadsheets().values()
                .get(spreadsheetId, "Activity!1:1")
                .execute()
                .getValues()
        } catch (_: Throwable) {
            null
        }

        val needsHeader = existingHeaderValues.isNullOrEmpty()
        val rowsToAppend = mutableListOf<List<Any>>()
        if (needsHeader) rowsToAppend.add(header)

        val existingKeys = getExistingActivityKeys(service, spreadsheetId)

        fun fmtNum(v: Any?): String =
            when (v) {
                is Double -> String.format(Locale.US, "%.2f", v)
                is Float -> String.format(Locale.US, "%.2f", v)
                is Int -> v.toString()
                is Long -> v.toString()
                else -> ""
            }

        dataWithActivity.entries.sortedBy { it.key }.forEach { (date, data) ->
            val exercises = (data.activityData["Exercises"] as? List<*>)?.filterIsInstance<ExerciseData>().orEmpty()

            val sources = data.activityData["Source(s)"] ?: ""

            val steps = data.activityData["Steps"] ?: ""
            val distance = data.activityData["Distance"] ?: ""
            val elevation = data.activityData["Elevation"] ?: ""
            val floors = data.activityData["Floors climbed"] ?: ""

            val totalCalories = data.activityData["Total calories burned"] ?: ""
            val activeCalories = data.activityData["Active calories burned"] ?: ""

            val pMin = fmtNum(data.activityData["Power min (W)"])
            val pMax = fmtNum(data.activityData["Power max (W)"])
            val pAvg = fmtNum(data.activityData["Power avg (W)"])

            val sMin = fmtNum(data.activityData["Speed min (m/s)"])
            val sMax = fmtNum(data.activityData["Speed max (m/s)"])
            val sAvg = fmtNum(data.activityData["Speed avg (m/s)"])

            val vMin = fmtNum(data.activityData["VO2 max min (ml/min/kg)"])
            val vMax = fmtNum(data.activityData["VO2 max max (ml/min/kg)"])
            val vAvg = fmtNum(data.activityData["VO2 max avg (ml/min/kg)"])

            val pushes = data.activityData["Wheelchair pushes"] ?: ""

            fun baseRow(
                startDateTime: String,
                exerciseName: String,
                durationMinutes: Any
            ): List<Any> = listOf(
                date,
                sources,
                timezone,
                steps,
                distance,
                elevation,
                floors,
                totalCalories,
                activeCalories,
                pMin,
                pMax,
                pAvg,
                sMin,
                sMax,
                sAvg,
                vMin,
                vMax,
                vAvg,
                pushes,
                startDateTime,
                exerciseName,
                durationMinutes
            )

            if (exercises.isNotEmpty()) {
                exercises.forEach { ex ->
                    val key = "$date|${ex.startDateTime}"
                    if (key !in existingKeys) {
                        rowsToAppend.add(baseRow(ex.startDateTime, ex.exerciseName, ex.durationMinutes))
                    }
                }
            } else {
                val key = "$date|NO_EXERCISE"
                if (key !in existingKeys) {
                    rowsToAppend.add(baseRow("", "", ""))
                }
            }
        }

        if (rowsToAppend.isNotEmpty()) {
            val endCol = getColumnLetter(header.size)
            val range = "Activity!A:$endCol"
            service.spreadsheets().values()
                .append(spreadsheetId, range, ValueRange().setValues(rowsToAppend))
                .setValueInputOption("RAW")
                .setInsertDataOption("INSERT_ROWS")
                .execute()
        }

        return rowsToAppend.size - (if (needsHeader) 1 else 0)
    }

    // -------------------------
    // Body Measurements
    // -------------------------

    private fun getExistingDateTimes(service: Sheets, spreadsheetId: String, sheetName: String): Set<String> {
        return try {
            val range = "$sheetName!A:A"
            val values = service.spreadsheets().values().get(spreadsheetId, range).execute().getValues().orEmpty()
            values.drop(1).mapNotNull { row -> row.getOrNull(0)?.toString() }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun appendOrUpdateBodyMeasurementData(
        service: Sheets,
        spreadsheetId: String,
        dailyDataMap: Map<String, DailyHealthData>
    ): Int {
        val dataWithMeasurements = dailyDataMap.filter { it.value.bodyMeasurementData.isNotEmpty() }
        if (dataWithMeasurements.isEmpty()) return 0

        val timezone = healthConnectManager.getTimeZone()

        // Updated header to include extra keys emitted by HealthConnectManager
        // NOTE: Weight/BodyFat are still emitted via the Measurements list; the extra fields are
        // stored as strings in the map.
        val header = listOf(
            "Date/Time",
            "Source(s)",
            "Timezone",
            "Weight (kg)",
            "Body Fat (%)",
            "Bone mass (kg)",
            "Height (m)",
            "Lean body mass (kg)"
        )

        val existingHeaderValues = try {
            service.spreadsheets().values()
                .get(spreadsheetId, "Body Measurements!1:1")
                .execute()
                .getValues()
        } catch (_: Throwable) {
            null
        }

        val needsHeader = existingHeaderValues.isNullOrEmpty()
        val rowsToAppend = mutableListOf<List<Any>>()
        if (needsHeader) rowsToAppend.add(header)

        val existingDateTimes = getExistingDateTimes(service, spreadsheetId, "Body Measurements")

        dataWithMeasurements.entries.sortedBy { it.key }.forEach { (_, data) ->
            val measurements = data.bodyMeasurementData["Measurements"] as? List<*>
            val sources = data.bodyMeasurementData["Source(s)"] ?: ""

            // Extra fields are stored as day-level strings ("dt=value, dt=value")
            // We'll put them on each row as the same day-level text (simple + consistent).
            val bone = data.bodyMeasurementData["Bone mass (kg)"] ?: ""
            val height = data.bodyMeasurementData["Height (m)"] ?: ""
            val lean = data.bodyMeasurementData["Lean body mass (kg)"] ?: ""

            measurements?.filterIsInstance<BodyMeasurementData>()?.forEach { m ->
                if (m.dateTime !in existingDateTimes) {
                    rowsToAppend.add(
                        listOf(
                            m.dateTime,
                            sources,
                            timezone,
                            m.weight ?: "",
                            m.bodyFat ?: "",
                            bone,
                            height,
                            lean
                        )
                    )
                }
            }
        }

        if (rowsToAppend.isNotEmpty()) {
            val endCol = getColumnLetter(header.size)
            val range = "Body Measurements!A:$endCol"
            service.spreadsheets().values()
                .append(spreadsheetId, range, ValueRange().setValues(rowsToAppend))
                .setValueInputOption("RAW")
                .setInsertDataOption("INSERT_ROWS")
                .execute()
        }

        return rowsToAppend.size - (if (needsHeader) 1 else 0)
    }

    // -------------------------
    // Cycle Tracking / Nutrition (dynamic columns)
    // -------------------------

    private fun getColumnLetter(columnNumber: Int): String {
        var num = columnNumber
        var result = ""
        while (num > 0) {
            val remainder = (num - 1) % 26
            result = ('A' + remainder) + result
            num = (num - 1) / 26
        }
        return result
    }

    private fun appendOrUpdateCycleTrackingData(
        service: Sheets,
        spreadsheetId: String,
        dailyDataMap: Map<String, DailyHealthData>
    ): Int {
        val dataWithCycle = dailyDataMap.filter { it.value.cycleTrackingData.isNotEmpty() }
        if (dataWithCycle.isEmpty()) return 0

        val timezone = healthConnectManager.getTimeZone()
        val allColumns = dataWithCycle.values.flatMap { it.cycleTrackingData.keys }.toSet().sorted()
        val header = mutableListOf<Any>("Date", "Source(s)", "Timezone").apply { addAll(allColumns) }

        val existingHeaderValues = try {
            service.spreadsheets().values().get(spreadsheetId, "Cycle Tracking!1:1").execute().getValues()
        } catch (_: Throwable) {
            null
        }

        val needsHeader = existingHeaderValues.isNullOrEmpty()
        val rowsToAppend = mutableListOf<List<Any>>()
        if (needsHeader) rowsToAppend.add(header)

        val existingDates = getExistingData(service, spreadsheetId, "Cycle Tracking")

        dataWithCycle.entries.sortedBy { it.key }.forEach { (date, data) ->
            if (date !in existingDates.keys) {
                val sources = data.cycleTrackingData["Source(s)"] ?: ""
                val row = mutableListOf<Any>(date, sources, timezone)
                allColumns.forEach { col -> row.add(data.cycleTrackingData[col] ?: "") }
                rowsToAppend.add(row)
            }
        }

        if (rowsToAppend.isNotEmpty()) {
            val range = "Cycle Tracking!A:${getColumnLetter(header.size)}"
            service.spreadsheets().values()
                .append(spreadsheetId, range, ValueRange().setValues(rowsToAppend))
                .setValueInputOption("RAW")
                .setInsertDataOption("INSERT_ROWS")
                .execute()
        }

        return rowsToAppend.size - (if (needsHeader) 1 else 0)
    }

    private fun appendOrUpdateNutritionData(
        service: Sheets,
        spreadsheetId: String,
        dailyDataMap: Map<String, DailyHealthData>
    ): Int {
        val dataWithNutrition = dailyDataMap.filter { it.value.nutritionData.isNotEmpty() }
        if (dataWithNutrition.isEmpty()) return 0

        val timezone = healthConnectManager.getTimeZone()
        val allColumns = dataWithNutrition.values.flatMap { it.nutritionData.keys }.toSet().sorted()
        val header = mutableListOf<Any>("Date", "Source(s)", "Timezone").apply { addAll(allColumns) }

        val existingHeaderValues = try {
            service.spreadsheets().values().get(spreadsheetId, "Nutrition!1:1").execute().getValues()
        } catch (_: Throwable) {
            null
        }

        val needsHeader = existingHeaderValues.isNullOrEmpty()
        val rowsToAppend = mutableListOf<List<Any>>()
        if (needsHeader) rowsToAppend.add(header)

        val existingDates = getExistingData(service, spreadsheetId, "Nutrition")

        dataWithNutrition.entries.sortedBy { it.key }.forEach { (date, data) ->
            if (date !in existingDates.keys) {
                val sources = data.nutritionData["Source(s)"] ?: ""
                val row = mutableListOf<Any>(date, sources, timezone)
                allColumns.forEach { col -> row.add(data.nutritionData[col] ?: "") }
                rowsToAppend.add(row)
            }
        }

        if (rowsToAppend.isNotEmpty()) {
            val range = "Nutrition!A:${getColumnLetter(header.size)}"
            service.spreadsheets().values()
                .append(spreadsheetId, range, ValueRange().setValues(rowsToAppend))
                .setValueInputOption("RAW")
                .setInsertDataOption("INSERT_ROWS")
                .execute()
        }

        return rowsToAppend.size - (if (needsHeader) 1 else 0)
    }

    // -------------------------
    // Sleep
    // -------------------------

    private fun appendOrUpdateSleepData(
        service: Sheets,
        spreadsheetId: String,
        dailyDataMap: Map<String, DailyHealthData>
    ): Int {
        val dataWithSleep = dailyDataMap.filter { it.value.sleepData.isNotEmpty() }
        if (dataWithSleep.isEmpty()) return 0

        val timezone = healthConnectManager.getTimeZone()

        val header = listOf(
            "Date", "Source(s)", "Timezone", "Start Time", "End Time",
            "Light Sleep (min)", "Deep Sleep (min)", "REM Sleep (min)", "Awake (min)"
        )

        val existingHeaderValues = try {
            service.spreadsheets().values().get(spreadsheetId, "Sleep!1:1").execute().getValues()
        } catch (_: Throwable) {
            null
        }

        val needsHeader = existingHeaderValues.isNullOrEmpty()
        val rowsToAppend = mutableListOf<List<Any>>()
        if (needsHeader) rowsToAppend.add(header)

        val existingDates = getExistingData(service, spreadsheetId, "Sleep")

        dataWithSleep.entries.sortedBy { it.key }.forEach { (date, data) ->
            if (date !in existingDates.keys) {
                val sources = data.sleepData["Source(s)"] ?: ""
                rowsToAppend.add(
                    listOf(
                        date, sources, timezone,
                        data.sleepData["Start time"] ?: "",
                        data.sleepData["End time"] ?: "",
                        data.sleepData["Light sleep (min)"] ?: "",
                        data.sleepData["Deep sleep (min)"] ?: "",
                        data.sleepData["REM sleep (min)"] ?: "",
                        data.sleepData["Awake (min)"] ?: ""
                    )
                )
            }
        }

        if (rowsToAppend.isNotEmpty()) {
            service.spreadsheets().values()
                .append(spreadsheetId, "Sleep!A:I", ValueRange().setValues(rowsToAppend))
                .setValueInputOption("RAW")
                .setInsertDataOption("INSERT_ROWS")
                .execute()
        }

        return rowsToAppend.size - (if (needsHeader) 1 else 0)
    }

    // -------------------------
    // Vitals (merge-update so blanks don't wipe)
    // -------------------------

    private fun appendOrUpdateVitalsData(
        service: Sheets,
        spreadsheetId: String,
        dailyDataMap: Map<String, DailyHealthData>
    ): Int {
        val dataWithVitals = dailyDataMap.filter { it.value.vitalsData.isNotEmpty() }
        if (dataWithVitals.isEmpty()) return 0

        val timezone = healthConnectManager.getTimeZone()

        val header = listOf(
            "Date", "Source(s)", "Timezone",
            "Heart rate min (bpm)", "Heart rate max (bpm)", "Heart rate avg (bpm)",
            "Heart rate variability min (ms)", "Heart rate variability max (ms)", "Heart rate variability avg (ms)",
            "Oxygen saturation min (%)", "Oxygen saturation max (%)", "Oxygen saturation avg (%)",
            "Respiratory rate min (breaths/min)", "Respiratory rate max (breaths/min)", "Respiratory rate avg (breaths/min)",
            "Resting heart rate min (bpm)", "Resting heart rate max (bpm)", "Resting heart rate avg (bpm)",
            "Blood pressure (mmHg)", "Blood glucose (mmol/L)", "Body temperature (°C)"
        )

        val existingHeaderValues = try {
            service.spreadsheets().values().get(spreadsheetId, "Vitals!1:1").execute().getValues()
        } catch (_: Throwable) {
            null
        }

        val needsHeader = existingHeaderValues.isNullOrEmpty()
        val rowsToAppend = mutableListOf<List<Any>>()
        if (needsHeader) rowsToAppend.add(header)

        val existingDates = getExistingData(service, spreadsheetId, "Vitals")

        dataWithVitals.entries.sortedBy { it.key }.forEach { (date, data) ->
            val vitals = data.vitalsData
            val sources = vitals["Source(s)"] ?: ""

            val incomingRow = listOf(
                date,
                sources,
                timezone,
                formatNumber(vitals["Heart rate min (bpm)"]),
                formatNumber(vitals["Heart rate max (bpm)"]),
                formatNumber(vitals["Heart rate avg (bpm)"]),
                formatNumber(vitals["Heart rate variability min (ms)"]),
                formatNumber(vitals["Heart rate variability max (ms)"]),
                formatNumber(vitals["Heart rate variability avg (ms)"]),
                formatNumber(vitals["Oxygen saturation min (%)"]),
                formatNumber(vitals["Oxygen saturation max (%)"]),
                formatNumber(vitals["Oxygen saturation avg (%)"]),
                formatNumber(vitals["Respiratory rate min (breaths/min)"]),
                formatNumber(vitals["Respiratory rate max (breaths/min)"]),
                formatNumber(vitals["Respiratory rate avg (breaths/min)"]),
                formatNumber(vitals["Resting heart rate min (bpm)"]),
                formatNumber(vitals["Resting heart rate max (bpm)"]),
                formatNumber(vitals["Resting heart rate avg (bpm)"]),
                vitals["Blood pressure (mmHg)"] ?: "",
                vitals["Blood glucose (mmol/L)"] ?: "",
                vitals["Body temperature (°C)"] ?: ""
            )

            val existingRow = existingDates[date]
            if (existingRow != null) {
                val range = "Vitals!A$existingRow:U$existingRow"
                val existingRowValues = try { getRowValues(service, spreadsheetId, range) } catch (_: Throwable) { emptyList() }
                val merged = if (existingRowValues.isNotEmpty()) mergeRow(existingRowValues, incomingRow) else incomingRow

                service.spreadsheets().values()
                    .update(spreadsheetId, range, ValueRange().setValues(listOf(merged)))
                    .setValueInputOption("RAW")
                    .execute()
            } else {
                rowsToAppend.add(incomingRow)
            }
        }

        if (rowsToAppend.isNotEmpty()) {
            service.spreadsheets().values()
                .append(spreadsheetId, "Vitals!A:U", ValueRange().setValues(rowsToAppend))
                .setValueInputOption("RAW")
                .setInsertDataOption("INSERT_ROWS")
                .execute()
        }

        return rowsToAppend.size - (if (needsHeader) 1 else 0)
    }

    private fun formatNumber(value: Any?): String {
        return when (value) {
            is Double -> String.format(Locale.US, "%.2f", value)
            is Float -> String.format(Locale.US, "%.2f", value)
            is Int -> value.toString()
            is Long -> value.toString()
            else -> ""
        }
    }
}