package com.example.safedistance

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.hardware.Camera
import android.os.Bundle
import android.util.Log
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
import com.example.safedistance.ui.theme.SafeDistanceTheme
import com.google.mlkit.vision.camera.CameraSourceConfig
import com.google.mlkit.vision.camera.CameraXSource
import com.google.mlkit.vision.face.FaceDetection
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

    private var focalLength: Float = 1f
    private var sensorX: Float = 0f
    private var sensorY: Float = 0f
    private var angleX: Float = 0f
    private var angleY: Float = 0f

    private var eyeDistance: MutableState<String?> = mutableStateOf("")

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
            initializeParamsAndLaunchCamera()
        }
    }

    private fun initializeParamsAndLaunchCamera() {
        val camera: Camera? = frontCam()
        var camparams = camera?.getParameters()
        if (camparams != null) {
            focalLength = camparams.focalLength
            angleX = camparams.horizontalViewAngle
            angleY = camparams.verticalViewAngle
            sensorX =
                (Math.tan(Math.toRadians((angleX / 2).toDouble())) * 2 * focalLength).toFloat();
            sensorY =
                (Math.tan(Math.toRadians((angleY / 2).toDouble())) * 2 * focalLength).toFloat();
        }
        if (camera != null) {
            camera.stopPreview();
            camera.release();
        }
        createCameraSource()
    }

    private val cameraPermissionRequestLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission granted: proceed with opening the camera
                initializeParamsAndLaunchCamera()
            } else {
                // Permission denied: inform the user to enable it through settings
                Toast.makeText(
                    this,
                    "Go to settings and enable camera permission to use this app",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private fun frontCam(): Camera? {
        var cameraCount = 0
        var cam: Camera? = null
        val cameraInfo = Camera.CameraInfo()
        cameraCount = Camera.getNumberOfCameras()
        for (camIdx in 0 until cameraCount) {
            Camera.getCameraInfo(camIdx, cameraInfo)
            Log.v("CAMID", camIdx.toString() + "")
            if (cameraInfo.facing === Camera.CameraInfo.CAMERA_FACING_FRONT) {
                try {
                    cam = Camera.open(camIdx)
                } catch (e: RuntimeException) {
                    Log.e("FAIL", "Camera failed to open: " + e.localizedMessage)
                }
            }
        }
        return cam
    }

    private fun createCameraSource(){
        // High-accuracy landmark detection and face classification
        val highAccuracyOpts = FaceDetectorOptions.Builder()
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
        val detector = FaceDetection.getClient(highAccuracyOpts)

        val cameraSourceConfig = CameraSourceConfig.Builder(this, detector){
            it.addOnSuccessListener { faces ->
                for(face in faces){
                    val leftEyePos: PointF? = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                    val rightEyePos: PointF? = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

                    if(leftEyePos != null && rightEyePos != null){
                        val deltaX : Float = abs(leftEyePos.x - rightEyePos.x)
                        val deltaY : Float = abs(leftEyePos.y - rightEyePos.y)

                        var distance: Float
                        distance = if (deltaX >= deltaY) {
                            focalLength * (AVERAGE_EYE_DISTANCE / sensorX) * (IMAGE_WIDTH / deltaX)
                        } else {
                            focalLength * (AVERAGE_EYE_DISTANCE / sensorY) * (IMAGE_HEIGHT / deltaY)
                        }

                        eyeDistance.value = "distance: " + String.format("%.0f", distance) + "mm"

//                        Toast.makeText(this , eyeDistance, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            it.addOnFailureListener{e ->
                e.printStackTrace()
            }

        }.setFacing(CameraSourceConfig.CAMERA_FACING_FRONT).build()

        val cameraXSource = CameraXSource(cameraSourceConfig);
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
        }
    }

    @Composable
    fun ScreenDistanceView(distance: MutableState<String?>){

        Column (modifier = Modifier
            .background(Color.Black)
            .fillMaxSize()){
            Text(text = "Screen Distance", fontWeight = FontWeight.Bold, modifier = Modifier.offset(x = 16.dp, y = 10.dp), fontSize = 24.sp, color = Color.White)
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .shadow(10.dp)
                .padding(16.dp)
                .background(Color(0xFF222222), shape = RoundedCornerShape(25.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally

                ) {
                    Text(text = distance.value!!.ifEmpty{"0"}, color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Hold the phone straight.", color = Color.White)
                }
            }
        }
    }
}
