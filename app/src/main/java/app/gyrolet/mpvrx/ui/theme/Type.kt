/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.gyrolet.mpvrx.R
import java.util.Locale

val SystemTypography = Typography()

private const val GoogleSansRoundedAxis = 100f

@OptIn(ExperimentalTextApi::class)
val GoogleSansRounded =
  FontFamily(
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Thin,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Thin.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.ExtraLight,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.ExtraLight.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Light,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Light.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Normal,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Normal.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Medium,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Medium.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.SemiBold,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.SemiBold.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Bold,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Bold.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.ExtraBold,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.ExtraBold.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Black,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Black.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
  )

fun typographyWithFontFamily(fontFamily: FontFamily): Typography =
  SystemTypography.run {
    copy(
      displayLarge = displayLarge.copy(fontFamily = fontFamily),
      displayMedium = displayMedium.copy(fontFamily = fontFamily),
      displaySmall = displaySmall.copy(fontFamily = fontFamily),
      headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
      headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
      headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
      titleLarge = titleLarge.copy(fontFamily = fontFamily),
      titleMedium = titleMedium.copy(fontFamily = fontFamily),
      titleSmall = titleSmall.copy(fontFamily = fontFamily),
      bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
      bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
      bodySmall = bodySmall.copy(fontFamily = fontFamily),
      labelLarge = labelLarge.copy(fontFamily = fontFamily),
      labelMedium = labelMedium.copy(fontFamily = fontFamily),
      labelSmall = labelSmall.copy(fontFamily = fontFamily),
    )
  }

// Use PixelPlayer's rounded Google Sans Flex typography app-wide by default.
val AppTypography = typographyWithFontFamily(GoogleSansRounded)

val LocalAppFontFamily = staticCompositionLocalOf { GoogleSansRounded }

@Composable
fun fontFamilyForText(text: String): FontFamily =
  if (text.requiresSystemFontFallback()) FontFamily.SansSerif else LocalAppFontFamily.current

fun localeRequiresSystemFont(locale: Locale): Boolean =
  locale.getDisplayName(locale).requiresSystemFontFallback()

private fun String.requiresSystemFontFallback(): Boolean {
  var index = 0
  while (index < length) {
    val codePoint = Character.codePointAt(this, index)
    when (Character.UnicodeScript.of(codePoint)) {
      Character.UnicodeScript.LATIN,
      Character.UnicodeScript.COMMON,
      Character.UnicodeScript.INHERITED,
      -> Unit
      else -> return true
    }
    index += Character.charCount(codePoint)
  }
  return false
}

// ═══════════════════════════════════════════════════════════
// Material 3 Expressive Typography Extensions
// Bumps font weights one step heavier for expressive emphasis
// ═══════════════════════════════════════════════════════════

data class EmphasizedTypography(
  val displayLarge: TextStyle,
  val displayMedium: TextStyle,
  val displaySmall: TextStyle,
  val headlineLarge: TextStyle,
  val headlineMedium: TextStyle,
  val headlineSmall: TextStyle,
  val titleLarge: TextStyle,
  val titleMedium: TextStyle,
  val titleSmall: TextStyle,
  val bodyLarge: TextStyle,
  val bodyMedium: TextStyle,
  val bodySmall: TextStyle,
  val labelLarge: TextStyle,
  val labelMedium: TextStyle,
  val labelSmall: TextStyle,
)

fun emphasizedTypography(typography: Typography): EmphasizedTypography =
  EmphasizedTypography(
    displayLarge = typography.displayLarge.copy(fontWeight = FontWeight.Black),
    displayMedium = typography.displayMedium.copy(fontWeight = FontWeight.Black),
    displaySmall = typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
    headlineLarge = typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
    headlineSmall = typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = typography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
    bodyMedium = typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
    bodySmall = typography.bodySmall.copy(fontWeight = FontWeight.Medium),
    labelLarge = typography.labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMedium = typography.labelMedium.copy(fontWeight = FontWeight.Bold),
    labelSmall = typography.labelSmall.copy(fontWeight = FontWeight.Bold),
  )

val AppEmphasizedTypography = emphasizedTypography(AppTypography)

val LocalEmphasizedTypography = staticCompositionLocalOf { AppEmphasizedTypography }
