@file:OptIn(ExperimentalMaterial3Api::class)

package app.mymemory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mymemory.ledger.Balances
import app.mymemory.ledger.PersonBalance
import app.mymemory.ledger.data.Category
import app.mymemory.ledger.data.Direction
import app.mymemory.ledger.data.LedgerTxn
import app.mymemory.ledger.data.TxnKind
import java.time.YearMonth

private val Green = Color(0xFF2E7D32)

private fun sources(t: LedgerTxn): String = t.channels.split(',')
    .map { it.substringBefore(':') }
    .distinct()
    .joinToString(" + ") { when (it) { "SMS" -> "SMS"; "GMAIL" -> "Mail"; "MANUAL" -> "Noted"; else -> it } }

// ---------------------------------------------------------------- Ledger

@Composable
fun LedgerScreen(txns: List<LedgerTxn>, onOpen: (LedgerTxn) -> Unit) {
    if (txns.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
            Text("Nothing here yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Open Settings: allow SMS, import the Gemma model, then Sync. Or tap + and say \"lent 500 to Rahul\".")
        }
        return
    }
    val summary = remember(txns) { Balances.month(txns, YearMonth.now()) }
    val groups = remember(txns) { txns.groupBy { Fmt.localDate(it.occurredAt) }.toList() }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Spent this month", style = MaterialTheme.typography.labelMedium)
                        Text(Fmt.rupees(summary.spentPaise), style = MaterialTheme.typography.headlineSmall)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Came in", style = MaterialTheme.typography.labelMedium)
                        Text(Fmt.rupees(summary.incomePaise), style = MaterialTheme.typography.headlineSmall, color = Green)
                    }
                }
            }
        }
        groups.forEach { (date, rows) ->
            item(key = "h$date") {
                Text(
                    Fmt.day(date), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(rows, key = { it.id }) { TxnRow(it, onOpen) }
        }
    }
}

@Composable
fun TxnRow(t: LedgerTxn, onOpen: (LedgerTxn) -> Unit) {
    val credit = t.dir == Direction.CREDIT
    ListItem(
        overlineContent = if (t.needsReview) ({ Text("Needs review") }) else null,
        headlineContent = {
            Text(t.counterparty ?: t.item ?: if (credit) "Money received" else "Payment", maxLines = 1)
        },
        supportingContent = {
            Text(
                listOfNotNull(
                    t.txnKind.takeIf { it != TxnKind.NORMAL && it != TxnKind.PERSON_TRANSFER }?.label ?: t.cat.label,
                    t.platform?.takeIf { !it.equals(t.counterparty, ignoreCase = true) },
                    t.item,
                    t.accountHint?.let { "•••• $it" },
                ).joinToString(" · "),
                maxLines = 2,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (credit) "+" else "−") + Fmt.rupees(t.amountPaise),
                    color = if (credit) Green else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(sources(t), style = MaterialTheme.typography.labelSmall)
            }
        },
        modifier = Modifier.clickable { onOpen(t) },
    )
}

// ---------------------------------------------------------------- Review

private fun reason(t: LedgerTxn) = when {
    t.txnKind == TxnKind.REPAYMENT_RECEIVED || t.txnKind == TxnKind.REPAYMENT_MADE -> "Looks like a repayment. Right?"
    t.txnKind == TxnKind.PERSON_TRANSFER ->
        if (t.dir == Direction.DEBIT) "You sent money to a person. What was it?" else "A person sent you money. What was it?"
    else -> "I'm not fully sure about this one."
}

@Composable
fun ReviewScreen(
    items: List<LedgerTxn>,
    onKind: (LedgerTxn, TxnKind) -> Unit,
    onConfirm: (LedgerTxn) -> Unit,
    onEdit: (LedgerTxn) -> Unit,
) {
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
            Text("All caught up", style = MaterialTheme.typography.titleLarge)
            Text("Anything the AI isn't sure about shows up here.")
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.id }) { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(bottom = 12.dp)) {
                    TxnRow(t, onEdit)
                    Text(reason(t), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val unexplained = t.txnKind == TxnKind.PERSON_TRANSFER
                        if (unexplained && t.dir == Direction.DEBIT) {
                            FilledTonalButton({ onKind(t, TxnKind.LENT) }) { Text("I lent it") }
                            OutlinedButton({ onKind(t, TxnKind.REPAYMENT_MADE) }) { Text("Paying back") }
                            TextButton({ onEdit(t) }) { Text("Something else") }
                        } else if (unexplained) {
                            FilledTonalButton({ onKind(t, TxnKind.BORROWED) }) { Text("I borrowed it") }
                            OutlinedButton({ onKind(t, TxnKind.REPAYMENT_RECEIVED) }) { Text("They repaid me") }
                            TextButton({ onEdit(t) }) { Text("Something else") }
                        } else {
                            FilledTonalButton({ onConfirm(t) }) { Text("Looks right") }
                            TextButton({ onEdit(t) }) { Text("Edit") }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- People

@Composable
fun PeopleScreen(people: List<PersonBalance>) {
    var open by remember { mutableStateOf<String?>(null) }
    if (people.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
            Text("No lending yet", style = MaterialTheme.typography.titleLarge)
            Text("Tap + and say \"lent 500 to Rahul for lunch\", or label a transfer in Review.")
        }
        return
    }
    val owedToMe = people.filter { it.netPaise > 0 }.sumOf { it.netPaise }
    val iOwe = people.filter { it.netPaise < 0 }.sumOf { -it.netPaise }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.padding(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Owed to you", style = MaterialTheme.typography.labelMedium)
                        Text(Fmt.rupees(owedToMe), style = MaterialTheme.typography.headlineSmall, color = Green)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("You owe", style = MaterialTheme.typography.labelMedium)
                        Text(Fmt.rupees(iOwe), style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        items(people, key = { it.key }) { p ->
            ListItem(
                headlineContent = { Text(p.name) },
                supportingContent = {
                    Text(
                        when {
                            p.netPaise > 0 -> "owes you ${Fmt.rupees(p.netPaise)}"
                            p.netPaise < 0 -> "you owe ${Fmt.rupees(-p.netPaise)}"
                            else -> "settled"
                        }
                    )
                },
                modifier = Modifier.clickable { open = if (open == p.key) null else p.key },
            )
            if (open == p.key) {
                Column(Modifier.padding(start = 32.dp, end = 16.dp, bottom = 8.dp)) {
                    p.entries.forEach { e ->
                        Text(
                            "${Fmt.day(Fmt.localDate(e.occurredAt))} · ${e.txnKind.label} ${Fmt.rupees(e.amountPaise)}" +
                                listOfNotNull(e.item, e.note).joinToString("") { " · $it" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

// ---------------------------------------------------------------- Settings

data class SettingsState(
    val modelLabel: String,
    val smsGranted: Boolean,
    val gmailOn: Boolean,
    val pending: Int,
    val busy: Boolean,
)

@Composable
fun SettingsScreen(
    s: SettingsState,
    onPickModel: () -> Unit,
    onAllowSms: () -> Unit,
    onGmail: (Boolean) -> Unit,
    onSync: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("On-device AI (Gemma)", s.modelLabel) {
            Text(
                "Download a Gemma .litertlm file from the LiteRT Community on Hugging Face " +
                    "(Gemma 3n E2B or Gemma 4 E2B for recent phones, Gemma 3 1B for older ones), then pick it here. " +
                    "It never leaves your phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onPickModel, enabled = !s.busy) { Text(if (s.busy) "Working…" else "Import model file") }
        }
        Section("Text messages", if (s.smsGranted) "Allowed" else "Not allowed") {
            if (!s.smsGranted) Button(onAllowSms) { Text("Allow SMS access") }
            else Text("Only messages that look like money movement are saved.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Gmail", if (s.gmailOn) "Connected (read-only)" else "Not connected") {
            if (s.gmailOn) OutlinedButton({ onGmail(false) }) { Text("Disconnect") }
            else Button({ onGmail(true) }) { Text("Connect Gmail") }
        }
        Section("Sync", if (s.pending > 0) "${s.pending} messages waiting for the AI" else "Up to date") {
            Button(onSync) { Text("Sync now") }
        }
        Text(
            "Everything is read and understood on this phone. Nothing is sent to any server.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Section(title: String, status: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

// ---------------------------------------------------------------- Edit sheet content

@Composable
fun EditSheet(t: LedgerTxn, onSave: (Category, TxnKind, String, String) -> Unit) {
    var name by remember(t.id) { mutableStateOf(t.counterparty.orEmpty()) }
    var note by remember(t.id) { mutableStateOf(t.note.orEmpty()) }
    var cat by remember(t.id) { mutableStateOf(t.cat) }
    var kind by remember(t.id) { mutableStateOf(if (t.txnKind == TxnKind.PERSON_TRANSFER) TxnKind.NORMAL else t.txnKind) }
    val kinds = if (t.dir == Direction.DEBIT)
        listOf(TxnKind.NORMAL, TxnKind.LENT, TxnKind.REPAYMENT_MADE)
    else
        listOf(TxnKind.NORMAL, TxnKind.BORROWED, TxnKind.REPAYMENT_RECEIVED, TxnKind.REFUND)
    val needsName = kind.isPeopleLedger && name.isBlank()

    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Fmt.rupees(t.amountPaise) + if (t.dir == Direction.DEBIT) " paid" else " received", style = MaterialTheme.typography.titleLarge)
        Text(
            listOfNotNull(t.instrument.replace('_', ' ').lowercase(), t.referenceId?.let { "ref $it" }, sources(t)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(name, { name = it }, label = { Text("Who / where") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("What is it?", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(kinds) { k -> FilterChip(kind == k, { kind = k }, label = { Text(k.label) }) }
        }
        if (kind == TxnKind.NORMAL || kind == TxnKind.REFUND) {
            Text("Category", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Category.entries.filter { it != Category.PEOPLE }) { c ->
                    FilterChip(cat == c, { cat = c }, label = { Text(c.label) })
                }
            }
        }
        OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
        Button({ onSave(cat, kind, name, note) }, enabled = !needsName, modifier = Modifier.fillMaxWidth()) {
            Text(if (needsName) "Add a name first" else "Save")
        }
    }
}
