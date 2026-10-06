package com.trananh.rollingdoor.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trananh.rollingdoor.R
import com.trananh.rollingdoor.protocol.ButtonIcon
import com.trananh.rollingdoor.protocol.ButtonList
import com.trananh.rollingdoor.protocol.RemoteButton
import com.trananh.rollingdoor.ui.components.DoorPreviews
import com.trananh.rollingdoor.ui.components.ListGroup
import com.trananh.rollingdoor.ui.components.ListRowAction
import com.trananh.rollingdoor.ui.components.ListRowStyle
import com.trananh.rollingdoor.ui.components.PrimaryButton
import com.trananh.rollingdoor.ui.components.SecondaryButton
import com.trananh.rollingdoor.ui.components.TextInput
import com.trananh.rollingdoor.ui.theme.DoorTheme

// Admin only, in Settings: the board's buttons, each opening its own page, and Add button.
//   learned: ids the board has a code for; null while not connected
@Composable
internal fun ButtonsPage(
    buttons: ButtonList,
    learned: Set<Int>?,
    onOpen: (Int) -> Unit,
    onAdd: () -> Unit,
    onDone: () -> Unit,
) {
    val yes = stringResource(R.string.learn_learned)
    val no = stringResource(R.string.learn_not_learned)
    val names = buttons.buttons.map { it.displayName() }
    val add = stringResource(R.string.buttons_add)
    val footer = stringResource(if (buttons.isFull) R.string.buttons_full_footer else R.string.buttons_footer)
    ListGroup(footer = footer) {
        buttons.buttons.forEachIndexed { index, button ->
            row(
                names[index],
                icon = button.icon.vector,
                value = learned?.let { if (button.id in it) yes else no },
                chevron = true,
                onClick = { onOpen(button.id) },
            )
        }
        if (!buttons.isFull) row(add, icon = Icons.Rounded.AddCircleOutline, onClick = onAdd)
    }
    SecondaryButton(stringResource(R.string.buttons_done), onDone, Modifier.fillMaxWidth())
}

// One button: name, icon, its remote code and Delete. A new button (saved == null) has to be
// saved before its code can be learned, since the board only learns codes for its own buttons.
//   saved: the board's copy of this button
//   canEdit: connected and no command in flight
@Composable
internal fun EditButtonPage(
    id: Int,
    saved: RemoteButton?,
    newIcon: ButtonIcon,
    learned: Set<Int>?,
    learning: RfLearning,
    editing: ButtonEditing,
    canEdit: Boolean,
    onSave: (RemoteButton) -> Unit,
    onLearn: (Int) -> Unit,
    onClear: (Int) -> Unit,
    onCancelLearn: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    var name by rememberSaveable(id) { mutableStateOf(saved?.name ?: "") }
    var icon by rememberSaveable(id) { mutableStateOf(saved?.icon ?: newIcon) }
    val draft = RemoteButton(id, icon, name.trim())
    val saving = editing.saving == id
    val listening = learning.listening == id

    Section(stringResource(R.string.edit_name), stringResource(R.string.edit_name_footer)) {
        TextInput(
            value = name,
            onValueChange = { name = ButtonList.fitName(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(icon.label),
        )
    }
    Section(stringResource(R.string.edit_icon)) {
        IconPicker(icon, onPick = { icon = it })
    }
    Column(verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.xs)) {
        PrimaryButton(
            stringResource(R.string.edit_save),
            onClick = { onSave(draft) },
            modifier = Modifier.fillMaxWidth(),
            enabled = canEdit && draft != saved,
            loading = saving,
        )
        if (editing.result?.button == id && editing.result.outcome == EditOutcome.SaveFailed) {
            Footnote(stringResource(R.string.edit_save_failed), DoorTheme.colors.red)
        }
    }

    if (saved == null) {
        Footnote(stringResource(R.string.edit_save_first))
    } else {
        val isLearned = learned != null && id in learned
        val clearing = learning.clearing == id
        val learnLabel = stringResource(R.string.learn_start)
        val clearLabel = stringResource(R.string.learn_clear)
        val value = when {
            listening || learned == null -> null
            isLearned -> stringResource(R.string.learn_learned)
            else -> stringResource(R.string.learn_not_learned)
        }
        ListGroup(header = stringResource(R.string.learn_header), footer = learnFooter(id, learning)) {
            row(
                learnLabel,
                icon = Icons.Rounded.SettingsRemote,
                value = value,
                loading = listening,
                enabled = canEdit || listening || clearing,
                action = if (!isLearned || listening) null else ListRowAction(
                    Icons.Rounded.Delete,
                    clearLabel,
                    style = ListRowStyle.Destructive,
                    loading = clearing,
                    enabled = canEdit,
                    onClick = { onClear(id) },
                ),
                onClick = if (listening || clearing) null else ({ onLearn(id) }),
            )
        }
        val delete = stringResource(R.string.edit_delete)
        ListGroup {
            row(delete, icon = Icons.Rounded.Delete, style = ListRowStyle.Destructive, enabled = canEdit, onClick = onDelete)
        }
    }

    if (listening) {
        SecondaryButton(stringResource(R.string.settings_cancel), onCancelLearn, Modifier.fillMaxWidth())
    } else {
        SecondaryButton(stringResource(R.string.buttons_done), onDone, Modifier.fillMaxWidth())
    }
}

// Asked before deleting: the learned code goes too, and learning it again needs the remote.
@Composable
internal fun DeleteButtonPage(
    name: String,
    deleting: Boolean,
    failed: EditOutcome?,
    canEdit: Boolean,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        stringResource(R.string.delete_body, name),
        Modifier.fillMaxWidth(),
        style = DoorTheme.type.body,
        color = DoorTheme.colors.secondaryLabel,
        textAlign = TextAlign.Center,
    )
    val delete = stringResource(R.string.edit_delete)
    val footer = when (failed) {
        EditOutcome.Busy -> stringResource(R.string.learn_result_busy)
        EditOutcome.DeleteFailed -> stringResource(R.string.delete_failed)
        else -> null
    }
    ListGroup(footer = footer) {
        row(
            delete,
            icon = Icons.Rounded.Delete,
            style = ListRowStyle.Destructive,
            loading = deleting,
            enabled = canEdit || deleting,
            onClick = onDelete,
        )
    }
    SecondaryButton(stringResource(R.string.settings_cancel), onCancel, Modifier.fillMaxWidth(), enabled = !deleting)
}

// What to do now, or how the last attempt for this button ended.
@Composable
private fun learnFooter(id: Int, learning: RfLearning): String {
    if (learning.listening == id) return stringResource(R.string.learn_listening)
    val result = learning.result?.takeIf { it.button == id } ?: return stringResource(R.string.learn_hint)
    return stringResource(
        when (result.outcome) {
            LearnOutcome.Learned -> R.string.learn_result_learned
            LearnOutcome.NotHeard -> R.string.learn_result_not_heard
            LearnOutcome.Busy -> R.string.learn_result_busy
            LearnOutcome.Failed -> R.string.learn_result_failed
            LearnOutcome.Cleared -> R.string.learn_result_cleared
            LearnOutcome.ClearFailed -> R.string.learn_result_clear_failed
        },
    )
}

// Every icon in its own color; the chosen one has an accent ring. Read by TalkBack as a radio
// group of the icons' names.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconPicker(selected: ButtonIcon, onPick: (ButtonIcon) -> Unit) {
    val colors = DoorTheme.colors
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DoorTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.s),
    ) {
        for (icon in ButtonIcon.entries) {
            val label = stringResource(icon.label)
            val isSelected = icon == selected
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .border(3.dp, if (isSelected) colors.accent else Color.Transparent, CircleShape)
                    .selectable(isSelected, role = Role.RadioButton, onClick = { onPick(icon) })
                    .semantics { contentDescription = label }
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(icon.color(colors)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon.vector, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

// A header above some content and an optional note below, spaced like ListGroup's.
@Composable
private fun Section(header: String, footer: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.xs)) {
        Text(
            header.uppercase(),
            Modifier.padding(horizontal = DoorTheme.spacing.m),
            style = DoorTheme.type.footnote,
            color = DoorTheme.colors.secondaryLabel,
        )
        content()
        if (footer != null) Footnote(footer)
    }
}

@Composable
private fun Footnote(text: String, color: Color = DoorTheme.colors.secondaryLabel) {
    Text(
        text,
        Modifier.padding(horizontal = DoorTheme.spacing.m),
        style = DoorTheme.type.footnote,
        color = color,
    )
}

private val PreviewButtons = ButtonList(
    revision = 0,
    buttons = ButtonList.DEFAULT.buttons + RemoteButton(5, ButtonIcon.Light, "Đèn sân"),
)

@DoorPreviews
@Composable
private fun ButtonsPagePreview() = SheetPreview {
    ButtonsPage(PreviewButtons, learned = setOf(1, 2, 3), onOpen = {}, onAdd = {}, onDone = {})
}

@DoorPreviews
@Composable
private fun EditButtonPreview() = SheetPreview {
    EditButtonPage(
        id = 5,
        saved = PreviewButtons[5],
        newIcon = ButtonIcon.Gate,
        learned = setOf(5),
        learning = RfLearning(),
        editing = ButtonEditing(),
        canEdit = true,
        onSave = {}, onLearn = {}, onClear = {}, onCancelLearn = {}, onDelete = {}, onDone = {},
    )
}

@DoorPreviews
@Composable
private fun NewButtonPreview() = SheetPreview {
    EditButtonPage(
        id = 6,
        saved = null,
        newIcon = ButtonIcon.Gate,
        learned = emptySet(),
        learning = RfLearning(),
        editing = ButtonEditing(),
        canEdit = true,
        onSave = {}, onLearn = {}, onClear = {}, onCancelLearn = {}, onDelete = {}, onDone = {},
    )
}

@DoorPreviews
@Composable
private fun EditButtonListeningPreview() = SheetPreview {
    EditButtonPage(
        id = 1,
        saved = ButtonList.DEFAULT[1],
        newIcon = ButtonIcon.Gate,
        learned = emptySet(),
        learning = RfLearning(listening = 1),
        editing = ButtonEditing(),
        canEdit = false,
        onSave = {}, onLearn = {}, onClear = {}, onCancelLearn = {}, onDelete = {}, onDone = {},
    )
}

@DoorPreviews
@Composable
private fun DeleteButtonPreview() = SheetPreview {
    DeleteButtonPage("Đèn sân", deleting = false, failed = null, canEdit = true, onDelete = {}, onCancel = {})
}
