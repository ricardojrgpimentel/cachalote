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

    /**
     * Servidores MCP (JSON, formato do ACP: `[{name, command, args, env?}]` ou
     * `[{name, type:"http", url}]`). Vão no `session/new` e no `session/resume`.
     */
    var mcpServersJson: String
        get() = sp.getString("mcpServersJson", "[]")!!
        set(v) = sp.edit().putString("mcpServersJson", v).apply()

    /**
     * URL de onde descarregar o kernel (hospedagem própria; vazio = o que vier no
     * `assets/kernel.remote.json`). Serve para não meter 389 MB dentro da APK.
     */
    var kernelUrl: String
        get() = sp.getString("kernelUrl", "")!!
        set(v) = sp.edit().putString("kernelUrl", v).apply()

    /** Token do canal no modo Shizuku (o do daemon root é outro: guardar à parte). */
    var tokenShizuku: String?
        get() = sp.getString("tokenShizuku", null)
        set(v) = sp.edit().putString("tokenShizuku", v).apply()

    /** Áreas onde o agente pode escrever sem pedir autorização (uma por linha). */
    var allowedRoots: String
        get() = sp.getString("allowedRoots", "")!!
        set(v) = sp.edit().putString("allowedRoots", v).apply()

    /** "daemon" (root/Shizuku) ou "embedded" (motor dentro da app, sem root). */
    /** Falso até o utilizador passar pelo assistente de configuração inicial. */
    var wizardConcluido: Boolean
        get() = sp.getBoolean("wizardConcluido", false)
        set(v) = sp.edit().putBoolean("wizardConcluido", v).apply()

    var mode: String
        get() = sp.getString("mode", "daemon")!!
        set(v) = sp.edit().putString("mode", v).apply()

    /**
     * O utilizador já escolheu um modo (no assistente ou em Ajustes → Motor).
     *
     * Sem isto, a sugestão do assistente (Shizuku se estiver a correr, root se houver Magisk,
     * embutido caso contrário) voltaria a impor-se sempre que o assistente reabrisse.
     */
    var modoEscolhido: Boolean
        get() = sp.getBoolean("modoEscolhido", false)
        set(v) = sp.edit().putBoolean("modoEscolhido", v).apply()

    /**
     * A acessibilidade já esteve ligada alguma vez.
     *
     * Serve para a faixa de aviso não incomodar quem nunca a ligou (e não quer o serviço): ela só
     * aparece a quem **perdeu** a acessibilidade a meio — o caso do `force-stop`, em que o Android
     * desliga o serviço e a app fica sem olhos nem dedos até o dono voltar às Definições.
     */
    var a11yJaEsteveLigada: Boolean
        get() = sp.getBoolean("a11yJaEsteveLigada", false)
        set(v) = sp.edit().putBoolean("a11yJaEsteveLigada", v).apply()

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
