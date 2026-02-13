package com.example.rusticpriceconvertor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
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
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.content.ContextCompat
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
    private lateinit var foundText: TextView
    private lateinit var btnFix: Button
    private lateinit var switchFast: MaterialSwitch
    private lateinit var switchBig: MaterialSwitch
    private lateinit var roiFrame: RoiOverlayView
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null

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
        roiFrame = view.findViewById(R.id.roiFrame)
        previewView = view.findViewById(R.id.previewView)
        foundText = view.findViewById(R.id.foundText)
        btnFix = view.findViewById(R.id.btnFix)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        switchFast = view.findViewById(R.id.switchFastMode)
        switchBig = view.findViewById(R.id.switchBigTag)

        view.findViewById<View>(R.id.foundPill).visibility = View.GONE
        foundText.text = ""

        btnCancel.setOnClickListener { dismissAllowingStateLoss() }

        btnFix.setOnClickListener {
            val v = bestValue
            if (!v.isNullOrBlank()) {
                deliver(v, bestCurrency)
                return@setOnClickListener
            }
            isScanning = true
            resetStability()
            Toast.makeText(requireContext(), "Наведи на цену…", Toast.LENGTH_SHORT).show()
        }

        btnFix.visibility = if (switchFast.isChecked) View.GONE else View.VISIBLE
        switchFast.setOnCheckedChangeListener { _, isChecked ->
            btnFix.visibility = if (isChecked) View.GONE else View.VISIBLE
            isScanning = isChecked
            resetStability()
        }

        switchBig.setOnCheckedChangeListener { _, _ ->
            resetStability()
        }

        ensureCameraAndStart()
    }

    private fun ensureCameraAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            previewView.post { startCamera() }
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
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

            val preview =
                Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            previewUseCase = preview

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysisUseCase = analysis

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
                    val pill = view?.findViewById<View>(R.id.foundPill)
                    pill?.visibility = View.VISIBLE
                    foundText.text =
                        if (parsed.currency != null) "$amountNorm ${parsed.currency}" else amountNorm
                }

                if ((switchFast.isChecked || isScanning) && stableHits >= 2) {
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
        runOnUi {
            view?.findViewById<View>(R.id.foundPill)?.visibility = View.GONE
            foundText.text = ""
        }
    }

    @OptIn(TransformExperimental::class)
    private fun mapRoiToImage(imageProxy: ImageProxy): Rect? {
        val overlay = roiFrame
        val roiLocal = overlay.getRoiInView()

        val roiLoc = IntArray(2)
        val pvLoc = IntArray(2)
        overlay.getLocationInWindow(roiLoc)
        previewView.getLocationInWindow(pvLoc)

        val dx = (roiLoc[0] - pvLoc[0]).toFloat()
        val dy = (roiLoc[1] - pvLoc[1]).toFloat()

        val roiInPreview = RectF(
            roiLocal.left + dx,
            roiLocal.top + dy,
            roiLocal.right + dx,
            roiLocal.bottom + dy
        )

        val pvTransform = previewView.outputTransform ?: return null

        val factory = ImageProxyTransformFactory().apply {
            isUsingRotationDegrees = true
            isUsingCropRect = true
        }
        val imgTransform = factory.getOutputTransform(imageProxy)

        val pvToImg = CoordinateTransform(pvTransform, imgTransform)

        val pts = floatArrayOf(
            roiInPreview.left, roiInPreview.top,
            roiInPreview.right, roiInPreview.bottom
        )
        pvToImg.mapPoints(pts)

        val l = min(pts[0], pts[2]).toInt()
        val t = min(pts[1], pts[3]).toInt()
        val r = max(pts[0], pts[2]).toInt()
        val b = max(pts[1], pts[3]).toInt()

        val cl = l.coerceAtLeast(0)
        val ct = t.coerceAtLeast(0)
        val cr = r.coerceAtMost(imageProxy.width)
        val cb = b.coerceAtMost(imageProxy.height)

        if (cr <= cl || cb <= ct) return null
        return Rect(cl, ct, cr, cb)
    }

    private data class Parsed(val amount: String, val currency: String?)

    private fun extractByTokens(blocks: List<TextBlock>, roi: Rect): Parsed? {
        data class Tok(val text: String, val box: Rect)

        fun normDigits(raw: String): String? {
            val t = raw.trim()
                .replace("O", "0", ignoreCase = true)
                .replace("D", "0", ignoreCase = true)
                .replace("I", "1", ignoreCase = true)
                .replace("l", "1", ignoreCase = true)

            val digits = t.filter { it.isDigit() }
            return digits.ifBlank { null }
        }

        fun overlap(a: Rect, b: Rect): Float {
            val ixL = max(a.left, b.left)
            val ixT = max(a.top, b.top)
            val ixR = min(a.right, b.right)
            val ixB = min(a.bottom, b.bottom)
            if (ixR <= ixL || ixB <= ixT) return 0f
            val inter = (ixR - ixL) * (ixB - ixT)
            val area = (a.width() * a.height()).coerceAtLeast(1)
            return inter.toFloat() / area.toFloat()
        }

        fun lineOk(lb: Rect): Boolean {
            val cx = (lb.left + lb.right) / 2
            val cy = (lb.top + lb.bottom) / 2
            return roi.contains(cx, cy) || overlap(lb, roi) >= 0.20f
        }

        val candidates = mutableListOf<Pair<String, String?>>()

        for (b in blocks) {
            for (line in b.lines) {
                val lb = line.boundingBox ?: continue
                if (!lineOk(lb)) continue

                val cur = detectCurrencyNear(line.text)

                val toks = mutableListOf<Tok>()
                for (el in line.elements) {
                    val box = el.boundingBox ?: continue
                    val n = normDigits(el.text) ?: continue
                    toks.add(Tok(n, box))
                }
                if (toks.isEmpty()) continue

                toks.sortBy { it.box.left }

                var i = 0
                while (i < toks.size) {
                    val a = toks[i]
                    var s = a.text
                    var lastBox = a.box

                    var j = i + 1
                    while (j < toks.size) {
                        val bTok = toks[j]
                        val gap = bTok.box.left - lastBox.right
                        val sameLine =
                            kotlin.math.abs(bTok.box.centerY() - lastBox.centerY()) <= lastBox.height() * 0.6f
                        val looksLikeGroup = bTok.text.length == 3
                        val near = gap <= lastBox.height() * 1.2f && gap >= -lastBox.height() * 0.2f

                        if (sameLine && looksLikeGroup && near) {
                            s += bTok.text
                            lastBox = bTok.box
                            j++
                        } else break
                    }

                    val v = s.toDoubleOrNull()
                    if (v != null && v > 0.0 && v <= 1_000_000.0) {
                        candidates.add(s to cur)
                    }

                    i = if (j > i + 1) j else i + 1
                }
            }
        }

        if (candidates.isEmpty()) return null

        val best = candidates.maxWith(
            compareBy<Pair<String, String?>>({ it.first.length }, { it.first.toLongOrNull() ?: 0L })
        )

        return Parsed(best.first, best.second)
    }

    private fun parseNormal(blocks: List<TextBlock>, roi: Rect): Parsed? {
        extractByTokens(blocks, roi)?.let { return it }
        val text = buildTextFromRoi(blocks, roi) ?: return null
        return extractBestPriceAndCurrency(text)
    }

    private fun centerInside(box: Rect, roi: Rect): Boolean {
        val cx = (box.left + box.right) / 2
        val cy = (box.top + box.bottom) / 2
        return roi.contains(cx, cy)
    }

    private fun parseBigTag(blocks: List<TextBlock>, roi: Rect): Parsed? {
        val roiText = buildTextFromRoi(blocks, roi)
        if (!roiText.isNullOrBlank()) {
            val m = Regex("""(?<!\d)(\d{1,3})\s*[.,]?\s*(\d{2})(?!\d)""").find(
                roiText.replace(
                    '\n',
                    ' '
                )
            )
            if (m != null) {
                val amount = "${m.groupValues[1]}.${m.groupValues[2]}"
                val v = amount.toDoubleOrNull()
                if (v != null && v > 0.0 && v < 1_000_000.0) {
                    val cur = detectCurrencyNear(roiText)
                    return Parsed(amount, cur)
                }
            }

            extractBestPriceAndCurrency(roiText)?.let { return it }
        }

        val nums = mutableListOf<NumToken>()
        val curTokens = mutableListOf<String>()

        for (b in blocks) {
            for (line in b.lines) {
                val lb = line.boundingBox ?: continue
                if (!centerInside(lb, roi)) continue

                val lineText = line.text
                val cur = detectCurrencyNear(lineText)
                if (cur != null) curTokens.add(cur)

                for (el in line.elements) {
                    val box = el.boundingBox ?: continue
                    if (!centerInside(lb, roi)) continue
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
        data class Tok(val text: String, val box: Rect)

        fun overlapRatio(a: Rect, b: Rect): Float {
            val ixL = max(a.left, b.left)
            val ixT = max(a.top, b.top)
            val ixR = min(a.right, b.right)
            val ixB = min(a.bottom, b.bottom)
            if (ixR <= ixL || ixB <= ixT) return 0f
            val inter = (ixR - ixL) * (ixB - ixT)
            val area = (a.width() * a.height()).coerceAtLeast(1)
            return inter.toFloat() / area.toFloat()
        }

        fun centerInside(a: Rect, b: Rect): Boolean {
            val cx = (a.left + a.right) / 2
            val cy = (a.top + a.bottom) / 2
            return b.contains(cx, cy)
        }

        val tokens = ArrayList<Tok>(64)

        for (block in blocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val box = el.boundingBox ?: continue
                    val ok = centerInside(box, roi) || overlapRatio(box, roi) >= 0.65f
                    if (!ok) continue

                    val t = el.text.trim()
                    if (t.isNotEmpty()) tokens.add(Tok(t, box))
                }
            }
        }

        if (tokens.isEmpty()) return null

        tokens.sortWith(compareBy<Tok>({ it.box.centerY() }, { it.box.left }))

        val sb = StringBuilder()
        var lastCy = Int.MIN_VALUE
        var lineH = tokens.first().box.height().coerceAtLeast(1)

        for (tok in tokens) {
            val cy = tok.box.centerY()
            val newLine = lastCy != Int.MIN_VALUE && kotlin.math.abs(cy - lastCy) > lineH * 0.55f
            if (newLine) sb.append('\n') else if (sb.isNotEmpty() && sb.last() != '\n') sb.append(
                ' '
            )
            sb.append(tok.text)
            lastCy = cy
            lineH = max(lineH, tok.box.height())
        }

        return sb.toString().trim().ifBlank { null }
    }

    private val moneyRegex =
        Regex("""(?<!\d)(\d{1,3}(?:[\s\u00A0\u202F]+\d{3})+|\d+)(?:[.,](\d{1,2}))?(?!\d)""")

    private fun extractBestPriceAndCurrency(raw: String): Parsed? {
        val text = raw
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace('\n', ' ')
        if (text.isBlank()) return null

        val matches = moneyRegex.findAll(text).toList()
        if (matches.isEmpty()) return null

        var best: Parsed? = null
        var bestScore = Int.MIN_VALUE

        for (m in matches) {
            val frac = m.groupValues.getOrNull(2).orEmpty()
            val rawInt = m.groupValues[1]
            val intPart = rawInt.replace(Regex("""\s+"""), "")
            val amount = if (frac.isNotBlank()) "$intPart.$frac" else intPart
            val v = amount.toDoubleOrNull() ?: continue
            if (v <= 0.0 || v > 1_000_000.0) continue

            val window = sliceWindow(text, m.range.first, m.range.last + 1, 18)
            val cur = detectCurrencyNear(window)
            val hasThousandsSep = Regex("""[\s\u00A0\u202F]\d{3}""").containsMatchIn(rawInt)

            val score =
                (if (cur != null) 1000 else 0) +
                        (if (frac.isNotBlank()) 50 else 0) +
                        (if (hasThousandsSep) 500 else 0)

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
        isScanning = false
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
