package app.fireremote.phone

import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * scrcpy virtual HID devices (checked against the bundled 5.0.1 server's ControlMessageReader):
 *   UHID_CREATE  = [12][id u16][vendor u16][product u16][name len u8][name utf-8][desc len u16][report descriptor]
 *   UHID_INPUT   = [13][id u16][len u16][report]
 *   UHID_DESTROY = [14][id u16]
 */
object Uhid {
    const val MOUSE_ID = 2

    /** Standard 3-button wheel mouse. Report = [buttons][dx][dy][wheel], relative, -127..127. */
    val MOUSE_DESCRIPTOR: ByteArray = intArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, // Generic Desktop, Mouse, Collection (Application)
        0x09, 0x01, 0xA1, 0x00, //               Pointer, Collection (Physical)
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, //   Buttons 1..3
        0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x03, //   5 bits padding
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, // X, Y, Wheel
        0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06, // -127..127, relative
        0xC0, 0xC0,
    ).map { it.toByte() }.toByteArray()

    fun create(id: Int, name: String, descriptor: ByteArray): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8).let { if (it.size > 127) it.copyOf(127) else it }
        return ByteBuffer.allocate(1 + 2 + 2 + 2 + 1 + n.size + 2 + descriptor.size)
            .put(12).putShort(id.toShort()).putShort(0).putShort(0)
            .put(n.size.toByte()).put(n)
            .putShort(descriptor.size.toShort()).put(descriptor)
            .array()
    }

    fun input(id: Int, report: ByteArray): ByteArray =
        ByteBuffer.allocate(5 + report.size).put(13).putShort(id.toShort()).putShort(report.size.toShort()).put(report).array()

    fun destroy(id: Int): ByteArray = ByteBuffer.allocate(3).put(14).putShort(id.toShort()).array()

    /** Mouse reports for one update; big moves are split into steps of at most 127. */
    fun mouseReports(dx: Int, dy: Int, buttons: Int, wheel: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var x = dx
        var y = dy
        var w = wheel.coerceIn(-127, 127)
        do {
            val sx = x.coerceIn(-127, 127)
            val sy = y.coerceIn(-127, 127)
            out.add(byteArrayOf(buttons.toByte(), sx.toByte(), sy.toByte(), w.toByte()))
            x -= sx
            y -= sy
            w = 0
        } while (x != 0 || y != 0)
        return out
    }
}

/** A favorite tile: an app package, or a web address that opens in Sofa TV Browser. */
data class Fav(val name: String, val target: String) {
    val isSite get() = Site.isSite(target)
}

object Site {
    const val BROWSER = "app.sofabrowser"

    fun isSite(t: String) = t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)

    /** An http(s) address that is safe inside a single-quoted adb shell argument, or null. */
    fun clean(text: String): String? {
        var t = text.trim()
        if (t.isEmpty()) return null
        if (!t.contains("://")) t = "https://$t"
        return try {
            val uri = try {
                URI(t)
            } catch (e: URISyntaxException) {
                // Spaces and other characters a URI can't hold: let the multi-part constructor encode them.
                val u = URL(t)
                URI(u.protocol, u.userInfo, u.host, u.port, u.path, u.query, u.ref)
            }
            val scheme = uri.scheme?.lowercase()
            val host = uri.host ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return null
            uri.toASCIIString().replace("'", "%27")
        } catch (e: Exception) {
            null
        }
    }

    fun defaultName(url: String): String =
        (runCatching { URI(url).host }.getOrNull() ?: url).removePrefix("www.")

    fun openCommand(url: String) = "am start -a android.intent.action.VIEW -d '$url' -p $BROWSER"

    private val error = Regex("(?m)^\\s*Error")
    fun failed(amOutput: String) = error.containsMatchIn(amOutput)
}

/** Tile colors, same table as the desktop app. */
object Brand {
    private val brands = listOf(
        "discord" to 0x5865F2, "youtube" to 0xFF0000, "prime" to 0x00A8E1, "firebat" to 0x00A8E1,
        "avod" to 0x00A8E1, "netflix" to 0xE50914, "spotify" to 0x1DB954, "disney" to 0x113CCF,
        "twitch" to 0x9146FF, "plex" to 0xE5A00D, "kodi" to 0x17B2E7, "hulu" to 0x1CE783,
        "hbo" to 0x5822B4, "crunchyroll" to 0xF47521, "vlc" to 0xFF8800, "tubi" to 0xFA382F,
        "peacock" to 0x000000, "paramount" to 0x0064FF, "apple" to 0x000000, "facebook" to 0x1877F2,
        "twitter" to 0x1D9BF0, "crave" to 0x00AEEF, "pluto" to 0xFFD400,
    )

    /** ARGB color for a tile. */
    fun color(name: String, target: String): Int {
        val key = "$name $target".lowercase()
        brands.firstOrNull { key.contains(it.first) }?.let { return it.second or 0xFF000000.toInt() }
        var h = 17
        for (ch in target) h = h * 31 + ch.code
        return fromHue(abs(h % 360))
    }

    private fun fromHue(hue: Int): Int {
        val hh = hue / 60.0
        val c = 0.55
        val x = c * (1 - abs(hh % 2 - 1))
        val m = 0.25
        var r = 0.0
        var g = 0.0
        var b = 0.0
        when (hh.toInt()) {
            0 -> { r = c; g = x }
            1 -> { r = x; g = c }
            2 -> { g = c; b = x }
            3 -> { g = x; b = c }
            4 -> { r = x; b = c }
            else -> { r = c; b = x }
        }
        fun ch(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** True when white text would be hard to read on this color. */
    fun darkText(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b > 170
    }
}
