package com.mardillu.multiscanner.utils

import android.content.Context
import android.os.Environment
import android.view.View
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Created on 26/01/2023 at 22:58.
 * @author Ezekiel Sebastine.
 */

fun View.show(){
    this.visibility = View.VISIBLE
}

fun View.hide(){
    this.visibility = View.GONE
}


@Throws(IOException::class)
fun Context.createImageFile(c: Context, name: String? = ""): File {
    val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    return File(getExternalFilesDir(null), "${name}_${timeStamp}.jpg")
}