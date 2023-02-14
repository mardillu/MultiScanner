package com.mardillu.multiscanner.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat.YUV_420_888
import android.graphics.Matrix
import android.media.Image
import android.os.Build
import android.renderscript.*
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
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer


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

    class OCRProcessor(context: Context, listener: OCRListener? = null) : FrameProcessor {
        private val c = context
        private val listeners = ArrayList<OCRListener>().apply { listener?.let { add(it) } }
        private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        @WorkerThread
        override fun process(frame: Frame) {
            Log.d("TAG", "analyze BEFORE: ${frame.size.height}")
            val image = if (frame.dataClass === Image::class.java) {
               //InputImage.fromMediaImage(frame.getData(), frame.rotationToUser)
                InputImage.fromBitmap(erodeImage(yuv420ToBitmap((frame.getData() as Image), c)), 0)
            } else {
                InputImage.fromByteArray(frame.getData(),
                        frame.size.width,
                        frame.size.height,
                        frame.rotationToUser,
                        YUV_420_888)
            }

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

        public fun analyse() {

        }

        private fun createInvertedBlackAndWhite(src: Bitmap): Bitmap {
            val width = src.width
            val height = src.height
            // create output bitmap
            val bmOut = Bitmap.createBitmap(width, height, src.config)
            // color information
            var A: Int
            var R: Int
            var G: Int
            var B: Int
            var pixel: Int

            // scan through all pixels
            for (x in 0 until width) {
                for (y in 0 until height) {
                    // get pixel color
                    pixel = src.getPixel(x, y)
                    A = Color.alpha(pixel)
                    R = Color.red(pixel)
                    G = Color.green(pixel)
                    B = Color.blue(pixel)
                    var gray = (0.2989 * R + 0.5870 * G + 0.1140 * B).toInt()

                    // use 128 as threshold, above -> white, below -> black
                    gray = if (gray > 128) 0 else 255
                    // set new pixel color to output bitmap
                    bmOut.setPixel(x, y, Color.argb(A, gray, gray, gray))
                }
            }
            return bmOut
        }

        private fun yuv420ToBitmap(image: Image, context: Context): Bitmap {
            val rs = RenderScript.create(context)
            val script = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

            // Refer the logic in a section below on how to convert a YUV_420_888 image
            // to single channel flat 1D array. For sake of this example I'll abstract it
            // as a method.
            val yuvByteArray: ByteArray = image2byteArray(image)
            val yuvType: Type.Builder = Type.Builder(rs, Element.U8(rs)).setX(yuvByteArray.size)
            val `in` = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT)
            val rgbaType: Type.Builder = Type.Builder(rs, Element.RGBA_8888(rs))
                .setX(image.width)
                .setY(image.height)
            val out = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT)

            // The allocations above "should" be cached if you are going to perform
            // repeated conversion of YUV_420_888 to Bitmap.
            `in`.copyFrom(yuvByteArray)
            script.setInput(`in`)
            script.forEach(out)
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            out.copyTo(bitmap)
            return bitmap
        }

        private fun image2byteArray(image: Image): ByteArray {
            require(image.format == YUV_420_888) { "Invalid image format" }
            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuffer: ByteBuffer = yPlane.buffer
            val uBuffer: ByteBuffer = uPlane.buffer
            val vBuffer: ByteBuffer = vPlane.buffer

            // Full size Y channel and quarter size U+V channels.
            val numPixels = (width * height * 1.5f).toInt()
            val nv21 = ByteArray(numPixels)
            var index = 0

            // Copy Y channel.
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            for (y in 0 until height) {
                for (x in 0 until width) {
                    nv21[index++] = yBuffer.get(y * yRowStride + x * yPixelStride)
                }
            }

            // Copy VU data; NV21 format is expected to have YYYYVU packaging.
            // The U/V planes are guaranteed to have the same row stride and pixel stride.
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride
            val uvWidth = width / 2
            val uvHeight = height / 2
            for (y in 0 until uvHeight) {
                for (x in 0 until uvWidth) {
                    val bufferIndex = y * uvRowStride + x * uvPixelStride
                    // V channel.
                    nv21[index++] = vBuffer.get(bufferIndex)
                    // U channel.
                    nv21[index++] = uBuffer.get(bufferIndex)
                }
            }
            return nv21
        }

        private fun cropImage(image: Bitmap): Bitmap {
            val y = 36
            val x = 60
            val matrix = Matrix()

            matrix.preRotate(90F)
            return Bitmap.createBitmap(image, x, y, 50, 100, matrix, true)
        }

        private fun erodeImage(img: Bitmap): Bitmap {
            // image to mat
            //144, 176
            val image = cropImage(img)

            val bnw = createInvertedBlackAndWhite(image)

            val mInput = Mat(bnw.height, bnw.width, CvType.CV_8UC3)

            Utils.bitmapToMat(bnw, mInput)

            val trim = if(Build.MODEL == "Q807") 3.0 else 7.0
           // image.close()
//            Imgproc.erode(mInput,
//                    mInput,
//                    Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(trim, trim)))

            Imgproc.dilate(mInput,
                    mInput,
                    Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(trim, trim)))

            val outBitmap = Bitmap.createBitmap(bnw.width, bnw.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mInput, outBitmap)

            return outBitmap
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