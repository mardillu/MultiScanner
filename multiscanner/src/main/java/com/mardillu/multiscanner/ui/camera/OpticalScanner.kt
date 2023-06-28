package com.mardillu.multiscanner.ui.camera

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.preference.PreferenceManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.mardillu.multiscanner.R
import com.mardillu.multiscanner.databinding.ActivityOcrScannerBinding
import com.mardillu.multiscanner.databinding.DialogOcrGuideBinding
import com.mardillu.multiscanner.utils.EXTRA_OCR_IMAGE_LOCATION
import com.mardillu.multiscanner.utils.EXTRA_OCR_IMAGE_NAME
import com.mardillu.multiscanner.utils.EXTRA_OCR_SCAN_RESULT
import com.mardillu.multiscanner.utils.EXTRA_SCANNER_RESULT
import com.mardillu.multiscanner.utils.EXTRA_SCAN_TYPE
import com.mardillu.multiscanner.utils.PREF_SHOW_GUIDE_DIALOG
import com.mardillu.multiscanner.utils.RESULT_SCAN_SUCCESS
import com.mardillu.multiscanner.utils.SCAN_TYPE_BARCODE
import com.mardillu.multiscanner.utils.SCAN_TYPE_OCR
import com.mardillu.multiscanner.utils.SCAN_TYPE_QR_CODE
import com.mardillu.multiscanner.utils.createImageFile
import com.mardillu.multiscanner.utils.hide
import com.mardillu.multiscanner.utils.show
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.PictureResult
import com.otaliastudios.cameraview.VideoResult
import org.opencv.android.OpenCVLoader
import java.util.Objects


class OpticalScanner : AppCompatActivity() {

    private lateinit var binding: ActivityOcrScannerBinding
    private var ocrImageName = ""
    var isRunning = false
    var detectedCount = 0
    var isWeightTyped = false
    private lateinit var sp: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (val scanType = intent.getIntExtra(EXTRA_SCAN_TYPE, SCAN_TYPE_BARCODE)) {
            SCAN_TYPE_BARCODE,
            SCAN_TYPE_QR_CODE -> {
                val intent = Intent(this, CodeScannerActivity::class.java)
                intent.putExtra("type", if(scanType == SCAN_TYPE_BARCODE) "1" else "2")
                val scanBarcode = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                    val resultIntent = Intent()
                    when (result.resultCode) {
                        RESULT_OK -> {
                            val data = result.data
                            val scanResult = data?.getStringExtra(EXTRA_SCANNER_RESULT)
                            intent.putExtra(EXTRA_SCANNER_RESULT, scanResult)
                        }
                        else -> {
                            intent.putExtra(EXTRA_SCANNER_RESULT, "")
                        }
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
                scanBarcode.launch(intent)
            }
            SCAN_TYPE_OCR -> {
                binding = ActivityOcrScannerBinding.inflate(LayoutInflater.from(this))
                setContentView(binding.root)

                sp = PreferenceManager.getDefaultSharedPreferences(this)
                editor = sp.edit()

                if (!OpenCVLoader.initDebug()) {
                    Log.d(TAG, "onCreate: Unable to init openCV")
                }

                binding.rescanScale.setOnClickListener {
                    updateUIDetectingText()
                    //showAid()
                }

                binding.completeAction.setOnClickListener {
                    confirmAndFinish()
                }

                binding.imgClose.setOnClickListener {
                    finish()
                }

                binding.quantityEdit.addTextChangedListener(textChangedListener)

                binding.detectWeight.setOnClickListener {
                    binding.cameraView.takePicture()
                    binding.textResult.text = "Weight scanned"
                    binding.textPrompts.text = ""
                    binding.rescanScale.show()
                    binding.detectWeight.hide()
                    binding.imgFreeze.show()
                    updateProgress(50.0)
                    showAid()
                }
                binding.cameraView.addFrameProcessor(CameraFrameProcessors.OCRProcessor(this@OpticalScanner) { string ->
                    string?.let { url ->
                        //updateUITextDetected(url)
                    }
                })

                updateUIDetectingText()
                setCameraListeners()
                showGuideDialog()
            }
        }
    }

    private fun startAidedInputCountDown(){
        timer.start()
    }

    private fun showAid() {
//        if ((!isRunning && detectedCount >= 1) || detectedCount > 2) {
//            runOnUiThread {
//                binding.layoutManualInput.show()
//            }
//        }

        runOnUiThread {
            binding.layoutManualInput.show()
        }
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
                        result.toBitmap {
                            binding.imgFreeze.setImageBitmap(it)
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
        if (::binding.isInitialized){
            binding.cameraView.open()
        }
        
    }

    override fun onPause() {
        super.onPause()
         if (::binding.isInitialized){
            binding.cameraView.close()
         }
    }

    private fun onResult(result: String?) {
        Log.d(TAG, "Result is $result")
        //binding.textResult.text = "${result}Kg"
    }

    private fun updateUITextDetected(result: String?) {
        if (binding.textResult.text.toString().isNotEmpty()){
            return
        }
        isWeightTyped = false
        binding.cameraView.takePicture()
        detectedCount += 1
        binding.apply {
            textResult.text = "${result?.toDouble()?.div(100.0)}Kg"
            textPrompts.text = "Quantity in bag"
            rescanScale.show()
            completeAction.isEnabled = true
            updateProgress(100.0)
        }
    }

    private fun updateUITextDetectedManual(result: String) {
        if (result.endsWith(".")){
            return
        }
        if (result.isEmpty()){
            binding.textResult.text = "Weight scanned"
            binding.textPrompts.text = ""
            updateProgress(50.0)
            binding.completeAction.isEnabled = false
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
            binding.quantityEdit.removeTextChangedListener(textChangedListener)
            binding.quantityEdit.setText("")
            textResult.text = ""
            textPrompts.text = "Click 'Detect Weight' to start detecting..."
            rescanScale.hide()
            completeAction.isEnabled = false
            imgFreeze.hide()
            detectWeight.show()
            layoutManualInput.hide()
            updateProgress()
            binding.quantityEdit.addTextChangedListener(textChangedListener)
        }
    }

    private fun confirmAndFinish() {
        val intent = Intent()
        val kgString = binding.textResult.text.toString().replace("Kg","")
        intent.putExtra(EXTRA_OCR_IMAGE_LOCATION, ocrImageName)
        intent.putExtra(EXTRA_OCR_SCAN_RESULT, if (isWeightTyped)
            kgString.toDouble().toString()
        else
            kgString.toDouble().div(100.0).toString())
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

    private fun showGuideDialog() {
        val showGuide = sp.getBoolean(PREF_SHOW_GUIDE_DIALOG, true)
        if (!showGuide){
            return
        }
        val guideBinding = DialogOcrGuideBinding.inflate(layoutInflater)
        val builder = android.app.Dialog(this@OpticalScanner, R.style.AlertDialogTheme)
        Objects.requireNonNull(builder.window)?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        builder.setContentView(guideBinding.root)
        builder.setCancelable(false)

        guideBinding.dismiss.setOnClickListener {
            if (guideBinding.showAgain.isChecked){
                editor.putBoolean(PREF_SHOW_GUIDE_DIALOG, false)
                editor.commit()
            }
            builder.dismiss()
        }

        builder.show()
    }

    companion object {
        const val TAG = "CameraXDemo"
    }

    private val textChangedListener = object : TextWatcher {
        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {

        }

        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {

        }

        override fun afterTextChanged(it: Editable?) {
            updateUITextDetectedManual(it.toString())
            isWeightTyped = true
        }
    }

    val timer = object: CountDownTimer(20000, 1000) {
        override fun onTick(millisUntilFinished: Long) {
            isRunning = true
        }

        override fun onFinish() {
            isRunning = false
            showAid()
        }
    }
}
