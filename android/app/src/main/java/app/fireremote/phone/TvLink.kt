package app.fireremote.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dadb.AdbAuthException
import dadb.AdbConnectException
import dadb.AdbConnectionClosedException
import dadb.AdbException
import dadb.AdbKeyPair
import dadb.AdbShellStream
import dadb.AdbStream
import dadb.AdbStreamOpenException
import dadb.AdbTimeoutException
import dadb.Dadb
import dadb.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.source
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The connection to the Fire TV, shared by the screen and the background service.
 *
 * - One ADB connection with this app's saved key; every TV operation runs on one thread, in order.
 * - Instant keys go through scrcpy-server's control socket (as on the laptop); plain ADB commands are the backup.
 * - A heartbeat checks the link every few seconds and right after you press something. Any failure ends the
 *   session and the loop reconnects by itself (1 s, 2 s, 4 s ... 15 s, or at once when the network changes).
 * - If the TV stays unreachable, the hotspot is scanned for the Stick in case it got a new IP.
 */
object TvLink {
    enum class Phase { Off, Connecting, Authorizing, Online, Waiting }

    data class State(
        val phase: Phase = Phase.Off,
        val target: String = "",
        val instant: Boolean = false,
        val detail: String = "",
        val retryAtMs: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short messages for the screen (not connected, Sofa missing, ...). */
    val notices: SharedFlow<String> = _notices

    fun notice(text: String) {
        _notices.tryEmit(text)
    }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    /** Set by Disconnect, so reopening the app doesn't reconnect by itself. */
    @Volatile var userStopped = false

    @Volatile var cachedApps: List<TvApp>? = null

    val isOnline get() = _state.value.phase == Phase.Online

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-io").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private var loop: Job? = null
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val check = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var session: Session? = null
    private val pending = ArrayList<Pair<Long, (Session) -> Unit>>()
    @Volatile private var lastCheck = 0L
    @Volatile private var lastOfflineNotice = 0L
    @Volatile private var rescan = false

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        UiPrefs.load(app)
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = networkChanged()
                    override fun onLost(network: Network) = networkChanged()
                },
            )
        }
    }

    fun setForeground(visible: Boolean) {
        _foreground.value = visible
        if (visible) {
            kick.trySend(Unit)
            check.trySend(Unit)
        }
    }

    fun start(host: String, port: Int) {
        userStopped = false
        val target = "$host:$port"
        if (loop?.isActive == true && _state.value.target == target) {
            kick.trySend(Unit)
            return
        }
        loop?.cancel()
        session?.kill("Switching TV")
        _state.value = State(Phase.Connecting, target)
        loop = scope.launch { connectLoop(host, port) }
    }

    fun stop(byUser: Boolean) {
        if (byUser) userStopped = true
        loop?.cancel()
        loop = null
        session?.kill("Disconnected")
        synchronized(pending) { pending.clear() }
        _state.update { it.copy(phase = Phase.Off, instant = false, detail = "", retryAtMs = 0L) }
    }

    fun retryNow() {
        kick.trySend(Unit)
    }

    private fun networkChanged() {
        rescan = true
        kick.trySend(Unit)
        check.trySend(Unit)
    }

    // ------------------------------------------------------------------ actions

    fun key(code: Int) = act { it.key(code) }
    fun keys(code: Int, times: Int) = act { s -> repeat(times) { s.key(code) } }
    fun longPress(code: Int) = act { it.shell("input keyevent --longpress $code") }
    fun text(t: String) {
        if (t.isNotEmpty()) act { it.text(t) }
    }
    fun launch(pkg: String) = act { it.startApp(pkg) }
    fun openSettings() = act { it.shell("am start -a android.settings.SETTINGS") }

    /** Sleep if the Stick is awake, wake it otherwise. */
    fun power() = act { s ->
        val awake = s.shell("dumpsys power | grep -m1 mWakefulness=").contains("Awake")
        s.shell("input keyevent " + if (awake) 223 else 224)
    }

    fun open(f: Fav) {
        if (!f.isSite) {
            launch(f.target)
            return
        }
        val url = Site.clean(f.target) ?: return
        act { s ->
            if (Site.failed(s.shell(Site.openCommand(url)))) notice(str(R.string.n_sofa_missing))
        }
    }

    private val focusedPackage = Regex("u0 ([A-Za-z0-9_.]+)/")

    /** Force-stops the app in front on the TV (never the home screen or system screens). */
    fun closeApp() = act { s ->
        val out = s.shell(
            "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'; " +
                "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 2",
        )
        val pkg = focusedPackage.find(out)?.groupValues?.get(1)
        if (pkg == null || pkg == "android" || "launcher" in pkg || "systemui" in pkg || ".settings" in pkg) {
            notice(str(R.string.n_nothing_to_close))
            return@act
        }
        s.shell("am force-stop $pkg")
        notice(str(R.string.n_closed, cachedApps?.firstOrNull { it.pkg == pkg }?.name ?: pkg))
    }

    private fun act(op: (Session) -> Unit) {
        val s = session
        if (s == null || !isOnline) {
            // Kept for a few seconds: if the link comes back quickly, the press still goes through.
            synchronized(pending) {
                pending.add(System.currentTimeMillis() to op)
                while (pending.size > 20) pending.removeAt(0)
            }
            kick.trySend(Unit)
            val now = System.currentTimeMillis()
            if (now - lastOfflineNotice > 2_500) {
                lastOfflineNotice = now
                notice(str(R.string.n_not_connected))
            }
            return
        }
        exec(s, op)
    }

    private fun exec(s: Session, op: (Session) -> Unit) {
        scope.launch(io) {
            try {
                op(s)
            } catch (e: Throwable) {
                s.kill(str(R.string.e_lost, describe(e)))
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastCheck > 1_500) {
            lastCheck = now
            check.trySend(Unit)
        }
    }

    private fun flushPending(s: Session) {
        val now = System.currentTimeMillis()
        val ops = synchronized(pending) {
            val fresh = pending.filter { now - it.first < 4_000 }.map { it.second }
            pending.clear()
            fresh
        }
        ops.forEach { exec(s, it) }
    }

    // ------------------------------------------------------------------ touchpad mouse

    private val mouseDx = AtomicInteger()
    private val mouseDy = AtomicInteger()
    private val mouseWheelSteps = AtomicInteger()
    private val mouseQueued = AtomicBoolean()
    @Volatile private var mouseQueuedAt = 0L
    @Volatile private var mouseButtons = 0
    @Volatile private var lastBasicNotice = 0L

    private fun mouseReady(): Boolean {
        val st = _state.value
        if (st.phase == Phase.Online && !st.instant) {
            val now = System.currentTimeMillis()
            if (now - lastBasicNotice > 3_000) {
                lastBasicNotice = now
                notice(str(R.string.n_touchpad_basic))
            }
            return false
        }
        return true
    }

    /** Movement is added up and sent in one go, so fast swipes don't queue hundreds of messages. */
    fun mouseMove(dx: Int, dy: Int) {
        if (!mouseReady()) return
        if (!isOnline) {
            act { }   // shows "Not connected" and reconnects
            return
        }
        mouseDx.addAndGet(dx)
        mouseDy.addAndGet(dy)
        val now = System.currentTimeMillis()
        // One send in flight at a time; the time check stops a lost send from blocking the touchpad.
        if (!mouseQueued.get() || now - mouseQueuedAt > 1_000) {
            mouseQueued.set(true)
            mouseQueuedAt = now
            act { s ->
                mouseQueued.set(false)
                s.mouse(mouseDx.getAndSet(0), mouseDy.getAndSet(0), mouseButtons, mouseWheelSteps.getAndSet(0))
            }
        }
    }

    fun mouseWheel(steps: Int) {
        if (!mouseReady()) return
        mouseWheelSteps.addAndGet(steps)
        mouseMove(0, 0)
    }

    /** Button changes are sent in order, each with the movement before it, so quick clicks are never merged. */
    fun mouseButton(down: Boolean) {
        if (!mouseReady()) return
        val b = if (down) 1 else 0
        mouseButtons = b
        act { s -> s.mouse(mouseDx.getAndSet(0), mouseDy.getAndSet(0), b, mouseWheelSteps.getAndSet(0)) }
    }

    /** Removes the TV cursor. */
    fun mouseEnd() {
        mouseButtons = 0
        session?.let { s -> if (isOnline) exec(s) { it.mouseOff() } }
    }

    // ------------------------------------------------------------------ apps, screen peek, finding the TV

    /** Asks scrcpy-server for the TV's apps (names + packages); falls back to bare package names. */
    suspend fun listApps(): List<TvApp> {
        val s = session ?: throw IOException(str(R.string.n_not_connected))
        withContext(io) {
            s.pushServer(Scrcpy.LIST_JAR)
            s.shell(Scrcpy.listAppsCommand())
        }
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            val out = withContext(io) { s.shell("cat ${Scrcpy.LIST_OUT} 2>/dev/null") }
            if (out.contains(Scrcpy.LIST_END)) {
                withContext(io) { runCatching { s.shell("rm -f ${Scrcpy.LIST_OUT}") } }
                val apps = Scrcpy.parseAppList(out)
                if (apps.isNotEmpty()) return apps
                break
            }
        }
        val pm = withContext(io) { s.shell("pm list packages -3") }
        return pm.lines().map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }.filter { Scrcpy.validPackage(it) }
            .map { TvApp(it, it, false) }
    }

    /** Runs one shell command on the TV, in order with the other operations, and returns its output. */
    suspend fun shellOut(cmd: String): String {
        val s = session ?: throw IOException(str(R.string.n_not_connected))
        return withContext(io) { s.shell(cmd) }
    }

    /**
     * Screenshots of the TV on a second connection of its own, so a 1-2 s capture never delays key presses.
     * Close it when done.
     */
    class Peeker {
        private var d: Dadb? = null

        suspend fun shot(): ByteArray = withContext(Dispatchers.IO) {
            val target = _state.value.target.ifEmpty { "${Store.host(app)}:${Store.port(app)}" }
            val (host, port) = Address.parse(target) ?: throw IOException(app.getString(R.string.n_not_connected))
            val conn = d ?: Dadb.create(host, port, Store.keys(app), 4_000, 10_000, true).also { d = it }
            try {
                val png = conn.open("exec:screencap -p").use { it.source.readByteArray() }
                if (png.size < 64) throw IOException("empty screenshot")
                png
            } catch (e: Exception) {
                runCatching { conn.close() }
                d = null
                throw e
            }
        }

        fun close() {
            runCatching { d?.close() }
            d = null
        }
    }

    /** Banners/icons of the TV's apps, on a separate connection (the helper can take several seconds). */
    suspend fun fetchIcons(): List<Triple<String, Boolean, ByteArray>> = withContext(Dispatchers.IO) {
        val target = _state.value.target.ifEmpty { "${Store.host(app)}:${Store.port(app)}" }
        val (host, port) = Address.parse(target) ?: throw IOException(str(R.string.n_not_connected))
        Dadb.create(host, port, Store.keys(app), 4_000, 30_000, true).use { d ->
            for ((asset, path) in listOf(Scrcpy.ASSET to Scrcpy.ICON_SRV, Scrcpy.ICON_ASSET to Scrcpy.ICON_JAR)) {
                app.assets.open(asset).source().use { src ->
                    val r = d.push(src, path, Scrcpy.FILE_MODE, System.currentTimeMillis())
                    if (r is SyncResult.Failure) throw IOException(r.reason)
                }
            }
            val text = try {
                d.open("exec:" + Scrcpy.iconCommand()).use { it.source.readUtf8() }
            } finally {
                runCatching { d.shell("rm -f ${Scrcpy.ICON_SRV} ${Scrcpy.ICON_JAR}") }
            }
            Scrcpy.parseIcons(text)
        }
    }

    /**
     * Looks for the Stick on [near]'s /24 and on the Windows hotspot range (192.168.137.x): open port 5555,
     * then an ADB answer from an Amazon device that accepts this phone's key. Only the laptop hotspot
     * ranges are scanned, never the campus network.
     */
    suspend fun findTv(port: Int, near: String?, skip: String?): String? = coroutineScope {
        val subnets = LinkedHashSet<String>()
        near?.substringBeforeLast('.', "")?.takeIf { it.count { c -> c == '.' } == 2 }?.let { subnets.add(it) }
        subnets.add("192.168.137")
        val keys = withContext(Dispatchers.IO) { Store.keys(app) }
        for (sn in subnets) {
            val open = (1..254).map { "$sn.$it" }.filter { it != skip }.chunked(64).flatMap { chunk ->
                chunk.map { h -> async(Dispatchers.IO) { if (portOpen(h, port)) h else null } }.awaitAll().filterNotNull()
            }
            for (h in open) {
                if (withContext(Dispatchers.IO) { isFireTv(h, port, keys) }) return@coroutineScope h
            }
        }
        null
    }

    private fun portOpen(host: String, port: Int) = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 350) }
        true
    } catch (e: Exception) {
        false
    }

    private fun isFireTv(host: String, port: Int, keys: AdbKeyPair) = try {
        Dadb.create(host, port, keys, 2_000, 5_000, false).use {
            it.shell("getprop ro.product.manufacturer").output.contains("Amazon", ignoreCase = true)
        }
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ connection loop

    private suspend fun connectLoop(startHost: String, port: Int) {
        var host = startHost
        var target = "$host:$port"
        var backoff = 1_000L
        var reason = ""
        var unreachable = 0
        var searched = false
        while (currentCoroutineContext().isActive) {
            if (rescan) {
                rescan = false
                searched = false
            }
            _state.value = State(Phase.Connecting, target, detail = reason)
            val t = target
            val slow = scope.launch {
                delay(2_500)
                _state.update { if (it.phase == Phase.Connecting && it.target == t) it.copy(phase = Phase.Authorizing) else it }
            }
            var opened: Session? = null
            try {
                val h = host
                withContext(io) { opened = openSession(h, port, t) }
            } catch (e: CancellationException) {
                opened?.let { s -> scope.launch(io) { s.close() } }
                throw e
            } catch (e: Throwable) {
                reason = describe(e)
                if (isUnreachable(e)) unreachable++ else unreachable = 0
            } finally {
                slow.cancel()
            }
            val s = opened
            if (s != null) {
                session = s
                backoff = 1_000L
                unreachable = 0
                searched = false
                _state.value = State(Phase.Online, target, s.instant, s.note)
                flushPending(s)
                val beat = scope.launch { heartbeat(s) }
                try {
                    reason = s.dead.await()
                } finally {
                    beat.cancel()
                    if (session === s) session = null
                    withContext(NonCancellable + io) { s.close() }
                }
            }
            if (UiPrefs.autoFind && unreachable >= 2 && !searched) {
                searched = true
                _state.value = State(Phase.Connecting, target, detail = str(R.string.finding_tv))
                val found = runCatching { findTv(port, host, host) }.getOrNull()
                if (found != null) {
                    host = found
                    target = "$host:$port"
                    Store.setTarget(app, host, port)
                    Store.setAuthorized(app, target, true)
                    notice(str(R.string.found_tv, host))
                    unreachable = 0
                    backoff = 1_000L
                    reason = ""
                    continue
                }
            }
            _state.value = State(Phase.Waiting, target, detail = reason, retryAtMs = System.currentTimeMillis() + backoff)
            withTimeoutOrNull(backoff) { kick.receive() }
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }

    private suspend fun heartbeat(s: Session) {
        while (true) {
            withTimeoutOrNull(if (_foreground.value) 4_000L else 15_000L) { check.receive() }
            try {
                withContext(io) { s.ping() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                s.kill(str(R.string.e_lost, describe(e)))
                return
            }
        }
    }

    private fun openSession(host: String, port: Int, target: String): Session {
        val keys = Store.keys(app)
        val first = !Store.isAuthorized(app, target)
        var d = connect(host, port, keys, if (first) 90_000 else 5_000, target)
        if (first) {
            // Accepted: reconnect with the short timeout used for normal operation.
            Store.setAuthorized(app, target, true)
            runCatching { d.close() }
            d = connect(host, port, keys, 5_000, target)
        }
        val s = Session(d, app)
        try {
            s.startInstant()
            if (!s.instant) s.ping()   // basic mode: make sure the connection itself still works
        } catch (e: Throwable) {
            s.close()
            throw e
        }
        return s
    }

    private fun connect(host: String, port: Int, keys: AdbKeyPair, readTimeoutMs: Int, target: String): Dadb {
        val d = Dadb.create(host, port, keys, 4_000, readTimeoutMs, true)
        try {
            val r = d.shell("echo fr")
            if (!r.output.contains("fr")) throw IOException("The TV gave no answer")
        } catch (e: Throwable) {
            runCatching { d.close() }
            // A handshake that times out may be a new "Allow USB debugging?" prompt: wait longer next time.
            if (e.chain().any { it is SocketTimeoutException } || e is AdbAuthException) Store.setAuthorized(app, target, false)
            throw e
        }
        return d
    }

    private fun Throwable.chain() = generateSequence(this) { it.cause }

    private fun isUnreachable(e: Throwable) = e is AdbConnectException && e.chain().any {
        it is java.net.ConnectException || it is java.net.NoRouteToHostException || it is SocketTimeoutException
    }

    fun describe(e: Throwable): String = when {
        e is AdbAuthException -> str(R.string.e_refused)
        e is AdbConnectException && e.chain().any { it is java.net.ConnectException || it is java.net.NoRouteToHostException } ->
            str(R.string.e_unreachable)
        e is AdbConnectException && e.chain().any { it is SocketTimeoutException } -> str(R.string.e_no_answer)
        e is AdbTimeoutException -> str(R.string.e_stopped)
        e is AdbConnectionClosedException -> str(R.string.e_dropped)
        else -> e.message ?: e.javaClass.simpleName
    }

    // ------------------------------------------------------------------ one connected session

    class Session(private val d: Dadb, private val ctx: Context) {
        val dead = CompletableDeferred<String>()
        var instant = false
            private set
        var note = ""
            private set
        private var control: AdbStream? = null
        private var server: AdbShellStream? = null
        private var mouseCreated = false

        fun kill(reason: String) {
            dead.complete(reason)
        }

        fun shell(cmd: String): String = d.shell(cmd).allOutput

        fun ping() {
            if (!d.shell("echo fr").output.contains("fr")) throw IOException("No answer from the TV")
        }

        fun pushServer(path: String) {
            ctx.assets.open(Scrcpy.ASSET).source().use { src ->
                val r = d.push(src, path, Scrcpy.FILE_MODE, System.currentTimeMillis())
                if (r is SyncResult.Failure) throw IOException("Couldn't copy scrcpy-server to the TV: ${r.reason}")
            }
        }

        /** Starts scrcpy-server for instant keys; on failure the session stays in basic mode. */
        fun startInstant() {
            try {
                pushServer(Scrcpy.JAR)
                val scid = "%08x".format(Random.nextInt(1, Int.MAX_VALUE))
                val sh = d.openShell(Scrcpy.serverCommand(scid))
                server = sh
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val st = d.open("localabstract:scrcpy_$scid")
                        // The server writes one 0 byte once it has accepted us.
                        if (st.source.readByte().toInt() == 0) {
                            control = st
                            break
                        }
                        st.close()
                    } catch (e: AdbStreamOpenException) {
                        // Not listening yet.
                    } catch (e: EOFException) {
                        // Closed right away: try again.
                    }
                    Thread.sleep(150)
                }
                // The running server keeps the file open, so it can be deleted now (scrcpy does the same).
                runCatching { d.shell("rm -f ${Scrcpy.JAR}") }
                if (control == null) {
                    runCatching { sh.close() }
                    server = null
                    throw IOException("scrcpy-server didn't answer")
                }
                instant = true
            } catch (e: AdbException) {
                if (e is AdbStreamOpenException || e is AdbTimeoutException) {
                    note = ctx.getString(R.string.instant_off, e.message ?: "")
                } else {
                    throw e
                }
            } catch (e: IOException) {
                note = ctx.getString(R.string.instant_off, e.message ?: "")
            }
        }

        fun key(code: Int) {
            val c = control
            if (c != null) {
                c.sink.write(Scrcpy.keyPress(code)).flush()
            } else {
                d.shell("input keyevent $code")
            }
        }

        fun text(t: String) {
            val c = control
            if (c != null) {
                for (m in Scrcpy.textMessages(t)) c.sink.write(m)
                c.sink.flush()
            } else if (Scrcpy.isPlainAscii(t)) {
                d.shell("input text " + Scrcpy.shellQuote(t.replace(" ", "%s")))
            }
        }

        fun startApp(pkg: String) {
            val c = control
            val msg = Scrcpy.startApp(pkg) ?: return
            if (c != null) {
                c.sink.write(msg).flush()
            } else {
                d.shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
            }
        }

        /** Sends to scrcpy-server's virtual HID mouse, creating it first (the TV then shows a cursor). */
        fun mouse(dx: Int, dy: Int, buttons: Int, wheel: Int) {
            val c = control ?: return
            if (!mouseCreated) {
                c.sink.write(Uhid.create(Uhid.MOUSE_ID, "Fire Remote touchpad", Uhid.MOUSE_DESCRIPTOR))
                mouseCreated = true
            }
            for (r in Uhid.mouseReports(dx, dy, buttons, wheel)) c.sink.write(Uhid.input(Uhid.MOUSE_ID, r))
            c.sink.flush()
        }

        fun mouseOff() {
            val c = control ?: return
            if (!mouseCreated) return
            c.sink.write(Uhid.destroy(Uhid.MOUSE_ID)).flush()
            mouseCreated = false
        }

        fun close() {
            runCatching { control?.close() }
            runCatching { server?.close() }
            runCatching { d.close() }
        }
    }
}
