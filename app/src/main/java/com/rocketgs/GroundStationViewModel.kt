package com.rocketgs

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rocketgs.core.Attitude
import com.rocketgs.core.AttitudeSettings
import com.rocketgs.core.Event
import com.rocketgs.core.FlightData
import com.rocketgs.core.Quat
import com.rocketgs.core.SimOptions
import com.rocketgs.core.Tilt
import com.rocketgs.source.Clock
import com.rocketgs.source.ReplaySource
import com.rocketgs.source.SimSource
import com.rocketgs.source.Source
import com.rocketgs.source.UsbSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Owns the flight data and the running source. Sources push Events from their
 * threads into a queue; this drains it on the main thread at most 10 times a
 * second and bumps [version], which is what makes the screen redraw. With no
 * data the screen updates 4 times a second (for the "time since last packet").
 */
class GroundStationViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val queue = ConcurrentLinkedQueue<Event>()
    val data = FlightData()
    val logDir: File = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")

    var version by mutableIntStateOf(0); private set      // bumps when data changed
    var clock by mutableStateOf(0.0); private set         // seconds, 4 Hz
    var source: Source? by mutableStateOf(null); private set
    var status by mutableStateOf("Not running"); private set
    var sourceOk by mutableStateOf(false); private set

    // ---- settings (saved) ----
    var attitude by mutableStateOf(loadAttitude()); private set
    var tare: Quat? by mutableStateOf(null); private set
    var sim by mutableStateOf(SimOptions(
        lossPct = prefs.getFloat("simLoss", 3f).toDouble(), garbagePct = prefs.getFloat("simGarbage", 1f).toDouble()))
        private set
    var plotWindowSec by mutableStateOf(prefs.getFloat("plotWindow", 60f)); private set
    var wholeFlight by mutableStateOf(prefs.getBoolean("wholeFlight", false)); private set
    var keepScreenOn by mutableStateOf(prefs.getBoolean("keepScreenOn", true)); private set

    init {
        viewModelScope.launch {
            var lastBump = 0.0
            while (true) {
                val now = Clock.now()
                var changed = false
                while (true) { val e = queue.poll() ?: break; data.apply(e, now); changed = true }
                source?.let { status = it.status; sourceOk = it.ok }
                if (changed) version++
                if (changed || now - lastBump > 0.25) { clock = now; lastBump = now }
                delay(100)
            }
        }
    }

    // ---- sources ----
    fun startUsb() = start(UsbSource(getApplication<Application>(), logDir, queue))
    fun startSim() = start(SimSource(sim, queue))
    fun startReplay(uri: Uri, speed: Double = 1.0) = start(ReplaySource(getApplication<Application>(), uri, queue).also { it.speed = speed })
    fun stop() { source?.stop(); source = null; status = "Not running"; sourceOk = false }
    fun isUsbRunning() = source is UsbSource

    private fun start(s: Source) {
        stop()
        queue.clear(); data.clear(); tare = null; version++
        source = s
        s.start()
    }

    override fun onCleared() { source?.stop() }

    // ---- derived values for the screen ----
    fun tilt(): Tilt? = data.tm?.let { Attitude.tilt(it.roll, it.pitch, it.yaw, attitude, tare) }

    /** Seconds since launch (first BOOST), from the data's own clock. Null before launch. */
    fun tPlus(): Double? {
        val boost = data.stateChanges.firstOrNull { it.second == 1 }?.first ?: return null
        val last = data.tm?.t ?: return null
        return last - boost
    }

    fun latestLogFile(): File? =
        (source as? UsbSource)?.logFile ?: logDir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }

    // ---- actions ----
    fun setUpright() { data.tm?.let { tare = Attitude.bodyToEnu(it.roll, it.pitch, it.yaw, attitude) } }
    fun clearUpright() { tare = null }
    fun clearData() { data.clear(); version++ }
    fun setGpsOrigin() { data.setOriginToCurrent(); version++ }

    fun updateAttitude(a: AttitudeSettings) {
        attitude = a; tare = null
        prefs.edit().putInt("noseAxis", a.noseAxis).putBoolean("flipRoll", a.flipRoll)
            .putBoolean("flipPitch", a.flipPitch).putBoolean("flipYaw", a.flipYaw).apply()
    }
    fun updateSim(o: SimOptions) {
        sim = o
        prefs.edit().putFloat("simLoss", o.lossPct.toFloat()).putFloat("simGarbage", o.garbagePct.toFloat()).apply()
    }
    fun updatePlot(windowSec: Float, whole: Boolean) {
        plotWindowSec = windowSec; wholeFlight = whole
        prefs.edit().putFloat("plotWindow", windowSec).putBoolean("wholeFlight", whole).apply()
    }
    fun updateKeepScreenOn(on: Boolean) { keepScreenOn = on; prefs.edit().putBoolean("keepScreenOn", on).apply() }
    fun setReplaySpeed(x: Double) { (source as? ReplaySource)?.speed = x }
    fun toggleReplayPause() { (source as? ReplaySource)?.let { it.paused = !it.paused } }

    private fun loadAttitude() = AttitudeSettings(
        noseAxis = prefs.getInt("noseAxis", 0), flipRoll = prefs.getBoolean("flipRoll", false),
        flipPitch = prefs.getBoolean("flipPitch", false), flipYaw = prefs.getBoolean("flipYaw", false))
}
