package com.mardillu.multiscanner.ui.fingerprint

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mardillu.multiscanner.databinding.ActivityFingerScannerBinding
import com.mardillu.multiscanner.utils.*
import com.mx.finger.alg.MxISOFingerAlg
import com.mx.finger.api.msc.MxIsoMscFingerApiFactory
import com.mx.finger.api.msc.MxMscBigFingerApi
import com.mx.finger.common.MxImage
import com.mx.finger.utils.RawBitmapUtils
import org.zz.jni.FingerLiveApi
import java.util.concurrent.Executors

class FingerprintScanner : AppCompatActivity() {
    private lateinit var binding: ActivityFingerScannerBinding
    private val TIME_OUT = 300000L //5 mins
    private val executor = Executors.newSingleThreadExecutor()
    private val featureBufferEnroll: ArrayList<ByteArray?> = arrayListOf(ByteArray(1), ByteArray(1))
    private var featureBufferMatch: ByteArray? = ByteArray(1)
    private lateinit var mxFingerAlg: MxISOFingerAlg
    private lateinit var mxMscBigFingerApi: MxMscBigFingerApi

    private var scanType = SCAN_TYPE_FINGERPRINT_ENROL

    var lfdEnabled = false
    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ActivityFingerScannerBinding.inflate(layoutInflater)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val fingerFactory = MxIsoMscFingerApiFactory(application)
        mxMscBigFingerApi = fingerFactory.api
        mxFingerAlg = fingerFactory.alg

        val i = FingerLiveApi.initAlg("")
        if (i == 0) {
            lfdEnabled = true
        }

        updateProgress(0.0)

        scanType = intent.getIntExtra(EXTRA_SCAN_TYPE, SCAN_TYPE_FINGERPRINT_ENROL)
        if (!SCAN_TYPES.contains(scanType)){
            scanType = SCAN_TYPE_FINGERPRINT_ENROL
        }
        disableActionButton()

        when (scanType) {
            SCAN_TYPE_FINGERPRINT_ENROL -> {
                showFingerImage(null)
                //resetViews()
                enrolFinger(0)
            }
            SCAN_TYPE_FINGERPRINT_MATCH -> {
                val rightProfile = intent.getByteArrayExtra(EXTRA_RIGHT_THUMB_PROFILE,)
                val leftProfile = intent.getByteArrayExtra(EXTRA_LEFT_THUMB_PROFILE,)

                featureBufferEnroll.clear()
                featureBufferEnroll.add(rightProfile)
                featureBufferEnroll.add(leftProfile)

                showFingerImage(null)
                //resetViews()
                matchFinger()
            }
        }

        binding.completeAction.setOnClickListener {
            setResultSuccessfulAndClose()
        }
        binding.imgClose.setOnClickListener {
            setResultFailAndClose()
        }
    }

    private fun enrolFinger(index: Int) {
        executor.execute {
            showPromptRightThumb()

            if (index != 0){
                showPromptLeftThumb()
                Thread.sleep(2000)
            }

            //showbinding.progressBarDialog("Please press finger ")
            try {
                //step 0 get finger image
                val result =
                    mxMscBigFingerApi.getFingerImageBig(TIME_OUT)
                if (!result.isSuccess) {
                    showToast(
                        """
                                Scan failed. 
                                Try again
                                """.trimIndent(),
                    )
                    enrolFinger(index)
                    return@execute
                }
                val image = result.data!!
                // step 0.5 check lfd
                if (lfdEnabled) {
                    val lfdDetectResult = IntArray(1)
                    val i = FingerLiveApi.fingerLiveWithLevel(
                        image.data,
                        image.width,
                        image.height,
                        3,
                        lfdDetectResult
                    )
                    if (i != 0) {
                        showToast(
                            "LFD Error. Try again",
                        )
                        enrolFinger(index)
                        return@execute
                    }
                    /*result 1 mean real finger*/if (lfdDetectResult[0] == 0) {
                        showToast(
                            "LFD Error, no finger detected. Try again",
                        )
                        enrolFinger(index)
                        return@execute
                    }
                }
                val qualityScore =
                    mxFingerAlg.getQualityScore(image.data, image.width, image.height)
                updateProgress(qualityScore.toDouble())
                if (qualityScore < 70) {
                    showToast(
                        "Quality too low. Scan again",
                    )
                    enrolFinger(index)
                    return@execute
                } else {

                }

                //step 1 extract finger feature
                showFingerImage(image)
                featureBufferEnroll[index] =
                    mxFingerAlg.extractFeature(
                        image.data,
                        image.width,
                        image.height
                    )
                if (featureBufferEnroll[index] == null) {
                    showToast(
                        "Enrollment failed ",
                    )
                } else {
                    showToast(
                        "Enrollment successful ",
                    )
                    if (index == 0) {
                        enrolFinger(1)
                    } else {
                        enableActionButton()
                    }
                }
            } finally {
                //dismissbinding.progressBarDialog()
            }
        }
    }

    private fun matchFinger(sleep: Boolean = false){
        executor.execute {
            showPromptNeutralFinger()

            if (sleep){
                Thread.sleep(2000)
            }

            //showbinding.progressBarDialog("Please press finger ")
            try {
                //step 0 get finger image
                val result =
                    mxMscBigFingerApi.getFingerImageBig(TIME_OUT)
                if (!result.isSuccess) {
                    showToast(
                        """
                                Scan failed. 
                                Try again
                                """.trimIndent(),
                        )
                    matchFinger(true)
                    return@execute
                }
                // step 0.5 check lfd
                val image = result.data!!
                // step 0.5 check lfd
                if (lfdEnabled) {
                    val lfdDetectResult = IntArray(1)
                    val i = FingerLiveApi.fingerLiveWithLevel(
                        image.data,
                        image.width,
                        image.height,
                        3,
                        lfdDetectResult
                    )
                    if (i != 0) {
                        showToast(
                            "LFD Error, try again",
                        )
                        matchFinger(true)
                        return@execute
                    }
                    /*result 1 mean real finger*/if (lfdDetectResult[0] == 0) {
                        showToast(
                            "LFD Error, no finger detected. Try again",
                            )
                        matchFinger(true)
                        return@execute
                    }
                }
                val qualityScore =
                    mxFingerAlg.getQualityScore(image.data, image.width, image.height)
                updateProgress(qualityScore.toDouble())
                if (qualityScore < 70) {
                    showToast(
                        "Quality too low. Scan again",
                    )
                    matchFinger(true)
                    return@execute
                } else {

                }
                showFingerImage(image)
                //step 1 get finger feature
                featureBufferMatch =
                    mxFingerAlg.extractFeature(
                        image.data,
                        image.width,
                        image.height
                    )
                if (featureBufferMatch == null) {
                    showToast(
                        "Extract failed. Try again",
                    )
                    matchFinger(true)
                    return@execute
                }
                //step 2 match finger feature
                val match =
                    mxFingerAlg.match(featureBufferEnroll[0]!!, featureBufferMatch!!, 3)
                val match2 =
                    mxFingerAlg.match(featureBufferEnroll[1]!!, featureBufferMatch!!, 3)
                if (match == 0 || match2 == 0) {
                    showToast(
                        "Fingerprint matched found",
                    )
                    enableActionButton()
                } else {
                    showToast(
                        "Match failed. Try again",
                    )
                    matchFinger(true)
                }
            } finally {
                //dismissbinding.progressBarDialog()
            }
        }
    }

    private fun showFingerImage(image: MxImage?) {
        runOnUiThread {
            val bitmap = if (image == null) null else RawBitmapUtils.raw2Bimap(
                image.data,
                image.width,
                image.height
            )
            if (bitmap == null) {
                binding.fingerImage.visibility = View.GONE
            } else {
                binding.fingerImage.visibility = View.VISIBLE
                binding.fingerImage.setImageBitmap(bitmap)
                binding.fingerImage.tag = image
            }
        }
    }
    
    private fun showToast(message: String){
        runOnUiThread {
            Toast.makeText(this@FingerprintScanner,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateProgress(perc: Double = 0.0){
        runOnUiThread {
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
    }

    private fun showPromptRightThumb(){
        runOnUiThread {
            binding.promptBody.text = "Scan farmer thumb to enrol fingerprint"
            binding.promptHead.text = "Scan RIGHT thumb"
        }
    }

    private fun showPromptLeftThumb(){
        runOnUiThread {
            binding.promptBody.text = "Scan farmer thumb to enrol fingerprint"
            binding.promptHead.text = "Scan LEFT thumb"
        }
    }

    private fun showPromptNeutralFinger(){
        runOnUiThread {
            binding.promptBody.text = "Scan farmer thumb to verify farmer"
            binding.promptHead.text = "Scan farmer thumb"
        }
    }

    private fun enableActionButton(){
        runOnUiThread {
            if (scanType == SCAN_TYPE_FINGERPRINT_MATCH) {
                binding.completeAction.text = "Complete Enrolment"
            } else {
                binding.completeAction.text = "Complete Verification"
            }

            binding.completeAction.isEnabled = true
        }
    }

    private fun disableActionButton(){
        runOnUiThread {
            if (scanType == SCAN_TYPE_FINGERPRINT_MATCH) {
                binding.completeAction.text = "Complete Enrolment"
            } else {
                binding.completeAction.text = "Complete Verification"
            }

            binding.completeAction.isEnabled = false
        }
    }

    private fun setResultSuccessfulAndClose(){
        val intent = Intent()
        if (scanType == SCAN_TYPE_FINGERPRINT_MATCH) {
            setResult(RESULT_MATCH_FOUND, intent)
        } else {
            intent.putExtra(EXTRA_RIGHT_THUMB_PROFILE, featureBufferEnroll[0])
            intent.putExtra(EXTRA_LEFT_THUMB_PROFILE, featureBufferEnroll[1])
            setResult(RESULT_ENROLMENT_SUCCESSFUL, intent)
        }

        finish()
    }

    private fun setResultFailAndClose(){
        val intent = Intent()
        if (scanType == SCAN_TYPE_FINGERPRINT_MATCH) {
            setResult(RESULT_MATCH_FAILED, intent)
        } else {
            setResult(RESULT_ENROLMENT_FAILED, intent)
        }

        finish()
    }
}