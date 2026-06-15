package com.msp1974.vacompanion.device

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import timber.log.Timber
import java.util.Timer
import kotlin.collections.iterator
import kotlin.concurrent.timer
import kotlin.math.abs

interface SensorUpdatesCallback {
    fun onUpdate(data: MutableMap<String, Any>)
}

class Sensors(val context: Context, val config: APPConfig, val deviceInfo: DeviceInfo, val cbFunc: SensorUpdatesCallback) {

    var sensorManager: SensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager

    var orientationSensor: String = ""
    var sensorData: MutableMap<String, Any> = mutableMapOf()
    var sensorLastValue: MutableMap<String, Any> = mutableMapOf()
    var timer: Timer? = null

    var hasBattery = false
    var isRawProximitySensor = false
    var lastCalculatedProximity: Float = -1f
    var lastAccel: FloatArray = FloatArray(3)
    var lastBump: Long = 0



    val sensorListener: SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_LIGHT -> {
                    updateFloatSensorData("light", event.values[0], 10f)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    if (System.currentTimeMillis() - lastBump > 2000) {
                        val prevAccel = lastAccel.clone()
                        val currAccel = event.values
                        lastAccel = currAccel.clone()
                        for (i in 0..2) {
                            val diff = currAccel[i] - prevAccel[i]
                            if (abs(prevAccel[i]) > 0 && abs(diff) > config.bumpSensitivity * 2) {
                                Timber.i("Device bump detected -> $i: ${abs(diff)}")
                                lastBump = System.currentTimeMillis()
                                config.eventBroadcaster.notifyEvent(Event("deviceBump", "", ""))
                            }
                        }
                    }
                }
                Sensor.TYPE_PROXIMITY -> {
                    if (isRawProximitySensor)
                    {
                        val calculatedProximity = if (event.values[0] > config.rawProximitySensorThreshold) 0f else 1f
                        if(calculatedProximity != lastCalculatedProximity) {
                            lastCalculatedProximity = calculatedProximity
                            config.eventBroadcaster.notifyEvent(Event("proximity", "", calculatedProximity))
                        }
                    } else {
                        config.eventBroadcaster.notifyEvent(Event("proximity", "", event.values[0]))
                    }
                }
                else -> {
                    Timber.d("Sensor changed - ${event.sensor.type} -> ${event.values}")
                }
            }


        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

        }
    }

    init {
        Timber.d("Starting sensors")
        isRawProximitySensor = deviceInfo.hardware.proximitySensorType == "raw"
        hasBattery = deviceInfo.hardware.hasBattery

        val sensors: Map<String, Int> = mapOf(
            "light" to Sensor.TYPE_LIGHT,
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "proximity" to Sensor.TYPE_PROXIMITY
        )
        for (sensor in sensors) {
            if (registerSensorListener(sensorManager.getDefaultSensor(sensor.value))) {
                Timber.d("Sensor registered - ${sensor.key}")
            } else {
                Timber.d("Sensor not found - ${sensor.key}")
            }
        }

        startIntervalTimer()
    }

    fun updateFloatSensorData(name: String, value: Float, changeRequired: Float) {
        val lastValue = sensorLastValue.getOrDefault(name,-1f) as Float
        if (abs(value - lastValue) >= changeRequired) {
            sensorData.put(name, value)
            sensorLastValue.put(name, value)
        }
    }

    fun updateStringSensorData(name: String, value: String) {
        val lastValue = sensorLastValue.getOrDefault(name,"") as String
        if (value != lastValue) {
        sensorData.put(name, value)
        sensorLastValue.put(name, value)
        }
    }

    fun updateBoolSensorData(name: String, value: Boolean?) {
        val lastValue = sensorLastValue[name] as Boolean?
        var newValue = value
        if (newValue == null) {
            newValue = false
        }
        if (value != lastValue) {
            sensorData.put(name, newValue)
            sensorLastValue.put(name, newValue)
        }
    }

    private fun startIntervalTimer() {
        // Reset timer if already running
        if (timer != null) {
            timer!!.cancel()
            timer = null
        }

        // Start interval timer
        timer = timer(name="sensorTimer", initialDelay = 5000, period = 5000) {
            // Set orientation as not a listener sensor
            val o = getOrientation()
            if (orientationSensor != o) {
                sensorData.put("orientation", o)
                orientationSensor = o

            }

            // Battery info
            if (hasBattery) {
                getBatteryState()
            }

            // run callback if sensor updates
            if (sensorData.isNotEmpty()) {
                cbFunc.onUpdate(data = sensorData)
                sensorData = mutableMapOf()
            }
        }
    }

    fun stop() {
        Timber.d("Stopping sensors")
        sensorManager.unregisterListener(sensorListener)
        if (timer != null) {
            timer!!.cancel()
            timer = null
        }
    }

    fun getOrientation(): String {
         return if (
             context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
         ) "portrait" else "landscape"
    }

    private fun registerSensorListener(sensor: Sensor?): Boolean {
        if(sensor != null) {
            sensorManager.registerListener(
                sensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                1000);
            return true
        } else {
            return false
        }

    }



    private fun getBatteryState() {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val batteryStatusIntExtra = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = batteryStatusIntExtra == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatusIntExtra == BatteryManager.BATTERY_STATUS_FULL
        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
        val acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1

        updateFloatSensorData("battery_level", level.toFloat(), 1f)
        updateBoolSensorData("battery_charging", isCharging)
    }
}

