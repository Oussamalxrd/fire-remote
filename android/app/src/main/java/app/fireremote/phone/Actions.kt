package app.fireremote.phone

/** What a remote button slot does. Ids are what Settings stores; icons are in RemoteUi (actionIcon). */
enum class Action(val id: String, val label: Int, val mode: Mode, val code: Int = 0) {
    HIDDEN("none", R.string.action_hidden, Mode.Tap),
    POWER("power", R.string.key_power, Mode.Tap),
    TV_SETTINGS("tvsettings", R.string.key_tv_settings, Mode.Tap),
    BACK("back", R.string.key_back, Mode.Hold, K.BACK),
    HOME("home", R.string.key_home, Mode.Hold, K.HOME),
    MENU("menu", R.string.key_menu, Mode.Hold, K.MENU),
    REWIND("rewind", R.string.key_rewind, Mode.Repeat, K.REWIND),
    PLAY("play", R.string.key_play, Mode.Tap, K.PLAY),
    FORWARD("forward", R.string.key_forward, Mode.Repeat, K.FORWARD),
    MUTE("mute", R.string.key_mute, Mode.Tap, K.MUTE),
    VOL_DOWN("voldown", R.string.key_vol_down, Mode.Repeat, K.VOL_DOWN),
    VOL_UP("volup", R.string.key_vol_up, Mode.Repeat, K.VOL_UP),
    PEEK("peek", R.string.key_peek, Mode.Tap),
    CLOSE_APP("close", R.string.key_close_app, Mode.Tap),
    TOUCHPAD("touchpad", R.string.touchpad, Mode.Tap),
    APPS("apps", R.string.apps, Mode.Tap),
    SEARCH("search", R.string.search, Mode.Tap, 84),
    APP_SETTINGS("settings", R.string.action_app_settings, Mode.Tap);

    companion object {
        /** The 13 slots: top corners, three rows of three, bottom corners. */
        val DEFAULTS = listOf(
            POWER, TV_SETTINGS,
            BACK, HOME, MENU,
            REWIND, PLAY, FORWARD,
            MUTE, VOL_DOWN, VOL_UP,
            PEEK, CLOSE_APP,
        )

        fun parse(text: String?): List<Action> {
            val ids = text?.split(',') ?: return DEFAULTS
            if (ids.size != DEFAULTS.size) return DEFAULTS
            return ids.mapIndexed { i, id -> entries.firstOrNull { it.id == id } ?: DEFAULTS[i] }
        }

        fun format(slots: List<Action>) = slots.joinToString(",") { it.id }
    }
}
