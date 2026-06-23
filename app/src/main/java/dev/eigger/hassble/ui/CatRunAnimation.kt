package dev.eigger.hassble.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

private enum class CatState { Loaf, Idle, Walk, Run, Front, Sleep, Purr }

private val CAT_BODY  = Color(0xFFF0F0F0)
private val CAT_INNER = Color(0xFFFFB0C8)
private val CAT_DARK  = Color(0xFF1A1A1A)
private val CAT_PINK  = Color(0xFFFF9BB5)
private val CAT_WHISK = Color(0xFFAAAAAA)
private val CAT_ZZZ   = Color(0xFF9E86FF)
private val CAT_HEART = Color(0xFFFF6B9D)

// 최대 이동 속도 (정규화 단위/틱)
private const val WALK_SPEED = 0.0018f
private const val RUN_BASE   = 0.0055f
private const val RUN_PER_RATE = 0.0009f

@Composable
fun CatRunOverlay(dataRate: Int, modifier: Modifier = Modifier) {
    val rate    = rememberUpdatedState(dataRate)
    val context = LocalContext.current

    var state       by remember { mutableStateOf(CatState.Loaf) }
    var catX        by remember { mutableFloatStateOf(0.45f) }
    var catVx       by remember { mutableFloatStateOf(0f) }
    var targetSpeed by remember { mutableFloatStateOf(0f) }
    var dir         by remember { mutableIntStateOf(1) }
    var animTime    by remember { mutableFloatStateOf(0f) }
    var zProg       by remember { mutableFloatStateOf(0f) }

    // ── 마스터 물리 + 애니메이션 틱 (16ms ≈ 60fps) ──────────────────────
    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            val targetVx = targetSpeed * dir
            catVx += (targetVx - catVx) * 0.10f          // 관성
            catX  += catVx
            if (catX > 0.88f && catVx > 0f) dir = -1     // 경계 반전
            if (catX < 0.12f && catVx < 0f) dir =  1
            catX = catX.coerceIn(0.05f, 0.95f)

            val normalizedV = (abs(catVx) / RUN_BASE).coerceIn(0f, 1f)
            animTime += when (state) {
                CatState.Run, CatState.Walk -> 0.045f + normalizedV * 0.21f
                CatState.Purr              -> 0.07f
                else                       -> 0.018f   // 느린 호흡
            }
        }
    }

    // ── ZZZ 애니메이션 ───────────────────────────────────────────────────
    LaunchedEffect(state) {
        if (state != CatState.Sleep) { zProg = 0f; return@LaunchedEffect }
        while (true) {
            zProg = 0f; delay(2200)
            repeat(28) { i -> zProg = i / 28f; delay(90) }
        }
    }

    // ── 골골송 + 진동 ────────────────────────────────────────────────────
    LaunchedEffect(state) {
        if (state != CatState.Purr) return@LaunchedEffect
        val vibrator = getVibrator(context)
        var audioTrack: AudioTrack? = null
        try {
            audioTrack = withContext(Dispatchers.Default) { buildPurrAudioTrack() }
            audioTrack.play()
            startPurrVibration(vibrator)
            delay(Long.MAX_VALUE)
        } finally {
            runCatching { audioTrack?.stop(); audioTrack?.release() }
            runCatching { vibrator.cancel() }
        }
    }

    // ── 상태 머신 ────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        while (true) {
            when (state) {
                CatState.Loaf -> {
                    targetSpeed = 0f
                    idleWait(Random.nextLong(9000, 20000), rate, 3,
                        onTrigger = { state = CatState.Run },
                        interruptCheck = { state != CatState.Loaf })
                    if (state == CatState.Loaf)
                        state = pick(CatState.Idle, CatState.Idle, CatState.Walk, CatState.Front)
                }
                CatState.Idle -> {
                    targetSpeed = 0f
                    idleWait(Random.nextLong(3000, 8000), rate, 3,
                        onTrigger = { state = CatState.Run },
                        interruptCheck = { state != CatState.Idle })
                    if (state == CatState.Idle)
                        state = pick(CatState.Loaf, CatState.Walk, CatState.Walk, CatState.Front, CatState.Sleep)
                }
                CatState.Walk -> {
                    targetSpeed = WALK_SPEED
                    val until = System.currentTimeMillis() + Random.nextLong(8000, 20000)
                    while (System.currentTimeMillis() < until && state == CatState.Walk) {
                        if (rate.value >= 3) { state = CatState.Run; break }
                        delay(200)
                    }
                    if (state == CatState.Walk) {
                        targetSpeed = 0f
                        state = if (Random.nextBoolean()) CatState.Idle else CatState.Loaf
                    }
                }
                CatState.Run -> {
                    while (rate.value >= 1 && state == CatState.Run) {
                        targetSpeed = RUN_BASE + rate.value * RUN_PER_RATE
                        delay(120)
                    }
                    if (state == CatState.Run) {
                        targetSpeed = 0f
                        state = CatState.Walk
                    }
                }
                CatState.Front -> {
                    targetSpeed = 0f
                    val end = System.currentTimeMillis() + Random.nextLong(4000, 10000)
                    while (System.currentTimeMillis() < end && state == CatState.Front) delay(200)
                    if (state == CatState.Front) state = CatState.Idle
                }
                CatState.Sleep -> {
                    targetSpeed = 0f
                    idleWait(Random.nextLong(12000, 28000), rate, 5,
                        onTrigger = { state = CatState.Run },
                        interruptCheck = { state != CatState.Sleep })
                    if (state == CatState.Sleep) state = CatState.Idle
                }
                CatState.Purr -> {
                    targetSpeed = 0f
                    delay(4000)
                    if (state == CatState.Purr) state = CatState.Idle
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(Unit) { detectTapGestures { state = CatState.Purr } }
    ) {
        val x   = catX * size.width
        val cy  = size.height * 0.62f
        val spd = abs(catVx)
        // 보행 중 상하 진동 (body bobbing)
        val s13 = 13.dp.toPx()
        val bob = -abs(sin(animTime * 2f * PI.toFloat())) *
                (spd / RUN_BASE).coerceIn(0f, 1f) * s13 * 0.12f

        when (state) {
            CatState.Run   -> sideCat(x, cy + bob, animTime, dir, spd, fast = true)
            CatState.Walk  -> sideCat(x, cy + bob, animTime, dir, spd, fast = false)
            CatState.Loaf  -> loafCat(x, cy, animTime)
            CatState.Idle  -> idleCat(x, cy, animTime)
            CatState.Front -> frontCat(x, cy, animTime)
            CatState.Sleep -> sleepCat(x, cy, animTime, zProg)
            CatState.Purr  -> purrCat(x, cy, animTime)
        }
    }
}

// ── 유틸 ──────────────────────────────────────────────────────────────────────

private suspend fun idleWait(
    ms: Long, rate: State<Int>, threshold: Int,
    onTrigger: () -> Unit, interruptCheck: () -> Boolean = { false }
) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) {
        if (interruptCheck()) return
        if (rate.value >= threshold) { onTrigger(); return }
        delay(300)
    }
}

private fun pick(vararg states: CatState) = states[Random.nextInt(states.size)]

// ── 음향 / 햅틱 ───────────────────────────────────────────────────────────────

private fun buildPurrAudioTrack(): AudioTrack {
    val sampleRate = 22050
    val numSamples = sampleRate * 4
    val buf = ShortArray(numSamples)
    val fade = (sampleRate * 0.08).toInt()
    for (i in buf.indices) {
        val t  = i.toDouble() / sampleRate
        val am = 0.5 + 0.5 * sin(2 * PI * 6.0 * t)
        val w  = sin(2 * PI * 27.0 * t) + 0.5 * sin(2 * PI * 54.0 * t) + 0.25 * sin(2 * PI * 81.0 * t)
        var s  = (w / 1.75 * am * 0.65 * Short.MAX_VALUE).toInt()
        if (i < fade) s = (s * i / fade.toDouble()).toInt()
        if (i > numSamples - fade) s = (s * (numSamples - i) / fade.toDouble()).toInt()
        buf[i] = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(buf.size * 2, minBuf))
        .setTransferMode(AudioTrack.MODE_STATIC).build()
    track.write(buf, 0, buf.size)
    return track
}

@Suppress("DEPRECATION")
private fun getVibrator(context: Context): Vibrator =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
    else
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

private fun startPurrVibration(vibrator: Vibrator) {
    if (!vibrator.hasVibrator()) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val t = longArrayOf(0, 30, 20, 30, 20, 60, 40, 30, 20, 30)
        val a = intArrayOf(0, 80, 0, 90, 0, 140, 0, 80, 0, 100)
        vibrator.vibrate(VibrationEffect.createWaveform(t, a, 0))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(longArrayOf(0, 30, 20, 30, 20, 60), 0)
    }
}

// ── 옆모습 (걷기 / 달리기) ────────────────────────────────────────────────────

private fun DrawScope.sideCat(cx: Float, cy: Float, animTime: Float, dir: Int, speed: Float, fast: Boolean) {
    if (dir < 0) scale(-1f, 1f, Offset(cx, cy)) { sideCatBody(cx, cy, animTime, speed, fast) }
    else sideCatBody(cx, cy, animTime, speed, fast)
}

private fun DrawScope.sideCatBody(cx: Float, cy: Float, animTime: Float, speed: Float, fast: Boolean) {
    val s  = 13.dp.toPx()
    val sw = 2.3f.dp.toPx()
    val t  = animTime * 2f * PI.toFloat()

    // 몸통 기울기 (빠를수록 앞으로 기운다)
    val lean = (speed / RUN_BASE).coerceIn(0f, 1f) * s * 0.18f

    // ── 꼬리 (독립적 사인파) ─────────────────────────────────────────────
    val tailPhase = sin(animTime * 1.3f * PI.toFloat())
    val tailMidY  = cy - s * (0.44f + tailPhase * 0.18f)
    val tailTipY  = cy - s * (0.88f + tailPhase * 0.30f)
    drawPath(Path().apply {
        moveTo(cx - s * 0.9f, cy - s * 0.05f)
        quadraticBezierTo(cx - s * 1.6f, tailMidY, cx - s * 1.3f, tailTipY)
    }, CAT_BODY, style = Stroke(sw, cap = StrokeCap.Round))

    // ── 몸통 ─────────────────────────────────────────────────────────────
    drawOval(CAT_BODY, Offset(cx - s + lean * 0.25f, cy - s * 0.43f), Size(s * 2f, s * 0.86f))

    // ── 머리 (lean 적용) ──────────────────────────────────────────────────
    val hx = cx + s * 0.85f + lean
    val hy = cy - s * 0.22f - lean * 0.4f
    drawCircle(CAT_BODY, s * 0.55f, Offset(hx, hy))
    sideEar(hx, hy, s, -s * 0.33f); sideEar(hx, hy, s, s * 0.02f)

    // 눈
    drawCircle(CAT_DARK, s * 0.10f, Offset(hx + s * 0.11f, hy - s * 0.07f))
    drawCircle(Color.White, s * 0.033f, Offset(hx + s * 0.15f, hy - s * 0.11f))
    // 코 & 수염
    drawCircle(CAT_PINK, s * 0.065f, Offset(hx + s * 0.26f, hy + s * 0.10f))
    val ww = 1.2f.dp.toPx()
    drawLine(CAT_WHISK, Offset(hx + s * 0.22f, hy + s * 0.05f), Offset(hx + s * 0.66f, hy - s * 0.04f), ww)
    drawLine(CAT_WHISK, Offset(hx + s * 0.22f, hy + s * 0.13f), Offset(hx + s * 0.66f, hy + s * 0.18f), ww)

    // ── 2-segment 다리 (갤럽 사이클) ─────────────────────────────────────
    val amp    = if (fast) s * 0.40f else s * 0.24f
    // 앞다리쌍: 0°, 25°, 뒷다리쌍: 180°, 205° 위상
    val fl1 = sin(t)              * amp
    val fl2 = sin(t + 0.43f)     * amp * 0.80f
    val bl1 = sin(t + PI.toFloat()) * amp
    val bl2 = sin(t + PI.toFloat() + 0.43f) * amp * 0.80f
    // 무릎 오프셋 (자연스러운 관절 굴곡)
    val fk1 = sin(t + 0.6f)              * amp * 0.45f
    val fk2 = sin(t + 0.6f + 0.43f)     * amp * 0.36f
    val bk1 = sin(t + 0.6f + PI.toFloat()) * amp * 0.45f
    val bk2 = sin(t + 0.6f + PI.toFloat() + 0.43f) * amp * 0.36f

    val fx = cx + s * 0.45f + lean * 0.3f
    val bx = cx - s * 0.52f
    val hip = cy + s * 0.28f
    val knee = cy + s * 0.55f
    val foot = cy + s * 0.82f

    fun leg(hx: Float, kxOff: Float, fxOff: Float) {
        drawLine(CAT_BODY, Offset(hx, hip), Offset(hx + kxOff, knee), sw, StrokeCap.Round)
        drawLine(CAT_BODY, Offset(hx + kxOff, knee), Offset(hx + fxOff, foot), sw, StrokeCap.Round)
    }
    leg(fx,              fk1, fl1)
    leg(fx - s * 0.22f, fk2, fl2)
    leg(bx,              bk1, bl1)
    leg(bx - s * 0.22f, bk2, bl2)
}

private fun DrawScope.sideEar(cx: Float, cy: Float, s: Float, ox: Float) {
    drawPath(Path().apply {
        moveTo(cx + ox, cy - s * 0.58f); lineTo(cx + ox + s * 0.13f, cy - s * 0.99f)
        lineTo(cx + ox + s * 0.26f, cy - s * 0.58f); close()
    }, CAT_BODY)
    drawPath(Path().apply {
        moveTo(cx + ox + s * 0.04f, cy - s * 0.63f); lineTo(cx + ox + s * 0.13f, cy - s * 0.87f)
        lineTo(cx + ox + s * 0.22f, cy - s * 0.63f); close()
    }, CAT_INNER)
}

// ── 식빵 자세 ────────────────────────────────────────────────────────────────

private fun DrawScope.loafCat(cx: Float, cy: Float, animTime: Float) {
    val s  = 13.dp.toPx()
    val sw = 2.3f.dp.toPx()
    // 호흡 (느린 사인파)
    val breathe = sin(animTime * 0.6f * PI.toFloat()) * s * 0.018f

    drawOval(CAT_BODY, Offset(cx - s * 0.85f, cy - s * 0.22f + breathe), Size(s * 1.7f, s * 0.78f - breathe))
    drawCircle(CAT_BODY, s * 0.52f + breathe * 0.3f, Offset(cx, cy - s * 0.52f))
    frontEar(cx, cy, s, -1f); frontEar(cx, cy, s, 1f)

    // 깜빡임: 랜덤처럼 보이는 패턴 (animTime 기반)
    val blinkCycle = (animTime * 0.18f) % 1f
    val blink = blinkCycle > 0.92f
    val ew = 2.dp.toPx()
    if (blink) {
        drawLine(CAT_DARK, Offset(cx - s * 0.22f, cy - s * 0.54f), Offset(cx - s * 0.06f, cy - s * 0.54f), ew, StrokeCap.Round)
        drawLine(CAT_DARK, Offset(cx + s * 0.06f, cy - s * 0.54f), Offset(cx + s * 0.22f, cy - s * 0.54f), ew, StrokeCap.Round)
    } else {
        drawCircle(CAT_DARK, s * 0.095f, Offset(cx - s * 0.16f, cy - s * 0.54f))
        drawCircle(CAT_DARK, s * 0.095f, Offset(cx + s * 0.16f, cy - s * 0.54f))
        drawCircle(Color.White, s * 0.03f, Offset(cx - s * 0.13f, cy - s * 0.58f))
        drawCircle(Color.White, s * 0.03f, Offset(cx + s * 0.19f, cy - s * 0.58f))
    }
    drawCircle(CAT_PINK, s * 0.065f, Offset(cx, cy - s * 0.37f))
    drawOval(CAT_INNER, Offset(cx - s * 0.55f, cy + s * 0.50f), Size(s * 0.42f, s * 0.17f))
    drawOval(CAT_INNER, Offset(cx + s * 0.13f, cy + s * 0.50f), Size(s * 0.42f, s * 0.17f))

    // 꼬리 실룩 (사인파)
    val ty = cy + sin(animTime * 1.8f * PI.toFloat()) * s * 0.07f
    drawLine(CAT_BODY, Offset(cx + s * 0.85f, cy + s * 0.1f), Offset(cx + s * 1.22f, ty), sw, StrokeCap.Round)
}

// ── 앉아있기 ──────────────────────────────────────────────────────────────────

private fun DrawScope.idleCat(cx: Float, cy: Float, animTime: Float) {
    val s  = 13.dp.toPx()
    val sw = 2.3f.dp.toPx()
    val breathe = sin(animTime * 0.5f * PI.toFloat()) * s * 0.015f

    drawOval(CAT_BODY, Offset(cx - s * 0.72f, cy - s * 0.12f + breathe), Size(s * 1.44f, s * 0.72f - breathe))
    drawCircle(CAT_BODY, s * 0.52f, Offset(cx + s * 0.14f, cy - s * 0.5f))
    drawPath(Path().apply {
        moveTo(cx - s * 0.1f, cy - s * 0.82f); lineTo(cx + s * 0.03f, cy - s * 1.18f)
        lineTo(cx + s * 0.18f, cy - s * 0.82f); close() }, CAT_BODY)
    drawPath(Path().apply {
        moveTo(cx - s * 0.06f, cy - s * 0.87f); lineTo(cx + s * 0.03f, cy - s * 1.07f)
        lineTo(cx + s * 0.14f, cy - s * 0.87f); close() }, CAT_INNER)
    drawPath(Path().apply {
        moveTo(cx + s * 0.22f, cy - s * 0.82f); lineTo(cx + s * 0.36f, cy - s * 1.18f)
        lineTo(cx + s * 0.5f,  cy - s * 0.82f); close() }, CAT_BODY)
    drawPath(Path().apply {
        moveTo(cx + s * 0.26f, cy - s * 0.87f); lineTo(cx + s * 0.36f, cy - s * 1.07f)
        lineTo(cx + s * 0.46f, cy - s * 0.87f); close() }, CAT_INNER)

    // 시선 이동 (사인파로 부드럽게)
    val look = sin(animTime * 0.4f * PI.toFloat()) * s * 0.03f
    drawCircle(CAT_DARK, s * 0.10f, Offset(cx + s * 0.20f + look, cy - s * 0.52f))
    drawCircle(Color.White, s * 0.032f, Offset(cx + s * 0.24f + look, cy - s * 0.56f))

    drawCircle(CAT_PINK, s * 0.065f, Offset(cx + s * 0.35f, cy - s * 0.35f))
    val ww = 1.2f.dp.toPx()
    drawLine(CAT_WHISK, Offset(cx + s * 0.32f, cy - s * 0.38f), Offset(cx + s * 0.70f, cy - s * 0.45f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 0.32f, cy - s * 0.30f), Offset(cx + s * 0.70f, cy - s * 0.26f), ww)

    drawPath(Path().apply {
        moveTo(cx - s * 0.72f, cy + s * 0.1f)
        quadraticBezierTo(cx - s * 1.1f, cy + s * 0.58f, cx - s * 0.1f, cy + s * 0.6f)
    }, CAT_BODY, style = Stroke(sw, cap = StrokeCap.Round))

    drawOval(CAT_BODY, Offset(cx - s * 0.55f, cy + s * 0.50f), Size(s * 0.38f, s * 0.16f))
    drawOval(CAT_BODY, Offset(cx + s * 0.10f, cy + s * 0.50f), Size(s * 0.38f, s * 0.16f))
}

// ── 정면 보기 ────────────────────────────────────────────────────────────────

private fun DrawScope.frontCat(cx: Float, cy: Float, animTime: Float) {
    val s  = 13.dp.toPx()

    drawOval(CAT_BODY, Offset(cx - s * 0.70f, cy - s * 0.06f), Size(s * 1.4f, s * 0.66f))
    drawCircle(CAT_BODY, s * 0.62f, Offset(cx, cy - s * 0.56f))
    frontEar(cx, cy, s, -1f); frontEar(cx, cy, s, 1f)

    val winkCycle = (animTime * 0.12f) % 1f
    val wink = winkCycle > 0.93f
    val ew = 2.dp.toPx()
    if (wink) {
        drawCircle(CAT_DARK, s * 0.12f, Offset(cx - s * 0.28f, cy - s * 0.62f))
        drawCircle(Color.White, s * 0.038f, Offset(cx - s * 0.24f, cy - s * 0.66f))
        drawLine(CAT_DARK, Offset(cx + s * 0.17f, cy - s * 0.62f), Offset(cx + s * 0.42f, cy - s * 0.62f), ew, StrokeCap.Round)
    } else {
        drawCircle(CAT_DARK, s * 0.12f, Offset(cx - s * 0.28f, cy - s * 0.62f))
        drawCircle(CAT_DARK, s * 0.12f, Offset(cx + s * 0.28f, cy - s * 0.62f))
        drawCircle(Color.White, s * 0.038f, Offset(cx - s * 0.24f, cy - s * 0.66f))
        drawCircle(Color.White, s * 0.038f, Offset(cx + s * 0.32f, cy - s * 0.66f))
    }
    drawCircle(CAT_PINK, s * 0.075f, Offset(cx, cy - s * 0.38f))
    drawLine(CAT_DARK, Offset(cx, cy - s * 0.31f), Offset(cx - s * 0.13f, cy - s * 0.22f), 1.5f.dp.toPx(), StrokeCap.Round)
    drawLine(CAT_DARK, Offset(cx, cy - s * 0.31f), Offset(cx + s * 0.13f, cy - s * 0.22f), 1.5f.dp.toPx(), StrokeCap.Round)

    val ww = 1.3f.dp.toPx()
    drawLine(CAT_WHISK, Offset(cx - s * 0.08f, cy - s * 0.40f), Offset(cx - s * 0.68f, cy - s * 0.48f), ww)
    drawLine(CAT_WHISK, Offset(cx - s * 0.08f, cy - s * 0.32f), Offset(cx - s * 0.68f, cy - s * 0.28f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 0.08f, cy - s * 0.40f), Offset(cx + s * 0.68f, cy - s * 0.48f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 0.08f, cy - s * 0.32f), Offset(cx + s * 0.68f, cy - s * 0.28f), ww)

    drawOval(CAT_BODY, Offset(cx - s * 0.55f, cy + s * 0.52f), Size(s * 0.42f, s * 0.18f))
    drawOval(CAT_BODY, Offset(cx + s * 0.13f, cy + s * 0.52f), Size(s * 0.42f, s * 0.18f))
}

private fun DrawScope.frontEar(cx: Float, cy: Float, s: Float, sign: Float) {
    drawPath(Path().apply {
        moveTo(cx + sign * s * 0.28f, cy - s * 0.95f)
        lineTo(cx + sign * s * 0.55f, cy - s * 1.35f)
        lineTo(cx + sign * s * 0.72f, cy - s * 0.95f); close()
    }, CAT_BODY)
    drawPath(Path().apply {
        moveTo(cx + sign * s * 0.33f, cy - s * 0.99f)
        lineTo(cx + sign * s * 0.55f, cy - s * 1.22f)
        lineTo(cx + sign * s * 0.66f, cy - s * 0.99f); close()
    }, CAT_INNER)
}

// ── 수면 ─────────────────────────────────────────────────────────────────────

private fun DrawScope.sleepCat(cx: Float, cy: Float, animTime: Float, zProg: Float) {
    val s  = 13.dp.toPx()
    val sw = 2.3f.dp.toPx()
    val breathe = sin(animTime * 0.5f * PI.toFloat()) * s * 0.02f

    drawOval(CAT_BODY, Offset(cx - s * 1.15f, cy - s * 0.36f + breathe), Size(s * 2.3f, s * 0.72f - breathe))
    drawCircle(CAT_BODY, s * 0.5f, Offset(cx + s * 0.9f, cy - s * 0.18f))

    drawPath(Path().apply {
        moveTo(cx + s * 0.58f, cy - s * 0.55f); lineTo(cx + s * 0.71f, cy - s * 0.9f)
        lineTo(cx + s * 0.86f, cy - s * 0.55f); close() }, CAT_BODY)
    drawPath(Path().apply {
        moveTo(cx + s * 0.64f, cy - s * 0.59f); lineTo(cx + s * 0.71f, cy - s * 0.78f)
        lineTo(cx + s * 0.80f, cy - s * 0.59f); close() }, CAT_INNER)
    drawPath(Path().apply {
        moveTo(cx + s * 0.92f, cy - s * 0.55f); lineTo(cx + s * 1.05f, cy - s * 0.9f)
        lineTo(cx + s * 1.18f, cy - s * 0.55f); close() }, CAT_BODY)
    drawPath(Path().apply {
        moveTo(cx + s * 0.98f, cy - s * 0.59f); lineTo(cx + s * 1.05f, cy - s * 0.78f)
        lineTo(cx + s * 1.12f, cy - s * 0.59f); close() }, CAT_INNER)

    val arc = Stroke(1.8f.dp.toPx(), cap = StrokeCap.Round)
    drawArc(CAT_DARK, 200f, -160f, false, Offset(cx + s * 0.70f, cy - s * 0.29f), Size(s * 0.24f, s * 0.14f), style = arc)
    drawArc(CAT_DARK, 200f, -160f, false, Offset(cx + s * 0.88f, cy - s * 0.29f), Size(s * 0.24f, s * 0.14f), style = arc)

    drawPath(Path().apply {
        moveTo(cx - s * 1.15f, cy - s * 0.05f)
        quadraticBezierTo(cx - s * 1.6f, cy + s * 0.42f, cx - s * 0.8f, cy + s * 0.44f)
    }, CAT_BODY, style = Stroke(sw, cap = StrokeCap.Round))

    drawOval(CAT_INNER, Offset(cx + s * 0.28f, cy + s * 0.27f), Size(s * 0.35f, s * 0.14f))
    drawOval(CAT_INNER, Offset(cx + s * 0.68f, cy + s * 0.27f), Size(s * 0.35f, s * 0.14f))

    if (zProg > 0f) {
        val alpha = when {
            zProg < 0.25f -> zProg / 0.25f
            zProg > 0.75f -> (1f - zProg) / 0.25f
            else -> 1f
        }.coerceIn(0f, 1f)
        val zColor = CAT_ZZZ.copy(alpha = alpha)
        val zSw = Stroke(1.6f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val baseX = cx + s * 1.35f
        val baseY = cy - s * (0.45f + zProg * 1.8f)
        fun zShape(x: Float, y: Float, sc: Float) {
            drawPath(Path().apply {
                moveTo(x, y); lineTo(x + sc * s * 0.5f, y)
                lineTo(x, y + sc * s * 0.5f); lineTo(x + sc * s * 0.5f, y + sc * s * 0.5f)
            }, zColor, style = zSw)
        }
        zShape(baseX, baseY + s * 0.55f, 0.55f)
        if (zProg > 0.3f) zShape(baseX + s * 0.14f, baseY + s * 0.05f, 0.75f)
        if (zProg > 0.6f) zShape(baseX + s * 0.30f, baseY - s * 0.55f, 1.0f)
    }
}

// ── 골골 ─────────────────────────────────────────────────────────────────────

private fun DrawScope.purrCat(cx: Float, cy: Float, animTime: Float) {
    val s  = 13.dp.toPx()

    drawOval(CAT_BODY, Offset(cx - s * 0.70f, cy - s * 0.06f), Size(s * 1.4f, s * 0.66f))
    drawCircle(CAT_BODY, s * 0.62f, Offset(cx, cy - s * 0.56f))
    frontEar(cx, cy, s, -1f); frontEar(cx, cy, s, 1f)

    val eyeStroke = Stroke(2.2f.dp.toPx(), cap = StrokeCap.Round)
    drawArc(CAT_DARK, 0f, -180f, false, Offset(cx - s * 0.44f, cy - s * 0.74f), Size(s * 0.28f, s * 0.20f), style = eyeStroke)
    drawArc(CAT_DARK, 0f, -180f, false, Offset(cx + s * 0.16f, cy - s * 0.74f), Size(s * 0.28f, s * 0.20f), style = eyeStroke)

    drawCircle(CAT_PINK.copy(alpha = 0.45f), s * 0.22f, Offset(cx - s * 0.44f, cy - s * 0.38f))
    drawCircle(CAT_PINK.copy(alpha = 0.45f), s * 0.22f, Offset(cx + s * 0.44f, cy - s * 0.38f))

    drawCircle(CAT_PINK, s * 0.075f, Offset(cx, cy - s * 0.38f))
    val mw = 1.5f.dp.toPx()
    drawLine(CAT_DARK, Offset(cx, cy - s * 0.31f), Offset(cx - s * 0.14f, cy - s * 0.21f), mw, StrokeCap.Round)
    drawLine(CAT_DARK, Offset(cx, cy - s * 0.31f), Offset(cx + s * 0.14f, cy - s * 0.21f), mw, StrokeCap.Round)

    val ww = 1.3f.dp.toPx()
    drawLine(CAT_WHISK, Offset(cx - s * 0.08f, cy - s * 0.40f), Offset(cx - s * 0.70f, cy - s * 0.50f), ww)
    drawLine(CAT_WHISK, Offset(cx - s * 0.08f, cy - s * 0.32f), Offset(cx - s * 0.70f, cy - s * 0.28f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 0.08f, cy - s * 0.40f), Offset(cx + s * 0.70f, cy - s * 0.50f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 0.08f, cy - s * 0.32f), Offset(cx + s * 0.70f, cy - s * 0.28f), ww)

    drawOval(CAT_BODY, Offset(cx - s * 0.55f, cy + s * 0.52f), Size(s * 0.42f, s * 0.18f))
    drawOval(CAT_BODY, Offset(cx + s * 0.13f, cy + s * 0.52f), Size(s * 0.42f, s * 0.18f))

    // 하트 (animTime 기반 부드러운 상승)
    val h1f = (animTime * 0.4f) % 1f
    val h2f = ((animTime * 0.4f) + 0.5f) % 1f
    drawHeart(cx - s * 0.35f, cy - s * (1.3f + h1f * 0.9f), s * 0.22f, CAT_HEART.copy(alpha = (1f - h1f).coerceIn(0.1f, 0.9f)))
    drawHeart(cx + s * 0.35f, cy - s * (1.2f + h2f * 0.9f), s * 0.16f, Color(0xFFFF8FB5).copy(alpha = (1f - h2f).coerceIn(0.1f, 0.8f)))
}

private fun DrawScope.drawHeart(cx: Float, cy: Float, r: Float, color: Color) {
    drawCircle(color, r, Offset(cx - r * 0.65f, cy - r * 0.1f))
    drawCircle(color, r, Offset(cx + r * 0.65f, cy - r * 0.1f))
    drawPath(Path().apply {
        moveTo(cx - r * 1.6f, cy - r * 0.1f)
        lineTo(cx, cy + r * 1.4f)
        lineTo(cx + r * 1.6f, cy - r * 0.1f)
        close()
    }, color)
}
