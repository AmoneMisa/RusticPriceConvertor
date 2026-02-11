package com.example.rusticpriceconvertor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.YuvImage
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ScanPriceDialogFragment : DialogFragment() {

    companion object {
        const val REQ_KEY = "scan_price"
        const val RESULT_VALUE = "value"
        const val RESULT_CURRENCY = "currency"
    }

    private lateinit var previewView: PreviewView
    private lateinit var roiFrame: View
    private lateinit var foundText: TextView

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var lastAnalyzeAt = 0L
    private var lastKey: String? = null
    private var stableHits = 0

    private var bestValue: String? = null
    private var bestCurrency: String? = null
    private var bestScore: Int = Int.MIN_VALUE

    private var dragging = false
    private var dragDx = 0f
    private var dragDy = 0f
    private var isPinching = false
    private var pinchStartDist = 0f
    private var pinchStartW = 0
    private var pinchStartH = 0
    private val minRoiSizeDp = 120f
    private val maxRoiSizeDp = 340f

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else dismiss()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_scan_price, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        previewView = view.findViewById(R.id.previewView)
        roiFrame = view.findViewById(R.id.roiFrame)
        foundText = view.findViewById(R.id.foundText)

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dismiss() }
        view.findViewById<Button>(R.id.btnFix)?.setOnClickListener {
            val v = bestValue ?: return@setOnClickListener
            deliver(v, bestCurrency)
        }

        setupRoiGestures()

        val hasCamera = requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        if (!hasCamera) {
            dismiss()
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) startCamera() else requestCameraPermission.launch(Manifest.permission.CAMERA)

        roiFrame.post { clampRoiToPreview() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        recognizer.close()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun setupRoiGestures() {
        roiFrame.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    isPinching = false
                    dragDx = ev.rawX - v.x
                    dragDy = ev.rawY - v.y
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount >= 2) {
                        isPinching = true
                        dragging = false
                        pinchStartDist = dist(ev)
                        pinchStartW = v.width
                        pinchStartH = v.height
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isPinching && ev.pointerCount >= 2) {
                        val d0 = pinchStartDist
                        val d1 = dist(ev)
                        if (d0 > 0f) {
                            val scale = d1 / d0
                            val minPx = dp(minRoiSizeDp).toInt()
                            val maxPx = dp(maxRoiSizeDp).toInt()
                            val newW = (pinchStartW * scale).toInt().coerceIn(minPx, maxPx)
                            val newH = (pinchStartH * scale).toInt().coerceIn(minPx, maxPx)
                            val lp = v.layoutParams
                            lp.width = newW
                            lp.height = newH
                            v.layoutParams = lp
                            clampRoiToPreview()
                        }
                        true
                    } else if (dragging) {
                        v.x = ev.rawX - dragDx
                        v.y = ev.rawY - dragDy
                        clampRoiToPreview()
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    isPinching = false
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (ev.pointerCount <= 2) {
                        isPinching = false
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun dist(ev: MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun clampRoiToPreview() {
        val pv = previewView
        val v = roiFrame
        val leftMin = pv.x
        val topMin = pv.y
        val rightMax = pv.x + pv.width
        val bottomMax = pv.y + pv.height

        val nx = v.x.coerceIn(leftMin, rightMax - v.width)
        val ny = v.y.coerceIn(topMin, bottomMax - v.height)

        v.x = nx
        v.y = ny
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                analyzeProxy(imageProxy)
            }

            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
            } catch (_: Exception) {
                dismiss()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun analyzeProxy(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzeAt < 220) {
            imageProxy.close()
            return
        }
        lastAnalyzeAt = now

        val pvW = previewView.width
        val pvH = previewView.height
        val roiW = roiFrame.width
        val roiH = roiFrame.height
        if (pvW <= 0 || pvH <= 0 || roiW <= 0 || roiH <= 0) {
            imageProxy.close()
            return
        }

        val bitmap = try {
            imageProxyToBitmap(imageProxy)
        } catch (_: Exception) {
            null
        }

        if (bitmap == null) {
            imageProxy.close()
            return
        }

        val rotated = try {
            rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
        } catch (_: Exception) {
            bitmap
        }

        val crop = try {
            val cropRect = roiToBitmapRect(rotated.width, rotated.height)
            val safe = RectF(
                cropRect.left.coerceAtLeast(0f),
                cropRect.top.coerceAtLeast(0f),
                cropRect.right.coerceAtMost(rotated.width.toFloat()),
                cropRect.bottom.coerceAtMost(rotated.height.toFloat())
            )
            val w = max(1, (safe.width()).toInt())
            val h = max(1, (safe.height()).toInt())
            Bitmap.createBitmap(rotated, safe.left.toInt(), safe.top.toInt(), w, h)
        } catch (_: Exception) {
            null
        }

        if (crop == null) {
            imageProxy.close()
            return
        }

        val input = InputImage.fromBitmap(crop, 0)

        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                val parsed = extractBestPriceAndCurrency(visionText.text) ?: return@addOnSuccessListener
                val amountNorm = normalizeAmount(parsed.amount) ?: return@addOnSuccessListener

                val key = amountNorm + "|" + (parsed.currency ?: "")
                if (key == lastKey) stableHits++ else {
                    lastKey = key
                    stableHits = 1
                }

                if (parsed.score > bestScore) {
                    bestScore = parsed.score
                    bestValue = amountNorm
                    bestCurrency = parsed.currency
                }

                val display = if (parsed.currency != null) "$amountNorm ${parsed.currency}" else amountNorm
                runOnUi { foundText.text = display }

                if (stableHits >= 2) {
                    deliver(amountNorm, parsed.currency)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun roiToBitmapRect(bmpW: Int, bmpH: Int): RectF {
        val pvW = previewView.width.toFloat()
        val pvH = previewView.height.toFloat()

        val pvLoc = IntArray(2)
        val roiLoc = IntArray(2)
        previewView.getLocationOnScreen(pvLoc)
        roiFrame.getLocationOnScreen(roiLoc)

        val roiLeftInPv = (roiLoc[0] - pvLoc[0]).toFloat()
        val roiTopInPv = (roiLoc[1] - pvLoc[1]).toFloat()
        val roiRightInPv = roiLeftInPv + roiFrame.width
        val roiBottomInPv = roiTopInPv + roiFrame.height

        val scale = max(pvW / bmpW.toFloat(), pvH / bmpH.toFloat())
        val displayedW = bmpW * scale
        val displayedH = bmpH * scale
        val offsetX = (pvW - displayedW) / 2f
        val offsetY = (pvH - displayedH) / 2f

        val left = (roiLeftInPv - offsetX) / scale
        val top = (roiTopInPv - offsetY) / scale
        val right = (roiRightInPv - offsetX) / scale
        val bottom = (roiBottomInPv - offsetY) / scale

        return RectF(left, top, right, bottom)
    }

    private fun deliver(value: String, currency: String?) {
        parentFragmentManager.setFragmentResult(
            REQ_KEY,
            Bundle().apply {
                putString(RESULT_VALUE, value)
                putString(RESULT_CURRENCY, currency)
            }
        )
        dismissAllowingStateLoss()
    }

    private fun runOnUi(block: () -> Unit) {
        activity?.runOnUiThread(block)
    }

    private fun rotateBitmap(src: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return src
        val m = Matrix()
        m.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val image = imageProxy.image ?: throw IllegalStateException("no image")
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)

        val chromaRowStride = image.planes[1].rowStride
        val chromaPixelStride = image.planes[1].pixelStride

        val width = image.width
        val height = image.height

        val uvHeight = height / 2
        val uvWidth = width / 2

        val uBytes = ByteArray(uBuffer.remaining())
        uBuffer.get(uBytes)

        val vBytes = ByteArray(vBuffer.remaining())
        vBuffer.get(vBytes)

        var outIndex = ySize
        var row = 0
        while (row < uvHeight) {
            var col = 0
            while (col < uvWidth) {
                val uIndex = row * chromaRowStride + col * chromaPixelStride
                val vIndex = row * image.planes[2].rowStride + col * image.planes[2].pixelStride

                val vVal = vBytes[vIndex]
                val uVal = uBytes[uIndex]

                nv21[outIndex++] = vVal
                nv21[outIndex++] = uVal
                col++
            }
            row++
        }

        val yuv = YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), 90, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private data class Parsed(val amount: String, val currency: String?, val score: Int)

    private val moneyRegex =
        Regex("""(?<!\d)(\d{1,3}(?:[ \u00A0]\d{3})+|\d+)(?:[.,](\d{1,2}))?(?!\d)""")

    private val splitDecimalRegex =
        Regex("""(?<!\d)(\d{1,3})(?:\s+|[^\d]{1,6})(\d{2})(?!\d)""")

    private fun extractBestPriceAndCurrency(raw: String): Parsed? {
        val text = raw.replace('\u00A0', ' ').replace('\n', ' ').trim()
        if (text.isBlank()) return null

        val matches = moneyRegex.findAll(text).toList()
        val candidates = mutableListOf<Parsed>()

        for (m in matches) {
            val intPart = m.groupValues[1].replace(" ", "")
            val frac = m.groupValues.getOrNull(2).orEmpty()
            val amount = if (frac.isNotBlank()) "$intPart.$frac" else intPart

            val start = m.range.first
            val end = m.range.last + 1
            val win = sliceWindow(text, start, end, 18)
            val cur = detectCurrencyNear(win)

            val v = amount.toDoubleOrNull() ?: continue
            if (v <= 0.0 || v > 1_000_000.0) continue

            val score =
                (if (cur != null) 5000 else 0) +
                        (if (frac.isNotBlank()) 120 else 0) +
                        (if (v >= 1.0) 20 else 0) +
                        (if (v >= 10.0) 10 else 0)

            candidates += Parsed(amount, cur, score)
        }

        val upper = text.uppercase(Locale.getDefault())
        val hasCurrencyToken = detectCurrencyNear(upper) != null

        if (hasCurrencyToken) {
            val m2 = splitDecimalRegex.findAll(text).toList()
            for (m in m2) {
                val a = m.groupValues[1]
                val b = m.groupValues[2]
                val amount = "$a.$b"
                val v = amount.toDoubleOrNull() ?: continue
                if (v <= 0.0 || v > 1_000_000.0) continue
                val win = sliceWindow(text, m.range.first, m.range.last + 1, 20)
                val cur = detectCurrencyNear(win)
                val score = (if (cur != null) 6000 else 0) + 200 + (if (v >= 1.0) 20 else 0)
                candidates += Parsed(amount, cur, score)
            }
        }

        return candidates.maxByOrNull { it.score }
    }

    private fun sliceWindow(text: String, start: Int, end: Int, pad: Int): String {
        val from = (start - pad).coerceAtLeast(0)
        val to = (end + pad).coerceAtMost(text.length)
        return text.substring(from, to)
    }

    private fun normalizeAmount(s: String): String? {
        val cleaned = s.replace(" ", "").replace(',', '.')
        val v = cleaned.toDoubleOrNull() ?: return null
        if (v <= 0.0 || v > 1_000_000.0) return null
        return if (v < 1.0) String.format(Locale.US, "%.4f", v) else String.format(Locale.US, "%.2f", v)
    }

    private fun detectCurrencyNear(windowRaw: String): String? {
        val w = windowRaw
            .replace('\u00A0', ' ')
            .replace('\n', ' ')
            .uppercase(Locale.getDefault())

        val hard = listOf(
            "UAH" to "UAH", "₴" to "UAH", "ГРН" to "UAH", "HRN" to "UAH",

            "RUB" to "RUB", "₽" to "RUB", "РУБ" to "RUB", "RUR" to "RUB",

            "BYN" to "BYN", "BYR" to "BYN", "BR" to "BYN",

            "TRY" to "TRY", "₺" to "TRY", " TL" to "TRY", "ТЛ" to "TRY",

            "GEL" to "GEL", "₾" to "GEL", "ЛАРИ" to "GEL",

            "RON" to "RON", " LEI" to "RON", "LEI" to "RON",

            "MDL" to "MDL",

            "KGS" to "KGS", "СОМ" to "KGS",

            "UZS" to "UZS", "СУМ" to "UZS",

            "KZT" to "KZT", "₸" to "KZT", "ТГ" to "KZT", "ТЕНГЕ" to "KZT",

            "USD" to "USD", "US$" to "USD",

            "EUR" to "EUR", "€" to "EUR",

            "GBP" to "GBP", "£" to "GBP",

            "PLN" to "PLN", "ZŁ" to "PLN", "ZL" to "PLN",

            "CAD" to "CAD", "C$" to "CAD", "CA$" to "CAD",

            "AUD" to "AUD", "A$" to "AUD", "AU$" to "AUD",

            "AED" to "AED", "د.إ" to "AED",

            "KRW" to "KRW", "₩" to "KRW", "원" to "KRW", "ВОН" to "KRW",

            "JPY" to "JPY", "¥" to "JPY", "円" to "JPY", "ЙЕН" to "JPY", "ИЕН" to "JPY"
        )

        for ((token, code) in hard) {
            if (w.contains(token)) {
                if (token == "LEI" || token == " LEI") {
                    if (w.contains("MDL") || w.contains("MOLD") || w.contains("МОЛД")) return "MDL"
                    return "RON"
                }
                return code
            }
        }

        if (w.contains("$")) {
            if (w.contains("CAD") || w.contains("C$") || w.contains("CA$")) return "CAD"
            if (w.contains("AUD") || w.contains("A$") || w.contains("AU$")) return "AUD"
            return "USD"
        }

        return null
    }
}
