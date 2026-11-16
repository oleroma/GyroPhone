package com.oleroma.gyrophone.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.NamedSens
import com.oleroma.gyrophone.core.model.SensLink
import kotlin.math.roundToInt

/** The Scale slider's step. */
private const val SCALE_STEP = 0.05f

/** What a gyro axis's sensitivity can follow besides its own value: the shared one, the other axes, the workspace's named gyro ones. */
internal fun gyroLinkChoices(axis: MotionAxis): List<String> =
    listOf(SensLink.SHARED) + MotionAxis.shown.filter { it != axis }.map { SensLink.axis(it) } +
        NamedSens.of(false).map { SensLink.named(it.id) }

/** What a Move's sensitivity can follow: the shared one, the finger count's other Move, the workspace's named touch ones. */
internal fun moveLinkChoices(x: Boolean): List<String> =
    listOf(SensLink.SHARED, if (x) SensLink.Y else SensLink.X) + NamedSens.of(true).map { SensLink.named(it.id) }

/** What a button's touchpad speed while held can follow: the workspace's named touch ones. */
internal fun touchLinkChoices(): List<String> = NamedSens.of(true).map { SensLink.named(it.id) }

/**
 * A sensitivity row (a gyro axis, a button's axis while held, a Move X / Y, a button's touchpad
 * speed while held): it takes one thing, either its own value ([exact], the usual slider) or
 * another sensitivity to follow ([link], in effect at this level, own or inherited): one of
 * [choices] ([SensLink.from]) times a Scale. [valueOf] is what a link comes to now, shown by
 * [show]; [touch] names the named ones Touch sens. [onLink] picks a link (null: its own value again).
 */
@Composable
internal fun LinkedSens(
    key: String,
    label: String,
    link: SensLink?,
    choices: List<String>,
    touch: Boolean,
    valueOf: (SensLink) -> Float,
    show: (Float) -> String,
    overridden: Boolean,
    onReset: () -> Unit,
    hint: String,
    onLink: (SensLink?) -> Unit,
    exact: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OverrideRow(
            "$label follows", overridden, onReset,
            "$hint Own value: set it here. Or follow another sensitivity at a Scale of it: " +
                (if (touch) "the shared Sensitivity, the other Move, or a Touch sens a slider sets " else "the shared Sensitivity, another axis, or a Gyro sens a slider sets ") +
                "(add one with + Gyro sens or + Touch sens in the mapping editor library's Sensitivity tab, then map it onto a slider). It follows that value as it changes, worked out only when it changes.",
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectChip("Own value", link == null) { if (link != null) onLink(null) }
                for (from in if (link != null && link.from !in choices) choices + link.from else choices) {
                    val l = SensLink(from)
                    SelectChip(l.source(touch), link?.from == from) {
                        if (link?.from != from) onLink(SensLink(from, link?.scale ?: 1f))
                    }
                }
            }
        }
        if (link == null) exact()
        else ScaleSlider(key + link.from, label, link, touch, valueOf, show, overridden, onReset) { f -> onLink(link.copy(scale = f)) }
    }
}

/** A link's Scale: 0.1 to 4, with what it comes to. */
@Composable
private fun ScaleSlider(
    key: String,
    label: String,
    link: SensLink,
    touch: Boolean,
    valueOf: (SensLink) -> Float,
    show: (Float) -> String,
    overridden: Boolean,
    onReset: () -> Unit,
    onDone: (Float) -> Unit,
) {
    var v by remember(key, link.scale) { mutableStateOf(link.scale) }
    val now = valueOf(link.copy(scale = v))
    OverrideRow(
        "$label = ${link.source(touch)} × ${SensLink.fmt(v)} = ${show(now)}", overridden, onReset,
        "Scale: this times what it follows (1 = the same).",
    ) {
        Slider(
            value = v.coerceIn(SensLink.MIN_SCALE, SensLink.MAX_SCALE),
            onValueChange = { v = ((it / SCALE_STEP).roundToInt() * SCALE_STEP).coerceIn(SensLink.MIN_SCALE, SensLink.MAX_SCALE) },
            valueRange = SensLink.MIN_SCALE..SensLink.MAX_SCALE,
            onValueChangeFinished = { onDone(v) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
