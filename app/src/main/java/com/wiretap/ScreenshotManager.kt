import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.wiretap.AccessibilityTreeCreator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenshotManager(
    private val service: AccessibilityService,
    private val coroutineScope: CoroutineScope
) {
    private var screenshotJob: Job? = null
    private var currentHardwareBuffer: HardwareBuffer? = null
    private var currentColorSpace: ColorSpace? = null
    private var currentTrees: Pair<String, String>? = null
    private val bufferLock = ReentrantLock()

    private val treeCreator = AccessibilityTreeCreator()

    @RequiresApi(Build.VERSION_CODES.R)
    fun startPeriodicCapture() {
        stopPeriodicCapture()

        screenshotJob = coroutineScope.launch {
            while (isActive) {
                captureScreenshotAndTrees()
                delay(300)
            }
        }
    }

    fun stopPeriodicCapture() {
        screenshotJob?.cancel()
        screenshotJob = null
        clearCurrentBuffer()
    }

    private fun clearCurrentBuffer() {
        bufferLock.withLock {
            currentHardwareBuffer?.close()
            currentHardwareBuffer = null
            currentColorSpace = null
            currentTrees = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenshotAndTrees() {
        return suspendCancellableCoroutine { continuation ->
            // Capture the accessibility trees
            val windows = service.windows
            val forestJsonDFS = buildForest(windows, "dfs")
            val forestJsonBFS = buildForest(windows, "bfs")

            // Then capture the screenshot
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        bufferLock.withLock {
                            currentHardwareBuffer?.close()
                            currentHardwareBuffer = screenshot.hardwareBuffer
                            currentColorSpace = screenshot.colorSpace
                            currentTrees = Pair(forestJsonDFS, forestJsonBFS)
                        }
                        continuation.resume(Unit)
                    }

                    override fun onFailure(errorCode: Int) {
                        val error = RuntimeException("Screenshot failed with error code: $errorCode")
                        Log.e(TAG, error.message ?: "Screenshot failed")
                        continuation.resumeWithException(error)
                    }
                }
            )
        }
    }

    private fun buildForest(windows: List<AccessibilityWindowInfo>, traversalType: String): String {
        val forestJson = when (traversalType) {
            "dfs" -> treeCreator.buildForest(windows, "dfs")
            "bfs" -> treeCreator.buildForest(windows, "bfs")
            else -> throw IllegalArgumentException("Invalid traversal type: $traversalType")
        }
        return forestJson
    }

    public fun saveCurrentScreenshotAndTrees(episodeDir: File, index: Int): Boolean {
        var bitmap: Bitmap? = null
        return try {
            bufferLock.withLock {
                val buffer = currentHardwareBuffer
                val colorSpace = currentColorSpace
                val trees = currentTrees

                if (buffer == null || colorSpace == null || trees == null) {
                    Log.w(TAG, "No screenshot or trees available")
                    return false
                }

                // Save trees
                File(episodeDir, "accessibility_tree_${index}_dfs.json").writeText(trees.first)
                File(episodeDir, "accessibility_tree${index}_bfs.json").writeText(trees.second)

                // Save screenshot
                bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                bitmap?.let {
                    val screenshotFile = File(episodeDir, "screenshot_$index.png")
                    FileOutputStream(screenshotFile).use { out ->
                        it.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    Log.d(TAG, "Screenshot and trees saved with index: $index")
                    true
                } ?: false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving screenshot and trees", e)
            false
        } finally {
            bitmap?.recycle()
        }
    }

    companion object {
        private const val TAG = "ScreenshotManager"
    }
}