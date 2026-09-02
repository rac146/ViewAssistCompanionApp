package com.msp1974.vacompanion.device.sensors

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.hardware.Sensor as AndroidSensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SensorManager.SENSOR_DELAY_NORMAL
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

class AccelerometerSensor(
    context: Context,
    private val config: APPConfig,
) : Sensor, SensorEventListener {

    companion object {
        private var isListenerRegistered = false
        private var listenerLastRegistered = 0L
        private var lastAccel = FloatArray(3)
        private var lastBump = 0L

        internal val basicSensor = Sensor.BasicSensor(
            "accelerometer",
            type = AndroidSensor.TYPE_ACCELEROMETER,
            name = "Accelerometer Sensor",
        )
    }

    private var mySensorManager: SensorManager? = null
    override var onUpdate: ((String, Any) -> Unit)? = null

    init {
        mySensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = mySensorManager?.getDefaultSensor(AndroidSensor.TYPE_ACCELEROMETER)
        if (sensor != null && !isListenerRegistered) {
            mySensorManager?.registerListener(this, sensor, SENSOR_DELAY_NORMAL)
            isListenerRegistered = true
            listenerLastRegistered = System.currentTimeMillis()
            Timber.d("Accelerometer sensor listener registered in init")
        }
    }

    override fun hasSensor(context: Context): Boolean {
        val sm = context.getSystemService(SENSOR_SERVICE) as SensorManager
        return sm.getDefaultSensor(AndroidSensor.TYPE_ACCELEROMETER) != null
    }

    override fun requiredPermissions(): Array<String> {
        return emptyArray()
    }

    override suspend fun getAvailableSensors(context: Context): List<Sensor.BasicSensor> {
        return listOf(basicSensor)
    }

    override suspend fun requestSensorUpdate(context: Context) {
        // No-op: Monitoring is handled by listener registered in init
    }

    override fun onAccuracyChanged(sensor: AndroidSensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        val currAccel = event?.values ?: return
        val now = System.currentTimeMillis()

        if (now - lastBump > 2000) {
            val prevAccel = lastAccel.clone()
            lastAccel = currAccel.clone()
            for (i in 0..2) {
                val diff = currAccel[i] - prevAccel[i]
                if (abs(prevAccel[i]) > 0 && abs(diff) > config.bumpSensitivity * 2) {
                    Timber.i("Device bump detected -> $i: ${abs(diff)}")
                    lastBump = now
                    config.eventBroadcaster.notifyEvent(Event("deviceBump", "", ""))
                    
                    val newState = AccelerometerState(
                        lastBump = now,
                        acceleration = currAccel.clone()
                    )

                    Sensor.sensorWorkerScope.launch {
                        onSensorUpdated(basicSensor.id, newState)
                    }
                    break
                }
            }
        }
    }

    override fun stop() {
        mySensorManager?.unregisterListener(this)
        isListenerRegistered = false
    }
}
