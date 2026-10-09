package com.abdownloadmanager.resources.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// WebDM placeholder app icon (rounded square with a download arrow).
// Replace with the final WebDM logo; keep it in sync with desktop/app/icons/icon.ico.
val ABDMIcons.AppIcon: ImageVector
    get() {
        if (_AppIcon != null) {
            return _AppIcon!!
        }
        _AppIcon = ImageVector.Builder(
            name = "AppIcon",
            defaultWidth = 48.dp,
            defaultHeight = 48.dp,
            viewportWidth = 48f,
            viewportHeight = 48f
        ).apply {
            // background
            path(
                fill = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFF1E88E5),
                        1f to Color(0xFF1565C0)
                    ),
                    start = Offset(24f, 0f),
                    end = Offset(24f, 48f)
                )
            ) {
                moveTo(10f, 0f)
                horizontalLineTo(38f)
                arcTo(10f, 10f, 0f, false, true, 48f, 10f)
                verticalLineTo(38f)
                arcTo(10f, 10f, 0f, false, true, 38f, 48f)
                horizontalLineTo(10f)
                arcTo(10f, 10f, 0f, false, true, 0f, 38f)
                verticalLineTo(10f)
                arcTo(10f, 10f, 0f, false, true, 10f, 0f)
                close()
            }
            // arrow
            path(fill = SolidColor(Color.White)) {
                moveTo(20f, 8f)
                horizontalLineTo(28f)
                verticalLineTo(22f)
                horizontalLineTo(35f)
                lineTo(24f, 33f)
                lineTo(13f, 22f)
                horizontalLineTo(20f)
                close()
            }
            // bar
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 36f)
                horizontalLineTo(36f)
                verticalLineTo(40f)
                horizontalLineTo(12f)
                close()
            }
        }.build()

        return _AppIcon!!
    }

@Suppress("ObjectPropertyName")
private var _AppIcon: ImageVector? = null
