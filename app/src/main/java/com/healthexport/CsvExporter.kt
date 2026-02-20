package com.healthexport

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.healthexport.models.BodyMeasurementData
import com.healthexport.models.DailyHealthData
import com.healthexport.models.ExerciseData
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class CsvExporter(private val context: Context) {

    enum class SplitMode { NONE, BY_MONTH, BY_YEAR }

    data class ExportResult(
        val files: List<Pair<String, Uri>> // displayName -> uri
    )

    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val monthFmt = DateTimeFormatter.ofPattern("yyyy-MM")
    private val yearFmt = DateTimeFormatter.ofPattern("yyyy")

    fun exportSelectedToDownloads(
        dailyData: Map<String, DailyHealthData>,
        splitMode: SplitMode,
        includeActivity: Boolean,
        includeBody: Boolean,
        includeSleep: Boolean,
        includeVitals: Boolean,
        includeNutrition: Boolean,
        includeCycle: Boolean
    ): ExportResult {
        val out = mutableListOf<Pair<String, Uri>>()

        if (includeActivity) out += exportActivity(dailyData, splitMode)
        if (includeBody) out += exportBody(dailyData, splitMode)
        if (includeSleep) out += exportSleep(dailyData, splitMode)
        if (includeVitals) out += exportVitals(dailyData, splitMode)
        if (includeNutrition) out += exportNutrition(dailyData, splitMode)
        if (includeCycle) out += exportCycle(dailyData, splitMode)

        return ExportResult(out)
    }

    // -------------------------
    // Splitting helpers
    // -------------------------

    private fun keyForSplit(date: String, mode: SplitMode): String {
        val ld = LocalDate.parse(date, dayFmt)
        return when (mode) {
            SplitMode.NONE -> "ALL"
            SplitMode.BY_MONTH -> ld.format(monthFmt)
            SplitMode.BY_YEAR -> ld.format(yearFmt)
        }
    }

    private fun fileSuffixForKey(mode: SplitMode, key: String): String =
        when (mode) {
            SplitMode.NONE -> ""
            SplitMode.BY_MONTH -> "_$key"
            SplitMode.BY_YEAR -> "_$key"
        }

    // -------------------------
    // CSV writing
    // -------------------------

    private fun csvEscape(v: Any?): String {
        val s = v?.toString().orEmpty()
        val needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")
        if (!needsQuotes) return s
        return "\"" + s.replace("\"", "\"\"") + "\""
    }

    private fun downloadsCollectionUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            // API 28 fallback
            MediaStore.Files.getContentUri("external")
        }
    }

    private fun writeCsvToDownloads(displayName: String, header: List<String>, rows: List<List<Any>>): Uri {
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Put into the Downloads folder on API 29+
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                // On API 28, RELATIVE_PATH is not supported. It will go to a default shared location.
                // (Still accessible from file managers / Downloads apps depending on OEM.)
            }
        }

        val uri = resolver.insert(downloadsCollectionUri(), values)
            ?: throw IllegalStateException("Failed to create CSV")

        resolver.openOutputStream(uri, "w")!!.use { os ->
            OutputStreamWriter(os, StandardCharsets.UTF_8).use { w ->
                w.write(header.joinToString(",") { csvEscape(it) })
                w.write("\n")
                rows.forEach { row ->
                    w.write(row.joinToString(",") { csvEscape(it) })
                    w.write("\n")
                }
                w.flush()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return uri
    }

    private fun fmtNum(v: Any?): String =
        when (v) {
            is Double -> String.format(Locale.US, "%.2f", v)
            is Float -> String.format(Locale.US, "%.2f", v)
            is Int -> v.toString()
            is Long -> v.toString()
            else -> ""
        }

    // -------------------------
    // Activity
    // -------------------------

    private fun exportActivity(
        dailyData: Map<String, DailyHealthData>,
        splitMode: SplitMode
    ): List<Pair<String, Uri>> {

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

        val tz = HealthConnectManager(context).getTimeZone()
        val groups = mutableMapOf<String, MutableList<List<Any>>>()

        dailyData.entries.sortedBy { it.key }.forEach { (date, d) ->
            if (d.activityData.isEmpty()) return@forEach

            val key = keyForSplit(date, splitMode)
            val list = groups.getOrPut(key) { mutableListOf() }

            val exercises = (d.activityData["Exercises"] as? List<*>)?.filterIsInstance<ExerciseData>().orEmpty()

            val sources = d.activityData["Source(s)"] ?: ""

            val steps = d.activityData["Steps"] ?: ""
            val distance = d.activityData["Distance"] ?: ""
            val elevation = d.activityData["Elevation"] ?: ""
            val floors = d.activityData["Floors climbed"] ?: ""

            val totalCalories = d.activityData["Total calories burned"] ?: ""
            val activeCalories = d.activityData["Active calories burned"] ?: ""

            val pMin = fmtNum(d.activityData["Power min (W)"])
            val pMax = fmtNum(d.activityData["Power max (W)"])
            val pAvg = fmtNum(d.activityData["Power avg (W)"])

            val sMin = fmtNum(d.activityData["Speed min (m/s)"])
            val sMax = fmtNum(d.activityData["Speed max (m/s)"])
            val sAvg = fmtNum(d.activityData["Speed avg (m/s)"])

            val vMin = fmtNum(d.activityData["VO2 max min (ml/min/kg)"])
            val vMax = fmtNum(d.activityData["VO2 max max (ml/min/kg)"])
            val vAvg = fmtNum(d.activityData["VO2 max avg (ml/min/kg)"])

            val pushes = d.activityData["Wheelchair pushes"] ?: ""

            fun baseRow(start: String, exName: String, duration: Any): List<Any> = listOf(
                date,
                sources,
                tz,
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
                start,
                exName,
                duration
            )

            if (exercises.isNotEmpty()) {
                exercises.forEach { ex ->
                    list += baseRow(ex.startDateTime, ex.exerciseName, ex.durationMinutes)
                }
            } else {
                list += baseRow("", "", "")
            }
        }

        return groups.entries.sortedBy { it.key }.map { (k, rows) ->
            val suffix = fileSuffixForKey(splitMode, k)
            val name = "Activity$suffix.csv"
            name to writeCsvToDownloads(name, header, rows)
        }
    }

    // -------------------------
    // Body Measurements
    // -------------------------

    private fun exportBody(
        dailyData: Map<String, DailyHealthData>,
        splitMode: SplitMode
    ): List<Pair<String, Uri>> {

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

        val tz = HealthConnectManager(context).getTimeZone()
        val groups = mutableMapOf<String, MutableList<List<Any>>>()

        dailyData.entries.sortedBy { it.key }.forEach { (day, d) ->
            val measurements = d.bodyMeasurementData["Measurements"] as? List<*>
            if (measurements.isNullOrEmpty()) return@forEach

            val key = keyForSplit(day, splitMode)
            val list = groups.getOrPut(key) { mutableListOf() }

            val sources = d.bodyMeasurementData["Source(s)"] ?: ""
            val bone = d.bodyMeasurementData["Bone mass (kg)"] ?: ""
            val height = d.bodyMeasurementData["Height (m)"] ?: ""
            val lean = d.bodyMeasurementData["Lean body mass (kg)"] ?: ""

            measurements.filterIsInstance<BodyMeasurementData>().forEach { m ->
                list += listOf(
                    m.dateTime,
                    sources,
                    tz,
                    m.weight ?: "",
                    m.bodyFat ?: "",
                    bone,
                    height,
                    lean
                )
            }
        }

        return groups.entries.sortedBy { it.key }.map { (k, rows) ->
            val suffix = fileSuffixForKey(splitMode, k)
            val name = "Body_Measurements$suffix.csv"
            name to writeCsvToDownloads(name, header, rows)
        }
    }

    // -------------------------
    // Sleep
    // -------------------------

    private fun exportSleep(
        dailyData: Map<String, DailyHealthData>,
        splitMode: SplitMode
    ): List<Pair<String, Uri>> {
        val header = listOf(
            "Date", "Source(s)", "Timezone", "Start Time", "End Time",
            "Light Sleep (min)", "Deep Sleep (min)", "REM Sleep (min)", "Awake (min)"
        )
        val tz = HealthConnectManager(context).getTimeZone()

        val groups = mutableMapOf<String, MutableList<List<Any>>>()

        dailyData.entries.sortedBy { it.key }.forEach { (date, d) ->
            if (d.sleepData.isEmpty()) return@forEach

            val key = keyForSplit(date, splitMode)
            val list = groups.getOrPut(key) { mutableListOf() }
            val sources = d.sleepData["Source(s)"] ?: ""

            list += listOf(
                date,
                sources,
                tz,
                d.sleepData["Start time"] ?: "",
                d.sleepData["End time"] ?: "",
                d.sleepData["Light sleep (min)"] ?: "",
                d.sleepData["Deep sleep (min)"] ?: "",
                d.sleepData["REM sleep (min)"] ?: "",
                d.sleepData["Awake (min)"] ?: ""
            )
        }

        return groups.entries.sortedBy { it.key }.map { (k, rows) ->
            val suffix = fileSuffixForKey(splitMode, k)
            val name = "Sleep$suffix.csv"
            name to writeCsvToDownloads(name, header, rows)
        }
    }

    // -------------------------
    // Vitals
    // -------------------------

    private fun exportVitals(
        dailyData: Map<String, DailyHealthData>,
        splitMode: SplitMode
    ): List<Pair<String, Uri>> {
        val header = listOf(
            "Date", "Source(s)", "Timezone",
            "Heart rate min (bpm)", "Heart rate max (bpm)", "Heart rate avg (bpm)",
            "Heart rate variability min (ms)", "Heart rate variability max (ms)", "Heart rate variability avg (ms)",
            "Oxygen saturation min (%)", "Oxygen saturation max (%)", "Oxygen saturation avg (%)",
            "Respiratory rate min (breaths/min)", "Respiratory rate max (breaths/min)", "Respiratory rate avg (breaths/min)",
            "Resting heart rate min (bpm)", "Resting heart rate max (bpm)", "Resting heart rate avg (bpm)",
            "Blood pressure (mmHg)", "Blood glucose (mmol/L)", "Body temperature (°C)"
        )

        val tz = HealthConnectManager(context).getTimeZone()
        val groups = mutableMapOf<String, MutableList<List<Any>>>()

        dailyData.entries.sortedBy { it.key }.forEach { (date, d) ->
            if (d.vitalsData.isEmpty()) return@forEach

            val key = keyForSplit(date, splitMode)
            val list = groups.getOrPut(key) { mutableListOf() }

            val v = d.vitalsData
            val sources = v["Source(s)"] ?: ""

            list += listOf(
                date,
                sources,
                tz,
                fmtNum(v["Heart rate min (bpm)"]),
                fmtNum(v["Heart rate max (bpm)"]),
                fmtNum(v["Heart rate avg (bpm)"]),
                fmtNum(v["Heart rate variability min (ms)"]),
                fmtNum(v["Heart rate variability max (ms)"]),
                fmtNum(v["Heart rate variability avg (ms)"]),
                fmtNum(v["Oxygen saturation min (%)"]),
                fmtNum(v["Oxygen saturation max (%)"]),
                fmtNum(v["Oxygen saturation avg (%)"]),
                fmtNum(v["Respiratory rate min (breaths/min)"]),
                fmtNum(v["Respiratory rate max (breaths/min)"]),
                fmtNum(v["Respiratory rate avg (breaths/min)"]),
                fmtNum(v["Resting heart rate min (bpm)"]),
                fmtNum(v["Resting heart rate max (bpm)"]),
                fmtNum(v["Resting heart rate avg (bpm)"]),
                v["Blood pressure (mmHg)"] ?: "",
                v["Blood glucose (mmol/L)"] ?: "",
                v["Body temperature (°C)"] ?: ""
            )
        }

        return groups.entries.sortedBy { it.key }.map { (k, rows) ->
            val suffix = fileSuffixForKey(splitMode, k)
            val name = "Vitals$suffix.csv"
            name to writeCsvToDownloads(name, header, rows)
        }
    }

    // -------------------------
    // Nutrition / Cycle Tracking (dynamic columns)
    // -------------------------

    private fun exportNutrition(
        dailyData: Map<String, DailyHealthData>,
        splitMode: SplitMode
    ): List<Pair<String, Uri>> {
        val tz = HealthConnectManager(context).getTimeZone()

        val hasAny = dailyData.values.any { it.nutritionData.isNotEmpty() }
        if (!hasAny) return emptyList()

        val allColumns = dailyData.values.flatMap { it.nutritionData.keys }.toSet().sorted()
        val header = listOf("Date", "Source(s)", "Timezone") + allColumns

        val groups = mutableMapOf<String, MutableList<List<Any>>>()

        dailyData.entries.sortedBy { it.key }.forEach { (date, d) ->
            if (d.nutritionData.isEmpty()) return@forEach
            val key = keyForSplit(date, splitMode)
            val list = groups.getOrPut(key) { mutableListOf() }

            val sources = d.nutritionData["Source(s)"] ?: ""
            val row = mutableListOf<Any>(date, sources, tz)
            allColumns.forEach { c -> row.add(d.nutritionData[c] ?: "") }
            list += row
        }

        return groups.entries.sortedBy { it.key }.map { (k, rows) ->
            val suffix = fileSuffixForKey(splitMode, k)
            val name = "Nutrition$suffix.csv"
            name to writeCsvToDownloads(name, header, rows)
        }
    }

    private fun exportCycle(
        dailyData: Map<String, DailyHealthData>,
        splitMode: SplitMode
    ): List<Pair<String, Uri>> {
        val tz = HealthConnectManager(context).getTimeZone()

        val hasAny = dailyData.values.any { it.cycleTrackingData.isNotEmpty() }
        if (!hasAny) return emptyList()

        val allColumns = dailyData.values.flatMap { it.cycleTrackingData.keys }.toSet().sorted()
        val header = listOf("Date", "Source(s)", "Timezone") + allColumns

        val groups = mutableMapOf<String, MutableList<List<Any>>>()

        dailyData.entries.sortedBy { it.key }.forEach { (date, d) ->
            if (d.cycleTrackingData.isEmpty()) return@forEach
            val key = keyForSplit(date, splitMode)
            val list = groups.getOrPut(key) { mutableListOf() }

            val sources = d.cycleTrackingData["Source(s)"] ?: ""
            val row = mutableListOf<Any>(date, sources, tz)
            allColumns.forEach { c -> row.add(d.cycleTrackingData[c] ?: "") }
            list += row
        }

        return groups.entries.sortedBy { it.key }.map { (k, rows) ->
            val suffix = fileSuffixForKey(splitMode, k)
            val name = "Cycle_Tracking$suffix.csv"
            name to writeCsvToDownloads(name, header, rows)
        }
    }
}