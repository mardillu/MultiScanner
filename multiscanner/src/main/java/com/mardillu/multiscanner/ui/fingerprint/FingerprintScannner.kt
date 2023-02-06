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
import com.mardillu.multiscanner.R
import com.mardillu.multiscanner.databinding.ActivityFingerScannerBinding
import com.mardillu.multiscanner.databinding.DialogDeviceListBinding
import com.mardillu.multiscanner.databinding.ItemBluetoothDeviceBinding
import com.mardillu.multiscanner.ui.fingerprint.bluetooth.BluetoothReaderService
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


class FingerprintScanner : AppCompatActivity() {
    private lateinit var binding: ActivityFingerScannerBinding
    private lateinit var bluetoothBinding: DialogDeviceListBinding
    private lateinit var builder: Dialog
    private val TIME_OUT = 300000L //5 mins
    private var executor = Executors.newSingleThreadExecutor()
    private val featureBufferEnroll: ArrayList<ByteArray?> = arrayListOf(ByteArray(1), ByteArray(1))
    private var featureBufferMatch: ByteArray? = ByteArray(1)
    private var allFarmersFingerProfiles: ArrayList<ByteArray> = ArrayList()
    private lateinit var mxFingerAlg: MxISOFingerAlg
    private lateinit var mxMscBigFingerApi: MxMscBigFingerApi
    private var mBtAdapter: BluetoothAdapter? = null
    private var scannerSource = SOURCE_INBUILT_READER
    private var asyncBluetoothReader: AsyncBluetoothReader? = AsyncBluetoothReader()
    var pairedDevices: ArrayList<BluetoothDevice>? = null
    private var scanType = SCAN_TYPE_FINGERPRINT_ENROL
    private var fingerIndex = 0
    var lfdEnabled = false

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
        ) == PackageManager.PERMISSION_GRANTED) {
            // You can use the API that requires the permission.
        } else {
            val PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
            ) else
                arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            val requestPermissionLauncher =
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { isGranted ->
                    if (isGranted.containsValue(false)) {
                        initBluetooth()
                    } else {
                        initBluetooth()
                    }
                }
            requestPermissionLauncher.launch(
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
        featureBufferEnroll[0] = null
        featureBufferEnroll[1] = null
        executor.shutdown()
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
        featureBufferEnroll[0] = null
        featureBufferEnroll[1] = null
        disableActionButton()
        showPromptRightThumb()
        updateProgress(0.0)
        enrolFinger(0)
    }

    private fun enrolFinger(index: Int) {
//        if(builder.isShowing){
//            builder.dismiss()
//        }
        if (executor.isShutdown){
            return
        }
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
                    showErrorToast(
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
                        showErrorToast(
                            "LFD Error. Try again",
                        )
                        enrolFinger(index)
                        return@execute
                    }
                    /*result 1 mean real finger*/if (lfdDetectResult[0] == 0) {
                        showErrorToast(
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
                    showErrorToast(
                        "Quality too low. Scan again",
                    )
                    enrolFinger(index)
                    return@execute
                } else {

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
                        "Enrollment failed. Try again",
                    )
                    enrolFinger(index)
                    return@execute
                } else {
                    if (!isFingerPrintUniqueLegacy(profile, allFarmersFingerProfiles)) {
                        showErrorToast(
                                "Looks like this finger has been scanned already. Please scan a different finger",
                        )
                        enrolFinger(index)
                        return@execute
                    }
                    featureBufferEnroll[index] = profile
                    if (index == 0) {
                        showSuccessToast(
                            "Right thumb captured successfully",
                        )
                        allFarmersFingerProfiles.add(profile)
                        enrolFinger(1)
                    } else {
                        showSuccessToast(
                            "Enrollment complete",
                        )
                        enableActionButton()
                    }
                }
            } finally {
                //dismissbinding.progressBarDialog()
            }
        }
    }

    private fun enrolBTFinger() {
        latestBTImage = null
        latestBTProfile = null
        Timer("SettingUp", false).schedule(1000) {
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
        if (qualityScore < 60) {
            showErrorToast(
                    "Quality too low. Scan again",
            )
            enrolBTFinger()
            return
        } else {
            val isUnique = isFingerPrintUnique(latestBTProfile, allFarmersFingerProfiles)

            if (!isUnique) {
                showErrorToast(
                        "Looks like this finger has been scanned already. Please scan a different finger",
                )
                enrolBTFinger()
                return
            }

            featureBufferEnroll[fingerIndex] = latestBTProfile
            if (fingerIndex >= NUM_FINGERS_TO_SCAN){
                showSuccessToast(
                        "Enrollment complete",
                )
                enableActionButton()
            } else {
                showSuccessToast(
                        "Right thumb captured successfully",
                )

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
        if (qualityScore < 60) {
            showErrorToast(
                    "Quality too low. Scan again",
            )
            enrolBTFinger()
            return
        } else {
            val isUnique = isFingerPrintUnique(latestBTProfile, allFarmersFingerProfiles)

            if (!isUnique) {
                showSuccessToast(
                        "Fingerprint matched found",
                )
                enableActionButton()
            } else {
                showErrorToast(
                        "Match failed. Try again",
                )
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
                        showErrorToast(
                            "LFD Error, try again",
                        )
                        matchFinger(true)
                        return@execute
                    }
                    /*result 1 mean real finger*/if (lfdDetectResult[0] == 0) {
                        showErrorToast(
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
                    showErrorToast(
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
                    showErrorToast(
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
                    showSuccessToast(
                        "Fingerprint matched found",
                    )
                    enableActionButton()
                } else {
                    showErrorToast(
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

    private fun isFingerPrintUnique(compare: ByteArray?, with: ArrayList<ByteArray>): Boolean {
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

            if (score >= 60 || scoreAlt >= 60){
                return false
            }
        }

        return true
    }

    private fun isFingerPrintUniqueLegacy(compare: ByteArray?, with: ArrayList<ByteArray>): Boolean {
        if (compare == null){
            return true
        }

        val mRefData = ByteArray(512)
        FPFormat.getInstance().StdToAnsiIso(compare, mRefData, FPFormat.ANSI_378_2004)
        for (profile in with){
            val mMatData = ByteArray(512)
            FPFormat.getInstance().StdToAnsiIso(profile, mMatData, FPFormat.ANSI_378_2004)
            val match =
                mxFingerAlg.match(mRefData, mMatData, 3)
            val matchAlt =
                mxFingerAlg.match(compare, profile, 3)
            if (match == 0 || matchAlt == 0){
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

            if (perc >= 60.0) {
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
            binding.promptSubBody.text = "Press the centre of your RIGHT thumb on the sensor, then lift it off when the progress turns green"
            binding.secondaryPromptHead.text = "Scan RIGHT thumb..."
        }
    }

    private fun showPromptLeftThumb(){
        runOnUiThread {
            binding.promptBody.text = "Scan farmer thumb to enrol fingerprint"
            binding.promptHead.text = "Scan LEFT thumb"
            binding.promptSubBody.text = "Press the centre of your LEFT thumb on the sensor, then lift it off when the progress turns green"
            binding.secondaryPromptHead.text = "Scan LEFT thumb..."
        }
    }

    private fun showPromptNeutralFinger(){
        runOnUiThread {
            binding.promptBody.text = "Scan farmer thumb to verify farmer"
            binding.promptHead.text = "Scan farmer thumb"
            binding.promptSubBody.text = "Press the centre of your thumb on the sensor, then lift it off when the progress turns green"
            binding.secondaryPromptHead.text = "Scan farmer thumb"
        }
    }

    private fun enableActionButton(){
        runOnUiThread {
            if (scanType == SCAN_TYPE_FINGERPRINT_MATCH) {
                binding.completeAction.text = "Complete Verification"
            } else {
                binding.completeAction.text = "Complete Enrolment"
            }

            binding.completeAction.isEnabled = true
        }
    }

    private fun disableActionButton(){
        runOnUiThread {
            if (scanType == SCAN_TYPE_FINGERPRINT_MATCH) {
                binding.completeAction.text = "Complete Verification"
            } else {
                binding.completeAction.text = "Complete Enrolment"
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
            Toast.makeText(this@FingerprintScanner, "No Bluetooth devices found", Toast.LENGTH_LONG).show()
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
        bluetoothBinding.notifications.text = "Scanning for new devices..."
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
                bluetoothBinding.notifications.text = "Select Bluetooth device"
                showBluetoothListDialog()
            }
        }
    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        asyncBluetoothReader!!.start()
    }

    // The Handler that gets information back from the BluetoothChatService
    @SuppressLint("HandlerLeak")
    private val mHandler: Handler = object : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_STATE_CHANGE -> when (msg.arg1) {
                    BluetoothReaderService.STATE_CONNECTED -> {
                        Log.d("TAG", "handleMessage: MESSAGE_STATE_CHANGE STATE_CONNECTED")
                        //enrolFinger(0)

                    }
                    BluetoothReaderService.STATE_CONNECTING -> {
                        Log.d("TAG", "handleMessage: MESSAGE_STATE_CHANGE STATE_CONNECTING")
                    }
                    BluetoothReaderService.STATE_LISTEN, BluetoothReaderService.STATE_NONE -> {
                        Log.d("TAG", "handleMessage: MESSAGE_STATE_CHANGE STATE_LISTEN STATE_NONE")
                    }
                }
                MESSAGE_WRITE -> {
                    Log.d("TAG", "handleMessage: MESSAGE_WRITE")
                }
                MESSAGE_READ -> {
                    Log.d("TAG", "handleMessage: MESSAGE_READ")
                }
                MESSAGE_DEVICE_NAME -> {
                    Log.d("TAG", "handleMessage: MESSAGE_DEVICE_NAME")
                }
                MESSAGE_TOAST -> {
                    Log.d("TAG", "handleMessage: MESSAGE_TOAST")
                }
            }
        }
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
                                "Connecting to device",
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
                        "Connected to: $devicename",
                )
            }

            override fun onBluetoothStateLost(arg: Int) {
                when (arg) {
                    BluetoothReader.MSG_UNABLE -> {
                        Log.d("TAG", "onBluetoothStateLost: BluetoothReader.MSG_UNABLE")
                        showErrorToast(
                                "Unable to connect to bluetooth device",
                        )
                    }
                    BluetoothReader.MSG_LOST -> {
                        Log.d("TAG", "onBluetoothStateLost: BluetoothReader.MSG_LOST")
                        showErrorToast(
                                "Bluetooth device disconnected",
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
}