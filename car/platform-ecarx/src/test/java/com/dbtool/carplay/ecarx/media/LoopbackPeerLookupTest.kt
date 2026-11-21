package com.dbtool.carplay.ecarx.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LoopbackPeerLookup] 的 `/proc` 解析单测。
 *
 * 全部通过注入的 `reader` 喂假数据：真实 `/proc` 在 CI/JVM 上既不稳定，
 * 也无法模拟「跨 uid 读 fd 被拒」这种车机上最常见的结果。
 */
class LoopbackPeerLookupTest {

    @Test
    fun `tcp 表里按 local_port 找到 inode 并映射到 pid`() {
        val files = mapOf(
            "/proc/net/tcp" to """
                  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
                   0: 0100007F:9C40 0100007F:D431 01 00000000:00000000 00:00000000 00000000  1000        0 424242 1 0 0 10 0
            """.trimIndent(),
            "/proc" to "1\n1234\n",
            "/proc/1/fd" to "0\n1",
            "/proc/1/fd/0" to "/dev/null",
            "/proc/1/fd/1" to "socket:[111]",
            "/proc/1234/fd" to "0\n7",
            "/proc/1234/fd/0" to "socket:[999]",
            "/proc/1234/fd/7" to "socket:[424242]",
            "/proc/1234/cmdline" to "com.geely.mediacard\u0000--flag\u0000",
        )

        assertEquals(
            "pid=1234 comm=com.geely.mediacard",
            LoopbackPeerLookup.resolve(40000) { files[it] },
        )
    }

    @Test
    fun `找不到端口返回 null`() {
        val files = mapOf(
            "/proc/net/tcp" to """
                  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
                   0: 0100007F:9C40 0100007F:D431 01 00000000:00000000 00:00000000 00000000  1000        0 424242 1 0 0 10 0
            """.trimIndent(),
            "/proc" to "1234\n",
            "/proc/1234/fd" to "7",
            "/proc/1234/fd/7" to "socket:[424242]",
        )

        assertNull(LoopbackPeerLookup.resolve(40001) { files[it] })
    }

    @Test
    fun `fd 目录读取被拒时返回 null 且不抛`() {
        val files = mapOf(
            "/proc/net/tcp" to """
                  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
                   0: 0100007F:9C40 0100007F:D431 01 00000000:00000000 00:00000000 00000000  1000        0 424242 1 0 0 10 0
            """.trimIndent(),
            "/proc" to "1234\n",
            // /proc/1234/fd 不在表里 → reader 返回 null，模拟 EACCES。
        )

        assertNull(LoopbackPeerLookup.resolve(40000) { files[it] })
    }

    @Test
    fun `非法端口直接返回 null 且不读 proc`() {
        var read = false
        val reader: (String) -> String? = {
            read = true
            null
        }

        assertNull(LoopbackPeerLookup.resolve(0, reader))
        assertNull(LoopbackPeerLookup.resolve(-1, reader))
        assertNull(LoopbackPeerLookup.resolve(65_536, reader))
        assertFalse(read)
    }

    @Test
    fun `tcp6 表里的 loopback 连接也能找到`() {
        val files = mapOf(
            "/proc/net/tcp6" to """
                  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
                   0: 00000000000000000000000001000000:9C40 00000000000000000000000001000000:D431 01 00000000:00000000 00:00000000 00000000  1000        0 515151 1 0 0 10 0
            """.trimIndent(),
            "/proc" to "4321\n",
            "/proc/4321/fd" to "3",
            "/proc/4321/fd/3" to "socket:[515151]",
            "/proc/4321/comm" to "com.geely.cluster\n", // cmdline 不可读时退回 comm
        )

        assertEquals(
            "pid=4321 comm=com.geely.cluster",
            LoopbackPeerLookup.resolve(40000) { files[it] },
        )
    }

    @Test
    fun `inode 对不上时继续扫后面的 pid`() {
        val files = mapOf(
            "/proc/net/tcp" to """
                  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
                   0: 0100007F:9C40 0100007F:D431 01 00000000:00000000 00:00000000 00000000  1000        0 424242 1 0 0 10 0
            """.trimIndent(),
            "/proc" to "100\n200\n",
            "/proc/100/fd" to "5",
            "/proc/100/fd/5" to "socket:[111]",
            "/proc/200/fd" to "6",
            "/proc/200/fd/6" to "socket:[424242]",
            "/proc/200/cmdline" to "com.geely.media\u0000",
        )

        assertEquals(
            "pid=200 comm=com.geely.media",
            LoopbackPeerLookup.resolve(40000) { files[it] },
        )
    }
}
