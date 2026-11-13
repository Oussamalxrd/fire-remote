package app.fireremote.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtrasTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun uhidCreateLayout() {
        val m = Uhid.create(2, "ab", byteArrayOf(0x05, 0x01))
        // type, id, vendor, product, name len + name, desc len + desc
        assertEquals("0c" + "0002" + "0000" + "0000" + "02" + "6162" + "0002" + "0501", hex(m))
    }

    @Test fun uhidInputAndDestroy() {
        assertEquals("0d" + "0002" + "0004" + "01fe0300", hex(Uhid.input(2, byteArrayOf(1, -2, 3, 0))))
        assertEquals("0e0002", hex(Uhid.destroy(2)))
    }

    @Test fun mouseDescriptorIsBalanced() {
        val d = Uhid.MOUSE_DESCRIPTOR.map { it.toInt() and 0xFF }
        assertEquals(d.count { it == 0xA1 }, d.count { it == 0xC0 })
        assertEquals(52, d.size)
    }

    @Test fun bigMovesAreSplit() {
        val r = Uhid.mouseReports(300, -10, 1, -1)
        assertEquals(3, r.size)
        assertEquals("017ff6ff", hex(r[0])) // 127, -10, wheel -1
        assertEquals("017f0000", hex(r[1]))
        assertEquals("012e0000", hex(r[2])) // 46 left
        assertEquals(listOf("01000000"), Uhid.mouseReports(0, 0, 1, 0).map(::hex)) // click state only
    }

    @Test fun sites() {
        assertEquals("https://youtube.com", Site.clean("youtube.com"))
        assertEquals("https://www.twitch.tv/x?a=1&b=2", Site.clean("  https://www.twitch.tv/x?a=1&b=2  "))
        assertEquals("http://example.org/a%20b", Site.clean("http://example.org/a b"))
        assertEquals("https://x.com/a%20b", Site.clean("https://x.com/a%20b")) // already encoded: kept
        assertEquals("https://x.com/it%27s", Site.clean("https://x.com/it's"))
        val hostile = Site.clean("https://x.com/\"';reboot;'`id`\$(id)")!!
        assertFalse(hostile.contains('\'') || hostile.contains('"') || hostile.contains('`') || hostile.contains('\\'))
        listOf("ftp://x.com", "javascript:alert(1)", "file:///etc/passwd", "youtube", "", "  ", "intent://x.com/#Intent;end")
            .forEach { assertNull(it, Site.clean(it)) }
        assertEquals("twitch.tv", Site.defaultName("https://www.twitch.tv/x"))
        assertTrue(Site.failed("Starting: Intent\nError: Activity not started, unable to resolve Intent"))
        assertFalse(Site.failed("Starting: Intent { dat=https://x.com/Error }"))
    }

    @Test fun brandColors() {
        assertEquals(0xFF5865F2.toInt(), Brand.color("Discord", "com.discord"))
        assertEquals(0xFFFF0000.toInt(), Brand.color("YouTube", "https://youtube.com"))
        assertTrue(Brand.darkText(Brand.color("Pluto TV", "tv.pluto.android")))
        assertFalse(Brand.darkText(Brand.color("Discord", "com.discord")))
        val other = Brand.color("Some App", "com.some.app")
        assertEquals(0xFF, (other ushr 24) and 0xFF)
    }
}
