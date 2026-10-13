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

    /** Áreas onde o agente pode escrever sem pedir autorização (uma por linha). */
    var allowedRoots: String
        get() = sp.getString("allowedRoots", "")!!
        set(v) = sp.edit().putString("allowedRoots", v).apply()

    /** "daemon" (root/Shizuku) ou "embedded" (motor dentro da app, sem root). */
    var mode: String
        get() = sp.getString("mode", "daemon")!!
        set(v) = sp.edit().putString("mode", v).apply()

    var workspace: String
        get() = sp.getString("workspace", RootShell.BASE + "/home/ws")!!
        set(v) = sp.edit().putString("workspace", v).apply()

    // ---------------------------------------------------------------- capacidades

    /** O agente só usa o que o utilizador autorizar. `notify` nasce autorizada. */
    fun isCapabilityAllowed(capability: String): Boolean =
        sp.getBoolean("cap_$capability", capability == "notify")

    fun setCapabilityAllowed(capability: String, allowed: Boolean) =
        sp.edit().putBoolean("cap_$capability", allowed).apply()

    // ---------------------------------------------------------------- auditoria

    /** Últimos pedidos do agente (mais recente primeiro), em memória + prefs. */
    fun auditAdd(line: String) {
        val current = sp.getString("audit", "") ?: ""
        val lines = (line + "\n" + current).lines().filter { it.isNotBlank() }.take(80)
        sp.edit().putString("audit", lines.joinToString("\n")).apply()
    }

    fun auditList(): List<String> =
        (sp.getString("audit", "") ?: "").lines().filter { it.isNotBlank() }

    fun auditClear() = sp.edit().remove("audit").apply()
}
