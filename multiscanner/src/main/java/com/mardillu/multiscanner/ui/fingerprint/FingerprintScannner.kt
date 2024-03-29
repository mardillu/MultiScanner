package com.mardillu.multiscanner.ui.fingerprint

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.SharedPreferences.Editor
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.*
import android.preference.PreferenceManager
import android.util.Log
import android.view.MenuInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.fpreader.fpcore.FPFormat
import com.fpreader.fpcore.FPMatch
import com.fpreader.fpdevice.AsyncBluetoothReader
import com.fpreader.fpdevice.BluetoothReader
import com.google.firebase.analytics.FirebaseAnalytics
import com.mardillu.multiscanner.R
import com.mardillu.multiscanner.databinding.ActivityFingerScannerBinding
import com.mardillu.multiscanner.databinding.DialogDeviceListBinding
import com.mardillu.multiscanner.databinding.ItemBluetoothDeviceBinding
import com.mardillu.multiscanner.utils.*
import com.mx.finger.alg.MxISOFingerAlg
import com.mx.finger.api.msc.MxIsoMscFingerApiFactory
import com.mx.finger.api.msc.MxMscBigFingerApi
import com.mx.finger.common.MxImage
import com.mx.finger.utils.RawBitmapUtils
import org.zz.jni.FingerLiveApi
import java.util.*
import java.util.concurrent.Executors
import kotlin.concurrent.schedule
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.ktx.Firebase
import com.google.firebase.FirebaseApp


class FingerprintScanner : AppCompatActivity() {
    private lateinit var binding: ActivityFingerScannerBinding
    private lateinit var bluetoothBinding: DialogDeviceListBinding
    private lateinit var builder: Dialog
    private val TIME_OUT = 300000L //5 mins
    private var executor = Executors.newSingleThreadExecutor()
    private val featureBufferEnroll: ArrayList<ByteArray?> = arrayListOf(ByteArray(1), ByteArray(1))
    private var featureBufferMatch: ByteArray? = ByteArray(1)
    private var allFarmersFingerProfiles: ArrayList<ByteArray?> = ArrayList()
    private lateinit var mxFingerAlg: MxISOFingerAlg
    private lateinit var mxMscBigFingerApi: MxMscBigFingerApi
    private var mBtAdapter: BluetoothAdapter? = null
    private var scannerSource = SOURCE_INBUILT_READER
    private var asyncBluetoothReader: AsyncBluetoothReader? = AsyncBluetoothReader()
    var pairedDevices: ArrayList<BluetoothDevice>? = null
    private var scanType = SCAN_TYPE_FINGERPRINT_ENROL
    private var fingerIndex = 0
    var lfdEnabled = false
    val compatibilityMode = true
    private lateinit var firebaseAnalytics: FirebaseAnalytics
    private val pid = System.currentTimeMillis()

    var latestBTImage: ByteArray? = null
    var latestBTProfile: ByteArray? = null
    private val mFPFormat = FPFormat.ANSI_378_2004

    private lateinit var sp: SharedPreferences
    private var preBluetoothDeviceAddress: String? = null
    private lateinit var editor: Editor

    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ActivityFingerScannerBinding.inflate(layoutInflater)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

//        FirebaseApp.initializeApp(this)
//        firebaseAnalytics = Firebase.analytics

        val fingerFactory = MxIsoMscFingerApiFactory(application)
        mxMscBigFingerApi = fingerFactory.api
        mxFingerAlg = fingerFactory.alg

        val i = FingerLiveApi.initAlg("")
        if (i == 0) {
            lfdEnabled = true
        }

        updateProgress(0.0)

        scanType = intent.getIntExtra(EXTRA_SCAN_TYPE, SCAN_TYPE_FINGERPRINT_ENROL)
        scannerSource = if(Build.MODEL == "Q807") SOURCE_INBUILT_READER else SOURCE_EXTERNAL_BT_READER
        getByteArrayFromStringArray(intent.getStringArrayListExtra(EXTRA_FARMERS_FINGERPRINT_PROFILES))

        sp = PreferenceManager.getDefaultSharedPreferences(this)
        editor = sp.edit()
        preBluetoothDeviceAddress = sp.getString(PREF_PREV_BLUETOOTH_DEVICE_ADDRESS, "")

        if (!SCAN_TYPES.contains(scanType)) {
            scanType = SCAN_TYPE_FINGERPRINT_ENROL
        }
        disableActionButton()

        when (scanType) {
            SCAN_TYPE_FINGERPRINT_ENROL -> {
                showFingerImage(null)
                if (scannerSource == SOURCE_INBUILT_READER){
                    resetForInbuiltReader()
                } else {
                    resetForBluetooth(false)
                }
            }
            SCAN_TYPE_FINGERPRINT_MATCH -> {
                val rightProfile = intent.getByteArrayExtra(EXTRA_RIGHT_THUMB_PROFILE)
                val leftProfile = intent.getByteArrayExtra(EXTRA_LEFT_THUMB_PROFILE)

                featureBufferEnroll.clear()
                featureBufferEnroll.add(rightProfile)
                featureBufferEnroll.add(leftProfile)
                allFarmersFingerProfiles.add(rightProfile)
                allFarmersFingerProfiles.add(leftProfile)

                showFingerImage(null)

                if (scannerSource == SOURCE_INBUILT_READER){
                    matchFinger()
                } else {
                    resetForBluetooth(false)
                }
            }
        }

        binding.completeAction.setOnClickListener {
            setResultSuccessfulAndClose()
        }
        binding.imgClose.setOnClickListener {
            setResultFailAndClose()
        }
        binding.layoutMenu.setOnClickListener { v ->
            val popup = PopupMenu(this@FingerprintScanner, v)
            val inflater: MenuInflater = popup.getMenuInflater()
            inflater.inflate(R.menu.fingerprint_options, popup.getMenu())

            popup.setOnMenuItemClickListener { item ->
                val i: Int = item.getItemId()
                if (i == R.id.connect_new_device) {
                    resetForBluetooth(true)
                    return@setOnMenuItemClickListener true
                } else if (i == R.id.use_inbuilt_reader) {
                    resetForInbuiltReader()
                    return@setOnMenuItemClickListener true
                } else if (i == R.id.use_bluetooth_reader) {
                    resetForBluetooth(false)
                    return@setOnMenuItemClickListener true
                } else if (i == R.id.close) {
                    setResultFailAndClose()
                    return@setOnMenuItemClickListener true
                } else {
                    return@setOnMenuItemClickListener false
                }
            }
            popup.show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if(resultCode == RESULT_OK) {
            when(requestCode){
                REQUEST_ENABLE_BT -> {
                    initBluetooth()
                }
            }
        }
    }

    private fun getByteArrayFromStringArray(profiles: ArrayList<String>?) {
        profiles?.forEach {
            val byteArray = it.toCustomByteArray()
            allFarmersFingerProfiles.add(byteArray)
        }
    }

    private fun initBluetooth(){
        if(ContextCompat.checkSelfPermission(
                this@FingerprintScanner,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {
            val PERMISSIONS = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            requestPermissionLauncher.launch(
                    PERMISSIONS
            )
            return
        } else if (ContextCompat.checkSelfPermission(
                    this@FingerprintScanner,
                    Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val PERMISSIONS = arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
            )

            requestPermissionLauncher2.launch(
                    PERMISSIONS
            )
            return
        }

        // Get the local Bluetooth adapter
        mBtAdapter = BluetoothAdapter.getDefaultAdapter()

        if (mBtAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }

        bluetoothBinding = DialogDeviceListBinding.inflate(layoutInflater)
        builder = Dialog(this@FingerprintScanner, R.style.AlertDialogTheme)
        Objects.requireNonNull(builder.window)?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        builder.setContentView(bluetoothBinding.root)
        builder.setCancelable(false)

        // Get a set of currently paired devices
        pairedDevices = ArrayList(mBtAdapter!!.bondedDevices)

        // Register for broadcasts when a device is discovered
        var filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        this.registerReceiver(mReceiver, filter)

        // Register for broadcasts when discovery has finished
        filter = IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        this.registerReceiver(mReceiver, filter)

        initBluetoothListeners()
        if (preBluetoothDeviceAddress.isNullOrEmpty()) {
            showBluetoothListDialog()
            doDiscovery()
        } else {
            connectDevice(preBluetoothDeviceAddress!!)
        }
    }

    private fun resetForBluetooth(hard: Boolean = false){
        latestBTImage = null
        latestBTProfile = null
        if (hard){
            preBluetoothDeviceAddress = null
        }
        fingerIndex = 0
        if (scanType == SCAN_TYPE_FINGERPRINT_ENROL) {
            featureBufferEnroll[0] = null
            featureBufferEnroll[1] = null
        }
        stopExecutor()
        asyncBluetoothReader!!.start()
        disableActionButton()
        showPromptRightThumb()
        updateProgress(0.0)
        initBluetooth()
    }

    private fun resetForInbuiltReader(){
        executor = Executors.newSingleThreadExecutor()
        asyncBluetoothReader!!.stop()
        try {
            unregisterReceiver(mReceiver)
        }catch (e: Exception){}
        if (scanType == SCAN_TYPE_FINGERPRINT_ENROL) {
            featureBufferEnroll[0] = null
            featureBufferEnroll[1] = null
        }
        disableActionButton()
        showPromptRightThumb()
        updateProgress(0.0)
        if (scanType == SCAN_TYPE_FINGERPRINT_MATCH){
            matchFinger()
        } else {
            enrolFinger(0)
        }
    }

    private fun enrolFinger(index: Int) {
        if (executor.isShutdown){
            return
        }
        executor.execute {
            showPromptRightThumb()

            if (index != 0){
                showPromptLeftThumb()
            }

            Thread.sleep(30000)

            //showbinding.progressBarDialog("Please press finger ")
            try {
                //step 0 get finger image
                val result =
                    mxMscBigFingerApi.getFingerImageBig(TIME_OUT)
                if (!result.isSuccess) {
                    showErrorToast(
                        getString(R.string.scan_failed_try_again).trimIndent(),
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
                        showErrorToast(
                            getString(R.string.lfd_error_try_again),
                        )
                        logEvent(0, "LFD init Error")
                        enrolFinger(index)
                        return@execute
                    }
                    /*result 1 mean real finger*/if (lfdDetectResult[0] == 0) {
                        showErrorToast(
                            getString(R.string.lfd_error_no_finger_detected_try_again),
                        )
                        logEvent(0, "LFD Error, no finger detected")
                        enrolFinger(index)
                        return@execute
                    }
                }
                val qualityScore =
                    mxFingerAlg.getQualityScore(image.data, image.width, image.height)
                updateProgress(qualityScore.toDouble())
                if (qualityScore < 30) {
                    showErrorToast(
                        getString(R.string.quality_too_low_scan_again),
                    )
                    logEvent(0, "quality less than 30", qualityScore)
                    enrolFinger(index)
                    return@execute
                } else {
                    logEvent(1, "quality over 30", qualityScore)
                }

                //step 1 extract finger feature
                showFingerImage(image)
                val profile = mxFingerAlg.extractFeature(
                        image.data,
                        image.width,
                        image.height
                )
                if (profile == null) {
                    showErrorToast(
                        getString(R.string.enrollment_failed_try_again),
                    )
                    logEvent(0, "could not extract profile from image")
                    enrolFinger(index)
                    return@execute
                } else {
                    if (!isFingerPrintUniqueLegacy(profile, allFarmersFingerProfiles) ||
                        !isFingerPrintUnique(profile, allFarmersFingerProfiles)) {
                        showErrorToast(
                            getString(R.string.looks_like_this_finger_has_been_scanned_already_please_scan_a_different_finger),
                        )
                        logEvent(0, "Thumb already scanned")
                        enrolFinger(index)
                        return@execute
                    }
                    featureBufferEnroll[index] = profile
                    if (index == 0) {
                        showSuccessToast(
                            getString(R.string.right_thumb_captured_successfully),
                        )
                        allFarmersFingerProfiles.add(profile)
                        logEvent(1, "Right thumb scanned")
                        enrolFinger(1)
                    } else {
                        showSuccessToast(
                            getString(R.string.enrollment_complete),
                        )
                        logEvent(1, "Left thumb scanned")
                        enableActionButton()
                    }
                }
            } finally {
                //dismissbinding.progressBarDialog()
            }
        }
    }

    private fun logEvent(result: Int, reason: String, score: Int = -1) {
        if(1==1) return
        firebaseAnalytics.logEvent("fingerprint_scan") {
            param("scan_type", scanType.toString())
            param("pid", pid)
            param("scan_source", scannerSource.toString())
            param("scan_result", result.toString())
            param("reason_for_result", reason)
            param("score", score.toString())
        }
        Log.d("TAG", "logEvent: Logging GA Event")
    }

    private fun enrolBTFinger() {
        latestBTImage = null
        latestBTProfile = null
        Timer("SettingUp", false).schedule(1500) {
            asyncBluetoothReader!!.GetImageAndTemplate()
        }
    }

    private fun processBTFP(){
        if (latestBTImage == null || latestBTProfile == null){
            return
        }
        val bmp = BitmapFactory.decodeByteArray(latestBTImage, 0, latestBTImage!!.size)
        val qualityScore =
            mxFingerAlg.getQualityScore(latestBTImage, bmp.width, bmp.height)
        updateProgress(qualityScore.toDouble())
        if (qualityScore < 30) {
            showErrorToast(
                getString(R.string.quality_too_low_scan_again),
            )
            logEvent(0, "quality less than 30", qualityScore)
            enrolBTFinger()
            return
        } else {
            logEvent(1, "quality over 30", qualityScore)
            val isUnique = !isFingerPrintUnique(latestBTProfile, allFarmersFingerProfiles) ||
                    !isFingerPrintUniqueLegacy(latestBTProfile, allFarmersFingerProfiles)

            if (isUnique) {
                showErrorToast(
                    getString(R.string.looks_like_this_finger_has_been_scanned_already_please_scan_a_different_finger),
                )
                logEvent(0, "Thumb already scanned")
                enrolBTFinger()
                return
            }

            featureBufferEnroll[fingerIndex] = latestBTProfile
            if (fingerIndex >= NUM_FINGERS_TO_SCAN){
                showSuccessToast(
                    getString(R.string.enrollment_complete),
                )
                logEvent(1, "Left thumb scanned")
                enableActionButton()
            } else {
                showSuccessToast(
                    getString(R.string.right_thumb_captured_successfully),
                )
                logEvent(1, "Right thumb scanned")
                allFarmersFingerProfiles.add(latestBTProfile!!)
                showPromptLeftThumb()
                fingerIndex += 1
                enrolBTFinger()
            }
        }
    }

    private fun processBTFPMatch(){
        if (latestBTImage == null || latestBTProfile == null){
            return
        }
        val bmp = BitmapFactory.decodeByteArray(latestBTImage, 0, latestBTImage!!.size)
        val qualityScore =
            mxFingerAlg.getQualityScore(latestBTImage, bmp.width, bmp.height)
        updateProgress(qualityScore.toDouble())
        if (qualityScore < 30) {
            showErrorToast(
                getString(R.string.quality_too_low_scan_again),
            )
            logEvent(0, "quality less than 30", qualityScore)
            enrolBTFinger()
            return
        } else {
            logEvent(1, "quality over 30", qualityScore)
            val isUnique = !isFingerPrintUnique(latestBTProfile, allFarmersFingerProfiles) ||
                    !isFingerPrintUniqueLegacy(latestBTProfile, allFarmersFingerProfiles)

            if (isUnique) {
                showSuccessToast(
                    getString(R.string.fingerprint_matched_found),
                )
                logEvent(1, "Match found")
                enableActionButton()
            } else {
                showErrorToast(
                    getString(R.string.match_failed_try_again),
                )
                logEvent(0, "Match failed")
                enrolBTFinger()
            }
        }
    }

    private fun matchFinger(sleep: Boolean = false){
        if (executor.isShutdown){
            return
        }
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
                    showErrorToast(
                        getString(R.string.scan_failed_try_again).trimIndent(),
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
                        showErrorToast(
                            getString(R.string.lfd_error_try_again),
                        )
                        logEvent(0, "LFD init Error")
                        matchFinger(true)
                        return@execute
                    }
                    /*result 1 mean real finger*/if (lfdDetectResult[0] == 0) {
                        showErrorToast(
                            getString(R.string.lfd_error_no_finger_detected_try_again),
                            )
                        logEvent(0, "LFD Error, no finger detected")
                        matchFinger(true)
                        return@execute
                    }
                }
                val qualityScore =
                    mxFingerAlg.getQualityScore(image.data, image.width, image.height)
                updateProgress(qualityScore.toDouble())
                if (qualityScore < 30) {
                    showErrorToast(
                        getString(R.string.quality_too_low_scan_again),
                    )
                    logEvent(0, "quality less than 30", qualityScore)
                    matchFinger(true)
                    return@execute
                } else {
                    logEvent(1, "quality over 30", qualityScore)
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
                    showErrorToast(
                        getString(R.string.extract_failed_try_again),
                    )
                    logEvent(0, "could not extract profile from image")
                    matchFinger(true)
                    return@execute
                }
                //step 2 match finger feature
                val match = !isFingerPrintUniqueLegacy(featureBufferMatch!!, featureBufferEnroll) ||
                        !isFingerPrintUnique(featureBufferMatch!!, featureBufferEnroll)
                if (match) {
                    showSuccessToast(
                        getString(R.string.fingerprint_matched_found),
                    )
                    logEvent(1, "Match found")
                    enableActionButton()
                } else {
                    showErrorToast(
                        getString(R.string.match_failed_try_again),
                    )
                    logEvent(0, "Match failed")
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

    private fun showBTFingerImage(bitmap: Bitmap?) {
        runOnUiThread {
            if (bitmap == null) {
                binding.fingerImage.visibility = View.GONE
            } else {
                binding.fingerImage.visibility = View.VISIBLE
                binding.fingerImage.setImageBitmap(bitmap)
                binding.fingerImage.tag = bitmap
            }
        }
    }

    private fun isFingerPrintUnique(compare: ByteArray?, with: ArrayList<ByteArray?>): Boolean {
        if (compatibilityMode) {
            return isFingerPrintUniqueLegacy(compare, with)
        }
        if (compare == null){
            return true
        }
        val mRefData = ByteArray(512)
        FPFormat.getInstance().StdToAnsiIso(compare, mRefData, FPFormat.ANSI_378_2004)

        for (profile in with){
            val adat = ByteArray(512)
            val bdat = ByteArray(512)


            val mMatData = ByteArray(512)
            FPFormat.getInstance().StdToAnsiIso(profile, mMatData, FPFormat.ANSI_378_2004)

            FPFormat.getInstance()
                .AnsiIsoToStd(mRefData, adat, FPFormat.ANSI_378_2004)
            FPFormat.getInstance()
                .AnsiIsoToStd(mMatData, bdat, FPFormat.ANSI_378_2004)
            val score = FPMatch.getInstance().MatchTemplate(adat, bdat)
            val scoreAlt = FPMatch.getInstance().MatchTemplate(compare, profile)
            val scoreAlt2 = FPMatch.getInstance().MatchTemplate(compare, profile)
            val scoreAlt3 = FPMatch.getInstance().MatchTemplate(compare, profile)

            if (score >= 60 || scoreAlt >= 60 || scoreAlt2 >= 60 || scoreAlt3 >= 60){
                return false
            }
        }

        return true
    }

    private fun isFingerPrintUniqueLegacy(compare: ByteArray?, with: ArrayList<ByteArray?>): Boolean {
        if (compare == null){
            return true
        }

        val mRefData = ByteArray(512)
        FPFormat.getInstance().StdToAnsiIso(compare, mRefData, FPFormat.ANSI_378_2004)
        for (profile in with){
            val adat = ByteArray(512)
            val bdat = ByteArray(512)
            val mMatData = ByteArray(512)
            FPFormat.getInstance().StdToAnsiIso(profile, mMatData, FPFormat.ANSI_378_2004)
            FPFormat.getInstance()
                .AnsiIsoToStd(mRefData, adat, FPFormat.ANSI_378_2004)
            FPFormat.getInstance()
                .AnsiIsoToStd(mMatData, bdat, FPFormat.ANSI_378_2004)

            val match =
                mxFingerAlg.match(adat, bdat, 3)
            val matchAlt =
                mxFingerAlg.match(compare, profile!!, 3)

            val matchAlt2 =
                mxFingerAlg.match(compare, mMatData, 3)

            val matchAlt3 =
                mxFingerAlg.match(mRefData, profile!!, 3)
            val score = FPMatch.getInstance().MatchTemplate(adat, bdat)
            val scoreAlt = FPMatch.getInstance().MatchTemplate(compare, profile)
            val scoreAlt2 = FPMatch.getInstance().MatchTemplate(compare, profile)
            val scoreAlt3 = FPMatch.getInstance().MatchTemplate(compare, profile)

            if (match == 0 || matchAlt == 0 || matchAlt2 == 0 || matchAlt3 == 0 ||
                score >= 60 || scoreAlt >= 60 || scoreAlt2 >= 60 || scoreAlt3 >= 60) {
                return false
            }
        }
        return true
    }
    
    private fun showErrorToast(message: String){
        runOnUiThread {
            binding.statusMessage.setTextColor(resources.getColor(R.color.errorRed))
            binding.statusMessage.text = message
        }
    }

    private fun showSuccessToast(message: String){
        runOnUiThread {
            binding.statusMessage.setTextColor(resources.getColor(R.color.greenPrimary))
            binding.statusMessage.text = message

            //success messages should not stay too long onn the screen because they can potentially give a false impression after a while
            //So we remove them after 4 secs of showing them
            val timer = object: CountDownTimer(4000, 1000) {
                override fun onTick(millisUntilFinished: Long) {}

                override fun onFinish() {
                    showErrorToast("")
                }
            }
            timer.start()
        }
    }

    private fun updateProgress(perc: Double = 0.0){
        runOnUiThread {
            binding.progressBar.setProgressPercentage(perc, true)

            if (perc >= 30.0) {
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
            binding.promptBody.text = getString(R.string.scan_farmer_thumb_to_enrol_fingerprint)
            binding.promptHead.text = getString(R.string.scan_right_thumb)
            binding.promptSubBody.text = getString(R.string.press_the_centre_of_your_right_thumb_on_the_sensor_then_lift_it_off_when_the_progress_turns_green)
            binding.secondaryPromptHead.text = getString(R.string.scan_right_thumb)
        }
    }

    private fun showPromptLeftThumb(){
        runOnUiThread {
            binding.promptBody.text = getString(R.string.scan_farmer_thumb_to_enrol_fingerprint)
            binding.promptHead.text = getString(R.string.scan_left_thumb)
            binding.promptSubBody.text =
                getString(R.string.press_the_centre_of_your_left_thumb_on_the_sensor_then_lift_it_off_when_the_progress_turns_green)
            binding.secondaryPromptHead.text = getString(R.string.scan_left_thumb)
        }
    }

    private fun showPromptNeutralFinger(){
        runOnUiThread {
            binding.promptBody.text = getString(R.string.scan_farmer_thumb_to_verify_farmer)
            binding.promptHead.text = getString(R.string.scan_farmer_thumb)
            binding.promptSubBody.text =
                getString(R.string.press_the_centre_of_your_thumb_on_the_sensor_then_lift_it_off_when_the_progress_turns_green)
            binding.secondaryPromptHead.text = getString(R.string.scan_farmer_thumb)
        }
    }

    private fun enableActionButton(){
        runOnUiThread {
            if (scanType == SCAN_TYPE_FINGERPRINT_MATCH) {
                binding.completeAction.text = getString(R.string.complete_verification)
            } else {
                binding.completeAction.text = getString(R.string.complete_enrolment)
            }

            binding.completeAction.isEnabled = true
        }
    }

    private fun disableActionButton(){
        runOnUiThread {
            if (scanType == SCAN_TYPE_FINGERPRINT_MATCH) {
                binding.completeAction.text = getString(R.string.complete_verification)
            } else {
                binding.completeAction.text = getString(R.string.complete_enrolment)
            }

            binding.completeAction.isEnabled = false
        }
    }

    private fun setResultSuccessfulAndClose(){
        stopExecutor()
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

    private fun stopExecutor() {
        if (!executor.isShutdown){
            executor.shutdownNow()
        }
    }

    private fun setResultFailAndClose(){
        stopExecutor()
        val intent = Intent()
        if (scanType == SCAN_TYPE_FINGERPRINT_MATCH) {
            setResult(RESULT_MATCH_FAILED, intent)
        } else {
            setResult(RESULT_ENROLMENT_FAILED, intent)
        }

        finish()
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothListDialog(){
        // If there are paired devices, add each one to the ArrayAdapter
        val filteredDevices = getRelevantDevices(pairedDevices!!)
        if (filteredDevices.isNotEmpty()) {
            bluetoothBinding.layoutDevicesList.removeAllViews()
            if (!builder.isShowing){
                builder.show()
            }
            bluetoothBinding.apply {
                //bluetoothBinding.progressBar.show()
                //get bluetooth devices

                filteredDevices.forEach { bt ->
                    val itemBinding = ItemBluetoothDeviceBinding.inflate(layoutInflater)
                    itemBinding.root.text = bt.name
                    bluetoothBinding.layoutDevicesList.addView(itemBinding.root)

                    itemBinding.root.setOnClickListener {
                        connectDevice(bt.address)
                        builder.dismiss()
                    }
                }
                //bluetoothBinding.progressBar.hide()
            }
        } else {
            Toast.makeText(this@FingerprintScanner,
                getString(R.string.no_bluetooth_devices_found), Toast.LENGTH_LONG).show()
            doDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun getRelevantDevices(pairedDvs: ArrayList<BluetoothDevice>): MutableSet<BluetoothDevice> {
        val devices: MutableSet<BluetoothDevice> = HashSet()
        for (dev in pairedDvs) {
            val nm = dev.name?.lowercase()?: dev.address
            if (nm.startsWith("shbt")) {
                devices.add(dev)
            }
        }
        return devices
    }

    @SuppressLint("MissingPermission")
    private fun doDiscovery() {
        bluetoothBinding.notifications.text = getString(R.string.scanning_for_new_devices)
        // If we're already discovering, stop it
        if (mBtAdapter!!.isDiscovering) {
            mBtAdapter!!.cancelDiscovery()
        }

        // Request discover from BluetoothAdapter
        mBtAdapter!!.startDiscovery()
    }

    private fun connectDevice(address: String) {
        val device: BluetoothDevice = mBtAdapter!!.getRemoteDevice(address)
        // Attempt to connect to the device
//        if (mChatService == null) setupChat()
//        mChatService!!.connect(device)
        unregisterReceiver(mReceiver)
        preBluetoothDeviceAddress = address
        editor.putString(PREF_PREV_BLUETOOTH_DEVICE_ADDRESS, address)
        editor.commit()
        asyncBluetoothReader!!.connect(device)
    }

    override fun onDestroy() {
        super.onDestroy()
        asyncBluetoothReader!!.stop()
        try {
            unregisterReceiver(mReceiver)
        } catch (e: Exception){}
    }

    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            val action = intent.action

            // When discovery finds a device
            if (BluetoothDevice.ACTION_FOUND == action) {
                // Get the BluetoothDevice object from the Intent
                val device = intent
                    .getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                // If it's already paired, skip it, because it's been listed
                // already
                if (device!!.bondState != BluetoothDevice.BOND_BONDED) {
                    pairedDevices?.add(device)
                }
                // When discovery is finished, change the Activity title
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED == action) {
                bluetoothBinding.progressBar.hide()
                bluetoothBinding.notifications.text = getString(R.string.select_bluetooth_device)
                showBluetoothListDialog()
            }
        }
    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        asyncBluetoothReader!!.start()
    }

    private fun initBluetoothListeners() {
        //asyncBluetoothReader =
        val bluetoothStateListener = asyncBluetoothReader!!.setOnBluetoothStateListener(object :
            AsyncBluetoothReader.OnBluetoothStateListener {
            override fun onBluetoothStateChange(arg: Int) {
                when (arg) {
                    BluetoothReader.STATE_CONNECTED -> {
                        Log.d("TAG", "handleMessage: MESSAGE_STATE_CHANGE STATE_CONNECTED")
                        enrolBTFinger()
                    }
                    BluetoothReader.STATE_CONNECTING -> {
                        Log.d("TAG", "handleMessage: STATE_CONNECTING")
                        showSuccessToast(
                            getString(R.string.connecting_to_device),
                        )
                    }
                    BluetoothReader.STATE_LISTEN, BluetoothReader.STATE_NONE -> {
                        Log.d("TAG", "handleMessage: STATE_LISTEN STATE_NONE")
//                    mTitle.setText(android.R.string.title_not_connected)
                    }
                }
            }

            override fun onBluetoothStateDevice(devicename: String) {
                showSuccessToast(
                    getString(R.string.connected_to, devicename),
                )
            }

            override fun onBluetoothStateLost(arg: Int) {
                when (arg) {
                    BluetoothReader.MSG_UNABLE -> {
                        Log.d("TAG", "onBluetoothStateLost: BluetoothReader.MSG_UNABLE")
                        showErrorToast(
                            getString(R.string.unable_to_connect_to_bluetooth_device),
                        )
                    }
                    BluetoothReader.MSG_LOST -> {
                        Log.d("TAG", "onBluetoothStateLost: BluetoothReader.MSG_LOST")
                        showErrorToast(
                            getString(R.string.bluetooth_device_disconnected),
                        )
                    }
                }
            }
        })
        val onDeviceInfoListener = asyncBluetoothReader!!.setOnDeviceInfoListener(object :
            AsyncBluetoothReader.OnDeviceInfoListener {
            override fun onDeviceInfoDeviceType(devicetype: String) {
//            AddStatusList(getString(android.R.string.txt_devicetype) + devicetype)
            }

            override fun onDeviceInfoDeviceSN(devicesn: String) {
//            AddStatusList(getString(android.R.string.txt_devicesn) + devicesn)
            }

            override fun onDeviceInfoDeviceSensor(sensor: Int) {
//            AddStatusList(getString(android.R.string.txt_devicesensor) + sensor.toString())
            }

            override fun onDeviceInfoDeviceVer(ver: Int) {
//            AddStatusList(getString(android.R.string.txt_devicever) + ver.toString())
            }

            override fun onDeviceInfoDeviceBat(args: ByteArray, size: Int) {
                Log.d("TAG", "onDeviceInfoDeviceBat: ${args.toString()}, $size")
//            AddStatusList(getString(android.R.string.txt_batval) + Integer.toString(args[0] / 10) + "." + Integer.toString(args[0] % 10) + "V")
            }

            override fun onDeviceInfoDeviceShutdown(arg: Int) {
//            if (arg == 1) AddStatusList(getString(android.R.string.txt_closeok)) else AddStatusList(getString(android.R.string.txt_closefail))
            }

            override fun onDeviceInfoDeviceError(arg: Int) {
//            AddStatusList(getString(android.R.string.txt_getdevicefail))
            }
        })
        val onGetStdImageListener = asyncBluetoothReader!!.setOnGetStdImageListener(object :
            AsyncBluetoothReader.OnGetStdImageListener {
            override fun onGetStdImageSuccess(data: ByteArray) {
                Log.d("TAG", "onGetStdImageSuccess: ")
//            val image = BitmapFactory.decodeByteArray(data, 0, data.size)
//            fingerprintImage!!.setImageBitmap(image)
//            //SaveImage(image);
//            AddStatusList(getString(android.R.string.txt_getimageok))
            }

            override fun onGetStdImageFail() {
                Log.d("TAG", "onGetStdImageFail: ")
//            AddStatusList(getString(android.R.string.txt_getimagefail))
            }
        })
        val onGetResImageListener = asyncBluetoothReader!!.setOnGetResImageListener(object :
            AsyncBluetoothReader.OnGetResImageListener {
            override fun onGetResImageSuccess(data: ByteArray) {
                Log.d("TAG", "onGetResImageSuccess: ")
                latestBTImage = data
                val image = BitmapFactory.decodeByteArray(data, 0, data.size)
                //featureBufferEnroll[fingerIndex] =
                showBTFingerImage(image)

                if (scanType == SCAN_TYPE_FINGERPRINT_MATCH) {
                    processBTFPMatch()
                } else {
                    processBTFP()
                }
//            //SaveImage(image);
//            AddStatusList(getString(android.R.string.txt_getimageok))
            }

            override fun onGetResImageFail() {
                Log.d("TAG", "onGetResImageFail: ")
//            AddStatusList(getString(android.R.string.txt_getimagefail))
            }
        })
        val onUpTemplateListener = asyncBluetoothReader!!.setOnUpTemplateListener(object :
            AsyncBluetoothReader.OnUpTemplateListener {
            override fun onUpTemplateSuccess(model: ByteArray) {
                latestBTProfile = model
                if (scanType == SCAN_TYPE_FINGERPRINT_MATCH){
                    processBTFPMatch()
                } else {
                    processBTFP()
                }

                Log.d("TAG", "onUpTemplateSuccess: ")

                /*
            if (worktype == 1) {
                when (mFPFormat) {
                    FPFormat.STD_TEMPLATE -> {
                        System.arraycopy(model, 0, mMatData, 0, model.size)
                        mMatSize = model.size
                    }
                    FPFormat.ANSI_378_2004 -> {
                        //mMatString=FPFormat.getInstance().To_Ansi378_2004_Base64(model);
                        //AddStatusList(mMatString);
                        FPFormat.getInstance()
                            .StdToAnsiIso(model, mMatData, FPFormat.ANSI_378_2004)
                        mMatSize = mMatData.size
                    }
                    FPFormat.ISO_19794_2005 -> {
                        //mMatString=FPFormat.getInstance().To_Iso19794_2005_Base64(model);
                        //AddStatusList(mMatString);
                        if (FPFormat.getInstance().StdToAnsiIso(model,
                                    mMatData,
                                    FPFormat.ISO_19794_2005))

                        mMatSize = mMatData.size
                    }
                }
                if (mRefSize > 0) {
                    var score = 0
                    val adat = ByteArray(512)
                    val bdat = ByteArray(512)
                    when (mFPFormat) {
                        FPFormat.STD_TEMPLATE -> score =
                            FPMatch.getInstance().MatchTemplate(mRefData, mMatData)
                        FPFormat.ANSI_378_2004 -> {
                            FPFormat.getInstance()
                                .AnsiIsoToStd(mRefData, adat, FPFormat.ANSI_378_2004)
                            FPFormat.getInstance()
                                .AnsiIsoToStd(mMatData, bdat, FPFormat.ANSI_378_2004)
                            score = FPMatch.getInstance().MatchTemplate(adat, bdat)
                        }
                        FPFormat.ISO_19794_2005 -> {
                            FPFormat.getInstance()
                                .AnsiIsoToStd(mRefData, adat, FPFormat.ISO_19794_2005)
                            FPFormat.getInstance()
                                .AnsiIsoToStd(mMatData, bdat, FPFormat.ISO_19794_2005)
                            score = FPMatch.getInstance().MatchTemplate(adat, bdat)
                        }
                    }
                    AddStatusList(getString(android.R.string.txt_matchscore) + score.toString())
                }
            } else {
                AddStatusList(getString(android.R.string.txt_enrolok))
                when (mFPFormat) {
                    FPFormat.STD_TEMPLATE -> {
                        System.arraycopy(model, 0, mRefData, 0, model.size)
                        mRefSize = model.size
                    }
                    FPFormat.ANSI_378_2004 -> {
                        //mRefString=FPFormat.getInstance().To_Ansi378_2004_Base64(model);
                        //AddStatusList(mRefString);
                        FPFormat.getInstance()
                            .StdToAnsiIso(model, mRefData, FPFormat.ANSI_378_2004)
                        mRefSize = mRefData.size
                    }
                    FPFormat.ISO_19794_2005 -> {
                        //mRefString=FPFormat.getInstance().To_Iso19794_2005_Base64(model);
                        //AddStatusList(mRefString);
                        if (FPFormat.getInstance().StdToAnsiIso(model,
                                    mRefData,
                                    FPFormat.ISO_19794_2005)) AddStatusList("ISO_19794_2005")
                        mRefSize = mRefData.size
                    }
                }
            } */
            }

            override fun onUpTemplateFail() {
//            if (worktype == 1) {
//                AddStatusList(getString(android.R.string.txt_capturefail))
//            } else {
//                AddStatusList(getString(android.R.string.txt_enrolfail))
//            }
                Log.d("TAG", "onUpTemplateFail: ")
            }
        })
        val onEnrolTemplateListener = asyncBluetoothReader!!.setOnEnrolTemplateListener(object :
            AsyncBluetoothReader.OnEnrolTemplateListener {
            override fun onEnrolTemplateSuccess(model: ByteArray) {
//            System.arraycopy(model, 0, mRefData, 0, model.size)
//            mRefSize = model.size
//            AddStatusList(getString(android.R.string.txt_enrolok))
                Log.d("TAG", "onEnrolTemplateSuccess: ${model.toString()}")
            }

            override fun onEnrolTemplateFail() {
                Log.d("TAG", "onEnrolTemplateFail:")
//            AddStatusList(getString(android.R.string.txt_enrolfail))
            }
        })
        val onCaptureTemplateListener = asyncBluetoothReader!!.setOnCaptureTemplateListener(object :
            AsyncBluetoothReader.OnCaptureTemplateListener {
            override fun onCaptureTemplateSuccess(model: ByteArray) {
//            System.arraycopy(model, 0, mMatData, 0, model.size)
//            mMatSize = model.size
//            AddStatusList(getString(android.R.string.txt_captureok))
//            if (mRefSize > 0) {
//                val score =
//                    asyncBluetoothReader!!.bluetoothReader.MatchTemplate(mRefData, mMatData)
//                AddStatusList(getString(android.R.string.txt_matchscore) + score.toString())
//            }
            }

            override fun onCaptureTemplateFail() {
//            AddStatusList(getString(android.R.string.txt_capturefail))
            }
        })
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { isGranted ->
            if (isGranted.containsValue(false)) {
                initBluetooth()
            } else {
                initBluetooth()
            }
        }

    private val requestPermissionLauncher2 =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { isGranted ->
            if (isGranted.containsValue(false)) {
                initBluetooth()
            } else {
                initBluetooth()
            }
        }
}