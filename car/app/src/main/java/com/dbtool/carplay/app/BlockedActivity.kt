package com.dbtool.carplay.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dbtool.carplay.bridge.developer.CarMfiBlockPrefs
import com.dbtool.carplay.ui.theme.CarIcons
import kotlinx.coroutines.delay

/**
 * 封禁设备的唯一可见页面。
 *
 * 这个 Activity 不提供返回 / 关闭入口：它由 [BlockGate] 以
 * `NEW_TASK | CLEAR_TASK` 拉起，主界面任务已经被清掉。管理员在后台解封后，
 * 心跳会用明确的 `blocked=false` 清掉本地标记，本页每 10 秒重读一次，
 * 发现标记消失才回到 [MainActivity]。
 */
class BlockedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = CarMfiBlockPrefs(applicationContext)
        val deviceId = runCatching {
            (application as CarPlayApplication).services.developer.state().deviceId
        }.getOrDefault("")
        val reason = runCatching { store.reason }.getOrDefault("")
        // Keep the unrelated block page's cached VIN display without theme dependency.
        val vin = runCatching {
            getSharedPreferences("dbplay_vin_cache", MODE_PRIVATE).getString("vin", null)
                ?.trim()?.uppercase(java.util.Locale.ROOT)
                ?.takeIf { it.matches(Regex("^[A-HJ-NPR-Z0-9]{17}$")) }
        }.getOrNull()

        setContent {
            BlockedScreen(
                reason = reason,
                deviceLine = BlockedPageCopy.deviceLine(deviceId, vin),
            )
            LaunchedEffect(Unit) {
                while (true) {
                    delay(UNBLOCK_POLL_MILLIS)
                    val stillBlocked = runCatching { store.isBlocked }.getOrDefault(true)
                    if (!stillBlocked) {
                        startActivity(
                            Intent(this@BlockedActivity, MainActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TASK,
                                )
                            },
                        )
                        finish()
                        return@LaunchedEffect
                    }
                }
            }
        }
    }

    /** 系统返回键不退出、不回到主界面。 */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() = Unit

    private companion object {
        const val UNBLOCK_POLL_MILLIS = 10_000L
    }
}

@Composable
private fun BlockedScreen(reason: String, deviceLine: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .padding(horizontal = 32.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.76f)
                .widthIn(max = 760.dp)
                .background(Color(0xFF131A23), RoundedCornerShape(24.dp))
                .border(1.dp, Color(0xFF2A3441), RoundedCornerShape(24.dp))
                .padding(horizontal = 40.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = CarIcons.Warning,
                contentDescription = null,
                tint = Color(0xFFFFB020),
                modifier = Modifier.height(52.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = BlockedPageCopy.TITLE,
                color = Color(0xFFF5F7FA),
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = BlockedPageCopy.BODY,
                color = Color(0xFFC8D1DC),
                fontSize = 18.sp,
                lineHeight = 27.sp,
                textAlign = TextAlign.Center,
            )
            BlockedPageCopy.reasonLine(reason)?.let { line ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = line,
                    color = Color(0xFFFFC45C),
                    fontSize = 18.sp,
                    lineHeight = 27.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = deviceLine,
                color = Color(0xFF91A2B8),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                text = BlockedPageCopy.FOOTER,
                color = Color(0xFFA8B5C5),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
