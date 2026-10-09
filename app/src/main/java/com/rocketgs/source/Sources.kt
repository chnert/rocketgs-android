package com.rocketgs.source

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.rocketgs.core.Config
import com.rocketgs.core.Event
import com.rocketgs.core.FlightSim
import com.rocketgs.core.Level
import com.rocketgs.core.LinePipeline
import com.rocketgs.core.SimOptions
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/** Seconds since the app started (monotonic). */
object Clock {
    private val t0 = SystemClock.elapsedRealtime()
    fun now() = (SystemClock.elapsedRealtime() - t0) / 1000.0
}

/**
 * Every data source runs on its own thread and drops Events into [queue];
 * the ViewModel drains it on the UI thread. Same design as the desktop app.
 */
abstract class Source(protected val queue: ConcurrentLinkedQueue<Event>) {
    @Volatile var status = "starting"; protected set
    @Volatile var ok = false; protected set
    @Volatile var badBlocks = 0; protected set
    @Volatile protected var running = true
    private var th: Thread? = null
    abstract val kind: String

    fun start() { th = thread(name = "source-$kind") { work() } }
    fun stop() { running = false; th?.join(1500); th = null }
    protected abstract fun work()

    protected fun publish(out: MutableList<Event>) { queue.addAll(out); out.clear() }
    protected fun notice(level: Level, text: String) { queue.add(Event.Notice(Clock.now(), level, text)) }
}

/**
 * The real receiver on USB-OTG. Waits for the device, asks for permission once,
 * reconnects automatically after unplug/replug, and writes a raw log.
 */
class UsbSource(private val ctx: Context, private val logDir: File, q: ConcurrentLinkedQueue<Event>) : Source(q) {
    override val kind = "USB"
    @Volatile var logFile: File? = null; private set

    override fun work() {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val buf = ByteArray(4096)
        val out = ArrayList<Event>()
        var pipeline: LinePipeline? = null
        var askedFor: UsbDevice? = null

        while (running) {
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()
            if (driver == null) {
                status = "Plug in the receiver (USB-OTG adapter)"; ok = false
                askedFor = null
                SystemClock.sleep(700); continue
            }
            val dev = driver.device
            if (!usb.hasPermission(dev)) {
                status = "Allow USB access in the dialog"; ok = false
                if (askedFor?.deviceId != dev.deviceId) { requestPermission(usb, dev); askedFor = dev }
                SystemClock.sleep(500); continue
            }
            val conn = usb.openDevice(dev)
            if (conn == null) { status = "Cannot open USB device"; SystemClock.sleep(1000); continue }
            val port: UsbSerialPort = driver.ports[0]
            try {
                port.open(conn)
                port.setParameters(Config.USB_BAUD, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                try { port.setDTR(true) } catch (_: Exception) {}   // genuine Uno resets here: normal
            } catch (e: IOException) {
                status = "USB open failed: ${e.message}"
                try { port.close() } catch (_: Exception) {}
                SystemClock.sleep(1000); continue
            }

            // One log per session, kept across reconnects.
            val pipe = pipeline ?: run {
                logDir.mkdirs()
                val f = File(logDir, SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".log")
                val w = try { FileWriter(f) } catch (e: IOException) { notice(Level.ERROR, "Cannot write log: ${e.message}"); null }
                if (w != null) logFile = f
                LinePipeline(w)
            }
            pipeline = pipe
            status = "Connected ${driver.javaClass.simpleName.removeSuffix("SerialDriver")} @ ${Config.USB_BAUD}"; ok = true
            notice(Level.INFO, "Receiver connected (the Arduino resets when the port opens)")

            try {
                while (running) {
                    val n = port.read(buf, 200)
                    val t = Clock.now()
                    if (n > 0) pipe.feedBytes(buf, n, t, out)
                    pipe.tick(t, out)
                    badBlocks = pipe.parser.badBlocks
                    if (out.isNotEmpty()) publish(out)
                }
            } catch (e: IOException) {
                ok = false
                status = "Disconnected - waiting for receiver"
                notice(Level.ERROR, "USB connection lost: ${e.message ?: "unplugged"}")
            } finally {
                try { port.close() } catch (_: Exception) {}
            }
        }
        pipeline?.close()
        ok = false; status = "stopped"
    }

    private fun requestPermission(usb: UsbManager, dev: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent("com.rocketgs.USB_PERMISSION").setPackage(ctx.packageName)
        usb.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, intent, flags))
    }
}

/** Replays a log written by this app or the desktop app, or any plain capture. */
class ReplaySource(private val ctx: Context, private val uri: Uri, q: ConcurrentLinkedQueue<Event>) : Source(q) {
    override val kind = "Replay"
    @Volatile var speed = 1.0
    @Volatile var paused = false
    @Volatile var progress = 0f; private set

    override fun work() {
        val out = ArrayList<Event>()
        val pipe = LinePipeline(null)
        val total: Long = try {
            val fd = ctx.contentResolver.openAssetFileDescriptor(uri, "r")
            val len: Long = fd?.length ?: -1L      // -1 when the provider does not know
            fd?.close()
            len
        } catch (_: Exception) { -1L }
        val input = try { ctx.contentResolver.openInputStream(uri) } catch (e: Exception) { null }
        if (input == null) { status = "Cannot open file"; return }
        status = "Replaying"; ok = true
        var read = 0L; var synth = 0.0; var first = -1.0; var dataT = 0.0; var lastWall = Clock.now()
        BufferedReader(InputStreamReader(input)).use { r ->
            while (running) {
                val raw = r.readLine() ?: break
                read += raw.length + 1
                if (raw.startsWith("#")) continue
                val (ts0, text) = LinePipeline.splitLogLine(raw.removeSuffix("\r"))
                val ts = if (ts0 != null) ts0 else { synth += 0.02; synth }
                if (first < 0) first = ts
                val target = ts - first
                while (running && dataT < target) {      // wait until the replay clock reaches this line
                    val now = Clock.now()
                    if (!paused) dataT += (now - lastWall) * speed
                    lastWall = now
                    if (dataT < target) { pipe.tick(dataT, out); publish(out); SystemClock.sleep(20) }
                }
                pipe.feedLine(text, target, out)
                pipe.tick(target, out)
                badBlocks = pipe.parser.badBlocks
                publish(out)
                if (total > 0L) progress = (read.toFloat() / total).coerceAtMost(1f)
            }
        }
        progress = 1f; ok = false
        status = if (running) "Replay finished" else "stopped"
    }
}

/** Built-in simulated ~6 km flight printing the receiver's exact text. */
class SimSource(private val opt: SimOptions, q: ConcurrentLinkedQueue<Event>) : Source(q) {
    override val kind = "Simulator"
    override fun work() {
        val out = ArrayList<Event>()
        val pipe = LinePipeline(null)
        var sim = FlightSim(opt, (System.nanoTime() and 0x7FFFFFFF).toInt())
        val lines = ArrayList<String>()
        val periodMs = (1000.0 / opt.telemHz).toLong()
        var next = SystemClock.elapsedRealtime()
        status = "Simulated flight"; ok = true
        while (running) {
            if (sim.finished) sim = FlightSim(opt, (System.nanoTime() and 0x7FFFFFFF).toInt())
            lines.clear(); sim.step(lines)
            val t = Clock.now()
            for (l in lines) pipe.feedLine(l, t, out)
            pipe.tick(t, out)
            badBlocks = pipe.parser.badBlocks
            publish(out)
            next += periodMs
            val wait = next - SystemClock.elapsedRealtime()
            if (wait > 0) SystemClock.sleep(wait)
        }
        ok = false; status = "stopped"
    }
}
