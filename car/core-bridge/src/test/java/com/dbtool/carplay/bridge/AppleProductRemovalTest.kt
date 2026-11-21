package com.dbtool.carplay.bridge

import java.io.File
import com.dbtool.carplay.bridge.hotspot.AdbShellSession
import org.junit.Assert.*
import org.junit.Test

class AppleProductRemovalTest {
    @Test fun developerVersionUnlockAndUploadEntryAreRestoredWithoutEnablingAdb() {
        val about = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/AboutScreen.kt")
        assertTrue(about.contains("onClick = onVersionTap"))
        assertTrue(about.contains("if (model.developer.unlocked)"))
        assertTrue(about.contains("onClick = onOpenDeveloper"))
        assertTrue(about.contains("value = \"日志采集与上传\""))
        val page = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/DeveloperScreen.kt")
        assertTrue(page.contains("actions.collectAndUploadLogs(scope, includeClientIpv4)"))
        val policy = source("car/api/src/main/java/com/dbtool/carplay/api/settings/AppleCarPlayProduct.kt")
        assertTrue(policy.contains("enableBuiltInAdb = false"))
        assertTrue(policy.contains("debugTunnelEnabled = false"))
    }
    @Test fun manualQualityLimitsRemovedAndCurrentFormatsAreReadOnly() {
        val ui = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        val store = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/SharedPreferencesCarSettingsStore.kt")
        for ((route, label) in listOf("music" to "音乐", "assistant" to "Siri", "navigation" to "导航", "phone" to "通话", "microphone" to "麦克风")) {
            assertFalse(ui.contains("title = \"${label}采样率上限\""))
            assertFalse(store.contains("prefs.getInt(\"audio_quality_${route}_rate\""))
            assertFalse(store.contains(".putInt(\"audio_quality_${route}_rate\""))
        }
        assertFalse(ui.contains("title = \"导航声道上限\""))
        assertTrue(ui.contains("title = \"当前音频流规格\""))
        assertTrue(ui.contains("val streams = model.activeAudioStreams"))
        assertTrue(ui.contains("未协商／当前无活动流"))
        val status = ui.substringAfter("private fun CurrentAudioStreamsGroup(").substringBefore("private fun AudioChannelRow(")
        assertFalse(status.contains("CarSettingChoice("))
        assertFalse(status.contains("CarSettingSwitch("))
        val bridge = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt")
        assertTrue(bridge.contains("activeHandle?.audioObserver?.activeAudioStreams().orEmpty()"))
        assertTrue(bridge.contains("SessionHandle(controller, mediaSink, locationProvider, localMfi, focusGuard, decoratedSink)"))
        val app = source("car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt")
        assertTrue(app.contains("activeAudioStreams = session.activeAudioStreams()"))
        assertTrue(app.contains("if (event == CarSessionEvent.AudioStreamsChanged) { refreshModel(); return }"))
        val sink = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/UpstreamMediaSinkBridge.kt")
        assertTrue(sink.contains("private val activeAudio = ActiveAudioStreams(audioStreamsChanged)"))
        assertTrue(sink.contains("type, format.audioType, format.codec.name, format.sampleRate, format.channels"))
        assertTrue(sink.contains("activeAudio.stop(type)"))
        assertTrue(sink.contains("activeAudio.stop(type, microphone = true)"))
    }
    @Test fun experimentalMediaSwitchPersistsAndUsesExistingTopRightReconnectAction() {
        val ui = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        assertTrue(ui.contains("title = \"使用无损传输\""))
        assertTrue(ui.contains("update(draft.copy(higherQualityMedia = it))"))
        assertTrue(ui.contains("if (model.settingsPendingReconnect)"))
        assertTrue(ui.contains("actions.applySettings(draft, reconnect = true)"))
        val store = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/SharedPreferencesCarSettingsStore.kt")
        assertTrue(store.contains("prefs.getBoolean(\"higher_quality_media\", d.higherQualityMedia)"))
        assertTrue(store.contains(".putBoolean(\"higher_quality_media\", settings.higherQualityMedia)"))
    }
    @Test fun latestLabelsAndHiddenControlsMatchProduct() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        assertTrue(s.contains("title = \"使用车机麦克风\""))
        assertTrue(s.contains("description = \"导航播报时通过按键或旋钮调节导航音量\""))
        assertTrue(s.contains("title = \"使用方向盘按键控制媒体\""))
        for (title in listOf("车机媒体卡片", "上报正在播放", "熄火自动暂停")) {
            assertFalse(title, s.contains("title = \"$title\""))
        }
        assertFalse(s.contains("actions.resetSettings()"))
        assertFalse(s.contains("text = \"恢复默认设置\""))
        val rail = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsTwoPane.kt")
        assertFalse(rail.contains("title = \"恢复默认设置\""))
        val lyrics = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/IndependentInstrumentCardsGroup.kt")
        assertFalse(lyrics.contains("歌词使用手机音乐软件"))
        assertFalse(lyrics.contains("不依赖仪表主题"))
        val summary = s.substringAfter("private fun summaryDisplay(").substringBefore("private fun summaryVehicle(")
        assertFalse(summary.contains("summaryWindow(s)"))
    }
    @Test fun powerPauseReadCannotBeDisabledByHiddenLegacyPreference() {
        val s = source("car/api/src/main/java/com/dbtool/carplay/api/power/CarPowerPauseSetting.kt")
        assertTrue(s.contains("fun isEnabled(): Boolean =\n        CAR_POWER_PAUSE_DEFAULT_ENABLED"))
        assertTrue(s.contains("CAR_POWER_PAUSE_DEFAULT_ENABLED: Boolean = true"))
    }
    @Test fun rightPaneChoiceSelectionUsesSwitchBlueAndContrastingTextWithoutChangingRail() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/components/SettingItem.kt")
        val chip = s.substringAfter("fun CarChoiceChip(").substringBefore("fun CarSettingSlider(")
        assertTrue(chip.contains("color = if (selected) colors.primary else colors.surfaceVariant"))
        assertTrue(chip.contains("selected -> colors.onPrimary"))
        assertTrue(s.contains("checkedTrackColor = CarTheme.colors.primary"))
        assertTrue(source("car/ui/src/main/java/com/dbtool/carplay/ui/components/CarRail.kt")
            .contains("selected -> colors.selectedBackground"))
    }
    @Test fun settingsRailScalesActualLayoutAndTextRatherThanOnlyDrawing() {
        val rail = source("car/ui/src/main/java/com/dbtool/carplay/ui/components/CarRail.kt")
        assertTrue(rail.contains(".height(dimens.settingsRailItemHeight * scale)"))
        assertTrue(rail.contains("fontSize = baseStyle.fontSize * scale, lineHeight = baseStyle.lineHeight * scale"))
        assertTrue(rail.contains("Modifier.size(dimens.settingsRailIconSize * scale)"))
        val pane = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsTwoPane.kt")
        assertEquals(2, Regex("scale = SettingsLayoutPolicy.NAV_RAIL_SCALE").findAll(pane).count())
        assertTrue(pane.contains(".width(dimens.settingsRailWidth * SettingsLayoutPolicy.NAV_RAIL_SCALE)"))
    }
    @Test fun fixedDeviceIdentityIsHiddenWithoutRemovingAppearanceSettings() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
            .substringAfter("private fun IdentitySection(").substringBefore("private fun AirPlayIconPreviewRow(")
        for (title in listOf("设备标识", "设备名称", "制造商", "型号", "OEM 标签", "恢复出厂标识")) {
            assertFalse(title, s.contains("title = \"$title\""))
        }
        assertTrue(s.contains("title = \"AirPlay 图标\""))
        val policy = source("car/api/src/main/java/com/dbtool/carplay/api/settings/AppleCarPlayProduct.kt")
        for (field in listOf("deviceName", "manufacturer", "model", "oemLabel")) {
            assertTrue(policy.contains("$field = \"Lynk&Co\""))
        }
    }
    @Test fun finalInstrumentCopyAndRemovedCruiseControl() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        assertTrue(s.contains("title = \"仪表显示苹果原生导航信息卡片\""))
        assertFalse(s.contains("title = \"允许无导航时手动开启\""))
        assertFalse(s.contains("draft.clusterManualCruise"))
        assertFalse(s.contains("subtitle = \"只影响导航画面 · 让 iPhone 决定导航画成什么样。\""))
        assertFalse(s.contains("subtitle = \"0 = 自动；1~20"))
        assertTrue(s.contains("description = \"通过饺子踏板把转向、距离、道路和可用车道交给原车导航组件。\""))
        assertTrue(s.contains("description = \"打开立即投屏，不再跟随导航开始或结束；CarPlay画面中三指左滑投屏、右滑收起。\""))
    }
    @Test fun immersiveNumericEditorUpdatesOnlyImmersivePreference() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
            .substringAfter("private fun ImmersiveNavigationSettings(").substringBefore("private fun ClusterFrameGroup(")
        assertTrue(s.contains("immersiveSafeAreaFromText(xText, yText, wText, hText)"))
        assertTrue(s.contains("enabled = numeric != null"))
        assertTrue(s.contains("update(draft.copy(immersiveNavigationSafeArea = it))"))
        assertFalse(s.contains("draft.copy(safeArea ="))
    }
    @Test fun settingsBottomUses110PhysicalPixelsAndThemeBackground() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        assertTrue(s.contains("{ 110.toDp() }"))
        assertTrue(s.contains(".background(CarTheme.colors.background).padding(bottom = settingsBottomInset)"))
    }
    @Test fun removedAudioAndStartupControlsDoNotRemainInSettings() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        for (title in listOf("高级通道映射", "媒体声（音乐 / 播客）", "导航提示音", "试听当前媒体通道", "试听当前导航通道", "启动性能优化")) {
            assertFalse(title, s.contains("title = \"$title\""))
        }
        val vehicle = s.substringAfter("private fun VehicleSection(").substringBefore("private fun StartupSection(")
        assertTrue(vehicle.contains("title = \"开机自动启动\""))
        val nav = source("car/ui/src/main/java/com/dbtool/carplay/ui/navigation/CarDestination.kt")
        assertFalse(nav.contains("STARTUP("))
    }
    @Test fun removedIndependentNavigationCardCannotBeRestoredBySavedOverlayPreference() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/IndependentInstrumentCardsGroup.kt")
        assertFalse(s.contains("导航信息卡")); assertFalse(s.contains("导航卡横向位置")); assertFalse(s.contains("导航卡纵向位置"))
        assertTrue(s.contains("歌曲信息与歌词卡"))
        val prefs = source("car/api/src/main/java/com/dbtool/carplay/api/projection/InstrumentCards.kt")
        assertTrue(prefs.contains("InstrumentCardSettings(false, it.getBoolean(\"music\", false)"))
    }
    @Test fun startupSponsorPromptIsRemovedForNewAndExistingInstallations() {
        val s = source("car/app/src/main/java/com/dbtool/carplay/app/MainActivity.kt")
        assertFalse(s.contains("sponsorPromptVisible"))
        assertFalse(s.contains("has_seen_sponsor_prompt_v1"))
        assertFalse(s.contains("title = \"赞助 DBPlay\""))
    }
    @Test fun immersiveSettingsHaveTheirOwnInstrumentCategoryAndAreNotNested() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        assertTrue(s.contains("CarSettingsSection.IMMERSIVE_NAVIGATION -> ImmersiveNavigationSettings(draft, update)"))
        val content = s.substringAfter("private fun ClusterContentGroup(").substringBefore("private fun ImmersiveNavigationSettings(")
        assertFalse(content.contains("ImmersiveNavigationSettings(draft, update)"))
        val nav = source("car/ui/src/main/java/com/dbtool/carplay/ui/navigation/CarDestination.kt")
        assertTrue(nav.contains("CLUSTER(\"投屏仪表\")"))
        assertTrue(nav.contains("IMMERSIVE_NAVIGATION(\"沉浸式导航（与仪表投屏互斥）\")"))
    }
    @Test fun systemBarSwitchesAndAboutCreditsAreAbsentAndHotspotPasswordIsPlain() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        assertFalse(s.contains("title = \"隐藏底部栏\""))
        assertFalse(s.contains("title = \"全屏\""))
        val connection = s.substringAfter("private fun ConnectionSection(").substringBefore("private fun LegacyConnectionSection(")
        assertTrue(connection.contains("value = draft.manualHotspotPassphrase, isPassword = false"))
        val about = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/AboutScreen.kt")
        assertFalse(about.contains("抖音搜")); assertFalse(about.contains("Lynk&Co · CarPlay"))
    }
    private fun source(path: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(root, path).isFile) root = root.parentFile ?: error(path)
        return File(root, path).readText()
    }
    @Test fun adbBackendCannotStartInitializeRepairOrAcquire() {
        val s = AdbShellSession(null)
        assertFalse(s.ensure().shellOk); assertFalse(s.checkDedicatedPort().shellOk)
        assertFalse(s.ensureRootForHotspot().rooted)
        assertFalse(s.initializeDedicatedPort().persistentPortVerified)
        assertFalse(s.repairDedicatedPort().attempted)
        s.open("old-owner"); assertEquals(0, s.ownerCount); assertNull(s.peek())
        try { s.runner; fail("must not expose operational runner") } catch (_: UnsupportedOperationException) {}
    }
    @Test fun productDoesNotInstantiateRemoteUpdateManagerOrUseAdbForHotspotRead() {
        val s = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarAppServices.kt")
        assertFalse(s.contains("= CarUpdateManager("))
        assertTrue(s.contains("= com.dbtool.carplay.bridge.update.DisabledProductUpdates"))
        val briefing = s.substringAfter("fun hotspotBriefing(").substringBefore("private fun manualSsidWarningOf(")
        assertFalse(briefing.contains("nativeHotspotProbe.cachedInfo("))
        assertTrue(briefing.contains("LocalSoftApReader.read(appContext)"))
    }
    @Test fun connectionPageHasOnlyCarHotspotAndSupportedSecurity() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
            .substringAfter("private fun ConnectionSection(").substringBefore("private fun LegacyConnectionSection(")
        assertTrue(s.contains("listOf(CarHotspotSecurity.OPEN, CarHotspotSecurity.WPA2)"))
        assertFalse(s.contains("title = \"蓝牙握手处理\""))
        assertFalse(s.contains("CarHotspotMode.entries"))
        assertFalse(s.contains("onInitializeAdb"))
    }
    @Test fun updateUiAndSponsorFullMapNavigationAreAbsent() {
        val about = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/AboutScreen.kt")
        assertFalse(about.contains("title = \"检查更新\"")); assertFalse(about.contains("title = \"后台自动更新\""))
        val nav = source("car/ui/src/main/java/com/dbtool/carplay/ui/navigation/CarDestination.kt")
        assertFalse(nav.contains("SPONSOR(")); assertFalse(nav.contains("CLUSTER_FULL_MAP("))
        val app = source("car/ui/src/main/java/com/dbtool/carplay/ui/navigation/CarPlayApp.kt")
        assertFalse(app.contains("if (model.update.dialogVisible)"))
    }
    @Test fun productBuildKeepsSameVersionWithoutEditingBaselineProperties() {
        val s = source("car/app/build.gradle.kts")
        assertTrue(s.contains("val dbplayVersionName = lastVersionName"))
        assertTrue(s.contains("val dbplayVersionCode = lastVersionCode"))
        assertTrue(s.contains("dbplay.signingDir"))
    }
}
