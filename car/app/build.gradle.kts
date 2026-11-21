import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// 版本号 —— **自动递增**，不需要手改
// ---------------------------------------------------------------------------
//
// 状态存在同目录的 `version.properties`（已入库）。每次**构建产物**时
// 把末位 +1 并写回：0.0.1 → 0.0.2 → … → 0.0.10 → 0.1.0 → … → 0.2.0
// （末位满 10 进位，见下面的 `bumpVersionName`）。
//
// 为什么改成自动：手改的版本号与"实际构建了几次"之间没有任何约束关系，
// 漏改一次就会出现两个内容不同的包共用一个版本号——现场回滚时
// 只能靠文件时间猜哪个是哪个，这正是"带版本号的 APK 名"想解决的问题本身。
//
// ⚠️ 四条必须知道的规则：
//
//   1. **只有构建产物才递增**：任务名以 assemble / bundle / package / install
//      开头才消耗版本号。跑单测（`testDebugUnitTest`）、`clean`、
//      纯 `compileDebugKotlin` 都不会让版本号跳动。
//
//   2. **构建失败不消耗版本号**：新值只在打包任务**成功之后**才写回
//      `version.properties`（见文件末尾的 `afterTask`）。所以连续编译失败
//      不会把版本号白白顶上去。
//
//   3. **递增发生在 Gradle 配置阶段** ⇒ **配置缓存命中会跳过整个配置阶段**，
//      于是版本号不递增、APK 名也沿用上一次的值（内容是新编的，只是名字没变）。
//      因此**构建 APK 必须带 `--no-configuration-cache`**——本项目的构建命令
//      一直如此（时间戳同理）。
//
//   4. `versionCode` 与 `versionName` 末位**同步** +1，不单独维护。
//      注意 `versionCode` 是**纯计数器**（只 +1、从不进位、永远单调递增），
//      它是"安装覆盖"和"新旧比较"的唯一依据；`versionName` 则**满 60 进位**
//      （2026-10-13 斌哥口径：`1.0.60` 的下一版是 `1.1.0`，见 [bumpVersionName]）。
//
// "关于"页显示的版本号来自 `PackageManager` 读回的 `versionName`，
// APK 文件名用的是同一个变量，二者天然一致，不需要去别处同步。
val versionStateFile = file("version.properties")

/** 读取上一次构建用的版本号。文件不存在时从 `0.0.0` 起步。 */
fun readVersionState(): Pair<String, Int> {
    if (!versionStateFile.exists()) return "0.0.0" to 0
    val props = Properties()
    versionStateFile.inputStream().use { stream -> props.load(stream) }
    val name = props.getProperty("versionName")?.trim().orEmpty().ifEmpty { "0.0.0" }
    val code = props.getProperty("versionCode")?.trim()?.toIntOrNull() ?: 0
    return name to code
}

/**
 * `1.0.58` → `1.0.59` → `1.0.60` → `1.1.0` → … → `1.1.60` → `1.2.0` …
 *
 * **满 60 进位**（2026-10-13 斌哥口径，verbatim）：
 *
 *   > 到了60就向前进一位，比如1.0.60往前就是1.1.0
 *
 * 规则：末位 +1；到 60 就**进位**给中位并把自己归零（`1.0.60` → `1.1.0`），
 * 中位到 60 同样进位给首位并归零（`1.1.60` → `1.2.0`）。
 *
 * ## 口径演进（三轮，逐次覆盖）
 *
 * 1. 满 10 进位（早期）；
 * 2. **只 +1、不进位**（2026-10-08）：「以后出包不需要满 10 递增，
 *    举例：1.0.22 1.0.23」——`1.0.99` 的下一版是 `1.0.100`；
 * 3. **满 60 进位**（2026-10-13，当前）——把 2 推翻：不再无限累加，
 *    到 60 就往前挪一位，版本号保持三段可读。
 *
 * ## `versionCode` 不参与进位
 *
 * `versionCode`（纯计数器）依旧**只 +1、从不进位、永远单调递增**：
 * 它是"安装覆盖"与"新旧比较"的唯一依据，进位会让新版本反而比旧版本小。
 * 只有 `versionName` 按上面三段式进位。
 *
 * ## 非数字段
 *
 * 段数不固定（`1.0.9` / `1.2.3` 都行）。出现非数字段（例如手工写成
 * `1.0.0-beta`）时退化成"在后面接一个 .1"，不抛异常——构建脚本不该因为
 * 版本号格式把整个构建弄挂。
 */
fun bumpVersionName(name: String): String {
    val parts = name.split('.')
    val numbers = parts.map { it.trim().toIntOrNull() }
    if (numbers.any { it == null } || numbers.isEmpty()) return "$name.1"

    val out = numbers.map { it!! }.toMutableList()
    out[out.lastIndex] += 1

    // 从末位往前进位：满 60 就把当前位归零、前一位 +1；首位不再往上进。
    var index = out.lastIndex
    while (index > 0 && out[index] >= VERSION_CARRY_THRESHOLD) {
        out[index] = 0
        out[index - 1] += 1
        index -= 1
    }
    return out.joinToString(".")
}

/** `versionName` 各段满多少就进位（2026-10-13 斌哥口径）。 */
val VERSION_CARRY_THRESHOLD = 60

/** 会产生构建产物的任务名前缀。 */
val artifactTaskPrefixes = listOf("assemble", "bundle", "package", "install")

val buildsArtifact: Boolean = gradle.startParameter.taskNames.any { raw ->
    val simpleName = raw.substringAfterLast(':')
    artifactTaskPrefixes.any { prefix -> simpleName.startsWith(prefix) }
}

// ------------------------------------------------------------ 出包审批闸门
//
// 斌哥的口径（2026-10-04，verbatim）：
//
//   > 版本号你改回 0.9.0，要我允许后才构建 APK 依次递增版本号
//
// 也就是说**版本号只在"斌哥点了头"的那次构建里递增**，其他时候光是编译
// 不该吃版本号。这不是靠自觉，而是靠这道闸门——想安全地试编译就带
// `-PallowRelease=false`，想真出包必须显式带 `-PallowRelease=true`。
//
// ⚠️ 默认**拒绝**：不带参数直接 `assembleDebug` 会在配置阶段失败并告诉你怎么做。
//    （默认放行等于闸门不存在——"忘了带参数"正是最该被挡住的情况。）
val allowRelease: Boolean =
    (project.findProperty("allowRelease") as? String)?.trim()?.lowercase() == "true"

if (buildsArtifact && !allowRelease) {
    throw GradleException(
        "⛔ 出包被闸门拦下：本次构建会产生 APK/安装包，但没有得到出包许可。\n" +
            "\n" +
            "斌哥的规矩：版本号只在许可的那次构建里递增，不许可就不许出包。\n" +
            "\n" +
            "  · 只想验证能不能编过（不吃版本号、不产 APK）→ 用 compile/test 任务，\n" +
            "    或在命令后加 -PallowRelease=false\n" +
            "  · 确实要出包（版本号将递增并写回）→ 加 -PallowRelease=true\n",
    )
}

// ------------------------------------------------------------ 工程模式开关
//
// 2026-10-19 车主口径：「现在出一版工程模式版本，暂停授权模式」。
// 起因：仪表主题模块（P1.5）带着整套授权门禁上线后，在**没有服务端授权、
// 也读不到 VIN 的环境**（模拟器）里点「仪表主题」入口只会弹授权窗，
// 打不开主题页、看不到界面，开发调试被卡死。
//
// 工程模式 = 构建期 BuildConfig 开关，**默认关闭**：
//   · 打开方式：`-Pdbplay.engineering=true`（只作用于带该参数的那次构建）；
//   · 刻意**不做成运行时开关**：那等于给最终用户留一个"自己关掉付费授权"
//     的口子，仪表主题授权（DBP 授权码）会直接失效；
//   · 打开后 MainActivity 把仪表主题模块按"已授权"放行（入口直达主题页），
//     云端 catalog 的按主题锁定也一律视为已授权；普通出包零行为差异。
val dbplayEngineering: Boolean =
    (project.findProperty("dbplay.engineering") as? String)?.trim()?.lowercase() == "true"

if (dbplayEngineering) {
    logger.lifecycle("DBPlay 工程模式：ENGINEERING_MODE=true（暂停仪表主题授权门禁）")
}

// -------------------------------------------- 主题链路 VIN 校验放行（测试开关）
//
// 2026-10-19 车主要求：「先把 VIN 校验暂停一下，我要测试」（确认范围＝仪表主题）。
//
// 放掉的是**仪表主题授权链路**那一层 VIN 校验（`ThemeAccount.normalizeVin` 的
// `^[A-HJ-NPR-Z0-9]{17}$` ⇒ VIN 读不到/非标准号就 null ⇒ 扫码卡整块不渲染、
// 「输入授权码」入口隐藏、提交按钮禁用），**不是** `CarVinReader` 读取链路的
// `isValid`（那层留着，见 `docs/DBPlay-VIN实现-完整还原手册` §8：三套口径不能合并）。
//
// 同样是构建期开关、默认 false：
//   · 打开方式 `-Pdbplay.themeVinFree=true`；
//   · 打开后读不到 VIN 时用占位值顶上，让扫码卡 / 授权码入口能打开走通流程；
//   · 占位值见 MainActivity 的 THEME_VIN_PLACEHOLDER（17 位、不含 I/O/Q，
//     能过 `normalizeVinForRequest`，日志里一眼能看出不是真 VIN）。
// ⚠️ 与工程模式**无关**：工程模式管"模块入口 + 按主题锁定"，这个开关管 VIN 字符校验，
//    两层各自独立，需要哪个加哪个。
val dbplayThemeVinFree: Boolean =
    (project.findProperty("dbplay.themeVinFree") as? String)?.trim()?.lowercase() == "true"

if (dbplayThemeVinFree) {
    logger.lifecycle("DBPlay 测试开关：THEME_VIN_FREE=true（仪表主题链路 VIN 校验已放行）")
}

val (lastVersionName, lastVersionCode) = readVersionState()
// User-authorized replacement product: keep EXACT baseline name/code for in-place
// replacement. This rule exists only in DBPlay-ImmersiveNav, never the old project.
val dbplayVersionName = lastVersionName
val dbplayVersionCode = lastVersionCode

if (buildsArtifact) {
    logger.lifecycle("DBPlay 版本：$dbplayVersionName ($dbplayVersionCode)，上一版 $lastVersionName")
}

// 打包任务成功后落盘新版本号。
//
// 刻意放在**执行阶段**而不是配置阶段直接写：配置阶段写的话，一次编译失败
// 也会吃掉一个版本号，而用户拿不到任何 APK——版本号与产物就对不上了。
// `doLast` 天然只在任务**成功**后执行，失败的任务不会走到这里。
//
// 判据用「任务输出里真的有 .apk」而不是任务名：AGP 下 `package` 开头的任务
// 不止一个（`packageDebugResources` / `packageDebugAssets` …），
// 只看名字会把它们一起收进来，导致资源打包成功就写盘。
//
// ⚠️ 必须**同时认文件与目录**：AGP 把打包任务的输出注册成 apk **所在目录**
// （`outputs/apk/debug`），只判断 `name.endsWith(".apk")` 会永远为 false、
// 版本号永远写不回去（已踩过）。
tasks.configureEach {
    if (!buildsArtifact) return@configureEach
    if (!name.startsWith("package")) return@configureEach
    // ⚠️⚠️ 这三行**不能省**，也不能改成在 `doLast` 里直接引用上面那几个顶层属性。
    //
    // Kotlin DSL 里脚本顶层 `val` 是**脚本类的成员**，而 `doLast` 闭包会被 Gradle
    // 配置缓存序列化。闭包一旦直接读它们，配置缓存就报
    // 「cannot serialize Gradle script object references」并**让整个构建失败**
    // （已踩：只有 `packageDebugResources` / `packageDebug` 这两个被加了 doLast
    // 的任务报错，其余任务正常——报错任务恰好等于被加 doLast 的任务，
    // 是这个问题最直接的指纹）。
    //
    // 在**配置阶段**先把值取成局部量，闭包捕获的就只剩 File / String / Int
    // 这些可序列化的值，配置缓存恢复正常。
    val stateFile = versionStateFile
    val versionNameForBuild = dbplayVersionName
    val versionCodeForBuild = dbplayVersionCode
    doLast {
        val producedApk = outputs.files.files.any { output ->
            when {
                output.isFile -> output.name.endsWith(".apk", ignoreCase = true)
                output.isDirectory -> output.listFiles().orEmpty()
                    .any { it.name.endsWith(".apk", ignoreCase = true) }

                else -> false
            }
        }
        if (producedApk) {
            stateFile.writeText(
                "versionName=$versionNameForBuild\nversionCode=$versionCodeForBuild\n",
            )
            logger.lifecycle("DBPlay 打包完成：$versionNameForBuild ($versionCodeForBuild)")
        }
    }
}

// APK 文件名里的时间戳。取 **Gradle 配置阶段**的时刻，也就是这次构建的发起时间。
//
// ⚠️ 与 **configuration cache** 的交互（实测确认过）：
// 配置缓存命中时**整个配置阶段被跳过**，`onVariants` 不会执行，
// 于是时间戳沿用上一次写入缓存时的值。
// 这与上面的版本号自动递增是同一个前提 ⇒ 构建 APK 一律加
// `--no-configuration-cache`：
//   ./gradlew :car:app:assembleDebug --no-configuration-cache
val dbplayBuildStamp: String =
    SimpleDateFormat("yyyyMMddHHmm", Locale.CHINA).format(Date())

// ---------------------------------------------------------------------------
// 可选：把原生库限制成单个 ABI —— 逃生开关，正常构建**不需要**
// ---------------------------------------------------------------------------
//
// ## 背景
//
// 随包的 `libdbplayadb.so` / `libdbplaybusybox.so` **只有 32 位 ARM 版本**
// （来源见 ADB-HOTSPOT.md §2.2）。而 Android 的安装器**只解压首选 ABI 的原生库**：
// 车机首选 ABI 是 `arm64-v8a` 时，`/data/app/…/lib/arm/` 根本不会被创建，
// 两个二进制虽然躺在 APK 里却拿不到可执行的文件系统路径 ⇒ 热点起不来。
//
// **实车证据**（2026-09-29，车机 Android 9 / QUALCOMM CS11，`nativeLibraryDir=…/lib/arm64`）：
// 第 1 步「随包二进制与 ABI」失败，随后被迫回落到该车机上不可用的
// `LOCAL_ONLY_HOTSPOT`，白等 60 秒后失败。
//
// ## 现在的解法：镜像到 `arm64-v8a/`，**不是**这个开关
//
// `car/core-bridge/src/main/jniLibs/arm64-v8a/` 里放了与 `armeabi-v7a/` **逐字节相同**
// 的那两个 32 位 ELF（理由与边界见该目录的 `README.md`）。这样：
//
//   - 首选 `arm64-v8a` 的设备 → 解压出 `lib/arm64/` → 找到文件 → 应用仍是 **64 位进程**；
//   - 首选 `armeabi-v7a` 的设备 → 解压出 `lib/arm/` → 找到文件 → 照旧工作。
//
// ⇒ **不需要在构建时记得加任何参数**，也不用把整个应用压成 32 位。
//
// ## 那这个开关还留着干什么
//
// 两个逃生用途：
//   1. **纯 64 位车机**（`abilist32` 为空）—— 32 位 ELF 无论如何都跑不了，
//      此时限成 `arm64-v8a` 可以少带 3.3 MB 的无用负载；
//   2. 万一某个 OEM 安装器对"ABI 目录里放错架构的 ELF"有额外校验而拒绝解压，
//      可以退回 `-Pdbplay.abi=armeabi-v7a`（代价见下）。
//
// ⚠️ 用 `-Pdbplay.abi=armeabi-v7a` 的代价是**整个应用跑成 32 位进程**
// （实测 vivo：`primaryCpuAbi=armeabi-v7a`）：堆上限更低，而车机上 1080p H.264
// 解码实测只有 20~56 fps，没必要冒这个险。另外**极少数 64-bit-only 车机会直接装不上**
// （`INSTALL_FAILED_NO_MATCHING_ABIS`）——那比"热点用不了"更糟。
//
//   ./gradlew :car:app:assembleDebug -Pdbplay.abi=armeabi-v7a --no-configuration-cache
//
// 判据看开发者页「ADB 热点自检」第 1 步：它会直接报出首选 ABI、`nativeLibraryDir`
// 与建议。
val dbplayAbiFilter: String? =
    providers.gradleProperty("dbplay.abi").orNull?.trim()?.takeIf { it.isNotEmpty() }

if (dbplayAbiFilter != null) {
    logger.lifecycle("DBPlay 原生库限制为 $dbplayAbiFilter（逃生开关；正常构建不需要）")
}

// ---------------------------------------------------------------------------
// release 签名：`car/keystore/`（随仓库分发，见 `car/keystore/keystore.properties`）
// ---------------------------------------------------------------------------
//
// ## 为什么不用 Android 自带的 debug keystore
//
// debug keystore（`~/.android/debug.keystore`）是**每台开发机各自生成**的、
// 口令公开的调试身份。用它签发布包有两个后果：
//   1. 换一台机器构建就换了签名身份 ⇒ 装不上去；
//   2. 任何人拿到 debug key 都能伪造一个"可覆盖安装"的包。
// 所以这里用项目自有的 `dbplay-release.jks`（2026-09-30 新建）。
//
// ⚠️ **代价（必须知道）**：车机上若装的是 debug 变体（debug key 签的），
// 与这把 key **签名不同** ⇒ release 包**装不上去**，必须先卸载旧版。
// 卸载会清掉 DBPlay 的设置（热点名/密码等）。这是"换签名身份"的固有代价，
// 不是 bug。
//
// ## 凭据文件读不到时会怎样
//
// `car/keystore/keystore.properties` 缺失（例如被裁剪过的外部克隆）⇒
// **不创建** release 签名配置 ⇒ AGP 产出 `*-unsigned.apk`，并打一行提示。
// 这样"本地没有私钥"不会让任何人的构建直接失败。
// ⚠️ 用简名 `Properties()` 而不是 `java.util.Properties()`：这个脚本里
// **全限定名解析不了**（Kotlin DSL 预编译脚本的隐式导入里有同名/同前缀的符号，
// 报的是 "Unresolved reference 'load'/'getProperty'" 这种看不懂的错）。
// 文件顶部已经有 `import java.util.Properties`，直接用简名即可。
val dbplaySigningDir = project.findProperty("dbplay.signingDir")?.toString()?.let(::File)
    ?: rootProject.file("car/keystore")
val dbplayKeystoreProperties = Properties().apply {
    val file = File(dbplaySigningDir, "keystore.properties")
    if (file.isFile) file.inputStream().use { stream -> load(stream) }
}

val dbplayHasReleaseSigning: Boolean = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword",
).all { !dbplayKeystoreProperties.getProperty(it).isNullOrBlank() }

if (!dbplayHasReleaseSigning) {
    logger.lifecycle(
        "DBPlay：未找到 car/keystore/keystore.properties（或字段不全）" +
            "⇒ release 包**不签名**。debug 构建不受影响。",
    )
}

android {
    packaging.jniLibs.excludes.add("**/libdbplayadb.so")
    packaging.jniLibs.excludes.add("**/libdbplaybusybox.so")
    namespace = "com.dbtool.carplay.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // ------------------------------------------------------------------
        // applicationId：**故意占用帆书车机版的包名**
        // ------------------------------------------------------------------
        //
        // 车机把「帆书」这个媒体源**固定映射到包名 `io.dushu.car`**：桌面 / 媒体卡片 /
        // 仪表 DimMenu 上点「帆书」时，系统按包名去拉起那个应用。
        // DBPlay 的媒体卡片本身是靠"伪装帆书"的广播通道喂出来的
        // （见 `:car:platform-ecarx` 的 `FdBookBroadcaster`），卡片上显示的就是帆书，
        // 于是点它时车机找的也是 `io.dushu.car` —— 包名对不上，点了就没反应。
        //
        // 原版 `GeelyCarPlay` 之所以"点帆书卡片就打开自己"，靠的正是它的包名整体
        // 就叫 `io.dushu.car`（`GeelyCarPlay-反编译/geelycarplay/_manifest.txt` 第 1 行），
        // **而不是任何代码**（全树 `getLaunchIntentForPackage` 零命中）。
        //
        // ⚠️ 只改 applicationId，**namespace 不动**（仍是 `com.dbtool.carplay.app`，
        //    代码里的 `com.dbtool.carplay.*` 包名一律不改）。这样做是安全的，因为全工程
        //    **没有任何一处写死包名字面量**：更新器、FileProvider authority
        //    （`${applicationId}.fileprovider`）、`package:` URI、版本读取
        //    全部走 `context.packageName` 动态取。
        //
        // ⚠️ 两条代价（必须知道）：
        //   1. 与**真帆书车机版**同包名 ⇒ 两者不能共存，装了本应用就装不了帆书；
        //   2. 从旧的 `com.dbtool.carplay` 升级**必须卸载重装**（包名变了 = 新应用 =
        //      新数据目录），**设置会丢** —— 与换签名是同一类代价。
        //   回退：把这一行改回 `com.dbtool.carplay` 再重装即可。
        //
        // 另：它与上游 `com.shilapi.xcertplay` 仍然不同 ⇒ 两者依旧可共存安装，
        // 便于在真机上对比新旧 UI 行为。
        // 用户要求恢复帆书车机包名；这是新的安装身份，不覆盖旧osn.play的数据。
        applicationId = "io.dushu.car"
        minSdk = 28
        targetSdk = 37
        versionCode = dbplayVersionCode
        versionName = dbplayVersionName

        // 见文件上方「工程模式开关」。默认 false ⇒ 普通出包零行为差异。
        buildConfigField("boolean", "ENGINEERING_MODE", dbplayEngineering.toString())

        // 见文件上方「主题链路 VIN 校验放行」。默认 false ⇒ 普通出包零行为差异。
        buildConfigField("boolean", "THEME_VIN_FREE", dbplayThemeVinFree.toString())

        // 见文件上方 `dbplayAbiFilter` 的说明：默认不设，只在需要时用
        // `-Pdbplay.abi=armeabi-v7a` 打开。
        dbplayAbiFilter?.let { abi -> ndk { abiFilters += abi } }
    }

    // release 的签名身份来自 `car/keystore/keystore.properties`（随仓库分发）。
    // 读不到就不创建 —— 见文件上方那一段的说明。
    signingConfigs {
        if (dbplayHasReleaseSigning) {
            create("release") {
                storeFile = File(dbplaySigningDir, dbplayKeystoreProperties.getProperty("storeFile"))
                storePassword = dbplayKeystoreProperties.getProperty("storePassword")
                keyAlias = dbplayKeystoreProperties.getProperty("keyAlias")
                keyPassword = dbplayKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 压缩 + 混淆。反射读的是车机系统类，不在这个包里。
            // 本应用自己按字段名反射的成员，以及写进设置的枚举名，留在 proguard-rules.pro。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // ⚠️ 这把 key 与旧 debug 变体**不同** ⇒ 这个包装不上去，
            // 必须先卸载旧版（设置会丢）。理由与取舍见文件上方 "release 签名" 一节。
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        // AGP 8+ 默认不再生成 BuildConfig；工程模式开关要用，显式打开。
        // （本模块此前没有 BuildConfig 依赖，理由见 MainActivity.appVersionName 注释。）
        buildConfig = true
    }

    // ---------------------------------------------------------------------
    // 原生库解压：**必须打开**，这不是性能选项，是功能前提
    // ---------------------------------------------------------------------
    //
    // `:car:core-bridge` 的 `jniLibs/armeabi-v7a/` 里放了两个可执行文件
    // （`libdbplayadb.so` = adb 客户端、`libdbplaybusybox.so` = 含 udhcpd 的
    // busybox），ADB 独立热点全靠它们跑起来。
    //
    // AGP 8 起 `useLegacyPackaging` 默认 **false** ⇒ 合并清单写
    // `android:extractNativeLibs="false"` ⇒ 安装时**不解压**原生库，
    // `/data/app/…/lib/<abi>/` 里**根本没有真实文件**（库直接从 APK 里 mmap），
    // 于是 `nativeLibraryDir` 指向一个不存在的目录，`File(...).isFile` 全 false。
    // 现场表现就是「缺少随包二进制」。
    //
    // 打开后安装器会把它们解压成 `0755`、SELinux `apk_data_file` 的真实文件，
    // 可以直接 `execve`（已在真机上核实过同类应用的权限位）。
    //
    // ⚠️ 代价是 APK 体积略增（两个文件共约 3.3 MB，压缩包→解压文件）。
    // 换来的是"能执行"，值。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // ---------------------------------------------------------------------
    // API 级别防线（配合根 `build.gradle.kts` 的 `subprojects` 规则）
    // ---------------------------------------------------------------------
    // 根构建已把 `NewApi` 在各 `:car:*` 模块里提到 **fatal**（理由与真车事故见根文件
    // 里那段注释）。但 release 门禁走的是 `lintVitalRelease`，它默认**只看本模块**的
    // 问题 —— 库模块里那条 NewApi 不会进 app 报告，vital 就卡不住。
    // 实测：只加根规则、不加下面这一行时，故意留一个 API 34 调用照样能出包；
    // 加上 `checkDependencies = true` 后同样的代码会被 `lintVitalRelease` 拦下。
    lint {
        checkDependencies = true
    }

}

// ---------------------------------------------------------------------------
// APK 输出名：DBPlay-<版本号>-<versionCode>-<yyyyMMddHHmm>.apk
// ---------------------------------------------------------------------------
//
// 默认输出名（`app-debug.apk` / `app-release.apk`）有两个问题：
//   1. 拷到车机上装完之后完全看不出是哪个版本，回滚时只能靠文件时间猜；
//   2. debug 与 release 的文件名不同，现场沟通时要额外说一句"是哪个"。
// 统一成带版本 + versionCode + 时间戳的名字后，"这个包是什么时候、
// 哪个版本、第几号构建"自解释。
//
// ## 2026-10-08（斌哥口径）：名字里**补上 versionCode**
//
// 车主原话：「**构建版本以后 DBPlay+版本号+versionCode+时间戳**」。
//
// 旧名只有 `DBPlay-<版本号>-<时间戳>`（如 `DBPlay-1.0.0-202610082130.apk`）。
// 问题在于 **`versionName` 末位是"只 +1、不进位"的人工计数**，前两位又只在
// 功能大改时才动 ⇒ 单看版本号分不出两个包哪个更新；而 `versionCode` 是
// **纯计数器、永不回退**，才是"安装覆盖 / 新旧比较"的唯一硬依据
// （见文件头部的版本号规则注释）。名字里带上它，回滚和装包时一眼可判。
//
// 分隔符统一用 `-`（不用 `+`）：`+` 在 URL 里会被解析成空格，
// 商店直链和发布会用到这个文件名，用 `-` 可以省掉一次转义。
//
// ⚠️ 这里只改**输出文件名**，不改产物内容，也不影响 `adb install`。
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                "AppleCarPlay-$dbplayVersionName-$dbplayVersionCode-$dbplayBuildStamp.apk",
            )
        }
    }
}

dependencies {
    // 产品 App 只依赖二开自己的模块 + 契约层。
    // 刻意**不依赖** :common（上游宿主 UI）与 :mobile/:automotive（上游应用壳）：
    //   - 避免把上游 3427 行的旧 UI 及其清单入口带进产品；
    //   - 让上游对这些文件的改动不会影响本应用的构建。
    // 对 upstream 的接触面完全收敛在 :car:core-bridge 里。
    implementation(project(":car:ui"))
    implementation(project(":car:core-bridge"))
    implementation(project(":car:platform-android"))
    implementation(project(":car:platform-ecarx"))
    // 吉利（eCarx）平台：在 eCarx 基线之上多出「仪表 HDMI2 副屏」能力。
    // ⚠️ 必须显式声明——CarPlayApplication.selectPlatform 要引用 GeelyCarPlatform。
    implementation(project(":car:platform-geely"))
    implementation(project(":car:steering"))
    implementation(project(":car:api"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // :car:app 原来没有单元测试，测试类路径上也就没有 JUnit。DexWarmup 的
    // 纯函数判定（装完要不要预热）必须能在本模块直接用 `:car:app:testDebugUnitTest`
    // 跑，所以这里把目录里已有的 `libs.junit` 接进 test 配置——没有引入新库，
    // 版本目录零改动。
    testImplementation(libs.junit)
}
