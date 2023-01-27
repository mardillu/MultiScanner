package com.mardillu.multiscanner.ui.camera

import android.graphics.ImageFormat.YUV_420_888
import android.media.Image
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.text.isDigitsOnly
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.common.InputImage.IMAGE_FORMAT_NV21
import com.google.mlkit.vision.text.Text.TextBlock
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.otaliastudios.cameraview.frame.Frame
import com.otaliastudios.cameraview.frame.FrameProcessor

/**
 * Created on 22/11/2022 at 14:56.
 * @author Ezekiel Sebastine.
 */

typealias BarcodeListener = (barcode: String?) -> Unit
typealias OCRListener = (quantity: String?) -> Unit

class CameraFrameProcessors {

    class BarcodeProcessor(listener: BarcodeListener? = null) : FrameProcessor {
        private val listeners = ArrayList<BarcodeListener>().apply { listener?.let { add(it) } }

        private val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()

        @WorkerThread
        override fun process(frame: Frame) {

            val image = if (frame.dataClass === Image::class.java)
                InputImage.fromMediaImage(frame.getData(), frame.rotationToUser)
            else
                InputImage.fromByteArray(frame.getData(),
                        frame.size.width,
                        frame.size.height,
                        frame.rotationToUser,
                        IMAGE_FORMAT_NV21)

            val scanner = BarcodeScanning.getClient(options)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    barcodes.forEach { barcode ->
                        listeners.forEach { it(barcode.displayValue) }
                    }
                    //imageProxy.close()
                }
                .addOnFailureListener {
                    //imageProxy.close()
                }
        }
    }

    class OCRProcessor(listener: OCRListener? = null) : FrameProcessor {
        private val listeners = ArrayList<OCRListener>().apply { listener?.let { add(it) } }
        private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        @WorkerThread
        override fun process(frame: Frame) {
            Log.d("TAG", "analyze BEFORE: ${frame.size.height}")
            val image = if (frame.dataClass === Image::class.java)
                InputImage.fromMediaImage(frame.getData(), frame.rotationToUser)
            else
                InputImage.fromByteArray(frame.getData(),
                        frame.size.width,
                        frame.size.height,
                        frame.rotationToUser,
                        YUV_420_888)

            val res = Tasks.await(recognizer.process(image)
                .addOnSuccessListener {
                    val quantity = getQuantityFromDetectedText(it.textBlocks)
                    listeners.forEach { it(quantity) }
                }
                .addOnFailureListener {
                    listeners.forEach { it(null) }
                }.addOnCompleteListener {

                })
        }

        private fun getQuantityFromDetectedText(detectedText: List<TextBlock>): String? {
            for (block in detectedText) {
                for (line in block.lines) {
                    return if(line.text.isDigitsOnly() || line.text.endsWith("kg").and(line.text.first().isDigit())){
                        line.text
                    } else null
                }
            }
            return null
        }
    }
}