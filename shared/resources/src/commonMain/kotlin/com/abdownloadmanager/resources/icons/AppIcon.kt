package com.abdownloadmanager.resources.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// WebDM logo: wireframe globe with a download arrow as its central meridian.
// Mirrors assets/branding/webdm-logo.svg (viewBox 256x256); keep both in sync.
private val WebDMNavy = Color(0xFF172A46)
private val WebDMCoral = Color(0xFFFF6A3D)
private val WebDMCream = Color(0xFFFFF3E6)

val ABDMIcons.AppIcon: ImageVector
    get() {
        if (_AppIcon != null) {
            return _AppIcon!!
        }
        _AppIcon = ImageVector.Builder(
            name = "AppIcon",
            defaultWidth = 48.dp,
            defaultHeight = 48.dp,
            viewportWidth = 256f,
            viewportHeight = 256f
        ).apply {
            // tile: rect x=8 y=8 w=240 h=240 rx=56
            path(fill = SolidColor(WebDMNavy)) {
                moveTo(64f, 8f)
                horizontalLineTo(192f)
                arcTo(56f, 56f, 0f, false, true, 248f, 64f)
                verticalLineTo(192f)
                arcTo(56f, 56f, 0f, false, true, 192f, 248f)
                horizontalLineTo(64f)
                arcTo(56f, 56f, 0f, false, true, 8f, 192f)
                verticalLineTo(64f)
                arcTo(56f, 56f, 0f, false, true, 64f, 8f)
                close()
            }
            // wireframe globe: outline, meridian ellipse, equator and two latitudes
            path(
                stroke = SolidColor(WebDMCream),
                strokeLineWidth = 10f,
                strokeLineCap = StrokeCap.Round,
            ) {
                // circle cx=128 cy=118 r=78
                moveTo(50f, 118f)
                arcTo(78f, 78f, 0f, true, true, 206f, 118f)
                arcTo(78f, 78f, 0f, true, true, 50f, 118f)
                close()
                // ellipse cx=128 cy=118 rx=38 ry=78
                moveTo(90f, 118f)
                arcTo(38f, 78f, 0f, true, true, 166f, 118f)
                arcTo(38f, 78f, 0f, true, true, 90f, 118f)
                close()
                moveTo(50f, 118f)
                horizontalLineTo(206f)
                moveTo(61f, 79f)
                horizontalLineTo(195f)
                moveTo(61f, 157f)
                horizontalLineTo(195f)
            }
            // download arrow: navy outline cuts the globe lines, then the coral arrow on top
            path(
                stroke = SolidColor(WebDMNavy),
                strokeLineWidth = 16f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                arrow()
            }
            path(
                fill = SolidColor(WebDMCoral),
                stroke = SolidColor(WebDMCoral),
                strokeLineWidth = 4f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                arrow()
            }
        }.build()

        return _AppIcon!!
    }

private fun androidx.compose.ui.graphics.vector.PathBuilder.arrow() {
    moveTo(112f, 46f)
    horizontalLineTo(144f)
    verticalLineTo(146f)
    horizontalLineTo(178f)
    lineTo(128f, 210f)
    lineTo(78f, 146f)
    horizontalLineTo(112f)
    close()
}

@Suppress("ObjectPropertyName")
private var _AppIcon: ImageVector? = null
