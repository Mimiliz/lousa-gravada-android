package br.com.mimiexplica.lousa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.View
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.max

/** Recebe a imagem do celular (app "Câmera da Lousa") pelo Wi-Fi. */
class RemoteCamera(private val onFrame: (Bitmap) -> Unit, private val onStatus: (String) -> Unit) {
    @Volatile private var running = false
    @Volatile private var sock: Socket? = null
    private val ui = Handler(Looper.getMainLooper())
    var host: String? = null; private set

    fun connect(h: String, port: Int) {
        stop()
        running = true; host = h
        thread(name = "camera-celular") {
            var warned = false
            while (running) {
                try {
                    val s = Socket(); sock = s
                    s.connect(InetSocketAddress(h, port), 3000)
                    s.tcpNoDelay = true
                    ui.post { onStatus("Câmera do celular conectada") }
                    warned = false
                    val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
                    var buf = ByteArray(300_000)
                    val opts = BitmapFactory.Options()
                    while (running) {
                        val n = inp.readInt()
                        if (n <= 0 || n > 8_000_000) break
                        if (n > buf.size) buf = ByteArray(n)
                        inp.readFully(buf, 0, n)
                        val b = BitmapFactory.decodeByteArray(buf, 0, n, opts) ?: continue
                        ui.post { if (running) onFrame(b) else b.recycle() }
                    }
                } catch (_: Exception) {}
                try { sock?.close() } catch (_: Exception) {}
                if (running && !warned) { warned = true; ui.post { onStatus("Procurando o celular… abra o app Câmera da Lousa nele") } }
                if (running) Thread.sleep(1500)
            }
        }
    }

    fun stop() {
        running = false
        try { sock?.close() } catch (_: Exception) {}
        host = null
    }
}

/** Procura na rede os celulares com o "Câmera da Lousa" aberto. */
class PhoneFinder(ctx: Context, private val onFound: (name: String, host: String, port: Int) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val ui = Handler(Looper.getMainLooper())

    fun start() {
        if (listener != null) return
        try { lock = wifi.createMulticastLock("lousa").apply { setReferenceCounted(false); acquire() } } catch (_: Exception) {}
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val h = si.host?.hostAddress ?: return
                            ui.post { onFound(si.serviceName, h, si.port) }
                        }
                    })
                } catch (_: Exception) {}
            }
        }
        listener = l
        try { nsd.discoverServices(CameraSenderActivity.SERVICE, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) { listener = null }
    }

    fun stop() {
        try { listener?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
        listener = null
        try { lock?.release() } catch (_: Exception) {}
    }
}

/** Mostra a imagem do celular preenchendo a janela da câmera. */
class FrameView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    fun setFrame(b: Bitmap) { val old = bmp; bmp = b; invalidate(); if (old != null && old !== b) old.recycle() }
    fun clear() { bmp?.recycle(); bmp = null; invalidate() }
    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        val s = max(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * s; val h = b.height * s
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        c.drawBitmap(b, null, dst, paint)
    }
}
