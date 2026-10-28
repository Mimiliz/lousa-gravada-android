package br.com.mimiexplica.lousa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * "Câmera da Lousa": abre no celular e envia a imagem da câmera pelo Wi-Fi para o tablet.
 * Cada quadro vai como JPEG: 4 bytes com o tamanho + os bytes da imagem.
 */
class CameraSenderActivity : AppCompatActivity() {

    companion object {
        const val PORT = 8765
        const val SERVICE = "_lousacam._tcp."
    }

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private var server: ServerSocket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var client: Socket? = null
    private var front = true
    private val analysisExec = Executors.newSingleThreadExecutor()
    private var nsd: NsdManager? = null
    private var reg: NsdManager.RegistrationListener? = null
    private val jpeg = ByteArrayOutputStream(256 * 1024)

    private val askCam = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else status.text = "Sem permissão para usar a câmera."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 40, 40, 60); setBackgroundColor(0xCC14161B.toInt())
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER }
        panel.addView(status)
        val swap = TextView(this).apply {
            text = "Trocar câmera (frente / trás)"; setTextColor(0xFF06281D.toInt()); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(40, 28, 40, 28)
            background = GradientDrawable().apply { setColor(0xFF37C99A.toInt()); cornerRadius = 60f }
            setOnClickListener { front = !front; startCamera() }
        }
        panel.addView(swap, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 28 })
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        startServer()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCam.launch(Manifest.permission.CAMERA)
    }

    private fun ip(): String {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress ?: ""
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun showStatus() {
        val addr = ip()
        runOnUiThread {
            status.text = if (client != null) "Conectado ao tablet. Pode deixar o celular apontado para você."
            else if (addr.isEmpty()) "Ligue o Wi-Fi: o celular e o tablet precisam estar na mesma rede."
            else "Aguardando o tablet…\nNo tablet: Ajustes › Câmera › escolha este celular.\nEndereço deste celular: $addr"
        }
    }

    private fun startServer() {
        thread(name = "servidor") {
            try {
                val s = ServerSocket(); s.reuseAddress = true; s.bind(java.net.InetSocketAddress(PORT)); server = s
                showStatus()
                while (!s.isClosed) {
                    val c = s.accept()
                    c.tcpNoDelay = true
                    try { client?.close() } catch (_: Exception) {}
                    client = c
                    out = DataOutputStream(BufferedOutputStream(c.getOutputStream(), 1 shl 16))
                    showStatus()
                }
            } catch (_: Exception) {}
        }
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = "Celular " + Build.MODEL
            serviceType = SERVICE
            port = PORT
        }
        reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        try { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg) } catch (_: Exception) {}
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get()
            p.unbindAll()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val res = ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            ).build()
            val an = ImageAnalysis.Builder()
                .setResolutionSelector(res)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            an.setAnalyzer(analysisExec) { img ->
                val o = out
                if (o == null) { img.close(); return@setAnalyzer }
                try {
                    var bmp = img.toBitmap()
                    val rot = img.imageInfo.rotationDegrees
                    val big = max(bmp.width, bmp.height)
                    val k = if (big > 1280) 1280f / big else 1f
                    if (rot != 0 || k != 1f) {
                        val m = Matrix(); m.postScale(k, k); m.postRotate(rot.toFloat())
                        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                        if (r !== bmp) bmp.recycle()
                        bmp = r
                    }
                    jpeg.reset()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 72, jpeg)
                    bmp.recycle()
                    o.writeInt(jpeg.size())
                    jpeg.writeTo(o)
                    o.flush()
                } catch (e: Exception) {
                    try { client?.close() } catch (_: Exception) {}
                    client = null; out = null
                    showStatus()
                } finally { img.close() }
            }
            val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try { p.bindToLifecycle(this, sel, pv, an) } catch (e: Exception) { status.text = "Não consegui abrir a câmera: ${e.message}" }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        try { reg?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        analysisExec.shutdown()
        super.onDestroy()
    }
}
