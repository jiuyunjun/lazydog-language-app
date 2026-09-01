package com.lazydog.english.core.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * 把一次请求拆成几段计时，在响应头到达时打一行日志。
 *
 * 为什么需要它：界面上「接通中」这一段跨了 DNS、建连、TLS、上传请求和等服务端返回头
 * 五件事，只知道"一共很久"没法修——是 DNS 解析卡住、IPv6 黑洞把建连耗在超时上，
 * 还是服务端自己慢，处理方式完全不同。这一行日志把它们分开。
 *
 * 用 `adb logcat -s LazyDogAI` 看，形如：
 * `听力 ⏱ DNS 21ms｜建连 8140ms｜TLS 260ms｜发请求 8ms｜等响应头 950ms｜合计 9.4s`
 *
 * 复用连接时不会有 DNS 和建连两段——那正是想看到的对照。
 */
internal class HttpTimingListener : EventListener() {

    private var callStart = 0L
    private var dnsMs = 0L
    private var connectMs = 0L
    private var tlsMs = 0L
    private var requestMs = 0L
    private var waitMs = 0L
    private var reusedConnection = true

    private var dnsStart = 0L
    private var connectStart = 0L
    private var tlsStart = 0L
    private var requestStart = 0L
    private var responseWaitStart = 0L

    override fun callStart(call: Call) {
        callStart = now()
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStart = now()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
        dnsMs += now() - dnsStart
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        reusedConnection = false
        connectStart = now()
    }

    override fun secureConnectStart(call: Call) {
        // 建连的计时到握手开始为止，TLS 单独算——两段慢的原因不一样。
        connectMs += now() - connectStart
        tlsStart = now()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsMs += now() - tlsStart
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        // 失败的那次尝试照样算进去：IPv6 黑洞的十秒就是这么耗掉的，不记就看不见。
        connectMs += now() - connectStart
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        if (reusedConnection) connectMs = 0
    }

    override fun requestHeadersStart(call: Call) {
        requestStart = now()
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        requestMs = now() - requestStart
        responseWaitStart = now()
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        if (request.body == null) {
            requestMs = now() - requestStart
            responseWaitStart = now()
        }
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        waitMs = now() - responseWaitStart
        log(call, "响应头 ${response.code}")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        log(call, "失败 ${ioe.javaClass.simpleName}")
    }

    private fun log(call: Call, outcome: String) {
        val total = now() - callStart
        val op = call.request().tag(String::class.java) ?: "ai"
        val parts = buildList {
            if (reusedConnection) add("复用连接") else add("新建连接")
            if (dnsMs > 0) add("DNS ${dnsMs}ms")
            if (connectMs > 0) add("建连 ${connectMs}ms")
            if (tlsMs > 0) add("TLS ${tlsMs}ms")
            add("发请求 ${requestMs}ms")
            if (waitMs > 0) add("等响应头 ${waitMs}ms")
            add("合计 ${total}ms")
        }
        com.lazydog.english.core.ai.AiLog.timing(op, outcome, parts.joinToString("｜"))
    }

    private fun now() = System.currentTimeMillis()
}

/**
 * 解析结果里把 IPv4 排在前面。
 *
 * OkHttp 按顺序逐个地址去连，而系统解析常把 IPv6 排在前面。在 IPv6 不通但有 AAAA 记录的网络上
 * （国内移动网络很常见），第一次连接就要先干等满 connectTimeout 才轮到 IPv4——这十秒整个落在
 * 用户盯着「接通中」的那段时间里。OkHttp 4.x 还没有 fastFallback，所以自己排一下序。
 *
 * 不丢掉 IPv6：真正的 IPv6-only 网络上 IPv4 会立刻返回 unreachable，几乎不耽误。
 */
internal object Ipv4FirstDns : okhttp3.Dns {
    override fun lookup(hostname: String): List<java.net.InetAddress> =
        ipv4First(okhttp3.Dns.SYSTEM.lookup(hostname))
}

/** 排序本身单独拎出来，方便直接测，不用真去解析域名。 */
internal fun ipv4First(addresses: List<java.net.InetAddress>): List<java.net.InetAddress> =
    addresses.sortedBy { it is java.net.Inet6Address }
