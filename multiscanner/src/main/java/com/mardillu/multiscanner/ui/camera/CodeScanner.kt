package com.mardillu.multiscanner.ui.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Created on 22/11/2022 at 14:56.
 * @author Ezekiel Sebastine.
 */

class Scanner {

    class BarcodeScanner(listener: BarcodeListener? = null) : ImageAnalysis.Analyzer {
        private val listeners = ArrayList<BarcodeListener>().apply { listener?.let { add(it) } }

        private val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()

        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image =
                    InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                val scanner = BarcodeScanning.getClient(options)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        barcodes.forEach { barcode ->
                            listeners.forEach { it(barcode.displayValue) }
                        }
                        imageProxy.close()
                    }
                    .addOnFailureListener {
                        imageProxy.close()
                    }
            }
        }
    }

    class OCRScanner (listener: BarcodeListener? = null): ImageAnalysis.Analyzer {
        private val listeners = ArrayList<BarcodeListener>().apply { listener?.let { add(it) } }

        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image =
                    InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                //process image
                //then close proxy imageProxy.close()
            }
        }
    }
}