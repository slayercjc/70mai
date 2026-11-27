package com.slayercjc.ftpserver

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast

/** 常驻后台服务：在 3721 端口提供 FTP，前台通知防杀。 */
class FtpService : Service() {

    companion object {
        const val ACTION_START = "com.slayercjc.ftpserver.START"
        const val ACTION_STOP = "com.slayercjc.ftpserver.STOP"
        private const val NOTIFY_ID = 1001
    }

    private var server: FtpServer? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                stopServerOnly()
                stopSelf()
            }
            else -> startServerOnly()
        }
        // 被系统杀掉后自动重建，保证后视镜重启/内存紧张时 FTP 仍可用
        return START_STICKY
    }

    private fun startServerOnly() {
        if (server?.isRunning == true) return
        startForegroundCompat()
        val srv = FtpServer(port = 3721)
        server = srv
        worker = Thread({
            try {
                srv.start()
            } catch (_: Exception) {
            }
        }, "70mai-ftp-server").apply { isDaemon = false; start() }
        Toast.makeText(this, "FTP 服务已启动 :3721", Toast.LENGTH_SHORT).show()
    }

    private fun stopServerOnly() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        worker = null
        try { stopForeground(true) } catch (_: Exception) {}
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification
        if (Build.VERSION.SDK_INT >= 26) {
            // Android 8+ 需要通知渠道（仅在新设备上测试时会走到，老后视镜是 Android 4）
            val channel = android.app.NotificationChannel(
                "ftp", "FTP 服务", android.app.NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
            notification = Notification.Builder(this, "ftp")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("70迈 FTP 服务运行中")
                .setContentText("端口 3721，供手机下载录像")
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            // Android 4.x 路径
            @Suppress("DEPRECATION")
            notification = Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("70迈 FTP 服务运行中")
                .setContentText("端口 3721，供手机下载录像")
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
        try {
            startForeground(NOTIFY_ID, notification)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        stopServerOnly()
        super.onDestroy()
    }
}
