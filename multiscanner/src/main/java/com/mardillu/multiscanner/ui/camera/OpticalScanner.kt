package com.mardillu.multiscanner.ui.camera

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.mardillu.multiscanner.databinding.ActivityOcrScannerBinding
import com.mardillu.multiscanner.utils.*
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.PictureResult
import com.otaliastudios.cameraview.VideoResult
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc


class OpticalScanner : AppCompatActivity(), CameraBridgeViewBase.CvCameraViewListener2{

    private lateinit var binding: ActivityOcrScannerBinding
    private var ocrImageName = ""
    private lateinit var analyser: CameraFrameProcessors.OCRProcessor
    var isRunning = false
    var detectedCount = 0
    var isWeightTyped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrScannerBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)

        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "onCreate: Unable to init openCV")
        }

        binding.rescanScale.setOnClickListener {
            updateUIDetectingText()
            showAid()
        }

        binding.completeAction.setOnClickListener {
            confirmAndFinish()
        }

        binding.imgClose.setOnClickListener {
            finish()
        }

        binding.quantityEdit.addTextChangedListener(textChangedListener)

        //binding.cameraView.filter = MultiFilter(Filters.BLACK_AND_WHITE.newInstance(), Filters.CONTRAST.newInstance(),)

        when (intent.getIntExtra(EXTRA_SCAN_TYPE, SCAN_TYPE_BARCODE)) {
            SCAN_TYPE_BARCODE,
            SCAN_TYPE_QR_CODE -> {
//                binding.cameraView.addFrameProcessor(CameraFrameProcessors.BarcodeProcessor { string ->
//                    string?.let { url ->
//                        onResult(url)
//                    }
//                })
            }
            SCAN_TYPE_OCR -> {
                binding.cameraView.addFrameProcessor(CameraFrameProcessors.OCRProcessor(this@OpticalScanner) { string ->
                    string?.let { url ->
                        updateUITextDetected(url)
                    }
                })
            }
        }

        updateUIDetectingText()
        setCameraListeners()
        startAidedInputCountDown()
    }

    private fun startAidedInputCountDown(){
        timer.start()
    }

    private fun showAid() {
        if ((!isRunning && detectedCount > 1) || detectedCount > 3) {
            runOnUiThread {
                binding.layoutManualInput.show()
            }
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
        isWeightTyped = false
        binding.cameraView.takePicture()
        detectedCount += 1
        binding.apply {
            textResult.text = "${result}Kg"
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
            updateUIDetectingText()
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
            textPrompts.text = "Detecting weight..."
            rescanScale.hide()
            completeAction.isEnabled = false
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

    companion object {
        const val TAG = "CameraXDemo"
    }

    override fun onCameraViewStarted(width: Int, height: Int) {

    }

    override fun onCameraViewStopped() {

    }

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame?): Mat? {
        val mInput = inputFrame?.gray()
        Imgproc.erode(inputFrame?.gray(),
                mInput,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0)))

        Imgproc.dilate(mInput,
                mInput,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0)))
        analyser.analyse()

        return inputFrame?.gray()
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

    val timer = object: CountDownTimer(180000, 1000) {
        override fun onTick(millisUntilFinished: Long) {
            isRunning = true
        }

        override fun onFinish() {
            isRunning = false
            showAid()
        }
    }
}