package com.uma.workbench.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object WorkbenchColors {
    val bg = Color(0xFF1E1E2E)
    val bgSecondary = Color(0xFF252535)
    val bgSurface = Color(0xFF2D2D3F)
    val bgHover = Color(0xFF353548)
    val border = Color(0xFF3D3D52)
    val textPrimary = Color(0xFFE0E0E8)
    val textSecondary = Color(0xFF8888A0)
    val textMuted = Color(0xFF5C5C70)
    val accent = Color(0xFF7B68EE)
    val accentDim = Color(0xFF4A3F6B)
    val accentBright = Color(0xFF9D8CFF)
    val success = Color(0xFF4EC9B0)
    val warning = Color(0xFFCE9178)
    val error = Color(0xFFF44747)
    val info = Color(0xFF569CD6)
    val syntaxKeyword = Color(0xFFC586C0)
    val syntaxString = Color(0xFFCE9178)
    val syntaxNumber = Color(0xFFB5CEA8)
    val syntaxComment = Color(0xFF6A9955)
    val syntaxFunction = Color(0xFFDCDCAA)
    val syntaxType = Color(0xFF4EC9B0)
    val syntaxVariable = Color(0xFF9CDCFE)
    val syntaxProperty = Color(0xFF9CDCFE)
    val syntaxPunctuation = Color(0xFF808080)
}

@Composable
fun WorkbenchTheme(content: @Composable () -> Unit) {
    val c = WorkbenchColors
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = c.accent, onPrimary = Color.White,
            primaryContainer = c.accentDim, onPrimaryContainer = c.textPrimary,
            secondary = c.info, onSecondary = Color.White,
            tertiary = c.success, onTertiary = Color.Black,
            background = c.bg, onBackground = c.textPrimary,
            surface = c.bgSecondary, onSurface = c.textPrimary,
            surfaceVariant = c.bgSurface, onSurfaceVariant = c.textSecondary,
            outline = c.border, outlineVariant = c.textMuted,
            error = c.error, onError = Color.White
        ),
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 14.sp, color = c.textPrimary),
            bodyMedium = TextStyle(fontSize = 13.sp, color = c.textPrimary),
            bodySmall = TextStyle(fontSize = 12.sp, color = c.textSecondary),
            labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 12.sp, color = c.textSecondary),
            labelSmall = TextStyle(fontSize = 11.sp, color = c.textMuted),
            titleLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary),
            titleMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.textPrimary),
            titleSmall = TextStyle(fontSize = 13.sp, color = c.textSecondary),
        ),
        content = content
    )
}
