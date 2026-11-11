# Eta UI 设计规范（改 UI 前必读）

本文写给要动 `app/src/main/kotlin/.../ui/` 的执行者，尤其是弱模型。目标不是设计新界面，而是让你写出和旁边页面一致的界面。所有数值和组件名都来自真实代码，改动前先 `grep` 确认还在。

## 0. 三条铁律

1. 抄最近的同类页面：先找到功能最接近的既有页面，照它的骨架、分组、行类型写，再改字段。不要从零构思布局。
2. 双分支同时改：任何页面级改动都要同时改 `AppearanceUiStyle.COUI` 与 `AppearanceUiStyle.MIUIX` 两个分支，只改一个等于交付半成品。
3. 只用库常量和主题色：COUI 用 `CouiPageDefaults.*` 与 `CouiPageDefaults.themeColors()`，MIUIX 用 `MiuixTheme.colorScheme` / `MiuixTheme.textStyles` 与 `CardDefaults`。禁止自造颜色值，禁止自己发明圆角/间距阶梯。

## 1. 代码地图（先读这些，再动手）

| 要找什么 | 真实文件 |
| --- | --- |
| 根主题、`LocalUiStyle`、深浅色、COUI/Miuix 双主题 | `ui/app/AgentAppTheme.kt` |
| COUI 二级页骨架（Scaffold / 顶栏 / 滚动） | `ui/components/coui/CouiScaffoldPage.kt` |
| COUI 列表行与分割线 | `ui/components/coui/CouiPreferenceRows.kt` |
| COUI 按钮、标签页、弹窗 API | `ui/components/coui/CouiControls.kt` |
| MIUIX 二级页骨架 | `ui/components/MiuixScaffoldPage.kt` |
| MIUIX 弹窗按钮行 | `ui/components/MiuixDialogActions.kt` |
| 空态 | `ui/components/ListEmptyState.kt` |
| 长列表分组首尾圆角（双风格） | `ui/components/EtaPreferenceGroupItem.kt` |
| 语义状态色 | `ui/components/StatusColors.kt` |
| 宽屏居中与安全区 | `ui/layout/WidePageContent.kt` |
| 双风格组件对应总表 | `docs/UI_COMPONENTS.md` |

参考实现（照抄优先级最高的四个）：`ui/SettingsScreen.kt`（分组设置页）、`ui/screens/schedule/ScheduledTasksSection.kt`（分区 + 开关行 + 确认弹窗）、`ui/screens/mcp/McpServersScreen.kt`（懒加载页 + 分组条目 + 弹窗）、`ui/components/AgentApprovalCard.kt`（自定义卡片）。

## 2. 双风格适配规则

`AgentAppTheme` 同时提供 `CouiTheme` 与 `MiuixTheme`，并通过 `LocalUiStyle` 暴露当前 `AppearanceUiStyle`（`COUI` / `MIUIX`）。切换样式不会重建组合树，所以状态必须活下来。

结构性约定：

```kotlin
@Composable
internal fun SomeScreen(state: SomeUiState, onBack: () -> Unit) {
    // 1. 所有 rememberSaveable / remember / 草稿 / Store 订阅放在 when 之前
    var pendingDelete by remember { mutableStateOf<Item?>(null) }
    val context = LocalContext.current

    // 2. 再按风格分叉组织界面
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> { /* CouiXxx */ }
        AppearanceUiStyle.MIUIX -> { /* MiuixXxx */ }
    }
}
```

分支内不要各自声明同一份状态，否则切换风格会丢草稿。弹窗也可以在 `when` 外统一持有 `pendingXxx`，再在弹窗内部按风格二次分叉（`ScheduledTasksSection.kt` 就是这么写的）。

组件归属（详见 `docs/UI_COMPONENTS.md` 全表），最容易搞错的四组：

| 用途 | COUI 分支 | MIUIX 分支 |
| --- | --- | --- |
| 二级页 | `CouiScaffoldPage` / `CouiLazyScaffoldPage` | `MiuixScaffoldPage` |
| 分组 | `Group(title = ...)` + `Divider()` / `CouiIconDivider()` | `SmallTitle(...)` + `Card {}` |
| 行 | `CouiPreference` / `CouiSwitchPreference` | `ArrowPreference` / `SwitchPreference` |
| 弹窗 | `CouiConfirmDialog` / `CouiNoticeDialog` / `CouiInputDialog` | `WindowDialog` + `MiuixDialogActions` |

`SectionHeader` 是 MIUIX 的 `SmallTitle` 包装，只在 MIUIX 分支用；COUI 分支用 `Group(title = ...)` 或 `CouiSectionTitle`。

## 3. 数值表

### 3.1 间距与尺寸（COUI）

COUI 的间距一律读库常量，不要手写 dp。

| 项 | 取值 | 说明 |
| --- | --- | --- |
| 页面左右边距 | `CouiPageDefaults.horizontalMargin` | Group 自带 |
| 行内边距 | `CouiPageDefaults.rowPadding` / `contentPadding` | 行左右 / 行上下 |
| 行最小高度 | `CouiPageDefaults.rowMinHeight` | 自定义行用 `heightIn(min = ...)` |
| 前导图标 | `CouiPageDefaults.iconSize` + `iconSpacing` | 图标容器 24.dp，glyph 22–24.dp |
| 分组底部间距 | `CouiPageDefaults.groupSpacing` | 分组之间 |
| 分组标题上方间距 | `CouiPageDefaults.categoryTopSpacing` | `CouiSectionTitle` |
| 分组圆角 | 24.dp（`CouiGroupItem` 首尾）+ `CouiPageDefaults.shape` | 中间项 0.dp |
| 跳转箭头 | 12.dp × 24.dp，`padding(start = 4.dp)` | `CouiPreference` 的 `showChevron` |
| 下拉/弹窗图标 | 14.dp × 24.dp | `CouiPopupPreference` |
| 行尾开关/复选 | `padding(start = 16.dp)`，复选 24.dp | |
| 行尾值文本最大宽 | `widthIn(max = 162.dp)`，右对齐，最多 2 行 | |
| 分组标题行 | `padding(horizontal = 32.dp, vertical = 8.dp)`，`heightIn(min = 16.dp)` | `CouiSectionTitle` |
| 顶栏文字按钮 | 胶囊 `CouiCapsuleShape`，`widthIn(min = 60.dp)`，`heightIn(min = 40.dp)` | 如“取消 / 完成” |

### 3.2 间距与尺寸（MIUIX）

| 项 | 取值 | 说明 |
| --- | --- | --- |
| 页面左右边距 | `padding(horizontal = 12.dp)` | `SettingsGroupCard` / `EtaPreferenceGroupItem` |
| 分组卡片间距 | `padding(bottom = 12.dp)` | 卡片之间 |
| 卡片内块内边距 | `padding(16.dp)` | 文本块、空态卡 |
| 卡片内紧凑行容器 | `padding(vertical = 6.dp)` | 多行塞一张卡时 |
| 组内按钮行 | `padding(horizontal = 8.dp)`，`Arrangement.End` | 卡片底部操作 |
| 卡片圆角 | `CardDefaults.CornerRadius` | 拼分组时用 `squircleSurface(...)` |
| 自定义卡片 | `squircleSurface(cornerRadius = 16.dp)` + `border(0.6.dp, ...)` + `padding(14.dp)` | `AgentApprovalCard` |
| 内嵌代码块 | `surfaceContainerHigh` + `RoundedCornerShape(10.dp)` + `padding(horizontal = 10.dp, vertical = 8.dp)` | |
| 页尾占位 | `MiuixPageBottomSpacer()`（24.dp + 导航栏） | 懒加载页必加 |
| 宽屏 | `WideScreenMinWidth = 600.dp`，内容最大 `MaxPageContentWidth = 800.dp` | `WidePageContent` |
| 图标 | 容器 24.dp，glyph 22–24.dp，`padding(end = 8.dp)` | |

### 3.3 字号（MIUIX 文本样式，按用途选）

| 用途 | 样式 | 颜色 |
| --- | --- | --- |
| 卡片/区块主标题 | `headline1` | `onSurface` |
| 行标题、正文 | `body2`（最常用）/ `body1` | `onSurface` |
| 行说明、次要信息 | `footnote1` | `onSurfaceVariantSummary` |
| 徽标、元信息 | `footnote2` | 强调色或 `onSurfaceVariantSummary` |
| 操作文字 | `onSurfaceVariantActions` | |
| 代码、路径 | `footnote2.copy(fontFamily = FontFamily.Monospace)` 或 12.sp / 行高 17.sp | |

COUI 文本用 `CouiText(kind = CouiTextKind.X)`，不要手写 `fontSize`。已用到的 kind：`PreferenceTitle`、`PreferenceSummary`、`PreferenceValue`、`Description`、`Body`、`GroupTitle`、`Toolbar`、`SearchCancel`。

### 3.4 颜色

| 语义 | 用法 |
| --- | --- |
| 主文字 | COUI `colors.title`；MIUIX `colorScheme.onSurface` |
| 次文字 | COUI `colors.summary`；MIUIX `colorScheme.onSurfaceVariantSummary`（全仓 128 处，最常用） |
| 页面底 | COUI `colors.background`；MIUIX `colorScheme.background` |
| 卡片/分组底 | COUI `colors.card`；MIUIX `colorScheme.surfaceContainer` |
| 强调 | COUI `colors.accent`；MIUIX `colorScheme.primary` |
| 危险 | COUI `dialogColors.destructive`；MIUIX `colorScheme.error` |
| 成功 / 警告 | `StatusSuccess`（0xFF34A853）/ `StatusWarning`（0xFFF9AB00） |
| 设置图标色 | `SettingsIconColors.Blue/Green/Orange/Yellow`（`SettingsScreen.kt` 内私有） |
| 禁用 | COUI `colors.disabled`；MIUIX `colorScheme.disabledOnSurface` |

`StatusColors.kt` 里 `StatusSuccess` / `StatusWarning` 是唯一允许的硬编码语义色，因为它们要在双主题下保持同一识别度。其他任何新颜色都必须来自主题。

## 4. 页面骨架范式

### 4.1 二级页（COUI，短页）

```kotlin
CouiScaffoldPage(title = stringResource(R.string.xxx_title), onBack = onBack) {
    Group(title = stringResource(R.string.xxx_group)) {
        CouiPreference(title = ..., summary = ..., icon = { CouiSettingsIcon(Icons.Rounded.X, SettingsIconColors.Blue) }, onClick = ...)
        CouiIconDivider()          // 有前导图标用 CouiIconDivider
        CouiSwitchPreference(title = ..., summary = ..., checked = ..., onCheckedChange = ...)
    }
    Group { /* 第二个分组 */ }
    CouiPageBottomSpacer()
}
```

### 4.2 二级页（COUI，长列表）

```kotlin
CouiLazyScaffoldPage(
    title = ...,
    onBack = onBack,
    actions = {
        CouiTopAppBarIconButton(icon = Icons.Rounded.Refresh, contentDescription = ..., onClick = ...)
    },
) {
    item(key = "settings") { Group(title = ...) { CouiSwitchPreference(...) } }
    item(key = "section") { CouiSectionTitle(text = ..., trailing = { /* 批量开关胶囊 */ }) }
    itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
        EtaPreferenceGroupItem(isFirst = index == 0, isLast = index == items.lastIndex) {
            CouiSwitchPreference(...)
        }
    }
    item(key = "bottom") { CouiPageBottomSpacer() }
}
```

长列表不要把一个 `Group` 或 `Card` 里塞几百行；用带稳定 key 的条目 + `EtaPreferenceGroupItem`，它内部会自动选 `CouiGroupItem` 或 MIUIX 圆角容器。

### 4.3 二级页（MIUIX）

```kotlin
MiuixScaffoldPage(title = ..., onBack = onBack) {
    item(key = "section_agent") {
        // SmallTitle 必须在 Card 外面
        SmallTitle(stringResource(R.string.xxx_group))
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            ArrowPreference(
                title = ...,
                summary = ...,
                startAction = { SettingsIcon(icon = Icons.Rounded.X, tint = SettingsIconColors.Blue) },
                onClick = ...,
            )
            SwitchPreference(title = ..., summary = ..., checked = ..., onCheckedChange = ...)
        }
    }
}
```

`MiuixScaffoldPage` 自带大标题折叠、顶栏毛玻璃、越界回弹、宽屏居中与页尾占位，不要在页面里再套 `Scaffold` 或自己加 `navigationBarsPadding`。

### 4.4 卡片范式（自定义内容卡）

适合需要强调、带边界的内容（风险确认、提示、统计块）。尺寸两个风格共用，颜色来源按分支替换（下面沿用 `AgentApprovalCard` 的 MIUIX 写法，`colors` 即 `MiuixTheme.colorScheme`；COUI 分支把 `accent` 换成 `CouiPageDefaults.themeColors().accent`）：

```kotlin
Column(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 8.dp)
        .squircleSurface(color = colors.surface, cornerRadius = 16.dp)
        .border(0.6.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
        .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) { /* 标题行 + 正文块 + 操作行 */ }
```

普通信息块用 `surfaceContainerHigh` + `RoundedCornerShape(10.dp)`；不要为一块文本再套一层大卡。

### 4.5 弹窗范式

确认类弹窗必须双分支，文案走 string resource，危险操作用 `destructive = true`：

```kotlin
when (LocalUiStyle.current) {
    AppearanceUiStyle.COUI -> CouiConfirmDialog(
        visible = true,
        title = stringResource(R.string.xxx_delete_title),
        message = stringResource(R.string.xxx_delete_message, item.title),
        confirmText = stringResource(R.string.action_delete),
        destructive = true,
        onConfirm = onConfirm,
        onDismissRequest = onDismiss,
    )
    AppearanceUiStyle.MIUIX -> WindowDialog(
        show = true,
        title = stringResource(R.string.xxx_delete_title),
        summary = stringResource(R.string.xxx_delete_message, item.title),
        onDismissRequest = onDismiss,
    ) {
        MiuixDialogActions(
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onCancel = onDismiss,
            onConfirm = onConfirm,
        )
    }
}
```

单行输入用 `CouiInputDialog` / `WindowDialog + TextField`；底部选择器用 `com.itos.coui.dialog.Dialog(CouiDialogPlacement.Bottom)` / `OverlayBottomSheet`。不要新增第三套弹窗实现。

### 4.6 按钮与开关层级

- 行内开关：整行可点，用 `CouiSwitchPreference` / `SwitchPreference`；COUI 有 `Role.Switch` + `CouiHapticFeedback.Switch`，不要自己拼 `Row + Switch`。
- 页面主操作：COUI `CouiButton(role = CouiButtonRole.Primary)`；次级 `Secondary`；不可逆操作 `Destructive`（描边）或 `DestructiveFilled`（填充）。同一屏最多一个 Primary。
- 卡片底部操作：文案型 `TextButton`，靠右 `Arrangement.End`，破坏性操作传 `ButtonDefaults.textButtonColorsPrimary(color = colorScheme.error, textColor = colorScheme.onError)`。
- 顶栏操作：`CouiTopAppBarIconButton` / `CouiTopAppBarTextButton`；MIUIX `IconButton` / `TextButton`。骨架已把 `actions` 自动包进 `TopAppBarButtonGroup`，页面只需传单个按钮。
- 不要用悬浮按钮替代行内操作。

### 4.7 空态与加载态

```kotlin
if (state.isLoading && items.isEmpty()) {
    // 加载态：说"正在读取…"，不要说成空
} else if (items.isEmpty()) {
    ListEmptyState(title = ..., summary = ..., icon = Icons.Rounded.Inbox)  // 双风格已内置
}
```

`ListEmptyState` 已处理双风格（COUI：`padding(horizontal = 24.dp, vertical = 40.dp)`、48.dp 图标块；MIUIX 对应卡片）。判定顺序固定：先 loading，再 empty，最后内容；不要把加载中说成"还没有内容"。

## 5. 深色模式

- 不传 `dark` 参数：`CouiPageDefaults.themeColors()` 默认读 `CouiTheme.isDark`，`MiuixTheme.colorScheme` 自动切换。只有需要显式覆盖时才传 `dark`。
- 禁止写死 `Color.White` / `Color.Black` / `0xFF...` 作为背景或文字色；半透明叠加一律基于主题色 `accent.copy(alpha = 0.14f)` 这类写法。
- 描边用低透明度主题色（如 `0.6.dp, accent.copy(alpha = 0.55f)`），不要用固定灰。
- 纯黑模式由 `AgentAppTheme` 统一处理（`pureBlackEnabled`），页面不要自己判断。
- 图片、品牌图标这类不可避免的固定色，放在 `ProviderBranding` / `SettingsIconColors` 之类的集中位置，不要散落在页面里。

## 6. 文案规范

- 文案只写在 `app/src/main/res/values/strings.xml`（英文默认）、`values-b+zh+Hans/strings.xml`、`values-b+zh+Hant/strings.xml` 三处，同步补齐，不得只加一份。
- 命名用语义 key：`schedule_delete_title`、`mcp_refresh_tools_hint`。不要新增 `ui_xxx_7debf9` 这种旧式哈希名。
- 不要硬编码中文字面量进 Compose；页面里只出现 `stringResource(...)`。
- 长度：按钮 2–6 字（删除 / 立即执行 / 取消）；行标题 ≤ 12 字；行说明一句，≤ 40 字；超长用 `maxLines = 1` + `TextOverflow.Ellipsis`。
- 标点：中文用全角并加「」引用名称（`将删除「%1$s」并取消它的闹钟。`）；短提示不加句号，完整句子加「。」。
- 占位符用位置参数 `%1$s`、`%1$d`，并在三种语言里保持同样的数量与顺序。
- 加载/空/失败的措辞要区分：`正在读取自动任务…` / `还没有自动任务。…` / 具体失败原因。
- 不要用感叹号卖萌，不要在 UI 里出现"AI 助手"自称；语气直接、克制。

## 7. 禁忌清单

1. 自造颜色值：`Color(0xFF...)` 出现在页面里（`StatusColors` 与你新增的集中常量文件除外）。
2. 自造间距阶梯：手写 `padding(horizontal = 13.dp)`、`padding(top = 5.dp)` 这类体系外数值。
3. 自造圆角：新写 `RoundedCornerShape(7.dp)`，而不复用 24.dp 分组 / `CardDefaults.CornerRadius` / `squircleSurface(16.dp)`。
4. 在 COUI 分支里用 MIUIX 组件（`Card`、`SwitchPreference`、`SmallTitle`、`WindowDialog`），或在 MIUIX 分支里用 `CouiXxx`。
5. 只改一个风格分支就提交。
6. 页面里再套 `Scaffold`、自己加 `navigationBarsPadding`、自己实现大标题折叠——骨架已经提供。
7. 把状态声明分散在 `when (LocalUiStyle.current)` 两侧，导致切风格丢草稿。
8. 重复实现已有能力：二次列表、弹窗、空态、分组条目都已有统一实现（`ScheduledTasksSection` 的注释明确要求不允许第二份列表实现）。
9. 硬编码中文字面量或直接改 `strings.xml` 只改一处语言。
10. 手写 `fontSize = 15.sp` 这类字号，绕过 `MiuixTheme.textStyles` / `CouiTextKind`。
11. 为"更好看"重排既有页面结构、换图标族、加动效——审美创新不在允许范围内。
12. 在 `ui/` 之外改 Compose，或为单个页面新建主题文件。

## 8. 提交前自检

```bash
grep -rn "Color(0xFF" app/src/main/kotlin/io/github/mangi/eta/ui --include=*.kt | grep -v StatusColors | grep -v ProviderBranding
grep -rn "dp)" app/src/main/kotlin/io/github/mangi/eta/ui/screens/<你改的文件> | head -30
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

人眼过一遍：

- [ ] COUI 与 MIUIX 两个分支都能看到完整功能，开关/输入/弹窗都可用。
- [ ] 切换界面样式后草稿与滚动位置仍在。
- [ ] 深色、浅色各看一遍，没有看不见的文字或硬白块。
- [ ] 空态、加载态、失败态都有文案，且不与真实内容混淆。
- [ ] 新文案在三个 `strings.xml` 中齐全。
- [ ] 页面顶部间距、分组间距与相邻页面一致，没有凭空多出的一条分割线或双份底部留白。
