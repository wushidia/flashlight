package io.custom.flashlight

import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.roundToInt

@Composable
fun FlashlightApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { FlashlightState(context, scope) }
    var settingsPage by rememberSaveable { mutableStateOf(false) }
    val back = remember { Animatable(0f) }
    val turnOffFirst = stringResource(R.string.turn_off_first)
    LaunchedEffect(state) { state.initialize() }
    PredictiveBackHandler(enabled = settingsPage) { events ->
        try {
            events.collect { back.snapTo(it.progress) }
            settingsPage = false
        } catch (_: CancellationException) {
            // Restore with the same spring after cancelling a back gesture.
            scope.launch { back.animateTo(0f, folmeSpring(damping = 0.9f, response = 0.35f)) }
        }
    }
    AnimatedContent(
        targetState = settingsPage,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val direction = if (targetState) 1 else -1
            (slideInHorizontally(folmeSpring(damping = 0.9f, response = 0.35f)) { direction * it / 5 } + fadeIn(folmeSpring(damping = 0.9f, response = 0.35f))) togetherWith
                (slideOutHorizontally(folmeSpring(damping = 0.9f, response = 0.35f)) { -direction * it / 8 } + fadeOut(folmeSpring(damping = 0.9f, response = 0.35f)))
        },
        label = "page",
    ) { showSettings ->
        Box(Modifier.fillMaxSize().graphicsLayer {
            if (showSettings) {
                translationX = size.width * 0.25f * back.value
                scaleX = 1f - back.value * 0.04f
                scaleY = scaleX
            }
        }) {
            FlashlightPage(state, showSettings, onBack = { settingsPage = false }, onSettings = {
                if (state.activeIds.isNotEmpty()) state.message = turnOffFirst
                else scope.launch { back.snapTo(0f); settingsPage = true }
            })
        }
    }
}

@Composable
private fun FlashlightPage(state: FlashlightState, settings: Boolean, onBack: () -> Unit, onSettings: () -> Unit) {
    val scroll = MiuixScrollBehavior()
    val list = rememberLazyListState()
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = if (Build.VERSION.SDK_INT >= 33 && isRuntimeShaderSupported()) rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    } else null
    val barColor = if (backdrop != null) Color.Transparent else surface
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidePadding = ((maxWidth - 600.dp) / 2).coerceAtLeast(0.dp)
        Scaffold(
            topBar = {
                // Same surface blend and 25dp texture blur used by AppMarket's BlurredBar.
                Box(if (backdrop != null) Modifier.textureBlur(
                    backdrop, RectangleShape, blurRadius = 25f,
                    colors = BlurColors(blendColors = listOf(BlendColorEntry(surface.copy(alpha = 0.8f)))),
                ) else Modifier) {
                    TopAppBar(
                        title = stringResource(if (settings) R.string.settings else R.string.app_name),
                        color = barColor,
                        scrollBehavior = scroll,
                        titlePadding = sidePadding + 28.dp,
                        navigationIconPadding = sidePadding + 12.dp,
                        actionIconPadding = sidePadding + 12.dp,
                        navigationIcon = {
                            if (settings) IconButton(onClick = onBack) {
                                Icon(MiuixIcons.Back, stringResource(R.string.back))
                            }
                        },
                        actions = {
                            if (!settings) IconButton(
                                onClick = onSettings, enabled = !state.loading && !state.busy,
                                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                                minWidth = 40.dp, minHeight = 40.dp,
                            ) { Icon(MiuixIcons.Settings, stringResource(R.string.settings)) }
                        },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                val horizontal = sidePadding
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal))
                        .scrollEndHaptic().overScrollVertical()
                        .nestedScroll(scroll.nestedScrollConnection)
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
                    contentPadding = PaddingValues(start = horizontal, end = horizontal, top = padding.calculateTopPadding()),
                    overscrollEffect = null,
                ) {
                    if (settings) settingsItems(state) else homeItems(state)
                    item("bottom") { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
                }
            }
            if (!settings) {
                InfoDialog(state.message != null, state.message.orEmpty()) { state.message = null }
            }
        }
    }
}

private fun LazyListScope.homeItems(state: FlashlightState) {
    item("space") { Spacer(Modifier.height(12.dp)) }
    item("power") {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp), insideMargin = PaddingValues(24.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(if (state.isActive) R.string.light_on else R.string.light_off), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                PowerButton(state.isActive, !state.loading && !state.busy, state::toggle)
            }
        }
    }
    item("device-title") { SmallTitle(stringResource(R.string.light_source)) }
    item("device") {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            if (state.devices.isNotEmpty()) WindowSpinnerPreference(
                title = stringResource(R.string.flashlight),
                items = state.devices.map { DropdownItem(it.label) },
                selectedIndex = state.selectedIndex,
                onSelectedIndexChange = state::select, enabled = !state.busy,
            ) else Text(stringResource(R.string.discovering), Modifier.padding(24.dp))
        }
    }
    item("brightness-title") { SmallTitle(stringResource(R.string.brightness)) }
    item("brightness") {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp), insideMargin = PaddingValues(0.dp)) {
            BrightnessPanel(state)
        }
    }
    item("refresh") {
        TextButton(text = stringResource(R.string.refresh_devices), onClick = state::refresh, enabled = !state.loading && !state.busy && state.activeIds.isEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
    }
}

@Composable
private fun PowerButton(active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) 0.93f else 1f, folmeSpring(damping = 0.9f, response = 0.35f), label = "power-press")
    val fill = animateColorAsState(if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer, folmeSpring(damping = 0.9f, response = 0.35f), label = "power-fill")
    val ink = animateColorAsState(if (active) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.primary, folmeSpring(damping = 0.9f, response = 0.35f), label = "power-icon")
    val halo = animateFloatAsState(if (active) 1f else 0f, folmeSpring(damping = 0.9f, response = 0.35f), label = "power-halo")
    val haptic = LocalHapticFeedback.current
    val primary = MiuixTheme.colorScheme.primary
    val description = stringResource(if (active) R.string.close else R.string.open)
    val status = stringResource(if (active) R.string.on else R.string.off)
    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(208.dp).drawBehind {
            drawCircle(primary.copy(alpha = 0.06f * halo.value))
            drawCircle(primary.copy(alpha = 0.08f * halo.value), radius = size.minDimension * 0.46f)
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(164.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .drawBehind { drawCircle(fill.value) }
                .semantics { contentDescription = description; stateDescription = status }
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) {
                    haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    onClick()
                }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(62.dp)) {
                    val stroke = 5.dp.toPx()
                    drawArc(ink.value, 135f, 270f, false,
                        topLeft = Offset(stroke, stroke), size = androidx.compose.ui.geometry.Size(size.width - stroke * 2, size.height - stroke * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round))
                    drawLine(ink.value, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height * 0.45f), strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun BrightnessPanel(state: FlashlightState) {
    val enabled = !state.loading && !state.busy && state.device?.id != "unknown"
    var editing by rememberSaveable { mutableStateOf(false) }
    ArrowPreference(
        title = stringResource(R.string.brightness_value),
        endActions = { Text(state.brightness.toString(), color = MiuixTheme.colorScheme.primary) },
        enabled = enabled,
        holdDownState = editing,
        onClick = { editing = true },
    )
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.low, R.string.mid, R.string.high).forEachIndexed { index, label ->
                val selected = state.brightness == state.gears[index]
                Button(onClick = { state.changeBrightness(state.gears[index]) }, enabled = enabled,
                    modifier = Modifier.weight(1f), minWidth = 0.dp,
                    colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                    Text(stringResource(label))
                }
            }
        }
        AnimatedVisibility(visible = !state.settings.useButtons,
            enter = expandVertically(folmeSpring(0.9f, 0.35f)) + fadeIn(),
            exit = shrinkVertically(folmeSpring(0.9f, 0.35f)) + fadeOut()) {
            Column(Modifier.padding(top = 16.dp)) {
                Slider(
                    value = state.brightness.toFloat(), onValueChange = { state.changeBrightness(it.roundToInt()) },
                    valueRange = state.minimum.toFloat()..state.maximum.coerceAtLeast(state.minimum + 1).toFloat(),
                    enabled = enabled && state.maximum > state.minimum,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(state.minimum.toString(), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(state.maximum.toString(), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
    BrightnessEditDialog(state, editing, onDismiss = { editing = false })
}

@Composable
private fun BrightnessEditDialog(state: FlashlightState, show: Boolean, onDismiss: () -> Unit) {
    val input = rememberTextFieldState()
    LaunchedEffect(show, state.device?.id) {
        if (show) input.setTextAndPlaceCursorAtEnd(state.brightness.toString())
    }
    val value = input.text.toString().toIntOrNull()
    val valid = value != null && value in state.minimum..state.maximum && !state.busy && !state.loading
    val confirm = {
        if (valid) {
            state.changeBrightness(requireNotNull(value))
            onDismiss()
        }
    }
    WindowDialog(show = show, title = stringResource(R.string.brightness_value), onDismissRequest = onDismiss) {
        TextField(
            state = input,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.brightness_range, state.minimum, state.maximum),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            inputTransformation = InputTransformation {
                if (length > 10 || asCharSequence().any { it !in '0'..'9' }) revertAllChanges()
            },
            onKeyboardAction = { confirm() },
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = stringResource(R.string.cancel), modifier = Modifier.weight(1f), onClick = onDismiss)
            TextButton(text = stringResource(R.string.ok), modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(), enabled = valid, onClick = confirm)
        }
    }
}

private data class SettingRow(val title: Int, val summary: Int?, val checked: Boolean, val change: (Boolean) -> Unit)

private fun LazyListScope.settingsItems(state: FlashlightState) {
    val s = state.settings
    fun group(key: String, title: Int, rows: List<SettingRow>) {
        item("$key-title") { SmallTitle(stringResource(title)) }
        rows.forEachIndexed { index, row ->
            item("$key-${row.title}") {
                val top = if (index == 0) 16.dp else 0.dp
                val bottom = if (index == rows.lastIndex) 16.dp else 0.dp
                val color = MiuixTheme.colorScheme.surfaceContainer
                val background = if (top == 0.dp && bottom == 0.dp) Modifier.background(color)
                    else Modifier.squircleSurface(color, top, top, bottom, bottom)
                CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onSurfaceContainer) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        .padding(bottom = if (index == rows.lastIndex) 12.dp else 0.dp).then(background)) {
                        SwitchPreference(title = stringResource(row.title), summary = row.summary?.let { stringResource(it) },
                            checked = row.checked, onCheckedChange = row.change)
                    }
                }
            }
        }
    }
    group("brightness", R.string.brightness, listOf(
        SettingRow(R.string.slider_adjust, null, !s.useButtons) { state.updateSettings(s.copy(useButtons = !it)) },
        SettingRow(R.string.limit_min_bright, R.string.limit_min_bright_summary, s.limitMin) { state.updateSettings(s.copy(limitMin = it)) },
        SettingRow(R.string.limit_max_bright, R.string.limit_max_bright_summary, s.limitMax) { state.updateSettings(s.copy(limitMax = it)) },
        SettingRow(R.string.dyn_bright_adj, R.string.dyn_bright_adj_summary, s.dynamicBrightness) { state.updateSettings(s.copy(dynamicBrightness = it)) },
    ))
    group("startup", R.string.startup, listOf(
        SettingRow(R.string.save_last_flash, null, s.saveLastFlash) { state.updateSettings(s.copy(saveLastFlash = it)) },
        SettingRow(R.string.save_last_bright, null, s.saveLastBrightness) { state.updateSettings(s.copy(saveLastBrightness = it)) },
        SettingRow(R.string.open_on_start, null, s.openOnStart) { state.updateSettings(s.copy(openOnStart = it)) },
    ))
    group("advanced", R.string.advanced, listOf(
        SettingRow(R.string.ctrl_multi_flash, R.string.ctrl_multi_flash_summary, s.multiFlash) { state.updateSettings(s.copy(multiFlash = it)) },
        SettingRow(R.string.tile_bright_adj, null, s.tileBrightness) { state.updateSettings(s.copy(tileBrightness = it)) },
    ))
    item("tile") {
        if (state.devices.isNotEmpty()) Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            WindowSpinnerPreference(title = stringResource(R.string.tile_flash_select),
                items = state.devices.map { DropdownItem(it.label) }, selectedIndex = s.tileFlashIndex.coerceIn(0, state.devices.lastIndex),
                onSelectedIndexChange = { state.updateSettings(s.copy(tileFlashIndex = it)) })
        }
    }
}

@Composable
private fun InfoDialog(show: Boolean, body: String, dismiss: () -> Unit) {
    WindowDialog(show = show, title = stringResource(R.string.tips), onDismissRequest = dismiss) {
        Column(Modifier.heightIn(max = 420.dp)) {
            Text(body, modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
            Spacer(Modifier.height(20.dp))
            Button(onClick = dismiss, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(stringResource(R.string.ok))
            }
        }
    }
}
