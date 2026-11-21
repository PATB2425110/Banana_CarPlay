package com.dbtool.carplay.geely.lynk

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * DBPlay **本机 ADB 客户端**（协议级实现，不依赖系统 adb）。
 *
 * ## 为什么需要
 *
 * 车机上想 `am start-activity --display N` 起副屏 Activity，需要一个能发命令的 ADB。
 * 系统 `adb` 不一定有、`setprop service.adb.tcp.port` 又常被 SELinux 拒。
 * DiPlay 的做法是**自己在 `127.0.0.1` 上连 adbd**（本机回环，无需改属性）。
 *
 * ## 协议（从 DiPlay(Lynk) 反编译提取，魔数与官方 ADB 规范一致）
 *
 * 包头 24 字节：command(4) arg0(4) arg1(4) dataLength(4) checksum(4) magic(4)，小端。
 *
 * | 命令 | 值 | 含义 |
 * |---|---|---|
 * | CNXN | 0x434E584E | 建连（版本 + 最大载荷 + 系统标识）|
 * | AUTH | 0x41555448 | 认证（arg0=1 TOKEN / 2 SIGNATURE / 3 PUBLIC_KEY）|
 * | OPEN | 0x4F50454E | 打开流（"shell:" 等）|
 * | OKAY | 0x4F4B4159 | 成功 |
 * | WRTE | 0x57525445 | 数据 |
 * | CLSE | 0x434C5345 | 关闭 |
 * | STLS | 0x53544C53 | 转 TLS（本实现不用）|
 *
 * `VERSION = 0x01000000`，`MAX_PAYLOAD = 262144`。
 *
 * ## 认证
 *
 * 自己生成 2048 位 RSA 密钥对（可持久化到 `adbkey.private`/`adbkey.public`），
 * 收到 `AUTH(TOKEN)` 时用私钥签名回 `AUTH(SIGNATURE)`；
 * 首次连接车机会弹「允许 USB 调试」→ [NOT_APPROVED]，等用户点允许。
 *
 * ## 四种连接态
 *
 * `READY` / `NOT_APPROVED`（等授权）/ `UNREACHABLE`（连不上 adbd）/
 * `UNSUPPORTED`（协议异常）。
 *
 * ⚠️ 未实机验证。车机是否开 `adbd`、端口是否 `127.0.0.1:5556`，须真机确认。
 */
class LynkLocalAdb(
    private val socketHost: String = "127.0.0.1",
    private val socketPort: Int = 5556,
) {

    companion object {
        private const val TAG = "DBPlay-LocalAdb"

        // ---- ADB 协议常量（魔数取自官方规范）----
        const val CNXN = 0x434E584E.toInt()
        const val AUTH = 0x41555448.toInt()
        const val OPEN = 0x4F50454E.toInt()
        const val OKAY = 0x4F4B4159.toInt()
        const val WRTE = 0x57525445.toInt()
        const val CLSE = 0x434C5345.toInt()

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_PUBLIC_KEY = 3

        const val VERSION = 0x01000000
        const val MAX_PAYLOAD = 262144
        const val HEADER_BYTES = 24

        const val CONNECT_TIMEOUT_MS = 2000
        const val READ_TIMEOUT_MS = 5000
        const val APPROVAL_TIMEOUT_MS = 60_000
        const val APPROVAL_RECHECK_MS = 1000
        const val MAX_COMMAND_TIMEOUT_MS = 30_000
    }

    /** 连接态。 */
    enum class Access { READY, NOT_APPROVED, UNREACHABLE, UNSUPPORTED }

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /**
     * 建连 + 认证。
     *
     * @param mayAsk true = 允许等用户在车机上点「允许调试」（最长 [APPROVAL_TIMEOUT_MS]）
     */
    @Synchronized
    fun connect(keyPair: KeyPair, mayAsk: Boolean): Access {
        closeQuietly()
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(socketHost, socketPort), CONNECT_TIMEOUT_MS)
            s.soTimeout = READ_TIMEOUT_MS
            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()
            handshake(keyPair, mayAsk)
        } catch (e: Exception) {
            Log.w(TAG, "connect 失败 host=$socketHost:$socketPort", e)
            closeQuietly()
            Access.UNREACHABLE
        }
    }

    /** 执行一条 shell 命令，返回 stdout。失败返回 null。 */
    @Synchronized
    fun shell(command: String, readTimeoutMillis: Int = READ_TIMEOUT_MS): String? {
        val out = output ?: return null
        return try {
            send(packet(OPEN, 0, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8)))
            val sb = StringBuilder()
            while (true) {
                val p = receive() ?: break
                when (p.command) {
                    OKAY -> Unit
                    WRTE -> {
                        sb.append(String(p.payload, Charsets.UTF_8))
                        // ack
                        send(packet(WRTE, p.arg0, p.arg1, ByteArray(0)))
                        if (sb.endsWith("\u0000")) break
                    }
                    CLSE -> break
                    else -> break
                }
            }
            sb.toString().trimEnd('\u0000', '\n', '\r')
        } catch (e: Exception) {
            Log.w(TAG, "shell 失败: $command", e)
            null
        }
    }

    @Synchronized
    fun close() = closeQuietly()

    // ---- 内部 ----

    private fun handshake(keyPair: KeyPair, mayAsk: Boolean): Access {
        // 发 CNXN
        val banner = "host::dbplay\u0000".toByteArray(Charsets.UTF_8)
        send(packet(CNXN, VERSION, MAX_PAYLOAD, banner))

        val deadline = System.currentTimeMillis() + if (mayAsk) APPROVAL_TIMEOUT_MS else READ_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val p = receive() ?: return Access.UNREACHABLE
            when (p.command) {
                CNXN -> return Access.READY
                AUTH -> when (p.arg0) {
                    AUTH_TOKEN -> {
                        // 用私钥签名 token 回应
                        val sig = sign(p.payload, keyPair.private)
                        send(packet(AUTH, AUTH_SIGNATURE, 0, sig))
                    }
                    AUTH_PUBLIC_KEY -> {
                        // 首次：把公钥给过去，车机会弹授权框
                        val pub = publicKeyMessage(keyPair.public as RSAPublicKey)
                        send(packet(AUTH, AUTH_PUBLIC_KEY, 0, pub))
                        if (!mayAsk) return Access.NOT_APPROVED
                        return awaitApproval()
                    }
                    else -> return Access.UNSUPPORTED
                }
                else -> return Access.UNSUPPORTED
            }
        }
        return Access.NOT_APPROVED
    }

    private fun awaitApproval(): Access {
        val deadline = System.currentTimeMillis() + APPROVAL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(APPROVAL_RECHECK_MS.toLong())
            // 重连一次看是否已授权
            closeQuietly()
            // 简化：返回 NOT_APPROVED，由上层重试 connect()
            return Access.NOT_APPROVED
        }
        return Access.NOT_APPROVED
    }

    /** ADB 签名：SHA1withRSA + PKCS#1 v1.5。 */
    private fun sign(token: ByteArray, key: PrivateKey): ByteArray =
        Signature.getInstance("SHA1withRSA").run {
            initSign(key)
            update(token)
            sign()
        }

    /** ADB 公钥报文：Android 格式 —— 长度前缀 + 模数 + 指数 + 名称。 */
    private fun publicKeyMessage(key: RSAPublicKey): ByteArray {
        val name = " dbplay@headunit\u0000".toByteArray(Charsets.UTF_8)
        val e = key.publicExponent.toByteArray()
        val n = key.modulus.toByteArray()
        val buf = java.io.ByteArrayOutputStream()
        fun writeInt(v: Int) {
            buf.write(v and 0xFF); buf.write((v ushr 8) and 0xFF)
            buf.write((v ushr 16) and 0xFF); buf.write((v ushr 24) and 0xFF)
        }
        writeInt(e.size)
        buf.write(e)
        writeInt(n.size)
        buf.write(n)
        writeInt(name.size)
        buf.write(name)
        return buf.toByteArray()
    }

    private fun packet(command: Int, arg0: Int, arg1: Int, payload: ByteArray) =
        AdbPacketData(command, arg0, arg1, payload)

    private fun send(p: AdbPacketData) {
        output?.write(p.encode())
        output?.flush()
    }

    private fun receive(): AdbPacketData? {
        val inp = input ?: return null
        val header = readFully(inp, HEADER_BYTES) ?: return null
        val b = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int
        val a0 = b.int
        val a1 = b.int
        val len = b.int
        b.int // checksum
        b.int // magic
        val payload = if (len > 0) readFully(inp, len) ?: ByteArray(0) else ByteArray(0)
        return AdbPacketData(cmd, a0, a1, payload)
    }

    private fun readFully(inp: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val r = inp.read(buf, off, count - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null; output = null; socket = null
    }

    /** ADB 包头（小端 24 字节）。 */
    data class AdbPacketData(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray) {
        fun encode(): ByteArray {
            val buf = java.nio.ByteBuffer.allocate(HEADER_BYTES + payload.size)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var sum = 0
            for (b in payload) sum += b.toInt() and 0xFF
            buf.putInt(command)
            buf.putInt(arg0)
            buf.putInt(arg1)
            buf.putInt(payload.size)
            buf.putInt(sum)
            buf.putInt(command xor 0xFFFFFFFF.toInt())
            buf.put(payload)
            return buf.array()
        }
    }

    /** 生成/加载 2048 位 RSA 密钥对。 */
    object Keys {
        const val KEY_BITS = 2048
        const val NAME = " dbplay@headunit"

        fun generate(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()

        fun littleEndian(value: BigInteger, size: Int): ByteArray {
            val out = ByteArray(size)
            val src = value.toByteArray()
            // 反序（小端）
            for (i in 0 until minOf(size, src.size)) out[i] = src[src.size - 1 - i]
            return out
        }
    }

    /** 便捷构造。 */
    fun from(context: Context): LynkLocalAdb = LynkLocalAdb()
}
