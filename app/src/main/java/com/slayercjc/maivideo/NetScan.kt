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
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/** 局域网 FTP 主机扫描：从手机当前 WiFi 网段里找出开 21 端口的设备 */
object NetScan {

    /** 取手机当前 WiFi 的 IPv4 与网关，得到 /24 网段前缀 */
    fun currentSubnet(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        // 优先取 WiFi 网络
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
            val parts = ip.split(".")
            if (parts.size == 4) return "${parts[0]}.${parts[1]}.${parts[2]}"
        }
        return null
    }

    data class ScanResult(val host: String, val openPorts: List<Int>)

    /** 并发扫描网段内 21 端口，返回开放 FTP 的主机 */
    suspend fun scan(
        prefix: String,
        ports: List<Int> = listOf(21),
        timeoutMs: Int = 400,
        concurrency: Int = 48,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        val targets = (1..254).map { "$prefix.$it" }
        val semaphore = kotlinx.coroutines.sync.Semaphore(concurrency)
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
