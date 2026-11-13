package app.fireremote.phone

import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.HighlightOff
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Colors for the dark (desktop app) and light themes; [dark] is set from Settings / the phone. */
object C {
    var dark by mutableStateOf(true)
    private fun p(d: Long, l: Long) = Color(if (dark) d else l)
    val Bg get() = p(0xFF111214, 0xFFF3F4F6)
    val Surface get() = p(0xFF1F2125, 0xFFE4E7EB)
    val SurfacePressed get() = p(0xFF363A41, 0xFFCDD1D7)
    val Ring get() = p(0xFF1B1D21, 0xFFE9ECEF)
    val RingEdge get() = p(0xFF272A2F, 0xFFD2D6DC)
    val RingPressed get() = p(0xFF33373E, 0xFFD6DAE0)
    val OkFill get() = p(0xFF26292E, 0xFFFFFFFF)
    val OkEdge get() = p(0xFF3B3F47, 0xFFC6CBD2)
    val OkPressed get() = p(0xFF3A3E46, 0xFFE3E6EA)
    val Accent get() = Color(0xFFFF9900)
    val AccentText get() = Color(0xFF1C1300)
    val Icon get() = p(0xFFE7E9EC, 0xFF1F2328)
    val Text get() = p(0xFFE7E9EC, 0xFF1F2328)
    val SubText get() = p(0xFF8C9199, 0xFF5E6570)
    val Field get() = p(0xFF1A1C20, 0xFFFFFFFF)
    val FieldEdge get() = p(0xFF2C2F35, 0xFFCDD1D7)
    val Menu get() = p(0xFF1B1D21, 0xFFFFFFFF)
    val Green get() = p(0xFF3DDC84, 0xFF1F9D57)
    val Amber get() = p(0xFFF5A524, 0xFFC27800)
    val Red get() = p(0xFFF4525B, 0xFFD3343F)
    val Gray get() = p(0xFF6B7078, 0xFF8A9099)
}

/** Android key codes, as on the desktop remote. */
object K {
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val OK = 23
    const val BACK = 4
    const val HOME = 3
    const val MENU = 82
    const val REWIND = 89
    const val PLAY = 85
    const val FORWARD = 90
    const val MUTE = 164
    const val VOL_DOWN = 25
    const val VOL_UP = 24
    const val ENTER = 66
    const val DEL = 67
}

/** Tap: once. Repeat: repeats while held (arrows, volume, seek). Hold: long-press after 550 ms (OK, Back, Home, Menu). */
enum class Mode { Tap, Repeat, Hold }

class Target(val code: Int, val mode: Mode, val tap: (() -> Unit)? = null)

private const val DESIGN_W = 360f
private const val DESIGN_H = 490f

@Composable
fun RemoteApp() {
    val dark = when (UiPrefs.theme) {
        1 -> true
        2 -> false
        else -> isSystemInDarkTheme()
    }
    if (C.dark != dark) C.dark = dark
    val scheme = if (dark) {
        darkColorScheme(
            primary = C.Accent, onPrimary = C.AccentText, background = C.Bg, onBackground = C.Text,
            surface = C.Menu, onSurface = C.Text, onSurfaceVariant = C.SubText, surfaceContainer = C.Menu,
            surfaceContainerHigh = C.Surface, surfaceContainerLow = C.Menu, outline = C.FieldEdge,
        )
    } else {
        lightColorScheme(
            primary = C.Accent, onPrimary = C.AccentText, background = C.Bg, onBackground = C.Text,
            surface = C.Menu, onSurface = C.Text, onSurfaceVariant = C.SubText, surfaceContainer = C.Menu,
            surfaceContainerHigh = C.Surface, surfaceContainerLow = C.Menu, outline = C.FieldEdge,
        )
    }
    val base = LocalDensity.current
    MaterialTheme(colorScheme = scheme) {
        // Interface size: scales every dp and sp of the app.
        CompositionLocalProvider(LocalDensity provides Density(base.density * UiPrefs.scale, base.fontScale)) {
            Screens()
        }
    }
}

@Composable
private fun Screens() {
    val ctx = LocalContext.current
    val view = LocalView.current
    SideEffect { view.keepScreenOn = UiPrefs.keepScreenOn }
    val state by TvLink.state.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    var remoteButtons by rememberSaveable { mutableStateOf(false) }
    var showAddress by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showSite by remember { mutableStateOf(false) }
    var showPeek by remember { mutableStateOf(false) }
    var mouseMode by remember { mutableStateOf(false) }
    var favs by remember { mutableStateOf(Store.favorites(ctx)) }
    fun saveFavs(list: List<Fav>) {
        favs = list
        Store.setFavorites(ctx, list)
    }
    fun addFav(f: Fav) {
        when {
            favs.any { it.target == f.target } -> TvLink.notice(ctx.getString(R.string.fav_exists, f.name))
            favs.size >= Store.MAX_FAVORITES -> TvLink.notice(ctx.getString(R.string.fav_full, Store.MAX_FAVORITES))
            else -> saveFavs(favs + f)
        }
    }
    LaunchedEffect(Unit) {
        TvLink.notices.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    }
    // App logos: cached ones at once, then fetched from the TV the first time a favorite app has none.
    LaunchedEffect(Unit) { AppIcons.load(ctx) }
    val online = state.phase == TvLink.Phase.Online
    LaunchedEffect(online) {
        if (online && !AppIcons.triedThisSession && favs.any { !it.isSite && AppIcons.image(it.target) == null }) {
            runCatching { AppIcons.refresh(ctx) }
        }
    }
    val handlers = PadHandlers(
        peek = { showPeek = true },
        apps = { showApps = true },
        touchpad = { mouseMode = true },
        settings = { settings = true },
    )
    BackHandler(enabled = settings) { settings = false }
    BackHandler(enabled = !settings && remoteButtons) { remoteButtons = false }
    BackHandler(enabled = !settings && !remoteButtons && mouseMode) {
        TvLink.mouseEnd()
        mouseMode = false
    }

    Column(Modifier.fillMaxSize().background(C.Bg).safeDrawingPadding()) {
        if (settings) {
            SettingsScreen(state.target, onBack = { settings = false }, onAddress = { showAddress = true })
        } else if (remoteButtons) {
            RemoteButtonsScreen(onBack = { remoteButtons = false })
        } else {
            StatusBar(state, onAddress = { showAddress = true }, onSettings = { settings = true }, onRemoteButtons = { remoteButtons = true })
            if (mouseMode) {
                Touchpad(Modifier.weight(1f).fillMaxWidth())
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val u = minOf(maxWidth / DESIGN_W, maxHeight / DESIGN_H)
                    // The remote keeps its layout in right-to-left languages: left stays left.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Pad(u, handlers)
                    }
                }
            }
            FavRow(
                favs,
                onRemove = { f -> saveFavs(favs.filterNot { it.target == f.target }) },
                onAddApp = { showApps = true },
                onAddSite = { showSite = true },
            )
            BottomBar(
                onApps = { showApps = true },
                mouseOn = mouseMode,
                onMouse = {
                    if (mouseMode) TvLink.mouseEnd()
                    mouseMode = !mouseMode
                },
            )
        }
    }
    if (showAddress) AddressDialog(onDismiss = { showAddress = false })
    if (showApps) {
        AppsSheet(
            favs = favs,
            onTogglePin = { a ->
                if (favs.any { it.target == a.pkg }) saveFavs(favs.filterNot { it.target == a.pkg }) else addFav(Fav(a.name, a.pkg))
            },
            onDismiss = { showApps = false },
        )
    }
    if (showSite) SiteDialog(onAdd = { addFav(it) }, onDismiss = { showSite = false })
    if (showPeek) PeekSheet(onDismiss = { showPeek = false })
}

// ------------------------------------------------------------------ status bar

@Composable
private fun StatusBar(state: TvLink.State, onAddress: () -> Unit, onSettings: () -> Unit, onRemoteButtons: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), state.phase, state.retryAtMs) {
        while (state.phase == TvLink.Phase.Waiting) {
            value = System.currentTimeMillis()
            delay(500)
        }
    }
    val (dot, text) = when (state.phase) {
        TvLink.Phase.Online ->
            if (state.instant) C.Green to stringResource(R.string.status_instant) else C.Amber to stringResource(R.string.status_basic)
        TvLink.Phase.Connecting -> C.Gray to stringResource(R.string.status_connecting)
        TvLink.Phase.Authorizing -> C.Amber to stringResource(R.string.status_authorizing)
        TvLink.Phase.Waiting -> C.Red to stringResource(R.string.status_waiting, maxOf(0L, (state.retryAtMs - now + 999) / 1000).toInt())
        TvLink.Phase.Off -> C.Gray to stringResource(R.string.status_off)
    }
    val detail = when (state.phase) {
        TvLink.Phase.Authorizing -> stringResource(R.string.detail_authorizing)
        TvLink.Phase.Online -> if (state.instant) "" else state.detail
        else -> state.detail
    }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).background(dot, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(detail, color = C.SubText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onAddress) {
            Text(state.target.removeSuffix(":5555").ifEmpty { Store.host(ctx) }, color = C.SubText, fontSize = 13.sp)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.menu_more), tint = C.SubText) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (state.phase == TvLink.Phase.Off) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_connect)) }, onClick = { menu = false; MainActivity.connect(ctx) })
                } else {
                    if (state.phase == TvLink.Phase.Waiting) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_retry)) }, onClick = { menu = false; TvLink.retryNow() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_disconnect)) }, onClick = { menu = false; TvLink.stop(byUser = true) })
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_remote_buttons)) },
                    leadingIcon = { Icon(Icons.Rounded.SettingsRemote, null) },
                    onClick = { menu = false; onRemoteButtons() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_settings)) },
                    leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                    onClick = { menu = false; onSettings() },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ pad

/** Centre x, centre y and diameter of the 13 button slots, in design units (360 x 490). */
private val SLOT_GEOMETRY = listOf(
    Triple(38f, 30f, 46f), Triple(322f, 30f, 46f),
    Triple(95f, 318f, 60f), Triple(180f, 318f, 60f), Triple(265f, 318f, 60f),
    Triple(95f, 390f, 60f), Triple(180f, 390f, 60f), Triple(265f, 390f, 60f),
    Triple(95f, 458f, 52f), Triple(180f, 458f, 52f), Triple(265f, 458f, 52f),
    Triple(38f, 458f, 46f), Triple(322f, 458f, 46f),
)

class PadHandlers(val peek: () -> Unit, val apps: () -> Unit, val touchpad: () -> Unit, val settings: () -> Unit)

fun actionIcon(a: Action): ImageVector = when (a) {
    Action.HIDDEN -> Icons.Rounded.VisibilityOff
    Action.POWER -> Icons.Rounded.PowerSettingsNew
    Action.TV_SETTINGS -> Icons.Rounded.Settings
    Action.BACK -> Icons.AutoMirrored.Rounded.Undo
    Action.HOME -> Icons.Outlined.Home
    Action.MENU -> Icons.Rounded.Menu
    Action.REWIND -> Icons.Rounded.FastRewind
    Action.PLAY -> Icons.Rounded.PlayArrow
    Action.FORWARD -> Icons.Rounded.FastForward
    Action.MUTE -> Icons.AutoMirrored.Rounded.VolumeOff
    Action.VOL_DOWN -> Icons.AutoMirrored.Rounded.VolumeDown
    Action.VOL_UP -> Icons.AutoMirrored.Rounded.VolumeUp
    Action.PEEK -> Icons.Rounded.Visibility
    Action.CLOSE_APP -> Icons.Rounded.HighlightOff
    Action.TOUCHPAD -> Icons.Rounded.Mouse
    Action.APPS -> Icons.Rounded.Apps
    Action.SEARCH -> Icons.Rounded.Search
    Action.APP_SETTINGS -> Icons.Rounded.Tune
}

@Composable
private fun Pad(u: Dp, h: PadHandlers) {
    Box(Modifier.size(u * DESIGN_W, u * DESIGN_H)) {
        DPad(u, 180f, 150f, 128f)
        val scale = UiPrefs.buttonScale.coerceIn(0.85f, 1.15f)
        UiPrefs.slots.forEachIndexed { i, a ->
            if (a == Action.HIDDEN) return@forEachIndexed
            val (cx, cy, d) = SLOT_GEOMETRY[i]
            val tap: (() -> Unit)? = when (a) {
                Action.POWER -> { { TvLink.power() } }
                Action.TV_SETTINGS -> { { TvLink.openSettings() } }
                Action.CLOSE_APP -> { { TvLink.closeApp() } }
                Action.PEEK -> h.peek
                Action.TOUCHPAD -> h.touchpad
                Action.APPS -> h.apps
                Action.APP_SETTINGS -> h.settings
                else -> null
            }
            key(i) {
                RoundKey(
                    u, cx, cy, d * scale, Target(a.code, a.mode, tap), a, actionIcon(a), stringResource(a.label),
                    icon2 = if (a == Action.PLAY) Icons.Rounded.Pause else null,
                    iconScale = if (d < 50f) 0.46f else 0.42f,
                )
            }
        }
    }
}

@Composable
private fun RoundKey(
    u: Dp, cx: Float, cy: Float, d: Float, target: Target, id: Any, icon: ImageVector, label: String,
    icon2: ImageVector? = null, iconScale: Float = 0.42f,
) {
    var down by remember { mutableStateOf(false) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .offset(u * (cx - d / 2), u * (cy - d / 2))
            .size(u * d)
            .clip(CircleShape)
            .background(if (down) C.SurfacePressed else C.Surface)
            .pointerInput(id) { remoteGestures(scope, view, { target }) { down = it != null } },
        contentAlignment = Alignment.Center,
    ) {
        if (icon2 == null) {
            Icon(icon, label, tint = C.Icon, modifier = Modifier.size(u * d * iconScale))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, label, tint = C.Icon, modifier = Modifier.size(u * d * 0.32f))
                Icon(icon2, null, tint = C.Icon, modifier = Modifier.size(u * d * 0.28f))
            }
        }
    }
}

/** The ring: four arrows that repeat while held, and OK in the middle (hold for a long-press). */
@Composable
private fun DPad(u: Dp, cx: Float, cy: Float, r: Float) {
    var active by remember { mutableStateOf<Int?>(null) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val okFraction = 0.39f
    Box(
        Modifier
            .offset(u * (cx - r), u * (cy - r))
            .size(u * r * 2)
            .pointerInput(UiPrefs.dpadSwipe) {
                val resolve = { p: Offset ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val v = p - c
                    val dist = v.getDistance()
                    val outer = size.width / 2f
                    when {
                        dist <= outer * (okFraction + 0.03f) -> Target(K.OK, Mode.Hold)
                        dist <= outer -> {
                            val a = Math.toDegrees(atan2(v.y, v.x).toDouble())
                            val code = when {
                                a >= -45 && a < 45 -> K.RIGHT
                                a >= 45 && a < 135 -> K.DOWN
                                a >= -135 && a < -45 -> K.UP
                                else -> K.LEFT
                            }
                            Target(code, Mode.Repeat)
                        }
                        else -> null
                    }
                }
                if (UiPrefs.dpadSwipe) dpadGestures(scope, view, resolve) { active = it?.code }
                else remoteGestures(scope, view, resolve) { active = it?.code }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val outer = size.minDimension / 2f
            val c = center
            val ok = outer * okFraction
            val line = 1.dp.toPx()
            drawCircle(C.Ring, outer, c)
            val sweepStart = when (active) {
                K.RIGHT -> -45f
                K.DOWN -> 45f
                K.LEFT -> 135f
                K.UP -> 225f
                else -> null
            }
            if (sweepStart != null) {
                drawArc(C.RingPressed, sweepStart, 90f, true, topLeft = Offset(c.x - outer, c.y - outer), size = Size(outer * 2, outer * 2))
            }
            drawCircle(C.RingEdge, outer - line / 2, c, style = Stroke(line))
            for (deg in listOf(45f, 135f, 225f, 315f)) {
                val rad = Math.toRadians(deg.toDouble())
                val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
                drawLine(C.RingEdge, c + dir * (ok + 8.dp.toPx()), c + dir * (outer - 8.dp.toPx()), 1.5.dp.toPx())
            }
            // Chevrons, drawn pointing right and rotated into place.
            val arm = outer * 0.075f
            for ((code, deg) in listOf(K.RIGHT to 0f, K.DOWN to 90f, K.LEFT to 180f, K.UP to 270f)) {
                rotate(deg, c) {
                    val x = c.x + outer * 0.72f
                    val p = Path().apply {
                        moveTo(x - arm * 0.55f, c.y - arm)
                        lineTo(x + arm * 0.45f, c.y)
                        lineTo(x - arm * 0.55f, c.y + arm)
                    }
                    drawPath(p, if (active == code) C.Accent else C.Icon, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            drawCircle(if (active == K.OK) C.OkPressed else C.OkFill, ok, c)
            drawCircle(C.OkEdge, ok - line / 2, c, style = Stroke(line))
            val label = measurer.measure("OK", TextStyle(color = C.Text, fontSize = (ok / 2.6f / density).sp, fontWeight = FontWeight.SemiBold))
            drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - label.size.height / 2f))
        }
    }
}

private fun direction(d: Offset) =
    if (abs(d.x) >= abs(d.y)) (if (d.x > 0) K.RIGHT else K.LEFT) else (if (d.y > 0) K.DOWN else K.UP)

/**
 * D-pad like the Amazon app: tap an arrow or OK as usual, or slide anywhere on the pad to move.
 * A longer slide moves more steps; resting the finger after a slide keeps moving that way.
 * Without sliding: arrows repeat after 380 ms, OK long-presses after 550 ms.
 */
private suspend fun PointerInputScope.dpadGestures(
    scope: CoroutineScope,
    view: View,
    resolve: (Offset) -> Target?,
    pressed: (Target?) -> Unit,
) {
    val startPx = 22.dp.toPx()
    val stepPx = 46.dp.toPx()
    val restPx = 6.dp.toPx()
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        val t = resolve(first.position) ?: return@awaitEachGesture
        first.consume()
        pressed(t)
        buzz(view)
        var swiping = false
        var longFired = false
        var repeated = false
        var anchor = first.position
        var restAt = first.position
        var dir = t.code
        fun keepGoing() = scope.launch {
            delay(400)
            while (isActive) {
                TvLink.key(dir)
                delay(120)
            }
        }
        var job: Job? = scope.launch {
            if (t.mode == Mode.Hold) {
                delay(550)
                longFired = true
                buzz(view, HapticFeedbackConstants.LONG_PRESS)
                TvLink.longPress(t.code)
            } else {
                delay(380)
                repeated = true
                while (isActive) {
                    TvLink.key(t.code)
                    delay(110)
                }
            }
        }
        var lifted = false
        try {
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == first.id } ?: break
                if (!ch.pressed) {
                    lifted = true
                    break
                }
                val moved = ch.position - anchor
                if (!swiping && !longFired && !repeated && moved.getDistance() > startPx) {
                    swiping = true
                    job?.cancel()
                    pressed(null)
                    dir = direction(moved)
                    TvLink.key(dir)
                    buzz(view)
                    anchor = ch.position
                    restAt = ch.position
                    job = keepGoing()
                } else if (swiping) {
                    if (abs(moved.x) >= stepPx || abs(moved.y) >= stepPx) {
                        dir = direction(moved)
                        TvLink.key(dir)
                        buzz(view)
                        anchor = ch.position
                    }
                    if ((ch.position - restAt).getDistance() > restPx) {
                        restAt = ch.position
                        job?.cancel()
                        job = keepGoing()
                    }
                }
                ch.consume()
            }
        } finally {
            job?.cancel()
            pressed(null)
        }
        if (lifted && !swiping && !longFired && !repeated) TvLink.key(t.code)
    }
}

/**
 * Press handling shared by all buttons. Keys go out on touch-down for speed; Hold buttons wait to see
 * whether it's a tap (normal key) or a 550 ms hold (long-press). Repeat buttons repeat after 380 ms, every 110 ms.
 */
private suspend fun PointerInputScope.remoteGestures(
    scope: CoroutineScope,
    view: View,
    resolve: (Offset) -> Target?,
    pressed: (Target?) -> Unit,
) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        val t = resolve(first.position) ?: return@awaitEachGesture
        first.consume()
        pressed(t)
        buzz(view)
        val press = t.tap ?: { TvLink.key(t.code) }
        var longFired = false
        val job: Job? = when (t.mode) {
            Mode.Tap -> {
                press()
                null
            }
            Mode.Repeat -> {
                press()
                scope.launch {
                    delay(380)
                    while (isActive) {
                        press()
                        delay(110)
                    }
                }
            }
            Mode.Hold -> scope.launch {
                delay(550)
                longFired = true
                buzz(view, HapticFeedbackConstants.LONG_PRESS)
                TvLink.longPress(t.code)
            }
        }
        try {
            val up = waitForUpOrCancellation()
            job?.cancel()
            if (t.mode == Mode.Hold && !longFired && up != null) press()
        } finally {
            job?.cancel()
            pressed(null)
        }
    }
}

// ------------------------------------------------------------------ bottom bar: apps, touchpad, typing

@Composable
private fun BottomBar(onApps: () -> Unit, mouseOn: Boolean, onMouse: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(C.Surface)
                .clickable(onClick = onApps)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Apps, null, tint = C.Icon, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.apps), color = C.Text, fontSize = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (mouseOn) C.Accent else C.Surface)
                .clickable(onClick = onMouse),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Mouse,
                stringResource(if (mouseOn) R.string.touchpad_off else R.string.touchpad),
                tint = if (mouseOn) C.AccentText else C.Icon, modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        TypeField(Modifier.weight(1f))
    }
}

/**
 * Live typing: every change in this field is mirrored on the TV (typed letters, deletions, autocorrect,
 * voice typing). Enter sends Enter; the clear button only clears the phone side.
 */
@Composable
private fun TypeField(modifier: Modifier) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val shape = RoundedCornerShape(24.dp)
    BasicTextField(
        value = value,
        onValueChange = { next ->
            if (next.text != value.text) {
                val (deletes, insert) = TextDiff.diff(value.text, next.text)
                if (deletes > 0) TvLink.keys(K.DEL, deletes)
                if (insert.isNotEmpty()) TvLink.text(insert)
            }
            value = next
        },
        singleLine = true,
        textStyle = TextStyle(color = C.Text, fontSize = 15.sp),
        cursorBrush = SolidColor(C.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = {
            TvLink.key(K.ENTER)
            value = TextFieldValue("")
        }),
        modifier = modifier.height(48.dp),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize().clip(shape).background(C.Field).border(1.dp, C.FieldEdge, shape).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Keyboard, null, tint = C.SubText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.text.isEmpty()) Text(stringResource(R.string.type_hint), color = C.SubText, fontSize = 15.sp, maxLines = 1)
                    inner()
                }
                if (value.text.isNotEmpty()) {
                    IconButton(onClick = { value = TextFieldValue("") }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.clear), tint = C.SubText, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
    )
}

// ------------------------------------------------------------------ dialogs

@Composable
private fun AddressDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(Address.format(Store.host(ctx), Store.port(ctx))) }
    var bad by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(stringResource(R.string.address_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; bad = false },
                    singleLine = true,
                    isError = bad,
                    label = { Text(stringResource(R.string.address_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (bad) R.string.address_bad else R.string.address_help),
                    color = if (bad) C.Red else C.SubText, fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    enabled = !finding,
                    onClick = {
                        finding = true
                        found = null
                        scope.launch {
                            val parsed = Address.parse(text)
                            val hit = runCatching { TvLink.findTv(parsed?.second ?: 5555, parsed?.first, null) }.getOrNull()
                            finding = false
                            found = if (hit != null) {
                                text = Address.format(hit, parsed?.second ?: 5555)
                                ctx.getString(R.string.found_tv, hit)
                            } else {
                                ctx.getString(R.string.no_tv_found)
                            }
                        }
                    },
                ) {
                    if (finding) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = C.Accent)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.finding_tv))
                    } else {
                        Text(stringResource(R.string.find_tv))
                    }
                }
                found?.let { Text(it, color = C.SubText, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = Address.parse(text)
                if (t == null) {
                    bad = true
                } else {
                    Store.setTarget(ctx, t.first, t.second)
                    TvLink.cachedApps = null
                    MainActivity.connect(ctx)
                    onDismiss()
                }
            }) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsSheet(favs: List<Fav>, onTogglePin: (TvApp) -> Unit, onDismiss: () -> Unit) {
    var apps by remember { mutableStateOf(TvLink.cachedApps) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    fun load() {
        if (loading) return
        scope.launch {
            loading = true
            error = null
            try {
                val list = TvLink.listApps()
                    .sortedWith(compareBy<TvApp> { it.system }.thenBy { it.name.lowercase() })
                TvLink.cachedApps = list
                apps = list
                if (!AppIcons.refreshing && (!AppIcons.triedThisSession || list.any { AppIcons.image(it.pkg) == null })) {
                    launch { runCatching { AppIcons.refresh(ctx) } }
                }
            } catch (e: Exception) {
                error = ctx.getString(R.string.apps_error, TvLink.describe(e))
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { if (apps == null) load() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.Menu,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.apps_title), color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.Accent)
                IconButton(onClick = { load() }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh), tint = C.SubText) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(stringResource(R.string.search)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            val list = apps
            when {
                error != null && list == null -> Text(error!!, color = C.Red, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                list == null -> Text(stringResource(R.string.apps_loading), color = C.SubText, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                else -> {
                    val q = query.trim().lowercase()
                    val shown = if (q.isEmpty()) list else list.filter { q in it.name.lowercase() || q in it.pkg.lowercase() }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(shown, key = { it.pkg }) { a ->
                            val pinned = favs.any { it.target == a.pkg }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        TvLink.launch(a.pkg)
                                        onDismiss()
                                    }
                                    .padding(start = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppLogo(a.pkg, a.name, Modifier.size(64.dp, 36.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                    Text(a.name, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (a.system) stringResource(R.string.app_system, a.pkg) else a.pkg,
                                        color = C.SubText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { onTogglePin(a) }) {
                                    Icon(
                                        if (pinned) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                        stringResource(if (pinned) R.string.fav_remove_star else R.string.fav_add_star),
                                        tint = if (pinned) C.Accent else C.SubText,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
