package com.dashdeck.car

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.CarSensors
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
        logStatus("speed", "display=${speed.displaySpeedMetersPerSecond.status()} raw=${speed.rawSpeedMetersPerSecond.status()}") {
            "display=${speed.displaySpeedMetersPerSecond.describe()} raw=${speed.rawSpeedMetersPerSecond.describe()} " +
                "unit=${speed.speedDisplayUnit.describe()}"
        }
        val value = speed.displaySpeedMetersPerSecond.valueOrNull() ?: speed.rawSpeedMetersPerSecond.valueOrNull()
        hub.update { it.copy(carSpeedMps = value?.let(Math::abs)) }
    }

    private val energyListener = OnCarDataAvailableListener<EnergyLevel> { energy ->
        logOnce(
            "energy", "fuel=${energy.fuelPercent.describe()} battery=${energy.batteryPercent.describe()} " +
                "range=${energy.rangeRemainingMeters.describe()} low=${energy.energyIsLow.describe()}",
        )
        val fuel = energy.fuelPercent.valueOrNull()
        val battery = energy.batteryPercent.valueOrNull()
        val range = energy.rangeRemainingMeters.valueOrNull()
        hub.update {
            it.copy(
                energyPct = fuel ?: battery,
                energyIsFuel = fuel != null || battery == null,
                energyLow = energy.energyIsLow.valueOrNull() == true,
                rangeM = range,
                energyShared = fuel != null || battery != null || range != null ||
                    (stillWaiting() && listOf(energy.fuelPercent, energy.batteryPercent, energy.rangeRemainingMeters)
                        .any { v -> v.status == CarValue.STATUS_UNKNOWN }),
            )
        }
    }

    private val mileageListener = OnCarDataAvailableListener<Mileage> { mileage ->
        logOnce("mileage", "odometer=${mileage.odometerMeters.describe()}")
        val odometer = mileage.odometerMeters.valueOrNull()
        hub.update {
            it.copy(
                odometerM = odometer,
                mileageShared = odometer != null ||
                    (stillWaiting() && mileage.odometerMeters.status == CarValue.STATUS_UNKNOWN),
            )
        }
    }

    private val logged = mutableMapOf<String, String>()
    private var startedAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    // The car may never send another reading, so settle the question when the wait is over.
    private val giveUp = Runnable {
        hub.update {
            it.copy(
                energyShared = if (it.energyPct == null && it.rangeM == null) false else it.energyShared,
                mileageShared = if (it.odometerM == null) false else it.mileageShared,
            )
        }
    }

    /**
     * "Unknown" can mean the first reading hasn't arrived yet, but some cars (BYD) report it for the
     * whole drive, so after a short wait it counts as not shared.
     */
    private fun stillWaiting() = SystemClock.elapsedRealtime() - startedAt < WAIT_MS

    /** Logs what the car reports for each kind of data, once per change, to tell "not shared" from bugs. */
    private fun logOnce(kind: String, detail: String) {
        if (logged.put(kind, detail) != detail) Log.i("DashDeck", "Car $kind: $detail")
    }

    /** For readings that change all the time: logs a sample whenever their [status] changes. */
    private fun logStatus(kind: String, status: String, sample: () -> String) {
        if (logged.put(kind, status) != status) Log.i("DashDeck", "Car $kind: ${sample()}")
    }

    /** Everything else the car might share, logged once, so a drive shows what this car offers. */
    @SuppressLint("MissingPermission")
    private fun survey(info: CarInfo) {
        val manager = carContext.getCarService(CarHardwareManager::class.java)
        val sensors = manager.carSensors
        val rate = CarSensors.UPDATE_RATE_NORMAL
        fun attempt(kind: String, block: () -> Unit) =
            runCatching(block).onFailure { Log.i("DashDeck", "Car $kind: not available (${it.javaClass.simpleName}: ${it.message})") }
        attempt("compass") {
            sensors.addCompassListener(rate, executor) { c -> logStatus("compass", c.orientations.status()) { "orientations=${c.orientations.describe()}" } }
        }
        attempt("accelerometer") {
            sensors.addAccelerometerListener(rate, executor) { a -> logStatus("accelerometer", a.forces.status()) { "forces=${a.forces.describe()}" } }
        }
        attempt("gyroscope") {
            sensors.addGyroscopeListener(rate, executor) { g -> logStatus("gyroscope", g.rotations.status()) { "rotations=${g.rotations.describe()}" } }
        }
        attempt("location") {
            sensors.addCarHardwareLocationListener(rate, executor) { l -> logStatus("location", l.location.status()) { "location=${l.location.describe()}" } }
        }
        attempt("toll card") {
            info.addTollListener(executor) { t -> logOnce("toll card", "state=${t.cardState.describe()}") }
        }
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_7) {
            attempt("dimensions") {
                info.fetchExteriorDimensions(executor) { d -> logOnce("dimensions", "${d.exteriorDimensions.describe()}") }
            }
        }
    }

    private fun CarValue<*>.status(): String = when (status) {
        CarValue.STATUS_SUCCESS -> "shared"
        CarValue.STATUS_UNIMPLEMENTED -> "unimplemented"
        CarValue.STATUS_UNAVAILABLE -> "unavailable"
        else -> "unknown"
    }

    private fun CarValue<*>.describe(): String = when (status) {
        CarValue.STATUS_SUCCESS -> value.let { if (it is FloatArray) it.joinToString(prefix = "[", postfix = "]") else "$it" }
        else -> status()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (carInfo != null) return
        startedAt = SystemClock.elapsedRealtime()
        handler.postDelayed(giveUp, WAIT_MS)
        Log.i("DashDeck", "Car API level ${carContext.carAppApiLevel}")
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) {
            hub.update { it.copy(energyShared = false, mileageShared = false) }
            return
        }
        val info = runCatching { carContext.getCarService(CarHardwareManager::class.java).carInfo }
            .onFailure { Log.w("DashDeck", "No car hardware info", it) }
            .getOrNull() ?: return
        carInfo = info
        runCatching {
            info.fetchModel(executor) { model ->
                Log.i("DashDeck", "Car model: ${model.manufacturer.describe()} ${model.name.describe()} ${model.year.describe()}")
            }
            info.fetchEnergyProfile(executor) { profile ->
                Log.i("DashDeck", "Car energy profile: fuel=${profile.fuelTypes.describe()} ev=${profile.evConnectorTypes.describe()}")
            }
        }
        // Each property needs its own permission; a denied one shouldn't take the others down.
        runCatching { info.addSpeedListener(executor, speedListener) }
        runCatching { info.addEnergyLevelListener(executor, energyListener) }
        runCatching { info.addMileageListener(executor, mileageListener) }
        if (logged.isEmpty()) survey(info)
    }

    fun stop() {
        handler.removeCallbacks(giveUp)
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


    private companion object {
        const val WAIT_MS = 20_000L
    }
}
