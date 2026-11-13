package app.fireremote.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonsAndIconsTest {
    @Test fun slotsRoundTrip() {
        val custom = Action.DEFAULTS.toMutableList().also { it[8] = Action.TOUCHPAD; it[9] = Action.HIDDEN; it[10] = Action.SEARCH }
        assertEquals(custom, Action.parse(Action.format(custom)))
        assertEquals(13, Action.DEFAULTS.size)
        assertEquals(Action.DEFAULTS, Action.parse(null))
        assertEquals(Action.DEFAULTS, Action.parse("power,back"))            // wrong count -> defaults
        assertEquals(Action.POWER, Action.parse(Action.format(Action.DEFAULTS).replace("power", "zzz"))[0]) // unknown id -> default
        assertEquals(Action.entries.size, Action.entries.map { it.id }.toSet().size)  // ids are unique
    }

    @Test fun parsesHelperOutput() {
        val png = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
        val b64 = java.util.Base64.getEncoder().encodeToString(png)
        val out = "com.discord\tb\t$b64\nbad line\nx;rm -rf\tb\t$b64\ncom.netflix.ninja\ti\t$b64\ncom.y\tq\t$b64\nFR_ICONS_END\ncom.after\tb\t$b64\n"
        val icons = Scrcpy.parseIcons(out)
        assertEquals(listOf("com.discord" to true, "com.netflix.ninja" to false), icons.map { it.first to it.second })
        assertArrayEquals(png, icons[0].third)
    }

    @Test fun iconCommandUsesBothJars() {
        assertEquals(
            "CLASSPATH=/data/local/tmp/fireremote-phone-iconsrv.jar:/data/local/tmp/fireremote-phone-icons.jar app_process / app.fireremote.tvhelper.IconDump 240",
            Scrcpy.iconCommand(),
        )
    }
}
