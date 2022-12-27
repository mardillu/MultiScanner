package com.mardillu.multiscanner.utils

import android.content.Context
import android.content.res.AssetManager

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.*


/**
 * Created on 26/12/2022 at 10:54 PM
 * @author mardillu
 */
class TesseractOCR {

    companion object {

        fun processImage(image: Bitmap?, context: Context, listener: OCREventsListener) {
            val language = "eng"
            val datapath = "${context.filesDir}/tesseract/"
            val mTess = TessBaseAPI()
            checkFile(File(datapath + "tessdata/"), datapath, context)
            if (!mTess.init(datapath, language)) {
                mTess.recycle()
                listener.onError(InitializationException())
                return
            }

            mTess.setImage(image)
            val text: String = mTess.utF8Text
            mTess.recycle()
            listener.onResult(text)
        }

        private fun checkFile(dir: File, datapath: String, c: Context) {
            if (!dir.exists() && dir.mkdirs()) {
                copyFiles(datapath, c)
            }
            if (dir.exists()) {
                val datafilepath = "$datapath/tessdata/eng.traineddata"
                val datafile = File(datafilepath)
                if (!datafile.exists()) {
                    copyFiles(datapath, c)
                }
            }
        }

        private fun copyFiles(datapath: String, c: Context) {
            try {
                val filepath = "$datapath/tessdata/eng.traineddata"
                val assetManager: AssetManager = c.getAssets()
                val instream: InputStream = assetManager.open("tessdata/eng.traineddata")
                val outstream: OutputStream = FileOutputStream(filepath)
                val buffer = ByteArray(1024)
                var read: Int
                while (instream.read(buffer).also { read = it } != -1) {
                    outstream.write(buffer, 0, read)
                }
                outstream.flush()
                outstream.close()
                instream.close()
                val file = File(filepath)
                if (!file.exists()) {
                    throw FileNotFoundException()
                }
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    interface OCREventsListener {
        fun onResult(result: String)
        fun onError(throwable: Throwable)
    }
}

private class InitializationException: Throwable(message = "Error initializing Tesseract (wrong data path or language)")