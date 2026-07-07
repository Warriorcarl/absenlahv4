package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.viewmodel.AbsenlahViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun LivenessCameraPreview(
    viewModel: AbsenlahViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val livenessState by viewModel.livenessState.collectAsState()

    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    // Face Detector Options
    val detectorOptions = remember {
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    }
    val faceDetector = remember { FaceDetection.getClient(detectorOptions) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analyzer ->
                        analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )

                                faceDetector.process(image)
                                    .addOnSuccessListener { faces ->
                                        for (face in faces) {
                                            // 1. Blink Detection Rule (Both eyes closed)
                                            val leftEyeOpen = face.leftEyeOpenProbability ?: 1.0f
                                            val rightEyeOpen = face.rightEyeOpenProbability ?: 1.0f
                                            
                                            if (livenessState == AbsenlahViewModel.LivenessStep.PROMPT_BLINK) {
                                                if (leftEyeOpen < 0.25f && rightEyeOpen < 0.25f) {
                                                    Log.d("LivenessCamera", "Blink Detected! Eyes: L=$leftEyeOpen, R=$rightEyeOpen")
                                                    viewModel.advanceLiveness("BLINK")
                                                }
                                            }

                                            // 2. Smile Detection Rule (Smile probability high)
                                            val smiling = face.smilingProbability ?: 0.0f
                                            if (livenessState == AbsenlahViewModel.LivenessStep.PROMPT_SMILE) {
                                                if (smiling > 0.65f) {
                                                    Log.d("LivenessCamera", "Smile Detected! Smile Prob=$smiling")
                                                    viewModel.advanceLiveness("SMILE")
                                                }
                                            }
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e("LivenessCamera", "Face detection failed", e)
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            } else {
                                imageProxy.close()
                            }
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    // Select Front Camera for Selfie Liveness
                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )
                } catch (exc: Exception) {
                    Log.e("LivenessCamera", "Camera binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        onRelease = {
            cameraExecutor.shutdown()
        }
    )
}
