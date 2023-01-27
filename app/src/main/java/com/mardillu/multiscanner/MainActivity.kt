package com.mardillu.multiscanner

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.mardillu.multiscanner.databinding.ActivityMainBinding
import com.mardillu.multiscanner.ui.camera.OpticalScanner
import com.mardillu.multiscanner.ui.fingerprint.FingerprintScanner
import com.mardillu.multiscanner.utils.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val featureBufferEnroll: ArrayList<ByteArray?> = arrayListOf(ByteArray(1), ByteArray(1))
    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ActivityMainBinding.inflate(layoutInflater)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.apply {
            enrolFinger.setOnClickListener {
                val intent = Intent(this@MainActivity, FingerprintScanner::class.java)
                intent.putExtra(EXTRA_SCAN_TYPE, SCAN_TYPE_FINGERPRINT_ENROL)
                enrol.launch(intent)
            }

            verifyFinger.setOnClickListener {
                val intent = Intent(this@MainActivity, FingerprintScanner::class.java)
                intent.putExtra(EXTRA_SCAN_TYPE, SCAN_TYPE_FINGERPRINT_MATCH)
                intent.putExtra(EXTRA_RIGHT_THUMB_PROFILE, featureBufferEnroll[0])
                intent.putExtra(EXTRA_LEFT_THUMB_PROFILE, featureBufferEnroll[1])
                verify.launch(intent)
            }

            scanOcr.setOnClickListener {
                val intent = Intent(this@MainActivity, OpticalScanner::class.java)
                intent.putExtra(EXTRA_SCAN_TYPE, SCAN_TYPE_OCR)
                opticalScan.launch(intent)
            }

            barQrCode.setOnClickListener {
                val intent = Intent(this@MainActivity, OpticalScanner::class.java)
                intent.putExtra(EXTRA_SCAN_TYPE, SCAN_TYPE_BARCODE)
                opticalScan.launch(intent)
            }
        }
    }

    val enrol = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        when (result.resultCode) {
            RESULT_ENROLMENT_SUCCESSFUL -> {
                Toast.makeText(this@MainActivity, "Enrol success!", Toast.LENGTH_LONG).show()
                val data = result.data
                val rightProfile = data?.getByteArrayExtra(EXTRA_RIGHT_THUMB_PROFILE,)
                val leftProfile = data?.getByteArrayExtra(EXTRA_LEFT_THUMB_PROFILE,)

                featureBufferEnroll.clear()
                featureBufferEnroll.add(rightProfile)
                featureBufferEnroll.add(leftProfile)

                Log.d("TAG", "RIGHT: $rightProfile")
                Log.d("TAG", "LEFT: $leftProfile")
            }
            RESULT_ENROLMENT_FAILED -> {
                Toast.makeText(this@MainActivity, "Enrol failed!", Toast.LENGTH_LONG).show()
            }
            RESULT_CANCELED -> {
                Toast.makeText(this@MainActivity, "Enrol canceled!", Toast.LENGTH_LONG).show()
            }
        }
    }

    val verify = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        when (result.resultCode) {
            RESULT_MATCH_FOUND -> {
                Toast.makeText(this@MainActivity, "Verify success!", Toast.LENGTH_LONG).show()
            }
            RESULT_MATCH_FAILED -> {
                Toast.makeText(this@MainActivity, "Verify failed!", Toast.LENGTH_LONG).show()
            }
            RESULT_CANCELED -> {
                Toast.makeText(this@MainActivity, "Verify canceled!", Toast.LENGTH_LONG).show()
            }
        }
    }

    val opticalScan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        when (result.resultCode) {
            RESULT_SCAN_SUCCESS -> {
                val data = result.data
                val text = data?.getStringExtra(EXTRA_OCR_SCAN_RESULT)
                Toast.makeText(this@MainActivity, "Scan success!: $text", Toast.LENGTH_LONG).show()
            }
            RESULT_SCAN_FAILED -> {
                Toast.makeText(this@MainActivity, "Scan failed!", Toast.LENGTH_LONG).show()
            }
            RESULT_CANCELED -> {
                Toast.makeText(this@MainActivity, "Scan canceled!", Toast.LENGTH_LONG).show()
            }
        }
    }
}