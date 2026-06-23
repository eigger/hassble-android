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
import kotlin.math.sin
import kotlin.random.Random

private enum class CatState { Loaf, Idle, Walk, Run, Front, Sleep, Purr }

private val CAT_BODY  = Color(0xFFF0F0F0)   // white
private val CAT_INNER = Color(0xFFFFB0C8)   // pink inner ear / paw
private val CAT_DARK  = Color(0xFF1A1A1A)
private val CAT_PINK  = Color(0xFFFF9BB5)
private val CAT_WHISK = Color(0xFFAAAAAA)
private val CAT_ZZZ   = Color(0xFF9E86FF)
private val CAT_HEART = Color(0xFFFF6B9D)

@Composable
fun CatRunOverlay(dataRate: Int, modifier: Modifier = Modifier) {
    val rate    = rememberUpdatedState(dataRate)
    val context = LocalContext.current

    var state by remember { mutableStateOf(CatState.Loaf) }
    var catX  by remember { mutableFloatStateOf(0.45f) }
    var dir   by remember { mutableIntStateOf(1) }
    var frame by remember { mutableIntStateOf(0) }
    var zProg by remember { mutableFloatStateOf(0f) }

    // 프레임 틱
    LaunchedEffect(Unit) {
        while (true) {
            delay(when (state) {
                CatState.Run   -> (140 / (1 + rate.value * 0.3f)).toLong().coerceIn(45, 140)
                CatState.Walk  -> 230L
                CatState.Loaf, CatState.Sleep -> 1100L
                CatState.Front -> 650L
                CatState.Purr  -> 350L
                CatState.Idle  -> 550L
            })
            frame = (frame + 1) % 8
        }
    }

    // 수면 ZZZ 애니메이션
    LaunchedEffect(state) {
        if (state != CatState.Sleep) { zProg = 0f; return@LaunchedEffect }
        while (true) {
            zProg = 0f; delay(2200)
            repeat(28) { i -> zProg = i / 28f; delay(90) }
        }
    }

    // 골골송 + 진동 (Purr 상태 진입 시)
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

    // 메인 상태 머신
    LaunchedEffect(Unit) {
        while (true) {
            when (state) {
                CatState.Loaf -> {
                    idleWait(Random.nextLong(9000, 20000), rate, 3,
                        onTrigger = { state = CatState.Run },
                        interruptCheck = { state != CatState.Loaf })
                    if (state == CatState.Loaf)
                        state = pick(CatState.Idle, CatState.Idle, CatState.Walk, CatState.Front)
                }
                CatState.Idle -> {
                    idleWait(Random.nextLong(3000, 8000), rate, 3,
                        onTrigger = { state = CatState.Run },
                        interruptCheck = { state != CatState.Idle })
                    if (state == CatState.Idle)
                        state = pick(CatState.Loaf, CatState.Walk, CatState.Walk, CatState.Front, CatState.Sleep)
                }
                CatState.Walk -> {
                    val until = System.currentTimeMillis() + Random.nextLong(8000, 20000)
                    while (System.currentTimeMillis() < until && state == CatState.Walk) {
                        if (rate.value >= 3) { state = CatState.Run; break }
                        catX += dir * 0.004f
                        if (catX > 0.88f) { catX = 0.88f; dir = -1 }
                        if (catX < 0.12f) { catX = 0.12f; dir = 1 }
                        delay(50)
                    }
                    if (state == CatState.Walk)
                        state = if (Random.nextBoolean()) CatState.Idle else CatState.Loaf
                }
                CatState.Run -> {
                    while (rate.value >= 1 && state == CatState.Run) {
                        catX += dir * 0.016f
                        if (catX > 1.1f)  { catX = 1.1f;  dir = -1 }
                        if (catX < -0.1f) { catX = -0.1f; dir =  1 }
                        delay(16)
                    }
                    if (state == CatState.Run) state = CatState.Walk
                }
                CatState.Front -> {
                    val end = System.currentTimeMillis() + Random.nextLong(4000, 10000)
                    while (System.currentTimeMillis() < end && state == CatState.Front) delay(200)
                    if (state == CatState.Front) state = CatState.Idle
                }
                CatState.Sleep -> {
                    idleWait(Random.nextLong(12000, 28000), rate, 5,
                        onTrigger = { state = CatState.Run },
                        interruptCheck = { state != CatState.Sleep })
                    if (state == CatState.Sleep) state = CatState.Idle
                }
                CatState.Purr -> {
                    delay(4000)
                    if (state == CatState.Purr) state = CatState.Idle
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .pointerInput(Unit) { detectTapGestures { state = CatState.Purr } }
    ) {
        val x = catX * size.width
        val y = size.height * 0.6f
        when (state) {
            CatState.Run   -> sideCat(x, y, frame, dir, fast = true)
            CatState.Walk  -> sideCat(x, y, frame, dir, fast = false)
            CatState.Loaf  -> loafCat(x, y, frame)
            CatState.Idle  -> idleCat(x, y, frame)
            CatState.Front -> frontCat(x, y, frame)
            CatState.Sleep -> sleepCat(x, y, frame, zProg)
            CatState.Purr  -> purrCat(x, y, frame)
        }
    }
}

private suspend fun idleWait(
    ms: Long,
    rate: State<Int>,
    threshold: Int,
    onTrigger: () -> Unit,
    interruptCheck: () -> Boolean = { false }
) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) {
        if (interruptCheck()) return
        if (rate.value >= threshold) { onTrigger(); return }
        delay(300)
    }
}

private fun pick(vararg states: CatState) = states[Random.nextInt(states.size)]

// ── 골골송 오디오 ────────────────────────────────────────────────────────────

private fun buildPurrAudioTrack(): AudioTrack {
    val sampleRate = 22050
    val numSamples = sampleRate * 4
    val buf = ShortArray(numSamples)
    val fadeSamples = (sampleRate * 0.08).toInt()
    for (i in buf.indices) {
        val t = i.toDouble() / sampleRate
        val am = 0.5 + 0.5 * sin(2 * PI * 6.0 * t)
        val wave = sin(2 * PI * 27.0 * t) +
                0.5 * sin(2 * PI * 54.0 * t) +
                0.25 * sin(2 * PI * 81.0 * t)
        var s = (wave / 1.75 * am * 0.65 * Short.MAX_VALUE).toInt()
        if (i < fadeSamples) s = (s * i / fadeSamples.toDouble()).toInt()
        if (i > numSamples - fadeSamples) s = (s * (numSamples - i) / fadeSamples.toDouble()).toInt()
        buf[i] = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build())
        .setAudioFormat(AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build())
        .setBufferSizeInBytes(maxOf(buf.size * 2, minBuf))
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
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
        val timings    = longArrayOf(0, 30, 20, 30, 20, 60, 40, 30, 20, 30)
        val amplitudes = intArrayOf(0, 80,  0, 90,  0, 140, 0, 80,  0, 100)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(longArrayOf(0, 30, 20, 30, 20, 60), 0)
    }
}

// ── 옆모습 (달리기 / 걷기) ─────────────────────────────────────────────────

private fun DrawScope.sideCat(cx: Float, cy: Float, frame: Int, dir: Int, fast: Boolean) {
    if (dir < 0) scale(-1f, 1f, Offset(cx, cy)) { sideCatBody(cx, cy, frame, fast) }
    else sideCatBody(cx, cy, frame, fast)
}

private fun DrawScope.sideCatBody(cx: Float, cy: Float, frame: Int, fast: Boolean) {
    val s = 13.dp.toPx(); val sw = 2.3f.dp.toPx()

    val tailHigh = frame % 2 == 0
    drawPath(Path().apply {
        moveTo(cx - s * 0.9f, cy - s * 0.05f)
        quadraticBezierTo(cx - s * 1.65f, cy - s * (if (tailHigh) 0.62f else 0.26f),
            cx - s * 1.35f, cy - s * (if (tailHigh) 1.25f else 0.85f))
    }, CAT_BODY, style = Stroke(sw, cap = StrokeCap.Round))

    drawOval(CAT_BODY, Offset(cx - s, cy - s * 0.43f), Size(s * 2f, s * 0.86f))
    drawCircle(CAT_BODY, s * 0.55f, Offset(cx + s * 0.85f, cy - s * 0.22f))
    sideEar(cx, cy, s, s * 0.52f); sideEar(cx, cy, s, s * 0.87f)
    drawCircle(CAT_DARK, s * 0.1f, Offset(cx + s * 0.96f, cy - s * 0.29f))
    drawCircle(Color.White, s * 0.033f, Offset(cx + s * 1.0f, cy - s * 0.33f))
    drawCircle(CAT_PINK, s * 0.065f, Offset(cx + s * 1.12f, cy - s * 0.12f))
    val ww = 1.2f.dp.toPx()
    drawLine(CAT_WHISK, Offset(cx + s * 1.08f, cy - s * 0.17f), Offset(cx + s * 1.52f, cy - s * 0.25f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 1.08f, cy - s * 0.09f), Offset(cx + s * 1.52f, cy - s * 0.04f), ww)

    val amp = if (fast) s * 0.38f else s * 0.22f
    val (fSwing, bSwing) = when (frame % 4) {
        0 -> amp to -amp; 1 -> amp * 0.2f to -amp * 0.2f
        2 -> -amp to amp; else -> -amp * 0.2f to amp * 0.2f
    }
    val fx = cx + s * 0.45f; val bx = cx - s * 0.52f
    drawLine(CAT_BODY, Offset(fx, cy + s * 0.28f), Offset(fx + fSwing, cy + s * 0.82f), sw, StrokeCap.Round)
    drawLine(CAT_BODY, Offset(fx - s * 0.22f, cy + s * 0.28f), Offset(fx - s * 0.22f - fSwing * 0.55f, cy + s * 0.82f), sw, StrokeCap.Round)
    drawLine(CAT_BODY, Offset(bx, cy + s * 0.28f), Offset(bx + bSwing, cy + s * 0.82f), sw, StrokeCap.Round)
    drawLine(CAT_BODY, Offset(bx - s * 0.22f, cy + s * 0.28f), Offset(bx - s * 0.22f - bSwing * 0.55f, cy + s * 0.82f), sw, StrokeCap.Round)
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

private fun DrawScope.loafCat(cx: Float, cy: Float, frame: Int) {
    val s = 13.dp.toPx(); val sw = 2.3f.dp.toPx()

    drawOval(CAT_BODY, Offset(cx - s * 0.85f, cy - s * 0.22f), Size(s * 1.7f, s * 0.78f))
    drawCircle(CAT_BODY, s * 0.52f, Offset(cx, cy - s * 0.52f))
    frontEar(cx, cy, s, -1f); frontEar(cx, cy, s, 1f)

    val blink = frame % 8 == 7
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

    drawOval(CAT_INNER, Offset(cx - s * 0.55f, cy + s * 0.5f), Size(s * 0.42f, s * 0.17f))
    drawOval(CAT_INNER, Offset(cx + s * 0.13f, cy + s * 0.5f), Size(s * 0.42f, s * 0.17f))

    val ty = cy + (if (frame % 4 < 2) s * 0.06f else -s * 0.06f)
    drawLine(CAT_BODY, Offset(cx + s * 0.85f, cy + s * 0.1f), Offset(cx + s * 1.22f, ty), sw, StrokeCap.Round)
}

// ── 앉아있기 ──────────────────────────────────────────────────────────────────

private fun DrawScope.idleCat(cx: Float, cy: Float, frame: Int) {
    val s = 13.dp.toPx(); val sw = 2.3f.dp.toPx()

    drawOval(CAT_BODY, Offset(cx - s * 0.72f, cy - s * 0.12f), Size(s * 1.44f, s * 0.72f))
    drawCircle(CAT_BODY, s * 0.52f, Offset(cx + s * 0.14f, cy - s * 0.5f))
    drawPath(Path().apply {
        moveTo(cx - s * 0.1f, cy - s * 0.82f); lineTo(cx + s * 0.03f, cy - s * 1.18f)
        lineTo(cx + s * 0.18f, cy - s * 0.82f); close()
    }, CAT_BODY)
    drawPath(Path().apply {
        moveTo(cx - s * 0.06f, cy - s * 0.87f); lineTo(cx + s * 0.03f, cy - s * 1.07f)
        lineTo(cx + s * 0.14f, cy - s * 0.87f); close()
    }, CAT_INNER)
    drawPath(Path().apply {
        moveTo(cx + s * 0.22f, cy - s * 0.82f); lineTo(cx + s * 0.36f, cy - s * 1.18f)
        lineTo(cx + s * 0.5f, cy - s * 0.82f); close()
    }, CAT_BODY)
    drawPath(Path().apply {
        moveTo(cx + s * 0.26f, cy - s * 0.87f); lineTo(cx + s * 0.36f, cy - s * 1.07f)
        lineTo(cx + s * 0.46f, cy - s * 0.87f); close()
    }, CAT_INNER)

    val look = if (frame / 4 % 2 == 0) s * 0.03f else -s * 0.03f
    drawCircle(CAT_DARK, s * 0.1f, Offset(cx + s * 0.2f + look, cy - s * 0.52f))
    drawCircle(Color.White, s * 0.032f, Offset(cx + s * 0.24f + look, cy - s * 0.56f))

    drawCircle(CAT_PINK, s * 0.065f, Offset(cx + s * 0.35f, cy - s * 0.35f))
    val ww = 1.2f.dp.toPx()
    drawLine(CAT_WHISK, Offset(cx + s * 0.32f, cy - s * 0.38f), Offset(cx + s * 0.7f, cy - s * 0.45f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 0.32f, cy - s * 0.3f), Offset(cx + s * 0.7f, cy - s * 0.26f), ww)

    drawPath(Path().apply {
        moveTo(cx - s * 0.72f, cy + s * 0.1f)
        quadraticBezierTo(cx - s * 1.1f, cy + s * 0.58f, cx - s * 0.1f, cy + s * 0.6f)
    }, CAT_BODY, style = Stroke(sw, cap = StrokeCap.Round))

    drawOval(CAT_BODY, Offset(cx - s * 0.55f, cy + s * 0.5f), Size(s * 0.38f, s * 0.16f))
    drawOval(CAT_BODY, Offset(cx + s * 0.1f, cy + s * 0.5f), Size(s * 0.38f, s * 0.16f))
}

// ── 정면 보기 ────────────────────────────────────────────────────────────────

private fun DrawScope.frontCat(cx: Float, cy: Float, frame: Int) {
    val s = 13.dp.toPx()

    drawOval(CAT_BODY, Offset(cx - s * 0.7f, cy - s * 0.06f), Size(s * 1.4f, s * 0.66f))
    drawCircle(CAT_BODY, s * 0.62f, Offset(cx, cy - s * 0.56f))
    frontEar(cx, cy, s, -1f); frontEar(cx, cy, s, 1f)

    val wink = frame % 8 == 6
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
    drawLine(CAT_WHISK, Offset(cx - s * 0.08f, cy - s * 0.4f), Offset(cx - s * 0.68f, cy - s * 0.48f), ww)
    drawLine(CAT_WHISK, Offset(cx - s * 0.08f, cy - s * 0.32f), Offset(cx - s * 0.68f, cy - s * 0.28f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 0.08f, cy - s * 0.4f), Offset(cx + s * 0.68f, cy - s * 0.48f), ww)
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

private fun DrawScope.sleepCat(cx: Float, cy: Float, frame: Int, zProg: Float) {
    val s = 13.dp.toPx(); val sw = 2.3f.dp.toPx()

    drawOval(CAT_BODY, Offset(cx - s * 1.15f, cy - s * 0.36f), Size(s * 2.3f, s * 0.72f))
    drawCircle(CAT_BODY, s * 0.5f, Offset(cx + s * 0.9f, cy - s * 0.18f))
    drawPath(Path().apply {
        moveTo(cx + s * 0.58f, cy - s * 0.55f); lineTo(cx + s * 0.71f, cy - s * 0.9f)
        lineTo(cx + s * 0.86f, cy - s * 0.55f); close()
    }, CAT_BODY)
    drawPath(Path().apply {
        moveTo(cx + s * 0.64f, cy - s * 0.59f); lineTo(cx + s * 0.71f, cy - s * 0.78f)
        lineTo(cx + s * 0.8f, cy - s * 0.59f); close()
    }, CAT_INNER)
    drawPath(Path().apply {
        moveTo(cx + s * 0.92f, cy - s * 0.55f); lineTo(cx + s * 1.05f, cy - s * 0.9f)
        lineTo(cx + s * 1.18f, cy - s * 0.55f); close()
    }, CAT_BODY)
    drawPath(Path().apply {
        moveTo(cx + s * 0.98f, cy - s * 0.59f); lineTo(cx + s * 1.05f, cy - s * 0.78f)
        lineTo(cx + s * 1.12f, cy - s * 0.59f); close()
    }, CAT_INNER)

    val arc = Stroke(1.8f.dp.toPx(), cap = StrokeCap.Round)
    drawArc(CAT_DARK, 200f, -160f, false,
        Offset(cx + s * 0.7f, cy - s * 0.29f), Size(s * 0.24f, s * 0.14f), style = arc)
    drawArc(CAT_DARK, 200f, -160f, false,
        Offset(cx + s * 0.88f, cy - s * 0.29f), Size(s * 0.24f, s * 0.14f), style = arc)

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
        if (zProg > 0.6f) zShape(baseX + s * 0.3f, baseY - s * 0.55f, 1.0f)
    }
}

// ── 골골 (만져주면 행복) ──────────────────────────────────────────────────────

private fun DrawScope.purrCat(cx: Float, cy: Float, frame: Int) {
    val s = 13.dp.toPx()

    // 몸통 & 머리 (frontCat 기반)
    drawOval(CAT_BODY, Offset(cx - s * 0.7f, cy - s * 0.06f), Size(s * 1.4f, s * 0.66f))
    drawCircle(CAT_BODY, s * 0.62f, Offset(cx, cy - s * 0.56f))
    frontEar(cx, cy, s, -1f); frontEar(cx, cy, s, 1f)

    // 기쁜 눈 (^ ^)
    val eyeStroke = Stroke(2.2f.dp.toPx(), cap = StrokeCap.Round)
    drawArc(CAT_DARK, 0f, -180f, false,
        Offset(cx - s * 0.44f, cy - s * 0.74f), Size(s * 0.28f, s * 0.2f), style = eyeStroke)
    drawArc(CAT_DARK, 0f, -180f, false,
        Offset(cx + s * 0.16f, cy - s * 0.74f), Size(s * 0.28f, s * 0.2f), style = eyeStroke)

    // 볼터치
    drawCircle(CAT_PINK.copy(alpha = 0.45f), s * 0.22f, Offset(cx - s * 0.44f, cy - s * 0.38f))
    drawCircle(CAT_PINK.copy(alpha = 0.45f), s * 0.22f, Offset(cx + s * 0.44f, cy - s * 0.38f))

    // 코 & 미소
    drawCircle(CAT_PINK, s * 0.075f, Offset(cx, cy - s * 0.38f))
    val mw = 1.5f.dp.toPx()
    drawLine(CAT_DARK, Offset(cx, cy - s * 0.31f), Offset(cx - s * 0.14f, cy - s * 0.21f), mw, StrokeCap.Round)
    drawLine(CAT_DARK, Offset(cx, cy - s * 0.31f), Offset(cx + s * 0.14f, cy - s * 0.21f), mw, StrokeCap.Round)

    // 수염
    val ww = 1.3f.dp.toPx()
    drawLine(CAT_WHISK, Offset(cx - s * 0.08f, cy - s * 0.4f), Offset(cx - s * 0.7f, cy - s * 0.5f), ww)
    drawLine(CAT_WHISK, Offset(cx - s * 0.08f, cy - s * 0.32f), Offset(cx - s * 0.7f, cy - s * 0.28f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 0.08f, cy - s * 0.4f), Offset(cx + s * 0.7f, cy - s * 0.5f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 0.08f, cy - s * 0.32f), Offset(cx + s * 0.7f, cy - s * 0.28f), ww)

    // 앞발
    drawOval(CAT_BODY, Offset(cx - s * 0.55f, cy + s * 0.52f), Size(s * 0.42f, s * 0.18f))
    drawOval(CAT_BODY, Offset(cx + s * 0.13f, cy + s * 0.52f), Size(s * 0.42f, s * 0.18f))

    // 하트 두 개 (프레임에 맞춰 위로 떠오름)
    val h1f = frame % 8
    val h2f = (frame + 4) % 8
    val h1a = (1f - h1f / 8f).coerceIn(0.15f, 0.9f)
    val h2a = (1f - h2f / 8f).coerceIn(0.1f, 0.8f)
    drawHeart(cx - s * 0.35f, cy - s * (1.35f + h1f * 0.1f), s * 0.22f, CAT_HEART.copy(alpha = h1a))
    drawHeart(cx + s * 0.35f, cy - s * (1.25f + h2f * 0.1f), s * 0.16f, Color(0xFFFF8FB5).copy(alpha = h2a))
}

private fun DrawScope.drawHeart(cx: Float, cy: Float, r: Float, color: Color) {
    // Two circles for top bumps + downward triangle for bottom point
    drawCircle(color, r, Offset(cx - r * 0.65f, cy - r * 0.1f))
    drawCircle(color, r, Offset(cx + r * 0.65f, cy - r * 0.1f))
    drawPath(Path().apply {
        moveTo(cx - r * 1.6f, cy - r * 0.1f)
        lineTo(cx, cy + r * 1.4f)
        lineTo(cx + r * 1.6f, cy - r * 0.1f)
        close()
    }, color)
}
