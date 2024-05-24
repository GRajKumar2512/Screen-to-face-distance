package com.example.safedistance

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PointF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.safedistance.exception.NoAccessToCameraException
import com.example.safedistance.exception.NoFocalLengthInfoException
import com.example.safedistance.exception.NoFrontCameraException
import com.example.safedistance.exception.NoSensorSizeException
import com.example.safedistance.ui.theme.SafeDistanceTheme
import com.google.mlkit.vision.camera.CameraSourceConfig
import com.google.mlkit.vision.camera.CameraXSource
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.io.IOException
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    companion object{
        const val IMAGE_WIDTH = 1024
        const val IMAGE_HEIGHT = 1024

        const val AVERAGE_EYE_DISTANCE = 63 // in mm
    }

    private var focalLength: Float = 0f
    private var sensorX: Float = 0f
    private var sensorY: Float = 0f

    private var eyeDistance: MutableState<String?> = mutableStateOf("")

    private val cameraPermissionRequestLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission granted: proceed with opening the camera
                initializeParams()
                createCameraSource()
            } else {
                // Permission denied: inform the user to enable it through settings
                Toast.makeText(
                    this,
                    "Go to settings and enable camera permission to use this app",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SafeDistanceTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScreenDistanceView(eyeDistance)
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Please grant permission to the camera", Toast.LENGTH_SHORT).show()
            cameraPermissionRequestLauncher.launch(Manifest.permission.CAMERA)
        } else {
            initializeParams()
            createCameraSource()
        }
    }

    private fun initializeParams() {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        try {
            val cameraId = getFrontCameraId(this)
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

            val focalLengths: FloatArray? = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            assignFocalLength(focalLengths, cameraId)

            val sensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            assignSensorXY(sensorSize, cameraId)
        } catch (e: NoFrontCameraException) {
            showErrorDialog("No Front Camera",
                "This device does not have a front camera. " +
                        "The application will now close.")
        } catch (e: NoSensorSizeException) {
            showErrorDialog("No Sensor size info",
                "This camera doesn't get an information about sensor size. " +
                        "The application will now close.")
        } catch (e: NoFocalLengthInfoException) {
            showErrorDialog("No focal length info",
                "This camera doesn't have an information about focal length. " +
                        "The application will now close.")
        }
    }

    @Throws(NoSensorSizeException::class)
    private fun assignSensorXY(sensorSize: SizeF?, cameraId: String) {
        if (sensorSize != null) {
            sensorX = sensorSize.width
            sensorY = sensorSize.height
        } else {
            throw NoSensorSizeException(
                "For camera: $cameraId Sensor size info isn't available."
            )
        }
    }

    @Throws(NoFocalLengthInfoException::class)
    private fun assignFocalLength(focalLengths: FloatArray?, cameraId: String) {
        if (focalLengths != null && focalLengths.isNotEmpty()) {
            // Retrieving the first focal length
            // TODO add select for user if not correct value
            focalLength = focalLengths[0]
        } else {
            throw NoFocalLengthInfoException(
                "For camera: $cameraId Focal Length info isn't available."
            )
        }
    }

    @Throws(NoFrontCameraException::class)
    private fun getFrontCameraId(context: Context): String {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return cameraId
                }
            }
        } catch (e: Exception) {
            throw NoFrontCameraException("Error accessing camera: ${e.message}")
        }
        throw NoAccessToCameraException("No front camera found")
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }

    private fun createCameraSource(){
        val highAccuracyOpts = faceDetectorOptions()
        val detector = FaceDetection.getClient(highAccuracyOpts)

        val cameraSourceConfig = cameraSourceConfig(detector)

        val cameraXSource = CameraXSource(cameraSourceConfig)
        Log.d("MyCamera", "createCameraSource: ${cameraXSource.previewSize}")

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            cameraXSource.start()
        } catch (e: IOException) {
            e.printStackTrace()
            showErrorDialog(e.cause?.message?:"Camera failure!", e.message?: "No extra message")
        }
    }

    private fun cameraSourceConfig(detector: FaceDetector): CameraSourceConfig {
        val cameraSourceConfig = CameraSourceConfig.Builder(this, detector) {
            it.addOnSuccessListener { faces ->
                eyeDistance.value = "No face detection!"
                for (face in faces) {
                    val leftEyePos: PointF? = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                    val rightEyePos: PointF? = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

                    if (leftEyePos != null && rightEyePos != null) {
                        val deltaX: Float = abs(leftEyePos.x - rightEyePos.x)
                        val deltaY: Float = abs(leftEyePos.y - rightEyePos.y)

                        val distance: Float = if (deltaX >= deltaY) {
                            focalLength * (AVERAGE_EYE_DISTANCE / sensorX) * (IMAGE_WIDTH / deltaX)
                        } else {
                            focalLength * (AVERAGE_EYE_DISTANCE / sensorY) * (IMAGE_HEIGHT / deltaY)
                        }

                        eyeDistance.value = "distance: %.0f mm".format(distance)
                    }
                }
            }

            it.addOnFailureListener { e ->
                e.printStackTrace()
                showErrorDialog(
                    "Problem with calculate distance",
                    e.message ?: "No extra info"
                )
            }

        }.setFacing(CameraSourceConfig.CAMERA_FACING_FRONT).build()
        return cameraSourceConfig
    }

    private fun faceDetectorOptions(): FaceDetectorOptions {
        val highAccuracyOpts = FaceDetectorOptions.Builder()
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
        return highAccuracyOpts
    }

    @Composable
    fun ScreenDistanceView(distance: MutableState<String?>){
        val backgroundColor = Color.Black
        val boxColor = Color(0xFF222222)
        val textColor = Color.White

        Column (modifier = Modifier
            .background(backgroundColor)
            .fillMaxSize()){
            Text(text = "Screen Distance", fontWeight = FontWeight.Bold, modifier = Modifier.offset(x = 16.dp, y = 10.dp), fontSize = 24.sp, color = textColor)
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .shadow(10.dp)
                .padding(16.dp)
                .background(boxColor, shape = RoundedCornerShape(25.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = distance.value!!.ifEmpty{"0"}, color = textColor)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Hold the phone straight.", color = textColor)
                }
            }
        }
    }
}
