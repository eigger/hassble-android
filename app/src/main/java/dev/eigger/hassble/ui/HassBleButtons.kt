package dev.eigger.hassble.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eigger.hassble.R

object HassBleShapes {
    val Button = RoundedCornerShape(8.dp)
    val ButtonLarge = RoundedCornerShape(12.dp)
    val Dialog = RoundedCornerShape(16.dp)
    val Card = RoundedCornerShape(12.dp)
    val CardLarge = RoundedCornerShape(16.dp)
    val Pill = RoundedCornerShape(28.dp)
}

object HassBleButtonDefaults {
    private val secondaryContainer = Color.White.copy(alpha = 0.08f)

    @Composable
    fun primaryColors() = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
    )

    @Composable
    fun secondaryColors() = ButtonDefaults.buttonColors(
        containerColor = secondaryContainer,
        contentColor = Color.White,
        disabledContainerColor = secondaryContainer.copy(alpha = 0.5f),
        disabledContentColor = Color.White.copy(alpha = 0.4f),
    )

    @Composable
    fun accentColors() = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        contentColor = MaterialTheme.colorScheme.primary,
        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    )

    @Composable
    fun dangerColors() = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = Color.White,
    )

    @Composable
    fun dangerOutlinedColors() = ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.error,
    )

    @Composable
    fun dangerTintColors() = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.error,
    )

    @Composable
    fun cancelTextColors() = ButtonDefaults.textButtonColors(
        contentColor = Color.Gray,
    )

    @Composable
    fun linkTextColors() = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
    )

    val contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    val compactPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
}

@Composable
fun HassPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    large: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = if (large) HassBleShapes.ButtonLarge else HassBleShapes.Button,
        colors = HassBleButtonDefaults.primaryColors(),
        contentPadding = HassBleButtonDefaults.contentPadding,
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = if (large) 14.sp else 12.sp)
    }
}

@Composable
fun HassSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = HassBleShapes.Button,
        colors = HassBleButtonDefaults.secondaryColors(),
        contentPadding = if (compact) HassBleButtonDefaults.compactPadding else HassBleButtonDefaults.contentPadding,
    ) {
        Text(
            text,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (compact) 11.sp else 12.sp,
            maxLines = 2,
        )
    }
}

@Composable
fun HassAccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        shape = HassBleShapes.Button,
        colors = HassBleButtonDefaults.accentColors(),
        border = border,
        contentPadding = HassBleButtonDefaults.compactPadding,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Text(text, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
fun HassDangerTintButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = HassBleShapes.Button,
        colors = HassBleButtonDefaults.dangerTintColors(),
        contentPadding = HassBleButtonDefaults.compactPadding,
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun HassDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = HassBleShapes.Button,
        colors = HassBleButtonDefaults.dangerColors(),
        contentPadding = HassBleButtonDefaults.contentPadding,
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun HassDangerOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = HassBleShapes.Button,
        colors = HassBleButtonDefaults.dangerOutlinedColors(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        contentPadding = HassBleButtonDefaults.compactPadding,
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun HassCancelButton(
    text: String = stringResource(R.string.cancel),
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = HassBleButtonDefaults.cancelTextColors(),
        contentPadding = HassBleButtonDefaults.compactPadding,
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
fun HassLinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = HassBleButtonDefaults.linkTextColors(),
        contentPadding = HassBleButtonDefaults.compactPadding,
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 다이얼로그 하단: 주요 액션(왼쪽) + 취소(오른쪽) */
@Composable
fun HassDialogActionRow(
    primaryLabel: String,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    cancelLabel: String = stringResource(R.string.cancel),
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HassPrimaryButton(
            text = primaryLabel,
            onClick = onPrimary,
            enabled = primaryEnabled,
        )
        HassCancelButton(text = cancelLabel, onClick = onCancel)
    }
}

/** 뒤로가기 + 취소 + 주요 액션 (BLE 마법사 등) */
@Composable
fun HassDialogActionRowWithBack(
    backLabel: String,
    onBack: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    cancelLabel: String = stringResource(R.string.cancel),
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HassLinkButton(text = backLabel, onClick = onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            HassCancelButton(text = cancelLabel, onClick = onCancel)
            HassPrimaryButton(text = primaryLabel, onClick = onPrimary, enabled = primaryEnabled)
        }
    }
}

/** 선택형 칩 (export 모드, 필터 등) */
@Composable
fun HassToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = if (selected) HassBleButtonDefaults.accentColors() else HassBleButtonDefaults.secondaryColors()
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = HassBleShapes.Button,
        contentPadding = HassBleButtonDefaults.compactPadding,
        colors = colors,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)) else null,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 보조 액션 행: secondary 버튼 + 취소 */
@Composable
fun HassDialogSecondaryActionRow(
    modifier: Modifier = Modifier,
    onCancel: () -> Unit,
    cancelLabel: String = stringResource(R.string.cancel),
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions()
        HassCancelButton(text = cancelLabel, onClick = onCancel)
    }
}
