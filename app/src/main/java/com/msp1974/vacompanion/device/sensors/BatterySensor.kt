package com.msp1974.vacompanion.device.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.msp1974.vacompanion.device.info.DeviceHardware
import kotlinx.coroutines.launch
import timber.log.Timber

class BatterySensor(private val context: Context) : Sensor {

    companion object {
        internal val basicSensor = Sensor.BasicSensor(
            "battery",
            type = -1, // Not a standard Android sensor type
            name = "Battery Sensor",
        )
    }

    val hasBattery = DeviceHardware(context).hardwareInfo.hasBattery

    override var onUpdate: ((String, Any) -> Unit)? = null
    private var lastLevel = -1f
    private var lastCharging = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                processBatteryIntent(intent)
            }
        }
    }

    init {
        if (hasBattery) {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val stickyIntent = context.registerReceiver(batteryReceiver, intentFilter)
            // Process the initial sticky intent if available
            stickyIntent?.let { processBatteryIntent(it) }
        }
    }

    override fun stop() {
        if (hasBattery) {
            try {
                context.unregisterReceiver(batteryReceiver)
            } catch (e: Exception) {
                Timber.e(e, "Error unregistering battery receiver")
            }
        }
    }

    private fun processBatteryIntent(intent: Intent) {
        val level = intent.let { i ->
            val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (s > 0) l * 100 / s.toFloat() else 0f
        }

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val chargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)

        val currentBatteryState = BatteryState(
            hasBattery = hasBattery,
            onBattery = chargePlug == 0,
            isCharging = isCharging,
            usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB,
            acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC,
            level = level
        )

        if (level != lastLevel || isCharging != lastCharging) {
            lastLevel = level
            lastCharging = isCharging
            Sensor.sensorWorkerScope.launch {
                onSensorUpdated(basicSensor.id, currentBatteryState)
            }
        }
    }

    override fun hasSensor(context: Context): Boolean {
        return hasBattery
    }

    override fun requiredPermissions(): Array<String> {
        return emptyArray()
    }

    override suspend fun getAvailableSensors(context: Context): List<Sensor.BasicSensor> {
        return if (hasBattery) listOf(basicSensor) else emptyList()
    }

    override suspend fun requestSensorUpdate(context: Context) {
        // No-op: Monitoring is handled by BroadcastReceiver
    }
}
