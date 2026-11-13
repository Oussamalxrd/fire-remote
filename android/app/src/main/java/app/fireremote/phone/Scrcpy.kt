package app.fireremote.phone

import java.nio.ByteBuffer

data class TvApp(val name: String, val pkg: String, val system: Boolean)

/**
 * scrcpy-server control protocol, the same one the desktop Fire Remote uses (checked against scrcpy 5.0.1):
 *   INJECT_KEYCODE = [0][action u8][keycode i32][repeat i32][metastate i32]   (big-endian)
 *   INJECT_TEXT    = [1][length u32][utf-8]                                    (max 300 bytes)
 *   SET_CLIPBOARD  = [9][sequence u64][paste u8][length u32][utf-8]
 *   START_APP      = [16][length u8][utf-8 package name]
 */
object Scrcpy {
    /** Version of the bundled scrcpy-server; the server refuses to start with any other value. */
    const val VERSION = "5.0.1"
    const val ASSET = "scrcpy-server"

    // Own file names, so the phone and the laptop never delete each other's copy.
    const val JAR = "/data/local/tmp/fireremote-phone.jar"
    const val LIST_JAR = "/data/local/tmp/fireremote-phone-list.jar"
    const val LIST_OUT = "/data/local/tmp/fireremote-phone-apps.txt"
    const val LIST_END = "FR_LIST_END"

    // App logos: our helper (assets/fireremote-icons.jar) runs next to its own copy of scrcpy-server.
    const val ICON_ASSET = "fireremote-icons.jar"
    const val ICON_SRV = "/data/local/tmp/fireremote-phone-iconsrv.jar"
    const val ICON_JAR = "/data/local/tmp/fireremote-phone-icons.jar"
    const val ICON_END = "FR_ICONS_END"

    fun iconCommand(width: Int = 240) =
        "CLASSPATH=$ICON_SRV:$ICON_JAR app_process / app.fireremote.tvhelper.IconDump $width"

    /** Helper output -> (package, is 16:9 banner, PNG bytes). Bad lines are skipped. */
    fun parseIcons(text: String): List<Triple<String, Boolean, ByteArray>> =
        text.lineSequence().takeWhile { it.trim() != ICON_END }.mapNotNull { line ->
            val parts = line.trim().split('\t')
            if (parts.size != 3 || !validPackage(parts[0]) || parts[1] !in setOf("b", "i")) return@mapNotNull null
            val png = runCatching { java.util.Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return@mapNotNull null
            Triple(parts[0], parts[1] == "b", png)
        }.toList()

    /** Regular file, rw-r--r--. */
    const val FILE_MODE = 0x81A4

    fun serverCommand(scid: String) =
        "CLASSPATH=$JAR app_process / com.genymobile.scrcpy.Server $VERSION scid=$scid log_level=warn" +
            " video=false audio=false control=true tunnel_forward=true" +
            " cleanup=false clipboard_autosync=false send_device_meta=false power_on=true"

    /** Runs list_apps in the background on the TV and writes the result to [LIST_OUT], ending with [LIST_END]. */
    fun listAppsCommand() =
        "rm -f $LIST_OUT; nohup sh -c 'CLASSPATH=$LIST_JAR app_process / com.genymobile.scrcpy.Server $VERSION" +
            " list_apps=true log_level=info cleanup=false > $LIST_OUT 2>&1; echo $LIST_END >> $LIST_OUT;" +
            " rm -f $LIST_JAR' > /dev/null 2>&1 &"

    fun keyEvent(action: Int, code: Int, repeat: Int = 0, meta: Int = 0): ByteArray =
        ByteBuffer.allocate(14).put(0).put(action.toByte()).putInt(code).putInt(repeat).putInt(meta).array()

    /** ACTION_DOWN followed by ACTION_UP. */
    fun keyPress(code: Int): ByteArray = keyEvent(0, code) + keyEvent(1, code)

    fun isPlainAscii(s: String) = s.all { it.code in 0x20..0x7E }

    /**
     * Plain ASCII is typed by the TV's virtual keyboard. Anything else (accents, Arabic, emoji) can't be,
     * so it goes on the TV clipboard and gets pasted (sequence 0 = no acknowledgement wanted).
     */
    fun textMessages(text: String): List<ByteArray> = chunks(text, 300).map { chunk ->
        val data = chunk.toByteArray(Charsets.UTF_8)
        if (isPlainAscii(chunk)) {
            ByteBuffer.allocate(5 + data.size).put(1).putInt(data.size).put(data).array()
        } else {
            ByteBuffer.allocate(14 + data.size).put(9).putLong(0).put(1).putInt(data.size).put(data).array()
        }
    }

    /** Splits [text] into pieces of at most [maxBytes] UTF-8 bytes without cutting a character in half. */
    fun chunks(text: String, maxBytes: Int): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            val nb = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + nb > maxBytes && i > start) {
                out.add(text.substring(start, i))
                start = i
                bytes = 0
            }
            bytes += nb
            i += len
        }
        if (start < text.length) out.add(text.substring(start))
        return out
    }

    private val packageName = Regex("^[A-Za-z0-9._]+$")

    fun validPackage(pkg: String) = packageName.matches(pkg)

    fun startApp(pkg: String): ByteArray? {
        val data = pkg.toByteArray(Charsets.UTF_8)
        if (data.isEmpty() || data.size > 255 || !validPackage(pkg)) return null
        return byteArrayOf(16, data.size.toByte()) + data
    }

    fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private val fullLine = Regex("^ ([*-]) (.*?)\\s{2,}(\\S+)\\s*$")
    private val nameOnly = Regex("^ ([*-]) (.+?)\\s*$")

    /**
     * Parses scrcpy-server's list_apps output: " * Name<padding> package" (system) / " - ..." (user).
     * Names of 30+ characters put the package on the next line.
     */
    fun parseAppList(text: String): List<TvApp> {
        val lines = text.replace("\r", "").split('\n')
        val start = lines.indexOfFirst { it.contains("List of apps:") }
        if (start < 0) return emptyList()
        val list = ArrayList<TvApp>()
        var i = start + 1
        while (i < lines.size) {
            val l = lines[i]
            val m = fullLine.find(l)
            if (m != null) {
                list.add(TvApp(m.groupValues[2].trim(), m.groupValues[3], m.groupValues[1] == "*"))
                i++
                continue
            }
            val n = nameOnly.find(l)
            if (n != null && i + 1 < lines.size) {
                val next = lines[i + 1].trim()
                if (next.isNotEmpty() && ' ' !in next) {
                    list.add(TvApp(n.groupValues[2].trim(), next, n.groupValues[1] == "*"))
                    i += 2
                    continue
                }
            }
            if (l.startsWith("[server]") || l.startsWith(LIST_END)) break
            i++
        }
        return list.filter { validPackage(it.pkg) }
    }
}

object TextDiff {
    /**
     * What to send so the TV's text field follows the phone's: delete [first] characters from the end,
     * then type [second]. Never splits an emoji or other surrogate pair.
     */
    fun diff(old: String, new: String): Pair<Int, String> {
        val max = minOf(old.length, new.length)
        var i = 0
        while (i < max && old[i] == new[i]) i++
        if (i > 0 && Character.isHighSurrogate(old[i - 1])) i--
        return old.codePointCount(i, old.length) to new.substring(i)
    }
}

object Address {
    private val host = Regex("^[A-Za-z0-9.-]+$")

    /** "192.168.137.50" or "192.168.137.50:5555" -> host and port, or null if it isn't an address. */
    fun parse(text: String): Pair<String, Int>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val colon = t.lastIndexOf(':')
        val h = if (colon >= 0) t.substring(0, colon) else t
        val p = if (colon >= 0) t.substring(colon + 1).toIntOrNull() ?: return null else 5555
        if (p !in 1..65535 || !host.matches(h) || h.startsWith('.') || h.endsWith('.')) return null
        return h to p
    }

    fun format(host: String, port: Int) = if (port == 5555) host else "$host:$port"
}
