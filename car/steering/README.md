# :car:steering

方控 / 车机按键路由层。

## 现状

本模块**只做三件与车机无关的通用工作**，不含任何车机专有按键处理：

1. **按键映射表可配置**——把"平台键码 → `CarKey`"做成可替换的表，
   不同车机的异形按键不需要改代码，只改配置；
2. **去重/限流**——车机方控普遍存在连发与抖动，直接转发会导致 iPhone 侧重复触发；
3. **事件路由**——统一出口给 `CarInputListener`。

### ⚠️ 当前是死代码（2026-09-28 核实）

`CarInputRouter` **全仓没有任何构造点**——实现完整、有单测、但没有接线。
按键现在的实际通路是：

```
AndroidCarInputAdapter.handleKeyEvent(event)
        ↓ 直接 dispatch（跳过 Router）
CarInputListener  →  CarPlaySessionBridge.forwardToCarPlay  →  CarPlay HID
```

后果：**抖动抑制没有生效**。车机方控的电气抖动（50~100ms）会被原样转发，
iPhone 侧可能重复触发。

接线不阻塞在分层上：`:car:app` 已经 `implementation(project(":car:steering"))`，
把 Router 插到「适配器 → listener」之间即可。**这属于功能增强，不是缺陷修复**，
所以没有随 2026-09-28 的按键接线修复一起做，留待单独决策。

## Phase 2 状态：已实现，但不在本模块

`CarKey` → CarPlay HID 上报**已经做完了**，落在 `:car:core-bridge`：

- `CarKeyHidMap` —— 纯映射，`CarKey + action → Intent`（`Media` / `Siri` / `Knob`），
  不引用 upstream 类型，可单测；
- `CarPlaySessionBridge.forwardToCarPlay` —— 执行侧，会话来源是 upstream 的公开回调
  `AirPlaySessionListener.onSessionActive`（零上游改动）。

**所以本模块不负责 HID 编码**，它的职责到「产出 `CarInputEvent` 交给 listener」为止。
两处职责不要重复实现，否则会互相掩盖问题。

## 依赖方向

```
CarPlay Input (Core: AirPlay HID)
        ↑
CarInputAdapter / CarInputEvent        ← :car:api
        ↑
CarInputRouter                          ← 本模块
        ↑
车机按键来源（KeyEvent / 专有服务 / CAN 总线）  ← :car:platform-*
```

本模块只依赖 `:car:api`。车机按键的**来源**由 `:car:platform-*` 负责提供，
路由与仲裁在本模块——两者职责不重叠。
