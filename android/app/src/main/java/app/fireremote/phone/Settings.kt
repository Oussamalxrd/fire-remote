package app.fireremote.phone

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** App settings, as Compose state so the screen updates the moment one changes. */
object UiPrefs {
    var theme by mutableIntStateOf(0)            // 0 follow phone, 1 dark, 2 light
    var scale by mutableFloatStateOf(1f)         // interface size, 0.8 .. 1.3
    var haptics by mutableStateOf(true)
    var keepScreenOn by mutableStateOf(false)
    var idleMinutes by mutableIntStateOf(10)     // 0 = stay connected
    var touchpadSpeed by mutableFloatStateOf(1f) // 0.5 .. 2
    var naturalScroll by mutableStateOf(true)
    var volumeKeys by mutableStateOf(true)
    var autoFind by mutableStateOf(true)
    var slots by mutableStateOf(Action.DEFAULTS)  // what each remote button does
    var buttonScale by mutableFloatStateOf(1f)   // remote button size, 0.85 .. 1.15
    var dpadSwipe by mutableStateOf(true)        // slide on the D-pad to move

    @Volatile private var loaded = false

    private fun prefs(c: Context) = c.getSharedPreferences("fireremote", Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(c)
        theme = p.getInt("theme", 0)
        scale = p.getFloat("scale", 1f)
        haptics = p.getBoolean("haptics", true)
        keepScreenOn = p.getBoolean("keepScreenOn", false)
        idleMinutes = p.getInt("idleMinutes", 10)
        touchpadSpeed = p.getFloat("touchpadSpeed", 1f)
        naturalScroll = p.getBoolean("naturalScroll", true)
        volumeKeys = p.getBoolean("volumeKeys", true)
        autoFind = p.getBoolean("autoFind", true)
        slots = Action.parse(p.getString("slots", null))
        buttonScale = p.getFloat("buttonScale", 1f)
        dpadSwipe = p.getBoolean("dpadSwipe", true)
    }

    fun update(c: Context, change: UiPrefs.() -> Unit) {
        change()
        prefs(c).edit()
            .putInt("theme", theme).putFloat("scale", scale).putBoolean("haptics", haptics)
            .putBoolean("keepScreenOn", keepScreenOn).putInt("idleMinutes", idleMinutes)
            .putFloat("touchpadSpeed", touchpadSpeed).putBoolean("naturalScroll", naturalScroll)
            .putBoolean("volumeKeys", volumeKeys).putBoolean("autoFind", autoFind)
            .putString("slots", Action.format(slots)).putFloat("buttonScale", buttonScale)
            .putBoolean("dpadSwipe", dpadSwipe)
            .apply()
    }
}

/** Vibration on press, if turned on in Settings. */
fun buzz(view: View, type: Int = HapticFeedbackConstants.VIRTUAL_KEY) {
    if (UiPrefs.haptics) view.performHapticFeedback(type)
}

private val IDLE_CHOICES = listOf(5, 10, 30, 0)

@Composable
fun SettingsScreen(target: String, onBack: () -> Unit, onAddress: () -> Unit) {
    val ctx = LocalContext.current
    fun set(change: UiPrefs.() -> Unit) = UiPrefs.update(ctx, change)
    var resetKey by remember { mutableStateOf(false) }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = C.Icon) }
            Text(stringResource(R.string.settings_title), color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Section(stringResource(R.string.set_appearance))
            Label(stringResource(R.string.set_theme))
            Choices(
                listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_dark), stringResource(R.string.theme_light)),
                UiPrefs.theme,
            ) { set { theme = it } }

            // Applied when the finger lifts, so the slider doesn't move under it while the screen resizes.
            var size by remember(UiPrefs.scale) { mutableFloatStateOf(UiPrefs.scale) }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.set_scale, (size * 100).roundToInt()), color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { size = 1f; set { scale = 1f } }) { Text(stringResource(R.string.reset), color = C.Accent) }
            }
            Slider(
                value = size, onValueChange = { size = it }, valueRange = 0.8f..1.3f, steps = 4,
                onValueChangeFinished = { set { scale = (size * 10).roundToInt() / 10f } },
                colors = sliderColors(), modifier = Modifier.padding(horizontal = 20.dp),
            )

            if (Build.VERSION.SDK_INT >= 33) LanguageRow()

            Section(stringResource(R.string.set_connection))
            ClickRow(stringResource(R.string.set_address), target.removeSuffix(":5555"), onAddress)
            SwitchRow(stringResource(R.string.set_autofind), stringResource(R.string.set_autofind_sub), UiPrefs.autoFind) { set { autoFind = it } }
            Label(stringResource(R.string.set_idle))
            Choices(
                IDLE_CHOICES.map { if (it == 0) stringResource(R.string.idle_always) else stringResource(R.string.idle_min, it) },
                IDLE_CHOICES.indexOf(UiPrefs.idleMinutes).coerceAtLeast(0),
            ) { set { idleMinutes = IDLE_CHOICES[it] } }
            SwitchRow(stringResource(R.string.set_volume_keys), null, UiPrefs.volumeKeys) { set { volumeKeys = it } }

            Section(stringResource(R.string.set_controls))
            SwitchRow(stringResource(R.string.set_haptics), null, UiPrefs.haptics) { set { haptics = it } }
            SwitchRow(stringResource(R.string.set_screen_on), null, UiPrefs.keepScreenOn) { set { keepScreenOn = it } }
            Label(stringResource(R.string.set_speed))
            Slider(
                value = UiPrefs.touchpadSpeed, onValueChange = { set { touchpadSpeed = it } },
                valueRange = 0.5f..2f, steps = 5, colors = sliderColors(), modifier = Modifier.padding(horizontal = 20.dp),
            )
            SwitchRow(stringResource(R.string.set_natural), stringResource(R.string.set_natural_sub), UiPrefs.naturalScroll) { set { naturalScroll = it } }
            SwitchRow(stringResource(R.string.set_dpad_swipe), stringResource(R.string.set_dpad_swipe_sub), UiPrefs.dpadSwipe) { set { dpadSwipe = it } }

            Section(stringResource(R.string.set_buttons))
            var buttons by remember(UiPrefs.buttonScale) { mutableFloatStateOf(UiPrefs.buttonScale) }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.set_button_size, (buttons * 100).roundToInt()), color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { buttons = 1f; set { buttonScale = 1f } }) { Text(stringResource(R.string.reset), color = C.Accent) }
            }
            Slider(
                value = buttons, onValueChange = { buttons = it }, valueRange = 0.85f..1.15f, steps = 5,
                onValueChangeFinished = { set { buttonScale = (buttons * 20).roundToInt() / 20f } },
                colors = sliderColors(), modifier = Modifier.padding(horizontal = 20.dp),
            )
            ButtonsEditor()

            Section(stringResource(R.string.set_about))
            Text(
                stringResource(R.string.version, version), color = C.Text, fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            ClickRow(stringResource(R.string.reset_key), stringResource(R.string.reset_key_sub)) { resetKey = true }
            Text(
                stringResource(R.string.credits), color = C.SubText, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }

    if (resetKey) {
        AlertDialog(
            onDismissRequest = { resetKey = false },
            containerColor = C.Surface,
            title = { Text(stringResource(R.string.reset_key_confirm)) },
            text = { Text(stringResource(R.string.reset_key_sub)) },
            confirmButton = {
                TextButton(onClick = {
                    resetKey = false
                    TvLink.stop(byUser = false)
                    Store.resetKeys(ctx)
                    MainActivity.connect(ctx)
                }) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = { TextButton(onClick = { resetKey = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Per-app language (Android 13+); "Phone language" follows the system. */
@Composable
private fun LanguageRow() {
    val ctx = LocalContext.current
    val lm = remember { ctx.getSystemService(LocaleManager::class.java) }
    var current by remember { mutableStateOf(lm.applicationLocales.toLanguageTags().substringBefore(',').substringBefore('-')) }
    var menu by remember { mutableStateOf(false) }
    val names = linkedMapOf(
        "" to stringResource(R.string.language_system),
        "en" to "English", "fr" to "Français", "ar" to "العربية",
        "de" to "Deutsch", "es" to "Español",
    )
    Box {
        ClickRow(stringResource(R.string.set_language), names[current] ?: current) { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            names.forEach { (tag, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    menu = false
                    current = tag
                    // The screen restarts in the new language.
                    lm.applicationLocales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
                })
            }
        }
    }
}

/**
 * A small map of the remote: tap a button to give it another action or hide it.
 * Grid cells: top corners around the D-pad, three rows of three, bottom corners.
 */
@Composable
private fun ButtonsEditor() {
    val ctx = LocalContext.current
    val layout = listOf(
        listOf(0, -1, 1),
        listOf(2, 3, 4),
        listOf(5, 6, 7),
        listOf(8, 9, 10),
        listOf(11, -2, 12),
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.set_buttons_sub), color = C.SubText, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { UiPrefs.update(ctx) { slots = Action.DEFAULTS } }) { Text(stringResource(R.string.reset), color = C.Accent) }
        }
        for (row in layout) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (slot in row) {
                    if (slot < 0) {
                        Box(Modifier.weight(1f).height(58.dp), contentAlignment = Alignment.Center) {
                            if (slot == -1) Icon(Icons.Rounded.Gamepad, null, tint = C.SubText)
                        }
                    } else {
                        SlotCell(slot, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotCell(slot: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val action = UiPrefs.slots[slot]
    val hidden = action == Action.HIDDEN
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (hidden) C.Bg else C.Surface)
                .border(1.dp, C.FieldEdge, RoundedCornerShape(14.dp))
                .clickable { menu = true }
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(actionIcon(action), null, tint = if (hidden) C.SubText else C.Icon, modifier = Modifier.size(20.dp))
            Text(stringResource(action.label), color = C.SubText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Action.entries.forEach { a ->
                DropdownMenuItem(
                    text = { Text(stringResource(a.label)) },
                    leadingIcon = { Icon(actionIcon(a), null) },
                    onClick = {
                        menu = false
                        UiPrefs.update(ctx) { slots = slots.toMutableList().also { it[slot] = a } }
                    },
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title, color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp),
    )
}

@Composable
private fun Label(text: String) {
    Text(text, color = C.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp))
}

@Composable
private fun Choices(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) C.Accent else C.Surface)
                    .clickable { onPick(i) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) C.AccentText else C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 15.sp)
            if (sub != null) Text(sub, color = C.SubText, fontSize = 12.sp)
        }
        Spacer(Modifier.padding(start = 12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = C.AccentText, checkedTrackColor = C.Accent,
                uncheckedThumbColor = C.SubText, uncheckedTrackColor = C.Surface, uncheckedBorderColor = C.FieldEdge,
            ),
        )
    }
}

@Composable
private fun ClickRow(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, color = C.Text, fontSize = 15.sp)
        Text(value, color = C.SubText, fontSize = 13.sp)
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Surface,
    activeTickColor = C.AccentText, inactiveTickColor = C.SubText,
)
