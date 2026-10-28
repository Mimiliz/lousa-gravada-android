package br.com.mimiexplica.lousa

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.hardware.camera2.CameraCharacteristics
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var board: BoardView
    private lateinit var bubble: CameraBubble
    private lateinit var boardArea: FrameLayout
    private lateinit var recBtn: TextView
    private lateinit var pauseBtn: ImageView
    private lateinit var timerTv: TextView
    private lateinit var pageTv: TextView
    private lateinit var countTv: TextView
    private val toolViews = HashMap<Tool, ImageView>()
    private val colorViews = ArrayList<View>()
    private val sizeViews = ArrayList<View>()
    private lateinit var undoV: ImageView
    private lateinit var redoV: ImageView

    private val ui = Handler(Looper.getMainLooper())
    private val COLORS = intArrayOf(0xFF14161A.toInt(), 0xFFFFFFFF.toInt(), 0xFFE5484D.toInt(), 0xFF2F7BF5.toInt(),
        0xFF1FA463.toInt(), 0xFFF2B705.toInt(), 0xFFA35BF0.toInt())
    private val SIZES = floatArrayOf(4f, 8f, 15f)
    private val ACCENT = 0xFF37C99A.toInt()
    private val CHROME = 0xFF14161B.toInt()
    private val RAISED = 0xFF272B34.toInt()
    private val MUTED = 0xFF8E96A5.toInt()

    // câmera
    private var provider: ProcessCameraProvider? = null
    private var camInfos: List<CameraInfo> = emptyList()
    private var camId: String? = null
    private var camOn = true
    private var camPct = 0.32f
    private var camCx = Float.NaN
    private var camCy = Float.NaN
    // celulares com o app "Câmera da Lousa" encontrados na rede: nome -> (endereço, porta)
    private val phones = LinkedHashMap<String, Pair<String, Int>>()
    private lateinit var remote: RemoteCamera
    private lateinit var finder: PhoneFinder
    private val SITE = "https://mimiliz.github.io/lousa-camera/"
    private val sala: String by lazy {
        prefs.getString("sala", null) ?: novoCodigo().also { prefs.edit().putString("sala", it).apply() }
    }
    private fun novoCodigo(): String {
        val abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { abc[(Math.random() * abc.length).toInt()] }.joinToString("")
    }
    private fun salaAtual() = prefs.getString("sala", null) ?: sala
    private fun linkAluno() = SITE + "?sala=" + salaAtual()

    // gravação
    private enum class Rec { PARADO, GRAVANDO, PAUSADO }
    private var rec = Rec.PARADO
    private var recStart = 0L
    private var recAcc = 0L

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).roundToInt()

    private val askAudio = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { askScreen() }
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startCamera() }
    private val screenCapture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) countdownThenRecord(res.resultCode, res.data!!)
        else toast("Gravação cancelada")
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) importFiles(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("lousa", MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        camPct = prefs.getFloat("camPct", 0.32f)
        camId = prefs.getString("camId", null)
        camOn = prefs.getBoolean("camOn", true)

        board = BoardView(this)
        board.setFormat(prefs.getBoolean("vertical", false))
        board.fundo = Fundo.valueOf(prefs.getString("fundo", "BRANCO")!!)
        board.color = board.fundo.ink
        board.size = SIZES[1]
        board.fingerDraws = prefs.getBoolean("dedo", false)
        board.onChange = { updateUi() }
        board.onLayoutBoard = { layoutCamera() }
        board.onMessage = { toast(it) }

        bubble = CameraBubble(this)
        bubble.round = prefs.getBoolean("camRedonda", true)
        bubble.mirror = prefs.getBoolean("espelhar", false)
        bubble.onGesture = { dx, dy, sc ->
            val s = board.scale
            if (camCx.isNaN()) defaultCamPos()
            camCx += dx / s; camCy += dy / s
            camPct = (camPct * sc).coerceIn(0.12f, 0.7f)
            layoutCamera()
        }
        bubble.onGestureEnd = { prefs.edit().putFloat("camPct", camPct).apply() }

        boardArea = FrameLayout(this)
        boardArea.addView(board, FrameLayout.LayoutParams(-1, -1))
        boardArea.addView(bubble, FrameLayout.LayoutParams(dpi(160f), dpi(160f)))
        countTv = TextView(this).apply {
            textSize = 140f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setShadowLayer(30f, 0f, 6f, 0x99000000.toInt()); visibility = View.GONE
        }
        boardArea.addView(countTv, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0D0F13.toInt()) }
        col.addView(buildTopBar(), LinearLayout.LayoutParams(-1, dpi(60f)))
        col.addView(boardArea, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(buildToolBar(), LinearLayout.LayoutParams(-1, dpi(64f)))
        setContentView(col)

        RecordService.onFinished = { uri, msg -> onRecordFinished(uri, msg) }
        RecordService.onStarted = { onRecordStarted() }

        remote = RemoteCamera({ b -> bubble.remote.setFrame(b) }, { m -> toast(m) })
        finder = PhoneFinder(this) { name, host, port ->
            val isNew = !phones.containsKey(name)
            phones[name] = host to port
            // se o celular escolhido da última vez apareceu, conecta sozinho
            if (camOn && camId == "celular:$name" && remote.host == null) useRemote(name)
            else if (isNew && camOn && camId == null && rec == Rec.PARADO) toast("$name encontrado: escolha-o em Ajustes › Câmera")
        }
        finder.start()
        bubble.onWebStatus = { t -> toast(t) }
        bubble.micAllowed = { prefs.getBoolean("conversa", true) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }

        if (camOn) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            else askCamera.launch(Manifest.permission.CAMERA)
        } else bubble.visibility = View.GONE
        updateUi()
    }

    override fun onDestroy() {
        remote.stop(); finder.stop(); bubble.destroyWeb()
        board.release()
        RecordService.onFinished = null; RecordService.onStarted = null
        super.onDestroy()
    }

    // ---------------- interface ----------------

    private fun icon(path: String, desc: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageDrawable(IconDrawable(path))
        contentDescription = desc
        setPadding(dpi(11f), dpi(11f), dpi(11f), dpi(11f))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dpi(50f), dpi(50f)).apply { marginEnd = dpi(2f) }
    }

    private fun sep(): View = View(this).apply {
        setBackgroundColor(0xFF323744.toInt())
        layoutParams = LinearLayout.LayoutParams(dpi(1f), dpi(30f)).apply { marginStart = dpi(8f); marginEnd = dpi(8f) }
    }

    private fun pill(color: Int, radius: Float = 999f, strokeColor: Int = 0, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius); if (stroke > 0) setStroke(stroke, strokeColor)
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CHROME); setPadding(dpi(16f), 0, dpi(10f), 0)
        }
        bar.addView(TextView(this).apply {
            text = "Lousa Gravada"; setTextColor(0xFFECEEF2.toInt()); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        timerTv = TextView(this).apply {
            text = "00:00"; setTextColor(MUTED); textSize = 16f; typeface = Typeface.MONOSPACE
            setPadding(0, 0, dpi(10f), 0)
        }
        bar.addView(timerTv)
        pauseBtn = icon(Icons.PAUSA, "Pausar") { togglePause() }
        pauseBtn.setImageDrawable(IconDrawable(Icons.PAUSA, filled = true))
        pauseBtn.visibility = View.GONE
        bar.addView(pauseBtn)
        recBtn = TextView(this).apply {
            text = "●  Gravar"; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; background = pill(0xFFFF4A4F.toInt())
            setPadding(dpi(20f), 0, dpi(20f), 0)
            setOnClickListener { onRecClick() }
        }
        bar.addView(recBtn, LinearLayout.LayoutParams(-2, dpi(44f)).apply { marginStart = dpi(6f); marginEnd = dpi(6f) })
        bar.addView(icon(Icons.CAMERA, "Mostrar ou esconder a câmera") { toggleCamera() })
        bar.addView(icon(Icons.AJUSTES, "Ajustes") { openSettings() })
        return bar
    }

    private fun buildToolBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(10f), 0, dpi(10f), 0)
        }
        val tools = listOf(Tool.CANETA to Icons.CANETA, Tool.MARCA to Icons.MARCA, Tool.RETA to Icons.RETA,
            Tool.MOVER to Icons.MOVER, Tool.BORRACHA to Icons.BORRACHA)
        val names = mapOf(Tool.CANETA to "Caneta", Tool.MARCA to "Marca-texto", Tool.RETA to "Linha reta",
            Tool.MOVER to "Mover e redimensionar a imagem", Tool.BORRACHA to "Borracha")
        for ((t, p) in tools) {
            val v = icon(p, names[t]!!) { board.tool = t; updateUi() }
            toolViews[t] = v; row.addView(v)
        }
        row.addView(sep())
        for (c in COLORS) {
            val v = View(this).apply {
                background = pill(c, strokeColor = 0x33FFFFFF, stroke = dpi(2f))
                contentDescription = "Cor"
                setOnClickListener { board.color = c; if (board.tool == Tool.BORRACHA || board.tool == Tool.MOVER) board.tool = Tool.CANETA; updateUi() }
                layoutParams = LinearLayout.LayoutParams(dpi(34f), dpi(34f)).apply { marginStart = dpi(4f); marginEnd = dpi(4f) }
            }
            colorViews.add(v); row.addView(v)
        }
        row.addView(sep())
        for ((i, s) in SIZES.withIndex()) {
            val holder = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpi(42f), dpi(50f))
                setOnClickListener { board.size = SIZES[i]; updateUi() }
                contentDescription = listOf("Fina", "Média", "Grossa")[i]
            }
            val d = dpi(6f + i * 5f)
            holder.addView(View(this).apply { background = pill(MUTED) }, FrameLayout.LayoutParams(d, d, Gravity.CENTER))
            sizeViews.add(holder); row.addView(holder)
        }
        row.addView(sep())
        undoV = icon(Icons.DESFAZER, "Desfazer") { board.undo() }; row.addView(undoV)
        redoV = icon(Icons.REFAZER, "Refazer") { board.redo() }; row.addView(redoV)
        row.addView(icon(Icons.LIXEIRA, "Limpar a escrita da página") { confirm("Apagar tudo o que foi escrito nesta página?", "Limpar") { board.clearPage() } })
        row.addView(sep())
        row.addView(icon(Icons.ANTERIOR, "Página anterior") { board.prev() })
        pageTv = TextView(this).apply { setTextColor(MUTED); textSize = 15f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD }
        row.addView(pageTv, LinearLayout.LayoutParams(dpi(52f), -1))
        row.addView(icon(Icons.PROXIMA, "Próxima página") { board.next() })
        row.addView(icon(Icons.NOVA, "Nova página") { board.addPage() })
        row.addView(icon(Icons.IMPORTAR, "Importar PDF ou imagem") { pickFiles.launch(arrayOf("application/pdf", "image/*")) })
        row.addView(icon(Icons.EXCLUIR, "Excluir esta página") { confirm("Excluir esta página inteira, com a imagem e a escrita?", "Excluir") { board.deletePage() } })
        return HorizontalScrollView(this).apply {
            setBackgroundColor(CHROME); isHorizontalScrollBarEnabled = false
            addView(row, FrameLayout.LayoutParams(-2, -1))
        }
    }

    private fun updateUi() {
        for ((t, v) in toolViews) {
            val on = board.tool == t
            v.background = if (on) pill(RAISED, 12f, ACCENT, dpi(1.5f)) else null
            (v.drawable as IconDrawable).color = if (on) ACCENT else MUTED
        }
        for ((i, v) in colorViews.withIndex()) {
            val on = COLORS[i] == board.color && board.tool != Tool.BORRACHA
            v.background = pill(COLORS[i], strokeColor = if (on) ACCENT else 0x33FFFFFF, stroke = dpi(if (on) 3f else 2f))
            v.scaleX = if (on) 1.12f else 1f; v.scaleY = v.scaleX
        }
        for ((i, v) in sizeViews.withIndex()) {
            val on = SIZES[i] == board.size
            v.background = if (on) pill(RAISED, 10f) else null
            ((v as FrameLayout).getChildAt(0)).background = pill(if (on) ACCENT else MUTED)
        }
        undoV.alpha = if (board.page.strokes.isEmpty()) 0.35f else 1f
        redoV.alpha = if (board.page.redo.isEmpty()) 0.35f else 1f
        pageTv.text = "${board.pi + 1}/${board.pages.size}"
    }

    private fun confirm(msg: String, action: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton(action) { _, _ -> ok() }.setNegativeButton("Cancelar", null).show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------------- câmera ----------------

    private fun defaultCamPos() {
        val (w, h) = camSize()
        camCx = board.W - 36f - w / 2; camCy = board.H - 36f - h / 2
    }

    private fun camSize(): Pair<Float, Float> {
        val base = camPct * min(board.W, board.H)
        return if (bubble.round) base to base else (base * 16f / 9f).coerceAtMost(board.W * 0.6f) to base
    }

    private fun layoutCamera() {
        if (camCx.isNaN()) defaultCamPos()
        val (w, h) = camSize()
        camCx = camCx.coerceIn(w / 2, board.W - w / 2)
        camCy = camCy.coerceIn(h / 2, board.H - h / 2)
        val s = board.scale
        val r = board.boardRectInView
        if (s <= 0f) return
        val lp = bubble.layoutParams as FrameLayout.LayoutParams
        lp.width = (w * s).roundToInt(); lp.height = (h * s).roundToInt()
        bubble.layoutParams = lp
        bubble.x = r.left + (camCx - w / 2) * s
        bubble.y = r.top + (camCy - h / 2) * s
    }

    private fun toggleCamera() {
        camOn = !camOn
        prefs.edit().putBoolean("camOn", camOn).apply()
        if (camOn) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            else askCamera.launch(Manifest.permission.CAMERA)
        } else { provider?.unbindAll(); remote.stop(); bubble.showRemote(false); bubble.visibility = View.GONE }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun idOf(info: CameraInfo): String = try { Camera2CameraInfo.from(info).cameraId } catch (e: Exception) { "?" }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun facingOf(info: CameraInfo): Int? = try {
        Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
    } catch (e: Exception) { null }

    private fun camLabel(info: CameraInfo): String = when (facingOf(info)) {
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "Câmera USB (${idOf(info)})"
        CameraCharacteristics.LENS_FACING_FRONT -> "Câmera frontal do tablet"
        CameraCharacteristics.LENS_FACING_BACK -> "Câmera traseira do tablet"
        else -> "Câmera ${idOf(info)}"
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = try { f.get() } catch (e: Exception) { null } ?: return@addListener
            provider = p
            camInfos = p.availableCameraInfos
            if (camId == "internet") { useInternet(); return@addListener }
            if (camId?.startsWith("celular:") == true) {
                val n = camId!!.removePrefix("celular:")
                if (phones.containsKey(n)) useRemote(n) else toast("Abra o app Câmera da Lousa no celular para conectar")
                return@addListener
            }
            val chosen = camInfos.firstOrNull { idOf(it) == camId }
                ?: camInfos.firstOrNull { facingOf(it) == CameraCharacteristics.LENS_FACING_EXTERNAL }
                ?: camInfos.firstOrNull { facingOf(it) == CameraCharacteristics.LENS_FACING_FRONT }
                ?: camInfos.firstOrNull()
            if (chosen == null) { toast("Nenhuma câmera encontrada"); return@addListener }
            if (facingOf(chosen) != CameraCharacteristics.LENS_FACING_EXTERNAL && camId == null)
                toast("Nenhuma câmera USB apareceu: usando a câmera do tablet. Veja em Ajustes.")
            bindCamera(chosen)
        }, ContextCompat.getMainExecutor(this))
    }

    /** Câmera pela internet: abre a sala e mostra quem entrar pelo link. */
    private fun useInternet() {
        provider?.unbindAll(); remote.stop()
        bubble.visibility = View.VISIBLE
        layoutCamera()
        val mic = if (prefs.getBoolean("conversa", true)) "1" else "0"
        bubble.showWeb(SITE + "receber.html?sala=" + salaAtual() + "&mic=" + mic)
        if (prefs.getBoolean("conversa", true) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            askAudioForTalk.launch(Manifest.permission.RECORD_AUDIO)
    }
    private val askAudioForTalk = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (camId == "internet") useInternet() }

    private fun invite() {
        val link = linkAluno()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dpi(20f), dpi(10f), dpi(20f), dpi(6f)) }
        box.addView(ImageView(this).apply { setImageBitmap(Qr.make(link, dpi(240f))) }, LinearLayout.LayoutParams(dpi(240f), dpi(240f)))
        box.addView(TextView(this).apply {
            text = "Código da sala: ${salaAtual()}\n$link"; setTextColor(0xFFECEEF2.toInt()); textSize = 15f; gravity = Gravity.CENTER
            setTextIsSelectable(true); setPadding(0, dpi(12f), 0, dpi(4f))
        })
        box.addView(TextView(this).apply {
            text = "Quem abrir este link no celular aparece na janela da câmera da lousa. O link continua o mesmo: dá para deixar salvo no celular da escola."
            setTextColor(MUTED); textSize = 13f; gravity = Gravity.CENTER
        })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Convidar câmera pela internet").setView(box)
            .setPositiveButton("Compartilhar link") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Abra este link no celular para mostrar sua câmera na minha lousa: $link")
                }, "Enviar o link"))
            }
            .setNeutralButton("Trocar código") { _, _ ->
                confirm("Criar um código novo? O link antigo deixa de funcionar.", "Trocar") {
                    prefs.edit().putString("sala", novoCodigo()).apply()
                    if (camId == "internet") useInternet()
                    invite()
                }
            }
            .setNegativeButton("Fechar", null).show()
        if (camId != "internet") {
            camId = "internet"; prefs.edit().putString("camId", camId).putBoolean("camOn", true).apply(); camOn = true
            useInternet()
        }
    }

    private fun useRemote(name: String) {
        val (h, port) = phones[name] ?: return
        provider?.unbindAll()
        bubble.showRemote(true)
        bubble.visibility = View.VISIBLE
        layoutCamera()
        remote.connect(h, port)
    }

    private fun bindCamera(info: CameraInfo) {
        val p = provider ?: return
        remote.stop(); bubble.showRemote(false)
        try {
            p.unbindAll()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(bubble.preview.surfaceProvider)
            p.bindToLifecycle(this, info.cameraSelector, preview)
            bubble.visibility = View.VISIBLE
            layoutCamera()
        } catch (e: Exception) {
            toast("Não consegui abrir essa câmera: ${e.message}")
        }
    }

    private fun askPhoneAddress() {
        val ed = android.widget.EditText(this).apply {
            hint = "ex.: 192.168.0.15"; inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(prefs.getString("celularIp", ""))
        }
        AlertDialog.Builder(this).setTitle("Endereço do celular")
            .setMessage("Aparece na tela do app Câmera da Lousa, no celular.")
            .setView(ed)
            .setPositiveButton("Conectar") { _, _ ->
                val ip = ed.text.toString().trim()
                if (ip.isNotEmpty()) {
                    prefs.edit().putString("celularIp", ip).apply()
                    val n = "Celular ($ip)"; phones[n] = ip to CameraSenderActivity.PORT
                    camId = "celular:$n"; useRemote(n)
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    // ---------------- importar ----------------

    private fun importFiles(uris: List<Uri>) {
        toast("Importando…")
        thread {
            val out = ArrayList<ImgSource>()
            val dir = File(filesDir, "importados").apply { mkdirs() }
            for (u in uris) {
                try {
                    val type = contentResolver.getType(u) ?: ""
                    val pdf = type == "application/pdf" || u.toString().lowercase().endsWith(".pdf")
                    val f = File(dir, UUID.randomUUID().toString() + if (pdf) ".pdf" else ".img")
                    contentResolver.openInputStream(u)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    if (pdf) {
                        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                            PdfRenderer(fd).use { r ->
                                for (i in 0 until r.pageCount) r.openPage(i).use { pg -> out.add(ImgSource(f, i, pg.width, pg.height)) }
                            }
                        }
                    } else {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(f.absolutePath, o)
                        if (o.outWidth > 0) out.add(ImgSource(f, -1, o.outWidth, o.outHeight))
                    }
                } catch (e: Exception) {
                    ui.post { toast("Não consegui abrir um dos arquivos") }
                }
            }
            ui.post {
                if (out.isNotEmpty()) {
                    board.addImagePages(out)
                    toast(if (out.size == 1) "1 página importada" else "${out.size} páginas importadas")
                }
            }
        }
    }

    // ---------------- gravação ----------------

    private fun onRecClick() {
        if (rec != Rec.PARADO) { RecordService.instance?.stopRecording(); recBtn.text = "Salvando…"; return }
        val need = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) askScreen() else askAudio.launch(need.toTypedArray())
    }

    private fun askScreen() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        screenCapture.launch(mpm.createScreenCaptureIntent())
    }

    private fun countdownThenRecord(code: Int, data: Intent) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        countTv.visibility = View.VISIBLE
        var n = 3
        val step = object : Runnable {
            override fun run() {
                if (n == 0) { countTv.visibility = View.GONE; startService(code, data); return }
                countTv.text = n.toString(); n--
                ui.postDelayed(this, 800)
            }
        }
        step.run()
    }

    private fun startService(code: Int, data: Intent) {
        val loc = IntArray(2)
        board.getLocationOnScreen(loc)
        val r = board.boardRectInView
        val crop = Rect(loc[0] + r.left.roundToInt(), loc[1] + r.top.roundToInt(), loc[0] + r.right.roundToInt(), loc[1] + r.bottom.roundToInt())
        val bounds = if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds
            else Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val hd = prefs.getBoolean("hd1080", true)
        val vertical = board.H > board.W
        val ow = if (vertical) (if (hd) 1080 else 720) else (if (hd) 1920 else 1280)
        val oh = if (vertical) (if (hd) 1920 else 1280) else (if (hd) 1080 else 720)
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!mic) toast("Gravando sem áudio: o microfone não foi liberado")
        val i = Intent(this, RecordService::class.java).apply {
            putExtra("code", code); putExtra("data", data); putExtra("mic", mic)
            putExtra("l", crop.left); putExtra("t", crop.top); putExtra("r", crop.right); putExtra("b", crop.bottom)
            putExtra("sw", bounds.width()); putExtra("sh", bounds.height()); putExtra("dpi", resources.displayMetrics.densityDpi)
            putExtra("ow", ow); putExtra("oh", oh)
        }
        ContextCompat.startForegroundService(this, i)
    }

    private fun onRecordStarted() {
        rec = Rec.GRAVANDO; recAcc = 0; recStart = SystemClock.elapsedRealtime()
        recBtn.text = "■  Parar"; recBtn.setTextColor(0xFFC21D22.toInt()); recBtn.background = pill(Color.WHITE)
        pauseBtn.visibility = View.VISIBLE
        timerTv.setTextColor(Color.WHITE)
        tickTimer()
    }

    private fun togglePause() {
        val s = RecordService.instance ?: return
        if (rec == Rec.GRAVANDO) {
            s.pause(); recAcc += SystemClock.elapsedRealtime() - recStart; rec = Rec.PAUSADO
            pauseBtn.setImageDrawable(IconDrawable(Icons.PLAY, filled = true)); timerTv.setTextColor(0xFFF2B705.toInt())
        } else if (rec == Rec.PAUSADO) {
            s.resume(); recStart = SystemClock.elapsedRealtime(); rec = Rec.GRAVANDO
            pauseBtn.setImageDrawable(IconDrawable(Icons.PAUSA, filled = true)); timerTv.setTextColor(Color.WHITE)
        }
    }

    private fun elapsed() = recAcc + if (rec == Rec.GRAVANDO) SystemClock.elapsedRealtime() - recStart else 0
    private fun tickTimer() {
        if (rec == Rec.PARADO) return
        val s = elapsed() / 1000
        timerTv.text = String.format("%02d:%02d", s / 60, s % 60)
        ui.postDelayed({ tickTimer() }, 250)
    }

    private fun onRecordFinished(uri: Uri?, msg: String) {
        rec = Rec.PARADO
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        recBtn.text = "●  Gravar"; recBtn.setTextColor(Color.WHITE); recBtn.background = pill(0xFFFF4A4F.toInt())
        pauseBtn.visibility = View.GONE
        pauseBtn.setImageDrawable(IconDrawable(Icons.PAUSA, filled = true))
        timerTv.setTextColor(MUTED)
        val b = AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null)
        if (uri != null) b.setNeutralButton("Compartilhar") { _, _ ->
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Compartilhar a aula"))
        }
        b.show()
    }

    // ---------------- ajustes ----------------

    @SuppressLint("SetTextI18n")
    private fun openSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(22f), dpi(8f), dpi(22f), dpi(16f)) }
        lateinit var dialog: AlertDialog

        fun label(t: String) = box.addView(TextView(this).apply {
            text = t.uppercase(); setTextColor(MUTED); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.07f
            setPadding(0, dpi(16f), 0, dpi(6f))
        })
        fun choices(opts: List<String>, sel: Int, enabled: Boolean = true, on: (Int) -> Unit) {
            val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            var row: LinearLayout? = null
            val views = ArrayList<TextView>()
            for ((i, o) in opts.withIndex()) {
                if (i % 3 == 0) { row = LinearLayout(this); wrap.addView(row) }
                val tv = TextView(this).apply {
                    text = o; textSize = 14f; gravity = Gravity.CENTER; setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
                    alpha = if (enabled) 1f else 0.4f
                }
                fun paint(selected: Boolean) {
                    tv.background = pill(if (selected) ACCENT else RAISED, 10f)
                    tv.setTextColor(if (selected) 0xFF06281D.toInt() else 0xFFECEEF2.toInt())
                }
                paint(i == sel)
                if (enabled) tv.setOnClickListener { views.forEachIndexed { j, v -> v.background = pill(if (j == i) ACCENT else RAISED, 10f); v.setTextColor(if (j == i) 0xFF06281D.toInt() else 0xFFECEEF2.toInt()) }; on(i) }
                views.add(tv)
                row!!.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f); bottomMargin = dpi(6f) })
            }
            box.addView(wrap)
        }

        val recording = rec != Rec.PARADO
        label("Formato do vídeo")
        choices(listOf("Horizontal 16:9", "Vertical 9:16 (TikTok)"), if (board.H > board.W) 1 else 0, !recording) { i ->
            prefs.edit().putBoolean("vertical", i == 1).apply(); board.setFormat(i == 1); camCx = Float.NaN; layoutCamera(); updateUi()
        }
        label("Qualidade do vídeo")
        choices(listOf("Full HD 1080p", "HD 720p"), if (prefs.getBoolean("hd1080", true)) 0 else 1, !recording) { i ->
            prefs.edit().putBoolean("hd1080", i == 0).apply()
        }
        label("Fundo da lousa")
        val fundos = Fundo.values()
        choices(listOf("Branco", "Quadriculado", "Pontilhado", "Quadro verde", "Preto"), board.fundo.ordinal) { i ->
            val old = board.fundo
            board.fundo = fundos[i]
            if (board.color == old.ink || board.color == COLORS[0] || board.color == COLORS[1]) board.color = fundos[i].ink
            prefs.edit().putString("fundo", fundos[i].name).apply(); updateUi()
        }
        label("Câmera")
        val p = provider
        if (p != null) camInfos = p.availableCameraInfos
        val phoneNames = phones.keys.toList()
        val labels = camInfos.map { camLabel(it) } + phoneNames.map { "📱 $it" } + listOf("🌐 Pela internet (sala ${salaAtual()})")
        if (labels.isEmpty()) {
            box.addView(TextView(this).apply { text = "Nenhuma câmera encontrada. Conecte a câmera USB e abra os ajustes de novo."; setTextColor(MUTED) })
        } else {
            val cur = if (camId == "internet") labels.size - 1
                else if (camId?.startsWith("celular:") == true) camInfos.size + phoneNames.indexOf(camId!!.removePrefix("celular:"))
                else camInfos.indexOfFirst { idOf(it) == camId }
            choices(labels, if (cur < 0) 0 else cur) { i ->
                if (!camOn) { camOn = true; prefs.edit().putBoolean("camOn", true).apply() }
                if (i < camInfos.size) {
                    camId = idOf(camInfos[i]); bindCamera(camInfos[i])
                } else if (i == labels.size - 1) {
                    camId = "internet"; useInternet()
                } else {
                    val n = phoneNames[i - camInfos.size]; camId = "celular:$n"; useRemote(n)
                }
                prefs.edit().putString("camId", camId).apply()
            }
        }
        box.addView(TextView(this).apply {
            text = "Para usar o celular como câmera: instale este mesmo app no celular, abra o ícone \"Câmera da Lousa\" e deixe os dois aparelhos no mesmo Wi-Fi. Ele aparece nesta lista com 📱."
            setTextColor(MUTED); textSize = 13f; setPadding(0, dpi(2f), 0, dpi(6f))
        })
        if (phoneNames.isEmpty()) choices(listOf("Digitar o endereço do celular"), -1) { askPhoneAddress() }
        choices(listOf("Convidar câmera pela internet (link e QR code)"), -1) { dialog.dismiss(); invite() }
        choices(listOf("Aluno me ouve na conversa", "Aluno não me ouve"), if (prefs.getBoolean("conversa", true)) 0 else 1) { i ->
            prefs.edit().putBoolean("conversa", i == 0).apply(); if (camId == "internet") useInternet()
        }
        choices(listOf("Redonda", "Retangular"), if (bubble.round) 0 else 1) { i ->
            bubble.round = i == 0; prefs.edit().putBoolean("camRedonda", bubble.round).apply(); layoutCamera()
        }
        choices(listOf("Sem espelhar", "Espelhar"), if (bubble.mirror) 1 else 0) { i ->
            bubble.mirror = i == 1; prefs.edit().putBoolean("espelhar", bubble.mirror).apply()
        }
        box.addView(TextView(this).apply {
            text = "Arraste a câmera com um dedo e use dois dedos sobre ela para aumentar ou diminuir."
            setTextColor(MUTED); textSize = 13f; setPadding(0, dpi(4f), 0, 0)
        })
        label("Escrita")
        choices(listOf("Só a S Pen escreve", "Dedo também escreve"), if (board.fingerDraws) 1 else 0) { i ->
            board.fingerDraws = i == 1; prefs.edit().putBoolean("dedo", board.fingerDraws).apply()
        }

        val scroll = ScrollView(this).apply { addView(box) }
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Ajustes").setView(scroll).setPositiveButton("Fechar", null).create()
        dialog.show()
    }
}
