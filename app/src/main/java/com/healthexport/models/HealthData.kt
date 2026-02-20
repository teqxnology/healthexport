package com.healthexport.models

data class DailyHealthData(
    val date: String,
    val activityData: Map<String, Any?> = emptyMap(),
    val bodyMeasurementData: Map<String, Any?> = emptyMap(),
    val cycleTrackingData: Map<String, Any?> = emptyMap(),
    val nutritionData: Map<String, Any?> = emptyMap(),
    val sleepData: Map<String, Any?> = emptyMap(),
    val vitalsData: Map<String, Any?> = emptyMap()
)

data class ExerciseData(
    val date: String,
    val startDateTime: String,
    val exerciseName: String,
    val durationMinutes: Long
)

data class BodyMeasurementData(
    val dateTime: String,
    val weight: String?,
    val bodyFat: String?
)

data class ExportConfig(
    // Activity
    val includeActiveCalories: Boolean = false,
    val includeDistance: Boolean = false,
    val includeElevationGained: Boolean = false,
    val includeExercise: Boolean = false,
    val includeFloorsClimbed: Boolean = false,
    val includePower: Boolean = false,
    val includeSpeed: Boolean = false,
    val includeSteps: Boolean = false,
    val includeTotalCalories: Boolean = false,
    val includeVO2Max: Boolean = false,
    val includeWheelchairPushes: Boolean = false,

    // Body Measurements
    val includeBodyFat: Boolean = false,
    val includeBoneMass: Boolean = false,
    val includeHeight: Boolean = false,
    val includeLeanBodyMass: Boolean = false,
    val includeWeight: Boolean = false,

    // Cycle Tracking
    val includeCervicalMucus: Boolean = false,
    val includeMenstruation: Boolean = false,
    val includeOvulationTest: Boolean = false,
    val includeSexualActivity: Boolean = false,

    // Nutrition
    val includeHydration: Boolean = false,
    val includeNutrition: Boolean = false,

    // Sleep
    val includeSleepSession: Boolean = false,

    // Vitals
    val includeBloodGlucose: Boolean = false,
    val includeBloodPressure: Boolean = false,
    val includeBodyTemperature: Boolean = false,
    val includeHeartRate: Boolean = false,
    val includeHeartRateVariability: Boolean = false,
    val includeOxygenSaturation: Boolean = false,
    val includeRespiratoryRate: Boolean = false,
    val includeRestingHeartRate: Boolean = false,

    val daysBack: Int = 7
)

data class MinMaxAvg(
    val min: Double? = null,
    val max: Double? = null,
    val avg: Double? = null
)