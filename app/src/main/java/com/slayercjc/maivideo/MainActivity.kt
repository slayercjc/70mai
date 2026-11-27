package com.slayercjc.maivideo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvHistory: TextView
    private lateinit var tvAdvanced: TextView
    private lateinit var llAdvanced: LinearLayout
    private lateinit var btnScan: Button
    private lateinit var btnConnect: Button
    private lateinit var progressBar: ProgressBar

    private val prefs by lazy { getSharedPreferences("maivideo", Context.MODE_PRIVATE) }
    private val history = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etHost = findViewById(R.id.et_host)
        etPort = findViewById(R.id.et_port)
        etUser = findViewById(R.id.et_user)
        etPass = findViewById(R.id.et_pass)
        tvStatus = findViewById(R.id.tv_status)
        tvHistory = findViewById(R.id.tv_history)
        tvAdvanced = findViewById(R.id.tv_advanced)
        llAdvanced = findViewById(R.id.ll_advanced)
        btnScan = findViewById(R.id.btn_scan)
        btnConnect = findViewById(R.id.btn_connect)
        progressBar = findViewById(R.id.progress)

        loadUiState()
        requestNotificationPermissionIfNeeded()

        tvAdvanced.setOnClickListener {
            val show = llAdvanced.visibility != View.VISIBLE
            llAdvanced.visibility = if (show) View.VISIBLE else View.GONE
            tvAdvanced.text = if (show) "▾ 登录账号（默认匿名，一般不用改）"
            else "▸ 登录账号（默认匿名，一般不用改）"
        }

        tvHistory.setOnClickListener {
            if (history.isEmpty()) {
                toast("暂无历史地址，先扫描或手动连接一次")
                return@setOnClickListener
            }
            val next = history[(history.indexOf(etHost.text.toString()) + 1) % history.size]
            etHost.setText(next)
            toast("已切换：$next")
        }

        btnScan.setOnClickListener { startScan() }
        btnConnect.setOnClickListener { startConnect() }
    }

    private fun loadUiState() {
        val cfg = FtpRepo.loadConfig(this)
        if (cfg.host.isNotBlank()) etHost.setText(cfg.host)
        etPort.setText(cfg.port.toString())
        etUser.setText(cfg.user)
        etPass.setText(cfg.password)

        history.clear()
        val saved = prefs.getString("history", "") ?: ""
        history.addAll(saved.split(",").map { it.trim() }.filter { it.isNotBlank() })
        refreshHistoryText()
    }

    private fun refreshHistoryText() {
        tvHistory.text = if (history.isEmpty())
            "历史地址：无（连接成功后会自动保存）"
        else
            "历史地址（点击切换）：${history.joinToString("  |  ")}"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101
                )
            }
        }
    }

    private fun readConfig(): FtpRepo.Config? {
        val host = etHost.text.toString().trim()
        if (TextUtils.isEmpty(host)) {
            toast("请先填后视镜 IP，或点扫描")
            return null
        }
        val port = etPort.text.toString().trim().toIntOrNull() ?: 2121
        return FtpRepo.Config(
            host = host,
            port = port,
            user = etUser.text.toString().trim().ifBlank { "anonymous" },
            password = etPass.text.toString(),
            startDir = "/"
        )
    }

    private fun startScan() {
        val prefix = NetScan.currentSubnet(this)
        if (prefix == null) {
            tvStatus.text = "扫描失败：未检测到 WiFi 连接。请确认手机已连上后视镜热点，或手机开热点让后视镜连入。"
            return
        }
        setBusy(true, "正在扫描 $prefix.1-254 的 FTP 端口…")
        progressBar.isIndeterminate = false
        lifecycleScope.launch {
            val found = NetScan.scan(prefix) { scanned, total ->
                runOnUiThread {
                    progressBar.max = total
                    progressBar.progress = scanned
                    tvStatus.text = "扫描中… $scanned/$total"
                }
            }
            setBusy(false)
            if (found.isEmpty()) {
                tvStatus.text =
                    "在 $prefix.* 网段没找到 FTP 服务（已探测 2121 和 21 端口）。\n" +
                    "请确认：① 手机与后视镜在同一网络；② 后视镜上的 ES 文件管理器已打开「PC 网络/远程管理」并启动了 FTP 服务（这一步每次都要做）；\n" +
                    "③ 也可以直接在上方手动输入后视镜 IP 和 ES 显示的端口。"
            } else {
                val hosts = found.map { it.host }
                history.addAll(hosts.filterNot { history.contains(it) })
                prefs.edit().putString("history", history.joinToString(",")).apply()
                refreshHistoryText()
                val first = found.first()
                etHost.setText(first.host)
                // 把探测到的端口自动填入（ES FTP 默认 2121）
                first.openPorts.firstOrNull()?.let { etPort.setText(it.toString()) }
                val desc = found.joinToString(", ") { "${it.host}:${it.openPorts.joinToString("/")}" }
                tvStatus.text = "找到 ${hosts.size} 个 FTP 设备：$desc\n已填入第一个（含端口），点【连接并列出视频】。"
                toast("发现 ${hosts.size} 个设备")
            }
        }
    }

    private fun startConnect() {
        val cfg = readConfig() ?: return
        setBusy(true, "正在连接 ${cfg.host}:${cfg.port} …")
        lifecycleScope.launch {
            try {
                val banner = FtpRepo.connect(cfg)
                FtpRepo.saveConfig(this@MainActivity, cfg)
                if (!history.contains(cfg.host)) history.add(0, cfg.host)
                prefs.edit().putString("history", history.joinToString(",")).apply()
                refreshHistoryText()

                withUi { setBusy(false, "$banner\n正在读取视频列表…") }
                val intent = Intent(this@MainActivity, BrowserActivity::class.java)
                startActivity(intent)
            } catch (e: Exception) {
                withUi {
                    setBusy(false)
                    tvStatus.text = "连接失败：${e.message ?: e.javaClass.simpleName}\n" +
                        "排查：① 后视镜 ES 文件管理器的 FTP 是否已启动（每次都要开）；② IP 是否为当前地址；③ 端口是否为 ES 显示的数字（默认 2121）；④ 账号保持匿名。"
                }
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        btnScan.isEnabled = !busy
        btnConnect.isEnabled = !busy
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) progressBar.isIndeterminate = true
        if (message != null) tvStatus.text = message
    }

    private fun withUi(block: () -> Unit) = runOnUiThread(block)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
