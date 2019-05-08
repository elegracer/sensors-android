package com.huangkai.sensors_android

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.*
import android.hardware.camera2.CameraDevice.*
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.support.v4.app.ActivityCompat
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v4.content.ContextCompat.checkSelfPermission
import android.support.v4.content.PermissionChecker.PERMISSION_GRANTED
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.*
import android.widget.Toast
import java.util.concurrent.Semaphore
import kotlinx.android.synthetic.main.fragment_video.*
import java.io.FileOutputStream
import java.io.IOException
import java.lang.NullPointerException
import java.lang.RuntimeException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class VideoFragment : Fragment(), View.OnClickListener, ActivityCompat.OnRequestPermissionsResultCallback, SensorEventListener {

    private val FRAGMENT_DIALOG = "dialog"
    private val TAG = "VideoFragment"
    private val surfaceTextListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            openSensors(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?) = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) = Unit
    }
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var previewSize: Size
    private lateinit var videoSize: Size
    private var isRecordingVideo = false
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var writerThread: HandlerThread? = null
    private var writerHandler: Handler? = null
    private val cameraOpenCloseLock = Semaphore(1)
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private val stateCallback = object : StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            this@VideoFragment.cameraDevice = camera
            startPreview()
            configureTransform(textureView.width, textureView.height)
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice?.close()
            this@VideoFragment.cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cameraDevice?.close()
            this@VideoFragment.cameraDevice = null
            activity?.finish()
        }
    }
    private var nextDatasetAbsolutePath: String? = null
    private var outputStream: FileOutputStream? = null
    private var imageReader: ImageReader? = null
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var gravity: Sensor? = null

    private lateinit var imageBuffer: ByteBuffer
    private lateinit var gyroscopeBuffer: ByteBuffer
    private lateinit var acceleratorBuffer: ByteBuffer
    private lateinit var gravityBuffer: ByteBuffer

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_video, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recordButton.setOnClickListener(this)
    }

    override fun onResume() {
        super.onResume()
        allocateBuffer()
        startBackgroundThread()
        if (textureView.isAvailable) {
            showToast("Texture available")
            openSensors(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextListener
        }
    }

    private fun allocateBuffer() {
        imageBuffer = ByteBuffer.allocate(1 + 8 + 4 + 4 + 640 * 480).apply {
            order(ByteOrder.LITTLE_ENDIAN)
        }
        gyroscopeBuffer = ByteBuffer.allocate(1 + 8 + 8 * 3).apply {
            order(ByteOrder.LITTLE_ENDIAN)
        }
        acceleratorBuffer = ByteBuffer.allocate(1 + 8 + 8 * 3).apply {
            order(ByteOrder.LITTLE_ENDIAN)
        }
        gravityBuffer = ByteBuffer.allocate(1 + 8 + 8 * 3).apply {
            order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        deallocateBuffer()
        super.onPause()
    }

    private fun deallocateBuffer() {

    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.recordButton -> {
                showToast("Button pressed")
                if (isRecordingVideo) stopRecordingVideo() else startRecordingVideo()
            }
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)

        writerThread = HandlerThread("WriterBackground")
        writerThread?.start()
        writerHandler = Handler(writerThread?.looper, object:Handler.Callback {
            override fun handleMessage(msg: Message?): Boolean {
                val buffer = msg?.obj as ByteBuffer
                outputStream?.write(buffer.array())
                return true
            }
        })
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }

        writerThread?.quitSafely()
        try {
            writerThread?.join()
            writerThread = null
            writerHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun shouldShowRequestPermissionRationale(permissions: Array<String>) =
        permissions.any { shouldShowRequestPermissionRationale(it) }

    private fun requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults.size == VIDEO_PERMISSIONS.size) {
                for (result in grantResults) {
                    if (result != PERMISSION_GRANTED) {
                        ErrorDialog.newInstance(getString(R.string.permission_request))
                            .show(childFragmentManager, FRAGMENT_DIALOG)
                        break
                    }
                }
            } else {
                ErrorDialog.newInstance(getString(R.string.permission_request))
                    .show(childFragmentManager, FRAGMENT_DIALOG)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun hasPermissionGranted(permissions: Array<String>) =
        permissions.none {
            checkSelfPermission((activity as FragmentActivity), it) != PERMISSION_GRANTED
        }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null) {
//            Log.e("sensors-android", "imu lag t: " + (SystemClock.elapsedRealtimeNanos() - event.timestamp).toString())
            if (event.sensor.type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {
                val time = event.timestamp
//                Log.e("sensors-android", "gyr delta t: " + (time - lastGyroTimestamp))

                val message = Message()
                message.obj = ByteBuffer.allocate(1 + 8 + 8 * 3).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                }
                val gyroscopeBuffer = message.obj as ByteBuffer
                gyroscopeBuffer.rewind()
                gyroscopeBuffer.put(0x01)
                gyroscopeBuffer.putDouble(time.toDouble() / 1e9)
                gyroscopeBuffer.putDouble(event.values[0].toDouble())
                gyroscopeBuffer.putDouble(event.values[1].toDouble())
                gyroscopeBuffer.putDouble(event.values[2].toDouble())
                writerHandler?.sendMessage(message)
//                outputStream?.write(gyroscopeBuffer.array())
                lastGyroTimestamp = time
            }
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) {
                val time = event.timestamp
//                Log.e("sensors-android", "acc delta t: " + (time - lastAccTimestamp))
                val message = Message()
                message.obj = ByteBuffer.allocate(1 + 8 + 8 * 3).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                }
                val acceleratorBuffer = message.obj as ByteBuffer
                acceleratorBuffer.rewind()
                acceleratorBuffer.put(0x02)
                acceleratorBuffer.putDouble(time.toDouble() / 1e9)
                acceleratorBuffer.putDouble(event.values[0].toDouble())
                acceleratorBuffer.putDouble(event.values[1].toDouble())
                acceleratorBuffer.putDouble(event.values[2].toDouble())
                writerHandler?.sendMessage(message)
//                outputStream?.write(acceleratorBuffer.array())
                lastAccTimestamp = time
            }
            if (event.sensor.type == Sensor.TYPE_GRAVITY) {
                val time = event.timestamp
//                Log.e("sensors-android", "gra delta t: " + (time - lastGravityTimestamp))
                val message = Message()
                message.obj = ByteBuffer.allocate(1 + 8 + 8 * 3).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                }
                val gravityBuffer = message.obj as ByteBuffer
                gravityBuffer.rewind()
                gravityBuffer.put(0x12)
                gravityBuffer.putDouble(time.toDouble() / 1e9)
                gravityBuffer.putDouble(event.values[0].toDouble())
                gravityBuffer.putDouble(event.values[1].toDouble())
                gravityBuffer.putDouble(event.values[2].toDouble())
                writerHandler?.sendMessage(message)
//                outputStream?.write(gravityBuffer.array())
                lastGravityTimestamp = time
            }
        }
    }

    private var lastImageTimestamp : Long = 0
    private var lastAccTimestamp : Long = 0
    private var lastGyroTimestamp : Long = 0
    private var lastGravityTimestamp : Long = 0

    private fun startRecordingVideo() {
        if (cameraDevice == null || !textureView.isAvailable) return
        try {
            closePreviewSession()
            setUpOutputPath()
            val texture = textureView.surfaceTexture.apply {
                setDefaultBufferSize(640, 480)
            }

            val previewSurface = Surface(texture)
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 5)
            imageReader!!.setOnImageAvailableListener({
                val img = it.acquireNextImage()
                if (img != null) {
                    val time = img.timestamp
//                    Log.e("sensors-android", "image lag t: " + (SystemClock.elapsedRealtimeNanos() - time).toString())
//                    Log.e("sensors-android", "img delta t: " + (time - lastImageTimestamp) + " " + imageBuffer.position().toString())
                    val message = Message()
                    message.obj = ByteBuffer.allocate(1 + 8 + 4 + 4 + 640 * 480).apply {
                        order(ByteOrder.LITTLE_ENDIAN)
                    }
                    val imageBuffer = message.obj as ByteBuffer
                    val buffer = img.planes[0].buffer.order(ByteOrder.LITTLE_ENDIAN)
                    imageBuffer.rewind()
                    imageBuffer.put(0x00)
                    imageBuffer.putDouble(img.timestamp.toDouble() / 1e9)
                    imageBuffer.putInt(640)
                    imageBuffer.putInt(480)
                    imageBuffer.put(buffer)
                    writerHandler?.sendMessage(message)
//                    outputStream?.write(imageBuffer.array())
                    lastImageTimestamp = time
                    img.close()
                }
            }, backgroundHandler)
            val captureSurface = imageReader!!.surface
            val surfaces = ArrayList<Surface>().apply {
                add(previewSurface)
                add(captureSurface)
            }
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(captureSurface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range<Int>(30, 30))
            }

            cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (activity != null) showToast("Failed")
                }

                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updatePreview()
                    activity?.runOnUiThread {
                        recordButton.setText(R.string.stop)
                        isRecordingVideo = true
                    }
                }
            }, backgroundHandler)


            val sensorActivity = activity
            if (sensorActivity == null || sensorActivity.isFinishing) return

            sensorManager = sensorActivity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
            gyroscope = sensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
            gravity = sensorManager!!.getDefaultSensor(Sensor.TYPE_GRAVITY)
            sensorManager!!.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST, backgroundHandler)
            sensorManager!!.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST, backgroundHandler)
            sensorManager!!.registerListener(this, gravity, SensorManager.SENSOR_DELAY_FASTEST, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: IOException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun setUpOutputPath() {
        activity ?: return

        if (nextDatasetAbsolutePath.isNullOrEmpty()) {
            nextDatasetAbsolutePath = getDatasetFilePath()
            outputStream = FileOutputStream(nextDatasetAbsolutePath)
        }
    }

    private fun getDatasetFilePath(): String? {
        val filename =
            "${SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Calendar.getInstance().time)}.sensors"
        val dir = context?.getExternalFilesDir(null)

        return if (dir == null) {
            filename
        } else {
            "${dir.absolutePath}/$filename"
        }
    }

    private fun stopRecordingVideo() {
        isRecordingVideo = false
        recordButton.setText(R.string.record)

        sensorManager?.unregisterListener(this, gyroscope)
        sensorManager?.unregisterListener(this, accelerometer)
        sensorManager?.unregisterListener(this, gravity)
        sensorManager?.unregisterListener(this)
        sensorManager = null

        imageReader = null

        outputStream?.flush()
        outputStream?.close()
        outputStream = null

        if (activity != null) showToast("Video saved: $nextDatasetAbsolutePath")
        nextDatasetAbsolutePath = null
        startPreview()
    }

    @SuppressLint("MissingPermission")
    private fun openSensors(width: Int, height: Int) {
        if (!hasPermissionGranted(VIDEO_PERMISSIONS)) {
            requestVideoPermissions()
            return
        }

        val cameraActivity = activity
        if (cameraActivity == null || cameraActivity.isFinishing) return

        val cameraManager = cameraActivity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            val cameraId = cameraManager.cameraIdList[0]

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw RuntimeException("Cannot get available preview/video sizes")
            videoSize = chooseVideoSize(map.getOutputSizes(MediaCodec::class.java))
            previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java), width, height, videoSize)

            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView.setAspectRatio(previewSize.width, previewSize.height)
            } else {
                textureView.setAspectRatio(previewSize.height, previewSize.width)
            }

            configureTransform(width, height)

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            showToast("Cannot access the camera.")
            cameraActivity.finish()
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                .show(childFragmentManager, FRAGMENT_DIALOG)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }


    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            closePreviewSession()
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun startPreview() {
        if (cameraDevice == null || !textureView.isAvailable) return

        try {
            closePreviewSession()
            val texture = textureView.surfaceTexture
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(TEMPLATE_PREVIEW)

            val previewSurface = Surface(texture)
            previewRequestBuilder.addTarget(previewSurface)

            cameraDevice?.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (activity != null) showToast("Failed")
                    }

                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updatePreview()
                    }

                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun updatePreview() {
        if (cameraDevice == null) return

        try {
            setUpCaptureRequestBuilder(previewRequestBuilder)
            captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun setUpCaptureRequestBuilder(builder: CaptureRequest.Builder?) {
        builder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    }

    private fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }

    private fun chooseVideoSize(choices: Array<Size>) = choices.firstOrNull {
        it.width == 640 && it.height == 480
    } ?: choices[choices.size - 1]

    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int, aspectRatio: Size): Size {
        // Collect the supported resolutions that are at least as big as the preview Surface
        val w = aspectRatio.width
        val h = aspectRatio.height
        val bigEnough = choices.filter {
            it.height == it.width * h / w && it.width >= width && it.height >= height
        }
        // Pick the smallest of those, assuming we found any
        return if (bigEnough.isNotEmpty()) {
            Collections.min(bigEnough, CompareSizesByArea())
        } else {
            choices[0]
        }
    }

    private fun showToast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        activity ?: return
        val rotation = (activity as FragmentActivity).windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            with(matrix) {
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        }
        textureView.setTransform(matrix)
    }

    companion object {
        fun newInstance(): VideoFragment = VideoFragment()
    }


}