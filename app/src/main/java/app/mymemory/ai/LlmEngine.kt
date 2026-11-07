package app.mymemory.ai

import android.content.Context
import app.mymemory.Prefs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the app needs from a language model. Swap the implementation without touching callers. */
interface LlmEngine {
    suspend fun isReady(): Boolean
    suspend fun generate(prompt: String): String
    /** Drop the loaded model (call after importing a different model file). */
    suspend fun invalidate()
}

/**
 * Runs Gemma fully on-device through LiteRT-LM.
 * One shared engine (loading takes a few seconds), a fresh conversation per message so that
 * one SMS can never leak into the next, and a mutex because the engine is not re-entrant.
 */
class LiteRtLlmEngine(
    private val context: Context,
    private val prefs: Prefs,
) : LlmEngine {

    private val lock = Mutex()
    private var engine: Engine? = null

    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        prefs.modelPath?.let { File(it).exists() } == true
    }

    override suspend fun generate(prompt: String): String = lock.withLock {
        withContext(Dispatchers.Default) {
            val e = engine ?: load().also { engine = it }
            e.createConversation().use { conversation ->
                // If your LiteRT-LM version exposes the reply text differently
                // (e.g. a `text`/`contents` accessor), this is the only line to change.
                conversation.sendMessage(prompt).toString()
            }
        }
    }

    override suspend fun invalidate() = lock.withLock {
        runCatching { engine?.close() }
        engine = null
    }

    /** GPU first (much faster on recent phones), CPU if the GPU backend cannot start. */
    private fun load(): Engine {
        val path = requireNotNull(prefs.modelPath) { "No Gemma model imported" }
        var last: Throwable? = null
        for (backend in listOf<Backend>(Backend.GPU(), Backend.CPU())) {
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = path,
                        backend = backend,
                        cacheDir = context.cacheDir.path,
                    )
                )
                e.initialize()
                return e
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException("Could not start the Gemma model", last)
    }
}
