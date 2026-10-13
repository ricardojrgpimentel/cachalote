package dev.ricardopimentel.cachalote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.Executors

/**
 * "Olhos e dedos" do agente: lê a árvore de controlos do ecrã, toca, escreve,
 * faz scroll e captura o ecrã. Só funciona depois de o utilizador ligar este
 * serviço em Definições → Acessibilidade (a app guia-o).
 *
 * Não guarda nada e não decide nada: a autorização por capacidade é do
 * [HostCapabilities] (separador "Capacidades"), e cada uso fica no registo.
 */
class CachaloteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CachaloteAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    private val io = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    /** Nó raiz da janela activa. */
    fun root(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (_: Throwable) {
        null
    }

    /** Nome do pacote em primeiro plano (para o agente saber onde está). */
    fun foregroundPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (_: Throwable) {
        null
    }

    /**
     * Descreve o ecrã como lista de elementos, em largura. Inclui texto, descrição,
     * classe, limites, e se é clicável/editável/focável — o suficiente para o agente
     * decidir onde tocar sem precisar de imagem.
     */
    fun describe(filter: String?, max: Int): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val rootNode = root() ?: return out
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(rootNode to 0)
        var visited = 0
        while (queue.isNotEmpty() && out.size < max && visited < 4000) {
            val (node, depth) = queue.removeFirst()
            visited++
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val interesting = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable
            if (interesting && depth > 0) {
                val matches = filter.isNullOrBlank() ||
                    text.contains(filter, ignoreCase = true) || desc.contains(filter, ignoreCase = true)
                if (matches) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    out.add(
                        mapOf(
                            "text" to text.ifEmpty { null },
                            "description" to desc.ifEmpty { null },
                            "class" to node.className?.toString()?.substringAfterLast('.'),
                            "bounds" to "${r.left},${r.top},${r.right},${r.bottom}",
                            "center" to "${(r.left + r.right) / 2},${(r.top + r.bottom) / 2}",
                            "clickable" to node.isClickable,
                            "editable" to node.isEditable,
                            "focused" to node.isFocused,
                            "enabled" to node.isEnabled,
                            "id" to node.viewIdResourceName?.substringAfterLast('/'),
                        ).filterValues { it != null },
                    )
                }
            }
            if (depth < 40) {
                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
                    queue.add(child to depth + 1)
                }
            }
        }
        return out
    }

    /** Encontra o primeiro nó cujo texto (exacto e depois parcial) ou descrição coincide. */
    fun findNode(text: String?, description: String?): AccessibilityNodeInfo? {
        val rootNode = root() ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var partial: AccessibilityNodeInfo? = null
        var visited = 0
        while (queue.isNotEmpty() && visited < 4000) {
            val node = queue.removeFirst()
            visited++
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (text != null) {
                if (t == text || d == text) return node
                if (partial == null && (t.contains(text, true) || d.contains(text, true))) partial = node
            } else if (description != null && (d == description || d.contains(description, true))) {
                return node
            }
            for (i in 0 until node.childCount) {
                try { node.getChild(i)?.let { queue.add(it) } } catch (_: Throwable) {}
            }
        }
        return partial
    }

    /** Clica num nó (subindo ao primeiro antepassado clicável, como faz um humano). */
    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        var cur = node ?: return false
        var hops = 0
        while (hops < 8) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent ?: return false
            hops++
        }
        return false
    }

    /** Gesto de toque simples em coordenadas absolutas. */
    fun tap(x: Float, y: Float, done: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) = done(true)
            override fun onCancelled(d: GestureDescription?) = done(false)
        }, null)
    }

    /** Escreve no campo focado (ou no primeiro editável) via ACTION_SET_TEXT. */
    fun typeText(text: String, submit: Boolean): Boolean {
        val focused = root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focused ?: findNode(null, null)?.let { firstEditable() }
        val node = if (target?.isEditable == true) target else firstEditable()
        if (node == null) return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        if (submit && Build.VERSION.SDK_INT >= 30) {
            // O Enter do IME é uma AccessibilityAction (não uma constante de primeiro nível).
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return true
    }

    private fun firstEditable(): AccessibilityNodeInfo? {
        val rootNode = root() ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var visited = 0
        while (queue.isNotEmpty() && visited < 4000) {
            val node = queue.removeFirst()
            visited++
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                try { node.getChild(i)?.let { queue.add(it) } } catch (_: Throwable) {}
            }
        }
        return null
    }

    /** Teclas/gestos de sistema. */
    fun pressKey(key: String): Boolean = when (key) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "enter" -> root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.let {
                if (Build.VERSION.SDK_INT >= 30) {
                    it.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else false
            } ?: false
        else -> false
    }

    /** Captura o ecrã (API 30+) para PNG no armazenamento externo privado da app. */
    fun screenshot(): Map<String, Any?> {
        val dir = getExternalFilesDir("shots") ?: filesDir
        if (!dir.isDirectory) dir.mkdirs()
        val file = File(dir, "shot-${System.currentTimeMillis()}.png")
        if (Build.VERSION.SDK_INT < 30) throw IllegalStateException("captura de ecrã pelo serviço exige Android 11+")
        val latch = java.util.concurrent.CountDownLatch(1)
        var error: String? = null
        var size = 0
        takeScreenshot(Display.DEFAULT_DISPLAY, io, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val copy = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                    bmp?.recycle()
                    result.hardwareBuffer.close()
                    if (copy != null) {
                        file.outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        size = copy.width
                        copy.recycle()
                    }
                } catch (t: Throwable) {
                    error = t.message
                } finally {
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                error = "takeScreenshot falhou (código $errorCode)"
                latch.countDown()
            }
        })
        if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) throw IllegalStateException("captura de ecrã expirou")
        if (error != null) throw IllegalStateException(error)
        return mapOf(
            "path" to file.absolutePath,
            "bytes" to file.length(),
            "width" to size,
        )
    }
}
