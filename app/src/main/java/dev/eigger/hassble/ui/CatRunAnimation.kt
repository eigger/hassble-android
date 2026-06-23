package dev.eigger.hassble.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

private enum class CatState { Loaf, Idle, Walk, Run, Front, Sleep }

private val CAT_BODY   = Color(0xFFFF8C42)
private val CAT_INNER  = Color(0xFFFFD580)
private val CAT_DARK   = Color(0xFF1A1A1A)
private val CAT_PINK   = Color(0xFFFF9BB5)
private val CAT_WHISK  = Color(0xFFCCCCCC)
private val CAT_ZZZ    = Color(0xFF9E86FF)

@Composable
fun CatRunOverlay(dataRate: Int, modifier: Modifier = Modifier) {
    val rate = rememberUpdatedState(dataRate)

    var state  by remember { mutableStateOf(CatState.Loaf) }
    var catX   by remember { mutableFloatStateOf(0.45f) }
    var dir    by remember { mutableIntStateOf(1) }
    var frame  by remember { mutableIntStateOf(0) }
    var zProg  by remember { mutableFloatStateOf(0f) }

    // 프레임 틱
    LaunchedEffect(Unit) {
        while (true) {
            delay(when (state) {
                CatState.Run   -> (140 / (1 + rate.value * 0.3f)).toLong().coerceIn(45, 140)
                CatState.Walk  -> 230L
                CatState.Loaf, CatState.Sleep -> 1100L
                CatState.Front -> 650L
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

    // 메인 상태 머신
    LaunchedEffect(Unit) {
        while (true) {
            when (state) {
                CatState.Loaf -> {
                    idleWait(Random.nextLong(9000, 20000), rate, 3) { state = CatState.Run }
                    if (state == CatState.Loaf)
                        state = pick(CatState.Idle, CatState.Idle, CatState.Walk, CatState.Front)
                }
                CatState.Idle -> {
                    idleWait(Random.nextLong(3000, 8000), rate, 3) { state = CatState.Run }
                    if (state == CatState.Idle)
                        state = pick(CatState.Loaf, CatState.Walk, CatState.Walk, CatState.Front, CatState.Sleep)
                }
                CatState.Walk -> {
                    val until = System.currentTimeMillis() + Random.nextLong(8000, 20000)
                    while (System.currentTimeMillis() < until) {
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
                    while (rate.value >= 1) {
                        catX += dir * 0.016f
                        if (catX > 1.1f)  { catX = 1.1f;  dir = -1 }
                        if (catX < -0.1f) { catX = -0.1f; dir =  1 }
                        delay(16)
                    }
                    state = CatState.Walk
                }
                CatState.Front -> {
                    delay(Random.nextLong(4000, 10000))
                    state = CatState.Idle
                }
                CatState.Sleep -> {
                    idleWait(Random.nextLong(12000, 28000), rate, 5) { state = CatState.Run }
                    if (state == CatState.Sleep) state = CatState.Idle
                }
            }
        }
    }

    Canvas(modifier = modifier.fillMaxWidth().height(52.dp)) {
        val x = catX * size.width
        val y = size.height * 0.6f
        when (state) {
            CatState.Run   -> sideCat(x, y, frame, dir, fast = true)
            CatState.Walk  -> sideCat(x, y, frame, dir, fast = false)
            CatState.Loaf  -> loafCat(x, y, frame)
            CatState.Idle  -> idleCat(x, y, frame)
            CatState.Front -> frontCat(x, y, frame)
            CatState.Sleep -> sleepCat(x, y, frame, zProg)
        }
    }
}

private suspend fun idleWait(ms: Long, rate: State<Int>, threshold: Int, onTrigger: () -> Unit) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) {
        if (rate.value >= threshold) { onTrigger(); return }
        delay(300)
    }
}

private fun pick(vararg states: CatState) = states[Random.nextInt(states.size)]

// ── 옆모습 (달리기 / 걷기) ─────────────────────────────────────────────────

private fun DrawScope.sideCat(cx: Float, cy: Float, frame: Int, dir: Int, fast: Boolean) {
    if (dir < 0) scale(-1f, 1f, Offset(cx, cy)) { sideCatBody(cx, cy, frame, fast) }
    else sideCatBody(cx, cy, frame, fast)
}

private fun DrawScope.sideCatBody(cx: Float, cy: Float, frame: Int, fast: Boolean) {
    val s = 13.dp.toPx(); val sw = 2.3f.dp.toPx()

    // 꼬리
    val tailHigh = frame % 2 == 0
    drawPath(Path().apply {
        moveTo(cx - s * 0.9f, cy - s * 0.05f)
        quadraticBezierTo(cx - s * 1.65f, cy - s * (if (tailHigh) 0.62f else 0.26f),
            cx - s * 1.35f, cy - s * (if (tailHigh) 1.25f else 0.85f))
    }, CAT_BODY, style = Stroke(sw, cap = StrokeCap.Round))

    // 몸통
    drawOval(CAT_BODY, Offset(cx - s, cy - s * 0.43f), Size(s * 2f, s * 0.86f))
    // 머리
    drawCircle(CAT_BODY, s * 0.55f, Offset(cx + s * 0.85f, cy - s * 0.22f))
    // 귀
    sideEar(cx, cy, s, s * 0.52f); sideEar(cx, cy, s, s * 0.87f)
    // 눈
    drawCircle(CAT_DARK, s * 0.1f, Offset(cx + s * 0.96f, cy - s * 0.29f))
    drawCircle(Color.White, s * 0.033f, Offset(cx + s * 1.0f, cy - s * 0.33f))
    // 코 & 수염
    drawCircle(CAT_PINK, s * 0.065f, Offset(cx + s * 1.12f, cy - s * 0.12f))
    val ww = 1.2f.dp.toPx()
    drawLine(CAT_WHISK, Offset(cx + s * 1.08f, cy - s * 0.17f), Offset(cx + s * 1.52f, cy - s * 0.25f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 1.08f, cy - s * 0.09f), Offset(cx + s * 1.52f, cy - s * 0.04f), ww)

    // 다리
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

    // 식빵 몸통
    drawOval(CAT_BODY, Offset(cx - s * 0.85f, cy - s * 0.22f), Size(s * 1.7f, s * 0.78f))
    // 머리
    drawCircle(CAT_BODY, s * 0.52f, Offset(cx, cy - s * 0.52f))
    // 귀
    frontEar(cx, cy, s, -1f); frontEar(cx, cy, s, 1f)

    // 눈 (깜빡임)
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
    // 코
    drawCircle(CAT_PINK, s * 0.065f, Offset(cx, cy - s * 0.37f))

    // 앞발 (살짝 보임)
    drawOval(CAT_INNER, Offset(cx - s * 0.55f, cy + s * 0.5f), Size(s * 0.42f, s * 0.17f))
    drawOval(CAT_INNER, Offset(cx + s * 0.13f, cy + s * 0.5f), Size(s * 0.42f, s * 0.17f))

    // 꼬리 끝 살짝 나옴 (실룩실룩)
    val ty = cy + (if (frame % 4 < 2) s * 0.06f else -s * 0.06f)
    drawLine(CAT_BODY, Offset(cx + s * 0.85f, cy + s * 0.1f), Offset(cx + s * 1.22f, ty), sw, StrokeCap.Round)
}

// ── 앉아있기 (side, 가만히) ──────────────────────────────────────────────────

private fun DrawScope.idleCat(cx: Float, cy: Float, frame: Int) {
    val s = 13.dp.toPx(); val sw = 2.3f.dp.toPx()

    // 앉은 몸통
    drawOval(CAT_BODY, Offset(cx - s * 0.72f, cy - s * 0.12f), Size(s * 1.44f, s * 0.72f))
    // 머리 (약간 오른쪽으로)
    drawCircle(CAT_BODY, s * 0.52f, Offset(cx + s * 0.14f, cy - s * 0.5f))
    // 귀
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

    // 눈 (좌우로 시선 이동)
    val look = if (frame / 4 % 2 == 0) s * 0.03f else -s * 0.03f
    drawCircle(CAT_DARK, s * 0.1f, Offset(cx + s * 0.2f + look, cy - s * 0.52f))
    drawCircle(Color.White, s * 0.032f, Offset(cx + s * 0.24f + look, cy - s * 0.56f))

    // 코 & 수염
    drawCircle(CAT_PINK, s * 0.065f, Offset(cx + s * 0.35f, cy - s * 0.35f))
    val ww = 1.2f.dp.toPx()
    drawLine(CAT_WHISK, Offset(cx + s * 0.32f, cy - s * 0.38f), Offset(cx + s * 0.7f, cy - s * 0.45f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 0.32f, cy - s * 0.3f), Offset(cx + s * 0.7f, cy - s * 0.26f), ww)

    // 꼬리 말려있음
    drawPath(Path().apply {
        moveTo(cx - s * 0.72f, cy + s * 0.1f)
        quadraticBezierTo(cx - s * 1.1f, cy + s * 0.58f, cx - s * 0.1f, cy + s * 0.6f)
    }, CAT_BODY, style = Stroke(sw, cap = StrokeCap.Round))

    // 발
    drawOval(CAT_BODY, Offset(cx - s * 0.55f, cy + s * 0.5f), Size(s * 0.38f, s * 0.16f))
    drawOval(CAT_BODY, Offset(cx + s * 0.1f, cy + s * 0.5f), Size(s * 0.38f, s * 0.16f))
}

// ── 정면 보기 ────────────────────────────────────────────────────────────────

private fun DrawScope.frontCat(cx: Float, cy: Float, frame: Int) {
    val s = 13.dp.toPx()

    // 몸통
    drawOval(CAT_BODY, Offset(cx - s * 0.7f, cy - s * 0.06f), Size(s * 1.4f, s * 0.66f))
    // 머리
    drawCircle(CAT_BODY, s * 0.62f, Offset(cx, cy - s * 0.56f))
    // 귀
    frontEar(cx, cy, s, -1f); frontEar(cx, cy, s, 1f)

    // 눈 (윙크)
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

    // 코 & 입
    drawCircle(CAT_PINK, s * 0.075f, Offset(cx, cy - s * 0.38f))
    drawLine(CAT_DARK, Offset(cx, cy - s * 0.31f), Offset(cx - s * 0.13f, cy - s * 0.22f), 1.5f.dp.toPx(), StrokeCap.Round)
    drawLine(CAT_DARK, Offset(cx, cy - s * 0.31f), Offset(cx + s * 0.13f, cy - s * 0.22f), 1.5f.dp.toPx(), StrokeCap.Round)

    // 수염 (양쪽으로)
    val ww = 1.3f.dp.toPx()
    drawLine(CAT_WHISK, Offset(cx - s * 0.08f, cy - s * 0.4f), Offset(cx - s * 0.68f, cy - s * 0.48f), ww)
    drawLine(CAT_WHISK, Offset(cx - s * 0.08f, cy - s * 0.32f), Offset(cx - s * 0.68f, cy - s * 0.28f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 0.08f, cy - s * 0.4f), Offset(cx + s * 0.68f, cy - s * 0.48f), ww)
    drawLine(CAT_WHISK, Offset(cx + s * 0.08f, cy - s * 0.32f), Offset(cx + s * 0.68f, cy - s * 0.28f), ww)

    // 앞발
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

    // 누운 몸통
    drawOval(CAT_BODY, Offset(cx - s * 1.15f, cy - s * 0.36f), Size(s * 2.3f, s * 0.72f))
    // 머리 (오른쪽)
    drawCircle(CAT_BODY, s * 0.5f, Offset(cx + s * 0.9f, cy - s * 0.18f))
    // 귀
    drawPath(Path().apply {
        moveTo(cx + s * 0.58f, cy - s * 0.55f); lineTo(cx + s * 0.71f, cy - s * 0.9f)
        lineTo(cx + s * 0.86f, cy - s * 0.55f); close()
    }, CAT_BODY)
    drawPath(Path().apply {
        moveTo(cx + s * 0.92f, cy - s * 0.55f); lineTo(cx + s * 1.05f, cy - s * 0.9f)
        lineTo(cx + s * 1.18f, cy - s * 0.55f); close()
    }, CAT_BODY)

    // 감은 눈
    val arc = Stroke(1.8f.dp.toPx(), cap = StrokeCap.Round)
    drawArc(CAT_DARK, 200f, -160f, false,
        Offset(cx + s * 0.7f, cy - s * 0.29f), Size(s * 0.24f, s * 0.14f), style = arc)
    drawArc(CAT_DARK, 200f, -160f, false,
        Offset(cx + s * 0.88f, cy - s * 0.29f), Size(s * 0.24f, s * 0.14f), style = arc)

    // 꼬리 말려있음
    drawPath(Path().apply {
        moveTo(cx - s * 1.15f, cy - s * 0.05f)
        quadraticBezierTo(cx - s * 1.6f, cy + s * 0.42f, cx - s * 0.8f, cy + s * 0.44f)
    }, CAT_BODY, style = Stroke(sw, cap = StrokeCap.Round))

    // 발
    drawOval(CAT_INNER, Offset(cx + s * 0.28f, cy + s * 0.27f), Size(s * 0.35f, s * 0.14f))
    drawOval(CAT_INNER, Offset(cx + s * 0.68f, cy + s * 0.27f), Size(s * 0.35f, s * 0.14f))

    // ZZZ
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
