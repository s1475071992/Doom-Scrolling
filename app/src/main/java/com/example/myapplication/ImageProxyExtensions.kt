package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun ImageProxy.toBitmap(): Bitmap {
    if (format != ImageFormat.YUV_420_888) {
        throw IllegalStateException("Unsupported ImageProxy format: $format")
    }

    val nv21 = yuv420888ToNv21(this)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)

    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
    val jpegBytes = out.toByteArray()
    var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        ?: throw IllegalStateException("Failed to decode bitmap")

    val rotationDegrees = imageInfo.rotationDegrees
    if (rotationDegrees != 0) {
        val m = Matrix()
        m.postRotate(rotationDegrees.toFloat())
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    return bitmap
}

private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val width = image.width
    val height = image.height

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    yBuffer.rewind()
    uBuffer.rewind()
    vBuffer.rewind()

    val nv21 = ByteArray(width * height * 3 / 2)

    // Copy Y plane row by row to handle rowStride
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    var outIndex = 0
    for (row in 0 until height) {
        val yRowStart = row * yRowStride
        for (col in 0 until width) {
            nv21[outIndex++] = yBuffer.get(yRowStart + col * yPixelStride)
        }
    }

    // Interleave VU for NV21
    val uvHeight = height / 2
    val uvWidth = width / 2
    val uRowStride = uPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vRowStride = vPlane.rowStride
    val vPixelStride = vPlane.pixelStride

    for (row in 0 until uvHeight) {
        val uRowStart = row * uRowStride
        val vRowStart = row * vRowStride
        for (col in 0 until uvWidth) {
            val uIndex = uRowStart + col * uPixelStride
            val vIndex = vRowStart + col * vPixelStride
            nv21[outIndex++] = vBuffer.get(vIndex)
            nv21[outIndex++] = uBuffer.get(uIndex)
        }
    }

    return nv21
}


/** Fast CameraX packed RGBA input, no JPEG conversion. */
fun ImageProxy.toOptimizedBitmap(): Bitmap {
    if (format != android.graphics.PixelFormat.RGBA_8888) return toBitmap()
    val plane = planes[0]
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val buffer = plane.buffer.duplicate()
    buffer.rewind()
    val length = width * height * 4
    if (plane.pixelStride == 4 && plane.rowStride == width * 4) {
        // CameraX RGBA plane can be copied directly into ARGB_8888's native byte layout.
        bitmap.copyPixelsFromBuffer(buffer)
    } else {
        // Rare camera row padding: copy each scanline in native RGBA order.
        val compact = java.nio.ByteBuffer.allocateDirect(length)
        val bytes = ByteArray(width * 4)
        for (row in 0 until height) {
            buffer.position(row * plane.rowStride)
            buffer.get(bytes)
            compact.put(bytes)
        }
        compact.flip()
        bitmap.copyPixelsFromBuffer(compact)
    }
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return bitmap
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

/**
 * Per-analyzer YUV420 -> ARGB converter. CameraX stays in native YUV so frames
 * discarded by inference cadence avoid RGBA conversion entirely.
 *
 * Conversion occurs only on the single camera analysis executor. The output
 * Bitmap and pixel array are reused while the MediaPipe async result is pending:
 * HandDetectionAnalyzer's busy gate prevents overwriting the Bitmap until the
 * previous MPImage has been closed.
 *
 * Handles plane row/pixel strides, rotation and chroma subsampling directly;
 * unlike the legacy toBitmap() helper, this path does NOT encode/decode JPEG.
 */
class ReusableFrameConverter : AutoCloseable {
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null

    fun convert(image: ImageProxy): Bitmap {
        val rotation = image.imageInfo.rotationDegrees
        require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
            "Unsupported camera rotation: $rotation"
        }
        val width = image.width
        val height = image.height
        val outWidth = if (rotation == 90 || rotation == 270) height else width
        val outHeight = if (rotation == 90 || rotation == 270) width else height
        if (pixels.size != outWidth * outHeight) pixels = IntArray(outWidth * outHeight)
        var target = bitmap
        if (target == null || target.isRecycled ||
            target.width != outWidth || target.height != outHeight) {
            target?.recycle()
            target = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            bitmap = target
        }
        when (image.format) {
            ImageFormat.YUV_420_888 -> fillYuvPixels(image, rotation)
            android.graphics.PixelFormat.RGBA_8888 -> fillRgbaPixels(image, rotation)
            else -> error("Unsupported camera format: ${image.format}")
        }
        target.setPixels(pixels, 0, outWidth, 0, 0, outWidth, outHeight)
        return target
    }

    private fun fillYuvPixels(image: ImageProxy, rotation: Int) {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer.duplicate()
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()

        for (y in 0 until height) {
            val yOffset = y * yPlane.rowStride
            val uvOffsetU = (y shr 1) * uPlane.rowStride
            val uvOffsetV = (y shr 1) * vPlane.rowStride
            for (x in 0 until width) {
                val lum = yBuf.get(yOffset + x * yPlane.pixelStride).toInt() and 255
                val uvX = x shr 1
                val u = (uBuf.get(uvOffsetU + uvX * uPlane.pixelStride).toInt() and 255) - 128
                val v = (vBuf.get(uvOffsetV + uvX * vPlane.pixelStride).toInt() and 255) - 128
                val yy = (lum - 16).coerceAtLeast(0)
                val r = ((298 * yy + 409 * v + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * yy - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * yy + 516 * u + 128) shr 8).coerceIn(0, 255)
                val pos = when (rotation) {
                    90 -> x * height + (height - 1 - y)
                    180 -> (height - 1 - y) * width + (width - 1 - x)
                    270 -> (width - 1 - x) * height + y
                    else -> y * width + x
                }
                pixels[pos] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun fillRgbaPixels(image: ImageProxy, rotation: Int) {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val src = plane.buffer.duplicate()
        for (y in 0 until height) for (x in 0 until width) {
            val offset = y * plane.rowStride + x * plane.pixelStride
            val r = src.get(offset).toInt() and 255
            val g = src.get(offset + 1).toInt() and 255
            val b = src.get(offset + 2).toInt() and 255
            val pos = when (rotation) {
                90 -> x * height + (height - 1 - y)
                180 -> (height - 1 - y) * width + (width - 1 - x)
                270 -> (width - 1 - x) * height + y
                else -> y * width + x
            }
            pixels[pos] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    override fun close() {
        bitmap?.recycle()
        bitmap = null
        pixels = IntArray(0)
    }
}
