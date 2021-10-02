package galaxytech.objectdetection

import android.content.Context
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView.SurfaceTextureListener
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import galaxytech.objectdetection.customview.AutoFitTextureView
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * A simple [Fragment] subclass.
 * Activities that contain this fragment must implement the
 * [CameraConnectionFragment.OnFragmentInteractionListener] interface
 * to handle interaction events.
 * Use the [CameraConnectionFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
open class CameraConnectionFragment(var connectionCallback: ConnectionCallback, var imageListener: ImageReader.OnImageAvailableListener, private var layout: Int, var inputSize: Size) : Fragment() {

    val MINIMUM_PREVIEW_SIZE = 320

    val cameraOpenCloseLock = Semaphore(1)
    private lateinit var cameraConnectionCallback: ConnectionCallback
    private val captureCallback = object : CameraCaptureSession.CaptureCallback(){

    }

    var cameraId: String? = null
    private lateinit var textureView: AutoFitTextureView
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var cameraDevice: CameraDevice
    private var screenOrientation = 0
    private lateinit var previewSize: Size
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private lateinit var previewReader: ImageReader
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var previewRequest: CaptureRequest
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera

        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
        }
    }

    private val surfaceTextureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            texture: SurfaceTexture, width: Int, height: Int
        ) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            texture: SurfaceTexture, width: Int, height: Int
        ) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    lateinit var manager: CameraManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(layout, container, false)

    override fun onResume() {
        super.onResume()

        manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        startBackgroundThread()

        // When the screen is turned off and turned back on, the SurfaceTexture is already
// available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
// a camera and start preview from here (otherwise, we wait until the surface is ready in
// the SurfaceTextureListener).
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ImageListener")
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }
    fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {

        }
    }

    private fun setUpCameraOutputs() {
        try {
            val characteristics = manager.getCameraCharacteristics(cameraId!!)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            screenOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
            previewSize = this.chooseOptimalSize(map!!.getOutputSizes(SurfaceTexture::class.java), inputSize.width, inputSize.height)!!
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) textureView.setAspectRatio(previewSize.width, previewSize.height) else textureView.setAspectRatio(previewSize.height, previewSize.width)
        } catch (e: CameraAccessException) {

        } catch (e: NullPointerException) {
            Toast.makeText(context, "", Toast.LENGTH_LONG).show()
        }
        cameraConnectionCallback.onPreviewSizeChosen(previewSize, screenOrientation)
    }

    protected fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size? {
        val minSize = Math.max(Math.min(width, height), MINIMUM_PREVIEW_SIZE)
        val desiredSize = Size(width, height)
        // Collect the supported resolutions that are at least as big as the preview Surface
        var exactSizeFound = false
        val bigEnough: MutableList<Size?> =
            ArrayList()
        val tooSmall: MutableList<Size?> =
            ArrayList()
        for (option in choices) {
            if (option == desiredSize) { // Set the size but don't return yet so that remaining sizes will still be logged.
                exactSizeFound = true
            }
            if (option.height >= minSize && option.width >= minSize) {
                bigEnough.add(option)
            } else {
                tooSmall.add(option)
            }
        }

        if (exactSizeFound) {
            return desiredSize
        }
        // Pick the smallest of those, assuming we found any
        return if (bigEnough.size > 0) {
//            val chosenSize: Size = Collections.min(bigEnough, CompareSizesByArea())!!
            choices[0]
        } else {
            choices[0]
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = activity!!.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0.toFloat(), 0.toFloat(), viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0.toFloat(), 0.toFloat(), previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(viewHeight.toFloat() / previewSize.height, viewWidth.toFloat() / previewSize.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90 * (rotation - 2).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    fun openCamera(width: Int, height: Int) {
        setUpCameraOutputs()
        configureTransform(width, height)
        try {
            if (cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: InterruptedException) {

        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            if (null != captureSession) {
                captureSession.close()
                captureSession = null
            }
            if (null != cameraDevice) {
                cameraDevice.close()
                cameraDevice = null
            }
            if (null != previewReader) {
                previewReader.close()
                previewReader = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(callback: ConnectionCallback, imageListener: ImageReader.OnImageAvailableListener, layout: Int, inputSize: Size) = CameraConnectionFragment(callback, imageListener, layout, inputSize)
    }

    interface ConnectionCallback {
        fun onPreviewSizeChosen(size: Size, cameraRotation: Int)
    }

    internal class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size?, rhs: Size?): Int { // We cast here to ensure the multiplications won't overflow
            return java.lang.Long.signum(lhs!!.width.toLong() * lhs.height - rhs!!.width.toLong() * rhs.height)
        }
    }
}
