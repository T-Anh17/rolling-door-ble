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
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.trananh.rollingdoor.protocol.ButtonIcon
import com.trananh.rollingdoor.protocol.ButtonList
import com.trananh.rollingdoor.protocol.PairedPhone
import com.trananh.rollingdoor.protocol.RemoteButton
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

// Admin's button editing and code learning, on their own pages of the sheet.
class ButtonActions(
    val buttons: ButtonList,
    val learned: Set<Int>?,
    val learning: RfLearning,
    val editing: ButtonEditing,
    val onSave: (RemoteButton) -> Unit,
    val onDelete: (Int) -> Unit,
    val onLearn: (Int) -> Unit,
    val onClear: (Int) -> Unit,
    val onCancelLearn: () -> Unit,
    val onLeave: () -> Unit, // forgets the last results
)

// The admin's phone list and Add phone, and Forget device, which asks the board to free this
// phone's slot.
class PhoneActions(
    val phones: PhonesState,
    val invite: InviteState,
    val leave: LeaveState?,
    val onInvite: () -> Unit,
    val onCloseInvite: () -> Unit,
    val onRename: (Int, String) -> Unit,
    val onRevoke: (Int) -> Unit,
    val onMakeAdmin: (Int) -> Unit,
    val onLeave: () -> Unit,
    val onForgetAnyway: () -> Unit,
)

private enum class SettingsPage { Main, Buttons, Edit, Delete, Phones, Phone, Invite, Revoke, MakeAdmin, Forget }

// Device info, admin actions, the Quick Settings tile and Forget device, kept off the control screen.
//   canAddPhone: connected and no command in flight (also gates editing and learning)
// Buttons, Phones, their pages and confirmations, and Forget device open in the same sheet.
@Composable
fun SettingsSheet(
    device: SavedDevice,
    canAddPhone: Boolean,
    buttons: ButtonActions,
    phones: PhoneActions,
    onDismiss: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    var editId by rememberSaveable { mutableStateOf(0) }
    var phoneId by rememberSaveable { mutableStateOf(0) }
    val phone = phones.phones.list?.get(phoneId)
    val quickTile = rememberQuickTileRow()
    val list = buttons.buttons
    val editing = list[editId]
    val goTo = { next: SettingsPage ->
        buttons.onLeave()
        page = next
    }
    // A deleted button has no page left to show.
    LaunchedEffect(buttons.editing.result) {
        if (buttons.editing.result?.outcome == EditOutcome.Deleted && page == SettingsPage.Delete) {
            goTo(SettingsPage.Buttons)
        }
    }
    // Nor does a revoked phone; and after handing over admin this phone has no admin pages.
    LaunchedEffect(phones.phones.result) {
        when (phones.phones.result?.outcome) {
            PhoneOutcome.Revoked -> if (page == SettingsPage.Revoke) page = SettingsPage.Phones
            PhoneOutcome.MadeAdmin -> if (page == SettingsPage.MakeAdmin) page = SettingsPage.Main
            else -> Unit
        }
    }
    val title = stringResource(
        when (page) {
            SettingsPage.Main -> R.string.settings_title
            SettingsPage.Buttons -> R.string.buttons_title
            SettingsPage.Edit -> if (editing == null) R.string.edit_title_new else R.string.edit_title
            SettingsPage.Delete -> R.string.delete_title
            SettingsPage.Phones -> R.string.phones_title
            SettingsPage.Phone -> R.string.phones_title
            SettingsPage.Invite -> R.string.settings_add_phone
            SettingsPage.Revoke -> R.string.revoke_title
            SettingsPage.MakeAdmin -> R.string.make_admin_title
            SettingsPage.Forget -> R.string.settings_forget_title
        },
    )
    BottomSheet(onDismiss = onDismiss, title = title, grouped = true) {
        when (page) {
            SettingsPage.Main -> SettingsGroups(
                device,
                onOpenPhones = { page = SettingsPage.Phones },
                onOpenButtons = { page = SettingsPage.Buttons },
                quickTile,
                onForget = { goTo(SettingsPage.Forget) },
            )
            SettingsPage.Buttons -> ButtonsPage(
                list,
                buttons.learned,
                onOpen = { id ->
                    editId = id
                    goTo(SettingsPage.Edit)
                },
                onAdd = {
                    editId = list.freeId() ?: return@ButtonsPage
                    goTo(SettingsPage.Edit)
                },
                onDone = { page = SettingsPage.Main },
            )
            SettingsPage.Edit -> EditButtonPage(
                editId,
                saved = editing,
                // A new button starts with an icon no other button uses.
                newIcon = ButtonIcon.entries.firstOrNull { icon -> list.buttons.none { it.icon == icon } }
                    ?: ButtonIcon.Power,
                buttons.learned,
                buttons.learning,
                buttons.editing,
                canEdit = canAddPhone,
                onSave = buttons.onSave,
                onLearn = buttons.onLearn,
                onClear = buttons.onClear,
                onCancelLearn = buttons.onCancelLearn,
                onDelete = { goTo(SettingsPage.Delete) },
                onDone = { goTo(SettingsPage.Buttons) },
            )
            SettingsPage.Delete -> DeleteButtonPage(
                name = editing?.displayName() ?: "",
                deleting = buttons.editing.deleting == editId,
                failed = buttons.editing.result?.takeIf { it.button == editId }?.outcome,
                canEdit = canAddPhone,
                onDelete = { buttons.onDelete(editId) },
                onCancel = { goTo(SettingsPage.Edit) },
            )
            SettingsPage.Phones -> PhonesPage(
                phones.phones,
                myKeyId = device.keyId,
                canAddPhone,
                onOpen = { id ->
                    phoneId = id
                    goTo(SettingsPage.Phone)
                },
                onAddPhone = {
                    goTo(SettingsPage.Invite)
                    phones.onInvite()
                },
                onDone = { goTo(SettingsPage.Main) },
            )
            SettingsPage.Invite -> InvitePage(
                phones.invite,
                onRetry = phones.onInvite,
                onDone = {
                    phones.onCloseInvite()
                    goTo(SettingsPage.Phones)
                },
            )
            SettingsPage.Phone -> if (phone == null) {
                // Gone from the list (revoked meanwhile): back to the list.
                LaunchedEffect(Unit) { page = SettingsPage.Phones }
            } else {
                PhonePage(
                    phone,
                    isMe = phone.keyId == device.keyId,
                    phones.phones,
                    canEdit = canAddPhone,
                    onSave = { phones.onRename(phone.keyId, it) },
                    onMakeAdmin = { goTo(SettingsPage.MakeAdmin) },
                    onRevoke = { goTo(SettingsPage.Revoke) },
                    onDone = { goTo(SettingsPage.Phones) },
                )
            }
            SettingsPage.Revoke -> ConfirmPhonePage(
                body = stringResource(R.string.revoke_body, phone.nameOrEmpty()),
                action = stringResource(R.string.phone_revoke),
                destructive = true,
                working = phones.phones.revoking == phoneId,
                failed = phones.phones.failedWith(phoneId, PhoneOutcome.RevokeFailed, R.string.revoke_failed),
                canEdit = canAddPhone,
                onConfirm = { phones.onRevoke(phoneId) },
                onCancel = { goTo(SettingsPage.Phone) },
            )
            SettingsPage.MakeAdmin -> ConfirmPhonePage(
                body = stringResource(R.string.make_admin_body, phone.nameOrEmpty()),
                action = stringResource(R.string.phone_make_admin),
                destructive = false,
                working = phones.phones.promoting == phoneId,
                failed = phones.phones.failedWith(phoneId, PhoneOutcome.MakeAdminFailed, R.string.make_admin_failed),
                canEdit = canAddPhone,
                onConfirm = { phones.onMakeAdmin(phoneId) },
                onCancel = { goTo(SettingsPage.Phone) },
            )
            SettingsPage.Forget -> ForgetConfirmation(
                phones.leave,
                onLeave = phones.onLeave,
                onForgetAnyway = phones.onForgetAnyway,
                onOpenPhones = { goTo(SettingsPage.Phones) },
                onCancel = { goTo(SettingsPage.Main) },
            )
        }
    }
}

@Composable
private fun PairedPhone?.nameOrEmpty(): String = this?.displayName() ?: ""

// The footer for a failed Revoke or Make admin of this phone, else none.
@Composable
private fun PhonesState.failedWith(keyId: Int, outcome: PhoneOutcome, text: Int): String? =
    if (result?.keyId == keyId && result.outcome == outcome) stringResource(text) else null

@Composable
private fun SettingsGroups(
    device: SavedDevice,
    onOpenPhones: () -> Unit,
    onOpenButtons: () -> Unit,
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
    // Admin actions share one card; Add phone is on the Phones page. Add watch is a placeholder
    // until the Wear OS app exists.
    if (device.role == Role.Admin) {
        val phones = stringResource(R.string.phones_title)
        val addWatch = stringResource(R.string.settings_add_watch)
        val comingSoon = stringResource(R.string.settings_coming_soon)
        val buttons = stringResource(R.string.buttons_title)
        ListGroup(header = stringResource(R.string.settings_section_admin)) {
            row(phones, icon = Icons.Rounded.Smartphone, chevron = true, onClick = onOpenPhones)
            row(addWatch, icon = Icons.Rounded.Watch, value = comingSoon)
            row(buttons, icon = Icons.Rounded.SettingsRemote, chevron = true, onClick = onOpenButtons)
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

// Forget asks the board to free this phone's slot first; the footer says why it did not.
// Offline: a normal phone may forget anyway. MustHandOver: the admin opens Phones from here.
@Composable
private fun ForgetConfirmation(
    leave: LeaveState?,
    onLeave: () -> Unit,
    onForgetAnyway: () -> Unit,
    onOpenPhones: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        stringResource(R.string.settings_forget_body),
        Modifier.fillMaxWidth(),
        style = DoorTheme.type.body,
        color = DoorTheme.colors.secondaryLabel,
        textAlign = TextAlign.Center,
    )
    val forget = stringResource(R.string.settings_forget)
    val anyway = stringResource(R.string.forget_anyway)
    val phones = stringResource(R.string.phones_title)
    val footer = when (leave) {
        LeaveState.MustHandOver -> stringResource(R.string.forget_must_hand_over)
        LeaveState.Offline -> stringResource(R.string.forget_offline)
        LeaveState.OfflineAdmin -> stringResource(R.string.forget_offline_admin)
        LeaveState.Failed -> stringResource(R.string.forget_failed)
        LeaveState.Leaving, null -> null
    }
    val leaving = leave == LeaveState.Leaving
    ListGroup(footer = footer) {
        when (leave) {
            LeaveState.Offline ->
                row(anyway, icon = Icons.Rounded.Delete, style = ListRowStyle.Destructive, onClick = onForgetAnyway)
            LeaveState.MustHandOver ->
                row(phones, icon = Icons.Rounded.Smartphone, chevron = true, onClick = onOpenPhones)
            else -> row(
                forget,
                icon = Icons.Rounded.Delete,
                style = ListRowStyle.Destructive,
                loading = leaving,
                enabled = !leaving,
                onClick = onLeave,
            )
        }
    }
    SecondaryButton(stringResource(R.string.settings_cancel), onCancel, Modifier.fillMaxWidth(), enabled = !leaving)
}

// The sheet body without the modal window, which previews cannot show.
@Composable
internal fun SheetPreview(content: @Composable ColumnScope.() -> Unit) = RollingDoorTheme {
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
        onOpenPhones = {},
        onOpenButtons = {},
        quickTile = QuickTileRow(added = false) {},
        onForget = {},
    )
}

@DoorPreviews
@Composable
private fun SettingsNormalPreview() = SheetPreview {
    SettingsGroups(PreviewDevice.copy(keyId = 1, role = Role.Normal), {}, {}, QuickTileRow(true) {}, {})
}

@DoorPreviews
@Composable
private fun SettingsForgetPreview() = SheetPreview { ForgetConfirmation(null, {}, {}, {}, {}) }

@DoorPreviews
@Composable
private fun SettingsForgetOfflinePreview() = SheetPreview { ForgetConfirmation(LeaveState.Offline, {}, {}, {}, {}) }
