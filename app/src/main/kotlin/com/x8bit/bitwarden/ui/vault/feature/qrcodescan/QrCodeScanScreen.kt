package com.x8bit.bitwarden.ui.vault.feature.qrcodescan

import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bitwarden.ui.platform.base.util.EventsEffect
import com.bitwarden.ui.platform.base.util.annotatedStringResource
import com.bitwarden.ui.platform.components.appbar.BitwardenTopAppBar
import com.bitwarden.ui.platform.components.util.rememberVectorPainter
import com.bitwarden.ui.platform.model.WindowSize
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.bitwarden.ui.platform.theme.LocalBitwardenColorScheme
import com.bitwarden.ui.platform.theme.color.darkBitwardenColorScheme
import com.bitwarden.ui.platform.util.rememberWindowSize
import com.x8bit.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.x8bit.bitwarden.ui.vault.feature.qrcodescan.util.QrCodeAnalyzer
import com.x8bit.bitwarden.ui.vault.feature.qrcodescan.util.QrCodeAnalyzerImpl
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The screen to scan QR codes for the application.
 */
@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrCodeScanScreen(
    onNavigateBack: () -> Unit,
    onNavigateToManualCodeEntryScreen: () -> Unit,
    viewModel: QrCodeScanViewModel = hiltViewModel(),
    qrCodeAnalyzer: QrCodeAnalyzer = QrCodeAnalyzerImpl(),
) {
    qrCodeAnalyzer.onQrCodeScanned = remember(viewModel) {
        { viewModel.trySendAction(QrCodeScanAction.QrCodeScanReceive(it)) }
    }

    val onEnterKeyManuallyClick = remember(viewModel) {
        { viewModel.trySendAction(QrCodeScanAction.ManualEntryTextClick) }
    }

    EventsEffect(viewModel = viewModel) { event ->
        when (event) {
            is QrCodeScanEvent.NavigateBack -> {
                onNavigateBack.invoke()
            }

            is QrCodeScanEvent.NavigateToManualCodeEntry -> {
                onNavigateToManualCodeEntryScreen.invoke()
            }
        }
    }
    // This screen should always look like it's in dark mode
    CompositionLocalProvider(LocalBitwardenColorScheme provides darkBitwardenColorScheme) {
        BitwardenScaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                BitwardenTopAppBar(
                    title = stringResource(id = BitwardenString.scan_qr_code),
                    navigationIcon = rememberVectorPainter(id = BitwardenDrawable.ic_close),
                    navigationIconContentDescription = stringResource(id = BitwardenString.close),
                    onNavigationIconClick = remember(viewModel) {
                        { viewModel.trySendAction(QrCodeScanAction.CloseClick) }
                    },
                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(
                        state = rememberTopAppBarState(),
                    ),
                )
            },
        ) {
            CameraPreview(
                cameraErrorReceive = remember(viewModel) {
                    { viewModel.trySendAction(QrCodeScanAction.CameraSetupErrorReceive) }
                },
                qrCodeAnalyzer = qrCodeAnalyzer,
            )
            when (rememberWindowSize()) {
                WindowSize.Compact -> {
                    QrCodeContentCompact(
                        onEnterKeyManuallyClick = onEnterKeyManuallyClick,
                    )
                }

                WindowSize.Medium -> {
                    QrCodeContentMedium(
                        onEnterKeyManuallyClick = onEnterKeyManuallyClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun QrCodeContentCompact(
    onEnterKeyManuallyClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        QrCodeSquare(
            squareOutlineSize = 250.dp,
            modifier = Modifier.weight(2f),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround,
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .background(color = BitwardenTheme.colorScheme.background.scrim)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(id = BitwardenString.point_your_camera_at_the_qr_code),
                textAlign = TextAlign.Center,
                color = Color.White,
                style = BitwardenTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            EnterKeyManuallyText(
                onEnterKeyManuallyClick = onEnterKeyManuallyClick,
            )
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun QrCodeContentMedium(
    onEnterKeyManuallyClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        QrCodeSquare(
            squareOutlineSize = 200.dp,
            modifier = Modifier.weight(2f),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround,
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .background(color = BitwardenTheme.colorScheme.background.scrim)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(id = BitwardenString.point_your_camera_at_the_qr_code),
                textAlign = TextAlign.Center,
                color = Color.White,
                style = BitwardenTheme.typography.bodySmall,
            )

            EnterKeyManuallyText(
                onEnterKeyManuallyClick = onEnterKeyManuallyClick,
            )
        }
    }
}

@Suppress("LongMethod", "TooGenericExceptionCaught")
@Composable
private fun CameraPreview(
    cameraErrorReceive: () -> Unit,
    qrCodeAnalyzer: QrCodeAnalyzer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = ViewGroup.LayoutParams(
                MATCH_PARENT,
                MATCH_PARENT,
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val imageAnalyzer = remember(qrCodeAnalyzer) {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(
                    Executors.newSingleThreadExecutor(),
                    qrCodeAnalyzer,
                )
            }
    }

    val preview = Preview.Builder()
        .build()
        .apply { surfaceProvider = previewView.surfaceProvider }

    // Unbind from the camera provider when we leave the screen.
    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
        }
    }

    // Set up the camera provider on a background thread. This is necessary because
    // ProcessCameraProvider.getInstance returns a ListenableFuture. For an example see
    // https://github.com/JetBrains/compose-multiplatform/blob/1c7154b975b79901f40f28278895183e476ed36d/examples/imageviewer/shared/src/androidMain/kotlin/example/imageviewer/view/CameraView.android.kt#L85
    LaunchedEffect(imageAnalyzer) {
        try {
            cameraProvider = suspendCoroutine { continuation ->
                ProcessCameraProvider.getInstance(context).also { future ->
                    future.addListener(
                        { continuation.resume(future.get()) },
                        ContextCompat.getMainExecutor(context),
                    )
                }
            }

            cameraProvider?.unbindAll()
            if (cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) == true) {
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer,
                )
            } else {
                cameraErrorReceive()
            }
        } catch (_: Exception) {
            cameraErrorReceive()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

/**
 * UI for the blue QR code square that is drawn onto the screen.
 */
@Suppress("MagicNumber", "LongMethod")
@Composable
private fun QrCodeSquare(
    modifier: Modifier = Modifier,
    squareOutlineSize: Dp,
) {
    val color = BitwardenTheme.colorScheme.text.primary

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) {
        Canvas(
            modifier = Modifier
                .padding(8.dp)
                .size(squareOutlineSize),
        ) {
            val strokeWidth = 3.dp.toPx()

            val squareSize = size.width
            val strokeOffset = strokeWidth / 2
            val sideLength = (1f / 6) * squareSize

            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.apply {
                    // Draw upper top left.
                    drawLine(
                        color = color,
                        start = Offset(0f, strokeOffset),
                        end = Offset(sideLength, strokeOffset),
                        strokeWidth = strokeWidth,
                    )

                    // Draw lower top left.
                    drawLine(
                        color = color,
                        start = Offset(strokeOffset, strokeOffset),
                        end = Offset(strokeOffset, sideLength),
                        strokeWidth = strokeWidth,
                    )

                    // Draw upper top right.
                    drawLine(
                        color = color,
                        start = Offset(squareSize - sideLength, strokeOffset),
                        end = Offset(squareSize - strokeOffset, strokeOffset),
                        strokeWidth = strokeWidth,
                    )

                    // Draw lower top right.
                    drawLine(
                        color = color,
                        start = Offset(squareSize - strokeOffset, 0f),
                        end = Offset(squareSize - strokeOffset, sideLength),
                        strokeWidth = strokeWidth,
                    )

                    // Draw upper bottom right.
                    drawLine(
                        color = color,
                        start = Offset(squareSize - strokeOffset, squareSize),
                        end = Offset(squareSize - strokeOffset, squareSize - sideLength),
                        strokeWidth = strokeWidth,
                    )

                    // Draw lower bottom right.
                    drawLine(
                        color = color,
                        start = Offset(squareSize - strokeOffset, squareSize - strokeOffset),
                        end = Offset(squareSize - sideLength, squareSize - strokeOffset),
                        strokeWidth = strokeWidth,
                    )

                    // Draw upper bottom left.
                    drawLine(
                        color = color,
                        start = Offset(strokeOffset, squareSize),
                        end = Offset(strokeOffset, squareSize - sideLength),
                        strokeWidth = strokeWidth,
                    )

                    // Draw lower bottom left.
                    drawLine(
                        color = color,
                        start = Offset(0f, squareSize - strokeOffset),
                        end = Offset(sideLength, squareSize - strokeOffset),
                        strokeWidth = strokeWidth,
                    )
                }
            }
        }
    }
}

@Composable
private fun EnterKeyManuallyText(
    onEnterKeyManuallyClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enterKeyManuallyString = stringResource(BitwardenString.enter_key_manually)
    Text(
        text = annotatedStringResource(
            id = BitwardenString.cannot_scan_qr_code_enter_key_manually,
            onAnnotationClick = {
                when (it) {
                    "enterKeyManually" -> onEnterKeyManuallyClick()
                }
            },
        ),
        style = BitwardenTheme.typography.bodySmall,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = modifier
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(
                        label = enterKeyManuallyString,
                        action = {
                            onEnterKeyManuallyClick()
                            true
                        },
                    ),
                )
            },
    )
}
