package com.ecency.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/** Decodes QR codes from camera luminance (the Y plane). Pure: tests feed it rendered codes. */
object QrDecoder {
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    /** The text of the QR code in [luminance] ([width] x [height], rows [rowStride] bytes apart), else null. */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        if (width <= 0 || height <= 0) return null
        val data = if (rowStride == width) luminance else ByteArray(width * height).also { out ->
            for (y in 0 until height) System.arraycopy(luminance, y * rowStride, out, y * width, width)
        }
        val reader = MultiFormatReader().apply { setHints(hints) }
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            // light on dark codes read only inverted
            try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
            } catch (_: Exception) {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}

/** The scanner's camera: CameraX preview plus a frame analyser over [QrDecoder]. */
object QrCamera {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** RN openSettings: the app's page in the system settings. */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** The back camera's preview; every decoded value goes to [onCode] on the main thread. */
    @Composable
    fun Preview(modifier: Modifier = Modifier, onCode: (String) -> Unit) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current
        val deliver by rememberUpdatedState(onCode)
        val executor = remember { Executors.newSingleThreadExecutor() }
        val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
        DisposableEffect(lifecycle) {
            val future = ProcessCameraProvider.getInstance(context)
            var provider: ProcessCameraProvider? = null
            val main = ContextCompat.getMainExecutor(context)
            future.addListener({
                val p = runCatching { future.get() }.getOrNull() ?: return@addListener
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image -> analyse(image)?.let { text -> main.execute { deliver(text) } } }
                runCatching {
                    p.unbindAll()
                    p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, main)
            onDispose {
                runCatching { provider?.unbindAll() }
                executor.shutdown()
            }
        }
        AndroidView({ view }, modifier.testTag("qrPreview"))
    }

    private fun analyse(image: ImageProxy): String? = try {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        QrDecoder.decode(bytes, image.width, image.height, plane.rowStride)
    } catch (_: Exception) {
        null
    } finally {
        image.close()
    }
}

/**
 * Runs an action that opens the camera app once the camera permission is held, asking for it first. The app
 * declares CAMERA for the QR scanner, and Android then refuses ACTION_IMAGE_CAPTURE and ACTION_VIDEO_CAPTURE to it
 * unless it holds the permission. [onDenied] runs when the user refused.
 */
@Composable
fun rememberCameraGate(onDenied: () -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val denied by rememberUpdatedState(onDenied)
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        val action = pending[0]
        pending[0] = null
        if (ok) action?.invoke() else denied()
    }
    return remember(launcher) {
        { action ->
            if (QrCamera.hasPermission(context)) {
                action()
            } else {
                pending[0] = action
                launcher.launch(android.Manifest.permission.CAMERA)
            }
        }
    }
}
