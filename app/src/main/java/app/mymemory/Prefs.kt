package app.mymemory

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("mymemory", Context.MODE_PRIVATE)

    /** Absolute path of the imported Gemma `.litertlm` file, or null if none imported yet. */
    var modelPath: String?
        get() = sp.getString("modelPath", null)
        set(v) = sp.edit().putString("modelPath", v).apply()

    var lastSmsTs: Long
        get() = sp.getLong("lastSmsTs", 0L)
        set(v) = sp.edit().putLong("lastSmsTs", v).apply()

    var lastGmailTs: Long
        get() = sp.getLong("lastGmailTs", 0L)
        set(v) = sp.edit().putLong("lastGmailTs", v).apply()

    var gmailEnabled: Boolean
        get() = sp.getBoolean("gmailEnabled", false)
        set(v) = sp.edit().putBoolean("gmailEnabled", v).apply()

    /** How far back the very first sync looks. */
    val backfillDays: Int = 90
}
