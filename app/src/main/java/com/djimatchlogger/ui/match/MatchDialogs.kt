package com.djimatchlogger.ui.match

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.djimatchlogger.R
import com.djimatchlogger.match.Match
import com.djimatchlogger.match.MatchMeta
import com.djimatchlogger.ui.theme.MatchPalette

/** How many known names to offer before the row gets in the way. */
private const val MAX_SUGGESTIONS = 12

/** The largest number of periods the setup screen offers. */
private const val MAX_PERIODS = 6

/**
 * "Before kickoff": team names, how many periods, and whether cards are
 * tracked at all.
 *
 * A full screen rather than a dialog. It gets filled
 * in once, standing beside a pitch, and the period buttons want to be big
 * enough to hit without looking.
 */
@Composable
fun SetupScreen(
    meta: MatchMeta,
    onDismiss: () -> Unit,
    onSave: (MatchMeta) -> Unit,
    knownTeams: List<String> = emptyList(),
) {
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var home by remember { mutableStateOf(meta.home) }
    var away by remember { mutableStateOf(meta.away) }
    var periods by remember { mutableIntStateOf(meta.periods) }
    var cards by remember { mutableStateOf(meta.cardsEnabled) }

    Dialog(
        onDismissRequest = onDismiss,
        // A dialog window does not inherit the activity's adjustResize, and
        // with the decor fitting system windows it pans instead of resizing —
        // which puts the fields under the keyboard with no way to scroll them
        // out. Off, the dialog is edge to edge and imePadding() below works.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MatchPalette.Page)
                .windowInsetsPadding(WindowInsets.systemBars)
                // The keyboard takes more than half a landscape screen. Without
                // this the fields, and the suggestion list under them, sit
                // behind it with no way to scroll them out.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = MatchPalette.Muted,
                    )
                }
                
                Text(
                    text = stringResource(R.string.setup_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MatchPalette.PanelTitle,
                    modifier = Modifier.align(Alignment.Center)
                )

                Button(
                    onClick = {
                        onSave(
                            meta.copy(
                                home = home.trim(),
                                away = away.trim(),
                                periods = periods,
                                cardsEnabled = cards,
                            ),
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MatchPalette.Go),
                    modifier = Modifier
                        .height(36.dp)
                        .align(Alignment.CenterEnd),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                ) {
                    Text(stringResource(R.string.action_ok), fontWeight = FontWeight.Bold)
                }
            }

            // Three panels side by side where there is width for it, stacked
            // where there is not — the same three either way.
            val homePanel = @Composable { m: Modifier ->
                SetupPanel(stringResource(R.string.match_setup_home), m) {
                    TeamField(
                        value = home,
                        onValueChange = { home = it },
                        suggestions = knownTeams,
                    )
                }
            }
            val awayPanel = @Composable { m: Modifier ->
                SetupPanel(stringResource(R.string.match_setup_away), m) {
                    TeamField(
                        value = away,
                        onValueChange = { away = it },
                        suggestions = knownTeams,
                    )
                }
            }
            val periodsPanel = @Composable { m: Modifier ->
                SetupPanel(stringResource(R.string.setup_periods), m) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = periods.toString(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MatchPalette.Text,
                            modifier = Modifier.width(32.dp),
                            textAlign = TextAlign.Center
                        )
                        
                        Column {
                            IconButton(
                                onClick = { if (periods < MAX_PERIODS) periods++ },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("▲", color = MatchPalette.Home, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = { if (periods > 1) periods-- },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("▼", color = MatchPalette.Home, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            val cardsPanel = @Composable { m: Modifier ->
                SetupPanel(stringResource(R.string.setup_track_cards), m) {
                    Row(
                        modifier = Modifier.toggleable(
                            value = cards,
                            onValueChange = { cards = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = cards,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = MatchPalette.Home),
                        )
                        Text(
                            text = stringResource(R.string.setup_track_cards),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MatchPalette.Text,
                        )
                    }
                }
            }

            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    homePanel(Modifier.weight(1f))
                    awayPanel(Modifier.weight(1f))
                    Column(
                        modifier = Modifier.weight(0.7f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        periodsPanel(Modifier.fillMaxWidth())
                        cardsPanel(Modifier.fillMaxWidth())
                    }
                }
            } else {
                homePanel(Modifier.fillMaxWidth())
                awayPanel(Modifier.fillMaxWidth())
                periodsPanel(Modifier.fillMaxWidth())
                cardsPanel(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PeriodButton(number: Int, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MatchPalette.Home else MatchPalette.Page,
            contentColor = MatchPalette.Text,
        ),
        contentPadding = CompactButtonPadding,
        modifier = Modifier.size(42.dp),
    ) {
        Text(number.toString(), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SetupPanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .background(MatchPalette.Panel, RoundedCornerShape(10.dp))
            .border(1.dp, MatchPalette.PanelBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MatchPalette.PanelTitle,
        )
        content()
    }
}

/**
 * A team name: type it, or tap one already known.
 *
 * Not a dropdown, which is what this started as. In landscape the keyboard
 * leaves about 150dp of screen, and a menu that opens under the field has
 * nowhere to appear — it simply never showed. These sit in the page, above the
 * keyboard, visible before anything is tapped, and one tap fills the field
 * without a keyboard ever opening.
 *
 * The list is what `teams.json` knows and will never be every club, so the
 * field stays a plain text field; what gets typed goes into the file after.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamField(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
) {
    val focusManager = LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }
    
    val matches = remember(value, suggestions) {
        if (value.isBlank()) {
            suggestions
        } else {
            suggestions.filter { it.contains(value.trim(), ignoreCase = true) }
        }
    }

    Box {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = "Show teams",
                        tint = MatchPalette.Muted
                    )
                }
            }
        )
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(MatchPalette.Panel)
                .fillMaxWidth(0.5f) // Limit width in landscape
        ) {
            if (suggestions.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No teams saved", color = MatchPalette.Muted) },
                    onClick = { expanded = false }
                )
            } else {
                suggestions.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, color = MatchPalette.Text) },
                        onClick = {
                            onValueChange(name)
                            expanded = false
                            focusManager.clearFocus()
                        }
                    )
                }
            }
        }
    }

    if (matches.isNotEmpty() && value.isNotBlank() && !suggestions.contains(value)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            matches.take(MAX_SUGGESTIONS).forEach { name ->
                SuggestionChip(
                    onClick = {
                        onValueChange(name)
                        focusManager.clearFocus()
                    },
                    label = {
                        Text(text = name, style = MaterialTheme.typography.labelMedium)
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MatchPalette.Page,
                        labelColor = MatchPalette.Text,
                    ),
                )
            }
        }
    }
}

@Composable
fun NoteDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.match_note)) },
        text = {
            OutlinedTextField(
                value = text,
                // Cut it off here rather than in the model, so the operator sees
                // the limit being reached instead of losing the tail later.
                onValueChange = { text = it.take(Match.MAX_NOTE_LENGTH) },
                label = { Text(stringResource(R.string.match_note_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun ConfirmNewMatchDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.match_new),
        text = stringResource(R.string.match_new_confirm),
        confirmLabel = stringResource(R.string.match_new_confirm_action),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = MatchPalette.Danger,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
