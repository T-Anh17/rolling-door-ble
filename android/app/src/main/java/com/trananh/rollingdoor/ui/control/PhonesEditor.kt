package com.trananh.rollingdoor.ui.control

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.trananh.rollingdoor.R
import com.trananh.rollingdoor.protocol.ButtonList
import com.trananh.rollingdoor.protocol.DoorProtocol
import com.trananh.rollingdoor.protocol.InviteCode
import com.trananh.rollingdoor.protocol.PairedPhone
import com.trananh.rollingdoor.protocol.PhoneList
import com.trananh.rollingdoor.protocol.Role
import com.trananh.rollingdoor.ui.components.DoorPreviews
import com.trananh.rollingdoor.ui.components.ListGroup
import com.trananh.rollingdoor.ui.components.ListRowStyle
import com.trananh.rollingdoor.ui.components.PrimaryButton
import com.trananh.rollingdoor.ui.components.SecondaryButton
import com.trananh.rollingdoor.ui.components.TextInput
import com.trananh.rollingdoor.ui.theme.DoorTheme

// An empty name shows "Phone <n>", counting slots from 1 like the board's log.
@Composable
internal fun PairedPhone.displayName(): String =
    name.ifEmpty { stringResource(R.string.phones_default_name, keyId + 1) }

// Admin only, in Settings: the board's phones, each opening its own page, and Add phone.
//   myKeyId: this phone's slot, marked "This phone"
//   canAddPhone: connected and no command in flight
@Composable
internal fun PhonesPage(
    phones: PhonesState,
    myKeyId: Int,
    canAddPhone: Boolean,
    onOpen: (Int) -> Unit,
    onAddPhone: () -> Unit,
    onDone: () -> Unit,
) {
    val list = phones.list
    val admin = stringResource(R.string.settings_role_admin)
    val me = stringResource(R.string.phones_this_phone)
    val loading = stringResource(R.string.phones_loading)
    val names = list?.phones?.map { it.displayName() }.orEmpty()
    val full = list != null && list.phones.size >= DoorProtocol.SLOT_COUNT
    val footer = when {
        phones.loadFailed -> stringResource(R.string.phones_load_failed)
        full -> stringResource(R.string.phones_full_footer)
        else -> stringResource(R.string.phones_footer)
    }
    val addPhone = stringResource(R.string.settings_add_phone)
    ListGroup(footer = footer) {
        if (list == null) {
            row(loading, icon = Icons.Rounded.Smartphone, loading = phones.loading)
        } else {
            list.phones.forEachIndexed { index, phone ->
                val labels = listOfNotNull(admin.takeIf { phone.role == Role.Admin }, me.takeIf { phone.keyId == myKeyId })
                row(
                    names[index],
                    icon = Icons.Rounded.Smartphone,
                    value = labels.joinToString(" · ").ifEmpty { null },
                    chevron = true,
                    onClick = { onOpen(phone.keyId) },
                )
            }
        }
        if (!full) {
            row(addPhone, icon = Icons.Rounded.PersonAdd, enabled = canAddPhone, onClick = onAddPhone)
        }
    }
    SecondaryButton(stringResource(R.string.buttons_done), onDone, Modifier.fillMaxWidth())
}

// One phone: its name, and for another phone Make admin and Revoke, each asked again first.
//   canEdit: connected and no command in flight
@Composable
internal fun PhonePage(
    phone: PairedPhone,
    isMe: Boolean,
    phones: PhonesState,
    canEdit: Boolean,
    onSave: (String) -> Unit,
    onMakeAdmin: () -> Unit,
    onRevoke: () -> Unit,
    onDone: () -> Unit,
) {
    var name by rememberSaveable(phone.keyId) { mutableStateOf(phone.name) }
    val draft = name.trim()
    val fallback = stringResource(R.string.phones_default_name, phone.keyId + 1)

    Column(verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.xs)) {
        Text(
            stringResource(R.string.edit_name).uppercase(),
            Modifier.padding(horizontal = DoorTheme.spacing.m),
            style = DoorTheme.type.footnote,
            color = DoorTheme.colors.secondaryLabel,
        )
        TextInput(
            value = name,
            onValueChange = { name = ButtonList.fitName(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = fallback,
        )
        PhoneNote(stringResource(R.string.phone_name_footer, fallback))
    }
    Column(verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.xs)) {
        PrimaryButton(
            stringResource(R.string.edit_save),
            onClick = { onSave(draft) },
            modifier = Modifier.fillMaxWidth(),
            enabled = canEdit && draft != phone.name,
            loading = phones.saving == phone.keyId,
        )
        if (phones.result?.keyId == phone.keyId && phones.result.outcome == PhoneOutcome.SaveFailed) {
            PhoneNote(stringResource(R.string.edit_save_failed), DoorTheme.colors.red)
        }
    }
    if (!isMe) {
        val makeAdmin = stringResource(R.string.phone_make_admin)
        val revoke = stringResource(R.string.phone_revoke)
        ListGroup {
            if (phone.role != Role.Admin) {
                row(makeAdmin, icon = Icons.Rounded.AdminPanelSettings, enabled = canEdit, onClick = onMakeAdmin)
            }
            row(revoke, icon = Icons.Rounded.PersonRemove, style = ListRowStyle.Destructive, enabled = canEdit, onClick = onRevoke)
        }
    }
    SecondaryButton(stringResource(R.string.buttons_done), onDone, Modifier.fillMaxWidth())
}

// Asked before Revoke and before Make admin: both take effect at once on the board.
//   body: what happens, with the phone's name
//   failed: the last attempt for this phone failed
@Composable
internal fun ConfirmPhonePage(
    body: String,
    action: String,
    destructive: Boolean,
    working: Boolean,
    failed: String?,
    canEdit: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        body,
        Modifier.fillMaxWidth(),
        style = DoorTheme.type.body,
        color = DoorTheme.colors.secondaryLabel,
        textAlign = TextAlign.Center,
    )
    ListGroup(footer = failed) {
        row(
            action,
            icon = if (destructive) Icons.Rounded.PersonRemove else Icons.Rounded.AdminPanelSettings,
            style = if (destructive) ListRowStyle.Destructive else ListRowStyle.Normal,
            loading = working,
            enabled = canEdit || working,
            onClick = onConfirm,
        )
    }
    SecondaryButton(stringResource(R.string.settings_cancel), onCancel, Modifier.fillMaxWidth(), enabled = !working)
}

// Add phone: the board's 8 digits and the same as a QR code, for the new phone to type or scan.
@Composable
internal fun InvitePage(invite: InviteState, onRetry: () -> Unit, onDone: () -> Unit) {
    val digits = invite.digits
    val qr = invite.qr
    when {
        digits != null && qr != null && !invite.expired -> {
            Centered(stringResource(R.string.invite_hint))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCode(qr, stringResource(R.string.invite_qr_label), Modifier.size(232.dp))
            }
            Text(
                InviteCode.display(digits),
                Modifier.fillMaxWidth(),
                style = DoorTheme.type.largeTitle.copy(fontFamily = FontFamily.Monospace),
                color = DoorTheme.colors.label,
                textAlign = TextAlign.Center,
            )
            PhoneNote(stringResource(R.string.invite_footer))
        }
        invite.expired || invite.failed -> {
            Centered(stringResource(if (invite.expired) R.string.invite_expired else R.string.invite_failed))
            PrimaryButton(stringResource(R.string.invite_new), onRetry, Modifier.fillMaxWidth())
        }
        else -> {
            val creating = stringResource(R.string.invite_creating)
            ListGroup { row(creating, icon = Icons.Rounded.PersonAdd, loading = true) }
        }
    }
    SecondaryButton(stringResource(R.string.buttons_done), onDone, Modifier.fillMaxWidth())
}

// Black modules on white with a quiet zone, in both themes, so any camera reads it.
@Composable
private fun QrCode(text: String, label: String, modifier: Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Box(
        modifier
            .clip(DoorTheme.radius.card)
            .background(Color.White)
            .padding(16.dp)
            .semantics { contentDescription = label },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val cell = size.minDimension / matrix.width
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (matrix[x, y]) {
                        // A hair wider than the cell, so no seams show between modules.
                        drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth(),
        style = DoorTheme.type.body,
        color = DoorTheme.colors.secondaryLabel,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun PhoneNote(text: String, color: Color = DoorTheme.colors.secondaryLabel) {
    Text(
        text,
        Modifier.padding(horizontal = DoorTheme.spacing.m),
        style = DoorTheme.type.footnote,
        color = color,
    )
}

// Fake names, not from a real board.
private val PreviewPhones = PhoneList(
    listOf(
        PairedPhone(0, Role.Admin, "Galaxy Tab A7 Lite"),
        PairedPhone(1, Role.Normal, "Điện thoại của Lan"),
        PairedPhone(3, Role.Normal),
    ),
)

@DoorPreviews
@Composable
private fun PhonesPagePreview() = SheetPreview {
    PhonesPage(PhonesState(list = PreviewPhones), myKeyId = 0, canAddPhone = true, {}, {}, {})
}

@DoorPreviews
@Composable
private fun PhonesLoadingPreview() = SheetPreview {
    PhonesPage(PhonesState(loading = true), myKeyId = 0, canAddPhone = false, {}, {}, {})
}

// Fake digits and secret, not from a real board.
@DoorPreviews
@Composable
private fun InvitePagePreview() = SheetPreview {
    InvitePage(
        InviteState(digits = "12345678", qr = "RDOOR1:001122334455:AAAQEAYEAUDAOCAJBIFQYDIOB4"),
        onRetry = {},
        onDone = {},
    )
}

@DoorPreviews
@Composable
private fun InviteExpiredPreview() = SheetPreview { InvitePage(InviteState(expired = true), {}, {}) }

@DoorPreviews
@Composable
private fun PhonePagePreview() = SheetPreview {
    PhonePage(PreviewPhones.phones[1], isMe = false, PhonesState(list = PreviewPhones), canEdit = true, {}, {}, {}, {})
}

@DoorPreviews
@Composable
private fun RevokePhonePreview() = SheetPreview {
    ConfirmPhonePage(
        body = "Điện thoại của Lan sẽ không điều khiển được cửa nữa. Muốn dùng lại thì phải ghép đôi lại bằng mã QR.",
        action = "Thu hồi",
        destructive = true,
        working = false,
        failed = null,
        canEdit = true,
        onConfirm = {},
        onCancel = {},
    )
}
