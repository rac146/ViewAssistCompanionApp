package com.msp1974.vacompanion.wyoming

import com.msp1974.vacompanion.device.DeviceInfo
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class WyomingCapabilitiesBuilder(private val config: APPConfig, private val deviceInfo: DeviceInfo) {

    @OptIn(ExperimentalSerializationApi::class)
    fun buildInfo(): JsonObject {
        val baseCapabilities = deviceInfo.getJson()
        val availableWakeSounds = config.availableWakeSounds
        val availableAlarms = config.availableAlarms

        return buildJsonObject {
           baseCapabilities.forEach { (key, value) -> put(key, value) }
            putJsonArray("wake_sounds") {
                availableWakeSounds.forEach { sound ->
                    add(buildJsonObject {
                        put("id", sound.id)
                        put("name", sound.name)
                    })
                }
            }
            putJsonArray("alarms") {
                availableAlarms.forEach { alarm ->
                    add(buildJsonObject {
                        put("id", alarm.id)
                        put("name", alarm.name)
                    })
                }
            }
        }
    }
}