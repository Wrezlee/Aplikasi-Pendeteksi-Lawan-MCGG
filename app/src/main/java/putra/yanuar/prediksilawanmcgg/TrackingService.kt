package putra.yanuar.prediksilawanmcgg

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class TrackingService : Service() {

    // ── Window & UI ───────────────────────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: LinearLayout
    private lateinit var collapsedView: Button
    private lateinit var expandedView: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var ocrStatusText: TextView
    private lateinit var buttonsContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var wmParams: WindowManager.LayoutParams

    // Popup edit terpisah
    private var popupView: LinearLayout? = null
    private var popupParams: WindowManager.LayoutParams? = null

    // ── OCR & Screen Capture ──────────────────────────────────────────────────
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val ocrExecutor = Executors.newSingleThreadScheduledExecutor()
    private var ocrTask: ScheduledFuture<*>? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val mainHandler = Handler(Looper.getMainLooper())

    // State OCR
    private var isOcrEnabled = false
    private val previouslyVisibleSlots = mutableSetOf<Int>()
    private val slotCooldown = mutableMapOf<Int, Long>()
    private val COOLDOWN_MS = 4000L

    companion object {
        private const val CHANNEL_ID = "MCGGTrackerChannel"
        private const val NOTIF_ID = 1
        private const val TOTAL_ENEMIES = 7
    }

    // ── Game State ────────────────────────────────────────────────────────────
    private val enemyNames = mutableMapOf<Int, String>()
    private val eliminatedSlots = mutableMapOf<Int, Boolean>()
    private val cycleSlots = ArrayList<Int>()
    private var currentRound = 1
    private var isCycleLocked = false
    private var cycleIndex = 0

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        for (i in 1..TOTAL_ENEMIES) {
            enemyNames[i] = "Musuh $i"
            eliminatedSlots[i] = false
        }
        setupLayouts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("result_code", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("result_data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("result_data")
        }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            initMediaProjection(resultCode, resultData)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isOcrEnabled = false
        ocrTask?.cancel(true)
        ocrExecutor.shutdown()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        textRecognizer.close()
        closePopup()
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
    }

    // =========================================================================
    // Foreground Notification
    // =========================================================================

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "MC GG Tracker", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Tracker musuh berjalan di latar belakang" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MC GG Tracker Aktif")
            .setContentText("Tracker sedang memantau giliran musuh")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    } // <-- kurung tutup startForegroundNotification

    // =========================================================================
    // MediaProjection & OCR
    // =========================================================================

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        // Android 14+ wajib register callback sebelum createVirtualDisplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    isOcrEnabled = false
                    virtualDisplay?.release()
                    updateOcrStatusLabel()
                }
            }, Handler(Looper.getMainLooper()))
        }

        val (screenW, screenH) = getScreenDimensions()
        val captureW = screenW / 2
        val captureH = screenH / 2
        val density = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(
            captureW, captureH, PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MCGGCapture",
            captureW, captureH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        isOcrEnabled = true
        updateOcrStatusLabel()
        startOcrLoop()
        Toast.makeText(this, "OCR Aktif", Toast.LENGTH_SHORT).show()
    }

    private fun startOcrLoop() {
        ocrTask = ocrExecutor.scheduleAtFixedRate({
            if (!isOcrEnabled) return@scheduleAtFixedRate
            val bitmap = captureScreen() ?: return@scheduleAtFixedRate
            runOcr(bitmap)
        }, 1500, 1000, TimeUnit.MILLISECONDS)
    }

    private fun captureScreen(): Bitmap? {
        return try {
            val image: Image = imageReader?.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val fullBmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            fullBmp.copyPixelsFromBuffer(buffer)
            image.close()

            // Crop sisi kanan 40% = area scoreboard musuh
            val cropX = (fullBmp.width * 0.60).toInt()
            val cropW = fullBmp.width - cropX
            val cropped = Bitmap.createBitmap(fullBmp, cropX, 0, cropW, fullBmp.height)
            fullBmp.recycle()
            cropped
        } catch (e: Exception) {
            null
        }
    }

    private fun runOcr(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                bitmap.recycle()
                processOcrResult(visionText.text)
            }
            .addOnFailureListener {
                bitmap.recycle()
            }
    }

    private fun processOcrResult(rawText: String) {
        val now = System.currentTimeMillis()
        val currentlyVisible = mutableSetOf<Int>()

        for (slot in 1..TOTAL_ENEMIES) {
            if (eliminatedSlots[slot] == true) continue
            val name = enemyNames[slot] ?: continue
            if (name.startsWith("Musuh ")) continue // skip nama default

            if (isNameInText(name, rawText)) {
                currentlyVisible.add(slot)
            }
        }

        // Slot yang BARU muncul di frame ini
        val newlyAppeared = currentlyVisible - previouslyVisibleSlots

        for (slot in newlyAppeared) {
            val lastTrigger = slotCooldown[slot] ?: 0L
            if (now - lastTrigger > COOLDOWN_MS) {
                slotCooldown[slot] = now
                mainHandler.post {
                    handleEnemyTap(slot)
                    Toast.makeText(
                        this,
                        "Auto-detect: ${displayName(slot)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        previouslyVisibleSlots.clear()
        previouslyVisibleSlots.addAll(currentlyVisible)
    }

    private fun isNameInText(name: String, text: String): Boolean {
        if (name.length < 3) return text.contains(name, ignoreCase = true)
        if (text.contains(name, ignoreCase = true)) return true

        val nameLower = name.lowercase()
        for (line in text.lines()) {
            val lineLower = line.trim().lowercase()
            if (lineLower.isEmpty()) continue
            if (levenshteinSimilarity(nameLower, lineLower) >= 0.80) return true
        }
        return false
    }

    private fun levenshteinSimilarity(a: String, b: String): Double {
        val la = a.length; val lb = b.length
        if (la == 0 || lb == 0) return 0.0
        val dp = Array(la + 1) { IntArray(lb + 1) }
        for (i in 0..la) dp[i][0] = i
        for (j in 0..lb) dp[0][j] = j
        for (i in 1..la) for (j in 1..lb) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
            else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return 1.0 - dp[la][lb].toDouble() / maxOf(la, lb)
    }

    private fun updateOcrStatusLabel() {
        if (::ocrStatusText.isInitialized) {
            mainHandler.post {
                if (isOcrEnabled) {
                    ocrStatusText.text = "OCR Aktif — Auto detect nyala"
                    ocrStatusText.setTextColor(Color.parseColor("#88FF88"))
                } else {
                    ocrStatusText.text = "Mode Manual — Tap musuh manual"
                    ocrStatusText.setTextColor(Color.parseColor("#FF8888"))
                }
            }
        }
    }

    // =========================================================================
    // UI Setup
    // =========================================================================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun getScreenDimensions(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun displayName(slot: Int) = enemyNames[slot] ?: "Musuh $slot"
    private fun activeSlots() = (1..TOTAL_ENEMIES).filter { eliminatedSlots[it] == false }

    private fun setupLayouts() {
        floatingView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        collapsedView = Button(this).apply {
            text = "MC"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#FF6200EE"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(40))
        }

        expandedView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0111111"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(270), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Header row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleText = TextView(this).apply {
            text = "Ronde: 1\nTap musuh saat muncul"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = Button(this).apply {
            text = "X"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC991111"))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(32))
            setPadding(0, 0, 0, 0)
            setOnClickListener { closePopup(); stopSelf() }
        }
        headerRow.addView(titleText)
        headerRow.addView(btnClose)
        expandedView.addView(headerRow)

        // Status OCR
        ocrStatusText = TextView(this).apply {
            text = "Mode Manual — Tap musuh manual"
            setTextColor(Color.parseColor("#FF8888"))
            textSize = 9f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, dp(3), 0, dp(2)) }
        }
        expandedView.addView(ocrStatusText)

        // Divider
        expandedView.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#55FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.setMargins(0, dp(4), 0, dp(4)) }
        })

        // Hint
        expandedView.addView(TextView(this).apply {
            text = "Tap=catat/prediksi  |  Tahan=edit nama/eliminasi"
            setTextColor(Color.parseColor("#99FFFFFF"))
            textSize = 9f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(4)) }
        })

        // ScrollView tombol musuh
        scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230)
            )
        }
        buttonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        buildEnemyButtons()
        scrollView.addView(buttonsContainer)
        expandedView.addView(scrollView)

        // Reset
        expandedView.addView(Button(this).apply {
            text = "RESET SIKLUS"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC880000"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)
            ).also { it.setMargins(0, dp(5), 0, 0) }
            setPadding(0, 0, 0, 0)
            setOnClickListener { resetAll() }
        })

        floatingView.addView(collapsedView)
        floatingView.addView(expandedView)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        wmParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20; y = 80
        }

        windowManager.addView(floatingView, wmParams)
        setupMovementAndClickLogic()
    }

    private fun recalcScrollHeight() {
        val (_, h) = getScreenDimensions()
        val lp = scrollView.layoutParams as LinearLayout.LayoutParams
        lp.height = (h * 0.45).toInt()
        scrollView.layoutParams = lp
    }

    // =========================================================================
    // Enemy Buttons
    // =========================================================================

    private fun buildEnemyButtons() {
        buttonsContainer.removeAllViews()

        for (i in 1..TOTAL_ENEMIES) {
            val isElim = eliminatedSlots[i] == true
            val inCycle = cycleSlots.contains(i)
            val isPredicted = isCycleLocked && !isElim &&
                    cycleSlots.isNotEmpty() && cycleSlots[cycleIndex] == i
            val isDefaultName = (enemyNames[i] ?: "").startsWith("Musuh ")

            val bgColor = when {
                isElim      -> Color.parseColor("#1A1A1A")
                isPredicted -> Color.parseColor("#886600")
                inCycle     -> Color.parseColor("#224422")
                else        -> Color.parseColor("#333355")
            }
            val label = when {
                isElim      -> "ELIM ${displayName(i)}"
                isPredicted -> "* ${displayName(i)}"
                inCycle     -> "OK ${displayName(i)}"
                else        -> "${displayName(i)}"
            }

            val btn = Button(this).apply {
                text = if (isDefaultName && isOcrEnabled) "$label (!)" else label
                textSize = 11f
                setPadding(dp(8), 0, dp(8), 0)
                isEnabled = true
                alpha = if (isElim) 0.4f else 1.0f
                setBackgroundColor(bgColor)
                setTextColor(if (isElim) Color.GRAY else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(36)
                ).also { it.setMargins(0, dp(3), 0, dp(3)) }

                setOnClickListener { handleEnemyTap(i) }
                setOnLongClickListener { showEditPopup(i); true }
            }
            buttonsContainer.addView(btn)
        }

        if (isOcrEnabled) {
            val unnamedCount = (1..TOTAL_ENEMIES).count {
                eliminatedSlots[it] != true && (enemyNames[it] ?: "").startsWith("Musuh ")
            }
            if (unnamedCount > 0) {
                buttonsContainer.addView(TextView(this).apply {
                    text = "$unnamedCount musuh belum diberi nama\n(Tahan tombol untuk edit nama)"
                    setTextColor(Color.parseColor("#FFAA44"))
                    textSize = 9f
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
            }
        }
    }

    // =========================================================================
    // Edit Popup
    // =========================================================================

    private fun showEditPopup(slot: Int) {
        closePopup()

        val isElim = eliminatedSlots[slot] == true
        val name = displayName(slot)
        val (screenW, _) = getScreenDimensions()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val popupWidthPx = minOf((screenW * 0.80).toInt(), dp(400))

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF1A1A2E"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = 20f
        }

        container.addView(TextView(this).apply {
            text = "Edit Musuh $slot"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(12)) }
        })

        container.addView(TextView(this).apply {
            text = "Nama musuh:"
            setTextColor(Color.parseColor("#BBFFFFFF"))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(4)) }
        })

        val editName = EditText(this).apply {
            setText(name)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Nama musuh..."
            textSize = 13f
            setBackgroundColor(Color.parseColor("#FF2A2A4A"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).also { it.setMargins(0, 0, 0, dp(14)) }
            selectAll()
        }
        container.addView(editName)

        if (isOcrEnabled) {
            container.addView(TextView(this).apply {
                text = "Nama ini dipakai OCR untuk auto-detect"
                setTextColor(Color.parseColor("#88AACCFF"))
                textSize = 9f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, -dp(10), 0, dp(10)) }
            })
        }

        container.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.setMargins(0, 0, 0, dp(12)) }
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun makeBtn(label: String, bg: String, action: () -> Unit) = Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(bg))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
                .also { it.setMargins(dp(3), 0, dp(3), 0) }
            setPadding(0, 0, 0, 0)
            setOnClickListener { action() }
        }

        val btnSave = makeBtn("Simpan", "#CC1A5C1A") {
            val newName = editName.text.toString().trim()
            if (newName.isNotEmpty()) {
                enemyNames[slot] = newName
                slotCooldown.remove(slot)
                previouslyVisibleSlots.remove(slot)
                Toast.makeText(this, "Nama disimpan: $newName", Toast.LENGTH_SHORT).show()
            }
            closePopup()
            buildEnemyButtons()
            updateDisplay()
        }

        val btnElim = makeBtn(
            if (isElim) "Aktifkan" else "Eliminasi",
            if (isElim) "#CC226622" else "#CC881111"
        ) {
            closePopup()
            toggleElimination(slot)
        }

        val btnCancel = makeBtn("Batal", "#CC444444") {
            closePopup()
        }

        btnRow.addView(btnSave)
        btnRow.addView(btnElim)
        btnRow.addView(btnCancel)
        container.addView(btnRow)

        val sv = ScrollView(this).apply {
            isFillViewport = true
            addView(container)
        }

        val pp = WindowManager.LayoutParams(
            popupWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0; y = 0
        }

        popupParams = pp

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sv)
        }
        popupView = wrapper
        windowManager.addView(wrapper, pp)
    }

    private fun closePopup() {
        popupView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { }
            popupView = null
            popupParams = null
        }
    }

    // =========================================================================
    // Game Logic
    // =========================================================================

    private fun handleEnemyTap(slot: Int) {
        if (eliminatedSlots[slot] == true) {
            Toast.makeText(this, "${displayName(slot)} dieliminasi. Tahan untuk opsi.", Toast.LENGTH_SHORT).show()
            return
        }

        val active = activeSlots()

        if (!isCycleLocked) {
            if (cycleSlots.contains(slot)) {
                Toast.makeText(this, "${displayName(slot)} sudah tercatat!", Toast.LENGTH_SHORT).show()
                return
            }
            cycleSlots.add(slot)
            currentRound++

            if (cycleSlots.size == active.size) {
                isCycleLocked = true
                cycleIndex = 0
                val next = cycleSlots[cycleIndex]
                titleText.text = "Ronde: $currentRound TERKUNCI\nPrediksi:\n* ${displayName(next)} *"
            } else {
                val rem = active.size - cycleSlots.size
                titleText.text = "Ronde: $currentRound\nTercatat: ${cycleSlots.size}/${active.size}\nSisa $rem lagi..."
            }
            buildEnemyButtons()
        } else {
            val expectedSlot = cycleSlots[cycleIndex]
            val warn = if (slot != expectedSlot) "\nTidak sesuai prediksi!" else ""
            currentRound++

            var nextIndex = (cycleIndex + 1) % cycleSlots.size
            var safety = 0
            while (eliminatedSlots[cycleSlots[nextIndex]] == true && safety < cycleSlots.size) {
                nextIndex = (nextIndex + 1) % cycleSlots.size
                safety++
            }
            cycleIndex = nextIndex
            val next = cycleSlots[cycleIndex]
            titleText.text = "Ronde: $currentRound$warn\nPrediksi selanjutnya:\n* ${displayName(next)} *"
            buildEnemyButtons()
        }
    }

    private fun toggleElimination(slot: Int) {
        val wasElim = eliminatedSlots[slot] == true
        eliminatedSlots[slot] = !wasElim

        if (!wasElim) {
            previouslyVisibleSlots.remove(slot)
            slotCooldown.remove(slot)

            if (isCycleLocked) {
                val curSlot = cycleSlots.getOrNull(cycleIndex)
                if (curSlot == slot) {
                    var nextIdx = (cycleIndex + 1) % cycleSlots.size
                    var safety = 0
                    while (eliminatedSlots[cycleSlots[nextIdx]] == true && safety < cycleSlots.size) {
                        nextIdx = (nextIdx + 1) % cycleSlots.size
                        safety++
                    }
                    cycleIndex = nextIdx
                }
                val activeInCycle = cycleSlots.count { eliminatedSlots[it] == false }
                if (activeInCycle == 0) {
                    isCycleLocked = false
                    cycleSlots.clear()
                    cycleIndex = 0
                    titleText.text = "Ronde: $currentRound\nSemua musuh dieliminasi!"
                } else {
                    updateDisplay()
                }
            }
            Toast.makeText(this, "${displayName(slot)} dieliminasi", Toast.LENGTH_SHORT).show()
        } else {
            val active = activeSlots()
            val inCycle = cycleSlots.filter { eliminatedSlots[it] == false }
            if (isCycleLocked || (inCycle.size == active.size && cycleSlots.isNotEmpty())) {
                isCycleLocked = true
            }
            Toast.makeText(this, "${displayName(slot)} aktif kembali!", Toast.LENGTH_SHORT).show()
            updateDisplay()
        }
        buildEnemyButtons()
    }

    private fun updateDisplay() {
        val inCycle = cycleSlots.filter { eliminatedSlots[it] == false }
        val active = activeSlots()
        if (isCycleLocked && cycleSlots.isNotEmpty()) {
            val next = cycleSlots[cycleIndex]
            titleText.text = "Ronde: $currentRound\nPrediksi:\n* ${displayName(next)} *"
        } else {
            titleText.text = "Ronde: $currentRound\nTercatat: ${inCycle.size}/${active.size}"
        }
    }

    private fun resetAll() {
        cycleSlots.clear()
        currentRound = 1
        cycleIndex = 0
        isCycleLocked = false
        previouslyVisibleSlots.clear()
        slotCooldown.clear()
        for (i in 1..TOTAL_ENEMIES) {
            eliminatedSlots[i] = false
            enemyNames[i] = "Musuh $i"
        }
        buildEnemyButtons()
        titleText.text = "Ronde: 1\nTap musuh saat muncul"
        Toast.makeText(this, "Semua di-reset!", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // Drag & Toggle Expand
    // =========================================================================

    private fun setupMovementAndClickLogic() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        collapsedView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = wmParams.x
                    initialY = wmParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) isDragging = true
                    if (isDragging) {
                        wmParams.x = initialX + dx
                        wmParams.y = initialY + dy
                        windowManager.updateViewLayout(floatingView, wmParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (expandedView.visibility == View.VISIBLE) {
                            expandedView.visibility = View.GONE
                            wmParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        } else {
                            recalcScrollHeight()
                            expandedView.visibility = View.VISIBLE
                            wmParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        }
                        windowManager.updateViewLayout(floatingView, wmParams)
                    }
                    true
                }
                else -> false
            }
        }
    }
}