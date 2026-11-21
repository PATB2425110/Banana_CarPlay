# 为什么 `arm64-v8a/` 里放的是 **32 位** ARM 二进制

这两个 `.so` 与 `../armeabi-v7a/` 下的**是同一份文件**（SHA-256 逐字节相同）：

| 文件 | 真身 | 架构 | SHA-256 |
|---|---|---|---|
| `libdbplayadb.so` | `adb` 客户端 | ARM 32 位（`ELFCLASS32` / `EM_ARM`）静态 | `878f258d…ecbeb138` |
| `libdbplaybusybox.so` | busybox（含 `udhcpd`） | ARM 32 位静态 | `dc1975c5…7a48000` |

来源与逆向过程见仓库根 `ADB-HOTSPOT.md` §2.2。**它们只有 32 位版本，没有 arm64 版本。**

## 为什么必须"重复"一份放在这里

Android 的安装器**只解压设备首选 ABI 那一个目录**，而且用的是**旧式目录名**：

| 设备首选 ABI | 安装器解压出的目录 | 谁能在里面找到文件 |
|---|---|---|
| `arm64-v8a` | `/data/app/…/lib/arm64/` | 只放 `arm64-v8a/` 时 ✅ |
| `armeabi-v7a` | `/data/app/…/lib/arm/` | 只放 `armeabi-v7a/` 时 ✅ |

所以**只放 `armeabi-v7a/` 时，arm64 首选 ABI 的车机上 `lib/arm/` 根本不会被创建**
——两个二进制明明躺在 APK 里，却拿不到可执行的文件系统路径，ADB 独立热点在第 1 步就失败。

**实车证据**（2026-09-29，车机 Android 9 / QUALCOMM CS11，`nativeLibraryDir=…/lib/arm64`、
`SUPPORTED_ABIS=arm64-v8a, armeabi-v7a, armeabi`）：

```
ADB hotspot failed, falling back to LocalOnlyHotspot:
  第 1 步「随包二进制与 ABI」失败：随包二进制**没有落到磁盘上**，ADB 独立热点无法启动。
```

接着被迫回落到该车机上**不可用**的 `LOCAL_ONLY_HOTSPOT`，白等 60 秒后失败。

## 为什么不用 `-Pdbplay.abi=armeabi-v7a`

那个开关**也能**让文件被解压出来，但代价是**把整个应用压成 32 位进程**
（实测 vivo：`primaryCpuAbi=armeabi-v7a`）。车机上 1080p H.264 解码实测只有
20~56 fps，再降到 32 位（堆上限更低）是没必要的风险。

把 32 位 ELF 放进 `arm64-v8a/` 则两头都要：

- 应用仍是 **64 位进程**（`primaryCpuAbi=arm64-v8a`），视频解码不受影响；
- 两个二进制被解压到 `lib/arm64/`，**arm64 内核带 32 位兼容**（`abilist32` 非空）就能 `execve`。

`AdbShellRunner.locateBinaryDir()` 天然支持这种形态：它**先看 `nativeLibraryDir`**，
只有那里没有才去同级找 32 位目录。所以代码不用改。

## 已知边界（如实写在这里）

- **纯 64 位车机**（`ro.product.cpu.abilist32` 为空）仍然用不了 —— 内核跑不了 32 位 ELF。
  那种情况 `prepare()` 会在 ABI 预检处如实报出，不会静默失败。
- `arm64-v8a/` 下的**不是** arm64 代码，**不要**用 `System.loadLibrary` 去加载它们，
  也不要用 `nm` / `readelf -h` 之外的 ABI 工具去推断应用架构。
  它们只被 `ProcessBuilder` 按路径 `execve`。
- 真正的根治是补一份 **arm64 静态 `adb` + `busybox`**（那样就不需要这份镜像）。
  目前没有可信来源，故先采用镜像方案。
