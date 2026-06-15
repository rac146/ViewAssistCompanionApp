package com.msp1974.vacompanion.data

import kotlinx.serialization.Serializable

/**
 * Data class representing an available wake sound.
 * @param id The filename without extension (e.g., "bell_chime")
 * @param name The formatted display name (e.g., "Bell Chime")
 * @param custom Boolean value to determine if the file is a custom file or built-in asset
 * @param filename The actual filename with extension (e.g., "bell_chime.wav")
 */
@Serializable
data class AvailableWakeSound(
    val id: String,
    val name: String,
    val custom: Boolean,
    val filename: String
)

/**
 * Data class representing an available alarm sound.
 * @param id The filename without extension (e.g., "emergency_alert")
 * @param name The formatted display name (e.g., "Emergency Alert")
 * @param custom Boolean value to determine if the file is a custom file or built-in asset
 * @param filename The actual filename with extension (e.g., "emergency_alert.wav")
 */
@Serializable
data class AvailableAlarm(
    val id: String,
    val name: String,
    val custom: Boolean,
    val filename: String
)
