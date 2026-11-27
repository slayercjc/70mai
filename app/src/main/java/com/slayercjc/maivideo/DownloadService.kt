package com.slayercjc.maivideo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 后台下载服务：串行下载选中的视频，进度写通知，支持断点续传与取消。
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_DOWNLOAD = "com.slayercjc.maivideo.action.DOWNLOAD"
        const val ACTION_CANCEL = "com.slayercjc.maivideo.action.CANCEL"
        // 用基础类型传递，规避 Android 13+ 对 Intent 自定义 Serializable 的加载限制
        const val EXTRA_PATHS = "paths"              // ArrayList<String> 远程路径
        const val EXTRA_SIZES = "sizes"              // LongArray 对应大小
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_SUBDIR = "subdir"            // 保存子目录名

        const val BROADCAST_PROGRESS = "com.slayercjc.maivideo.PROGRESS"
        const val EXTRA_DONE = "done"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_NAME = "name"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_FINISHED = "finished"
        const val EXTRA_ERROR = "error"

        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFY_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    @Volatile private var cancelled = false
    private var notifyManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notifyManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
                job?.cancel()
                publishProgress(0, 0, "正在取消…", 0, false, null)
                return START_NOT_STICKY
            }
            ACTION_DOWNLOAD -> {
                cancelled = false
                val paths = intent.getStringArrayListExtra(EXTRA_PATHS)
                val sizes = intent.getLongArrayExtra(EXTRA_SIZES)
                val host = intent.getStringExtra(EXTRA_HOST)
                if (paths.isNullOrEmpty() || sizes == null || host.isNullOrBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val cfg = FtpRepo.Config(
                    host = host,
                    port = intent.getIntExtra(EXTRA_PORT, 2121),
                    user = intent.getStringExtra(EXTRA_USER) ?: "anonymous",
                    password = intent.getStringExtra(EXTRA_PASS) ?: "anonymous@"
                )
                val files = paths.mapIndexed { i, p ->
                    FtpRepo.RemoteFile(
                        name = p.substringAfterLast('/'),
                        path = p,
                        isDir = false,
                        size = sizes.getOrElse(i) { 0L },
                        timestamp = 0L,
                        isVideo = true
                    )
                }
                val subdir = intent.getStringExtra(EXTRA_SUBDIR) ?: "70迈录像"
                startAsForeground()
                job = scope.launch {
                    try {
                        withContext(Dispatchers.IO) { runDownloads(cfg, files, subdir) }
                    } finally {
                        stopSelf(startId)
                    }
                }
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "视频下载", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "70迈视频提取下载进度" }
            notifyManager?.createNotificationChannel(ch)
        }
    }

    private fun startAsForeground() {
        val n = buildNotification(0, 0, "准备下载…", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFY_ID, n)
        }
    }

    private fun buildNotification(done: Int, total: Int, name: String, percent: Int): Notification {
        val cancelIntent = Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getService(this, 2, cancelIntent, flags)

        val title = if (total > 0) "下载中 $done/$total" else "视频下载"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", pi)
        if (total > 0) builder.setProgress(100, percent, false)
        return builder.build()
    }

    private fun updateNotification(done: Int, total: Int, name: String, percent: Int) {
        notifyManager?.notify(NOTIFY_ID, buildNotification(done, total, name, percent))
    }

    private suspend fun runDownloads(
        cfg: FtpRepo.Config,
        files: List<FtpRepo.RemoteFile>,
        subdir: String
    ) {
        try {
            FtpRepo.connect(cfg)
        } catch (e: Exception) {
            publishProgress(0, files.size, "连接失败", 0, true, e.message ?: "连接失败")
            return
        }

        var succeeded = 0
        var failed = 0
        val dateDir = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

        try {
            for ((index, file) in files.withIndex()) {
                if (cancelled) break
                val name = file.name.ifBlank { "video_${index + 1}.mp4" }
                publishProgress(index, files.size, name, 0, false, null)
                val outAndUri = openOutput(name, subdir, dateDir)

                var lastPercent = -1
                try {
                    FtpRepo.download(
                        remotePath = file.path,
                        out = outAndUri.stream,
                        offset = outAndUri.offset,
                        onProgress = { transferred ->
                            val total = outAndUri.offset + transferred
                            val known = if (file.size > 0) file.size else 0L
                            val percent = if (known > 0) {
                                ((total * 100) / known).toInt().coerceIn(0, 99)
                            } else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                updateNotification(index + 1, files.size, name, if (percent < 0) 0 else percent)
                                publishProgress(index, files.size, name, if (percent < 0) 0 else percent, false, null)
                            }
                        },
                        shouldCancel = { cancelled }
                    )
                    markPendingComplete(outAndUri.uri)
                    succeeded++
                } catch (e: CancellationException) {
                    discardPartial(outAndUri)
                    break
                } catch (e: InterruptedException) {
                    discardPartial(outAndUri)
                    break
                } catch (e: Exception) {
                    failed++
                    discardPartial(outAndUri)
                    publishProgress(index + 1, files.size, name, 0, false, e.message ?: "下载失败")
                } finally {
                    try { outAndUri.stream.close() } catch (_: Exception) {}
                }
            }
        } finally {
            FtpRepo.disconnect()
        }

        val msg = buildString {
            append("完成：成功 $succeeded 个")
            if (failed > 0) append("，失败 $failed 个")
            if (cancelled) append("（已取消）")
            append("。文件保存在 下载/$subdir/$dateDir")
        }
        updateNotification(files.size, files.size, msg, 100)
        publishProgress(files.size, files.size, msg, 100, true, null)
    }

    private data class OutputTarget(val stream: OutputStream, val uri: Uri?, val offset: Long)

    /** Android 10+ 用 MediaStore 写入「下载」目录，无需存储权限；低版本直写公共目录 */
    private fun openOutput(name: String, subdir: String, dateDir: String): OutputTarget {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            var existing: Uri? = null
            var existingSize = 0L
            contentResolver.query(collection, projection, selection, arrayOf(name), null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val id = cur.getLong(cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    existing = Uri.withAppendedPath(collection, id.toString())
                    existingSize = cur.getLong(cur.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                }
            }
            val uri = existing ?: run {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subdir/$dateDir")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                contentResolver.insert(collection, values)
                    ?: throw IOException("无法在下载目录创建文件")
            }
            val mode = if (existing != null && existingSize > 0) "wa" else "w"
            val stream = contentResolver.openOutputStream(uri, mode)
                ?: throw IOException("无法打开文件输出流")
            OutputTarget(stream, uri, if (mode == "wa") existingSize else 0L)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$subdir/$dateDir"
            )
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            val offset = if (f.exists()) f.length() else 0L
            OutputTarget(FileOutputStream(f, offset > 0), null, offset)
        }
    }

    private fun markPendingComplete(uri: Uri?) {
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        try { contentResolver.update(uri, values, null, null) } catch (_: Exception) {}
    }

    private fun discardPartial(target: OutputTarget) {
        try { target.stream.close() } catch (_: Exception) {}
        if (target.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { contentResolver.delete(target.uri, null, null) } catch (_: Exception) {}
        }
    }

    private fun publishProgress(
        done: Int, total: Int, name: String, percent: Int,
        finished: Boolean, error: String?
    ) {
        val i = Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_DONE, done)
            putExtra(EXTRA_TOTAL, total)
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_PERCENT, percent)
            putExtra(EXTRA_FINISHED, finished)
            putExtra(EXTRA_ERROR, error)
            setPackage(packageName)
        }
        sendBroadcast(i)
    }
}
