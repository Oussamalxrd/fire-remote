package app.fireremote.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrcpyTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun keyPressMatchesDesktopBytes() {
        // Desktop FastChannel.Key(23): DOWN then UP, 14 bytes each, big-endian.
        assertEquals(
            "0000" + "00000017" + "00000000" + "00000000" + "0001" + "00000017" + "00000000" + "00000000",
            hex(Scrcpy.keyPress(23)),
        )
    }

    @Test fun asciiTextIsInjected() {
        val m = Scrcpy.textMessages("hi you").single()
        assertEquals("01" + "00000006" + hex("hi you".toByteArray()), hex(m))
    }

    @Test fun otherTextIsPasted() {
        val t = "café 😀"
        val data = t.toByteArray(Charsets.UTF_8)
        val m = Scrcpy.textMessages(t).single()
        assertEquals("09" + "0000000000000000" + "01" + "%08x".format(data.size) + hex(data), hex(m))
    }

    @Test fun chunksNeverSplitCharacters() {
        val t = "😀".repeat(100) // 400 bytes
        val parts = Scrcpy.chunks(t, 300)
        assertEquals(2, parts.size)
        assertEquals(t, parts.joinToString(""))
        assertTrue(parts.all { it.toByteArray().size <= 300 })
        assertEquals(listOf("ab", "c"), Scrcpy.chunks("abc", 2))
    }

    @Test fun startApp() {
        assertArrayEquals(byteArrayOf(16, 3) + "a.b".toByteArray(), Scrcpy.startApp("a.b"))
        assertNull(Scrcpy.startApp("x; reboot"))
        assertNull(Scrcpy.startApp(""))
    }

    @Test fun serverCommandMatchesDesktopOptions() {
        assertEquals(
            "CLASSPATH=/data/local/tmp/fireremote-phone.jar app_process / com.genymobile.scrcpy.Server 5.0.1 scid=0000abcd" +
                " log_level=warn video=false audio=false control=true tunnel_forward=true cleanup=false" +
                " clipboard_autosync=false send_device_meta=false power_on=true",
            Scrcpy.serverCommand("0000abcd"),
        )
    }

    @Test fun parsesAppList() {
        val out = listOf(
            "[server] INFO: List of apps:",
            " * Amazon Music              com.amazon.bueller.music",
            " - Discord                   com.discord",
            " - A really long application name that wraps",
            "   com.example.longname",
            " - YouTube                   com.amazon.firetv.youtube",
            "FR_LIST_END",
        ).joinToString("\n")
        val apps = Scrcpy.parseAppList(out)
        assertEquals(listOf("com.amazon.bueller.music", "com.discord", "com.example.longname", "com.amazon.firetv.youtube"), apps.map { it.pkg })
        assertEquals("Discord", apps[1].name)
        assertTrue(apps[0].system)
        assertTrue(!apps[1].system)
    }

    @Test fun textDiff() {
        assertEquals(0 to "c", TextDiff.diff("ab", "abc"))
        assertEquals(1 to "", TextDiff.diff("abc", "ab"))
        assertEquals(2 to "ere ", TextDiff.diff("Thw ", "There "))  // autocorrect: only the changed tail is resent
        assertEquals(1 to "😃", TextDiff.diff("a😀", "a😃")) // emoji swap, one delete
        assertEquals(0 to "hello world", TextDiff.diff("", "hello world")) // voice typing
    }

    @Test fun addresses() {
        assertEquals("192.168.137.50" to 5555, Address.parse(" 192.168.137.50 "))
        assertEquals("192.168.137.50" to 5556, Address.parse("192.168.137.50:5556"))
        assertNull(Address.parse("192.168.137.50:0"))
        assertNull(Address.parse("http://x"))
        assertNull(Address.parse(""))
        assertEquals("192.168.137.50", Address.format("192.168.137.50", 5555))
    }
}
