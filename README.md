# Banana_CarPlay

私有维护仓库：当前Apple CarPlay独立工程源码快照。仓库名称不改变应用产品名、包名或签名。

## 当前交付

- Apple CarPlay／`io.dushu.car`／1.4.2(code375)，原证书签名、未加固。
- APK：`AppleCarPlay-1.4.2-375-202611210125.apk`（见本仓库Releases）。
- SHA256：`42775B7FBF1664BDED474D4E532141F038957AEE9D67886E4E1FE2D43045C314`。
- 最新本地8模块JUnit：2468项，0失败／错误／跳过；详见`verification/local-test-summary.json`。
- 燃油车Vehicle Status A100/A101/A102、独立通道lease、严格原车权限／有效性检查。
- “读取车辆状态”默认关闭；设置→车机→车辆状态读取，手动确认后重连才启用。
- 活跃读取未正常退出时下次启动保护性关闭，系统杀进程／断电也可能触发。不保证SDK不崩溃，不是实车验收。

## 构建与签名

现有工程使用Android Studio JBR、SDK37及NDK（见Gradle配置）。在`local.properties`自行配置SDK路径。

```powershell
.\gradlew.bat :shared:testDebugUnitTest :car:api:testDebugUnitTest :car:core-bridge:testDebugUnitTest :car:platform-android:testDebugUnitTest :car:platform-ecarx:testDebugUnitTest :car:platform-geely:testDebugUnitTest :car:ui:testDebugUnitTest :car:app:testDebugUnitTest -PallowRelease=false --no-configuration-cache
.\gradlew.bat :car:app:assembleRelease -PallowRelease=true -Pdbplay.signingDir=<本机受保护的签名目录> --no-configuration-cache
```

APK签名私钥／口令不入库；缺原证书无法制作同证书覆盖包。版本在本独立分支固定为1.4.2/code375，不自动递增。

原始APK中内置MFi身份已由仓库所有者明确确认为公开测试身份，并授权把原APK上传私有Release。源码快照不提交单独身份文件；按`scripts/install-mfi-identity.ps1`或运行时导入你合法持有的身份。不得将未知来源私钥／签名材料提交入库。

OEM框架JAR仅为此私有项目的compileOnly编译依赖，来自用户提供的目标ROM；不随产品APK分发，不代表其有公开再分发许可。GPL声明不覆盖厂商闭源资料。

## 上传快照边界

- 完整保留car/shared/common/mobile/automotive源码、资源、测试、Gradle wrapper及所列编译依赖。
- 不上传构建缓存、私钥／密码、私人设备日志、agent记忆、跳板服务器脚本或自动CI发布配置。
- 源码注释／测试及文档中的现场VIN已在本上传副本替换为稳定合成fixture；本地原工程／APK不修改。逐文件差异见`SOURCE-SNAPSHOT.json`。
- 历史构建记录属于原开发机；不把源码导出后的验证或GitHub上传当成新一轮实车验收。

## 来源与许可

基于DBPlay独立分支及GPL-3.0上游[shilapi/xcertplay](https://github.com/shilapi/xcertplay)，保留原作者徐大兵及原文件版权／来源说明。
许可证见`LICENSE`，架构／继承见`ARCHITECTURE.md`、`UPSTREAM.md`；上游README保留为`README.upstream*.md`。
