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

fun String.toCustomByteArray(): ByteArray {
    if (this.isEmpty()) return ByteArray(0)
    val byteArray = this.replace("[","").replace("]","").split(",")
    val bytes = ByteArray(byteArray.size)
    byteArray.forEachIndexed { i, it, ->
        if (it.isByte()) {
            bytes[i] = it.trim().toByte()
        }
    }
    return bytes
}

fun ByteArray.toCustomArrayString(): String {
    val builder = StringBuilder()
    builder.append("[")
    this.forEachIndexed { i, it ->
        builder.append("$it")
        if (i+1 < this.size){
            builder.append(",")
        }
    }
    builder.append("]")
    return builder.toString()
}

fun String.isByte(): Boolean{
    return try {
        this.trim().toByte()
        true
    } catch (e: Exception){
        false
    }
}