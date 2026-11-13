package app.fireremote.phone

import android.content.Context
import android.os.Build
import dadb.AdbKeyPair
import java.io.File

/** Settings and the phone's ADB key, kept in the app's private storage. */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("fireremote", Context.MODE_PRIVATE)

    fun host(c: Context): String = prefs(c).getString("host", null) ?: "192.168.137.50"
    fun port(c: Context): Int = prefs(c).getInt("port", 5555)

    fun setTarget(c: Context, host: String, port: Int) {
        prefs(c).edit().putString("host", host).putInt("port", port).apply()
    }

    const val MAX_FAVORITES = 6

    fun favorites(c: Context): List<Fav> =
        (prefs(c).getString("favorites", null) ?: "").split('\n').mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0 || i == line.length - 1) null else Fav(line.substring(0, i), line.substring(i + 1))
        }.take(MAX_FAVORITES)

    fun setFavorites(c: Context, list: List<Fav>) {
        val text = list.take(MAX_FAVORITES).joinToString("\n") {
            it.name.replace('\t', ' ').replace('\n', ' ') + "\t" + it.target
        }
        prefs(c).edit().putString("favorites", text).apply()
    }

    fun volumeKeys(c: Context): Boolean = prefs(c).getBoolean("volumeKeys", true)
    fun setVolumeKeys(c: Context, on: Boolean) = prefs(c).edit().putBoolean("volumeKeys", on).apply()

    // Targets that have accepted this phone's key. Until then the first connection waits longer,
    // so there's time to answer the "Allow USB debugging?" prompt on the TV.
    fun isAuthorized(c: Context, target: String) =
        prefs(c).getStringSet("authorized", emptySet())!!.contains(target)

    fun setAuthorized(c: Context, target: String, yes: Boolean) {
        val set = HashSet(prefs(c).getStringSet("authorized", emptySet())!!)
        if (yes) set.add(target) else set.remove(target)
        prefs(c).edit().putStringSet("authorized", set).apply()
    }

    /** Deletes this phone's ADB key: the TV will ask to allow the phone again. */
    @Synchronized
    fun resetKeys(c: Context) {
        File(c.filesDir, "adbkey").delete()
        File(c.filesDir, "adbkey.pub").delete()
        prefs(c).edit().remove("authorized").apply()
    }

    /**
     * One key for the life of the app, so the TV's "Always allow" keeps working.
     * (An app that makes a new key on every start looks like a new device to the TV each time.)
     */
    @Synchronized
    fun keys(c: Context): AdbKeyPair {
        val priv = File(c.filesDir, "adbkey")
        val pub = File(c.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            AdbKeyPair.generate(priv, pub)
            val model = Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "")
            pub.writeText(pub.readText().replace("unknown@unknown", "FireRemote@$model"))
        }
        return AdbKeyPair.read(priv, pub)
    }
}
