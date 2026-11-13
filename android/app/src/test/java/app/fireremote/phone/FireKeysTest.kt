package app.fireremote.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FireKeysTest {
    // Real GET reply from FireKeys 0.2 on the Stick (Nov 2026), plus a link with escaped slashes.
    private val reply = "Broadcasting: Intent { act=com.oussama.firekeys.GET flg=0x400000 cmp=com.oussama.firekeys/.ConfigReceiver }\n" +
        "Broadcast completed: result=-1, data=\"{\"map\":{\"Disney\":\"app.zap.tv\",\"Netflix\":\"https:\\/\\/www.youtube.com\\/watch?v=1&t=2\"," +
        "\"PrimeVideo\":\"com.stremio.one\",\"TV\":\"firekeys:lastapp\",\"TV.long\":\"firekeys:sleep\",\"YouTube\":\"org.smarttube.stable\"}," +
        "\"readLogs\":true,\"overlay\":true,\"admin\":false,\"recent\":\"com.a,com.b\",\"service\":\"running\"," +
        "\"source\":\"system+vitals+events\",\"lastButton\":\"00:46:21  PrimeVideo\"," +
        "\"lastAction\":\"00:46:21  PrimeVideo -> com.stremio.one  (back on top of com.amazon.firebat x1)\"}\"\n"

    @Test fun parsesGetReply() {
        val s = FireKeys.parse(reply)!!
        assertEquals("app.zap.tv", s.map["Disney"])
        assertEquals("https://www.youtube.com/watch?v=1&t=2", s.map["Netflix"])
        assertEquals(FireKeys.SLEEP, s.map["TV.long"])
        assertEquals(6, s.map.size)
        assertTrue(s.running)
        assertTrue(s.permissionsOk)
        assertFalse(s.admin)
        assertTrue(s.sleepNeedsAdmin)
        assertEquals("system+vitals+events", s.source)
        assertEquals("00:46:21 PrimeVideo -> com.stremio.one (back on top of com.amazon.firebat x1)", s.lastAction)
    }

    @Test fun noAnswerMeansMissing() {
        assertNull(FireKeys.parse("Broadcasting: Intent { act=com.oussama.firekeys.GET }\nBroadcast completed: result=0\n"))
        assertNull(FireKeys.parse(""))
    }

    @Test fun setCommands() {
        assertEquals(
            "am broadcast -n com.oussama.firekeys/.ConfigReceiver -a com.oussama.firekeys.SET --es button TV.long --es target 'firekeys:sleep'",
            FireKeys.setCommand("TV.long", FireKeys.SLEEP),
        )
        assertEquals(
            "am broadcast -n com.oussama.firekeys/.ConfigReceiver -a com.oussama.firekeys.SET --es button Netflix",
            FireKeys.setCommand("Netflix", null),
        )
        assertEquals("am broadcast -n com.oussama.firekeys/.ConfigReceiver -a com.oussama.firekeys.GET", FireKeys.getCommand())
    }

    @Test fun linksAndKinds() {
        assertEquals("https://youtube.com", FireKeys.link("youtube.com"))
        assertEquals("https://x.com/a%27b", FireKeys.link(" https://x.com/a'b "))
        assertNull(FireKeys.link("http://example.com"))
        assertNull(FireKeys.link("not a link"))
        assertEquals(FireKeys.Kind.NORMAL, FireKeys.kind(null))
        assertEquals(FireKeys.Kind.LAST_APP, FireKeys.kind(FireKeys.LAST_APP))
        assertEquals(FireKeys.Kind.LINK, FireKeys.kind("https://a.b"))
        assertEquals(FireKeys.Kind.APP, FireKeys.kind("com.x/.Main"))
        assertEquals("com.x", FireKeys.packageOf("com.x/.Main"))
    }

    @Test fun holdsAfterSave() {
        val s = FireKeys.parse(reply)!!
        assertTrue(FireKeys.holds(s, "PrimeVideo", "com.stremio.one"))
        assertTrue(FireKeys.holds(s, "Netflix", "https://www.youtube.com/watch?v=1&t=2"))
        assertFalse(FireKeys.holds(s, "YouTube", null))
        val cleared = FireKeys.parse(reply.replace("\"YouTube\":\"org.smarttube.stable\"", "\"X\":\"y\""))!!
        assertTrue(FireKeys.holds(cleared, "YouTube", null))
    }
}
