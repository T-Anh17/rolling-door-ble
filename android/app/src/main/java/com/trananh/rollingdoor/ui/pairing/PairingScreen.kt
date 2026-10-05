package com.trananh.rollingdoor.ui.pairing

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trananh.rollingdoor.R
import com.trananh.rollingdoor.ui.components.Card
import com.trananh.rollingdoor.ui.components.DoorPreviews
import com.trananh.rollingdoor.ui.components.NavBarScaffold
import com.trananh.rollingdoor.ui.components.PrimaryButton
import com.trananh.rollingdoor.ui.components.SecondaryButton
import com.trananh.rollingdoor.ui.components.Step
import com.trananh.rollingdoor.ui.components.StepList
import com.trananh.rollingdoor.ui.components.StepState
import com.trananh.rollingdoor.ui.components.TextInput
import com.trananh.rollingdoor.ui.components.TextLink
import com.trananh.rollingdoor.ui.theme.DoorMotion
import com.trananh.rollingdoor.ui.theme.DoorTheme
import com.trananh.rollingdoor.ui.theme.RollingDoorTheme

@Composable
fun PairingScreen(viewModel: PairingViewModel = viewModel(factory = PairingViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var scanning by rememberSaveable { mutableStateOf(false) }
    // Refused for good: the system dialog no longer shows, so Scan opens app settings instead.
    var cameraBlocked by rememberSaveable { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scanning = true
        } else {
            cameraBlocked = context.findActivity()
                ?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
            viewModel.onCameraDenied()
        }
    }
    val scan = {
        when {
            context.hasCameraPermission() -> scanning = true
            cameraBlocked -> context.openAppSettings()
            else -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    Box {
        PairingContent(
            state = state,
            onScan = scan,
            onSubmitManual = viewModel::submitManual,
            onRetry = viewModel::retry,
            onCancel = viewModel::cancel,
            onScanAnother = {
                viewModel.reset()
                scan()
            },
        )
        if (scanning) {
            QrScannerOverlay(
                onCode = { code ->
                    scanning = false
                    viewModel.onScanned(code)
                },
                onClose = { scanning = false },
                onCameraError = {
                    scanning = false
                    viewModel.onCameraFailed()
                },
            )
        }
    }
}

@Composable
private fun PairingContent(
    state: PairingUiState,
    onScan: () -> Unit,
    onSubmitManual: (String) -> Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onScanAnother: () -> Unit,
) {
    NavBarScaffold(title = stringResource(R.string.pairing_title)) {
        Text(
            stringResource(R.string.pairing_intro),
            style = DoorTheme.type.body,
            color = DoorTheme.colors.secondaryLabel,
        )
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = { fadeIn(DoorMotion.spring()) togetherWith fadeOut(DoorMotion.spring()) },
            label = "pairing",
        ) { current ->
            Column(verticalArrangement = Arrangement.spacedBy(DoorTheme.spacing.m)) {
                when (current) {
                    PairingUiState.Idle -> IdleActions(onScan, onSubmitManual)
                    is PairingUiState.Running -> {
                        StepsCard(current.stage, failed = false)
                        if (current.pairingClosedHint) Hint(stringResource(R.string.pairing_closed_hint))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextLink(stringResource(R.string.pairing_cancel), onCancel)
                        }
                    }
                    PairingUiState.Done -> StepsCard(stage = null, failed = false)
                    // Failed before any step (bad code, scanner): back to the start, with the
                    // problem on top. Manual entry opens by itself when the scanner is missing.
                    is PairingUiState.Failed -> if (current.stage == null) {
                        ProblemCard(current.problem)
                        IdleActions(
                            onScan,
                            onSubmitManual,
                            startManual = current.problem == PairingProblem.CameraUnavailable ||
                                current.problem == PairingProblem.CameraPermission,
                        )
                    } else {
                        StepsCard(current.stage, failed = true)
                        ProblemCard(current.problem)
                        if (current.canRetry) {
                            PrimaryButton(stringResource(R.string.pairing_retry), onRetry, Modifier.fillMaxWidth())
                        }
                        SecondaryButton(
                            stringResource(R.string.pairing_scan_another),
                            onScanAnother,
                            Modifier.fillMaxWidth(),
                            icon = Icons.Rounded.QrCodeScanner,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IdleActions(onScan: () -> Unit, onSubmitManual: (String) -> Boolean, startManual: Boolean = false) {
    var manual by rememberSaveable { mutableStateOf(startManual) }
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val submit = { invalid = !onSubmitManual(text) }

    PrimaryButton(
        stringResource(R.string.pairing_scan),
        onScan,
        Modifier.fillMaxWidth(),
        icon = Icons.Rounded.QrCodeScanner,
    )
    if (!manual) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextLink(stringResource(R.string.pairing_enter_manually), { manual = true })
        }
    } else {
        Text(
            stringResource(R.string.pairing_manual_label),
            style = DoorTheme.type.footnote,
            color = DoorTheme.colors.secondaryLabel,
        )
        TextInput(
            value = text,
            onValueChange = {
                text = it
                invalid = false
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "RDOOR1:…",
            error = if (invalid) stringResource(R.string.pairing_manual_invalid) else null,
            textStyle = DoorTheme.type.body.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        SecondaryButton(
            stringResource(R.string.pairing_pair),
            onClick = submit,
            modifier = Modifier.fillMaxWidth(),
            enabled = text.isNotBlank(),
        )
    }
}

@Composable
private fun StepsCard(stage: PairingStage?, failed: Boolean) {
    val labels = listOf(
        R.string.pairing_step_find,
        R.string.pairing_step_connect,
        R.string.pairing_step_key,
        R.string.pairing_step_confirm,
    )
    // stage = null: every step done.
    val current = stage?.ordinal ?: labels.size
    Card(Modifier.fillMaxWidth()) {
        StepList(
            labels.mapIndexed { index, label ->
                val stepState = when {
                    index < current -> StepState.Done
                    index > current -> StepState.Waiting
                    failed -> StepState.Failed
                    else -> StepState.Running
                }
                Step(stringResource(label), stepState)
            },
        )
    }
}

@Composable
private fun ProblemCard(problem: PairingProblem) {
    val (title, body) = problem.text()
    Card(Modifier.fillMaxWidth()) {
        Text(stringResource(title), style = DoorTheme.type.headline, color = DoorTheme.colors.red)
        Text(stringResource(body), style = DoorTheme.type.subhead, color = DoorTheme.colors.secondaryLabel)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = DoorTheme.type.footnote, color = DoorTheme.colors.secondaryLabel)
}

private fun PairingProblem.text(): Pair<Int, Int> = when (this) {
    PairingProblem.InvalidCode -> R.string.pairing_error_invalid_code to R.string.pairing_error_invalid_code_body
    PairingProblem.CameraPermission -> R.string.pairing_error_camera_permission to R.string.pairing_error_camera_permission_body
    PairingProblem.CameraUnavailable -> R.string.pairing_error_camera to R.string.pairing_error_camera_body
    PairingProblem.BluetoothOff -> R.string.pairing_error_bluetooth_off to R.string.pairing_error_bluetooth_off_body
    PairingProblem.NoPermission -> R.string.pairing_error_permission to R.string.pairing_error_permission_body
    PairingProblem.NotFound -> R.string.pairing_error_not_found to R.string.pairing_error_not_found_body
    PairingProblem.ConnectionFailed -> R.string.pairing_error_connection to R.string.pairing_error_connection_body
    PairingProblem.PairingClosed -> R.string.pairing_error_closed to R.string.pairing_error_closed_body
    PairingProblem.WrongCode -> R.string.pairing_error_wrong_code to R.string.pairing_error_wrong_code_body
    PairingProblem.LockedOut -> R.string.pairing_error_locked_out to R.string.pairing_error_locked_out_body
    PairingProblem.TableFull -> R.string.pairing_error_table_full to R.string.pairing_error_table_full_body
    PairingProblem.InvalidResponse -> R.string.pairing_error_invalid_response to R.string.pairing_error_invalid_response_body
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@DoorPreviews
@Composable
private fun PairingIdlePreview() = RollingDoorTheme {
    PairingContent(PairingUiState.Idle, {}, { true }, {}, {}, {})
}

@DoorPreviews
@Composable
private fun PairingRunningPreview() = RollingDoorTheme {
    PairingContent(PairingUiState.Running(PairingStage.ExchangeKey, pairingClosedHint = false), {}, { true }, {}, {}, {})
}

@DoorPreviews
@Composable
private fun PairingFailedPreview() = RollingDoorTheme {
    PairingContent(
        PairingUiState.Failed(PairingProblem.PairingClosed, PairingStage.ExchangeKey, canRetry = true),
        {}, { true }, {}, {}, {},
    )
}
