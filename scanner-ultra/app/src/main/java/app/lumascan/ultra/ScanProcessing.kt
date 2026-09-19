package app.lumascan.ultra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ScanProcessing {
    fun processPage(
        context: Context,
        source: Uri,
        target: File,
        mode: ColorMode,
        cleanup: Boolean,
        cleanupStrength: Int,
        jpegQuality: Int
    ) {
        target.parentFile?.mkdirs()
        val bitmap = decodeReasonable(context, source)
            ?: error("Unable to decode scanned page")
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (mutable !== bitmap) bitmap.recycle()

        if (cleanup || mode != ColorMode.COLOR) {
            processRows(mutable, mode, cleanup, cleanupStrength.coerceIn(0, 100))
        }

        FileOutputStream(target).use {
            mutable.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(70, 100), it)
        }
        mutable.recycle()
    }

    private fun decodeReasonable(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val maxDim = max(bounds.outWidth, bounds.outHeight)
        while (maxDim / sample > 2800) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun processRows(bitmap: Bitmap, mode: ColorMode, cleanup: Boolean, strength: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val histogram = IntArray(256)
        val stepX = max(1, w / 80)
        val stepY = max(1, h / 80)
        var sampled = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                histogram[luma(Color.red(c), Color.green(c), Color.blue(c))]++
                sampled++
                x += stepX
            }
            y += stepY
        }
        val low = percentile(histogram, sampled, 0.04)
        val high = max(low + 35, percentile(histogram, sampled, 0.985))
        val blend = strength / 100f
        val row = IntArray(w)

        for (yy in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, yy, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val oldL = luma(r, g, b)
                var normalized = ((oldL - low) * 255f / (high - low)).roundToInt().coerceIn(0, 255)

                if (cleanup) {
                    // Lift uneven shadows and compress hard specular highlights while preserving ink.
                    if (normalized < 150) normalized = (normalized + (150 - normalized) * 0.12f * blend).roundToInt()
                    if (normalized > 245) normalized = (245 + (normalized - 245) * 0.25f).roundToInt()
                } else {
                    normalized = oldL
                }

                row[x] = when (mode) {
                    ColorMode.GRAYSCALE -> Color.rgb(normalized, normalized, normalized)
                    ColorMode.BLACK_WHITE -> {
                        val threshold = 178
                        val v = if (normalized >= threshold) 255 else 0
                        Color.rgb(v, v, v)
                    }
                    ColorMode.COLOR -> {
                        if (!cleanup) c else {
                            val target = (oldL + (normalized - oldL) * blend).roundToInt().coerceIn(0, 255)
                            val ratio = target / max(1f, oldL.toFloat())
                            Color.rgb(
                                (r * ratio).roundToInt().coerceIn(0, 255),
                                (g * ratio).roundToInt().coerceIn(0, 255),
                                (b * ratio).roundToInt().coerceIn(0, 255)
                            )
                        }
                    }
                }
            }
            bitmap.setPixels(row, 0, w, 0, yy, w, 1)
        }
    }

    private fun luma(r: Int, g: Int, b: Int): Int =
        (0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)

    private fun percentile(hist: IntArray, total: Int, p: Double): Int {
        val target = (total * p).roundToInt()
        var sum = 0
        for (i in hist.indices) {
            sum += hist[i]
            if (sum >= target) return i
        }
        return 255
    }

    fun buildPdf(images: List<File>, target: File) {
        target.parentFile?.mkdirs()
        val document = PdfDocument()
        images.forEachIndexed { index, file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@forEachIndexed
            val maxPage = 2400f
            val scale = min(1f, maxPage / max(bitmap.width, bitmap.height).toFloat())
            val pageW = max(1, (bitmap.width * scale).roundToInt())
            val pageH = max(1, (bitmap.height * scale).roundToInt())
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create())
            page.canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, pageW, pageH), null)
            document.finishPage(page)
            bitmap.recycle()
        }
        FileOutputStream(target).use { document.writeTo(it) }
        document.close()
    }
}

object SmartNamer {
    private val generic = setOf(
        "invoice", "tax invoice", "receipt", "bill", "statement", "lease agreement",
        "agreement", "document", "total", "date", "original"
    )

    fun name(text: String, createdAt: Long): String {
        val lower = text.lowercase()
        val type = when {
            "invoice" in lower -> "Invoice"
            "receipt" in lower -> "Receipt"
            "lease" in lower && "agreement" in lower -> "Lease_Agreement"
            "agreement" in lower -> "Agreement"
            "bank statement" in lower || "statement" in lower -> "Statement"
            "passport" in lower -> "Passport"
            "purchase order" in lower -> "Purchase_Order"
            else -> "Document"
        }

        val candidate = text.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 3..48 }
            .filter { line ->
                val clean = line.lowercase().replace(Regex("[^a-z ]"), "").trim()
                clean !in generic &&
                    line.count { it.isLetter() } >= 3 &&
                    !line.contains("@") &&
                    !Regex("\\+?\\d[\\d ()-]{6,}").containsMatchIn(line)
            }
            .firstOrNull()
            ?.let(::safePart)
            ?.take(28)

        val month = java.text.SimpleDateFormat("MMM_yyyy", java.util.Locale.US)
            .format(java.util.Date(createdAt))
        return listOfNotNull(type, candidate, month)
            .filter { it.isNotBlank() }
            .joinToString("_")
            .replace(Regex("_+"), "_")
    }

    fun safePart(value: String): String = value
        .trim()
        .replace("&", "and")
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_')
}
