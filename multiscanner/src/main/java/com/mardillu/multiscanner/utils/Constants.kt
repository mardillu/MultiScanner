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

//verification results status
const val RESULT_ENROLMENT_SUCCESSFUL = 1
const val RESULT_ENROLMENT_FAILED = 2
const val RESULT_MATCH_FOUND = 3
const val RESULT_MATCH_FAILED = 4
const val RESULT_SCAN_CANCELED = 5

//EXTRAS
const val EXTRA_RIGHT_THUMB_PROFILE = "EXTRA_RIGHT_THUMB_PROFILE"
const val EXTRA_LEFT_THUMB_PROFILE = "EXTRA_LEFT_THUMB_PROFILE"
const val EXTRA_SCAN_TYPE = "EXTRA_SCAN_TYPE"