package com.mardillu.multiscanner.ui.fingerprint

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.*
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

class FingerprintScanner : AppCompatActivity() {
    private lateinit var binding: ActivityFingerScannerBinding
    private lateinit var bluetoothBinding: DialogDeviceListBinding
    private lateinit var builder: Dialog
    private val TIME_OUT = 300000L //5 mins
    private val executor = Executors.newSingleThreadExecutor()
    private val featureBufferEnroll: ArrayList<ByteArray?> = arrayListOf(ByteArray(1), ByteArray(1))
    private var featureBufferMatch: ByteArray? = ByteArray(1)
    private lateinit var mxFingerAlg: MxISOFingerAlg
    private lateinit var mxMscBigFingerApi: MxMscBigFingerApi
    private var mBtAdapter: BluetoothAdapter? = null
    private var mChatService: BluetoothReaderService? = null

    var pairedDevices: MutableSet<BluetoothDevice>? = null

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
                //initBluetooth()
            }
            SCAN_TYPE_FINGERPRINT_MATCH -> {
                val rightProfile = intent.getByteArrayExtra(EXTRA_RIGHT_THUMB_PROFILE)
                val leftProfile = intent.getByteArrayExtra(EXTRA_LEFT_THUMB_PROFILE)

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

    private fun initBluetooth(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when {
                ContextCompat.checkSelfPermission(
                        this@FingerprintScanner,
                        Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // You can use the API that requires the permission.
                }
                else -> {
                    val requestPermissionLauncher =
                        registerForActivityResult(ActivityResultContracts.RequestPermission()
                        ) { isGranted: Boolean ->
                            if (isGranted) {
                                initBluetooth()
                            } else {
                                initBluetooth()
                            }
                        }
                    requestPermissionLauncher.launch(
                            Manifest.permission.BLUETOOTH_CONNECT
                    )
                    return
                }
            }
        }
        bluetoothBinding = DialogDeviceListBinding.inflate(layoutInflater)
        builder = Dialog(this@FingerprintScanner, R.style.AlertDialogTheme)
        Objects.requireNonNull(builder.window)?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        builder.setContentView(bluetoothBinding.root)
        builder.setCancelable(false)

        // Get the local Bluetooth adapter
        mBtAdapter = BluetoothAdapter.getDefaultAdapter()
        // Get a set of currently paired devices
        pairedDevices = mBtAdapter!!.bondedDevices

        // Register for broadcasts when a device is discovered
        var filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        this.registerReceiver(mReceiver, filter)

        // Register for broadcasts when discovery has finished
        filter = IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        this.registerReceiver(mReceiver, filter)

        showBluetoothListDialog()
        doDiscovery()
    }

    private fun enrolFinger(index: Int) {
//        if(builder.isShowing){
//            builder.dismiss()
//        }
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
                featureBufferEnroll[index] =
                    mxFingerAlg.extractFeature(
                        image.data,
                        image.width,
                        image.height
                    )
                if (featureBufferEnroll[index] == null) {
                    showErrorToast(
                        "Enrollment failed. Try again",
                    )
                    enrolFinger(index)
                    return@execute
                } else {
                    if (index == 0) {
                        showSuccessToast(
                            "Right thumb captured successfully",
                        )
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

    private fun showBluetoothListDialog(){
        // If there are paired devices, add each one to the ArrayAdapter
        //pairedDevices = getRelevantDevices(pairedDevices!!)
        if (pairedDevices!!.isNotEmpty()) {
            bluetoothBinding.layoutDevicesList.removeAllViews()
            builder.show()
            bluetoothBinding.apply {
                //bluetoothBinding.progressBar.show()
                //get bluetooth devices

                pairedDevices!!.forEach { bt ->
                    val itemBinding = ItemBluetoothDeviceBinding.inflate(layoutInflater)
                    itemBinding.root.text = bt.name
                    bluetoothBinding.layoutDevicesList.addView(itemBinding.root)

                    itemBinding.root.setOnClickListener {
                        connectDevice(bt.address)
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
    private fun getRelevantDevices(pairedDvs: MutableSet<BluetoothDevice>): MutableSet<BluetoothDevice>? {
        val devices: MutableSet<BluetoothDevice> = HashSet()
        for (dev in pairedDvs) {
            val nm = dev.name.lowercase()
            if (nm.startsWith("SHBT")) {
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
        if (mChatService == null) setupChat()
        mChatService!!.connect(device)
    }

    private fun setupChat(){
        mChatService = BluetoothReaderService(this, mHandler)
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterReceiver(mReceiver)
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

    // The Handler that gets information back from the BluetoothChatService
    @SuppressLint("HandlerLeak")
    private val mHandler: Handler = object : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_STATE_CHANGE -> when (msg.arg1) {
                    BluetoothReaderService.STATE_CONNECTED -> {
                        Log.d("TAG", "handleMessage: MESSAGE_STATE_CHANGE STATE_CONNECTED")
                        enrolFinger(0)
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
}