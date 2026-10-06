package com.trananh.rollingdoor.ui.control

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.trananh.rollingdoor.R
import com.trananh.rollingdoor.data.SavedDevice
import com.trananh.rollingdoor.protocol.Role
import com.trananh.rollingdoor.tile.QuickTile
import com.trananh.rollingdoor.ui.components.BottomSheet
import com.trananh.rollingdoor.ui.components.DoorPreviews
import com.trananh.rollingdoor.ui.components.ListGroup
import com.trananh.rollingdoor.ui.components.ListRowStyle
import com.trananh.rollingdoor.ui.components.SecondaryButton
import com.trananh.rollingdoor.ui.theme.DoorTheme
import com.trananh.rollingdoor.ui.theme.ElevatedColors
import com.trananh.rollingdoor.ui.theme.RollingDoorTheme

// Device info, admin actions, the Quick Settings tile and Forget device, kept off the control screen.
//   canAddPhone: connected and no command in flight
//   addingPhone: Add phone is in flight
// Forget device asks first, in the same sheet.
@Composable
fun SettingsSheet(
    device: SavedDevice,
    canAddPhone: Boolean,
    addingPhone: Boolean,
    onAddPhone: () -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmingForget by rememberSaveable { mutableStateOf(false) }
    val quickTile = rememberQuickTileRow()
    val title = stringResource(if (confirmingForget) R.string.settings_forget_title else R.string.settings_title)
    BottomSheet(onDismiss = onDismiss, title = title, grouped = true) {
        if (confirmingForget) {
            ForgetConfirmation(onForget, onCancel = { confirmingForget = false })
        } else {
            SettingsGroups(
                device,
                canAddPhone,
                addingPhone,
                onAddPhone,
                quickTile,
                onForget = { confirmingForget = true },
            )
        }
    }
}

@Composable
private fun SettingsGroups(
    device: SavedDevice,
    canAddPhone: Boolean,
    addingPhone: Boolean,
    onAddPhone: () -> Unit,
    quickTile: QuickTileRow?,
    onForget: () -> Unit,
) {
    val address = stringResource(R.string.settings_address)
    val role = stringResource(R.string.settings_role)
    val admin = stringResource(R.string.settings_role_admin)
    // Only the admin phone shows its role; a normal phone has nothing to act on.
    ListGroup(header = stringResource(R.string.settings_section_device)) {
        row(address, value = device.mac)
        if (device.role == Role.Admin) row(role, value = admin)
    }
    if (device.role == Role.Admin) {
        val addPhone = stringResource(R.string.settings_add_phone)
        ListGroup(
            header = stringResource(R.string.settings_section_admin),
            footer = stringResource(R.string.settings_add_phone_footer),
        ) {
            row(
                addPhone,
                icon = Icons.Rounded.PersonAdd,
                loading = addingPhone,
                enabled = canAddPhone,
                onClick = onAddPhone,
            )
        }
    }
    if (quickTile != null) {
        val addTile = stringResource(R.string.settings_quick_tile)
        val added = stringResource(R.string.settings_quick_tile_added)
        ListGroup(footer = stringResource(R.string.settings_quick_tile_footer)) {
            if (quickTile.added) {
                row(addTile, icon = Icons.Rounded.GridView, value = added)
            } else {
                row(addTile, icon = Icons.Rounded.GridView, onClick = quickTile.onAdd)
            }
        }
    }
    val forget = stringResource(R.string.settings_forget)
    ListGroup {
        row(forget, icon = Icons.Rounded.Delete, style = ListRowStyle.Destructive, onClick = onForget)
    }
}

private class QuickTileRow(val added: Boolean, val onAdd: () -> Unit)

// Null below Android 13, where an app cannot ask to add its tile. Follows the tile's own
// added/removed record, so the row updates when the prompt is answered.
@Composable
private fun rememberQuickTileRow(): QuickTileRow? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val context = LocalContext.current
    var added by remember { mutableStateOf(QuickTile.isAdded(context)) }
    DisposableEffect(context) {
        val prefs = QuickTile.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            added = QuickTile.isAdded(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return QuickTileRow(added) { QuickTile.requestAdd(context) }
}

@Composable
private fun ForgetConfirmation(onForget: () -> Unit, onCancel: () -> Unit) {
    Text(
        stringResource(R.string.settings_forget_body),
        Modifier.fillMaxWidth(),
        style = DoorTheme.type.body,
        color = DoorTheme.colors.secondaryLabel,
        textAlign = TextAlign.Center,
    )
    val forget = stringResource(R.string.settings_forget)
    ListGroup {
        row(forget, icon = Icons.Rounded.Delete, style = ListRowStyle.Destructive, onClick = onForget)
    }
    SecondaryButton(stringResource(R.string.settings_cancel), onCancel, Modifier.fillMaxWidth())
}

// The sheet body without the modal window, which previews cannot show.
@Composable
private fun SheetPreview(content: @Composable ColumnScope.() -> Unit) = RollingDoorTheme {
    ElevatedColors {
        Column(
            Modifier
                .clip(DoorTheme.radius.sheet)
                .background(DoorTheme.colors.background)
                .padding(DoorTheme.spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.l),
            content = content,
        )
    }
}

// Fake address, not from a real board.
private val PreviewDevice = SavedDevice(mac = "00:11:22:33:44:55", keyId = 0, role = Role.Admin)

@DoorPreviews
@Composable
private fun SettingsAdminPreview() = SheetPreview {
    SettingsGroups(
        PreviewDevice,
        canAddPhone = true,
        addingPhone = false,
        onAddPhone = {},
        quickTile = QuickTileRow(added = false) {},
        onForget = {},
    )
}

@DoorPreviews
@Composable
private fun SettingsNormalPreview() = SheetPreview {
    SettingsGroups(PreviewDevice.copy(keyId = 1, role = Role.Normal), false, false, {}, QuickTileRow(true) {}, {})
}

@DoorPreviews
@Composable
private fun SettingsForgetPreview() = SheetPreview { ForgetConfirmation({}, {}) }
