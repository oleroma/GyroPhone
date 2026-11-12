package com.oleroma.gyrophone.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The edit menu's Material icons (paths from Material Icons, Apache 2.0), without the icons library, and our own line icons. */
internal object EditIcons {
    val Lock = icon(
        "Lock",
        "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12" +
            "c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 " +
            "-0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z",
    )
    val LockOpen = icon(
        "LockOpen",
        "M12,17c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM18,8h-1V6c0,-2.76 -2.24,-5 -5,-5" +
            "S7,3.24 7,6h1.9c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2H6c-1.1,0 -2,0.9 -2,2v10" +
            "c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM18,20H6V10h12v10z",
    )
    val Undo = icon(
        "Undo",
        "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 " +
            "3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
    )
    val Redo = icon(
        "Redo",
        "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 " +
            "7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z",
    )

    /** Settings of the whole app (the pad's App capsule): a gear, drawn in lines. */
    val Gear = strokeIcon("Gear", "M9.81,4.93L10.09,2.59L13.91,2.59L14.19,4.93A7.4,7.4 0 0,1 15.45,5.45L15.45,5.45L17.30,4.00L20.00,6.70L18.55,8.55A7.4,7.4 0 0,1 19.07,9.81L19.07,9.81L21.41,10.09L21.41,13.91L19.07,14.19A7.4,7.4 0 0,1 18.55,15.45L18.55,15.45L20.00,17.30L17.30,20.00L15.45,18.55A7.4,7.4 0 0,1 14.19,19.07L14.19,19.07L13.91,21.41L10.09,21.41L9.81,19.07A7.4,7.4 0 0,1 8.55,18.55L8.55,18.55L6.70,20.00L4.00,17.30L5.45,15.45A7.4,7.4 0 0,1 4.93,14.19L4.93,14.19L2.59,13.91L2.59,10.09L4.93,9.81A7.4,7.4 0 0,1 5.45,8.55L5.45,8.55L4.00,6.70L6.70,4.00L8.55,5.45A7.4,7.4 0 0,1 9.81,4.93ZM15.2,12a3.2,3.2 0 1,1 -6.4,0a3.2,3.2 0 1,1 6.4,0Z")

    /** Settings that tune something (Defaults, a workspace's settings): three sliders, drawn in lines. */
    val Tune = strokeIcon("Tune", "M3,6H12.8M17.2,6H21M12.8,6a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0M3,12H5.8M10.2,12H21M5.8,12a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0M3,18H14.8M19.2,18H21M14.8,18a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0")

    private fun strokeIcon(name: String, path: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    private fun icon(name: String, path: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
