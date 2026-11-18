package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable

/**
 * A sensitivity that follows another one instead of holding its own value: [scale] times the
 * sensitivity [from] names, at the same level (a sibling) or a named one of the workspace.
 *
 * - [SHARED]: the level's shared Sensitivity (the gyro's every-axis one, the touchpad's).
 * - A gyro axis's name ([MotionAxis.YAW], [MotionAxis.PITCH], [MotionAxis.ROLL]): that axis's
 *   sensitivity in effect at the same level (for a gyro axis only).
 * - "X" / "Y": the same finger count's Move X / Move Y (for a Move only).
 * - "n<id>": one of the workspace's named sensitivities ([NamedSens], added as needed), set by a slider ([OutputTarget.GyroSens.named], [OutputTarget.TouchSens.named]);
 *   until one sets it, it is the level's shared Sensitivity.
 *
 * It is worked out into a plain number whenever something it reads changes (never per motion
 * sample), always from its source, so a chain never compounds.
 */
@Serializable
data class SensLink(val from: String, val scale: Float = 1f) {
    /** The named sensitivity it reads ([NamedSens.id]), or 0 for none. */
    val named: Int get() = namedOf(from)

    /** The gyro axis it reads, or null. */
    val axis: MotionAxis? get() = MotionAxis.entries.firstOrNull { it != MotionAxis.NONE && it.name == from }

    /** What it reads, for showing: "Yaw", "Sensitivity", "Move Y", a named one's name ([touch]: a Move's or touchpad's). */
    fun source(touch: Boolean): String = when {
        named > 0 -> NamedSens.nameOf(named, touch)
        axis != null -> axis!!.label
        from == X -> "Move X"
        from == Y -> "Move Y"
        else -> "Sensitivity"
    }

    /** It, for showing: "Yaw ×0.5" (a scale of 1 left out: "Gyro sens 1"). */
    fun label(touch: Boolean): String = source(touch) + if (scale == 1f) "" else " ×" + fmt(scale)

    companion object {
        /** [f] with at most two decimals, trailing zeros dropped. */
        fun fmt(f: Float): String = "%.2f".format(java.util.Locale.ROOT, f).trimEnd('0').trimEnd('.')

        const val SHARED = "sens"
        const val X = "X"
        const val Y = "Y"

        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 4f

        fun named(n: Int): String = "n$n"

        fun namedOf(from: String): Int =
            if (from.length >= 2 && from[0] == 'n') from.substring(1).toIntOrNull()?.takeIf { it > 0 } ?: 0 else 0

        fun axis(a: MotionAxis): String = a.name

        /** A factor saved by the old Scale parent switch: read as that share of the shared Sensitivity. */
        fun legacy(scale: Float?): SensLink? = scale?.let { SensLink(SHARED, it) }

        /** Nothing named is set: every named sensitivity is its level's shared one. */
        val NO_NAMED: (Int) -> Float? = { null }
    }
}

/**
 * One of a workspace's named sensitivities ([Workspace.namedSens]): a gyro one (counts per degree)
 * or a touch one ([touch], a Move sensitivity). [id] never changes, so renaming breaks nothing;
 * a delete removes every use of its id in the workspace ([delete]), and the id is never given
 * again ([Workspace.namedSensLastId]), so a use elsewhere never follows a later one.
 */
@Serializable
data class NamedSens(val id: Int, val name: String, val touch: Boolean = false) {
    companion object {
        /**
         * The active workspace's named sensitivities, for showing chips and links where the
         * workspace isn't at hand ([com.oleroma.gyrophone.core.engine.MappingEngine] keeps it current).
         */
        @Volatile
        var active: List<NamedSens> = emptyList()

        /** The active workspace's gyro ([touch] false) or touch named sensitivities. */
        fun of(touch: Boolean): List<NamedSens> = active.filter { it.touch == touch }

        /** Named sensitivity [id]'s name, or "Gyro sens #3" / "Touch sens #3" for one this workspace doesn't have. */
        fun nameOf(id: Int, touch: Boolean): String =
            active.firstOrNull { it.id == id }?.name ?: ((if (touch) "Touch sens #" else "Gyro sens #") + id)

        /** [w] with a new named sensitivity ([touch] or gyro), "Sens N" with the first free N; and its id. */
        fun add(w: Workspace, touch: Boolean): Pair<Workspace, Int> {
            val id = maxOf(w.namedSens.maxOfOrNull { it.id } ?: 0, w.namedSensLastId) + 1
            val taken = w.namedSens.mapTo(HashSet()) { it.name }
            var n = 1
            while ("Sens $n" in taken) n++
            return w.copy(namedSens = w.namedSens + NamedSens(id, "Sens $n", touch), namedSensLastId = id) to id
        }

        /** [w] with named sensitivity [id] renamed [name] (blank: unchanged). */
        fun rename(w: Workspace, id: Int, name: String): Workspace =
            if (name.isBlank()) w else w.copy(namedSens = w.namedSens.map { if (it.id == id) it.copy(name = name.trim()) else it })

        /**
         * [w] without named sensitivity [id]: its slider chips and kept value go, and every
         * sensitivity that followed it keeps what it gave (its kept value times the scale as its
         * own value; never set, the shared Sensitivity at the same scale), so nothing jumps.
         */
        fun delete(w: Workspace, id: Int): Workspace {
            val ns = w.namedSens.firstOrNull { it.id == id } ?: return w
            val touch = ns.touch
            val v = SliderOverrides.named(w.sliders, touch, id)
            fun hit(l: SensLink?) = l != null && l.named == id
            fun gyro(g: GyroSettings): GyroSettings {
                var r = g
                for (a in MotionAxis.shown) {
                    val l = r.ownLink(a)
                    if (touch || !hit(l)) continue
                    r = if (v != null) r.withSensitivity(a, v * l!!.scale) else r.withLink(a, SensLink(SensLink.SHARED, l!!.scale))
                }
                return r
            }
            fun move(m: MoveSettings): MoveSettings {
                val l = m.linkOf
                if (!touch || !hit(l)) return m
                return if (v != null) m.copy(sensitivity = v * l!!.scale, link = null, scale = null) else m.copy(link = SensLink(SensLink.SHARED, l!!.scale), scale = null)
            }
            fun moves(list: List<MoveSettings>?) = list?.map(::move)
            fun button(b: ButtonGyro): ButtonGyro {
                var r = b
                if (!touch) for (a in MotionAxis.shown) {
                    val l = r.ownLink(a)
                    if (!hit(l)) continue
                    r = if (v != null) r.with(a, v * l!!.scale) else r.withLink(a, SensLink(SensLink.SHARED, l!!.scale))
                }
                val t = r.touchLinkOf
                if (touch && hit(t)) r = if (v != null) r.withTouch(v * t!!.scale) else r.withTouchLink(SensLink(SensLink.SHARED, t!!.scale))
                return r
            }
            fun isIt(m: Mapping): Boolean = when (val t = m.target) {
                is OutputTarget.GyroSens -> !touch && t.named == id
                is OutputTarget.TouchSens -> touch && t.named == id
                else -> false
            }
            fun level(x: Workspace): Workspace = x.copy(
                gyro = gyro(x.gyro),
                touchpad = x.touchpad.copy(moves = moves(x.touchpad.moves)),
            )
            return level(w).copy(
                namedSens = w.namedSens.filterNot { it.id == id },
                // Recorded too, for a workspace saved before the counter whose highest id this was.
                namedSensLastId = maxOf(w.namedSensLastId, id),
                sliders = w.sliders.filterNot { o ->
                    val t = o.target
                    (t is OutputTarget.GyroSens && !touch && t.named == id) || (t is OutputTarget.TouchSens && touch && t.named == id)
                },
                sets = w.sets.map { s -> s.settings?.let { s.copy(settings = level(it)) } ?: s },
                touchControls = w.touchControls.map { c ->
                    c.copy(gyro = c.gyro.map(::button), surface = c.surface?.let { it.copy(moves = moves(it.moves)) })
                },
                heldButtons = w.heldButtons.map { h -> h.copy(gyro = h.gyro.map(::button)) },
                mappings = w.mappings.filterNot(::isIt),
                keyboard = w.keyboard.filterNot(::isIt),
                mouse = w.mouse.filterNot(::isIt),
                phone = w.phone.filterNot(::isIt),
                pads = w.pads.map { p -> p.copy(mappings = p.mappings.filterNot(::isIt)) },
            )
        }
    }
}
