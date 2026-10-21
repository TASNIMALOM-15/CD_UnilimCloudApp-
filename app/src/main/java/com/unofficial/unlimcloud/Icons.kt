package com.unofficial.unlimcloud

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser

/** Material আইকনের path data (২৪×২৪)। বড় icons-extended লাইব্রেরি এড়াতে নিজেরাই আঁকা হয়। */
object AppIcons {
    const val IMAGE = "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"
    const val FOLDER = "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"
    const val DOWNLOAD = "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
    const val UPLOAD = "M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z"
    const val ARROW_DOWN = "M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z"
    const val ARROW_UP = "M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z"
}

@Composable
fun PathIcon(
    pathData: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 24.dp
) {
    val path = remember(pathData) { PathParser.createPathFromPathData(pathData) }
    Canvas(modifier.size(iconSize)) {
        drawIntoCanvas { canvas ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = tint.toArgb()
            val native = canvas.nativeCanvas
            native.save()
            native.scale(this.size.width / 24f, this.size.height / 24f)
            native.drawPath(path, paint)
            native.restore()
        }
    }
}
