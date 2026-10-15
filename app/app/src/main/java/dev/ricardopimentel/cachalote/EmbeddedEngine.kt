package dev.ricardopimentel.cachalote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Motor **dentro da própria app** — o modo sem root (nem Shizuku).
 *
 * Porque é que isto funciona com `targetSdk 35`: o directório privado da app é noexec, mas o
 * `nativeLibraryDir` (onde a APK extrai os jniLibs) é executável. O Node vai embarcado como
 * `libnode.so` e as bibliotecas versionadas como alias `.so`, reconstruídas numa farm de
 * symlinks no `filesDir` (ver base de conhecimento 61 e `tools/prepare-embed.sh`).
 *
 * O kernel é extraído de `files/kernel.zip` para `files/kernel/` (o Android não traz `tar`).
 *
 * Consequência de segurança: o motor corre como o **uid da app**, portanto o sandbox do
 * Android é o limite real — o contrário do modo daemon, onde o motor corre como root e a
 * barreira é a nossa guarda (D10).
 */
class EmbeddedEngine(private val ctx: Context) {

    companion object {
        /** Porta do servidor de capacidades que a app levanta para o motor embutido. */
        const val HOST_PORT = 39732
        private const val MARKER = ".extracted"
    }

    private var proc: Process? = null
    private var streams: CachaloteClient.Streams? = null
    private val startLock = Any()

    val home: File get() = File(ctx.filesDir, "dsh-home")
    private val kernelRoot: File get() = File(ctx.filesDir, "kernel")
    private val kernelZip: File get() = File(ctx.filesDir, "kernel.zip")
    val binJs: File get() = File(kernelRoot, "dsh/node_modules/@deepseek-ai/dsh/lib/bin.js")

    fun isPrepared(): Boolean = binJs.isFile

    /** O zip existe na app, vem embutido na APK, ou há de onde o descarregar. */
    fun hasZip(): Boolean = kernelZip.isFile || assetZipPresent() || kernelSource() != null

    private fun assetZipPresent(): Boolean = runCatching { ctx.assets.open("kernel.zip").close(); true }.getOrDefault(false)

    /** Metadados do kernel publicado (url + sha256 + bytes), se o build os tiver deixado. */
    private data class RemoteKernel(val url: String, val sha256: String?, val bytes: Long)

    private fun remoteMeta(): RemoteKernel? = runCatching {
        ctx.assets.open("kernel.remote.json").bufferedReader().use { reader ->
            val json = org.json.JSONObject(reader.readText())
            val url = json.optString("url").ifBlank { return@use null }
            RemoteKernel(url, json.optString("sha256").takeIf { it.length == 64 }, json.optLong("bytes", 0))
        }
    }.getOrNull()

    /**
     * De onde vem o kernel, por ordem de preferência: 1) já cá está; 2) embutido na APK;
     * 3) **download** (URL do utilizador ou do `assets/kernel.remote.json`).
     */
    fun kernelSource(): String? = when {
        kernelZip.isFile && kernelZip.length() > 0 -> "ficheiro"
        assetZipPresent() -> "asset"
        userKernelUrl().isNotBlank() -> "download (${userKernelUrl()})"
        remoteMeta() != null -> "download (${remoteMeta()!!.url})"
        else -> null
    }

    /** URL configurado pelo utilizador (serve para hospedagem própria ou testes). */
    private fun userKernelUrl(): String = Prefs(ctx).kernelUrl.trim()

    /**
     * Descarrega o kernel e verifica o sha256. É o caminho para distribuir sem meter 389 MB
     * dentro da APK: a app traz só os metadados e vai buscar o ficheiro.
     */
    private fun downloadKernel(progress: (Long, Long) -> Unit): File {
        val url = userKernelUrl().ifBlank { remoteMeta()?.url.orEmpty() }
        require(url.isNotBlank()) { "não há URL do kernel (define em Poderes ou compila com --remote-url)" }
        val expected = remoteMeta()?.sha256
        kernelZip.parentFile?.mkdirs()
        val tmp = File(kernelZip.parentFile, "kernel.zip.part")
        val started = System.currentTimeMillis()
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "cachalote-android")
        }
        connection.connect()
        val code = connection.responseCode
        require(code in 200..299) { "o servidor respondeu $code ao pedido do kernel ($url)" }
        val total = connection.contentLengthLong
        var lastReport = 0L
        connection.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buffer = ByteArray(1 shl 16)
                var read = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    read += n
                    if (System.currentTimeMillis() - lastReport > 400) {
                        lastReport = System.currentTimeMillis()
                        progress(read, total)
                    }
                }
                progress(read, total)
            }
        }
        connection.disconnect()
        require(tmp.length() > 0) { "o download do kernel veio vazio" }
        if (expected != null) {
            val actual = sha256(tmp)
            require(actual.equals(expected, ignoreCase = true)) {
                "o kernel descarregado falhou a verificação (esperado ${expected.take(16)}…, obtido ${actual.take(16)}…)"
            }
        }
        if (kernelZip.exists()) kernelZip.delete()
        require(tmp.renameTo(kernelZip)) { "não consegui guardar o kernel em ${kernelZip.absolutePath}" }
        android.util.Log.i("cachalote-kernel", "kernel descarregado: ${kernelZip.length()} bytes em ${(System.currentTimeMillis() - started) / 1000}s")
        return kernelZip
    }

    /**
     * Garante o zip do kernel no armazenamento da app. Se não estiver lá (primeira execução,
     * ou depois de limpar os dados), copia-o do **asset embutido na APK** e verifica o sha256
     * antes de confiar nele — é a diferença entre "funciona" e "o utilizador tem de fazer
     * `adb push`".
     */
    private fun ensureKernelZip(progress: (Long, Long) -> Unit = { _, _ -> }): File {
        if (kernelZip.isFile && kernelZip.length() > 0) return kernelZip
        // Sem asset, o kernel vem por download (com verificação) — é o caminho de distribuição:
        // a APK fica leve em vez de carregar 389 MB de kernel.
        if (!assetZipPresent()) return downloadKernel(progress)
        kernelZip.parentFile?.mkdirs()
        val tmp = File(kernelZip.parentFile, "kernel.zip.part")
        val expected = expectedSha256()
        // Duas tentativas, e a cópia inteira dentro do lock: a primeira utilização do modo
        // embutido falhou uma vez a verificação (esperado 0c7106ff…, obtido d2cf2cd6…) com o
        // artefacto da APK **correcto** — verificado à mão, byte a byte, no S10+ em 2026-10-15.
        // O que falhou foi a cópia, não o ficheiro. Duas tentativas com o `.part` apagado pelo
        // meio resolvem o caso intermitente; e a mensagem só aparece depois de as duas falharem.
        var ultimoErro: Throwable? = null
        repeat(2) { tentativa ->
            try {
                synchronized(copiaLock) {
                    tmp.delete()
                    ctx.assets.open("kernel.zip").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (expected != null) {
                        val actual = sha256(tmp)
                        require(actual.equals(expected, ignoreCase = true)) {
                            "o kernel embutido falhou a verificação de integridade (esperado ${expected.take(16)}…, obtido ${actual.take(16)}…)"
                        }
                    }
                    if (kernelZip.exists()) kernelZip.delete()
                    require(tmp.renameTo(kernelZip)) { "não consegui guardar o kernel em ${kernelZip.absolutePath}" }
                }
                return kernelZip
            } catch (t: Throwable) {
                ultimoErro = t
                tmp.delete()
                android.util.Log.w("cachalote-kernel", "cópia do kernel falhou (tentativa ${tentativa + 1}): ${t.message}")
            }
        }
        throw ultimoErro ?: IllegalStateException("não consegui copiar o kernel embutido")
    }

    private val copiaLock = Any()

    private fun expectedSha256(): String? = runCatching {
        ctx.assets.open("kernel.sha256").bufferedReader().use { it.readText().trim() }
            .split(Regex("\\s+")).firstOrNull { it.length == 64 }
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    val running: Boolean get() = proc?.isAlive == true

    /**
     * Reconstrói os nomes verdadeiros das bibliotecas (soname) como symlinks para os ficheiros
     * embarcados no nativeLibraryDir. Feito uma vez; é o que faz o `dlopen` funcionar.
     */
    fun libraryFarm(): File {
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        val farm = File(ctx.filesDir, "embed-lib")
        // Se o caminho da instalação mudou (reinstalação da APK), a farm antiga tem links
        // mortos: apagamos tudo e refazemos. `exists()` não serve para detectar links mortos.
        val marker = File(farm, ".native-dir")
        val current = nativeDir.absolutePath
        if (farm.isDirectory && marker.isFile && marker.readText() != current) {
            farm.deleteRecursively()
        }
        farm.mkdirs()
        runCatching { ctx.assets.open("embed-libs.txt").bufferedReader().forEachLine { line ->
            val parts = line.trim().split("=")
            if (parts.size != 2) return@forEachLine
            val link = File(farm, parts[0])
            val target = File(nativeDir, parts[1])
            if (!target.isFile) return@forEachLine
            val linkPath = link.toPath()
            runCatching { java.nio.file.Files.deleteIfExists(linkPath) }
            runCatching { java.nio.file.Files.createSymbolicLink(linkPath, target.toPath()) }
                .onFailure { runCatching { target.copyTo(link, overwrite = true) } }
        } }
        marker.writeText(current)
        return farm
    }

    /** Extrai o kernel do zip (uma vez). Devolve o número de ficheiros escritos. */
    suspend fun prepare(progress: (Long, Long) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        if (isPrepared()) return@withContext 0
        ensureKernelZip(progress)
        var files = 0
        ZipInputStream(kernelZip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                // O zip tem a raiz `dsh/`: extrair para files/kernel/ dá files/kernel/dsh/...
                val out = File(kernelRoot, e.name)
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                    files++
                }
                zin.closeEntry()
            }
        }
        kernelRoot.mkdirs()
        File(kernelRoot, MARKER).writeText("ok")
        files
    }

    /**
     * Escreve a camada de patch do perfil no DSH_HOME embutido. É o equivalente ao
     * `config/cachalote.patch.yml` do modo daemon, mas com o caminho ABSOLUTO do plugin
     * dentro do armazenamento privado da app (o loader não resolve plugins postos à mão por
     * nome — ver base de conhecimento 53).
     */
    fun ensureProfilePatch(): File {
        val plugin = File(kernelRoot, "dsh/node_modules/@deepseek-ai/dsh-tool-cachalote/lib/index.js")
        val file = File(home, "cordis.patch.yml")
        val content = """
# cachalote (modo embutido) — gerado pela app; não editar à mão.
- id: otel
  disabled: true
# Títulos de sessão (uma chamada no primeiro prompt) — a lista fica com nomes.
- id: session-title-llm
  disabled: false
- id: session-telemetry-otel
  disabled: true
- id: bash-sandbox
  disabled: true
- id: pwsh-sandbox
  disabled: true
- id: session-log-download
  disabled: true
- insert:
    - id: bash-local
      name: '@deepseek-ai/dsh-bash-local'
    - id: tool-cachalote
      name: '${plugin.absolutePath}'
- id: permission
  config:
    presets:
      read-only:
        sandbox: read-only
        approval: ask
      workspace-write:
        sandbox: workspace-write
        approval: ask
      danger-full-access:
        sandbox: danger-full-access
        approval: never
    defaultPreset: workspace-write
# O modelo tem de declarar que aceita imagens, senão o motor anuncia
# `promptCapabilities.image: false` e recusa QUALQUER prompt com imagem inline
# ("inline image prompts were not advertised by this connection") — tanto as capturas do
# `android_see` como as imagens que o utilizador anexa no chat. O perfil do daemon já o
# fazia (config/cachalote.patch.yml); o modo embutido gerava o seu próprio patch e não.
- id: llm-deepseek
  config:
    models:
      - id: deepseek-v4-flash
        name: DeepSeek-V4-Flash
        inputModalities: ["text", "image"]
""".trimIndent() + "\n"
        if (!file.isFile || file.readText() != content) file.writeText(content)
        return file
    }

    /** Arranca o motor e devolve os streams do seu stdio (idempotente e sincronizado). */
    suspend fun start(allowedRoots: String = ""): CachaloteClient.Streams = withContext(Dispatchers.IO) {
        synchronized(startLock) {
            streams?.let { if (proc?.isAlive == true) return@withContext it }
        }
        stop()
        require(isPrepared()) { "kernel não extraído" }
        val farm = libraryFarm()
        val node = File(ctx.applicationInfo.nativeLibraryDir, "libnode.so")
        require(node.isFile) { "libnode.so não está na APK (corre tools/prepare-embed.sh e reconstrói)" }
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val tmp = File(ctx.cacheDir, "tmp").apply { mkdirs() }
        home.mkdirs()
        File(home, "ws").mkdirs()
        // O plugin do kernel procura o servidor de capacidades pelo porto + token.
        val tokenFile = File(home, "app.token")
        tokenFile.writeText(HostToken.ensure(ctx) + "\n")
        // A camada de patch do perfil tem de existir ANTES de o motor arrancar (é lida no boot).
        ensureProfilePatch()

        val pb = ProcessBuilder(
            node.absolutePath,
            "--expose-internals",
            binJs.absolutePath,
            "--profile", "acp",
        )
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = farm.absolutePath
        env["PATH"] = "$nativeDir:${File(ctx.filesDir, "bin").absolutePath}:/system/bin:/system/xbin"
        env["DSH_HOME"] = home.absolutePath
        env["HOME"] = ctx.filesDir.absolutePath
        env["TMPDIR"] = tmp.absolutePath
        env["TERM"] = "dumb"
        env["NODE_OPTIONS"] = ""
        // O Android não tem `bash`: o patch ao dsh-bash-local usa esta variável como fallback.
        env["CACHALOTE_SHELL"] = "/system/bin/sh"
        env["CACHALOTE_PORT"] = HOST_PORT.toString()
        env["CACHALOTE_TOKEN_FILE"] = tokenFile.absolutePath
        if (allowedRoots.isNotBlank()) {
            env["CACHALOTE_ALLOWED_ROOTS"] = allowedRoots.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(":")
        }
        pb.redirectErrorStream(false)

        val p = pb.start()
        proc = p
        // O stderr do motor vai para um ficheiro, para diagnóstico.
        val log = File(ctx.filesDir, "engine-embedded.log")
        Thread {
            runCatching {
                p.errorStream.bufferedReader().use { r ->
                    // stderr do motor num ficheiro (com tecto, para não encher o telefone).
                    val text = r.readText()
                    if (log.length() < 1_000_000) log.appendText(text)
                }
            }
        }.apply { isDaemon = true; start() }

        val s = CachaloteClient.Streams(p.inputStream, p.outputStream, close = { runCatching { p.destroy() } })
        streams = s
        s
    }

    fun stop() {
        val p = proc ?: run { streams = null; return }
        proc = null
        streams = null
        runCatching {
            p.outputStream.close()          // EOF no stdin → o motor desliga-se
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    /** Reinicia (usado quando a chave muda: o motor lê credenciais no arranque). */
    suspend fun restart(allowedRoots: String = ""): CachaloteClient.Streams {
        stop()
        return start(allowedRoots)
    }
}

/** Token local do servidor de capacidades (o motor embutido lê-o do seu DSH_HOME). */
object HostToken {
    private const val PREFS = "dsh_prefs"
    private const val KEY = "local_token"

    fun ensure(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = sp.getString(KEY, null)
        if (!existing.isNullOrBlank() && existing.length >= 32) return existing
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        sp.edit().putString(KEY, token).apply()
        return token
    }
}
