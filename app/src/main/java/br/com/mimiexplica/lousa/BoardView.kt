package br.com.mimiexplica.lousa

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A lousa. A S Pen desenha numa camada "front buffer" que vai direto para a tela (baixa latência,
 * como no Samsung Notes). Ao levantar a caneta o traço é gravado no bitmap da página.
 */
class BoardView(context: Context) : SurfaceView(context) {

    /** Operações enviadas para a camada de baixa latência. */
    sealed class Op {
        class Seg(val g: FloatArray, val color: Int, val tool: Tool, val w: Float, val pen: Boolean) : Op()
        class Live(val s: Stroke) : Op()
    }

    var W = 1920; private set
    var H = 1080; private set
    val pages = arrayListOf(Page())
    var pi = 0; private set
    val page get() = pages[pi]

    var tool = Tool.CANETA
    var color = 0xFF14161A.toInt()
    var size = 8f
    var fundo = Fundo.BRANCO
        set(v) { field = v; rebuildBg() }
    var fingerDraws = false

    /** Avisos para a tela principal (atualizar botões, reposicionar a câmera...). */
    var onChange: (() -> Unit)? = null
    var onLayoutBoard: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null

    private val lock = Any()
    private var ink: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private var bg: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private var inkCanvas = Canvas(ink)
    private val inkPaint = Paint()
    private val frontPaint = Paint()

    // matriz lousa -> tela (a lousa fica centralizada, mantendo a proporção)
    private val toView = Matrix()
    private val toBoard = Matrix()
    @Volatile private var drawMatrix = Matrix()
    val boardRectInView = RectF()

    private var renderer: CanvasFrontBufferedRenderer<Op>? = null

    private val callback = object : CanvasFrontBufferedRenderer.Callback<Op> {
        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: Op) {
            when (param) {
                is Op.Seg -> {
                    canvas.save(); canvas.concat(drawMatrix)
                    val s = Stroke(param.tool, param.color, param.w, param.pen)
                    Ink.drawGeom(canvas, param.g, Ink.paintFor(s, frontPaint))
                    canvas.restore()
                }
                is Op.Live -> {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    canvas.save(); canvas.concat(drawMatrix)
                    Ink.draw(canvas, param.s, frontPaint)
                    canvas.restore()
                }
            }
        }

        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<Op>) {
            canvas.drawColor(0xFF0D0F13.toInt())
            synchronized(lock) {
                canvas.save(); canvas.concat(drawMatrix)
                canvas.drawBitmap(bg, 0f, 0f, null)
                canvas.drawBitmap(ink, 0f, 0f, null)
                canvas.restore()
            }
        }
    }

    init {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { post { refresh() } }
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
        renderer = CanvasFrontBufferedRenderer(this, callback)
        rebuildBg()
    }

    fun release() { renderer?.release(true); renderer = null }

    /** Redesenha a lousa inteira na tela. */
    fun refresh() { renderer?.renderMultiBufferedLayer(emptyList()) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    private fun updateMatrix() {
        if (width == 0 || height == 0) return
        val pad = 8f
        val s = min((width - 2 * pad) / W, (height - 2 * pad) / H)
        val dx = (width - W * s) / 2f
        val dy = (height - H * s) / 2f
        toView.reset(); toView.setScale(s, s); toView.postTranslate(dx, dy)
        toView.invert(toBoard)
        drawMatrix = Matrix(toView)
        boardRectInView.set(dx, dy, dx + W * s, dy + H * s)
        onLayoutBoard?.invoke()
        refresh()
    }

    val scale: Float get() = boardRectInView.width() / W

    // ---------- formato e fundo ----------

    fun setFormat(vertical: Boolean) {
        val nw = if (vertical) 1080 else 1920
        val nh = if (vertical) 1920 else 1080
        if (nw == W && nh == H) return
        synchronized(lock) {
            W = nw; H = nh
            ink = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            bg = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            inkCanvas = Canvas(ink)
        }
        for (p in pages) { p.cx = Float.NaN; p.cy = Float.NaN; p.k = 1f }
        imgCache = null
        redrawInk()
        rebuildBg()
        updateMatrix()
    }

    private var imgCache: Pair<ImgSource, Bitmap>? = null

    private fun loadImage(src: ImgSource): Bitmap? {
        imgCache?.let { if (it.first === src) return it.second }
        // tamanho que cabe na lousa (com folga para aproximar um pouco)
        val fit = min(W.toFloat() / src.w, H.toFloat() / src.h) * 1.5f
        val tw = max(1, (src.w * fit).toInt())
        val th = max(1, (src.h * fit).toInt())
        val bmp: Bitmap? = try {
            if (src.pdfIndex >= 0) {
                ParcelFileDescriptor.open(src.file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { r ->
                        r.openPage(src.pdfIndex).use { pg ->
                            val b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                            b.eraseColor(Color.WHITE)
                            pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            b
                        }
                    }
                }
            } else {
                val o = BitmapFactory.Options()
                var sample = 1
                while (src.w / (sample * 2) >= tw && src.h / (sample * 2) >= th) sample *= 2
                o.inSampleSize = sample
                BitmapFactory.decodeFile(src.file.absolutePath, o)
            }
        } catch (e: Exception) { null }
        if (bmp != null) {
            imgCache?.second?.let { if (it !== bmp) it.recycle() }
            imgCache = src to bmp
        }
        return bmp
    }

    /** Monta o fundo da página: cor, grade ou pontos, e a imagem importada. */
    fun rebuildBg() {
        val p = page
        val img = p.img?.let { loadImage(it) }
        synchronized(lock) {
            val c = Canvas(bg)
            c.drawColor(fundo.fill)
            val pt = Paint(Paint.ANTI_ALIAS_FLAG)
            val step = 60f
            if (fundo.grid != 0) {
                pt.color = fundo.grid; pt.strokeWidth = 2f
                var x = step; while (x < W) { c.drawLine(x, 0f, x, H.toFloat(), pt); x += step }
                var y = step; while (y < H) { c.drawLine(0f, y, W.toFloat(), y, pt); y += step }
            }
            if (fundo.dots != 0) {
                pt.color = fundo.dots
                var x = step
                while (x < W) { var y = step; while (y < H) { c.drawCircle(x, y, 2.6f, pt); y += step }; x += step }
            }
            if (img != null) c.drawBitmap(img, null, imgRect(p), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        refresh()
    }

    fun imgRect(p: Page): RectF {
        val src = p.img ?: return RectF()
        if (p.cx.isNaN()) { p.cx = W / 2f; p.cy = H / 2f }
        val base = min(W.toFloat() / src.w, H.toFloat() / src.h)
        val w = src.w * base * p.k
        val h = src.h * base * p.k
        return RectF(p.cx - w / 2, p.cy - h / 2, p.cx + w / 2, p.cy + h / 2)
    }

    private fun redrawInk() {
        synchronized(lock) {
            ink.eraseColor(Color.TRANSPARENT)
            for (s in page.strokes) Ink.draw(inkCanvas, s, inkPaint)
        }
    }

    // ---------- páginas e histórico ----------

    private fun pageChanged() {
        renderer?.cancel()
        cur = null
        redrawInk(); rebuildBg(); onChange?.invoke()
    }
    fun undo() { val p = page; if (p.strokes.isNotEmpty()) { p.redo.add(p.strokes.removeAt(p.strokes.size - 1)); redrawInk(); refresh(); onChange?.invoke() } }
    fun redo() { val p = page; if (p.redo.isNotEmpty()) { p.strokes.add(p.redo.removeAt(p.redo.size - 1)); redrawInk(); refresh(); onChange?.invoke() } }
    fun clearPage() { val p = page; if (p.strokes.isEmpty()) return; p.redo.clear(); p.redo.addAll(p.strokes.reversed()); p.strokes.clear(); redrawInk(); refresh(); onChange?.invoke() }
    fun prev() { if (pi > 0) { pi--; pageChanged() } }
    fun next() { if (pi < pages.size - 1) { pi++; pageChanged() } }
    fun addPage() { pages.add(pi + 1, Page()); pi++; pageChanged() }
    fun deletePage() {
        if (pages.size == 1) pages[0] = Page() else { pages.removeAt(pi); pi = min(pi, pages.size - 1) }
        pageChanged()
    }
    fun addImagePages(list: List<ImgSource>) {
        var first = true
        for (src in list) {
            val p = page
            if (first && p.strokes.isEmpty() && p.img == null) p.img = src
            else { val np = Page(); np.img = src; pages.add(pi + 1, np); pi++ }
            first = false
        }
        pageChanged()
    }

    // ---------- caneta e gestos ----------

    private var cur: Stroke? = null
    private var curPointer = -1
    private val pts = FloatArray(2)

    private fun boardPt(x: Float, y: Float, p: Float): Pt {
        pts[0] = x; pts[1] = y; toBoard.mapPoints(pts)
        return Pt(pts[0], pts[1], p)
    }

    // gesto de mover a imagem
    private val mv = HashMap<Int, Pt>()
    private var mv0: FloatArray? = null // cx, cy, dist, centro x, centro y, k

    private fun mvState(): FloatArray? {
        val v = mv.values.toList()
        if (v.isEmpty()) return null
        return if (v.size > 1) floatArrayOf((v[0].x + v[1].x) / 2, (v[0].y + v[1].y) / 2, hypot(v[0].x - v[1].x, v[0].y - v[1].y))
        else floatArrayOf(v[0].x, v[0].y, 0f)
    }
    private fun mvStart() { val st = mvState(); val p = page; mv0 = st?.let { floatArrayOf(p.cx, p.cy, it[2], it[0], it[1], p.k) } }
    private fun mvMove() {
        val st = mvState() ?: return
        val m0 = mv0 ?: return
        val p = page
        val k = if (m0[2] > 0 && st[2] > 0) (m0[5] * st[2] / m0[2]).coerceIn(0.2f, 6f) else m0[5]
        val r = k / m0[5]
        p.k = k
        p.cx = st[0] + (m0[0] - m0[3]) * r
        p.cy = st[1] + (m0[1] - m0[4]) * r
        rebuildBg()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        val type = e.getToolType(idx)
        val stylus = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER

        if (tool == Tool.MOVER) return handleMove(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (cur != null) return true
                if (!stylus && !fingerDraws) return true
                requestUnbufferedDispatch(e)
                val side = type == MotionEvent.TOOL_TYPE_ERASER || (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                val t = if (side) Tool.BORRACHA else tool
                val s = Stroke(t, color, size, stylus)
                val p = boardPt(e.getX(idx), e.getY(idx), if (stylus) e.getPressure(idx) else 0.5f)
                s.pts.add(p)
                if (t == Tool.RETA) s.pts.add(Pt(p.x, p.y, p.p))
                cur = s; curPointer = e.getPointerId(idx)
                if (t == Tool.MARCA || t == Tool.RETA) renderer?.renderFrontBufferedLayer(Op.Live(copyOf(s)))
            }
            MotionEvent.ACTION_MOVE -> {
                val s = cur ?: return true
                val pidx = e.findPointerIndex(curPointer)
                if (pidx < 0) return true
                for (h in 0 until e.historySize) addPoint(s, e.getHistoricalX(pidx, h), e.getHistoricalY(pidx, h), e.getHistoricalPressure(pidx, h))
                addPoint(s, e.getX(pidx), e.getY(pidx), e.getPressure(pidx))
                if (s.tool == Tool.MARCA || s.tool == Tool.RETA) renderer?.renderFrontBufferedLayer(Op.Live(copyOf(s)))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val s = cur ?: return true
                if (e.getPointerId(idx) != curPointer && e.actionMasked != MotionEvent.ACTION_CANCEL) return true
                cur = null
                if (s.tool == Tool.RETA && hypot(s.pts[1].x - s.pts[0].x, s.pts[1].y - s.pts[0].y) < 3f) {
                    renderer?.cancel(); refresh(); return true
                }
                synchronized(lock) {
                    if (s.tool == Tool.BORRACHA) {
                        Ink.drawGeom(inkCanvas, Ink.tailGeom(s), Ink.paintFor(s, inkPaint))
                    } else {
                        Ink.draw(inkCanvas, s, inkPaint)
                    }
                }
                page.strokes.add(s); page.redo.clear()
                if (s.tool == Tool.BORRACHA) refresh() else renderer?.commit()
                onChange?.invoke()
            }
        }
        return true
    }

    private fun copyOf(s: Stroke): Stroke { val c = Stroke(s.tool, s.color, s.w, s.pen); c.pts.addAll(s.pts); return c }

    private fun addPoint(s: Stroke, x: Float, y: Float, pressure: Float) {
        val p = boardPt(x, y, if (s.pen) pressure else 0.5f)
        if (s.tool == Tool.RETA) { s.pts[1] = p; return }
        val l = s.pts[s.pts.size - 1]
        if (hypot(p.x - l.x, p.y - l.y) < 1.2f) return
        s.pts.add(p)
        if (s.tool == Tool.CANETA) {
            renderer?.renderFrontBufferedLayer(Op.Seg(Ink.segGeom(s, s.pts.size - 1), s.color, s.tool, s.w, s.pen))
        } else if (s.tool == Tool.BORRACHA) {
            // a borracha apaga direto no bitmap da página
            synchronized(lock) { Ink.drawGeom(inkCanvas, Ink.segGeom(s, s.pts.size - 1), Ink.paintFor(s, inkPaint)) }
            refresh()
        }
    }

    private fun handleMove(e: MotionEvent): Boolean {
        if (page.img == null) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onMessage?.invoke("Esta página não tem imagem para mover. Importe um PDF ou imagem.")
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                mv[e.getPointerId(i)] = boardPt(e.getX(i), e.getY(i), 0f); mvStart()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (mv.containsKey(id)) mv[id] = boardPt(e.getX(i), e.getY(i), 0f)
                }
                mvMove()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) mv.clear() else mv.remove(e.getPointerId(e.actionIndex))
                mvStart()
            }
        }
        return true
    }
}
