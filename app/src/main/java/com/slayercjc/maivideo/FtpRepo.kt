package com.slayercjc.maivideo

import android.content.Context
import android.content.SharedPreferences
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.IOException
import java.io.OutputStream

/**
 * FTP 连接与文件操作封装。
 * 后视镜上 ES 文件管理器开启的 FTP 服务：匿名登录、被动模式、老版默认端口 3721。
 */
object FtpRepo {

    data class Config(
        val host: String,
        val port: Int = 3721,
        val user: String = "anonymous",
        val password: String = "anonymous@",
        val startDir: String = "/"
    ) : java.io.Serializable

    data class RemoteFile(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val timestamp: Long,
        val isVideo: Boolean
    ) : java.io.Serializable {
        val isParent: Boolean get() = name == ".."
    }

    private val VIDEO_EXT = setOf("mp4", "mov", "avi", "mkv", "ts", "3gp", "webm", "m4v")

    @Volatile
    private var client: FTPClient? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("maivideo", Context.MODE_PRIVATE)

    fun loadConfig(ctx: Context): Config = with(prefs(ctx)) {
        Config(
            host = getString("host", "") ?: "",
            port = getInt("port", 3721),
            user = getString("user", "anonymous") ?: "anonymous",
            password = getString("pass", "anonymous@") ?: "anonymous@",
            startDir = getString("startDir", "/") ?: "/"
        )
    }

    fun saveConfig(ctx: Context, cfg: Config) {
        prefs(ctx).edit()
            .putString("host", cfg.host)
            .putInt("port", cfg.port)
            .putString("user", cfg.user)
            .putString("pass", cfg.password)
            .putString("startDir", cfg.startDir)
            .apply()
    }

    /** 测试连接，成功返回欢迎信息/系统标识，失败抛异常 */
    @Synchronized
    fun connect(cfg: Config): String {
        disconnect()
        // 先做一次纯 TCP 探测：Commons Net 在连接失败时会抛出难以理解的
        // "setSoTimeout on a null object" 错误，这里提前拦截并给出明确原因。
        probeTcp(cfg.host, cfg.port)

        val c = FTPClient()
        c.connectTimeout = 8000
        c.defaultTimeout = 15000
        c.soTimeout = 20000
        c.controlEncoding = "UTF-8"
        c.autodetectUTF8 = true

        try {
            c.connect(cfg.host, cfg.port)
        } catch (e: Exception) {
            try { c.disconnect() } catch (_: Exception) {}
            throw IOException("FTP 握手失败：${e.message ?: e.javaClass.simpleName}（检查 IP/端口是否正确）")
        }
        val system = try { c.systemName } catch (_: Exception) { null }
        val ok = c.login(cfg.user, cfg.password)
        if (!ok) {
            c.disconnect()
            throw IOException("登录失败：用户名或密码不正确（可尝试 anonymous / anonymous@）")
        }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        client = c
        return system?.takeIf { it.isNotBlank() } ?: "FTP 连接成功"
    }

    /**
     * 纯 TCP 连通性探测，失败时抛出可读的中文错误。
     * 区分：主机不可达 / 连接被拒绝（端口没开）/ 超时 / 地址格式错误。
     */
    private fun probeTcp(host: String, port: Int) {
        if (host.isBlank()) throw IOException("IP 不能为空，请先扫描或手动填写后视镜地址")
        val socket = java.net.Socket()
        try {
            socket.connect(java.net.InetSocketAddress(host, port), 6000)
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("连接超时：$host:$port 无响应。检查后视镜是否在线、是否同一网络")
        } catch (e: java.net.ConnectException) {
            throw IOException("连接被拒绝：$host:$port 没有服务监听。后视镜 FTP 未启动，或 IP/端口填错")
        } catch (e: java.net.UnknownHostException) {
            throw IOException("无法解析地址：$host，请填写 IP 数字")
        } catch (e: IOException) {
            throw IOException("网络不通：$host:$port（${e.message ?: e.javaClass.simpleName}）。确认手机与后视镜在同一 WiFi/热点")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun disconnect() {
        try { client?.logout() } catch (_: Exception) {}
        try { client?.disconnect() } catch (_: Exception) {}
        client = null
    }

    private fun requireClient(): FTPClient {
        val c = client ?: throw IOException("尚未连接，请先点【连接】")
        if (!c.isConnected) throw IOException("连接已断开，请重新连接")
        return c
    }

    /** 列目录，自动在前面插入一个「返回上级」项 */
    @Synchronized
    fun list(path: String): List<RemoteFile> {
        val c = requireClient()
        val arr = c.listFiles(path) ?: throw IOException("无法读取目录：$path（路径不存在或权限不足）")

        val result = mutableListOf<RemoteFile>()
        if (path != "/" && path.isNotBlank()) {
            result += RemoteFile("..", parentOf(path), true, 0, 0, false)
        }
        for (f: FTPFile in arr) {
            val name = f.name
            if (name == "." || name == "..") continue
            val full = join(path, name)
            val video = !f.isDirectory && name.substringAfterLast('.').lowercase() in VIDEO_EXT
            result += RemoteFile(
                name = name,
                path = full,
                isDir = f.isDirectory,
                size = if (f.isDirectory) 0 else f.size,
                timestamp = try { f.timestamp?.timeInMillis ?: 0L } catch (_: Exception) { 0L },
                isVideo = video
            )
        }
        // 目录在前，视频在后，各自按名称排
        return result.sortedWith(
            compareByDescending<RemoteFile> { it.isDir }
                .thenBy { it.name.lowercase() }
        )
    }

    /** 递归收集指定目录下所有视频（用于一键列出全部录像） */
    @Synchronized
    fun collectVideos(root: String, maxDepth: Int = 12, maxCount: Int = 5000): List<RemoteFile> {
        val c = requireClient()
        val out = mutableListOf<RemoteFile>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && out.size < maxCount) {
            val (dir, depth) = queue.removeFirst()
            if (depth > maxDepth) continue
            visited++
            if (visited > 3000) break
            val arr = try { c.listFiles(dir) } catch (_: Exception) { null } ?: continue
            for (f: FTPFile in arr) {
                val name = f.name
                if (name == "." || name == "..") continue
                val full = join(dir, name)
                if (f.isDirectory) {
                    // 跳过常见的系统/隐藏目录，加快扫描
                    if (!name.startsWith(".") && name != "loss" && name != "LOST.DIR") {
                        queue.add(full to (depth + 1))
                    }
                } else if (name.substringAfterLast('.').lowercase() in VIDEO_EXT) {
                    out += RemoteFile(
                        name = name, path = full, isDir = false, size = f.size,
                        timestamp = try { f.timestamp?.timeInMillis ?: 0L } catch (_: Exception) { 0L },
                        isVideo = true
                    )
                }
            }
        }
        return out.sortedByDescending { it.timestamp }
    }

    /** 流式下载单个文件，支持断点续传（offset 为本地已有字节数） */
    @Synchronized
    fun download(
        remotePath: String,
        out: OutputStream,
        offset: Long,
        onProgress: (bytes: Long) -> Unit,
        shouldCancel: () -> Boolean
    ) {
        val c = requireClient()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        c.enterLocalPassiveMode()
        if (offset > 0) c.setRestartOffset(offset)

        val input = c.retrieveFileStream(remotePath)
            ?: throw IOException("无法读取远程文件：$remotePath")
        var transferred = 0L
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                if (shouldCancel()) throw InterruptedException("已取消")
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                transferred += n
                onProgress(transferred)
            }
        } finally {
            try { input.close() } catch (_: Exception) {}
            try { out.flush() } catch (_: Exception) {}
        }
        if (!c.completePendingCommand()) {
            throw IOException("传输未完成（服务器未确认），文件可能不完整")
        }
    }

    private fun join(dir: String, name: String): String =
        if (dir == "/") "/$name" else "$dir/$name"

    private fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        val i = trimmed.lastIndexOf('/')
        return if (i <= 0) "/" else trimmed.substring(0, i)
    }
}
