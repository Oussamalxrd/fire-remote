package app.fireremote.phone

import android.graphics.BitmapFactory
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

// ------------------------------------------------------------------ favorites

/**
 * Up to 6 tiles sharing the whole width (3 favorites = 3 wide tiles), with a slim + at the end.
 * Tiles are close to 16:9 so TV banners fit. Tap opens the app or site, long-press removes it.
 */
@Composable
fun FavRow(favs: List<Fav>, onRemove: (Fav) -> Unit, onAddApp: () -> Unit, onAddSite: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp)) {
        val gap = 8.dp
        val n = favs.size
        val canAdd = n < Store.MAX_FAVORITES
        val addWidth = if (canAdd && n > 0) 48.dp else 0.dp
        val gaps = if (canAdd) n else n - 1
        val tileWidth = if (n == 0) maxWidth else (maxWidth - addWidth - gap * gaps) / n
        val tileHeight = (tileWidth * 9f / 16f).coerceIn(52.dp, 68.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            favs.forEach { f -> FavTile(f, Modifier.weight(1f), tileHeight, onRemove) }
            if (canAdd) AddTile(if (n == 0) Modifier.weight(1f) else Modifier.width(addWidth), tileHeight, onAddApp, onAddSite)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavTile(f: Fav, modifier: Modifier, height: Dp, onRemove: (Fav) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val view = LocalView.current
    val argb = Brand.color(f.name, f.target)
    val ink = if (Brand.darkText(argb)) Color(0xFF1C1300) else Color.White
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(argb))
                    .combinedClickable(
                        onClick = {
                            buzz(view)
                            TvLink.open(f)
                        },
                        onLongClick = {
                            buzz(view, HapticFeedbackConstants.LONG_PRESS)
                            menu = true
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (!f.isSite && AppIcons.image(f.target) != null) {
                    AppLogo(f.target, f.name, Modifier.fillMaxSize(), corner = 14.dp)
                } else {
                    if (f.isSite) {
                        Icon(Icons.Rounded.Language, null, tint = ink.copy(alpha = 0.4f), modifier = Modifier.size(height * 0.75f))
                    }
                    Text(f.name.trim().take(1).uppercase(), color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.fav_remove, f.name)) }, onClick = { menu = false; onRemove(f) })
            }
        }
        Text(
            f.name, color = C.SubText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The app's own logo from the TV: its 16:9 TV banner, or its icon; a colored initial until it's loaded. */
@Composable
fun AppLogo(pkg: String, name: String, modifier: Modifier, corner: Dp = 8.dp) {
    val img = AppIcons.image(pkg)
    val banner = AppIcons.isBanner(pkg)
    val argb = Brand.color(name, pkg)
    val shape = RoundedCornerShape(corner)
    Box(
        modifier.clip(shape).background(if (img != null && !banner) C.Surface else Color(argb)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            img == null -> Text(
                name.trim().take(1).uppercase(),
                color = if (Brand.darkText(argb)) Color(0xFF1C1300) else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
            banner -> Image(img, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> Image(img, name, Modifier.fillMaxHeight(0.82f).aspectRatio(1f), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun AddTile(modifier: Modifier, height: Dp, onAddApp: () -> Unit, onAddSite: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier.fillMaxWidth().height(height).clip(shape).border(1.dp, C.FieldEdge, shape).clickable { menu = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, stringResource(R.string.fav_add), tint = C.SubText)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.fav_pin_app)) }, onClick = { menu = false; onAddApp() })
                DropdownMenuItem(text = { Text(stringResource(R.string.fav_add_site)) }, onClick = { menu = false; onAddSite() })
            }
        }
        Text(stringResource(R.string.fav_add_short), color = C.SubText, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun SiteDialog(onAdd: (Fav) -> Unit, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(stringResource(R.string.site_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; bad = false },
                    singleLine = true,
                    isError = bad,
                    label = { Text(stringResource(R.string.site_address)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.site_name)) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (bad) R.string.site_bad else R.string.site_help),
                    color = if (bad) C.Red else C.SubText, fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val url = Site.clean(address)
                if (url == null) {
                    bad = true
                } else {
                    onAdd(Fav(name.trim().ifEmpty { Site.defaultName(url) }, url))
                    onDismiss()
                }
            }) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------ touchpad

/**
 * The phone screen as a laptop touchpad for the TV cursor:
 * one finger moves, tap clicks, two fingers scroll, two-finger tap goes Back.
 * Dragging: hold the Click button and move with another finger (resting a finger never starts a drag).
 */
@Composable
fun Touchpad(modifier: Modifier) {
    val view = LocalView.current
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(shape)
                .background(C.Ring)
                .border(1.dp, C.RingEdge, shape)
                .pointerInput(Unit) {
                    var remX = 0f
                    var remY = 0f
                    val scrollStep = 26.dp.toPx()
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val start = first.uptimeMillis
                        var end = start
                        var moved = 0f
                        var fingers = 1
                        var scrollY: Float? = null
                        var scrollAcc = 0f
                        val wheel = if (UiPrefs.naturalScroll) 1 else -1
                        val speed = UiPrefs.touchpadSpeed
                        while (true) {
                            val event = awaitPointerEvent()
                            val down = event.changes.filter { it.pressed }
                            event.changes.firstOrNull()?.let { end = it.uptimeMillis }
                            if (down.isEmpty()) break
                            fingers = maxOf(fingers, down.size)
                            if (down.size >= 2) {
                                val y = down.map { it.position.y }.average().toFloat()
                                scrollY?.let { prev ->
                                    scrollAcc += y - prev
                                    moved += kotlin.math.abs(y - prev)
                                    while (scrollAcc >= scrollStep) { TvLink.mouseWheel(wheel); scrollAcc -= scrollStep }
                                    while (scrollAcc <= -scrollStep) { TvLink.mouseWheel(-wheel); scrollAcc += scrollStep }
                                }
                                scrollY = y
                            } else {
                                scrollY = null
                                val d = down[0].positionChange()
                                val dist = d.getDistance()
                                if (dist > 0f) {
                                    moved += dist
                                    // Faster swipes travel further, like a laptop touchpad.
                                    val f = (1.1f + minOf(dist / 14f, 1.6f)) * speed
                                    val mx = d.x * f + remX
                                    val my = d.y * f + remY
                                    val ix = mx.toInt()
                                    val iy = my.toInt()
                                    remX = mx - ix
                                    remY = my - iy
                                    if (ix != 0 || iy != 0) TvLink.mouseMove(ix, iy)
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                        val quick = end - start < 300 && moved < viewConfiguration.touchSlop * 1.5f
                        when {
                            quick && fingers >= 2 -> TvLink.key(K.BACK)
                            quick -> {
                                buzz(view)
                                TvLink.mouseButton(true)
                                TvLink.mouseButton(false)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.touchpad_help),
                color = C.SubText, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadButton(stringResource(R.string.key_back), Modifier.weight(1f), onDown = { TvLink.key(K.BACK) }, onUp = {}, icon = true)
            PadButton(stringResource(R.string.touchpad_click), Modifier.weight(2f), onDown = { TvLink.mouseButton(true) }, onUp = { TvLink.mouseButton(false) })
        }
    }
}

@Composable
private fun PadButton(label: String, modifier: Modifier, onDown: () -> Unit, onUp: () -> Unit, icon: Boolean = false) {
    var down by remember { mutableStateOf(false) }
    val view = LocalView.current
    Row(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (down) C.SurfacePressed else C.Surface)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    down = true
                    buzz(view)
                    onDown()
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        onUp()
                        down = false
                    }
                }
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(Icons.AutoMirrored.Rounded.Undo, null, tint = C.Icon, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ screen peek

/** A still screenshot of the TV, refreshed on demand or continuously in Live mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeekSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf(false) }
    var refreshes by remember { mutableIntStateOf(0) }
    val peeker = remember { TvLink.Peeker() }
    DisposableEffect(Unit) { onDispose { peeker.close() } }
    LaunchedEffect(refreshes, live) {
        do {
            loading = true
            try {
                val png = peeker.shot()
                image = withContext(Dispatchers.Default) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeByteArray(png, 0, png.size, opts)?.asImageBitmap()
                        ?: throw IOException("unreadable image")
                }
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = ctx.getString(R.string.peek_error, TvLink.describe(e))
                if (live) delay(2_000)
            } finally {
                loading = false
            }
            if (live) delay(250)
        } while (live)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.Menu,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.peek_title), color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.peek_live), color = C.SubText, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = live, onCheckedChange = { live = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = C.AccentText, checkedTrackColor = C.Accent,
                        uncheckedThumbColor = C.SubText, uncheckedTrackColor = C.Surface, uncheckedBorderColor = C.FieldEdge,
                    ),
                )
                IconButton(onClick = { refreshes++ }, enabled = !live) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh), tint = C.SubText)
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                image?.let { Image(it, stringResource(R.string.peek_title), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                if (image == null && loading) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = C.Accent)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.peek_loading), color = Color(0xFFB0B4BB), fontSize = 13.sp)
                    }
                }
                if (image != null && loading && !live) {
                    CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp), strokeWidth = 2.dp, color = C.Accent)
                }
            }
            error?.let { Text(it, color = C.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            Text(stringResource(R.string.peek_note), color = C.SubText, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
