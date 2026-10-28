package br.com.mimiexplica.lousa

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Serviço que mantém a gravação viva (o Android exige um serviço com notificação para gravar a tela). */
class RecordService : Service() {

    companion object {
        var instance: RecordService? = null
        /** Chamado na tela principal quando a gravação termina: (endereço do vídeo na Galeria, mensagem). */
        var onFinished: ((Uri?, String) -> Unit)? = null
        var onStarted: (() -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "Gravação", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, "rec")
            .setContentTitle("Lousa Gravada")
            .setContentText("Gravando a aula")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        val withMic = intent?.getBooleanExtra("mic", true) ?: true
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            (if (withMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notif, type) else startForeground(1, notif)

        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val code = intent.getIntExtra("code", Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java) else intent.getParcelableExtra("data")
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = data?.let { mpm.getMediaProjection(code, it) }
        if (proj == null) { finish(null, "O Android não liberou a gravação da tela."); return START_NOT_STICKY }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopRecording() }
        }, main)

        val crop = Rect(intent.getIntExtra("l", 0), intent.getIntExtra("t", 0), intent.getIntExtra("r", 0), intent.getIntExtra("b", 0))
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "")
        dir.mkdirs()
        val name = "aula-" + SimpleDateFormat("yyyy-MM-dd-HH'h'mm", Locale.US).format(Date()) + ".mp4"
        val rec = ScreenRecorder(
            this, proj,
            intent.getIntExtra("sw", 0), intent.getIntExtra("sh", 0), intent.getIntExtra("dpi", 320),
            crop, intent.getIntExtra("ow", 1920), intent.getIntExtra("oh", 1080),
            File(dir, name), withMic
        )
        recorder = rec
        rec.start { msg -> main.post { finish(null, msg) } }
        main.post { onStarted?.invoke() }
        return START_NOT_STICKY
    }

    fun pause() { recorder?.pause() }
    fun resume() { recorder?.resume() }

    fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        rec.stop { f ->
            val uri = f?.let { saveToGallery(it) }
            main.post {
                finish(uri, if (uri != null) "Vídeo salvo na Galeria, em Filmes › LousaGravada" else "A gravação não pôde ser salva.")
            }
        }
    }

    private fun saveToGallery(f: File): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LousaGravada")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            f.delete()
            uri
        } catch (e: Exception) { null }
    }

    private fun finish(uri: Uri?, msg: String) {
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        instance = null
        onFinished?.invoke(uri, msg)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { instance = null; super.onDestroy() }
}
