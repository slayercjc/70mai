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
 *  老版本 ES「远程管理器/View on PC」FTP 默认端口 3721（智能后视镜常用），
 *  新版 ES 用 2121，标准 FTP 用 21，三个端口都探测。 */
object NetScan {

    /** 取本机所有可能的 /24 网段。
     *  返回多个候选并全部扫描，避免"猜错网段"——手机开热点时蜂窝接口(10.x)
     *  与热点接口(10.x/192.168.x)可能同段或不同段，逐一扫描最可靠。
     *  兼容两种拓扑：
     *  1) 后视镜开热点、手机连入 —— 手机为 WiFi STA
     *  2) 手机开热点、后视镜连入 —— 手机为 AP */
    fun currentSubnets(ctx: Context): List<String> {
        val result = linkedMapOf<String, Boolean>() // 网段 -> 是否高优先级(热点接口/网关)
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // 1) WiFi STA 模式：手机连入热点（最可信，排最前）
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
                if (s != null) result[s] = true
            }
        }
        // 2) 枚举所有网卡：热点接口 ap* 或 .1 网关地址优先，其余私网段也纳入
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val host = a.hostAddress ?: continue
                        val s = subnetOf(host) ?: continue
                        if (!isPrivateIp(host)) continue
                        val hot = ni.name.contains("ap", ignoreCase = true) || host.endsWith(".1")
                        if (hot || !result.containsKey(s)) result[s] = hot || (result[s] ?: false)
                    }
                }
            }
        } catch (_: Exception) {
            // 枚举失败时至少保留 WiFi STA 的结果
        }
        // 高优先级(热点/网关)在前，其余在后
        return result.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** 兼容旧调用：返回第一个候选网段 */
    fun currentSubnet(ctx: Context): String? = currentSubnets(ctx).firstOrNull()

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

    /** 单网段扫描的兼容包装 */
    suspend fun scan(
        prefix: String,
        ports: List<Int> = listOf(3721, 2121, 21),
        timeoutMs: Int = 400,
        concurrency: Int = 48,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ScanResult> = scan(listOf(prefix), ports, timeoutMs, concurrency, onProgress)

    /** 并发扫描多个网段的 FTP 端口（3721 老版 ES / 2121 新版 ES / 21 标准），返回开放 FTP 的主机 */
    suspend fun scan(
        prefixes: List<String>,
        ports: List<Int> = listOf(3721, 2121, 21),
        timeoutMs: Int = 400,
        concurrency: Int = 48,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        val targets = prefixes.flatMap { prefix -> (1..254).map { "$prefix.$it" } }
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
