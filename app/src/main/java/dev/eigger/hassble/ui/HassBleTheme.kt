package dev.eigger.hassble.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 앱 공통 다크 테마. MainActivity와 Tasker 편집 화면이 같이 쓴다. */
@Composable
fun HassBleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF9E86FF),
            onPrimary = Color.Black,
            secondary = Color(0xFF00E676),
            background = Color(0xFF121214),
            surface = Color(0xFF1E1E22),
            error = Color(0xFFFF5252)
        ),
        shapes = Shapes(
            small = HassBleShapes.Button,
            medium = HassBleShapes.ButtonLarge,
            large = HassBleShapes.CardLarge,
        ),
        content = content,
    )
}
