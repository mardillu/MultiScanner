package com.mardillu.multiscanner.ui.camera

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.mardillu.multiscanner.databinding.ActivityOcrScannerBinding
import com.mardillu.multiscanner.utils.*
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.FileCallback
import com.otaliastudios.cameraview.PictureResult
import com.otaliastudios.cameraview.VideoResult
import java.io.File


class OpticalScanner : AppCompatActivity() {

    private lateinit var binding: ActivityOcrScannerBinding
    private var ocrImageName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrScannerBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)

        binding.rescanScale.setOnClickListener {
            updateUIDetectingText()
        }

        binding.completeAction.setOnClickListener {
            confirmAndFinish()
        }

        binding.imgClose.setOnClickListener {
            finish()
        }

        when (intent.getIntExtra(EXTRA_SCAN_TYPE, SCAN_TYPE_BARCODE)) {
            SCAN_TYPE_BARCODE,
            SCAN_TYPE_QR_CODE -> {
                binding.cameraView.addFrameProcessor(CameraFrameProcessors.BarcodeProcessor { string ->
                    string?.let { url ->
                        onResult(url)
                    }
                })
            }
            SCAN_TYPE_OCR -> {
                binding.cameraView.addFrameProcessor(CameraFrameProcessors.OCRProcessor { string ->
                    string?.let { url ->
                        updateUITextDetected(url)
                    }
                })
            }
        }

        updateUIDetectingText()
        setCameraListeners()
    }

    private fun setCameraListeners() {
        binding.cameraView.addCameraListener(
                object : CameraListener() {
                    override fun onPictureShutter() {
                        // Picture capture started!
                    }

                    override fun onPictureTaken(result: PictureResult) {
                        val file = createImageFile(this@OpticalScanner, intent.getStringExtra(EXTRA_OCR_IMAGE_NAME))
                        result.toFile(file) {
                            ocrImageName = it?.path?:""
                        }
                    }

                    override fun onVideoTaken(result: VideoResult) {
                        // A Video was taken!
                    }

                    override fun onVideoRecordingStart() {
                        // Notifies that the actual video recording has started.
                        // Can be used to show some UI indicator for video recording or counting time.
                    }

                    override fun onVideoRecordingEnd() {
                        // Notifies that the actual video recording has ended.
                        // Can be used to remove UI indicators added in onVideoRecordingStart.
                    }
                }
        )
    }

    override fun onResume() {
        super.onResume()
        binding.cameraView.open()
    }

    override fun onPause() {
        super.onPause()
        binding.cameraView.close()
    }

    private fun onResult(result: String?) {
        Log.d(TAG, "Result is $result")
        //binding.textResult.text = "${result}Kg"
    }

    private fun updateUITextDetected(result: String?) {
        if (binding.textResult.text.toString().isNotEmpty()){
            return
        }
        binding.cameraView.takePicture()
        binding.apply {
            textResult.text = "${result}Kg"
            textPrompts.text = "Quantity in bag"
            rescanScale.show()
            completeAction.isEnabled = true
            updateProgress(100.0)
        }
    }

    private fun updateUIDetectingText() {
        binding.apply {
            textResult.text = ""
            textPrompts.text = "Detecting weight..."
            rescanScale.hide()
            completeAction.isEnabled = false
            updateProgress()
        }
    }

    private fun confirmAndFinish() {
        val intent = Intent()
        intent.putExtra(EXTRA_OCR_IMAGE_LOCATION, ocrImageName)
        intent.putExtra(EXTRA_OCR_SCAN_RESULT, binding.textResult.text.toString().replace("Kg",""))
        setResult(RESULT_SCAN_SUCCESS, intent)

        finish()
    }

    private fun updateProgress(perc: Double = 0.0){
        binding.progressBar.setProgressPercentage(perc, true)

        if (perc >= 70.0) {
            binding.progressBar.setBackgroundDrawableColor(Color.parseColor("#D5C6F6DB"))
            binding.progressBar.setBackgroundTextColor(Color.parseColor("#2B9D5C"))
            binding.progressBar.setProgressDrawableColor(Color.parseColor("#2B9D5C"))
            binding.progressBar.setProgressTextColor(Color.parseColor("#D5C6F6DB"))
        } else {
            binding.progressBar.setBackgroundDrawableColor(Color.parseColor("#D5F6CAC6"))
            binding.progressBar.setBackgroundTextColor(Color.parseColor("#9D2B2B"))
            binding.progressBar.setProgressDrawableColor(Color.parseColor("#9D2B2B"))
            binding.progressBar.setProgressTextColor(Color.parseColor("#D5F6CAC6"))
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    companion object {
        const val TAG = "CameraXDemo"
    }
}