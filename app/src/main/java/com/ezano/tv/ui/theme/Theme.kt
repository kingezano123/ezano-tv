package com.ezano.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ezano.tv.R

object EzColors {
    val Bg = Color(0xFF07080C)
    val Bg2 = Color(0xFF0D0F17)
    val Surface = Color(0xFF151824)
    val SurfaceHi = Color(0xFF1E2232)
    val SurfaceFocus = Color(0xFF2A2F44)
    val Stroke = Color(0x1FFFFFFF)
    val TextPrimary = Color(0xFFF5F6FA)
    val TextSecondary = Color(0xFFB4B8C8)
    val TextTertiary = Color(0xFF7A7F94)
    val Accent = Color(0xFF7C5CFF)
    val Accent2 = Color(0xFF3DD5F3)
    val Live = Color(0xFFFF3B5C)
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)
    val Scrim = Color(0xCC07080C)

    val AccentGradient = Brush.linearGradient(listOf(Accent, Accent2))
    val AccentGradientH = Brush.horizontalGradient(listOf(Accent, Accent2))
    /** Bottom-to-top fade used over hero backdrops. */
    val BottomScrim = Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color(0x9907080C), 1f to Bg)
    /** Left-to-right fade used over hero backdrops so text stays readable. */
    val LeftScrim = Brush.horizontalGradient(0f to Bg, 0.35f to Color(0xD907080C), 0.65f to Color(0x3307080C), 1f to Color.Transparent)
}

val Jakarta = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_medium, FontWeight.Medium),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
    Font(R.font.jakarta_extrabold, FontWeight.ExtraBold),
)

/** Text styles sized for 10-foot viewing (TV renders at ~960x540dp). */
object EzType {
    val Display = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.02).em, color = EzColors.TextPrimary)
    val H1 = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.01).em, color = EzColors.TextPrimary)
    val H2 = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, color = EzColors.TextPrimary)
    val H3 = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp, color = EzColors.TextPrimary)
    val Title = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, color = EzColors.TextPrimary)
    val Body = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, color = EzColors.TextSecondary)
    val BodySmall = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp, color = EzColors.TextSecondary)
    val Label = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.04.em, color = EzColors.TextSecondary)
    val Caption = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, color = EzColors.TextTertiary)
}

@Composable
fun EzanoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = EzColors.Accent,
            secondary = EzColors.Accent2,
            background = EzColors.Bg,
            surface = EzColors.Surface,
            onPrimary = Color.White,
            onBackground = EzColors.TextPrimary,
            onSurface = EzColors.TextPrimary,
            error = EzColors.Live,
        ),
        typography = Typography(
            bodyLarge = EzType.Body,
            bodyMedium = EzType.Body,
            bodySmall = EzType.BodySmall,
            titleLarge = EzType.H2,
            titleMedium = EzType.H3,
            titleSmall = EzType.Title,
            labelLarge = EzType.Label,
            labelMedium = EzType.Label,
            labelSmall = EzType.Caption,
            headlineLarge = EzType.H1,
            headlineMedium = EzType.H2,
            displayLarge = EzType.Display,
        ),
        content = content,
    )
}
