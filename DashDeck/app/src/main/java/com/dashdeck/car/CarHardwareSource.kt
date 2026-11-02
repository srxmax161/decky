package com.dashdeck.car

import android.annotation.SuppressLint
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Speed
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import com.dashdeck.data.TelemetryHub

/** Speed, fuel/charge, range and odometer straight from the car, where the vehicle shares them. */
class CarHardwareSource(private val carContext: CarContext, private val hub: TelemetryHub) {
    private val executor = ContextCompat.getMainExecutor(carContext)
    private var carInfo: CarInfo? = null

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        val value = speed.displaySpeedMetersPerSecond.valueOrNull() ?: speed.rawSpeedMetersPerSecond.valueOrNull()
        hub.update { it.copy(carSpeedMps = value?.let(Math::abs)) }
    }

    private val energyListener = OnCarDataAvailableListener<EnergyLevel> { energy ->
        val fuel = energy.fuelPercent.valueOrNull()
        val battery = energy.batteryPercent.valueOrNull()
        hub.update {
            it.copy(
                energyPct = fuel ?: battery,
                energyIsFuel = fuel != null || battery == null,
                energyLow = energy.energyIsLow.valueOrNull() == true,
                rangeM = energy.rangeRemainingMeters.valueOrNull(),
            )
        }
    }

    private val mileageListener = OnCarDataAvailableListener<Mileage> { mileage ->
        hub.update { it.copy(odometerM = mileage.odometerMeters.valueOrNull()) }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (carInfo != null || carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) return
        val info = runCatching { carContext.getCarService(CarHardwareManager::class.java).carInfo }.getOrNull() ?: return
        carInfo = info
        // Each property needs its own permission; a denied one shouldn't take the others down.
        runCatching { info.addSpeedListener(executor, speedListener) }
        runCatching { info.addEnergyLevelListener(executor, energyListener) }
        runCatching { info.addMileageListener(executor, mileageListener) }
    }

    fun stop() {
        val info = carInfo ?: return
        runCatching { info.removeSpeedListener(speedListener) }
        runCatching { info.removeEnergyLevelListener(energyListener) }
        runCatching { info.removeMileageListener(mileageListener) }
        carInfo = null
        hub.update { it.copy(carSpeedMps = null) }
    }

    fun restart() {
        stop()
        start()
    }

    private fun <T : Any> CarValue<T>.valueOrNull(): T? = if (status == CarValue.STATUS_SUCCESS) value else null
}
