# 车辆状态读取及安全开关Release交付（2026-11-21）

> **最终交付**：`AppleCarPlay-1.4.2-375-202611210125.apk`，6,173,059 bytes，SHA256 `42775B7FBF1664BDED474D4E532141F038957AEE9D67886E4E1FE2D43045C314`。签名Release实际`BUILD SUCCESSFUL in 1m 37s`／exit0，8模块测试2468项0失败／错误／跳过。已复制到指定交付目录并重算摘要一致；原证书／SDK28验签、zipalign、版本／Manifest／DEX和原工程／历史文件校验通过。未加固、未安装、未实车验收。

## 授权与范围

用户在源码／本地验证完成后问“为什么不出包”，本轮据此执行出包交付。前阶段只执行编译／测试而未出APK，不是编译失败；不把此前“未出包”记录改写成已经交付。

本包包含燃油车Vehicle Status A100/A101/A102订阅与三通道独立读取，以及默认关闭的“读取车辆状态”开关和异常退出回退。只读燃油续航、外温、原车低油警告、Twli车身昼夜，未知不填0，不执行车辆控制setter。

- 保持Apple CarPlay／`io.dushu.car`／1.4.2(code375)／原DBPLAY证书。
- 只在独立DBPlay-ImmersiveNav出包，以`-PallowRelease=true --no-configuration-cache`显式放行；原签名目录只供Gradle读取，不输出或交付凭据／私钥。
- 和此前2244交付一样为签名、未加固Release，不冒称protected-signed。
- 新增时间戳文件，不覆盖历史APK、已交付API文档或其记录；不自动安装、卸载、清数据／配对，不提交、推送或发布。
- 默认关闭；用户确认开启后手动重连，收到手机A100才开始读取。关闭不主动停止CarPlay／导航／音乐。原生崩溃／杀进程后只能在下次启动凭标记关闭，无法保证当次进程自救。
- 系统杀进程／断电也可能保守关闭；未实车验收、不保证绝不闪退或已获vendor权限。其它限制见`vehicle-read-safety-switch-20261121.md`。

## Release专项复核

打包前发现`VehicleReadCrashClassifier`按`com.dbtool.carplay.geely.vehicle.`调用栈前缀识别异常，R8默认可改名而破坏这部分识别。限定在产品`proguard-rules.pro`添加该小包的`-keepnames`，不保留全部业务代码，不更改协议或厂商反射方法。既有源码接线用例补充该规则断言，打包后再核对R8 mapping。类名保留不保证优化内联后每次异常都能准确归因，活跃读取标记仍是主要保守回退。

## 验证／交付状态

本轮8模块（前阶段7模块加App）单测已全量强制执行并通过，签名Release已构建、核验并复制到指定目录。最终信息见本页首行和下方交付记录。

不复用此前2321项旧交付摘要。前阶段2446项不可变快照保留；本轮在R8规则补丁后重新验证8模块，另存2468项交付记录。

### 本轮测试真实结果

使用Android Studio JBR，实际执行：

```text
gradlew.bat :shared:testDebugUnitTest :car:api:testDebugUnitTest
  :car:core-bridge:testDebugUnitTest :car:platform-android:testDebugUnitTest
  :car:platform-ecarx:testDebugUnitTest :car:platform-geely:testDebugUnitTest
  :car:ui:testDebugUnitTest :car:app:testDebugUnitTest
  -PallowRelease=false --no-configuration-cache --rerun-tasks

> Task :car:api:compileDebugKotlin
> Task :car:platform-geely:compileDebugKotlin
> Task :car:app:compileDebugKotlin
> Task :car:app:testDebugUnitTest
> Task :car:core-bridge:testDebugUnitTest
BUILD SUCCESSFUL in 38s
182 actionable tasks: 182 executed
GRADLE_EXIT_CODE=0
```

`verify-vehicle-read-safety-release.cjs preflight`逐份解析JUnit XML、核对源文件和历史文件，实际exit0：

| 模块 | tests | failures / errors / skipped |
| --- | ---: | --- |
| shared | 469 | 0 / 0 / 0 |
| car/api | 153 | 0 / 0 / 0 |
| car/core-bridge | 1311 | 0 / 0 / 0 |
| car/platform-android | 8 | 0 / 0 / 0 |
| car/platform-ecarx | 62 | 0 / 0 / 0 |
| car/platform-geely | 291 | 0 / 0 / 0 |
| car/ui | 152 | 0 / 0 / 0 |
| car/app | 22 | 0 / 0 / 0 |
| **合计** | **2468** | **0 / 0 / 0** |

开包前冻结867个源码／Gradle／XML等所列范围构建输入摘要（不复制签名材料，不声称锁定全部SDK／NDK工具链）、105个历史交付目录顶层文件摘要，原DBPlay-dev基线1044项全部一致。交付前全部再次检查一致，构建期间所列代码和旧文件未变化。

测试日志：`C:\Users\17900\AppData\Local\Temp\opencode\vehicle-read-safety-release-tests-20261121.log`。

仍有既存deprecated API／override命名／测试空性等警告，不冒称无警告。这里是本地JVM／编译验证，不等于Android／iPhone／原车权限或native崩溃验收。

### 签名Release真实构建

```text
gradlew.bat :car:app:assembleRelease -PallowRelease=true
  -Pdbplay.signingDir=C:\Users\17900\dbplay-build\DBPlay-dev\car\keystore
  --no-configuration-cache

> Task :car:api:compileReleaseKotlin
> Task :car:ui:compileReleaseKotlin
> Task :car:platform-geely:compileReleaseKotlin
> Task :car:core-bridge:compileReleaseKotlin
> Task :car:app:compileReleaseKotlin
> Task :car:app:minifyReleaseWithR8
> Task :car:app:packageRelease
> Task :car:app:assembleRelease
BUILD SUCCESSFUL in 1m 37s
338 actionable tasks: 97 executed, 241 up-to-date
GRADLE_EXIT_CODE=0
```

Release未加`--rerun-tasks`，不能把241项UP-TO-DATE说成全部强制重跑。实际重新编译受影响模块并运行R8、打包，所列源码摘要在构建前后相同。

构建日志：`C:\Users\17900\AppData\Local\Temp\opencode\vehicle-read-safety-release-build-20261121.log`。

### 交付与核验

APK：

`C:\Users\17900\Desktop\饺子互联\Apple CarPlay\AppleCarPlay-1.4.2-375-202611210125.apk`

| 项目 | 实际结果 |
| --- | --- |
| 大小／SHA256 | 6,173,059 bytes／`42775B7FBF1664BDED474D4E532141F038957AEE9D67886E4E1FE2D43045C314` |
| 产品／身份 | Apple CarPlay／io.dushu.car／1.4.2(code375)，Release非debuggable。 |
| 证书SHA256 | `30ded7ab47342f2b3a4746f42c37544cdd6ed181d1e257c37d3c3cc93756a97e`，与原DBPLAY一致。 |
| 验签／对齐 | `apksigner verify --min-sdk-version 28`退出0，v2=true；`zipalign -c -p 4`退出0。不是设备安装记录。 |
| Manifest | vendor权限仅声明、android.car可选，无sharedUserId、无安装其它APK权限。声明不是系统授权。 |
| 遥测／安全功能入包 | 检出A100/A101/A102协议标签、读取开关／保护提示、独立prefs与活跃标记、标准property客户端和读取线程名。字符串／描述符存在不等于设备读值／崩溃回退成功。 |
| R8 | 48个仍存活的原始reader类名字保留；2个类已优化删除、6个R8生成synthetic类另记，不把它们当成原始存活类。关键protected／标准／legacy客户端的DEX类描述符在包中。 |
| 既有实现／移除项 | 导航／HUD桥接／沉浸／三卡等关键标记仍在，无旧HUD探针、ADB/BusyBox二进制、赞助／主题assets及已移除菜单。入包静态检查不是实车功能零回归保证。 |
| 原工程与历史交付 | 原工程1044项hash一致，原交付目录105个顶层文件全部一致；以COPYFILE_EXCL新增APK／清单／摘要，不覆盖旧文件。 |
| 设备与加固 | 未安装／清数据／清配对／发布；未实车验收；未加固，不标protected-signed。 |

`verify-vehicle-read-safety-release.cjs deliver`最终exit0，复制后重新计算APK摘要一致。项目记录为`vehicle-read-safety-release-delivery-20261121.json`；同名APK旁新增独立`-交付清单.json`、`-SHA256.txt`，没有更新／覆盖旧通用交付清单。

证据目录：

`C:\Users\17900\Desktop\饺子互联\Apple CarPlay\验证记录\AppleCarPlay-1.4.2-375-202611210125`

内含309份JUnit XML、两份真实Gradle日志、冻结输入摘要、R8 mapping、签名／badging／Manifest／zipalign结果、APK entries及本轮校验器；不交付私钥或密码。2468项XML摘要另存`vehicle-read-safety-release-test-summary-20261121.json`。

### 辅助核验失败与修正

第一次deliver核验exit1，发生在创建证据目录／复制APK之前：校验器错误要求R8 mapping中所有reader来源条目都“原名＝目标名”。实际mapping还列出删除占位`R8$$REMOVED$$CLASS$$...`和构建器生成的`$$ExternalSynthetic...`；`-keepnames`并不保证这些条目是同名的原始存活类。独立查看56条mapping，确认48个实际存活原始类都保留名字、2条删除／6条synthetic须区分。修正验证器仅对原始存活类严格检查名字，并将synthetic／删除情况完整写入交付JSON，关键客户端额外检查真实DEX描述符后重跑exit0。未修改APK或为此重打包，未隐瞒为首次核验全绿；优化／synthetic栈仍是崩溃归因局限。

## 人工使用

1. 按既有同包同证书方式人工覆盖安装；不要先卸载、清数据或重新配对。本轮未自动安装。
2. **设置 → 车机 → 车辆状态读取 → 读取车辆状态**默认关闭；确认开启后手动重连CarPlay，收到iPhone A100才开始读。
3. 如需停止，直接关闭开关；读取异常／上次读取未正常结束会保护性关闭，重启后不会自行恢复开启。
4. 未知／无合法权限不制造0公里、无警告或白天；系统杀进程／断电也可能触发保护。不能保证SDK绝不闪退，也不承诺原生崩溃当次进程自救。
