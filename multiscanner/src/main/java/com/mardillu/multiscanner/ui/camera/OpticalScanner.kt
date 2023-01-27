package com.mardillu.multiscanner.ui.camera

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.mardillu.multiscanner.databinding.ActivityOcrScannerBinding
import com.mardillu.multiscanner.utils.*
import com.otaliastudios.cameraview.CameraOptions

class OpticalScanner : AppCompatActivity() {

    private lateinit var binding: ActivityOcrScannerBinding

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
        intent.putExtra(EXTRA_OCR_SCAN_RESULT, binding.textResult.text.toString().replace("Kg",""))
        setResult(RESULT_ENROLMENT_SUCCESSFUL, intent)

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