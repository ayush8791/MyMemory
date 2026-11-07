package app.mymemory.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mymemory.MyMemoryApp
import app.mymemory.ai.ModelStore
import app.mymemory.ledger.Balances
import app.mymemory.ledger.PersonBalance
import app.mymemory.ledger.data.Category
import app.mymemory.ledger.data.LedgerTxn
import app.mymemory.ledger.data.MerchantRule
import app.mymemory.ledger.data.PartyKind
import app.mymemory.ledger.data.TxnKind
import app.mymemory.ledger.ingest.SyncWorker
import app.mymemory.ledger.parse.Names
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class LedgerViewModel(private val app: Application) : AndroidViewModel(app) {
    private val c = MyMemoryApp.container(app)

    val txns: StateFlow<List<LedgerTxn>> = c.dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val review: StateFlow<List<LedgerTxn>> = c.dao.review().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val people: StateFlow<List<PersonBalance>> = txns.map { Balances.people(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pending: StateFlow<Int> = c.dao.pendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val draft = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val smsGranted = MutableStateFlow(c.ingest.smsPermitted())
    val gmailOn = MutableStateFlow(c.prefs.gmailEnabled)
    val modelLabel = MutableStateFlow(currentModelLabel())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun toast(msg: String) { _messages.tryEmit(msg) }

    private fun currentModelLabel(): String =
        c.prefs.modelPath?.let { File(it) }?.takeIf { it.exists() }
            ?.let { "Ready (${it.length() / 1_000_000} MB)" } ?: "Not imported yet"

    fun refreshPermissions() { smsGranted.value = c.ingest.smsPermitted() }

    fun setGmailEnabled(on: Boolean) {
        c.prefs.gmailEnabled = on
        gmailOn.value = on
        if (on) { toast("Gmail connected"); SyncWorker.syncNow(app) }
    }

    fun syncNow() { SyncWorker.syncNow(app); toast("Sync started") }

    fun importModel(uri: Uri) = viewModelScope.launch {
        busy.value = true
        toast("Importing model. This can take a minute.")
        try {
            ModelStore.import(app, c.prefs, uri)
            c.llm.invalidate()
            modelLabel.value = currentModelLabel()
            toast("Model ready")
            SyncWorker.syncNow(app)
        } catch (e: Exception) {
            toast(e.message ?: "Could not import the model")
        } finally {
            busy.value = false
        }
    }

    fun quickAdd(text: String) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        busy.value = true
        val r = c.processor.addManual(text)
        busy.value = false
        toast(r.message)
        if (r.ok) draft.value = ""
    }

    /** Full edit from the sheet. Also teaches the app: same merchant -> same category from now on. */
    fun save(t: LedgerTxn, cat: Category, kind: TxnKind, name: String, note: String) = viewModelScope.launch {
        val cleaned = name.trim().ifBlank { null }
        val key = if (cleaned == t.counterparty) t.counterpartyKey else Names.key(cleaned)
        val category = when {
            kind.isPeopleLedger || kind == TxnKind.PERSON_TRANSFER -> Category.PEOPLE
            cat == Category.PEOPLE -> Category.OTHER
            else -> cat
        }
        c.dao.updateTxn(
            t.copy(
                category = category.name, kind = kind.name, counterparty = cleaned, counterpartyKey = key,
                note = note.trim().ifBlank { null }, needsReview = false, userEdited = true, confidence = 1f,
            )
        )
        if (key != null && kind == TxnKind.NORMAL && t.party != PartyKind.PERSON) {
            c.dao.putRule(MerchantRule(key, category.name))
            c.dao.recategorise(key, category.name)
        }
    }

    /** One-tap answers from the review queue. */
    fun setKind(t: LedgerTxn, kind: TxnKind) = viewModelScope.launch {
        c.dao.updateTxn(t.copy(kind = kind.name, category = Category.PEOPLE.name, needsReview = false, userEdited = true, confidence = 1f))
    }

    fun confirm(t: LedgerTxn) = viewModelScope.launch {
        c.dao.updateTxn(t.copy(needsReview = false, userEdited = true, confidence = 1f))
    }
}
