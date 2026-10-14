package dev.ricardopimentel.cachalote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * **Ecrã virtual** — reimplementado do zero com APIs do AOSP (decisão do utilizador: opção A,
 * zero LGPL). Nada aqui vem do Operit nem de terceiros: `DisplayManager.createVirtualDisplay` e
 * `ImageReader` são API pública do Android (Apache-2.0) e é o que basta para ter uma segunda tela
 * onde o agente trabalha sem mexer no ecrã do utilizador.
 *
 * Como funciona:
 *  · `createVirtualDisplay` desenha para o `Surface` de um [ImageReader];
 *  · cada fotograma sai do `ImageReader` como um [Image] RGBA e vira [Bitmap];
 *  · a captura é devolvida em PNG (base64) — o mesmo formato da capacidade `screenshot`, para o
 *    plugin do kernel a poder anexar ao contexto do modelo sem código novo;
 *  · quem injecta toques/teclas é o **daemon (root)** com `input -d <displayId>`, o que de caminho
 *    faz o ecrã virtual funcionar **sem** o serviço de acessibilidade.
 *
 * A utilidade prática: o agente abre apps **neste** ecrã (com `am start --display <id>`), vê-as
 * por captura e toca-as — e o telefone fica livre para o dono.
 */
object VirtualScreen {

    private const val TAG = "cachalote-vscreen"

    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var lastImage: Bitmap? = null
    private var frames = 0
    private var started = 0L
    private var erro: String? = null

    var width = 0
        private set
    var height = 0
        private set
    var density = 0
        private set

    val displayId: Int get() = display?.display?.displayId ?: -1
    val running: Boolean get() = display != null

    /** Cria o ecrã virtual. `PUBLIC` para que outras apps se possam lançar nele. */
    fun start(ctx: Context, w: Int, h: Int, dpi: Int): JSONObject {
        stop()
        erro = null
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        width = w.coerceIn(240, 4096)
        height = h.coerceIn(240, 4096)
        density = dpi.coerceIn(120, 640)
        return try {
            // Dois buffers: um para o compositor escrever, outro que estamos a ler.
            val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = r
            r.setOnImageAvailableListener({ reader ->
                val bmp = bmpDe(reader) ?: return@setOnImageAvailableListener
                lastImage?.recycle()
                lastImage = bmp
                frames++
            }, Handler(Looper.getMainLooper()))

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            display = dm.createVirtualDisplay(
                "cachalote-vscreen",
                width,
                height,
                density,
                r.surface,
                flags,
            )
            started = System.currentTimeMillis()
            android.util.Log.i(TAG, "ecrã virtual criado: id=$displayId ${width}x$height @$density")
            status()
        } catch (t: Throwable) {
            erro = t.message ?: t.toString()
            android.util.Log.e(TAG, "falhou a criar o ecrã virtual: $erro")
            stop()
            throw IllegalStateException("não consegui criar o ecrã virtual: $erro")
        }
    }

    fun stop(): JSONObject {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { lastImage?.recycle() }
        display = null
        reader = null
        lastImage = null
        frames = 0
        return JSONObject().put("ok", true).put("running", false)
    }

    fun status(): JSONObject = JSONObject()
        .put("running", running)
        .put("displayId", displayId)
        .put("width", width)
        .put("height", height)
        .put("density", density)
        .put("frames", frames)
        .put("uptimeMs", if (started > 0 && running) System.currentTimeMillis() - started else 0)
        .put("lastError", erro ?: JSONObject.NULL)

    /**
     * Fotograma actual em PNG base64 (mesma forma da capacidade `screenshot`: `path`, `bytes`,
     * `width`, `height`, `mediaType`, `data`). Se ainda não houver fotograma, espera um pouco.
     */
    fun frame(maxWidth: Int = 720, esperaMs: Long = 2500): JSONObject {
        check(running) { "o ecrã virtual não está a correr (vscreen_start)" }
        val limite = System.currentTimeMillis() + esperaMs
        var bmp = lastImage?.copy(Bitmap.Config.ARGB_8888, false)
        while (bmp == null && System.currentTimeMillis() < limite) {
            Thread.sleep(100)
            bmp = lastImage?.copy(Bitmap.Config.ARGB_8888, false)
        }
        checkNotNull(bmp) { "o ecrã virtual ainda não desenhou nada (a app foi lançada com --display $displayId?)" }
        val escalado = if (maxWidth in 64 until bmp.width) {
            val h = (bmp.height.toLong() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, maxWidth, h, true).also { if (it !== bmp) bmp.recycle() }
        } else {
            bmp
        }
        val out = ByteArrayOutputStream()
        escalado.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        val largura = escalado.width
        val altura = escalado.height
        escalado.recycle()
        return JSONObject()
            .put("displayId", displayId)
            .put("width", largura)
            .put("height", altura)
            .put("bytes", bytes.size.toLong())
            .put("mediaType", "image/png")
            .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
    }

    private fun bmpDe(reader: ImageReader): Bitmap? {
        val image: Image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val padding = rowStride - pixelStride * image.width
            // O buffer traz "padding" no fim de cada linha: copia-se largo e corta-se depois.
            val largo = Bitmap.createBitmap(
                image.width + padding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
            largo.copyPixelsFromBuffer(plane.buffer)
            val cortado = Bitmap.createBitmap(largo, 0, 0, image.width, image.height)
            largo.recycle()
            cortado
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "fotograma ilegível: ${t.message}")
            null
        } finally {
            runCatching { image.close() }
        }
    }

    /** Para a pré-visualização na app (S4): o último fotograma, sem serializar. */
    fun lastFrameBitmap(): Bitmap? = lastImage?.copy(Bitmap.Config.ARGB_8888, false)
}
