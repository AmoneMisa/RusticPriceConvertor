package com.example.rusticpriceconvertor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text.TextBlock
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors
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
    private lateinit var btnFix: Button
    private lateinit var switchFast: MaterialSwitch
    private lateinit var switchBig: MaterialSwitch

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var lastAnalyzeAt = 0L
    private var lastKey: String? = null
    private var stableHits = 0

    private var isScanning = false
    private var bestValue: String? = null
    private var bestCurrency: String? = null

    private val prefs by lazy {
        requireContext().getSharedPreferences("scan_prefs", Context.MODE_PRIVATE)
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else dismiss()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(
            STYLE_NORMAL,
            com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_scan_price, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.previewView)
        roiFrame = view.findViewById(R.id.roiFrame)
        foundText = view.findViewById(R.id.foundText)

        val btnFix = view.findViewById<Button>(R.id.btnFix)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val switchFastMode =
            view.findViewById<MaterialSwitch>(R.id.switchFastMode)

        btnCancel.setOnClickListener { dismiss() }
        btnFix.visibility =
            if (switchFastMode.isChecked) View.GONE else View.VISIBLE

        switchFastMode.setOnCheckedChangeListener { _, isChecked ->
            btnFix.visibility = if (isChecked) View.GONE else View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        recognizer.close()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val rotation = imageProxy.imageInfo.rotationDegrees
                val input = InputImage.fromMediaImage(mediaImage, rotation)
                analyzeFrame(input, imageProxy)
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

    private fun analyzeFrame(image: InputImage, imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzeAt < 220) {
            imageProxy.close()
            return
        }
        lastAnalyzeAt = now

        if (!switchFast.isChecked && !isScanning) {
            imageProxy.close()
            return
        }

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val roi = mapRoiToImage(imageProxy) ?: return@addOnSuccessListener
                val parsed = if (switchBig.isChecked) {
                    parseBigTag(visionText.textBlocks, roi)
                } else {
                    parseNormal(visionText.textBlocks, roi)
                } ?: return@addOnSuccessListener

                val amountNorm = trimCandidate(parsed.amount)
                bestValue = amountNorm
                bestCurrency = parsed.currency

                val key = amountNorm + "|" + (parsed.currency ?: "")
                if (key == lastKey) stableHits++ else {
                    lastKey = key
                    stableHits = 1
                }

                runOnUi {
                    foundText.text =
                        if (parsed.currency != null) "$amountNorm ${parsed.currency}" else amountNorm
                }

                if (switchFast.isChecked && stableHits >= 2) {
                    deliver(amountNorm, parsed.currency)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun resetStability() {
        lastKey = null
        stableHits = 0
        bestValue = null
        bestCurrency = null
        runOnUi { foundText.text = "" }
    }

    private fun mapRoiToImage(imageProxy: ImageProxy): Rect? {

        val pvW = previewView.width.toFloat()
        val pvH = previewView.height.toFloat()

        if (pvW == 0f || pvH == 0f) return null

        val roiLoc = IntArray(2)
        val pvLoc = IntArray(2)

        roiFrame.getLocationOnScreen(roiLoc)
        previewView.getLocationOnScreen(pvLoc)

        val left = (roiLoc[0] - pvLoc[0]).toFloat() / pvW
        val top = (roiLoc[1] - pvLoc[1]).toFloat() / pvH
        val right = (left + roiFrame.width / pvW)
        val bottom = (top + roiFrame.height / pvH)

        val imgW = imageProxy.width.toFloat()
        val imgH = imageProxy.height.toFloat()

        val l = (left * imgW).toInt().coerceAtLeast(0)
        val t = (top * imgH).toInt().coerceAtLeast(0)
        val r = (right * imgW).toInt().coerceAtMost(imageProxy.width)
        val b = (bottom * imgH).toInt().coerceAtMost(imageProxy.height)

        if (r <= l || b <= t) return null

        return Rect(l, t, r, b)
    }

    private data class Parsed(val amount: String, val currency: String?)

    private fun parseNormal(blocks: List<TextBlock>, roi: Rect): Parsed? {
        val text = buildTextFromRoi(blocks, roi) ?: return null
        return extractBestPriceAndCurrency(text)
    }

    private fun parseBigTag(blocks: List<TextBlock>, roi: Rect): Parsed? {
        val nums = mutableListOf<NumToken>()
        val curTokens = mutableListOf<String>()

        for (b in blocks) {
            for (line in b.lines) {
                val lb = line.boundingBox ?: continue
                if (!Rect.intersects(roi, lb)) continue

                val lineText = line.text
                val cur = detectCurrencyNear(lineText)
                if (cur != null) curTokens.add(cur)

                for (el in line.elements) {
                    val box = el.boundingBox ?: continue
                    if (!Rect.intersects(roi, box)) continue
                    val t = el.text
                    val n = normalizeDigitsToken(t) ?: continue
                    nums.add(NumToken(n, box))
                }
            }
        }

        if (nums.isEmpty()) {
            val fallback = buildTextFromRoi(blocks, roi) ?: return null
            return extractBestPriceAndCurrency(fallback)
        }

        val currency = curTokens.firstOrNull() ?: run {
            val roiText = buildTextFromRoi(blocks, roi).orEmpty()
            detectCurrencyNear(roiText)
        }

        val best =
            nums.maxByOrNull { it.box.height().toLong() * it.box.width().toLong() } ?: return null

        val cents = nums
            .filter { it !== best }
            .filter { it.value.length in 1..2 }
            .filter { isNearCents(best.box, it.box) }
            .minByOrNull { dist(best.box, it.box) }

        val amount = if (cents != null) {
            val major = best.value
            val minor = cents.value.padStart(2, '0').take(2)
            "$major.$minor"
        } else {
            best.value
        }

        val v = amount.toDoubleOrNull()
        if (v == null || v <= 0.0 || v > 1_000_000.0) return null
        return Parsed(amount, currency)
    }

    private data class NumToken(val value: String, val box: Rect)

    private fun normalizeDigitsToken(raw: String): String? {
        val t = raw.trim()
            .replace("O", "0", ignoreCase = true)
            .replace("I", "1", ignoreCase = true)
            .replace("l", "1", ignoreCase = true)

        val digits = t.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        if (t.count { it.isDigit() } >= 1 && t.any { it == ',' || it == '.' }) {
            val parts = t.replace(',', '.').split('.')
            val a = parts.getOrNull(0)?.filter { it.isDigit() }.orEmpty()
            val b = parts.getOrNull(1)?.filter { it.isDigit() }.orEmpty()
            if (a.isNotBlank() && b.isNotBlank()) return a + b.take(2)
        }

        return digits
    }

    private fun isNearCents(major: Rect, minor: Rect): Boolean {
        val majorCy = (major.top + major.bottom) / 2
        val minorCy = (minor.top + minor.bottom) / 2
        val sameLine = kotlin.math.abs(majorCy - minorCy) <= major.height() * 0.55

        val rightSide = minor.left >= major.right - major.width() * 0.15
        val nearHoriz = (minor.left - major.right) <= major.width() * 0.9

        val belowRight = minor.top >= major.top && minor.left >= major.left
        val nearVert = (minor.top - major.bottom) <= major.height() * 0.8

        return (sameLine && rightSide && nearHoriz) || (belowRight && nearVert)
    }

    private fun dist(a: Rect, b: Rect): Int {
        val ax = (a.left + a.right) / 2
        val ay = (a.top + a.bottom) / 2
        val bx = (b.left + b.right) / 2
        val by = (b.top + b.bottom) / 2
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    private fun buildTextFromRoi(blocks: List<TextBlock>, roi: Rect): String? {
        val sb = StringBuilder()
        for (block in blocks) {
            val box = block.boundingBox ?: continue
            if (Rect.intersects(roi, box)) {
                sb.append(block.text).append(' ')
            }
        }
        return sb.toString().trim().ifBlank { null }
    }

    private val moneyRegex =
        Regex("""(?<!\d)(\d{1,3}(?:[ \u00A0]\d{3})+|\d+)(?:[.,](\d{1,2}))?(?!\d)""")

    private fun extractBestPriceAndCurrency(raw: String): Parsed? {
        val text = raw.replace('\u00A0', ' ').replace('\n', ' ')
        if (text.isBlank()) return null

        val matches = moneyRegex.findAll(text).toList()
        if (matches.isEmpty()) return null

        var best: Parsed? = null
        var bestScore = Int.MIN_VALUE

        for (m in matches) {
            val intPart = m.groupValues[1].replace(" ", "")
            val frac = m.groupValues.getOrNull(2).orEmpty()
            val amount = if (frac.isNotBlank()) "$intPart.$frac" else intPart

            val v = amount.toDoubleOrNull() ?: continue
            if (v <= 0.0 || v > 1_000_000.0) continue

            val window = sliceWindow(text, m.range.first, m.range.last + 1, 18)
            val cur = detectCurrencyNear(window)

            val score =
                (if (cur != null) 1000 else 0) +
                        (if (frac.isNotBlank()) 50 else 0) +
                        (if (v >= 10.0) 10 else 0) +
                        (if (v in 0.1..9999.0) 5 else 0)

            if (score > bestScore) {
                bestScore = score
                best = Parsed(amount, cur)
            }
        }

        return best
    }

    private fun sliceWindow(text: String, start: Int, end: Int, pad: Int): String {
        val from = (start - pad).coerceAtLeast(0)
        val to = (end + pad).coerceAtMost(text.length)
        return text.substring(from, to)
    }

    private fun detectCurrencyNear(windowRaw: String): String? {
        val w = windowRaw
            .replace('\u00A0', ' ')
            .uppercase(Locale.getDefault())

        val tokens = listOf(
            "EUR" to "EUR", "€" to "EUR", "ЕВРО" to "EUR",
            "GBP" to "GBP", "£" to "GBP", "ФУНТ" to "GBP",
            "CHF" to "CHF", "FR" to "CHF",
            "PLN" to "PLN", "ZŁ" to "PLN", "ЗЛОТ" to "PLN",
            "CZK" to "CZK", "KČ" to "CZK",
            "HUF" to "HUF", "FT" to "HUF",
            "SEK" to "SEK", "NOK" to "NOK", "DKK" to "DKK", "ISK" to "ISK",
            "RON" to "RON", "LEI" to "RON",
            "MDL" to "MDL",
            "BGN" to "BGN",
            "RSD" to "RSD",
            "ALL" to "ALL",
            "BAM" to "BAM",
            "MKD" to "MKD",
            "TRY" to "TRY", "₺" to "TRY", " TL" to "TRY", "ЛИРА" to "TRY",
            "GEL" to "GEL", "₾" to "GEL", "ЛАРИ" to "GEL",
            "UAH" to "UAH", "₴" to "UAH", "ГРН" to "UAH", "HRN" to "UAH",
            "RUB" to "RUB", "₽" to "RUB", "РУБ" to "RUB", "РУБЛ" to "RUB",
            "BYN" to "BYN", "БЕЛ" to "BYN",
            "KZT" to "KZT", "₸" to "KZT", "ТЕНГ" to "KZT", "ТГ" to "KZT",
            "UZS" to "UZS", "СУМ" to "UZS",
            "KGS" to "KGS", "СОМ" to "KGS",
            "AMD" to "AMD", "֏" to "AMD", "ДРАМ" to "AMD",
            "AZN" to "AZN", "₼" to "AZN",
            "TJS" to "TJS", "СОМОНИ" to "TJS",
            "TMT" to "TMT",
            "USD" to "USD", "US$" to "USD", "ДОЛЛАР" to "USD",
            "CAD" to "CAD", "C$" to "CAD", "CA$" to "CAD",
            "AUD" to "AUD", "A$" to "AUD", "AU$" to "AUD",
            "AED" to "AED", "د.إ" to "AED", "ДИРХАМ" to "AED",
            "KRW" to "KRW", "₩" to "KRW", "ВОН" to "KRW", "원" to "KRW",
            "JPY" to "JPY", "¥" to "JPY", "円" to "JPY", "ЙЕН" to "JPY", "ИЕН" to "JPY",
            "CNY" to "CNY", "¥" to "CNY", "元" to "CNY", "RMB" to "CNY",
            "THB" to "THB", "฿" to "THB",
            "VND" to "VND", "₫" to "VND",
            "EGP" to "EGP", "ج.م" to "EGP"
        )

        for ((token, code) in tokens) {
            if (w.contains(token)) return code
        }

        if (w.contains("$")) {
            if (w.contains("CAD") || w.contains("C$") || w.contains("CA$")) return "CAD"
            if (w.contains("AUD") || w.contains("A$") || w.contains("AU$")) return "AUD"
            return "USD"
        }

        return null
    }

    private fun trimCandidate(s: String): String {
        val v = s.toDoubleOrNull() ?: return s
        val clamped = min(max(v, 0.0), 1_000_000.0)
        return if (clamped < 1.0) String.format("%.4f", clamped) else String.format("%.2f", clamped)
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
}
