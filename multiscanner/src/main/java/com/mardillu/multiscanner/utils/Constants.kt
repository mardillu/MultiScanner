package com.mardillu.multiscanner.utils

/**
 * Created on 20/12/2022 at 9:58 PM
 * @author mardillu
 */

const val SCAN_TYPE_FINGERPRINT_ENROL = 1
const val SCAN_TYPE_FINGERPRINT_MATCH = 2
const val SCAN_TYPE_BARCODE = 3
const val SCAN_TYPE_QR_CODE = 4
const val SCAN_TYPE_OCR = 5

val SCAN_TYPES = listOf(1,2,3,4,5)

//results status
const val RESULT_ENROLMENT_SUCCESSFUL = 1
const val RESULT_ENROLMENT_FAILED = 2
const val RESULT_MATCH_FOUND = 3
const val RESULT_MATCH_FAILED = 4
const val RESULT_SCAN_FAILED = 7
const val RESULT_SCAN_SUCCESS = 6

//EXTRAS
const val EXTRA_RIGHT_THUMB_PROFILE = "EXTRA_RIGHT_THUMB_PROFILE"
const val EXTRA_LEFT_THUMB_PROFILE = "EXTRA_LEFT_THUMB_PROFILE"
const val EXTRA_SCAN_TYPE = "EXTRA_SCAN_TYPE"
const val EXTRA_OCR_SCAN_RESULT = "EXTRA_OCR_SCAN_RESULT"
const val EXTRA_OCR_IMAGE_NAME = "EXTRA_OCR_IMAGE_NAME"
const val EXTRA_OCR_IMAGE_LOCATION = "EXTRA_OCR_IMAGE_LOCATION"
const val EXTRA_FINGERPRINT_SOURCE = "EXTRA_FINGERPRINT_SOURCE"
const val EXTRA_FARMERS_FINGERPRINT_PROFILES = "EXTRA_FARMERS_FINGERPRINT_PROFILES"
const val EXTRA_SCANNER_RESULT = "EXTRA_SCANNER_RESULT"

// Message types sent from the BluetoothChatService Handler
const val MESSAGE_STATE_CHANGE = 1
const val MESSAGE_READ = 2
const val MESSAGE_WRITE = 3
const val MESSAGE_DEVICE_NAME = 4
const val MESSAGE_TOAST = 5

// Key names received from the BluetoothChatService Handler
const val DEVICE_NAME = "device_name"
const val TOAST = "toast"

//Scanner sources
const val SOURCE_EXTERNAL_BT_READER = 1
const val SOURCE_INBUILT_READER = 2
const val NUM_FINGERS_TO_SCAN = 1 // = 2 (0, 1)

//prefs
const val PREF_PREV_BLUETOOTH_DEVICE_ADDRESS = "PREF_PREV_BLUETOOTH_DEVICE_ADDRESS"
const val PREF_SHOW_GUIDE_DIALOG = "PREF_SHOW_GUIDE_DIALOG"

const val REQUEST_ENABLE_BT = 2