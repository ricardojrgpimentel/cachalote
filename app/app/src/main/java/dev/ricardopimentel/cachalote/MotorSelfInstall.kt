package dev.ricardopimentel.cachalote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Instala o motor no telefone **sem computador**.
 *
 * O motor (o *daemon*) é o processo que corre fora do sandbox da app e lhe dá root ou o acesso do
 * Shizuku. Até agora só o `daemon/install.sh`, num computador, o sabia pôr lá — o que deixava de
 * fora quem instala a app e só tem o telefone. Não era uma limitação técnica: **a app já leva tudo
 * o que é preciso**:
 *
 *   · o kernel do DSH — `assets/kernel.zip` (212 MB), o mesmo que o modo embutido extrai;
 *   · o Node — `libnode.so` + as bibliotecas da farm, que são o que o modo embutido executa;
 *   · os ficheiros pequenos do daemon — `assets/motor-*` (~60 KB);
 *   · o script de arranque do Magisk — também nos assets.
 *
 * O que faltava era a canalização, e é isto: os bytes vão **pelo stdin** de um processo do lado de
 * lá (o `sh` do Shizuku, ou o `su`), porque a app não pode escrever em `/data/local/tmp` nem em
 * `/data/adb`. A extracção do kernel é feita lá — o `/system/bin/unzip` existe em qualquer Android
 * e evita passar 24 000 ficheiros por um tubo.
 *
 * O `libnode.so` é **copiado** para o destino antes de ser executado: correr um ficheiro de dentro
 * de `/data/app/.../lib/arm64` a partir do contexto do `su` é o tipo de coisa que o SELinux recusa.
 */
object MotorSelfInstall {

    /** Para onde instalar. O destino decide **quem** executa os comandos. */
    enum class Destino { SHIZUKU, ROOT }

    /** Onde vive o motor em cada modo. */
    fun baseDe(destino: Destino): String = when (destino) {
        Destino.SHIZUKU -> ShizukuBridge.DAEMON_BASE
        Destino.ROOT -> RootShell.BASE
    }

    /** Um passo do progresso, para a UI poder dizer o que está a acontecer. */
    data class Progresso(val passo: String, val fracao: Float)

    /** Quem executa os comandos do outro lado. */
    private interface Lado {
        suspend fun run(cmd: String, segundos: Long = 300): String
        suspend fun enviar(cmd: String, src: java.io.InputStream, bytes: Long, onBytes: (Long) -> Unit)
        /** Comando longo, destacado do processo que o lançou; termina ao escrever `marca.pronto`. */
        suspend fun destacado(comando: String, marca: String, onEspera: suspend (Long) -> Unit): String
    }

    private class LadoShizuku : Lado {
        override suspend fun run(cmd: String, segundos: Long) = ShizukuBridge.runOuFalha(cmd, segundos)
        override suspend fun enviar(cmd: String, src: java.io.InputStream, bytes: Long, onBytes: (Long) -> Unit) {
            ShizukuBridge.sendFile(cmd, src, onBytes = onBytes)
        }
        override suspend fun destacado(comando: String, marca: String, onEspera: suspend (Long) -> Unit) =
            ShizukuBridge.runDestacado(comando, marca, onEspera = onEspera)
    }

    private class LadoRoot : Lado {
        override suspend fun run(cmd: String, segundos: Long) = run {
            val saida = RootShell.run("$cmd && echo __OK__", segundos).getOrThrow()
            if (!saida.contains("__OK__")) error("o comando não chegou ao fim: ${cmd.take(120)}")
            saida.replace("__OK__", "").trim()
        }
        override suspend fun enviar(cmd: String, src: java.io.InputStream, bytes: Long, onBytes: (Long) -> Unit) {
            RootShell.sendFile(cmd, src, onBytes = onBytes)
        }
        override suspend fun destacado(comando: String, marca: String, onEspera: suspend (Long) -> Unit) =
            RootShell.runDestacado(comando, marca, onEspera = onEspera)
    }

    /**
     * Uma instalação anterior ficou a meio? Apaga-se antes de começar.
     *
     * Sem isto, um `unzip` interrompido deixava meia árvore e a segunda tentativa dava erros
     * estranhos — o mesmo cuidado que o modo embutido já tem com o `.part`.
     */
    const val MARCA = ".instalado"

    /**
     * Quantos ficheiros tem o `kernel.zip` (24 000 e poucos). Serve só para a barra de progresso
     * da extracção dizer uma percentagem verdadeira em vez de andar às voltas.
     */
    private const val FICHEIROS_DO_KERNEL = 23_958f

    suspend fun instalar(
        ctx: Context,
        destino: Destino,
        onProgresso: (Progresso) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val base = baseDe(destino)
        val lado: Lado = if (destino == Destino.SHIZUKU) LadoShizuku() else LadoRoot()

        fun diz(passo: String, fracao: Float) = onProgresso(Progresso(passo, fracao.coerceIn(0f, 1f)))

        diz("a preparar as pastas", 0.01f)
        lado.run(
            "rm -f $base/$MARCA && mkdir -p $base/bin $base/runtime/bin $base/runtime/lib " +
                "$base/home/ws $base/run $base/logs $base/tmp",
        )

        // **O porto.** O daemon root ouve no 39731 e o do Shizuku no 39733 — os dois podem
        // coexistir no mesmo telefone, e é `home/port` que o diz (o `install.sh` do PC já o
        // escrevia; o instalador da app não, e por isso o daemon que a app instalava ficava no
        // porto do root). Apanhado no S10+ em 2026-10-21: app em Shizuku a dizer «token inválido»
        // porque se ligava ao daemon root, que subira no boot.
        val porto = Portos.paraBase(base)
        lado.enviar("cat > $base/home/port", "$porto\n".byteInputStream(), 0) {}

        // ---- 1. O kernel (o peso todo: 212 MB comprimidos, 216 MB extraídos, 24 000 ficheiros)
        val zip = ctx.assets.open("kernel.zip")
        val tamanhoZip = zip.available().toLong()
        var ultimoAviso = 0L
        diz("a enviar o kernel", 0.02f)
        lado.enviar("cat > $base/kernel.zip", zip, tamanhoZip) { enviados ->
            // O envio é 70% do trabalho; o progresso dentro dele evita a barra parada.
            val f = 0.02f + 0.70f * (enviados.toFloat() / tamanhoZip.toFloat().coerceAtLeast(1f))
            // Não se avisa a cada bloco: seriam centenas de recomposições por segundo.
            if (enviados - ultimoAviso > 8L * 1024 * 1024) {
                ultimoAviso = enviados
                diz("a enviar o kernel", f)
            }
        }

        // A extracção é o passo longo (24 000 ficheiros). Vai **destacada** — presa a um processo
        // do Shizuku durante minutos, morria a meio — e o progresso conta ficheiros a sério.
        diz("a extrair o kernel", 0.74f)
        val marca = "$base/.extraindo"
        var vistos = 0L
        lado.destacado(
            "sh -c 'cd $base && unzip -q -o kernel.zip -d kernel && rm -f kernel.zip && " +
                "echo ok > $marca.pronto'",
            marca,
        ) { _ ->
            val n = runCatching {
                lado.run("find $base/kernel -type f 2>/dev/null | wc -l", 30).trim().toLong()
            }.getOrDefault(vistos)
            if (n > vistos) {
                vistos = n
                diz("a extrair o kernel ($n ficheiros)", 0.74f + 0.10f * (n.toFloat() / FICHEIROS_DO_KERNEL))
            }
        }
        lado.run("rm -f $marca $marca.pronto")

        // ---- 2. O Node: o `libnode.so` da APK faz de `runtime/bin/node`
        val libs = ctx.applicationInfo.nativeLibraryDir
        diz("a instalar o motor Node", 0.86f)
        File(libs, "libnode.so").inputStream().use { lado.enviar("cat > $base/runtime/bin/node", it, 0) {} }

        // ---- 3. As bibliotecas, com o nome que o `DT_NEEDED` pede (a farm da APK usa outro)
        val manifesto = ctx.assets.open("embed-libs.txt").bufferedReader().readLines()
        val uteis = manifesto.mapNotNull { linha ->
            val partes = linha.trim().split("=")
            if (partes.size == 2 && File(libs, partes[1]).isFile) partes[0] to partes[1] else null
        }
        uteis.forEachIndexed { i, (soname, embarcado) ->
            val fracao = 0.90f + 0.06f * (i.toFloat() / uteis.size.toFloat().coerceAtLeast(1f))
            diz("a instalar as bibliotecas ($soname)", fracao)
            File(libs, embarcado).inputStream().use {
                lado.enviar("cat > $base/runtime/lib/$soname", it, 0) {}
            }
        }

        // ---- 4. Os ficheiros pequenos do daemon, que vinham do computador
        diz("a instalar o daemon", 0.97f)
        // Os caminhos lá dentro estão escritos para `/data/adb/cachalote` (é o que o
        // `install.sh` corrige com um `sed` no fim). Aqui a correcção faz-se antes de enviar, em
        // Kotlin — não se depende do `sed -i` do telefone.
        listOf(
            "motor-cachalote" to "bin/cachalote",
            "motor-bridge.cjs" to "bridge.cjs",
            "motor-cachalote.patch.yml" to "cachalote.patch.yml",
        ).forEach { (asset, relativo) ->
            val texto = ctx.assets.open(asset).use { it.readBytes().toString(Charsets.UTF_8) }
                .replace("/data/adb/cachalote", base)
            lado.enviar("cat > $base/$relativo", texto.byteInputStream(), 0) {}
        }
        ctx.assets.open("motor-session-meta.cjs").use { lado.enviar("cat > $base/session-meta.cjs", it, 0) {} }

        // **O `bash`.** O Android não tem bash, mas o `bash-local` do motor spawna `bash` por nome
        // e o `bridge.cjs` põe `$base/bin` à frente do PATH. Sem este shim, todos os comandos do
        // agente falham com `spawn bash ENOENT` — o `install.sh` do PC criava-o e o instalador da
        // app não (apanhado no S10+ em 2026-10-19, no primeiro turno do modo root).
        val shim = "#!/system/bin/sh\nexec /system/bin/sh \"\$@\"\n"
        lado.enviar("cat > $base/bin/bash", shim.byteInputStream(), 0) {}

        // **O perfil.** Sem `home/cordis.patch.yml` o motor arranca com o perfil por omissão e o
        // agente **não tem as ferramentas `android_*`** — o `doctor` dizia-o em letras grandes
        // («o perfil não insere o plugin das capacidades»). O `install.sh` copia-o para aqui; o
        // instalador da app tem de fazer o mesmo.
        val patch = ctx.assets.open("motor-cachalote.patch.yml").use { it.readBytes().toString(Charsets.UTF_8) }
            .replace("/data/adb/cachalote", base)
        lado.enviar("cat > $base/home/cordis.patch.yml", patch.byteInputStream(), 0) {}

        // A marca de origem do kernel, do ponto de vista de quem o instalou (a app), para o
        // `doctor` poder dizer de onde ele veio.
        val sha = runCatching {
            ctx.assets.open("kernel.sha256").use { it.readBytes().toString(Charsets.UTF_8).trim() }
        }.getOrDefault("?")
        // A versão do kernel vem do próprio `package.json` que acabámos de extrair: é a única
        // fonte de verdade, e o `doctor` mostra-a.
        // Raw string para não haver guerra de escapes com as aspas do JSON e do sed.
        val versao = runCatching {
            lado.run(
                """sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' """ +
                    "$base/kernel/dsh/node_modules/@deepseek-ai/dsh/package.json | head -1",
                30,
            ).trim()
        }.getOrDefault("?")
        val origem = """{"origem":"assets da APK (kernel.zip)","versao":"$versao","sha256":"$sha",""" +
            """"instalador":"a app, sem computador","quando":"${java.time.Instant.now()}"}"""
        lado.enviar("cat > $base/kernel/.cachalote-origem", origem.byteInputStream(), 0) {}

        // ---- 5. Permissões e a marca de «está instalado»
        diz("a acabar", 0.99f)
        lado.run(
            "chmod 755 $base/bin/cachalote $base/bin/bash $base/runtime/bin/node && " +
                "chmod 644 $base/bridge.cjs $base/session-meta.cjs $base/cachalote.patch.yml " +
                "$base/home/port $base/home/cordis.patch.yml $base/kernel/.cachalote-origem && " +
                "touch $base/$MARCA",
        )
        // Confirmação a sério: o `cachalote doctor` é quem sabe se a árvore está sã. Não se falha
        // a instalação por causa dele (o doctor sai com código != 0 quando encontra avisos), mas
        // o resultado fica no log para se poder ver.
        runCatching { lado.run("$base/bin/cachalote doctor 2>&1 | tail -3", 120) }

        diz("instalado", 1f)
    }

    /**
     * Instala o arranque automático no Magisk (modo root).
     *
     * É o que faz o motor subir com o telefone, sem a app ter de estar aberta. Fica na pasta do
     * Magisk, que é do root — por isso só existe no modo root.
     */
    suspend fun instalarArranque(ctx: Context, base: String) {
        withContext(Dispatchers.IO) {
            val script = ctx.assets.open("motor-service.sh").use { it.readBytes().toString(Charsets.UTF_8) }
            RootShell.run("mkdir -p /data/adb/service.d").getOrThrow()
            // 60 s e não 0: o `0` era interpretado como «timeout imediato» pelo `esperarSaida` do
            // `RootShell` e o arranque no boot nunca chegava a ser escrito (o `runCatching` acima
            // engolia o erro em silêncio). Apanhado no S10+ em 2026-10-19: `/data/adb/service.d`
            // estava vazio e o `doctor` avisava que o watchdog não subia no arranque.
            RootShell.sendFile("cat > /data/adb/service.d/cachalote.sh", script.byteInputStream(), 60) {}
            RootShell.run("chmod 755 /data/adb/service.d/cachalote.sh").getOrThrow()
        }
    }

    /**
     * O arranque automático está instalado? (e a apontar para esta base?)
     */
    suspend fun arranqueInstalado(base: String): Boolean = runCatching {
        RootShell.run("test -x /data/adb/service.d/cachalote.sh && echo ok", 20).getOrThrow().contains("ok")
    }.getOrDefault(false)

    /** Uma instalação anterior ficou a meio? */
    suspend fun instalacaoParcial(destino: Destino): Boolean = runCatching {
        val base = baseDe(destino)
        val cmd = "if [ -d $base ] && [ ! -f $base/$MARCA ]; then echo parcial; fi"
        val saida = when (destino) {
            Destino.SHIZUKU -> ShizukuBridge.run(cmd, 20)
            Destino.ROOT -> RootShell.run(cmd, 20).getOrThrow()
        }
        saida.contains("parcial")
    }.getOrDefault(false)

    /** Já está instalado e são (tem a marca e o kernel no sítio)? */
    suspend fun instalado(destino: Destino): Boolean = runCatching {
        val base = baseDe(destino)
        val cmd = "test -f $base/$MARCA && test -x $base/bin/cachalote && " +
            "test -f $base/kernel/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js && echo sim"
        val saida = when (destino) {
            Destino.SHIZUKU -> ShizukuBridge.run(cmd, 20)
            Destino.ROOT -> RootShell.run(cmd, 20).getOrThrow()
        }
        saida.contains("sim")
    }.getOrDefault(false)
}
