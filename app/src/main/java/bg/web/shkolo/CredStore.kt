package bg.web.shkolo

import android.content.Context
import android.util.Log

/**
 * Saved login for Shkolo Web.
 *
 * The site's own "remember me" does not survive, so the app keeps the last
 * credentials and replays them through the real login handshake.
 * Stored in plain SharedPreferences: this is a personal device and the
 * credentials only ever unlock the user's own school account.
 */
object CredStore {

    private const val PREFS = "shkolo_creds"
    private const val PREF_USER = "user"
    private const val PREF_PASS = "pass"

    fun save(ctx: Context, user: String, pass: String) {
        if (user.isBlank() || pass.isEmpty()) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_USER, user)
            .putString(PREF_PASS, pass)
            .apply()
        Log.i("ShkoloWeb", "credentials saved")
    }

    fun load(ctx: Context): Pair<String, String>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val u = p.getString(PREF_USER, null) ?: return null
        val w = p.getString(PREF_PASS, null) ?: return null
        if (u.isBlank() || w.isEmpty()) return null
        return u to w
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        Log.i("ShkoloWeb", "credentials cleared")
    }
}