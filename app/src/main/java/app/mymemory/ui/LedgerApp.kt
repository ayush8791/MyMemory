@file:OptIn(ExperimentalMaterial3Api::class)

package app.mymemory.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mymemory.ledger.data.LedgerTxn

/** Everything the screens need from the Android side (pickers, permission prompts, OAuth, voice). */
class Actions(
    val pickModel: () -> Unit,
    val requestSms: () -> Unit,
    val connectGmail: () -> Unit,
    val voice: () -> Unit,
)

private val tabs = listOf("Ledger" to "₹", "Review" to "✓", "People" to "👥", "Settings" to "⚙")

@Composable
fun LedgerApp(vm: LedgerViewModel, actions: Actions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<LedgerTxn?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    val txns by vm.txns.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val smsGranted by vm.smsGranted.collectAsStateWithLifecycle()
    val gmailOn by vm.gmailOn.collectAsStateWithLifecycle()
    val modelLabel by vm.modelLabel.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // A spoken note arrives through the draft: reopen the dialog so the user can check it.
    LaunchedEffect(draft) { if (draft.isNotBlank()) showAdd = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, glyph) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            if (i == 1 && review.isNotEmpty()) BadgedBox(badge = { Badge { Text("${review.size}") } }) { Text(glyph) }
                            else Text(glyph)
                        },
                        label = { Text(label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 0 || tab == 2) FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add a note") }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> LedgerScreen(txns) { editing = it }
                1 -> ReviewScreen(review, onKind = { t, k -> vm.setKind(t, k) }, onConfirm = { vm.confirm(it) }, onEdit = { editing = it })
                2 -> PeopleScreen(people)
                else -> SettingsScreen(
                    SettingsState(modelLabel, smsGranted, gmailOn, pending, busy),
                    onPickModel = actions.pickModel,
                    onAllowSms = actions.requestSms,
                    onGmail = { on -> if (on) actions.connectGmail() else vm.setGmailEnabled(false) },
                    onSync = vm::syncNow,
                )
            }
        }
    }

    editing?.let { t ->
        ModalBottomSheet(onDismissRequest = { editing = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            EditSheet(t) { cat, kind, name, note ->
                vm.save(t, cat, kind, name, note)
                editing = null
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Note a transaction") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { vm.draft.value = it },
                        placeholder = { Text("lent 500 to Rahul for lunch") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = actions.voice) { Text("🎤 Speak instead") }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.quickAdd(draft); showAdd = false }, enabled = draft.isNotBlank() && !busy) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false; vm.draft.value = "" }) { Text("Cancel") } },
        )
    }
}
