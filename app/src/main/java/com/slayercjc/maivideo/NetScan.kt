package com.slayercjc.maivideo

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** 局域网 FTP 主机扫描：从本机所在网段里找出开放 FTP 端口的设备。
 *  后视镜上 ES 文件管理器的 FTP 服务默认端口 2121，部分版本/配置用 21，
 *  因此两个端口都探测。 */
object NetScan {

    /** 取本机所在局域网的 /24 网段前缀。
     *  兼容两种拓扑：
     *  1) 后视镜开热点、手机连入 —— 手机为 WiFi STA，走 TRANSPORT_WIFI
     *  2) 手机开热点、后视镜连入 —— 手机为 AP，需枚举网卡找热点接口 */
    fun currentSubnet(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // 1) WiFi STA 模式：手机连入热点
        if (cm != null) {
            val networks = cm.allNetworks ?: emptyArray()
            for (n: Network in networks) {
                val caps = try { cm.getNetworkCapabilities(n) } catch (_: Exception) { null }
                    ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp: LinkProperties = try { cm.getLinkProperties(n) } catch (_: Exception) { null }
                    ?: continue
                val ip = lp.linkAddresses
                    .map { it.address }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress ?: continue
                val s = subnetOf(ip)
                if (s != null) return s
            }
        }
        // 2) 手机开热点模式：枚举网卡，优先热点接口(ap*)与网关地址(.1)
        try {
            val candidates = mutableListOf<Pair<String, String>>()
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val host = a.hostAddress
                        if (host != null) candidates.add(ni.name to host)
                    }
                }
            }
            val ap = candidates.firstOrNull { it.first.contains("ap", ignoreCase = true) }
            if (ap != null) {
                val s = subnetOf(ap.second)
                if (s != null) return s
            }
            val gw = candidates.firstOrNull { it.second.endsWith(".1") }
            if (gw != null) {
                val s = subnetOf(gw.second)
                if (s != null) return s
            }
            val priv = candidates.firstOrNull { isPrivateIp(it.second) }
            if (priv != null) {
                val s = subnetOf(priv.second)
                if (s != null) return s
            }
        } catch (_: Exception) {
            // 枚举失败则由界面提示手动输入 IP
        }
        return null
    }

    private fun subnetOf(ip: String): String? {
        val p = ip.split(".")
        return if (p.size == 4) "${p[0]}.${p[1]}.${p[2]}" else null
    }

    private fun isPrivateIp(ip: String): Boolean {
        val p = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 ||
                (p[0] == 172 && p[1] in 16..31) ||
                (p[0] == 192 && p[1] == 168) ||
                (p[0] == 169 && p[1] == 254)
    }

    data class ScanResult(val host: String, val openPorts: List<Int>)

    /** 并发扫描网段内 FTP 端口（2121 为 ES 默认，21 为备用），返回开放 FTP 的主机 */
    suspend fun scan(
        prefix: String,
        ports: List<Int> = listOf(2121, 21),
        timeoutMs: Int = 400,
        concurrency: Int = 48,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        val targets = (1..254).map { "$prefix.$it" }
        val semaphore = Semaphore(concurrency)
        var scanned = 0
        val lock = Any()

        coroutineScope {
            targets.map { host ->
                async {
                    semaphore.withPermit {
                        val open = mutableListOf<Int>()
                        for (p in ports) {
                            try {
                                Socket().use { s ->
                                    s.connect(InetSocketAddress(host, p), timeoutMs)
                                    open.add(p)
                                }
                            } catch (_: Exception) {
                                // 端口关闭或不可达，忽略
                            }
                        }
                        synchronized(lock) {
                            scanned++
                            if (scanned % 16 == 0) onProgress(scanned, targets.size)
                        }
                        if (open.isNotEmpty()) ScanResult(host, open) else null
                    }
                }
            }.awaitAll().filterNotNull()
        }.sortedBy { it.host }
    }
}
