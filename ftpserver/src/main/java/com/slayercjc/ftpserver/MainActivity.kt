package com.slayercjc.ftpserver

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 简易控制界面：显示本机 IP 与服务状态，可手动启动/停止。
 * 纯代码构建 UI，不依赖 AndroidX，兼容 Android 4.1。
 */
class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermissionIfNeeded()

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "70迈后视镜 FTP 服务"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#222222"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(24) }
        }
        root.addView(title)

        tvStatus = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        }
        root.addView(tvStatus)

        btnToggle = Button(this).apply {
            text = "启动 FTP 服务"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            setOnClickListener { toggleService() }
        }
        root.addView(btnToggle)

        val help = TextView(this).apply {
            text = buildHelpText()
            textSize = 14f
            setTextColor(Color.parseColor("#555555"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(help)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val ip = deviceIpAddress()
        val running = isServiceRunning()
        tvStatus.text =
            "本机 IP：$ip\n" +
            "端口：3721\n" +
            "服务状态：${if (running) "运行中 ✓" else "未运行"}\n\n" +
            "手机端 APP 连接地址：\nftp://$ip:3721"
        btnToggle.text = if (running) "停止 FTP 服务" else "启动 FTP 服务"
    }

    private fun toggleService() {
        val running = isServiceRunning()
        val i = Intent(this, FtpService::class.java)
        i.action = if (running) FtpService.ACTION_STOP else FtpService.ACTION_START
        try {
            if (!running && Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            Toast.makeText(this, if (running) "已停止" else "已启动", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "操作失败：${e.message}", Toast.LENGTH_LONG).show()
        }
        tvStatus.postDelayed({ refreshStatus() }, 500)
    }

    private fun isServiceRunning(): Boolean {
        // 用一个轻量 TCP 探测判断 3721 是否在监听，避免依赖系统 API 差异
        return try {
            val s = java.net.Socket()
            s.connect(java.net.InetSocketAddress("127.0.0.1", 3721), 300)
            s.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun deviceIpAddress(): String {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val i = wm.connectionInfo.ipAddress
            if (i != 0) {
                return (i and 0xFF).toString() + "." +
                    (i shr 8 and 0xFF) + "." +
                    (i shr 16 and 0xFF) + "." +
                    (i shr 24 and 0xFF)
            }
        } catch (_: Exception) {}
        // 兜底：枚举网卡
        try {
            val eni = java.net.NetworkInterface.getNetworkInterfaces()
            while (eni.hasMoreElements()) {
                val ni = eni.nextElement()
                val adds = ni.inetAddresses
                while (adds.hasMoreElements()) {
                    val a = adds.nextElement()
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress && !a.isAnyLocalAddress) {
                        return a.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return "未知（请连上 WiFi 后查看）"
    }

    private fun buildHelpText(): String {
        return "使用说明：\n" +
            "1. 本 APP 安装到后视镜后，开机自动启动 FTP 服务。\n" +
            "2. 手机开热点，后视镜 WiFi 连上手机热点。\n" +
            "3. 在上方查看后视镜拿到的 IP。\n" +
            "4. 手机端 APP 填入该 IP + 端口 3721 即可下载录像，无需再开 ES。\n\n" +
            "注意：若服务未自动启动，可点上方按钮手动启动，\n" +
            "并在系统设置中允许本 APP 自启动 / 后台运行。"
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23) {
            val need = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ).filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (need.isNotEmpty()) {
                requestPermissions(need.toTypedArray(), 100)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
