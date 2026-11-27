package com.slayercjc.ftpserver

import android.os.Environment
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 精简 FTP 服务器，专为手机端 commons-net FTPClient 设计。
 *
 * 兼容 Android 4.1（API 16）：
 *  - 只使用 java.net.* / java.io.* / java.text.* 等 API 1 基础类
 *  - 不使用 AndroidX、协程、java.time、StandardCharsets（API 19+）
 *  - 字符编码显式用 Charset.forName("UTF-8")
 *
 * 支持的命令（覆盖手机端 FtpRepo.kt 的全部调用）：
 *  USER PASS SYST FEAT OPTS TYPE PWD CWD CDUP PASV EPSV LIST NLST RETR REST SIZE MDTM NOOP QUIT
 *
 * 安全：只能访问 rootDir（外部存储根目录）之内的文件，阻止 ../ 越界。
 */
class FtpServer(
    private val port: Int = 3721,
    private val rootDir: File = Environment.getExternalStorageDirectory()
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        pool = Executors.newCachedThreadPool()
        try {
            val ss = ServerSocket(port)
            serverSocket = ss
            while (running) {
                val client = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (running) continue else break
                }
                try {
                    pool?.execute(ClientSession(client))
                } catch (_: Exception) {
                    try { client.close() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            running = false
        } finally {
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { pool?.shutdownNow() } catch (_: Exception) {}
        pool = null
    }

    private inner class ClientSession(val socket: Socket) : Runnable {
        private val utf8 = Charset.forName("UTF-8")
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), utf8))
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), utf8))
        private val fmtList = SimpleDateFormat("MMM dd HH:mm", Locale.US)
        private val fmtMdtm = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

        private var cwd = "/"                 // FTP 虚拟路径，始终以 / 开头
        private var restOffset = 0L           // REST 命令设置的断点
        private var pasvServer: ServerSocket? = null
        private var pasvPort = 0
        private var passive = true            // 默认被动模式（手机端 enterLocalPassiveMode 会显式切换）
        private var loggedIn = false

        override fun run() {
            try {
                socket.soTimeout = 0
                reply(220, "70mai Mirror FTP Server ready")
                while (running && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val sp = line.indexOf(' ')
                    val cmd: String
                    val arg: String
                    if (sp < 0) {
                        cmd = line.uppercase(Locale.US)
                        arg = ""
                    } else {
                        cmd = line.substring(0, sp).uppercase(Locale.US)
                        arg = line.substring(sp + 1).trim()
                    }
                    try {
                        handle(cmd, arg)
                    } catch (e: Exception) {
                        reply(451, "Local error: ${e.message ?: "unknown"}")
                    }
                }
            } catch (_: Exception) {
                // 连接断开属正常
            } finally {
                closePasv()
                try { socket.close() } catch (_: Exception) {}
            }
        }

        private fun handle(cmd: String, arg: String) {
            when (cmd) {
                "USER" -> {
                    loggedIn = true   // 匿名服务：任何用户名都接受
                    reply(331, "Password required")
                }
                "PASS" -> {
                    loggedIn = true
                    reply(230, "Login successful")
                }
                "SYST" -> reply(215, "UNIX Type: L8")
                "FEAT" -> {
                    writer.write("211-Features:\r\n")
                    writer.write(" UTF8\r\n")
                    writer.write(" MDTM\r\n")
                    writer.write(" SIZE\r\n")
                    writer.write(" REST STREAM\r\n")
                    writer.write("211 End\r\n")
                    writer.flush()
                }
                "OPTS" -> reply(200, "OK")
                "TYPE" -> reply(200, "Type set to ${arg.uppercase(Locale.US)}")
                "NOOP" -> reply(200, "OK")
                "PWD", "XPWD" -> reply(257, "\"${escape(cwd)}\" is current directory")
                "CWD", "XCWD" -> {
                    val target = resolve(arg)
                    if (target != null && target.isDirectory) {
                        cwd = toVirtual(target) ?: cwd
                        reply(250, "Directory changed")
                    } else {
                        reply(550, "No such directory")
                    }
                }
                "CDUP", "XCUP" -> {
                    val parent = resolve("..")
                    if (parent != null && parent.isDirectory) {
                        cwd = toVirtual(parent) ?: cwd
                        reply(250, "Directory changed to parent")
                    } else {
                        reply(250, "Already at root")
                    }
                }
                "PASV" -> enterPassive()
                "EPSV" -> {
                    // 手机端在 IPv4 下使用 PASV；EPSV 作为兼容补充。
                    // 只开监听端口，不应重复发 227 响应。
                    openPassiveSocket()
                    if (pasvPort > 0) reply(229, "Entering Extended Passive Mode (|||$pasvPort|)")
                    else reply(425, "Cannot open passive connection")
                }
                "LIST", "NLST" -> doList(cmd, arg)
                "RETR" -> doRetrieve(arg)
                "REST" -> {
                    restOffset = arg.toLongOrNull() ?: 0L
                    reply(350, "Restarting at $restOffset")
                }
                "SIZE" -> {
                    val f = resolve(arg)
                    if (f != null && f.isFile) reply(213, f.length().toString())
                    else reply(550, "No such file")
                }
                "MDTM" -> {
                    val f = resolve(arg)
                    if (f != null && f.exists()) reply(213, fmtMdtm.format(Date(f.lastModified())))
                    else reply(550, "No such file")
                }
                "QUIT" -> {
                    reply(221, "Goodbye")
                    try { socket.close() } catch (_: Exception) {}
                }
                else -> reply(502, "Command not implemented")
            }
        }

        // ---------- 被动模式 ----------
        private fun enterPassive() {
            openPassiveSocket()
            if (pasvPort > 0) {
                val p1 = pasvPort shr 8
                val p2 = pasvPort and 0xff
                reply(227, "Entering Passive Mode (${pasvAddress()},$p1,$p2)")
            } else {
                reply(425, "Cannot open passive connection")
            }
        }

        /** 仅打开被动数据端口，不发送响应（供 PASV / EPSV 复用） */
        private fun openPassiveSocket() {
            closePasv()
            pasvPort = 0
            try {
                // port=0 让系统分配随机端口；绑定 0.0.0.0 使手机可连
                val ss = ServerSocket(0)
                ss.soTimeout = 30000
                pasvServer = ss
                pasvPort = ss.localPort
            } catch (_: Exception) {
                pasvPort = 0
            }
        }

        /** PASV 必须返回客户端可达的 IP。优先用控制连接的本地地址（即后视镜的热点 IP）。 */
        private fun pasvAddress(): String {
            val local = socket.localAddress
            if (local is Inet4Address && !local.isLoopbackAddress && !local.isAnyLocalAddress) {
                return local.hostAddress.replace('.', ',')
            }
            // 兜底：枚举非回环 IPv4
            try {
                val eni = java.net.NetworkInterface.getNetworkInterfaces()
                while (eni.hasMoreElements()) {
                    val ni = eni.nextElement()
                    val adds = ni.inetAddresses
                    while (adds.hasMoreElements()) {
                        val a = adds.nextElement()
                        if (a is Inet4Address && !a.isLoopbackAddress && !a.isAnyLocalAddress) {
                            return a.hostAddress.replace('.', ',')
                        }
                    }
                }
            } catch (_: Exception) {}
            return "127,0,0,1"
        }

        private fun acceptData(): Socket? {
            val ss = pasvServer ?: return null
            return try {
                val s = ss.accept()
                pasvServer = null
                try { ss.close() } catch (_: Exception) {}
                s
            } catch (_: Exception) {
                null
            }
        }

        private fun closePasv() {
            try { pasvServer?.close() } catch (_: Exception) {}
            pasvServer = null
        }

        // ---------- LIST ----------
        private fun doList(cmd: String, arg: String) {
            val dir = if (arg.isBlank() || arg == "-a" || arg == "-l" || arg == "-al") {
                resolve(cwd)
            } else {
                resolve(arg)
            }
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                reply(450, "Directory not available")
                return
            }
            reply(150, "Opening ${if (cmd == "NLST") "ASCII" else "binary"} data connection for LIST")
            var data: Socket? = null
            try {
                data = acceptData()
                if (data == null) {
                    reply(425, "Can't open data connection")
                    return
                }
                val out = BufferedWriter(OutputStreamWriter(data.getOutputStream(), utf8))
                val children = dir.listFiles() ?: emptyArray()
                // 排序：目录在前，名称升序，与手机端展示习惯一致
                val sorted = children.sortedWith(
                    compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) }
                )
                for (f in sorted) {
                    if (cmd == "NLST") {
                        out.write(f.name)
                    } else {
                        out.write(unixLine(f))
                    }
                    out.write("\r\n")
                }
                out.flush()
                reply(226, "Transfer complete")
            } catch (e: Exception) {
                reply(451, "List error: ${e.message ?: "unknown"}")
            } finally {
                try { data?.close() } catch (_: Exception) {}
            }
        }

        /**
         * Unix ls -l 格式，匹配 commons-net UnixFTPEntryParser 的默认正则：
         * 权限 链接数 属主 组 大小 MMM dd HH:mm 名称
         */
        private fun unixLine(f: File): String {
            val perm = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
            val links = if (f.isDirectory) "2" else "1"
            val size = if (f.isDirectory) 4096L else f.length()
            val date = fmtList.format(Date(f.lastModified()))
            return "$perm $links 0 0 $size $date ${f.name}"
        }

        // ---------- RETR（含断点续传）----------
        private fun doRetrieve(arg: String) {
            val f = resolve(arg)
            if (f == null || !f.exists() || !f.isFile) {
                reply(550, "No such file")
                return
            }
            reply(150, "Opening binary data connection for $arg")
            var data: Socket? = null
            var input: BufferedInputStream? = null
            try {
                data = acceptData()
                if (data == null) {
                    reply(425, "Can't open data connection")
                    return
                }
                val out = BufferedOutputStream(data.getOutputStream(), 64 * 1024)
                input = BufferedInputStream(FileInputStream(f), 64 * 1024)
                var skipped = 0L
                if (restOffset > 0) {
                    skipped = try { input.skip(restOffset) } catch (_: Exception) { 0L }
                }
                restOffset = 0L
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                out.flush()
                reply(226, "Transfer complete ($total bytes)")
            } catch (e: Exception) {
                reply(451, "Retrieve error: ${e.message ?: "unknown"}")
            } finally {
                try { input?.close() } catch (_: Exception) {}
                try { data?.close() } catch (_: Exception) {}
            }
        }

        // ---------- 路径解析与安全 ----------
        /** FTP 虚拟路径 -> 真实 File，禁止越出 rootDir */
        private fun resolve(path: String): File? {
            return try {
                val virtual = if (path.startsWith("/")) normalize(path) else normalize("$cwd/$path")
                val file = File(rootDir, virtual.trimStart('/'))
                val real = file.canonicalPath
                val root = rootDir.canonicalPath
                if (real == root || real.startsWith(root + File.separator)) file else null
            } catch (_: Exception) {
                null
            }
        }

        /** 真实 File -> FTP 虚拟路径 */
        private fun toVirtual(file: File): String? {
            return try {
                val rel = file.canonicalPath.removePrefix(rootDir.canonicalPath).replace(File.separatorChar, '/')
                if (rel.isBlank()) "/" else if (rel.startsWith("/")) rel else "/$rel"
            } catch (_: Exception) {
                null
            }
        }

        private fun normalize(path: String): String {
            val parts = ArrayList<String>()
            for (p in path.split('/')) {
                when (p) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(p)
                }
            }
            return "/" + parts.joinToString("/")
        }

        private fun escape(s: String): String = s.replace("\"", "\"\"")

        // ---------- 响应 ----------
        private fun reply(code: Int, msg: String) {
            try {
                writer.write("$code $msg\r\n")
                writer.flush()
            } catch (_: Exception) {}
        }
    }
}
