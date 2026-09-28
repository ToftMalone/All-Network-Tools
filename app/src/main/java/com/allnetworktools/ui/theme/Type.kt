package com.allnetworktools.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.allnetworktools.R

private val Weights = listOf(300, 400, 500, 600, 700, 800)

@OptIn(ExperimentalTextApi::class)
private fun variableFamily(res: Int) = FontFamily(
    Weights.map { w ->
        Font(res, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

/** Google Sans Flex: display, headline and live metrics. */
val GoogleSansFlex = variableFamily(R.font.google_sans_flex)

/** Roboto Flex: titles, body and labels. */
val RobotoFlex = variableFamily(R.font.roboto_flex)

@OptIn(ExperimentalTextApi::class)
val SymbolsOutlined = FontFamily(
    Font(R.font.material_symbols_rounded, variationSettings = FontVariation.Settings(FontVariation.Setting("FILL", 0f))),
)

@OptIn(ExperimentalTextApi::class)
val SymbolsFilled = FontFamily(
    Font(R.font.material_symbols_rounded, variationSettings = FontVariation.Settings(FontVariation.Setting("FILL", 1f))),
)

private fun lsPx(px: Float, size: Float): TextUnit = (px / size).em

/** Google Sans Flex text style, sizes in sp matching the dp values of the mockups. */
fun gs(size: Int, line: Int = size, weight: Int = 500, tracking: Float = 0f, tnum: Boolean = false) = TextStyle(
    fontFamily = GoogleSansFlex,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = lsPx(tracking, size.toFloat()),
    fontFeatureSettings = if (tnum) "tnum" else null,
)

/** Roboto Flex text style. */
fun rf(size: Number, line: Number = size.toFloat() * 1.4f, weight: Int = 400, tracking: Float = 0f, tnum: Boolean = false) = TextStyle(
    fontFamily = RobotoFlex,
    fontSize = size.toFloat().sp,
    lineHeight = line.toFloat().sp,
    fontWeight = FontWeight(weight),
    letterSpacing = lsPx(tracking, size.toFloat()),
    fontFeatureSettings = if (tnum) "tnum" else null,
)

val AntTypography = Typography(
    displayLarge = gs(57, 64, 400),
    displayMedium = gs(45, 52, 400),
    displaySmall = gs(36, 44, 400),
    headlineLarge = gs(32, 40, 500),
    headlineMedium = gs(28, 36, 500),
    headlineSmall = gs(24, 32, 500),
    titleLarge = gs(22, 28, 500),
    titleMedium = rf(16, 24, 600),
    titleSmall = rf(14, 20, 600),
    bodyLarge = rf(16, 24, 400),
    bodyMedium = rf(14, 20, 400),
    bodySmall = rf(12, 16, 400),
    labelLarge = rf(14, 20, 600),
    labelMedium = rf(12, 16, 600),
    labelSmall = rf(11, 16, 500),
)
