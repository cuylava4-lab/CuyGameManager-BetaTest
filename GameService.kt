package app.sensicoach

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Estado compartido (mismo proceso) entre la pantalla, la arena de práctica y el HUD.
 * Son puntos de partida para los deslizadores que TÚ escribes en el juego; nada se envía ni se inyecta en el juego.
 */
object SensiState {
    const val REF_PPI = 420f
    val WEAPONS = arrayOf("SMG", "AR", "Sniper")
    val DIST = arrayOf("Cerca", "Media", "Larga")
    val LABELS = arrayOf("Gen", "Rojo", "2x", "4x", "AWM")

    // Valores de partida orientativos (no oficiales) para los deslizadores de Free Fire a ~420 ppi.
    private val BASE = arrayOf(
        intArrayOf(98, 94, 88, 80, 55),  // SMG
        intArrayOf(90, 88, 80, 72, 50),  // AR
        intArrayOf(82, 80, 72, 64, 42))  // Sniper

    // Distancia de la arena que afina cada deslizador: Gen y Rojo = cerca, 2x = media, 4x y AWM = larga.
    private val DIST_OF = intArrayOf(0, 0, 1, 2, 2)

    @Volatile var weapon = 1
    @Volatile var ppi = REF_PPI
    @Volatile var latencyMs = -1f
    @Volatile var lastRate = -1          // % del último bloque o sesión de la arena
    @Volatile var thrMm = -1f            // umbral 90 %: promedio móvil de tus sesiones (-1 = sin datos)
    @Volatile var trackAvg = -1f         // % en cabeza en Seguimiento: promedio móvil (-1 = sin datos)
    val gain = FloatArray(9) { 1f }      // multiplicador por arma (SMG, AR, Sniper) x distancia (cerca, media, larga)
    private val locked = BooleanArray(9)  // true = ya validado en la arena (≥ 90 % estable); se muestra con ✔
    var listener: (() -> Unit)? = null

    fun gainOf(d: Int) = gain[weapon * 3 + d]
    fun setGain(d: Int, v: Float) { gain[weapon * 3 + d] = v.coerceIn(0.6f, 1.5f) }
    fun lockedOf(d: Int) = locked[weapon * 3 + d]
    fun setLocked(d: Int, v: Boolean) { locked[weapon * 3 + d] = v }
    /** Si cambia tu PPI efectivo, lo validado ya no vale: hay que volver a comprobarlo. */
    fun clearLocks() { for (i in 0..8) locked[i] = false }

    /** Promedio móvil: una sola sesión es ruidosa (±25 %), así que el progreso se sigue con la tendencia, no con la mejor. */
    fun ewma(old: Float, new: Float) = if (old < 0f) new else 0.6f * old + 0.4f * new

    /** Valor sugerido del deslizador i: base del arma × corrección por PPI × multiplicador afinado en la arena. */
    fun slider(i: Int): Int =
        (BASE[weapon][i] * (REF_PPI / ppi).coerceIn(0.75f, 1.35f) * gainOf(DIST_OF[i])).roundToInt().coerceIn(1, 100)

    fun suggestion(): String =
        LABELS.indices.joinToString("  ") { "${LABELS[it]} ${slider(it)}${if (lockedOf(DIST_OF[it])) "✔" else ""}" }

    fun changed() { listener?.invoke() }

    fun load(p: SharedPreferences) {
        weapon = p.getInt("w", 1).coerceIn(0, 2)
        ppi = p.getFloat("ppi", REF_PPI).coerceIn(100f, 900f)
        thrMm = p.getFloat("thr", -1f)
        trackAvg = p.getFloat("trk", -1f)
        for (i in 0..8) gain[i] = p.getFloat("gn$i", 1f).coerceIn(0.6f, 1.5f)
        val m = p.getInt("lk", 0)
        for (i in 0..8) locked[i] = ((m shr i) and 1) == 1
    }

    fun save(p: SharedPreferences) {
        val e = p.edit().putInt("w", weapon).putFloat("ppi", ppi).putFloat("thr", thrMm).putFloat("trk", trackAvg)
        var m = 0
        for (i in 0..8) { e.putFloat("gn$i", gain[i]); if (locked[i]) m = m or (1 shl i) }
        e.putInt("lk", m).apply()
    }
}

/**
 * Calibración con verificación (sin dependencias de Android, así se puede probar en el ordenador).
 *
 * Cada gesto da una estimación del multiplicador ideal: ln(recorrido pedido / recorrido logrado).
 * Positivo = te quedas corto, negativo = te pasas. Por bloque se usa la mediana (robusta a tiros raros)
 * y su error típico (MAD). Cuando el bloque no llega al 90 %:
 *  1. COMPROBAR: ¿el sesgo es mayor que el ruido? Si no, el fallo es de pulso y NO se toca la sensibilidad.
 *  2. REAJUSTAR: cambio proporcional al sesgo y amortiguado (máx. ±12 % por bloque).
 *  3. VERIFICAR: el bloque siguiente debe mostrar menos sesgo; si no mejora, se DESHACE y el paso se reduce a la mitad.
 *  4. FIJAR: con ≥ 90 % (promedio de pares de bloques) dos veces seguidas queda calibrado y no se toca más
 *     hasta que vuelva a fallar.
 */
class GainTuner {
    class Out(val gain: Float, val note: String, val locked: Boolean)

    private var pending = false
    private var prevGain = 1f
    private var prevAbs = 0f
    private var step = 1f
    private var prevHits = 0
    private var prevShots = 0
    private var streak = 0
    private var reverts = 0
    private var cool = 0
    var locked = false
        private set

    fun reset() {
        pending = false; prevGain = 1f; prevAbs = 0f; step = 1f
        prevHits = 0; prevShots = 0; streak = 0; reverts = 0; cool = 0; locked = false
    }

    /** g = multiplicador actual; lr[0 until n] = ln(pedido/logrado) de cada gesto válido; hits/shots = bloque. */
    fun update(g: Float, lr: FloatArray, n: Int, hits: Int, shots: Int): Out {
        val pooled = if (prevShots > 0) (hits + prevHits).toFloat() / (shots + prevShots) else hits.toFloat() / shots
        prevHits = hits
        prevShots = shots
        if (n < 6) return Out(g, "Haz gestos completos hacia la cabeza", locked)
        val b = median(lr, n)
        val thr = max(0.03f, 2f * 1.4826f * mad(lr, n, b) / sqrt(n.toFloat()))
        val significant = abs(b) > thr
        if (pending) {                                   // 3. VERIFICAR el ajuste del bloque anterior
            pending = false
            if (significant && abs(b) > prevAbs * 0.9f) {
                step = max(0.25f, step * 0.5f)
                reverts++
                if (reverts >= 3) cool = 3
                streak = 0
                locked = false
                return Out(prevGain, "No mejoró: deshago el ajuste", false)
            }
        }
        if (pooled >= 0.9f) {                            // 4. FIJAR
            streak++
            reverts = 0
            if (streak >= 2) { locked = true; step = 1f }
            return Out(g, if (locked) "¡Meta ≥90 %! Calibrado y estable" else "Meta ≥90 %: lo confirmo en otro bloque", locked)
        }
        streak = 0
        locked = false
        if (cool > 0) { cool--; return Out(g, "Mejor no tocar la sensibilidad: practica el pulso", false) }
        if (!significant) return Out(g, "Sin sesgo: el fallo es de pulso, no de sensibilidad", false)   // 1. COMPROBAR
        val f = exp((step * b).coerceIn(-0.12f, 0.12f))  // 2. REAJUSTAR
        prevGain = g
        prevAbs = abs(b)
        pending = true
        val pct = abs(((f - 1f) * 100f).roundToInt())
        return Out(g * f, if (b > 0f) "Te quedas corto: +$pct %, lo verifico" else "Te pasas de largo: −$pct %, lo verifico", false)
    }

    private fun median(a: FloatArray, n: Int): Float {
        val t = a.copyOf(n)
        t.sort()
        return if (n % 2 == 1) t[n / 2] else (t[n / 2 - 1] + t[n / 2]) / 2f
    }

    private fun mad(a: FloatArray, n: Int, m: Float): Float = median(FloatArray(n) { abs(a[it] - m) }, n)
}

/**
 * Servicio ligero en primer plano: notificación con temperatura y temporizador de la rutina,
 * más un HUD flotante opcional. El HUD solo muestra datos y cambia de arma: no lee ni toca ningún juego.
 */
class GameService : Service() {
    companion object {
        const val EXTRA_GAME = "game"
        const val EXTRA_HUD = "hud"
        @Volatile var hudVisible = false
        private const val CH = "game"
        private const val ID = 1
        val ROUTINES = mapOf(
            "ff" to listOf(
                "Pre-apunta a la altura de la cabeza moviéndote" to 120,
                "Ráfagas cortas: levanta la mira del pecho a la cabeza" to 240,
                "Seguimiento lateral y cambio rápido de blanco" to 240),
            "cod" to listOf(
                "Dispara a la pared sin mover la mira (retroceso)" to 120,
                "Bots móviles: mantén la mira en la cabeza" to 240,
                "Pasa la mira entre blancos a distintas distancias" to 240))
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager
    private val prefs by lazy { getSharedPreferences("sc", MODE_PRIVATE) }
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private var steps: List<Pair<String, Int>> = emptyList()
    private var idx = 0
    private var endAt = 0L
    private var temp = 0f
    private var warned = false
    private var hud: LinearLayout? = null
    private var pill: TextView? = null
    private var sugg: TextView? = null
    private var chips: List<TextView> = emptyList()
    private val next = Runnable { advance() }
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            if (temp >= 42f && !warned) { warned = true; buzz() } else if (temp < 40f) warned = false
            nm.notify(ID, build())
            refreshHud()
        }
    }

    override fun onCreate() {
        super.onCreate()
        SensiState.load(prefs)
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Modo juego", NotificationManager.IMPORTANCE_LOW))
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { temp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val g = i?.getStringExtra(EXTRA_GAME)
        if (g != null) {
            h.removeCallbacks(next)
            steps = ROUTINES[g] ?: emptyList()
            idx = 0
            if (steps.isNotEmpty()) {
                endAt = System.currentTimeMillis() + steps[0].second * 1000L
                h.postDelayed(next, steps[0].second * 1000L)
            }
        }
        startForeground(ID, build())
        val wantHud = i?.takeIf { it.hasExtra(EXTRA_HUD) }?.getBooleanExtra(EXTRA_HUD, false)
        if (wantHud == true) showHud() else if (wantHud == false) hideHud()
        return START_NOT_STICKY
    }

    private fun advance() {
        buzz()
        idx++
        if (idx < steps.size) {
            endAt = System.currentTimeMillis() + steps[idx].second * 1000L
            h.postDelayed(next, steps[idx].second * 1000L)
        }
        nm.notify(ID, build())
    }

    private fun build(): Notification {
        val hot = temp >= 42f
        val b = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSubText(if (hot) "%.1f °C: baja gráficos".format(temp) else "%.1f °C".format(temp))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (idx < steps.size) {
            b.setContentTitle("Paso ${idx + 1} de ${steps.size}")
                .setContentText(steps[idx].first)
                .setWhen(endAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            b.setContentTitle(if (steps.isEmpty()) "Modo juego activo" else "Rutina completa")
                .setContentText(if (hot) "Calor alto: baja un nivel de gráficos" else "Temperatura bajo control")
        }
        return b.build()
    }

    private fun buzz() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ---------------------------------------------------------------- HUD flotante

    /**
     * Mini panel arrastrable. Solo ocupa el área de la píldora (y del menú cuando está abierto): el resto de la pantalla
     * sigue siendo del juego. Toca la píldora para abrir/cerrar el menú de armas; arrástrala para moverla.
     */
    private fun showHud() {
        if (hud != null || !Settings.canDrawOverlays(this)) return
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        fun label(sp: Float, l: Int, t: Int) = TextView(this).apply {
            textSize = sp; setTextColor(Color.WHITE); setPadding(px(l), px(t), px(l), px(t))
        }
        val p = label(11f, 8, 4)
        val s = label(11f, 8, 3)
        val row = LinearLayout(this)
        val cs = SensiState.WEAPONS.mapIndexed { i, n ->
            label(12f, 10, 6).apply {
                text = n
                gravity = Gravity.CENTER
                setOnClickListener { SensiState.weapon = i; SensiState.save(prefs); refreshHud() }
            }.also { row.addView(it) }
        }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(row)
            addView(s)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.argb(150, 8, 12, 20)); setCornerRadius(10f * d) }
            addView(p)
            addView(menu)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = px(16); y = px(96) }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        p.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (!moved && hypot(dx, dy) > slop) moved = true
                    if (moved) {
                        lp.x = ox + dx.toInt()
                        lp.y = oy + dy.toInt()
                        try { wm.updateViewLayout(root, lp) } catch (ex: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            true
        }

        try { wm.addView(root, lp) } catch (e: Exception) { return }
        hud = root; pill = p; sugg = s; chips = cs; hudVisible = true
        SensiState.listener = { refreshHud() }
        refreshHud()
    }

    private fun hideHud() {
        SensiState.listener = null
        hud?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        hud = null; pill = null; sugg = null; chips = emptyList(); hudVisible = false
    }

    /** Se llama solo cuando algo cambia (arma, latencia medida en la arena, temperatura): no hay temporizador del HUD. */
    private fun refreshHud() {
        val st = SensiState
        val lat = if (st.latencyMs >= 0f) "${st.latencyMs.roundToInt()} ms" else "-- ms"
        val rate = if (st.lastRate >= 0) "${st.lastRate} % · " else ""
        pill?.text = "${SensiState.WEAPONS[st.weapon]} · ${rate}arena $lat · ${temp.roundToInt()}°C"
        sugg?.text = SensiState.suggestion()
        chips.forEachIndexed { i, c ->
            c.setBackgroundColor(if (i == st.weapon) Color.argb(160, 61, 214, 140) else Color.TRANSPARENT)
        }
    }

    override fun onDestroy() {
        h.removeCallbacks(next)
        hideHud()
        try { unregisterReceiver(battery) } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
