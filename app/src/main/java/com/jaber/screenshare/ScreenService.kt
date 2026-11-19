package com.jaber.screenshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLServerSocketFactory
import kotlin.math.roundToInt

private data class Preset(val maxSide: Int, val bpp: Int)

// 0 = سریع، 1 = بالا، 2 = خیلی بالا
private val PRESETS = listOf(Preset(1280, 5), Preset(1920, 7), Preset(2560, 9))

private class EncoderRun(val codec: MediaCodec, val surface: Surface) {
    @Volatile
    var stopped = false
    var thread: Thread? = null
}

class ScreenService : Service() {

    companion object {
        const val ACTION_START = "com.jaber.screenshare.START"
        const val ACTION_STOP = "com.jaber.screenshare.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "share"

        @Volatile
        var running = false

        @Volatile
        var starting = false

        @Volatile
        var urls: List<String> = emptyList()

        @Volatile
        var clientCount = 0

        @Volatile
        var lastError: String? = null
    }

    private lateinit var ctrlThread: HandlerThread
    private lateinit var ctrl: Handler

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null

    @Volatile
    private var enc: EncoderRun? = null
    private var server: WebServer? = null

    @Volatile
    private var preset = 2
    private var capW = 0
    private var capH = 0
    private var lastRealW = 0
    private var lastRealH = 0

    @Volatile
    private var csd: ByteArray? = null
    private var lastKeyReq = 0L
    private var stopping = false

    private var connectivity: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var boundNetwork: Network? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onScreenChanged()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ctrlThread = HandlerThread("ss-ctrl")
        ctrlThread.start()
        ctrl = Handler(ctrlThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ctrl.post { stopAll() }
            }
            ACTION_START -> {
                if (running || starting) return START_NOT_STICKY
                starting = true
                lastError = null
                startFg()
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data == null) {
                    lastError = "اجازهٔ ضبط صفحه دریافت نشد"
                    ctrl.post { stopAll() }
                } else {
                    ctrl.post { begin(code, data) }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!stopping) {
            try {
                stopAll()
            } catch (_: Exception) {
            }
        }
        ctrlThread.quitSafely()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- foreground

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "اشتراک صفحه", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("اشتراک صفحه فعال است")
            .setContentText("برای باز کردن برنامه ضربه بزنید")
            .setContentIntent(openPi)
            .addAction(0, "توقف", stopPi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private fun begin(code: Int, data: Intent) {
        try {
            acquireLocks()

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("MediaProjection ساخته نشد")
            projection = p
            // از اندروید ۱۴ ثبت callback قبل از شروع ضبط اجباری است
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    ctrl.post { stopAll() }
                }
            }, ctrl)

            // مهم: قبل از ساخت سرور، برنامه را به شبکهٔ وای‌فای می‌بندیم تا VPN ترافیکش را نبلعد
            bindToWifi()
            server = createServer()

            startPipeline()

            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.registerDisplayListener(displayListener, ctrl)

            running = true
            starting = false
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            stopAll()
        }
    }

    private fun createServer(): WebServer {
        val factory: SSLServerSocketFactory = CertHelper.serverSocketFactory(this)
        val srv = WebServer(factory, { assets.open("index.html").use { it.readBytes() } }, serverListener)
        val port = srv.start()
        val ips = Util.localAddresses()
        urls = if (ips.isEmpty()) listOf("https://<IP گوشی>:$port") else ips.map { "https://$it:$port" }
        return srv
    }

    /** سرور را دوباره می‌سازد (مثلاً وقتی وای‌فای قطع و وصل شد). فقط روی ترد ctrl. */
    private fun restartServer() {
        if (!running) return
        try {
            server?.stop()
            server = createServer()
            startPipeline()
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            stopAll()
        }
    }

    /**
     * وقتی VPN وصل می‌شود، اندروید ترافیک برنامه‌ها را وارد تونل می‌کند، اتصال‌های باز را می‌بندد
     * و پاسخ‌ها دیگر به وای‌فای برنمی‌گردند. با بستن کل پروسه به شبکهٔ وای‌فای، سرور از VPN مستقل می‌شود.
     */
    @Suppress("DEPRECATION")
    private fun bindToWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity = cm
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    cm.bindProcessToNetwork(n)
                    boundNetwork = n
                    break
                }
            }
        } catch (_: Exception) {
        }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (boundNetwork == network) return
                try {
                    cm.bindProcessToNetwork(network)
                    boundNetwork = network
                } catch (_: Exception) {
                }
                // سوکت شنونده با شبکهٔ قبلی ساخته شده بود؛ دوباره می‌سازیمش
                ctrl.post { restartServer() }
            }

            override fun onLost(network: Network) {
                if (boundNetwork == network) {
                    try {
                        cm.bindProcessToNetwork(null)
                    } catch (_: Exception) {
                    }
                    boundNetwork = null
                }
            }
        }
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                cb
            )
            netCallback = cb
        } catch (_: Exception) {
        }
    }

    private fun stopAll() {
        if (stopping) return
        stopping = true
        running = false
        starting = false
        clientCount = 0
        urls = emptyList()
        try {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        try {
            netCallback?.let { connectivity?.unregisterNetworkCallback(it) }
            connectivity?.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
        netCallback = null
        boundNetwork = null
        enc?.let { stopEncoder(it) }
        enc = null
        try {
            vd?.release()
        } catch (_: Exception) {
        }
        vd = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        releaseLocks()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "screenshare:cpu").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
        // قفل وای‌فای در حالت کم‌تأخیر تا گوشی برای صرفه‌جویی، وای‌فای را کند نکند
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "screenshare:wifi").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        wifiLock = null
    }

    // ---------------------------------------------------------------- server callbacks

    private val serverListener = object : WebServer.Listener {
        override fun onClientOpen(c: WebServer.Client) {
            clientCount = server?.clients?.size ?: 0
            requestKeyFrame()
        }

        override fun onClientClose(c: WebServer.Client) {
            clientCount = server?.clients?.size ?: 0
        }

        override fun onNeedKey() {
            requestKeyFrame()
        }

        override fun onText(c: WebServer.Client, text: String) {
            handleCommand(c, text)
        }
    }

    private fun handleCommand(c: WebServer.Client, text: String) {
        try {
            val j = JSONObject(text)
            when (j.optString("t")) {
                "p" -> {
                    val ctl = InputService.instance != null
                    c.offer(wsFrame(1, """{"t":"s","ctl":$ctl}""".toByteArray()))
                }
                "k" -> requestKeyFrame()
                "q" -> {
                    val v = j.optInt("v", 1).coerceIn(0, PRESETS.size - 1)
                    if (v != preset) {
                        preset = v
                        ctrl.post {
                            try {
                                if (running) startPipeline()
                            } catch (e: Throwable) {
                                lastError = e.message ?: e.javaClass.simpleName
                                stopAll()
                            }
                        }
                    }
                }
                "g" -> InputService.instance?.gesture(j.getJSONArray("p"), j.optLong("d", 50L))
                "a" -> InputService.instance?.action(j.optString("a"))
                "text" -> InputService.instance?.typeText(j.optString("s"))
                "bs" -> InputService.instance?.backspace()
                "enter" -> InputService.instance?.enter()
            }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- capture + encode

    private fun onScreenChanged() {
        if (!running) return
        val r = Util.realSize(this)
        if (r.x != lastRealW || r.y != lastRealH) {
            try {
                startPipeline()
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                stopAll()
            }
        }
    }

    /** انکودر را (دوباره) می‌سازد؛ برای شروع، چرخش صفحه و تغییر کیفیت. فقط روی ترد ctrl. */
    private fun startPipeline() {
        val proj = projection ?: return
        val real = Util.realSize(this)
        lastRealW = real.x
        lastRealH = real.y
        val pr = PRESETS[preset]

        var created: EncoderRun? = null
        var cw = 0
        var ch = 0
        val sides = listOf(pr.maxSide, 1920, 1280, 960, 720).filter { it <= pr.maxSide }.distinct()
        for (side in sides) {
            val (w, h) = fit(real.x, real.y, side)
            val bitrate = (w.toLong() * h * pr.bpp).toInt().coerceIn(1_500_000, 40_000_000)
            val e = createEncoder(w, h, bitrate)
            if (e != null) {
                created = e
                cw = w
                ch = h
                break
            }
        }
        if (created == null) throw IllegalStateException("انکودر H.264 روی این گوشی ساخته نشد")

        val dpi = resources.displayMetrics.densityDpi
        val d = vd
        if (d == null) {
            vd = proj.createVirtualDisplay(
                "ScreenShare", cw, ch, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                created.surface, null, null
            ) ?: throw IllegalStateException("VirtualDisplay ساخته نشد")
        } else {
            d.resize(cw, ch, dpi)
            d.surface = created.surface
        }

        val old = enc
        enc = created
        old?.let { stopEncoder(it) }

        capW = cw
        capH = ch
        csd = null
        server?.resetStream()
        startDrain(created)
    }

    private fun fit(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(w, h))
        fun al(v: Double) = ((v / 16.0).roundToInt() * 16).coerceAtLeast(16)
        return al(w * scale) to al(h * scale)
    }

    private fun createEncoder(w: Int, h: Int, bitrate: Int): EncoderRun? {
        for (variant in 0..2) {
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                f.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if (variant <= 1) {
                    // اگر صفحه ثابت بود هر ۱۰۰ms فریم قبلی تکرار شود (بیننده‌ی جدید زود تصویر بگیرد)
                    f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                }
                if (variant == 0) {
                    f.setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    )
                    f.setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
                    if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LATENCY, 1)
                    f.setFloat("max-fps-to-encoder", 60f)
                }
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()
                return EncoderRun(codec, surface)
            } catch (_: Exception) {
                try {
                    codec?.release()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun stopEncoder(run: EncoderRun) {
        run.stopped = true
        try {
            run.thread?.join(800)
        } catch (_: Exception) {
        }
        try {
            run.codec.stop()
        } catch (_: Exception) {
        }
        try {
            run.codec.release()
        } catch (_: Exception) {
        }
        try {
            run.surface.release()
        } catch (_: Exception) {
        }
    }

    private fun startDrain(run: EncoderRun) {
        val t = Thread({ drainLoop(run) }, "ss-drain")
        t.isDaemon = true
        run.thread = t
        t.start()
    }

    private fun drainLoop(run: EncoderRun) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        val info = MediaCodec.BufferInfo()
        while (!run.stopped) {
            val idx = try {
                run.codec.dequeueOutputBuffer(info, 20_000)
            } catch (e: Exception) {
                break
            }
            if (idx < 0) continue
            try {
                val buf = run.codec.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buf.get(data)
                    onEncoded(data, info.flags)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    run.codec.releaseOutputBuffer(idx, false)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun onEncoded(data: ByteArray, flags: Int) {
        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            setCsd(data)
            return
        }
        val key = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (csd == null) {
            if (key) setCsd(extractParamSets(data) ?: ByteArray(0)) else return
        }
        val c = csd ?: return
        val msg = if (key) wsFrame(2, byteArrayOf(1), c, data) else wsFrame(2, byteArrayOf(0), data)
        server?.broadcastFrame(msg, key)
    }

    private fun setCsd(c: ByteArray) {
        csd = c
        val json = """{"t":"cfg","w":$capW,"h":$capH,"q":$preset,"codec":"${codecString(c)}"}"""
        server?.setConfig(wsFrame(1, json.toByteArray()))
    }

    fun requestKeyFrame() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyReq < 150) return
        lastKeyReq = now
        try {
            val b = Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            enc?.codec?.setParameters(b)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- H.264 helpers

    private fun hex(b: Byte) = String.format("%02x", b.toInt() and 0xFF)

    /** رشتهٔ codec (مثل avc1.640028) را از SPS می‌سازد. */
    private fun codecString(c: ByteArray): String {
        var i = 0
        while (i + 4 < c.size) {
            val sc3 = c[i].toInt() == 0 && c[i + 1].toInt() == 0 && c[i + 2].toInt() == 1
            val sc4 = c[i].toInt() == 0 && c[i + 1].toInt() == 0 && c[i + 2].toInt() == 0 && c[i + 3].toInt() == 1
            val n = if (sc3) i + 3 else if (sc4) i + 4 else -1
            if (n > 0 && n + 3 < c.size && (c[n].toInt() and 0x1F) == 7) {
                return "avc1." + hex(c[n + 1]) + hex(c[n + 2]) + hex(c[n + 3])
            }
            i++
        }
        return "avc1.42e01f"
    }

    /** اگر انکودر SPS/PPS را جدا نداد، از داخل اولین کی‌فریم بیرون می‌کشیم. */
    private fun extractParamSets(d: ByteArray): ByteArray? {
        val starts = ArrayList<Int>()
        val payload = ArrayList<Int>()
        var i = 0
        while (i + 3 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) {
                if (d[i + 2].toInt() == 1) {
                    starts.add(i)
                    payload.add(i + 3)
                    i += 3
                    continue
                }
                if (d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) {
                    starts.add(i)
                    payload.add(i + 4)
                    i += 4
                    continue
                }
            }
            i++
        }
        val out = ByteArrayOutputStream()
        for (k in starts.indices) {
            val end = if (k + 1 < starts.size) starts[k + 1] else d.size
            if (payload[k] >= d.size) continue
            val t = d[payload[k]].toInt() and 0x1F
            if (t == 7 || t == 8) out.write(d, starts[k], end - starts[k])
        }
        return if (out.size() > 0) out.toByteArray() else null
    }
}
