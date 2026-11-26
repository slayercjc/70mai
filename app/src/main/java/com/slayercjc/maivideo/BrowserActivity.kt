package com.slayercjc.maivideo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class BrowserActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvPath: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvDownloadStatus: TextView
    private lateinit var btnDownload: Button
    private lateinit var adapter: FileAdapter

    private var currentPath = "/"
    private val items = mutableListOf<FtpRepo.RemoteFile>()
    private val selected = linkedMapOf<String, FtpRepo.RemoteFile>()
    private val cfg by lazy { FtpRepo.loadConfig(this) }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val done = intent.getIntExtra(DownloadService.EXTRA_DONE, 0)
            val total = intent.getIntExtra(DownloadService.EXTRA_TOTAL, 0)
            val name = intent.getStringExtra(DownloadService.EXTRA_NAME) ?: ""
            val error = intent.getStringExtra(DownloadService.EXTRA_ERROR)
            if (error != null) {
                tvDownloadStatus.text = "⚠ 错误：$error"
            }
            if (total > 0) {
                tvDownloadStatus.text =
                    "进度：$done/$total  ·  $name"
            } else {
                tvDownloadStatus.text = name
            }
            btnDownload.isEnabled = true
            btnDownload.text = "下载选中 (${selected.size})"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        listView = findViewById(R.id.list_view)
        tvPath = findViewById(R.id.tv_path)
        tvCount = findViewById(R.id.tv_count)
        tvDownloadStatus = findViewById(R.id.tv_download_status)
        btnDownload = findViewById(R.id.btn_download)
        adapter = FileAdapter()
        listView.adapter = adapter

        loadDir("/")

        findViewById<View>(R.id.btn_up).setOnClickListener {
            loadDir(if (currentPath == "/") "/" else parentOf(currentPath))
        }
        findViewById<View>(R.id.btn_all_videos).setOnClickListener { loadAllVideos() }
        findViewById<View>(R.id.btn_select_all).setOnClickListener {
            val videos = items.filter { it.isVideo && !it.isParent }
            val allSelected = videos.isNotEmpty() && videos.all { selected.containsKey(it.path) }
            selected.clear()
            if (!allSelected) videos.forEach { selected[it.path] = it }
            tvDownloadStatus.text = if (allSelected) "已取消全选" else "已选中 ${selected.size} 个视频"
            adapter.notifyDataSetChanged()
            updateDownloadButton()
        }
        btnDownload.setOnClickListener { startDownload() }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(DownloadService.BROADCAST_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(progressReceiver, filter)
        }
    }

    override fun onStop() {
        try { unregisterReceiver(progressReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    private fun loadDir(path: String) {
        tvDownloadStatus.text = "正在读取 $path …"
        lifecycleScope.launch {
            try {
                val list = FtpRepo.list(path)
                currentPath = path
                items.clear()
                items.addAll(list)
                selected.clear() // 换目录清空勾选，避免误下
                tvPath.text = path
                tvCount.text = "${items.count { it.isVideo }} 个视频"
                adapter.notifyDataSetChanged()
                tvDownloadStatus.text = if (items.isEmpty()) "此目录为空" else "点文件夹进入，点视频名称可加入下载"
                updateDownloadButton()
            } catch (e: Exception) {
                tvDownloadStatus.text = "读取失败：${e.message}"
            }
        }
    }

    private fun loadAllVideos() {
        tvDownloadStatus.text = "正在递归扫描全部视频（可能要几十秒）…"
        lifecycleScope.launch {
            try {
                val list = FtpRepo.collectVideos("/")
                items.clear()
                items.addAll(list)
                selected.clear()
                currentPath = "/（全部视频）"
                tvPath.text = currentPath
                tvCount.text = "共 ${list.size} 个视频"
                adapter.notifyDataSetChanged()
                tvDownloadStatus.text =
                    if (list.isEmpty()) "没找到视频文件，试试手动进目录看"
                    else "找到 ${list.size} 个视频，勾选后下载"
                updateDownloadButton()
            } catch (e: Exception) {
                tvDownloadStatus.text = "扫描失败：${e.message}"
            }
        }
    }

    private fun startDownload() {
        if (selected.isEmpty()) {
            toast("请先勾选视频")
            return
        }
        val chosen = selected.values.toList()
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_DOWNLOAD
            putStringArrayListExtra(
                DownloadService.EXTRA_PATHS,
                ArrayList(chosen.map { it.path })
            )
            putExtra(DownloadService.EXTRA_SIZES, LongArray(chosen.size) { chosen[it].size })
            putExtra(DownloadService.EXTRA_HOST, cfg.host)
            putExtra(DownloadService.EXTRA_PORT, cfg.port)
            putExtra(DownloadService.EXTRA_USER, cfg.user)
            putExtra(DownloadService.EXTRA_PASS, cfg.password)
            putExtra(DownloadService.EXTRA_SUBDIR, "70迈录像")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        btnDownload.isEnabled = false
        btnDownload.text = "下载中…"
        tvDownloadStatus.text = "已启动后台下载，可看通知栏进度"
    }

    private fun updateDownloadButton() {
        btnDownload.text = "下载选中 (${selected.size})"
    }

    private fun parentOf(path: String): String {
        val t = path.trimEnd('/')
        val i = t.lastIndexOf('/')
        return if (i <= 0) "/" else t.substring(0, i)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private inner class FileAdapter : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@BrowserActivity)

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): FtpRepo.RemoteFile = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: inflater.inflate(R.layout.item_file, parent, false)
            val file = items[position]
            val cb = view.findViewById<CheckBox>(R.id.cb_select)
            val icon = view.findViewById<TextView>(R.id.tv_icon)
            val name = view.findViewById<TextView>(R.id.tv_name)
            val meta = view.findViewById<TextView>(R.id.tv_meta)

            val selectable = file.isVideo
            cb.visibility = if (selectable) View.VISIBLE else View.INVISIBLE
            cb.isChecked = selected.containsKey(file.path)
            icon.text = if (file.isParent) "⬆" else if (file.isDir) "📁" else if (file.isVideo) "🎬" else "📄"

            name.text = file.name
            meta.text = when {
                file.isParent -> "返回上一级目录"
                file.isDir -> "文件夹"
                else -> "${formatSize(file.size)}${if (file.timestamp > 0) "  ·  ${formatTime(file.timestamp)}" else ""}"
            }
            name.setTextColor(
                if (file.isVideo) resources.getColor(android.R.color.holo_blue_dark)
                else resources.getColor(android.R.color.black)
            )

            view.setOnClickListener {
                if (!selectable) {
                    loadDir(file.path)
                } else {
                    toggle(file)
                }
            }
            return view
        }

        private fun toggle(file: FtpRepo.RemoteFile) {
            if (selected.containsKey(file.path)) selected.remove(file.path)
            else selected[file.path] = file
            adapter.notifyDataSetChanged()
            updateDownloadButton()
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "未知大小"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun formatTime(ms: Long): String {
        val d = java.util.Date(ms)
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(d)
    }
}
