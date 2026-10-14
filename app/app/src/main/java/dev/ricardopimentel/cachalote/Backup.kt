package dev.ricardopimentel.cachalote

import android.content.Context
import org.json.JSONObject

/**
 * Backup e restauro das **definições** (não das conversas, nem da chave).
 *
 * O que entra: modo, capacidades autorizadas, áreas permitidas do bash, URL do kernel e os
 * servidores MCP. O que **não** entra, de propósito:
 *  · a **chave do provedor** — vive no daemon (`home/.env`, modo 600) e a app nunca a guarda nem
 *    a copia (foi decisão explícita do projecto: ver D do plano);
 *  · o **token do canal** — é um segredo entre a app e o daemon; se o ficheiro de backup andar por
 *    aí, não deve levar com ele;
 *  · as **conversas** — estão no motor, em `home/sessions`.
 *
 * O formato é JSON legível para se poder inspeccionar antes de restaurar.
 */
object Backup {

    const val VERSAO = 1

    fun exportar(ctx: Context, prefs: Prefs): JSONObject {
        val caps = JSONObject()
        HostCapabilities.CAPABILITIES.forEach { caps.put(it, prefs.isCapabilityAllowed(it)) }
        return JSONObject()
            .put("app", "cachalote")
            .put("versao", VERSAO)
            .put("modo", prefs.mode)
            .put("capacidades", caps)
            .put("areasBash", prefs.allowedRoots)
            .put("kernelUrl", prefs.kernelUrl)
            .put("mcpServers", prefs.mcpServersJson)
            .put("workspace", prefs.workspace)
    }

    /**
     * Aplica um backup. Só toca no que reconhece; o resto é ignorado em silêncio (um ficheiro
     * vindo de outra versão não deve estragar nada).
     *
     * Devolve quantas definições foram aplicadas.
     */
    fun importar(ctx: Context, prefs: Prefs, texto: String): Int {
        val json = JSONObject(texto)
        require(json.optString("app") == "cachalote") { "isto não é um backup do Cachalote" }
        var aplicadas = 0

        json.optJSONObject("capacidades")?.let { caps ->
            HostCapabilities.CAPABILITIES.forEach { cap ->
                if (caps.has(cap)) {
                    prefs.setCapabilityAllowed(cap, caps.optBoolean(cap))
                    aplicadas++
                }
            }
        }
        if (json.has("areasBash")) {
            prefs.allowedRoots = json.optString("areasBash")
            aplicadas++
        }
        if (json.has("kernelUrl")) {
            prefs.kernelUrl = json.optString("kernelUrl")
            aplicadas++
        }
        if (json.has("mcpServers")) {
            // Validar antes de aceitar: um JSON partido deixaria todas as sessões novas a falhar.
            val bruto = json.optString("mcpServers").ifBlank { "[]" }
            org.json.JSONArray(bruto) // lança se for inválido
            prefs.mcpServersJson = bruto
            aplicadas++
        }
        if (json.has("modo")) {
            val modo = json.optString("modo")
            if (modo in setOf("daemon", "shizuku", "embedded")) {
                prefs.mode = modo
                aplicadas++
            }
        }
        return aplicadas
    }

    /** Nome sugerido para o ficheiro de backup. */
    fun nomeFicheiro(): String = "cachalote-definicoes.json"
}
