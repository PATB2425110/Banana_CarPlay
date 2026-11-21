package com.dbtool.carplay.ui

import android.view.MotionEvent
import com.dbtool.carplay.api.session.CarTouchContact

/**
 * 把 Android `MotionEvent` 映射成 CarPlay 触点。
 *
 * 为什么在 UI 模块自己实现（而不复用 upstream 的 `CarPlayTouchMapper`）：
 * upstream 的映射器在 `:shared`，`:car:ui` 只允许依赖 `:car:api`。
 * 复用它会立刻破坏"UI 与 Core 解耦"这条硬约束，
 * 代价只是本文件这二十来行纯坐标换算——值得。
 *
 * 口径与 upstream 保持一致，避免触摸手感出现差异：
 * - 最多同时上报 [MAX_CONTACTS] 个触点（CarPlay HID 触摸描述符就是 2 点）
 * - 坐标归一化到 [0,1]，原点在左上
 */
object CarTouchMapper {

    const val MAX_CONTACTS = 2

    fun contacts(event: MotionEvent, viewWidth: Int, viewHeight: Int): List<CarTouchContact> {
        if (viewWidth <= 0 || viewHeight <= 0) return emptyList()
        val count = minOf(event.pointerCount, MAX_CONTACTS)
        val liftedIndex = when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_UP -> event.actionIndex
            MotionEvent.ACTION_UP -> 0
            else -> -1
        }
        val result = ArrayList<CarTouchContact>(count)
        for (index in 0 until count) {
            val rawX = event.getX(index)
            val rawY = event.getY(index)
            val x = (rawX / viewWidth).coerceIn(0f, 1f)
            val y = (rawY / viewHeight).coerceIn(0f, 1f)
            result += CarTouchContact(
                id = event.getPointerId(index),
                x = x.toDouble(),
                y = y.toDouble(),
                // 抬指的那一个触点带 down=false，其余保持按下——
                // CarPlay 靠这个区分"其中一指抬起"与"全部抬起"。
                down = index != liftedIndex,
            )
        }
        return result
    }
}
