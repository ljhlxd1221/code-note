# BottomSheet 关闭后残留透明窗口拦截全屏触摸 —— 问题分析

> 技术栈：Android + Jetpack Compose（Material3 `ModalBottomSheet`）
> 结论状态：根因已定位，含一处由实测证据触发的根因排序修正

---

## 一、问题现象

**触发路径**

```
打开 BottomSheet
  → 显示前通过 visible 做判断，动态控制显示/关闭
  → 显示期间播放倒计时动画
  → 倒计时结束 → 关闭 BottomSheet
```

**异常表现**

在某些机型上，将系统字体调大后：

1. 倒计时结束，BottomSheet 的半屏内容消失（视觉上已关闭）
2. 但底部残留一个透明窗口，**拦截整个应用的触摸事件**
3. 应用完全无法点击，只能杀进程恢复

**关键限定条件**

| 条件 | 现象 |
|---|---|
| 字体调大 + 部分机型 | 复现 |
| 字体正常 | 不复现 |
| 其他部分机型 | 不复现 |
| **去掉内容动画，改为静态内容** | **不复现（决定性证据）** |
| 代码中是否存在 progress 组件 | 无（已排除该嫌疑） |

---

## 二、技术底座（源码事实）

### 2.1 BottomSheet 不是一个 View，是一个真实的 Window

`ModalBottomSheet` 底层是 `Popup`，在 Android 上通过 `windowManager.addView()` 挂载独立窗口。

```kotlin
// AndroidPopup.android.kt
DisposableEffect(popupLayout) {
    popupLayout.show()
    popupLayout.updateParameters(...)
    onDispose {
        popupLayout.disposeComposition()   // ① 清空内容
        popupLayout.dismiss()              // ② 移除 window
    }
}

fun dismiss() {
    windowManager.removeViewImmediate(this)
}
```

**这两步的顺序精确对应了现象**：内容被清空（视觉上半屏消失），但 `removeViewImmediate` 未成功执行，window 与它的 InputChannel 仍注册在 WMS/InputManager 中 → 全屏点击被吞。

### 2.2 该窗口天生就是全屏模态（重要修正）

```kotlin
private const val PopupPropertiesBaseFlags: Int =
    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

private fun createFlags(focusable: Boolean, ...): Int {
    var flags = PopupPropertiesBaseFlags
    if (!focusable) { flags = flags or FLAG_NOT_FOCUSABLE }
    ...
    // 全程没有 FLAG_NOT_TOUCH_MODAL
}
```

`ModalBottomSheet` 使用的正是 `PopupProperties(focusable = true)`。

- 没有 `FLAG_NOT_TOUCH_MODAL`，靠 `FLAG_WATCH_OUTSIDE_TOUCH` 感知外部点击触发 dismiss，**而非让点击穿透**
- 内容为 `fillMaxSize`（要画 scrim），尺寸是全屏

> **结论**：残留后必然拦截整个应用。不是"退化成模态"，而是本就如此。这解释了为什么是"全屏拦截"而非"只挡底部"。

### 2.3 sheet 高度被持续观测，anchors 由高度算出

```kotlin
Surface(
    modifier = Modifier
        .fillMaxWidth()
        .bottomSheetSwipeable(sheetState, fullHeight, sheetHeightState)
        .onGloballyPositioned {
            sheetHeightState.value = it.size.height.toFloat()   // 每帧观测内容高度
        }
) { Column(content = sheetContent) }
```

```kotlin
private fun Modifier.bottomSheetSwipeable(
    sheetState, fullHeight: Float, sheetHeightState: State<Float?>
): Modifier {
    val sheetHeight = sheetHeightState.value
    val modifier = if (sheetHeight != null) {
        val anchors = if (sheetHeight < fullHeight / 2) {
            mapOf(fullHeight to Hidden, fullHeight - sheetHeight to Expanded)
        } else {
            mapOf(fullHeight to Hidden, fullHeight / 2 to HalfExpanded,
                  max(0f, fullHeight - sheetHeight) to Expanded)
        }
        Modifier.swipeable(state = sheetState, anchors = anchors, ...)
    }
}
```

### 2.4 anchors 更新会重新定靶

`AnchoredDraggableState.updateAnchors` 的默认 newTarget 逻辑：

```
newTarget: T = if (!offset.isNaN()) {
    newAnchors.closestAnchor(offset) ?: targetValue
} else targetValue
```

官方对 `animateTo` 的约定：

> **Throws `CancellationException`** if the interaction interrupted by another interaction like a gesture interaction or another programmatic interaction like an `animateTo` or `snapTo` call.

---

## 三、决定性证据与根因排序修正

### 3.1 证据

**把内容动画去掉、改为静态内容，问题不复现。**

### 3.2 为什么这条证据是决定性的

若根因是纯 `fontScale` 配置变更，则静态内容也应复现（配置照样变、字体照样大）。静态内容不复现，说明**触发点必须在"内容发生变化"这个动作上**，fontScale 只是让它更容易发生。

### 3.3 排序修正

| | 修正前判断 | 修正后结论 |
|---|---|---|
| 主因 | fontScale 触发 remeasure | **内容动画导致 sheet 高度抖动 → anchors 重算 → hide 动画被重新定靶** |
| fontScale | 主触发条件 | **放大器**（放大尺寸抖动幅度，提高触发概率） |
| 静态内容不复现 | 无法解释 | **完美解释** |

---

## 四、根因链路（逐帧时序）

### 4.1 正常流程

```
倒计时结束 → visible = false
  → OR 条件仍为 true（currentValue 未变 Hidden）
  → hide() → 退出动画播放 → currentValue = Hidden
  → OR 条件变 false → sheet 退出组合树
  → onDispose: disposeComposition() → dismiss()
  → removeViewImmediate 成功 → InputChannel 注销
  ✅ 正常
```

### 4.2 异常流程

```
T0   倒计时结束，visible = false
T1   条件 = false || (currentValue != Hidden)
             = false || true        ← 动画未完，currentValue 仍是 Expanded
             = true                 ← visible=false 完全无效
T2   hide() 动画启动，offset 开始下滑
T3   ⚡ 内容动画导致尺寸变化 → remeasure
T4   onGloballyPositioned 上报新高度 → anchors 全部重算
T5   updateAnchors 以当前 offset 求 closestAnchor
T6   offset 尚在中间 → closestAnchor 得出 Expanded / PartiallyExpanded
T7   动画终态被从 Hidden 改写 → animateTo 抛 CancellationException
T8   hide() 提前返回，currentValue 永远停在 Expanded
T9   OR 条件恒为 true → 死锁，sheet 永不退出组合树
T10  若发生配置变更，框架还会把 sheet 状态恢复为 expanded，与 visible=false 直接打架
T11  disposeComposition 已清空内容 → 半屏视觉消失
T12  removeViewImmediate 走非正常路径 → InputChannel 未注销
T13  幽灵全屏模态窗口仍在 WMS/InputManager → 全屏触摸被吞
```

**注意 T11 与 T12 的分离**——这正是"半屏关闭了但窗口还在"的精确解释：内容清空与窗口移除是两步，前一步成功、后一步失败。

### 4.3 结构性必然：关闭时刻最危险

```
倒计时归零 → 内容最后一次更新（触发 measure + onGloballyPositioned）
          → 同帧/相邻帧触发 close()
          → hide() 刚启动就撞上 anchors 重算
```

关闭指令与尺寸抖动天然撞在同一帧。这是**结构性**冲突，不是运气问题。

---

## 五、根因分层

| 层 | 根因 | 源码依据 |
|---|---|---|
| **L1 主因** | 内容动画使 sheet 高度抖动 → anchors 重算 → 收起动画终态被改写 | 2.3 / 2.4 |
| **L2 决定性** | OR 逻辑让摘除 100% 依赖动画完成，`visible=false` 无摘除能力 | 四.2 T1 |
| **L3 触发器** | 倒计时归零与最后一次 measure 同帧 | 四.3 |
| **L4 放大器** | fontScale 放大文本/尺寸抖动幅度 | 六 |
| **L5 加剧** | 配置变更时框架恢复 sheet expanded 状态，与 `visible=false` 打架 | Material3 restoration flag |
| **L6 表现** | window 移除失败，InputChannel 未注销；该窗口本就是全屏模态 | 2.1 / 2.2 |

---

## 六、fontScale 的确切角色

字体调大 = `fontScale` 配置变更。它**不改变机制，只改变触发概率**：

- 正常字体：`10s → 9s` 宽度差几个 px，可能不足以改变整体高度
- 大字体：同样字符变化，宽度差几十 px，**更容易触发换行/高度变化** → 必现

**分岔点在 Manifest**：

- **未声明 `configChanges`** → Activity 重建 → Popup 随之整体销毁重建 → 反而不会残留
- **声明 `configChanges` 含 `fontScale`** → 不重建，仅重组 → 重组与 dismiss 争抢同一 window → 加剧

部分 ROM 的字体滑块会**同时改 fontScale 与 densityDpi**，双变更叠加更易触发。

---

## 七、机型差异

**明确**：无法从代码推导"某型号必现"，但可给出充分条件组合。

四条同时满足，必中：

| # | 条件 | 来源 |
|---|---|---|
| 1 | 内容动画在收起期间改变 sheet 高度 | L1 |
| 2 | OR 逻辑，摘除完全依赖动画完成 | L2 |
| 3 | 关闭指令与最后一次 measure 同帧 | L3 |
| 4 | window 移除走非正常路径 | L6 |

**差异的真实来源**：不在后果（所有机型残留后都必然全屏拦截），而在**前置条件是否发生**——取决于 WMS 窗口移除时序与窗口动画时长。

- **高风险**：华为 EMUI/鸿蒙、小米 MIUI/HyperOS、OPPO ColorOS、vivo OriginOS。改过 WMS 窗口移除时序，且普遍带强制窗口动画，会把 `removeViewImmediate` 排队延后。Android 10–12 区间最高。
- **低风险**：Pixel、Android One、摩托罗拉等接近 AOSP 的 ROM，容错能兜住。

---

## 八、验证方案

**① 抓实锤日志**

```bash
adb logcat -d | grep -i "input channel"
```

出现 `Pop-Up Window (client)' was disposed without first being removed with the input manager` → **根因实锤**。

**② 看残留窗口**

```bash
adb shell dumpsys window windows | grep -A5 '你的包名'
```

正常关闭后应只剩主 Activity 一个 window；多出的全屏 `TYPE_APPLICATION_PANEL` 即元凶。

**③ 验证窗口移除时序（解释机型差异）**

```bash
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
```

关掉窗口动画后复现，不出现 → 确认为窗口移除时序问题。

**④ 验证 anchors 重算（验证 L1）**

```kotlin
ComposeMaterial3Flags.isAnchoredDraggableComponentsInvalidationFixEnabled = false
```

官方说明：设为 true 时组件会在 state 变化/remeasure 时重算 anchor points；false 则不做额外 remeasure。**切换后复现一次即可确认。**

---

## 九、修复方案

### ① 锁死 sheet 高度，切断 size → anchors 链路（最直接）

```kotlin
Column(
    modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 200.dp, max = 200.dp)   // 高度锁死
) { ... }
```

内容再怎么动，`sheetHeight` 都不变，anchors 永不重算。

### ② 让倒计时动画不触发 measure

原则：**动画只改绘制，不改布局**。

- 数字文本（`3s → 2s`）：给 Text 固定宽度，避免字符数变化影响布局
- 进度动画：用 `graphicsLayer` 把动画推到 draw 阶段
- 避免 `AnimatedVisibility` / `animateContentSize` 这类会改变布局尺寸的 API

### ③ 拆除 OR 逻辑，加超时兜底（必做）

```kotlin
fun close() {
    scope.launch {
        try {
            withTimeout(500) { sheetState.hide() }   // 可能抛 CancellationException
        } finally {
            openSheet = false                         // 无论成败都摘，打破死锁
        }
    }
}

// 显示条件只留一个开关，删掉 || currentValue != Hidden
if (openSheet) {
    ModalBottomSheet(
        onDismissRequest = { openSheet = false },
        sheetState = sheetState,
    ) { ... }
}
```

**两个要点**

1. `finally` 是打破死锁的关键，直接对冲 `CancellationException`，能兜住所有 ROM 差异
2. `openSheet` 成为唯一真相源；`sheetState` 只管动画进度，两者不再混在一个布尔里

> 官方 sample 已给出标准写法，注释亦点明此坑：
> `// Note: If you provide logic outside of onDismissRequest to remove the sheet, you must additionally handle intended state cleanup, if any.`

### ④ 规避配置变更冲突

Manifest 去掉 `android:configChanges` 中的 `fontScale`，让字体变化走标准重建。

### ⑤ 避坑提醒

- 不要用 `confirmValueChange` 返回 false 去阻止关闭——会让 `currentValue` 与实际状态脱节，加重问题
- 若需判断"是否正在关闭"，读 `targetValue` 而非 `currentValue`（后者在动画期间是旧值，正是它让 OR 判断失真）

---

## 十、一句话总结

**内容动画让 sheet 高度在收起动画期间抖动 → anchors 被重算 → 收起动画的终态被从 Hidden 改写成 Expanded → hide() 抛 CancellationException 提前返回 → OR 条件因 currentValue 永不达 Hidden 而恒为真 → sheet 死锁在组合树 → Popup window 的 InputChannel 未注销 → 全屏触摸被吞。**

fontScale 不是原因，是把尺寸抖动放大到足以触发的放大器。

---

## 十一、待确认项

1. **倒计时动画的具体形态**：数字文本（`3s → 2s`）、环形/线性进度、还是 `InfiniteTransition`？
   - 数字文本 → 固定宽度 + draw 阶段动画
   - 进度条 → 固定容器尺寸
2. **Manifest 中 Activity 的 `android:configChanges` 具体值**（是否含 `fontScale` / `density`）
   - 有 `fontScale` → 第六节链路完全成立
   - 无 → L1 主因路径仍成立（该路径不依赖 Activity 是否重建）
3. **出问题的具体机型与 Android 版本**
