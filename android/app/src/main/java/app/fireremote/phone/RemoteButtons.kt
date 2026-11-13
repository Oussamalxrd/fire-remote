package app.fireremote.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * FireKeys (com.oussama.firekeys on the Stick) remaps the Fire TV remote's app buttons, as in the laptop app's
 * More > Remote buttons. It is driven by broadcasts: GET answers with its state as JSON in the broadcast data,
 * SET changes one button (no target = back to normal).
 */
object FireKeys {
    const val PKG = "com.oussama.firekeys"
    private const val RECEIVER = "$PKG/.ConfigReceiver"
    const val LAST_APP = "firekeys:lastapp"
    const val SLEEP = "firekeys:sleep"
    const val ADMIN_COMMAND = "adb shell dpm set-active-admin $PKG/.AdminReceiver"

    /** Button ids in screen order. Only TV can be held: the app buttons send down+up at once. */
    val BUTTONS = listOf("Netflix", "PrimeVideo", "YouTube", "Disney", "TV", "TV.long")

    enum class Kind { NORMAL, LAST_APP, SLEEP, LINK, APP }

    class State(
        val map: Map<String, String>,
        val service: String?,
        val source: String?,
        val readLogs: Boolean,
        val overlay: Boolean,
        val admin: Boolean,
        val lastAction: String?,
    ) {
        val running get() = service == "running"
        val permissionsOk get() = readLogs && overlay
        val sleepNeedsAdmin get() = !admin && map.containsValue(SLEEP)
    }

    fun getCommand() = "am broadcast -n $RECEIVER -a $PKG.GET"

    fun setCommand(button: String, target: String?): String {
        require(button in BUTTONS) { "unknown button $button" }
        val base = "am broadcast -n $RECEIVER -a $PKG.SET --es button $button"
        return if (target.isNullOrEmpty()) base else base + " --es target " + Scrcpy.shellQuote(target)
    }

    fun kind(target: String?): Kind = when {
        target.isNullOrEmpty() -> Kind.NORMAL
        target == LAST_APP -> Kind.LAST_APP
        target == SLEEP -> Kind.SLEEP
        Site.isSite(target) -> Kind.LINK
        else -> Kind.APP
    }

    /** The package of an app target ("pkg" or "pkg/.Activity"). */
    fun packageOf(target: String) = target.substringBefore('/')

    /** A link typed for a button: an https address that is safe for the shell, or null. */
    fun link(text: String): String? = Site.clean(text)?.takeIf { it.startsWith("https://", ignoreCase = true) }

    /** True if FireKeys now holds [want] for the button (null = normal). */
    fun holds(s: State, button: String, want: String?): Boolean {
        val have = s.map[button]
        if (want.isNullOrEmpty()) return have.isNullOrEmpty()
        return have != null && have.trimEnd('/').equals(want.trimEnd('/'), ignoreCase = true)
    }

    // Reply:  Broadcast completed: result=-1, data="{"map":{"YouTube":"com.x",...},"readLogs":true,"service":"running",...}"
    private val mapBlock = Regex("\"map\":\\{(.*?)\\}")
    private val pair = Regex("\"([^\"]+)\":\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val escape = Regex("\\\\(u[0-9a-fA-F]{4}|.)")

    /** FireKeys' state, or null if it didn't answer (not installed). */
    fun parse(reply: String): State? {
        val block = mapBlock.find(reply) ?: return null
        val map = pair.findAll(block.groupValues[1]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        return State(
            map = map,
            service = field(reply, "service"),
            source = field(reply, "source"),
            readLogs = field(reply, "readLogs") == "true",
            overlay = field(reply, "overlay") == "true",
            admin = field(reply, "admin") == "true",
            lastAction = field(reply, "lastAction")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun field(reply: String, name: String): String? {
        val m = Regex("\"" + Regex.escape(name) + "\":(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,}]*))").find(reply) ?: return null
        return if (m.groups[1] != null) unescape(m.groupValues[1]) else m.groupValues[2].trim()
    }

    fun unescape(s: String): String = escape.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.length == 5 -> e.substring(1).toInt(16).toChar().toString()
            e == "n" -> "\n"
            e == "t" -> "\t"
            else -> e
        }
    }
}

// ------------------------------------------------------------------ screen

@Composable
private fun buttonLabel(id: String) = when (id) {
    "PrimeVideo" -> "Prime Video"
    "Disney" -> "Disney+"
    "TV.long" -> stringResource(R.string.rb_tv_hold)
    else -> id
}

private fun appName(apps: List<TvApp>?, pkg: String) = apps?.firstOrNull { it.pkg == pkg }?.name

/** More > Remote buttons: what each of the Fire TV remote's app buttons does, through FireKeys. */
@Composable
fun RemoteButtonsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val link by TvLink.state.collectAsStateWithLifecycle()
    val online = link.phase == TvLink.Phase.Online
    var fk by remember { mutableStateOf<FireKeys.State?>(null) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) }   // text, ok
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    var linkFor by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf(TvLink.cachedApps) }
    var appsLoading by remember { mutableStateOf(false) }
    var appsError by remember { mutableStateOf<String?>(null) }

    suspend fun readState(): FireKeys.State? {
        val st = FireKeys.parse(TvLink.shellOut(FireKeys.getCommand()))
        fk = st
        missing = st == null
        return st
    }

    fun fail(e: Exception) {
        if (e is CancellationException) throw e
        error = ctx.getString(R.string.rb_error, TvLink.describe(e))
    }

    fun reload() {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            note = null
            try {
                readState()
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = false
            }
        }
    }

    /** SET, then GET to show what FireKeys really holds now. */
    fun save(button: String, target: String?) {
        if (busy) return
        scope.launch {
            busy = true
            saving = button
            error = null
            note = null
            try {
                TvLink.shellOut(FireKeys.setCommand(button, target))
                val st = readState()
                note = if (st != null && FireKeys.holds(st, button, target)) {
                    ctx.getString(R.string.rb_saved) to true
                } else {
                    ctx.getString(R.string.rb_not_saved) to false
                }
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = false
                saving = null
            }
        }
    }

    fun loadApps() {
        if (appsLoading) return
        scope.launch {
            appsLoading = true
            appsError = null
            try {
                val list = TvLink.listApps().sortedWith(compareBy<TvApp> { it.system }.thenBy { it.name.lowercase() })
                TvLink.cachedApps = list
                apps = list
                if (!AppIcons.refreshing && (!AppIcons.triedThisSession || list.any { AppIcons.image(it.pkg) == null })) {
                    launch { runCatching { AppIcons.refresh(ctx) } }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                appsError = ctx.getString(R.string.apps_error, TvLink.describe(e))
            } finally {
                appsLoading = false
            }
        }
    }

    LaunchedEffect(online) {
        if (online) {
            reload()
            if (apps == null) loadApps()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = C.Icon) }
            Text(
                stringResource(R.string.rb_title), color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.Accent)
            IconButton(onClick = { reload() }, enabled = online && !busy) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh), tint = C.SubText)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            StatusBlock(online, busy, fk, missing, error, note)
            Text(
                stringResource(R.string.rb_hint), color = C.SubText, fontSize = 13.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            )
            val st = fk
            for (id in FireKeys.BUTTONS) {
                ButtonRow(
                    label = buttonLabel(id),
                    target = st?.map?.get(id),
                    apps = apps,
                    saving = saving == id,
                    enabled = online && st != null && !busy,
                    onClick = { picking = id },
                )
            }
            st?.lastAction?.let {
                Text(
                    stringResource(R.string.rb_last, it), color = C.SubText, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
                )
            }
        }
    }

    picking?.let { id ->
        TargetSheet(
            title = buttonLabel(id),
            current = fk?.map?.get(id),
            admin = fk?.admin == true,
            apps = apps,
            appsLoading = appsLoading,
            appsError = appsError,
            onRefreshApps = { loadApps() },
            onPick = { target ->
                picking = null
                save(id, target)
            },
            onLink = {
                picking = null
                linkFor = id
            },
            onDismiss = { picking = null },
        )
    }
    linkFor?.let { id ->
        val current = fk?.map?.get(id)
        LinkDialog(
            initial = if (FireKeys.kind(current) == FireKeys.Kind.LINK) current!! else "",
            onSet = { url ->
                linkFor = null
                save(id, url)
            },
            onDismiss = { linkFor = null },
        )
    }
}

/** FireKeys running or missing, missing permissions, Sleep without device admin, and the last save. */
@Composable
private fun StatusBlock(
    online: Boolean, busy: Boolean, fk: FireKeys.State?, missing: Boolean, error: String?, note: Pair<String, Boolean>?,
) {
    val (dot, text) = when {
        !online -> C.Gray to stringResource(R.string.n_not_connected)
        error != null -> C.Red to error
        missing -> C.Amber to stringResource(R.string.rb_missing)
        fk == null -> C.Gray to stringResource(R.string.rb_reading)
        fk.running -> C.Green to stringResource(R.string.rb_running)
        else -> C.Amber to stringResource(R.string.rb_not_running, fk.service ?: "?")
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(dot, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(text, color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
        if (online && error == null && fk != null) {
            fk.source?.takeIf { it.isNotEmpty() && fk.running }?.let {
                Text(stringResource(R.string.rb_source, it), color = C.SubText, fontSize = 12.sp, modifier = Modifier.padding(start = 19.dp))
            }
            if (!fk.permissionsOk) {
                Text(stringResource(R.string.rb_perm), color = C.Amber, fontSize = 12.sp, modifier = Modifier.padding(start = 19.dp, top = 4.dp))
            }
            if (fk.sleepNeedsAdmin) {
                Text(stringResource(R.string.rb_sleep_admin), color = C.Amber, fontSize = 12.sp, modifier = Modifier.padding(start = 19.dp, top = 4.dp))
                Text(
                    FireKeys.ADMIN_COMMAND, color = C.SubText, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 19.dp, top = 2.dp),
                )
            }
        }
        if (note != null && !busy) {
            Text(
                note.first, color = if (note.second) C.Green else C.Amber, fontSize = 12.sp,
                modifier = Modifier.padding(start = 19.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun ButtonRow(label: String, target: String?, apps: List<TvApp>?, saving: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TargetBadge(target, apps)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled || saving) C.Text else C.SubText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(describe(target, apps), color = C.SubText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.Accent)
    }
}

@Composable
private fun describe(target: String?, apps: List<TvApp>?): String = when (FireKeys.kind(target)) {
    FireKeys.Kind.NORMAL -> stringResource(R.string.rb_normal)
    FireKeys.Kind.LAST_APP -> stringResource(R.string.rb_last_app)
    FireKeys.Kind.SLEEP -> stringResource(R.string.rb_sleep)
    FireKeys.Kind.LINK -> target!!.removePrefix("https://")
    FireKeys.Kind.APP -> appName(apps, target!!)?.takeIf { '/' !in target } ?: target
}

@Composable
private fun TargetBadge(target: String?, apps: List<TvApp>?) {
    val icon = when (FireKeys.kind(target)) {
        FireKeys.Kind.NORMAL -> Icons.Rounded.SettingsBackupRestore
        FireKeys.Kind.LAST_APP -> Icons.Rounded.SwapHoriz
        FireKeys.Kind.SLEEP -> Icons.Rounded.Bedtime
        FireKeys.Kind.LINK -> Icons.Rounded.Link
        FireKeys.Kind.APP -> null
    }
    if (icon != null) IconBadge(icon) else {
        val pkg = FireKeys.packageOf(target!!)
        AppLogo(pkg, appName(apps, pkg) ?: pkg, Modifier.size(64.dp, 36.dp))
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Box(Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(8.dp)).background(C.Surface), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = C.Icon, modifier = Modifier.size(20.dp))
    }
}

// ------------------------------------------------------------------ picker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSheet(
    title: String, current: String?, admin: Boolean, apps: List<TvApp>?, appsLoading: Boolean, appsError: String?,
    onRefreshApps: () -> Unit, onPick: (String?) -> Unit, onLink: () -> Unit, onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val kind = FireKeys.kind(current)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.Menu,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (appsLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.Accent)
                IconButton(onClick = onRefreshApps) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh), tint = C.SubText) }
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
            val q = query.trim().lowercase()
            val shown = apps.orEmpty().filter { it.pkg != FireKeys.PKG }
                .filter { q.isEmpty() || q in it.name.lowercase() || q in it.pkg.lowercase() }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (q.isEmpty()) {
                    item {
                        Choice({ IconBadge(Icons.Rounded.SettingsBackupRestore) }, stringResource(R.string.rb_normal),
                            stringResource(R.string.rb_normal_sub), kind == FireKeys.Kind.NORMAL) { onPick(null) }
                    }
                    item {
                        Choice({ IconBadge(Icons.Rounded.SwapHoriz) }, stringResource(R.string.rb_last_app),
                            stringResource(R.string.rb_last_app_sub), kind == FireKeys.Kind.LAST_APP) { onPick(FireKeys.LAST_APP) }
                    }
                    item {
                        Choice({ IconBadge(Icons.Rounded.Bedtime) }, stringResource(R.string.rb_sleep),
                            stringResource(if (admin) R.string.rb_sleep_sub else R.string.rb_sleep_no_admin),
                            kind == FireKeys.Kind.SLEEP, warn = !admin) { onPick(FireKeys.SLEEP) }
                    }
                    item {
                        Choice({ IconBadge(Icons.Rounded.Link) }, stringResource(R.string.rb_link),
                            if (kind == FireKeys.Kind.LINK) current!!.removePrefix("https://") else stringResource(R.string.rb_link_sub),
                            kind == FireKeys.Kind.LINK, onClick = onLink)
                    }
                    item {
                        Text(
                            stringResource(R.string.rb_apps), color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                }
                when {
                    apps == null && appsError != null -> item { Text(appsError, color = C.Red, fontSize = 14.sp, modifier = Modifier.padding(6.dp)) }
                    apps == null -> item { Text(stringResource(R.string.apps_loading), color = C.SubText, fontSize = 14.sp, modifier = Modifier.padding(6.dp)) }
                    else -> items(shown, key = { it.pkg }) { a ->
                        Choice(
                            { AppLogo(a.pkg, a.name, Modifier.size(64.dp, 36.dp)) }, a.name,
                            if (a.system) stringResource(R.string.app_system, a.pkg) else a.pkg,
                            current == a.pkg,
                        ) { onPick(a.pkg) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(badge: @Composable () -> Unit, title: String, sub: String, selected: Boolean, warn: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badge()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = if (warn) C.Amber else C.SubText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = C.Accent, modifier = Modifier.padding(start = 8.dp).size(22.dp))
    }
}

@Composable
private fun LinkDialog(initial: String, onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var bad by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(stringResource(R.string.rb_link_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; bad = false },
                    singleLine = true,
                    isError = bad,
                    label = { Text(stringResource(R.string.site_address)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (bad) R.string.rb_link_bad else R.string.rb_link_help),
                    color = if (bad) C.Red else C.SubText, fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val url = FireKeys.link(text)
                if (url == null) bad = true else onSet(url)
            }) { Text(stringResource(R.string.rb_link_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
