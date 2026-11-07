package app.mymemory.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.mymemory.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gemma weights are large (0.5 - 3 GB) and licence-gated on Hugging Face, so the app does not
 * download them itself: you pick the `.litertlm` file you downloaded and it is copied into
 * private app storage.
 */
object ModelStore {
    suspend fun import(ctx: Context, prefs: Prefs, uri: Uri): String = withContext(Dispatchers.IO) {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "model"
        require(name.endsWith(".litertlm", ignoreCase = true)) {
            "Pick a .litertlm model file (found \"$name\")"
        }
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        val tmp = File(dir, "model.tmp")
        val input = requireNotNull(ctx.contentResolver.openInputStream(uri)) { "Cannot open file" }
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
        val final = File(dir, "model.litertlm")
        if (final.exists()) final.delete()
        check(tmp.renameTo(final)) { "Could not save the model" }
        prefs.modelPath = final.absolutePath
        "$name (${final.length() / 1_000_000} MB)"
    }
}
