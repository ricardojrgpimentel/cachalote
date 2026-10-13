package dev.ricardopimentel.cachalote

import android.content.Context

/**
 * Preferências da app. Guarda apenas o token do canal TCP e detalhes de ligação
 * (nunca a API key: essa é enviada ao daemon, que a escreve com modo 600, e a app
 * esquece-a).
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cachalote", Context.MODE_PRIVATE)

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()

    var port: Int
        get() = sp.getInt("port", 39731)
        set(v) = sp.edit().putInt("port", v).apply()

    var base: String
        get() = sp.getString("base", RootShell.BASE)!!
        set(v) = sp.edit().putString("base", v).apply()

    var lastSessionId: String?
        get() = sp.getString("sessionId", null)
        set(v) = sp.edit().putString("sessionId", v).apply()

    var workspace: String
        get() = sp.getString("workspace", RootShell.BASE + "/home/ws")!!
        set(v) = sp.edit().putString("workspace", v).apply()
}
