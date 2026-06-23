package dev.eigger.hassble.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun CatRunOverlay(dataRate: Int, modifier: Modifier = Modifier) {
    val catX = remember { Animatable(-0.12f) }
    var frame by remember { mutableIntStateOf(0) }

    val crossMs by rememberUpdatedState((4200 / (1f + dataRate * 0.35f)).toInt().coerceIn(700, 4200))
    val frameMs by rememberUpdatedState((170L / (1 + dataRate / 3)).coerceIn(55L, 170L))

    LaunchedEffect(Unit) {
        while (true) {
            catX.animateTo(1.12f, tween(crossMs, easing = LinearEasing))
            catX.snapTo(-0.12f)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(frameMs)
            frame = (frame + 1) % 4
        }
    }

    Canvas(modifier = modifier.fillMaxWidth().height(44.dp)) {
        drawCat(catX.value * size.width, size.height * 0.6f, frame)
    }
}

private fun DrawScope.drawCat(cx: Float, cy: Float, frame: Int) {
    val s = 13.dp.toPx()
    val sw = 2.3f.dp.toPx()
    val body = Color(0xFFFF8C42)
    val inner = Color(0xFFFFD580)
    val dark = Color(0xFF1A1A1A)
    val pink = Color(0xFFFF9BB5)
    val whisker = Color(0xFFBBBBBB)

    // 꼬리 (위아래 흔들기)
    val tailHigh = frame < 2
    val tailPath = Path().apply {
        moveTo(cx - s * 0.9f, cy - s * 0.05f)
        quadraticBezierTo(
            cx - s * 1.65f, cy - s * (if (tailHigh) 0.6f else 0.25f),
            cx - s * 1.35f, cy - s * (if (tailHigh) 1.25f else 0.85f),
        )
    }
    drawPath(tailPath, body, style = Stroke(width = sw, cap = StrokeCap.Round))

    // 몸통
    drawOval(body, topLeft = Offset(cx - s, cy - s * 0.43f), size = Size(s * 2f, s * 0.86f))

    // 머리
    drawCircle(body, radius = s * 0.55f, center = Offset(cx + s * 0.85f, cy - s * 0.22f))

    // 귀 (왼쪽)
    drawPath(Path().apply {
        moveTo(cx + s * 0.52f, cy - s * 0.58f)
        lineTo(cx + s * 0.63f, cy - s * 0.99f)
        lineTo(cx + s * 0.78f, cy - s * 0.58f)
        close()
    }, body)
    drawPath(Path().apply {
        moveTo(cx + s * 0.57f, cy - s * 0.63f)
        lineTo(cx + s * 0.63f, cy - s * 0.86f)
        lineTo(cx + s * 0.73f, cy - s * 0.63f)
        close()
    }, inner)

    // 귀 (오른쪽)
    drawPath(Path().apply {
        moveTo(cx + s * 0.88f, cy - s * 0.58f)
        lineTo(cx + s * 1.01f, cy - s * 0.99f)
        lineTo(cx + s * 1.15f, cy - s * 0.58f)
        close()
    }, body)
    drawPath(Path().apply {
        moveTo(cx + s * 0.91f, cy - s * 0.63f)
        lineTo(cx + s * 1.01f, cy - s * 0.86f)
        lineTo(cx + s * 1.11f, cy - s * 0.63f)
        close()
    }, inner)

    // 눈
    drawCircle(dark, radius = s * 0.1f, center = Offset(cx + s * 0.96f, cy - s * 0.29f))
    drawCircle(Color.White, radius = s * 0.033f, center = Offset(cx + s * 1.0f, cy - s * 0.33f))

    // 코
    drawCircle(pink, radius = s * 0.065f, center = Offset(cx + s * 1.12f, cy - s * 0.12f))

    // 수염
    val ww = 1.2f.dp.toPx()
    drawLine(whisker, Offset(cx + s * 1.08f, cy - s * 0.16f), Offset(cx + s * 1.52f, cy - s * 0.24f), ww)
    drawLine(whisker, Offset(cx + s * 1.08f, cy - s * 0.09f), Offset(cx + s * 1.52f, cy - s * 0.04f), ww)

    // 다리 (4프레임 달리기)
    val (fSwing, bSwing) = when (frame % 4) {
        0 -> s * 0.38f to -s * 0.38f   // 앞다리 앞, 뒷다리 뒤
        1 -> s * 0.08f to -s * 0.08f   // 모임
        2 -> -s * 0.38f to s * 0.38f   // 반대 stride
        else -> -s * 0.08f to s * 0.08f
    }

    val fx = cx + s * 0.45f
    drawLine(body, Offset(fx, cy + s * 0.28f), Offset(fx + fSwing, cy + s * 0.82f), sw, StrokeCap.Round)
    drawLine(body, Offset(fx - s * 0.22f, cy + s * 0.28f), Offset(fx - s * 0.22f - fSwing * 0.55f, cy + s * 0.82f), sw, StrokeCap.Round)

    val bx = cx - s * 0.52f
    drawLine(body, Offset(bx, cy + s * 0.28f), Offset(bx + bSwing, cy + s * 0.82f), sw, StrokeCap.Round)
    drawLine(body, Offset(bx - s * 0.22f, cy + s * 0.28f), Offset(bx - s * 0.22f - bSwing * 0.55f, cy + s * 0.82f), sw, StrokeCap.Round)
}
