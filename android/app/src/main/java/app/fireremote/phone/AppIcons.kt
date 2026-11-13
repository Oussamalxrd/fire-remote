package app.fireremote.phone

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Logos of the TV's apps, cached on the phone (files/icons/<package>.b.png = banner, .i.png = icon). */
object AppIcons {
    private val images = mutableStateMapOf<String, ImageBitmap>()
    private val banners = mutableStateMapOf<String, Boolean>()
    var refreshing by mutableStateOf(false)
        private set
    @Volatile private var loaded = false
    @Volatile var triedThisSession = false
        private set

    fun image(pkg: String): ImageBitmap? = images[pkg]
    fun isBanner(pkg: String) = banners[pkg] == true

    private fun dir(c: Context) = File(c.filesDir, "icons")

    private fun decode(f: File) = runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()

    /** Reads the cache from disk once. */
    suspend fun load(c: Context) {
        if (loaded) return
        loaded = true
        val found = withContext(Dispatchers.IO) {
            dir(c).listFiles().orEmpty().mapNotNull { f ->
                val m = Regex("^(.+)\\.([bi])\\.png$").find(f.name) ?: return@mapNotNull null
                val img = decode(f) ?: return@mapNotNull null
                Triple(m.groupValues[1], m.groupValues[2] == "b", img)
            }
        }
        for ((pkg, banner, img) in found) {
            images[pkg] = img
            banners[pkg] = banner
        }
    }

    /** Asks the TV for all logos and replaces the cache. */
    suspend fun refresh(c: Context) {
        if (refreshing) return
        refreshing = true
        triedThisSession = true
        try {
            val icons = TvLink.fetchIcons()
            if (icons.isEmpty()) return
            val decoded = withContext(Dispatchers.IO) {
                val d = dir(c)
                d.mkdirs()
                d.listFiles()?.forEach { it.delete() }
                icons.mapNotNull { (pkg, banner, png) ->
                    val f = File(d, "$pkg.${if (banner) "b" else "i"}.png")
                    f.writeBytes(png)
                    BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()?.let { Triple(pkg, banner, it) }
                }
            }
            images.clear()
            banners.clear()
            for ((pkg, banner, img) in decoded) {
                images[pkg] = img
                banners[pkg] = banner
            }
        } finally {
            refreshing = false
        }
    }
}
