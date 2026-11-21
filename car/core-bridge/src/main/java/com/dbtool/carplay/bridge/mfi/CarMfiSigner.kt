package com.dbtool.carplay.bridge.mfi

import com.dbtool.carplay.api.developer.CarDeveloperEndpoints
import com.dbtool.carplay.bridge.developer.CarMfiBlockStore

/**
 * 本地环回服务需要的全部密码学能力。
 *
 * 刻意收窄成三个成员，与 upstream `MfiAuthenticator` 的语义对齐但不依赖它——
 * 这样 [CarMfiLoopbackServer] 完全不认识 Android，也不认识 upstream，
 * 可以在普通 JVM 单测里用一个假实现跑通真实 HTTP 往返。
 *
 * **不实现 `MfiAuthenticator` 是有意的**：那个接口在 upstream `:shared` 里，
 * 实现它会把 `:car:core-bridge` 的密码学代码和上游类型绑死，
 * 而上游未来给接口加成员时这里就会编译失败——但本层与上游之间
 * 其实只隔着一条 HTTP 契约，不应该有编译期耦合。
 */
interface CarMfiSigner {

    /** iAP2 MFi 协议主版本。MFi 3 固定为 3。 */
    val protocolMajor: Int

    /**
     * 要交给 iPhone 的证书字节。
     *
     * 返回的是**文件原始字节**（PKCS#7 容器整体），不是里面那张 X.509。
     * 这一点必须与 DiPlay 逐字节一致：`LocalMfiAuthenticationClient.readCertificate()`
     * 返回的就是 `certificate.p7b` 的完整内容，而 upstream 的
     * `Iap2MfiAuthenticationClient` 会把它直接放进 iAP2 `0xaa01` 的
     * `accessoryCertificate` 载荷。改成只发里面那张证书会破坏与 iPhone 的协商。
     */
    fun certificatePayload(): ByteArray

    /**
     * 对 32 字节摘要签名，返回 **raw 64 字节**（`r || s`），不是 DER。
     *
     * 调用方给进来的已经是摘要，实现**不得再哈希一次**。
     */
    fun signRaw(challenge: ByteArray): ByteArray
}

/**
 * [CarMfiSigner] 的"身份不可用"实现：任何操作都抛 [CarMfiIdentityException]。
 *
 * ## 为什么需要它
 *
 * 身份缺失时有两种做法：
 *
 * 1. 干脆不起环回服务，让上游在 `checkNotNull(config.remoteMfiServer)` 处失败。
 *    结果是用户看到一句 `Required value was null.`——完全不知道要去哪做什么。
 * 2. 服务照常起来，但每个端点都返回带原因的 503。
 *    上游 `RemoteMfiAuthenticationClient` 会把响应体里的 `detail` 拼进异常消息，
 *    于是一路透传到设置页/状态栏上，用户看到的是一句能照做的中文提示。
 *
 * 这里选 2。代价只是多一个几十行的类，换来的是"失败可诊断"。
 */
class UnavailableCarMfiSigner(private val reason: String) : CarMfiSigner {

    override val protocolMajor: Int get() = throw CarMfiIdentityException(reason)

    override fun certificatePayload(): ByteArray = throw CarMfiIdentityException(reason)

    override fun signRaw(challenge: ByteArray): ByteArray = throw CarMfiIdentityException(reason)
}

/**
 * [CarMfiSigner] 的「拉黑守卫」装饰器：本机处于拉黑状态时，任何操作都抛
 * [CarMfiIdentityException] —— 于是 [CarMfiLoopbackServer] 自动回 503 + 中文原因，
 * 上游把它一路透传到状态栏/设置页。
 *
 * ## 为什么在会话桥已经拦过一道之后还要这一道
 *
 * `CarPlaySessionBridge.startInternal()` 在**建栈之前**就查过拉黑状态，所以正常
 * 路径上这个守卫根本不会触发。它挡的是那次检查与真正握手之间的**时间窗**：
 * 拉黑是管理员在后台点的，车机完全可能正处在"已经查过、还没握手"的那几秒里。
 * 守卫在**每个请求**上现读本地落盘，于是那几秒里到达的证书/签名请求会被拒。
 * 没有它，"拦截"就只是一次性快照，而不是这条服务真的不签名。
 *
 * ⚠️ 它是 `internal`：唯一使用方是同一模块里的 `CarPlaySessionBridge`，
 * 没有理由把"拉黑"这件事泄露到模块外。
 */
internal class CarMfiBlockGuardedSigner(
    private val delegate: CarMfiSigner,
    private val store: CarMfiBlockStore,
) : CarMfiSigner {

    override val protocolMajor: Int get() = allowed().protocolMajor

    override fun certificatePayload(): ByteArray = allowed().certificatePayload()

    override fun signRaw(challenge: ByteArray): ByteArray = allowed().signRaw(challenge)

    private fun allowed(): CarMfiSigner {
        if (store.isBlocked) {
            throw CarMfiIdentityException(
                store.reason.ifEmpty { CarDeveloperEndpoints.BLOCKED_REASON_FALLBACK },
            )
        }
        return delegate
    }
}
