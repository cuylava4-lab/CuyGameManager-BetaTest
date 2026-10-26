package app.sensicoach

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pantalla única, sin librerías: modo juego, calibrador de sensibilidad, arena de práctica, HUD y ajuste de pantalla.
 * Todo ocurre dentro de la app o en los ajustes de tu teléfono; no lee, no toca ni simula toques en ningún juego.
 */
class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var info: TextView
    private lateinit var scrOut: TextView
    private lateinit var calcOut: TextView
    private lateinit var blockOut: TextView
    private lateinit var progOut: TextView
    private lateinit var etDpi: EditText
    private lateinit var etW: EditText
    private lateinit var etH: EditText
    private lateinit var weaponGroup: RadioGroup
    private lateinit var arena: TrainingView
    private val prefs by lazy { getSharedPreferences("sc", MODE_PRIVATE) }
    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 3000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        SensiState.load(prefs)
        val dm = resources.displayMetrics
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        fun tv(t: String, sp: Float = 15f) = TextView(this).apply { text = t; textSize = sp; setPadding(0, 12, 0, 12) }.also { col.addView(it) }
        fun mk(t: String, f: () -> Unit) = Button(this).apply { text = t; setAllCaps(false); setOnClickListener { f() } }
        fun btn(t: String, f: () -> Unit) = mk(t, f).also { col.addView(it) }
        fun row(vararg v: View) = LinearLayout(this).apply {
            v.forEach { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); addView(it) }
        }.also { col.addView(it) }
        fun field(label: String, v: Int) = EditText(this).apply {
            hint = label; setText(v.toString()); inputType = InputType.TYPE_CLASS_NUMBER
        }

        tv("Sensi Coach", 26f)
        tv("Modo juego, calibrador y arena de práctica. No lee, no toca ni simula toques en ningún juego.")
        info = tv("")
        btn("Activar modo juego") { activate() }
        btn("Rutina 10 min: Free Fire") { routine("ff") }
        btn("Rutina 10 min: CODM") { routine("cod") }
        btn("Desactivar y restaurar") { deactivate() }

        tv("Calibración de sensibilidad", 18f)
        tv("DPI, ancho y alto de tu pantalla (px). Se rellenan solos; cámbialos si juegas con otra resolución.")
        val (rw, rh) = realSize()
        etDpi = field("DPI", prefs.getInt("dpi", dm.densityDpi))
        etW = field("Ancho", prefs.getInt("rw", rw))
        etH = field("Alto", prefs.getInt("rh", rh))
        row(etDpi, etW, etH)
        weaponGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        SensiState.WEAPONS.forEachIndexed { i, n -> weaponGroup.addView(RadioButton(this).apply { text = n; id = 100 + i }) }
        weaponGroup.check(100 + SensiState.weapon)
        weaponGroup.setOnCheckedChangeListener { _, id -> SensiState.weapon = id - 100; SensiState.save(prefs); calculate() }
        col.addView(weaponGroup)
        btn("Calcular sensibilidad") { calculate() }
        calcOut = tv("")

        tv("Arena de práctica", 18f)
        tv("Flick: arrastra y suelta para disparar a la cabeza. Si un bloque de 15 no llega al 90 %, revisa si tus fallos son sistemáticos, reajusta el multiplicador de tu arma, lo verifica en el bloque siguiente y lo deshace si no mejoró; con dos bloques estables lo marca ✔. Umbral 90 %: la cabeza se encoge con cada acierto y crece mucho con cada fallo, hasta hallar el tamaño con el que aciertas el 90 %. Seguimiento: mantén el dedo 20 s y sigue la cabeza en movimiento.")
        tv("El 90 % es la meta dentro de la arena: mide y entrena tu puntería, no apunta por ti.")
        arena = TrainingView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (300 * dm.density).toInt())
        }
        arena.onBlock = { msg -> SensiState.save(prefs); blockOut.text = msg + "\n" + SensiState.suggestion(); progress() }
        col.addView(arena)
        row(mk("Flick") { arena.mode = TrainingView.FLICK }, mk("Umbral 90 %") { arena.mode = TrainingView.THRESH }, mk("Seguimiento") { arena.mode = TrainingView.TRACK })
        row(mk("Cerca") { arena.distance = 0 }, mk("Media") { arena.distance = 1 }, mk("Larga") { arena.distance = 2 })
        blockOut = tv("")
        progOut = tv("")

        tv("HUD flotante", 18f)
        tv("Mini panel sobre el juego para cambiar de arma y ver los valores a escribir. Solo muestra datos: no lee ni toca el juego.")
        btn("Mostrar / ocultar HUD") { toggleHud() }

        tv("Pantalla (avanzado)", 18f)
        scrOut = tv("")
        tv("Bajar resolución y densidad a la vez alivia a la GPU. Android solo lo permite con root o ADB; si no confirmas en 15 s, se revierte solo.")
        row(mk("90 %") { scaleScreen(90) }, mk("80 %") { scaleScreen(80) }, mk("75 %") { scaleScreen(75) })
        btn("Restaurar pantalla") { restoreScreen() }

        tv("Abrir juego", 18f)
        btn("Free Fire") { open("com.dts.freefireth") }
        btn("Free Fire MAX") { open("com.dts.freefiremax") }
        btn("Call of Duty Mobile") { open("com.activision.callofduty.shooter") }
        setContentView(ScrollView(this).apply { addView(col) })
        calculate()
        progress()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        weaponGroup.check(100 + SensiState.weapon)   // el HUD también puede cambiar el arma
        tick.run()
    }

    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun refresh() {
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        info.text = "Temperatura: %.1f °C\nRAM libre: %d MB".format(t, mi.availMem / 1048576)
        scrOut.text = screenInfo()
    }

    // ---------------------------------------------------------------- Calibración

    /**
     * PPI efectivo = DPI × (diagonal de la resolución elegida / diagonal nativa). Si juegas a menor resolución
     * cada píxel es más grande y el mismo recorrido del dedo mueve menos píxeles. Con el PPI, el arma y el
     * multiplicador afinado en la arena, SensiState calcula los valores de partida para los deslizadores del juego.
     */
    private fun calculate() {
        val dpi = etDpi.text.toString().toIntOrNull()
        val w = etW.text.toString().toIntOrNull()
        val hh = etH.text.toString().toIntOrNull()
        if (dpi == null || w == null || hh == null || dpi !in 120..800 || w < 300 || hh < 300) {
            toast("Revisa el DPI (120–800) y la resolución"); return
        }
        val (nw, nh) = nativeSize()
        val diag = hypot(nw.toFloat(), nh.toFloat())
        val ppi = dpi * hypot(w.toFloat(), hh.toFloat()) / diag
        if (abs(ppi - SensiState.ppi) > 0.01f * SensiState.ppi) SensiState.clearLocks()   // otro PPI: lo validado ya no vale
        SensiState.ppi = ppi
        SensiState.save(prefs)
        prefs.edit().putInt("dpi", dpi).putInt("rw", w).putInt("rh", hh).apply()
        SensiState.changed()
        val capped = SensiState.LABELS.indices.any { SensiState.slider(it) >= 100 }
        val base = ("PPI efectivo: %.0f · diagonal ≈ %.1f″\n" +
            "Valores de partida para ${SensiState.WEAPONS[SensiState.weapon]} (Free Fire):\n${SensiState.suggestion()}\n" +
            "Escríbelos tú en el juego y afina con la arena (✔ = ya validado en ella).").format(ppi, diag / dpi)
        calcOut.text = base + if (capped) "\nOjo: algún valor está en el tope de 100 del juego; ahí no hay más margen." else ""
    }

    /** Tendencia = promedio móvil de tus sesiones (una sesión suelta es ruidosa; la mejor sesión te engañaría). */
    private fun progress() {
        val thr = if (SensiState.thrMm > 0f) "${"%.1f".format(SensiState.thrMm)} mm" else "sin datos"
        val trk = if (SensiState.trackAvg >= 0f) "${SensiState.trackAvg.roundToInt()} %" else "sin datos"
        progOut.text = "Tendencia (promedio de tus últimas sesiones)\nUmbral 90 %: $thr · en cabeza (Seguimiento): $trk"
    }

    // ---------------------------------------------------------------- Modo juego (sin cambios de comportamiento)

    private fun activate() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            toast("Concede acceso a No molestar y vuelve a pulsar")
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return
        }
        if (!Settings.System.canWrite(this)) {
            toast("Permite modificar ajustes del sistema y vuelve a pulsar")
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))); return
        }
        val r = contentResolver
        if (!prefs.contains("f")) prefs.edit()
            .putInt("f", nm.currentInterruptionFilter)
            .putInt("m", Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, 1))
            .putInt("b", Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 128)).apply()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, 180)
        startForegroundService(Intent(this, GameService::class.java))
        toast("Modo juego activo")
    }

    private fun routine(g: String) {
        startForegroundService(Intent(this, GameService::class.java).putExtra(GameService.EXTRA_GAME, g))
        toast("Rutina iniciada: mira la notificación")
    }

    private fun deactivate() {
        stopService(Intent(this, GameService::class.java))
        if (prefs.contains("f")) {
            val nm = getSystemService(NotificationManager::class.java)
            val f = prefs.getInt("f", 1)
            if (nm.isNotificationPolicyAccessGranted && f in 1..4) nm.setInterruptionFilter(f)
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, prefs.getInt("m", 1))
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, prefs.getInt("b", 128))
            }
            // Solo las claves del modo juego: el resto (calibración, multiplicadores) se conserva.
            prefs.edit().remove("f").remove("m").remove("b").apply()
        }
        toast("Ajustes restaurados")
    }

    private fun open(p: String) {
        val i = packageManager.getLaunchIntentForPackage(p)
        if (i == null) toast("No está instalado") else startActivity(i)
    }

    private fun toggleHud() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Permite «Mostrar sobre otras apps» y vuelve a pulsar")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return
        }
        startForegroundService(Intent(this, GameService::class.java).putExtra(GameService.EXTRA_HUD, !GameService.hudVisible))
    }

    // ---------------------------------------------------------------- Resolución y densidad (root o ADB, con reversión)

    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(m)
        return min(m.widthPixels, m.heightPixels) to max(m.widthPixels, m.heightPixels)
    }

    @Suppress("DEPRECATION")
    private fun nativeSize(): Pair<Int, Int> {
        val mode = windowManager.defaultDisplay.mode
        return min(mode.physicalWidth, mode.physicalHeight) to max(mode.physicalWidth, mode.physicalHeight)
    }

    private fun screenInfo(): String {
        val (rw, rh) = realSize()
        val (nw, nh) = nativeSize()
        val dpi = resources.displayMetrics.densityDpi
        return "Pantalla: $rw×$rh a $dpi dpi" + (if (rw != nw || rh != nh) " (nativa $nw×$nh)" else "")
    }

    /** Guarda los valores originales la primera vez y aplica pct % de resolución con la densidad proporcional. */
    private fun scaleScreen(pct: Int) {
        val (nw, nh) = nativeSize()
        if (!prefs.contains("ow")) {
            val (rw, rh) = realSize()
            prefs.edit().putInt("ow", rw).putInt("oh", rh).putInt("od", resources.displayMetrics.densityDpi).apply()
        }
        val w = (nw * pct / 100) and 1.inv()
        val hh = (nh * pct / 100) and 1.inv()
        val dpi = (prefs.getInt("od", 420) * pct / 100).coerceIn(120, 640)
        val back = restoreCmd()
        runShell("wm size ${w}x$hh && wm density $dpi") { ok, out ->
            if (ok) confirmOrRevert { runShell(back) { _, _ -> restoreDone() } }
            else adbDialog("adb shell wm size ${w}x$hh\nadb shell wm density $dpi", out)
        }
    }

    private fun restoreCmd(): String {
        val rw = prefs.getInt("ow", 0)
        val rh = prefs.getInt("oh", 0)
        val od = prefs.getInt("od", 0)
        return if (rw > 0 && rh > 0 && od > 0) "wm size ${rw}x$rh && wm density $od" else "wm size reset && wm density reset"
    }

    private fun restoreScreen() {
        runShell(restoreCmd()) { ok, out ->
            if (ok) restoreDone() else adbDialog("adb shell wm size reset\nadb shell wm density reset", out)
        }
    }

    private fun restoreDone() {
        prefs.edit().remove("ow").remove("oh").remove("od").apply()
        scrOut.text = screenInfo()
        toast("Pantalla restaurada")
    }

    /** Ejecuta con root (su) en un hilo aparte; si no hay root, devuelve ok = false y el motivo. */
    private fun runShell(cmd: String, done: (Boolean, String) -> Unit) {
        Thread {
            var ok = false
            var out = ""
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                out = (p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()).trim()
                ok = p.waitFor() == 0
            } catch (e: Exception) { out = e.message ?: "" }
            runOnUiThread { done(ok, out) }
        }.start()
    }

    /** Cuenta atrás de 15 s: si no pulsas «Conservar», se ejecuta la reversión aunque el diálogo ya no esté. */
    private fun confirmOrRevert(onRevert: () -> Unit) {
        var left = 15
        var kept = false
        val dlg = AlertDialog.Builder(this)
            .setTitle("¿Conservar el ajuste de pantalla?")
            .setMessage("Se revierte solo en 15 s")
            .setCancelable(false)
            .setPositiveButton("Conservar") { _, _ -> kept = true }
            .create()
        val countdown = object : Runnable {
            override fun run() {
                if (kept) return
                left--
                if (left <= 0) {
                    try { dlg.dismiss() } catch (e: Exception) {}
                    onRevert()
                } else {
                    dlg.setMessage("Se revierte solo en $left s")
                    h.postDelayed(this, 1000)
                }
            }
        }
        dlg.show()
        h.postDelayed(countdown, 1000)
    }

    private fun adbDialog(cmds: String, why: String) {
        val reason = why.take(80).ifBlank { "sin root" }
        val full = cmds + "\n\nPara volver:\nadb shell wm size reset\nadb shell wm density reset"
        AlertDialog.Builder(this)
            .setTitle("Necesitas root o ADB")
            .setMessage("No pude aplicarlo desde la app ($reason). Con el teléfono por USB ejecuta en tu PC:\n\n$full")
            .setPositiveButton("Copiar") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", full))
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/**
 * Arena de práctica con tres modos. Aquí SÍ interceptamos MotionEvent, porque la vista es de esta app.
 *
 * Por cada gesto se mide la velocidad (mm/s, con el PPI), la aceleración, el ángulo de elevación, la frecuencia de
 * muestreo táctil observada y la latencia toque→inicio de frame (cota inferior: no incluye el escaneo de la pantalla).
 * La ganancia de la mira crece con la velocidad (aceleración lineal por arma) y el multiplicador se guarda por arma y distancia.
 * Los tamaños están en milímetros reales, así que el entrenamiento es comparable entre teléfonos.
 *
 *  - FLICK: arrastra y suelta para disparar. Cada 15 tiros GainTuner comprueba si tu multiplicador tiene un sesgo real,
 *    lo reajusta, lo verifica en el bloque siguiente y lo deshace si no mejoró; con dos bloques ≥ 90 % lo da por calibrado.
 *  - THRESH (umbral 90 %): método escalera ponderada. Cada acierto encoge la cabeza ~3 % y cada fallo la agranda ~31 %
 *    (9 pasos), así que el tamaño converge al que aciertas el 90 % de las veces. Simulado: se estabiliza en ~90 %.
 *  - TRACK: mantén el dedo y sigue una cabeza que se mueve lateralmente. Mide tiempo en cabeza, error de altura y retraso.
 */
class TrainingView(ctx: Context) : View(ctx) {
    companion object {
        const val FLICK = 0
        const val THRESH = 1
        const val TRACK = 2
        private const val BLOCK = 15                                 // tiros por bloque de ajuste (Flick)
        private const val SESSION = 40                               // tiros por sesión (Umbral 90 %)
        private const val TRACK_SECS = 20f                           // segundos con el dedo apoyado por sesión (Seguimiento)
        private const val BASE_K = 2.0f                              // px de mira por px arrastrado con multiplicador 1.0
        private const val V_REF = 250f                               // mm/s a los que la aceleración llega a su máximo
        private const val STEP_UP = 0.03f                            // acierto: cabeza ~3 % más pequeña (en logaritmo)
        private const val STEP_DOWN = 0.27f                          // fallo: cabeza ~31 % más grande (9 pasos): converge al 90 %
        private val LN_MIN = ln(0.8f)                                // radio mínimo 0,8 mm
        private val LN_MAX = ln(12f)                                 // radio máximo 12 mm
        private val ACCEL = floatArrayOf(0.45f, 0.35f, 0.15f)        // SMG, AR, Sniper
        private val RADIUS_MM = floatArrayOf(4.5f, 3.0f, 1.8f)       // radio de la cabeza: cerca, media, larga
    }

    var onBlock: ((String) -> Unit)? = null
    var mode = FLICK
        set(v) { field = v.coerceIn(0, 2); restart() }
    var distance = 1
        set(v) { field = v.coerceIn(0, 2); restart() }

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = java.util.Random()
    private var cx = 0f
    private var cy = 0f
    private var tx = 0f
    private var ty = 0f
    private var baseY = 0f
    private var down = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var sumDx = 0f
    private var sumDy = 0f
    private var vEma = 0f
    private var peakV = 0f
    private var peakA = 0f
    private var shots = 0
    private var hits = 0
    private var sx0 = 0f                                         // mira al empezar el gesto
    private var sy0 = 0f
    private var ix = 0f                                          // recorrido pedido: de la mira a la cabeza al empezar el gesto
    private var iy = 0f
    private val lr = FloatArray(BLOCK)                           // ln(pedido / logrado) de cada gesto válido del bloque
    private var lrN = 0
    private val tuners = Array(9) { GainTuner() }                // uno por arma x distancia
    private var blockWeapon = SensiState.weapon
    private var trkPending = false                               // Seguimiento: hay un ajuste pendiente de verificar
    private var trkPrevGain = 1f
    private var trkPrevLag = 0f
    private var thrLog = ln(3.0f)
    private val recent = FloatArray(20)
    private var ri = 0
    private var hv = 0f
    private var hNext = 1f
    private var phase = 0f
    private var lastFrame = 0L
    private var statusAcc = 0f
    private var trkTime = 0f
    private var trkOn = 0f
    private var exAbs = 0f
    private var eyAbs = 0f
    private var lag = 0f
    private var lastV = 0f
    private var lastA = 0f
    private var lastAng = 0f
    private var hz = 0f
    private var winStart = 0L
    private var winN = 0
    private var evTime = 0L
    private var queued = false
    private var latSum = 0f
    private var latN = 0
    private var lastRep = 0L
    private var note = "Arrastra y suelta para disparar"
    private var l1 = ""
    private var l2 = ""
    private var l3 = ""

    private fun mm2px(mm: Float) = mm * SensiState.ppi / 25.4f
    private val radiusMm get() = if (mode == THRESH) exp(thrLog) else RADIUS_MM[distance]
    private val radius get() = mm2px(radiusMm)
    /** Qué multiplicador usa ahora: en Umbral, el de la distancia cuyo tamaño de cabeza se parece más al actual. */
    private val bucket get() = if (mode == THRESH) { if (radiusMm >= 3.75f) 0 else if (radiusMm >= 2.4f) 1 else 2 } else distance
    private val tuner get() = tuners[SensiState.weapon * 3 + distance]

    private val frameCb = Choreographer.FrameCallback { ns ->
        queued = false
        val now = ns / 1_000_000L
        val l = now - evTime
        if (l in 0L..250L) { latSum += l; latN++ }
        if (latN > 0 && now - lastRep > 500L) {
            SensiState.latencyMs = latSum / latN
            latSum = 0f; latN = 0; lastRep = now
            status()
            SensiState.changed()
            invalidate()
        }
    }

    init { restart() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        cx = w / 2f; cy = h / 2f
        newTarget()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCb)
        queued = false
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(e)                       // API estándar: eventos sin agrupar por frame
                parent?.requestDisallowInterceptTouchEvent(true)   // que el ScrollView no robe el arrastre
                down = true
                sx0 = cx; sy0 = cy; ix = tx - cx; iy = ty - cy
                lastX = e.x; lastY = e.y; lastT = e.eventTime
                sumDx = 0f; sumDy = 0f; vEma = 0f; peakV = 0f; peakA = 0f
                winStart = 0L; winN = 0
            }
            MotionEvent.ACTION_MOVE -> if (down) {
                val first = if (e.historySize > 0) e.getHistoricalEventTime(0) else e.eventTime
                for (i in 0 until e.historySize) feed(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i))
                feed(e.x, e.y, e.eventTime)
                sampleRate(e.historySize + 1, e.eventTime)
                if (!queued) {
                    queued = true
                    evTime = first
                    Choreographer.getInstance().postFrameCallback(frameCb)
                }
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_UP -> if (down) { down = false; if (mode != TRACK) fire() }
            MotionEvent.ACTION_CANCEL -> down = false
        }
        return true
    }

    /** Una muestra táctil: mide velocidad/aceleración y mueve la mira con la ganancia dependiente de la velocidad. */
    private fun feed(x: Float, y: Float, t: Long) {
        val dt = max(1L, t - lastT)
        val dx = x - lastX
        val dy = y - lastY
        val v = hypot(dx, dy) / dt * 25400f / SensiState.ppi          // px/ms -> mm/s
        val a = abs(v - vEma) * 1000f / dt                            // mm/s²
        vEma += 0.5f * (v - vEma)
        peakV = max(peakV, vEma)
        peakA = max(peakA, a)
        val f = SensiState.gainOf(bucket) * BASE_K * (1f + ACCEL[SensiState.weapon] * min(vEma / V_REF, 1f))
        cx = max(0f, min(width.toFloat(), cx + dx * f))
        cy = max(0f, min(height.toFloat(), cy + dy * f))
        sumDx += dx; sumDy += dy
        lastX = x; lastY = y; lastT = t
    }

    private fun sampleRate(n: Int, t: Long) {
        if (winStart == 0L) winStart = t
        winN += n
        if (t - winStart >= 500L) {
            hz = winN * 1000f / (t - winStart)
            winStart = 0L; winN = 0
        }
    }

    /** Disparo al soltar el dedo (Flick y Umbral). */
    private fun fire() {
        if (blockWeapon != SensiState.weapon) {              // cambiaste de arma a mitad de bloque: empieza otro
            blockWeapon = SensiState.weapon
            shots = 0; hits = 0; lrN = 0
        }
        val hit = hypot(tx - cx, ty - cy) <= radius
        // Recorrido logrado hacia la cabeza (proyección) frente al pedido: da el sesgo de tu multiplicador.
        val dLen = hypot(ix, iy)
        if (mode == FLICK && dLen > 4 * radius && lrN < lr.size) {
            val got = ((cx - sx0) * ix + (cy - sy0) * iy) / dLen
            if (got > 0.25f * dLen) lr[lrN++] = ln(dLen / got)
        }
        lastV = peakV
        lastA = peakA / 1000f
        lastAng = Math.toDegrees(atan2((-sumDy).toDouble(), abs(sumDx).toDouble())).toFloat()
        shots++
        if (hit) hits++
        if (mode == THRESH) {
            recent[ri % recent.size] = thrLog                // el radio con el que se hizo ESTE tiro
            ri++
            thrLog = max(LN_MIN, min(LN_MAX, thrLog + (if (hit) -STEP_UP else STEP_DOWN)))
            if (shots >= SESSION) endThresh()
        } else if (shots >= BLOCK) {
            endBlock()
        }
        newTarget()
        status()
        invalidate()
    }

    /** Fin de bloque (Flick): comprobar, reajustar, verificar y fijar los hace GainTuner. */
    private fun endBlock() {
        val pct = (100 * hits / shots.toFloat()).roundToInt()
        val d = distance
        val out = tuner.update(SensiState.gainOf(d), lr, lrN, hits, shots)
        SensiState.setGain(d, out.gain)
        SensiState.setLocked(d, out.locked)
        note = out.note
        SensiState.lastRate = pct
        val msg = "Flick ${SensiState.DIST[d]}: $pct % de aciertos. ${out.note}"
        shots = 0; hits = 0; lrN = 0
        SensiState.changed()
        onBlock?.invoke(msg)
    }

    private fun endThresh() {
        val n = min(ri, recent.size)
        var s = 0f
        for (i in 0 until n) s += recent[i]
        val est = exp(s / n)                                 // media geométrica de los últimos 20 radios
        SensiState.thrMm = SensiState.ewma(SensiState.thrMm, est)
        val pct = 100 * hits / shots
        SensiState.lastRate = pct
        val ratio = RADIUS_MM[1] / est
        note = "Umbral 90 %: ${"%.1f".format(est)} mm"
        val msg = "Umbral 90 %: radio ${"%.1f".format(est)} mm (≈ ${"%.1f".format(ratio)}× la distancia Media), $pct % de aciertos. " +
            "Entre sesiones, ±25 % es ruido: fíjate en la tendencia."
        shots = 0; hits = 0
        status()
        SensiState.changed()
        onBlock?.invoke(msg)
    }

    private fun endTrack() {
        down = false                                         // levanta el dedo y vuelve a tocar para otra sesión
        val k = 25.4f / SensiState.ppi / trkTime             // px·s -> mm (media temporal)
        val pct = (100 * trkOn / trkTime).roundToInt()
        val eyMm = eyAbs * k
        val exMm = exAbs * k
        val lagMm = lag * k
        val d = distance
        val g = SensiState.gainOf(d)
        val tip: String
        if (trkPending && abs(lagMm) > trkPrevLag * 0.9f && abs(lagMm) > 0.7f) {   // VERIFICAR: ¿mejoró el ajuste anterior?
            trkPending = false
            SensiState.setGain(d, trkPrevGain)
            tip = "No mejoró: deshago el ajuste"
        } else {
            trkPending = false
            tip = when {
                pct >= 90 -> "¡Meta ≥90 %!"
                lagMm > 0.7f -> { trkPrevGain = g; trkPrevLag = abs(lagMm); trkPending = true; SensiState.setGain(d, g * 1.04f); "Vas por detrás: +4 %, lo verifico" }
                lagMm < -0.7f -> { trkPrevGain = g; trkPrevLag = abs(lagMm); trkPending = true; SensiState.setGain(d, g * 0.96f); "Te adelantas: −4 %, lo verifico" }
                eyMm > 0.8f * exMm -> "Se te va la altura: mantén la mira en la cabeza"
                else -> "Movimientos más suaves y continuos"
            }
        }
        SensiState.trackAvg = SensiState.ewma(SensiState.trackAvg, pct.toFloat())
        SensiState.lastRate = pct
        note = tip
        val msg = "Seguimiento: $pct % en cabeza · altura ±${"%.1f".format(eyMm)} mm · retraso ${"%.1f".format(lagMm)} mm. $tip"
        trkTime = 0f; trkOn = 0f; exAbs = 0f; eyAbs = 0f; lag = 0f
        status()
        SensiState.changed()
        onBlock?.invoke(msg)
        invalidate()
    }

    /** Avanza la cabeza en modo Seguimiento (una vez por frame) y acumula las métricas mientras el dedo está apoyado. */
    private fun step(dt: Float) {
        val lo = 2 * radius
        val hi = max(lo, width - 2 * radius)
        tx += hv * dt
        if (tx < lo) { tx = lo; hv = abs(hv) } else if (tx > hi) { tx = hi; hv = -abs(hv) }
        hNext -= dt
        if (hNext <= 0f) flip()
        phase += dt * 6.2832f
        ty = baseY + sin(phase) * mm2px(1.2f)                // pequeño rebote vertical, como un enemigo que corre
        if (down) {
            val ex = tx - cx
            val ey = ty - cy
            trkTime += dt
            if (hypot(ex, ey) <= radius) trkOn += dt
            exAbs += abs(ex) * dt
            eyAbs += abs(ey) * dt
            lag += ex * (if (hv >= 0f) 1f else -1f) * dt     // > 0: la mira va por detrás de la cabeza
            if (trkTime >= TRACK_SECS) endTrack()
        }
        statusAcc += dt
        if (statusAcc > 0.2f) { statusAcc = 0f; status() }
    }

    private fun flip() {
        val mag = mm2px(18f + 24f * rnd.nextFloat())
        hv = if (hv > 0f) -mag else mag
        hNext = 0.5f + 0.9f * rnd.nextFloat()
    }

    private fun restart() {
        shots = 0; hits = 0; lrN = 0; ri = 0; trkPending = false; blockWeapon = SensiState.weapon; tuner.reset()
        trkTime = 0f; trkOn = 0f; exAbs = 0f; eyAbs = 0f; lag = 0f
        thrLog = max(LN_MIN, min(LN_MAX, ln(if (SensiState.thrMm > 0f) SensiState.thrMm * 1.2f else RADIUS_MM[1])))
        hv = mm2px(30f); hNext = 1f; phase = 0f; lastFrame = 0L; statusAcc = 0f
        down = false
        note = when (mode) {
            TRACK -> "Mantén el dedo y sigue la cabeza"
            THRESH -> "Aciertas: más pequeña. Fallas: más grande"
            else -> "Distancia: ${SensiState.DIST[distance]}"
        }
        newTarget()
        status()
        invalidate()
    }

    /** Nueva cabeza a una distancia de la mira que obliga a un gesto real (no a un microajuste). */
    private fun newTarget() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 50f || h < 50f) return
        if (mode == TRACK) {
            baseY = h * 0.4f                                  // línea de altura de cabeza
            tx = w / 2f; ty = baseY
            cx = w / 2f; cy = h * 0.7f
            return
        }
        val r = radius
        val reach = min(w, h) * (0.3f + 0.35f * rnd.nextFloat())
        val loX = 2 * r
        val hiX = max(loX, w - 2 * r)
        val loY = 2 * r + 24 * dp
        val hiY = max(loY, h - 3.6f * r)
        var tries = 0
        do {
            val ang = rnd.nextFloat() * 6.2832f
            tx = cx + cos(ang) * reach
            ty = cy + sin(ang) * reach
            tries++
        } while ((tx < loX || tx > hiX || ty < loY || ty > hiY) && tries < 30)
        tx = max(loX, min(hiX, tx))
        ty = max(loY, min(hiY, ty))
    }

    /** Las líneas de estado se reconstruyen solo cuando cambian: onDraw no crea objetos. */
    private fun status() {
        val lat = SensiState.latencyMs
        val acc = if (shots == 0) 0 else 100 * hits / shots
        l1 = when (mode) {
            TRACK -> {
                val pct = if (trkTime > 0f) (100 * trkOn / trkTime).roundToInt() else 0
                "Seguimiento · ${"%.0f".format(trkTime)}/${TRACK_SECS.roundToInt()} s · en cabeza $pct %"
            }
            THRESH -> "Umbral · radio ${"%.1f".format(radiusMm)} mm · tiro $shots/$SESSION · $acc %"
            else -> "Flick ${SensiState.DIST[distance]} · tiro $shots/$BLOCK · aciertos $acc %"
        }
        l2 = if (mode == TRACK) {
            val k = 25.4f / SensiState.ppi / max(trkTime, 0.001f)
            "altura ±${"%.1f".format(eyAbs * k)} mm · retraso ${"%.1f".format(lag * k)} mm"
        } else {
            "vel ${lastV.roundToInt()} mm/s · acel ${"%.1f".format(lastA)} m/s² · ${lastAng.roundToInt()}°"
        }
        l3 = "${SensiState.WEAPONS[SensiState.weapon]} · ~${hz.roundToInt()} Hz · ${if (lat >= 0f) lat.roundToInt() else 0} ms · ×${"%.2f".format(SensiState.gainOf(bucket))}"
    }

    override fun onDraw(c: Canvas) {
        if (mode == TRACK) {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 0f else min(0.05f, (now - lastFrame) / 1000f)
            lastFrame = now
            step(dt)
            postInvalidateOnAnimation()                       // animación continua solo en este modo
        } else {
            lastFrame = 0L
        }
        c.drawColor(Color.rgb(11, 18, 32))
        val r = radius
        if (mode == TRACK) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp
            paint.color = Color.argb(70, 255, 255, 255)
            c.drawLine(0f, baseY, width.toFloat(), baseY, paint)                                           // altura de cabeza
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(50, 255, 255, 255)
        c.drawRoundRect(tx - 1.6f * r, ty + r, tx + 1.6f * r, ty + 3.4f * r, 0.5f * r, 0.5f * r, paint)   // cuerpo (solo referencia)
        paint.color = Color.rgb(229, 72, 77)
        c.drawCircle(tx, ty, r, paint)                                                                      // cabeza = zona de acierto
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * dp
        paint.color = Color.rgb(61, 214, 140)
        c.drawCircle(cx, cy, 9 * dp, paint)
        c.drawLine(cx - 15 * dp, cy, cx + 15 * dp, cy, paint)
        c.drawLine(cx, cy - 15 * dp, cx, cy + 15 * dp, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 11 * dp
        c.drawText(l1, 8 * dp, 14 * dp, paint)
        c.drawText(l2, 8 * dp, 28 * dp, paint)
        c.drawText(l3, 8 * dp, 42 * dp, paint)
        c.drawText(note, 8 * dp, height - 8 * dp, paint)
    }
}
