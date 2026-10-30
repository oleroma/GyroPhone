package com.oleroma.gyrophone.core.model

/**
 * One shared touch surface setting that a level sets over its parent: a swipe button's or the
 * touchpad's own ([SurfaceSettings], null = the workspace's), or the workspace's and the defaults'
 * ([WorkspaceDefaults] keys, over the parent's values). [button]: a swipe button has it; the others
 * are the touchpad's only. Length zones are no field: they are always the surface's own. Each
 * direction's own settings over these are [DirField]s, each Move X / Y's [MoveField]s.
 */
enum class SurfaceField(val button: Boolean = true) {
    ENABLED(button = false),
    SENSITIVITY, ACCELERATION, SCROLL_SPEED, NATURAL_SCROLL, INERTIA,
    INERTIA_GLIDE(button = false),
    CURSOR_DEADZONE, CATCH_UP, REARM, STILL, MOVE_MODE, MOVE_PIE_X, MOVE_PIE_Y, MOVE_SAMPLE,
    SWIPE_PIE_X, SWIPE_PIE_Y, SWIPE_SAMPLE, SWIPE,
    HOLD_PIE_X, HOLD_PIE_Y, HOLD_SAMPLE, HOLD, HOLD_MODE, DRAG_START, RELEASE, LATCH, LATCH_RELEASE;

    private fun value(t: TouchpadSettings): Any? = when (this) {
        ENABLED -> t.enabled
        SENSITIVITY -> t.sensitivity
        ACCELERATION -> t.acceleration
        SCROLL_SPEED -> t.scrollSpeed
        NATURAL_SCROLL -> t.naturalScroll
        INERTIA -> t.inertia
        INERTIA_GLIDE -> t.inertiaGlideMs
        CURSOR_DEADZONE -> t.cursorDeadzoneDp
        CATCH_UP -> t.catchUpMs
        REARM -> t.deadzoneRearmMs
        STILL -> t.deadzoneStillDp
        MOVE_MODE -> t.moveMode
        MOVE_PIE_X -> t.movePieX
        MOVE_PIE_Y -> t.movePieY
        MOVE_SAMPLE -> t.moveSampleDp
        SWIPE_PIE_X -> t.swipePieX
        SWIPE_PIE_Y -> t.swipePieY
        SWIPE_SAMPLE -> t.swipeSampleDp
        HOLD_PIE_X -> t.holdPieX
        HOLD_PIE_Y -> t.holdPieY
        HOLD_SAMPLE -> t.holdSampleDp
        SWIPE -> t.swipeDp
        HOLD -> t.holdDp
        HOLD_MODE -> t.holdMode
        DRAG_START -> t.dragStart
        RELEASE -> t.releaseDp
        LATCH -> t.latch
        LATCH_RELEASE -> t.latchReleaseMs
    }

    /** [a] and [b] have the same value of this setting. */
    fun same(a: TouchpadSettings, b: TouchpadSettings): Boolean = value(a) == value(b)

    /** [to] with [from]'s value of this setting. */
    fun take(to: TouchpadSettings, from: TouchpadSettings): TouchpadSettings = when (this) {
        ENABLED -> to.copy(enabled = from.enabled)
        SENSITIVITY -> to.copy(sensitivity = from.sensitivity)
        ACCELERATION -> to.copy(acceleration = from.acceleration)
        SCROLL_SPEED -> to.copy(scrollSpeed = from.scrollSpeed)
        NATURAL_SCROLL -> to.copy(naturalScroll = from.naturalScroll)
        INERTIA -> to.copy(inertia = from.inertia)
        INERTIA_GLIDE -> to.copy(inertiaGlideMs = from.inertiaGlideMs)
        CURSOR_DEADZONE -> to.copy(cursorDeadzoneDp = from.cursorDeadzoneDp)
        CATCH_UP -> to.copy(catchUpMs = from.catchUpMs)
        REARM -> to.copy(deadzoneRearmMs = from.deadzoneRearmMs)
        STILL -> to.copy(deadzoneStillDp = from.deadzoneStillDp)
        MOVE_MODE -> to.copy(moveMode = from.moveMode)
        MOVE_PIE_X -> to.copy(movePieX = from.movePieX)
        MOVE_PIE_Y -> to.copy(movePieY = from.movePieY)
        MOVE_SAMPLE -> to.copy(moveSampleDp = from.moveSampleDp)
        SWIPE_PIE_X -> to.copy(swipePieX = from.swipePieX)
        SWIPE_PIE_Y -> to.copy(swipePieY = from.swipePieY)
        SWIPE_SAMPLE -> to.copy(swipeSampleDp = from.swipeSampleDp)
        HOLD_PIE_X -> to.copy(holdPieX = from.holdPieX)
        HOLD_PIE_Y -> to.copy(holdPieY = from.holdPieY)
        HOLD_SAMPLE -> to.copy(holdSampleDp = from.holdSampleDp)
        SWIPE -> to.copy(swipeDp = from.swipeDp)
        HOLD -> to.copy(holdDp = from.holdDp)
        HOLD_MODE -> to.copy(holdMode = from.holdMode)
        DRAG_START -> to.copy(dragStart = from.dragStart)
        RELEASE -> to.copy(releaseDp = from.releaseDp)
        LATCH -> to.copy(latch = from.latch)
        LATCH_RELEASE -> to.copy(latchReleaseMs = from.latchReleaseMs)
    }

    /** A swipe button's [s] sets this itself. */
    fun isOwn(s: SurfaceSettings): Boolean = when (this) {
        ENABLED -> s.enabled != null
        INERTIA_GLIDE -> s.inertiaGlideMs != null
        SENSITIVITY -> s.sensitivity != null
        ACCELERATION -> s.acceleration != null
        SCROLL_SPEED -> s.scrollSpeed != null
        NATURAL_SCROLL -> s.naturalScroll != null
        INERTIA -> s.inertia != null
        CURSOR_DEADZONE -> s.cursorDeadzoneDp != null
        CATCH_UP -> s.catchUpMs != null
        REARM -> s.deadzoneRearmMs != null
        STILL -> s.deadzoneStillDp != null
        MOVE_MODE -> s.moveMode != null
        MOVE_PIE_X -> s.movePieX != null
        MOVE_PIE_Y -> s.movePieY != null
        MOVE_SAMPLE -> s.moveSampleDp != null
        SWIPE_PIE_X -> s.swipePieX != null
        SWIPE_PIE_Y -> s.swipePieY != null
        SWIPE_SAMPLE -> s.swipeSampleDp != null
        HOLD_PIE_X -> s.holdPieX != null
        HOLD_PIE_Y -> s.holdPieY != null
        HOLD_SAMPLE -> s.holdSampleDp != null
        SWIPE -> s.swipeDp != null
        HOLD -> s.holdDp != null
        HOLD_MODE -> s.holdMode != null
        DRAG_START -> s.dragStart != null
        RELEASE -> s.releaseDp != null
        LATCH -> s.latch != null
        LATCH_RELEASE -> s.latchReleaseMs != null
    }

    /** [s] setting this itself, to [from]'s value. */
    fun own(s: SurfaceSettings, from: TouchpadSettings): SurfaceSettings = when (this) {
        ENABLED -> s.copy(enabled = from.enabled)
        INERTIA_GLIDE -> s.copy(inertiaGlideMs = from.inertiaGlideMs)
        SENSITIVITY -> s.copy(sensitivity = from.sensitivity)
        ACCELERATION -> s.copy(acceleration = from.acceleration)
        SCROLL_SPEED -> s.copy(scrollSpeed = from.scrollSpeed)
        NATURAL_SCROLL -> s.copy(naturalScroll = from.naturalScroll)
        INERTIA -> s.copy(inertia = from.inertia)
        CURSOR_DEADZONE -> s.copy(cursorDeadzoneDp = from.cursorDeadzoneDp)
        CATCH_UP -> s.copy(catchUpMs = from.catchUpMs)
        REARM -> s.copy(deadzoneRearmMs = from.deadzoneRearmMs)
        STILL -> s.copy(deadzoneStillDp = from.deadzoneStillDp)
        MOVE_MODE -> s.copy(moveMode = from.moveMode)
        MOVE_PIE_X -> s.copy(movePieX = from.movePieX)
        MOVE_PIE_Y -> s.copy(movePieY = from.movePieY)
        MOVE_SAMPLE -> s.copy(moveSampleDp = from.moveSampleDp)
        SWIPE_PIE_X -> s.copy(swipePieX = from.swipePieX)
        SWIPE_PIE_Y -> s.copy(swipePieY = from.swipePieY)
        SWIPE_SAMPLE -> s.copy(swipeSampleDp = from.swipeSampleDp)
        HOLD_PIE_X -> s.copy(holdPieX = from.holdPieX)
        HOLD_PIE_Y -> s.copy(holdPieY = from.holdPieY)
        HOLD_SAMPLE -> s.copy(holdSampleDp = from.holdSampleDp)
        SWIPE -> s.copy(swipeDp = from.swipeDp)
        HOLD -> s.copy(holdDp = from.holdDp)
        HOLD_MODE -> s.copy(holdMode = from.holdMode)
        DRAG_START -> s.copy(dragStart = from.dragStart)
        RELEASE -> s.copy(releaseDp = from.releaseDp)
        LATCH -> s.copy(latch = from.latch)
        LATCH_RELEASE -> s.copy(latchReleaseMs = from.latchReleaseMs)
    }

    /** [s] following the parent for this again. */
    fun drop(s: SurfaceSettings): SurfaceSettings = when (this) {
        ENABLED -> s.copy(enabled = null)
        INERTIA_GLIDE -> s.copy(inertiaGlideMs = null)
        SENSITIVITY -> s.copy(sensitivity = null)
        ACCELERATION -> s.copy(acceleration = null)
        SCROLL_SPEED -> s.copy(scrollSpeed = null)
        NATURAL_SCROLL -> s.copy(naturalScroll = null)
        INERTIA -> s.copy(inertia = null)
        CURSOR_DEADZONE -> s.copy(cursorDeadzoneDp = null)
        CATCH_UP -> s.copy(catchUpMs = null)
        REARM -> s.copy(deadzoneRearmMs = null)
        STILL -> s.copy(deadzoneStillDp = null)
        MOVE_MODE -> s.copy(moveMode = null)
        MOVE_PIE_X -> s.copy(movePieX = null)
        MOVE_PIE_Y -> s.copy(movePieY = null)
        MOVE_SAMPLE -> s.copy(moveSampleDp = null)
        SWIPE_PIE_X -> s.copy(swipePieX = null)
        SWIPE_PIE_Y -> s.copy(swipePieY = null)
        SWIPE_SAMPLE -> s.copy(swipeSampleDp = null)
        HOLD_PIE_X -> s.copy(holdPieX = null)
        HOLD_PIE_Y -> s.copy(holdPieY = null)
        HOLD_SAMPLE -> s.copy(holdSampleDp = null)
        SWIPE -> s.copy(swipeDp = null)
        HOLD -> s.copy(holdDp = null)
        HOLD_MODE -> s.copy(holdMode = null)
        DRAG_START -> s.copy(dragStart = null)
        RELEASE -> s.copy(releaseDp = null)
        LATCH -> s.copy(latch = null)
        LATCH_RELEASE -> s.copy(latchReleaseMs = null)
    }

    companion object {
        /**
         * Surface settings [own] after the settings in effect went from [was] to [now]: the changed
         * ones, shared and per direction, become its own ([button]: only what a swipe button has).
         */
        fun edited(own: SurfaceSettings, was: TouchpadSettings, now: TouchpadSettings, button: Boolean = true): SurfaceSettings {
            var o = entries.filter { (!button || it.button) && !it.same(was, now) }.fold(own) { s, f -> f.own(s, now) }
            for (d in Direction.entries) for (f in DirField.entries) {
                if (!f.same(was.dir(d), now.dir(d))) o = f.own(o, d, now.dir(d))
            }
            for (m in MoveSlot.entries) for (f in MoveField.of(m)) {
                if (!f.same(was.move(m), now.move(m))) o = f.own(o, m, now.move(m))
            }
            return o
        }

        /** [s] following the parent for every setting; its length zones stay. */
        fun dropAll(s: SurfaceSettings): SurfaceSettings =
            SurfaceSettings(holdZones = s.holdZones, dirHoldZones = s.dirHoldZones, releaseTapMs = s.releaseTapMs)

        /** [t] with [parent]'s value of every setting, per direction too; everything else (mappings, zones) stays. */
        fun takeAll(t: TouchpadSettings, parent: TouchpadSettings): TouchpadSettings =
            entries.fold(t) { r, f -> f.take(r, parent) }.copy(dirs = parent.dirs, moves = parent.moves)
    }
}

/**
 * One direction's own setting ([DirSettings]) over its surface's shared one ([sharedOf]): the pie,
 * sample and distance of its Swipe, the pie, sample, hold distance and latch of its Swipe hold.
 */
enum class DirField(val hold: Boolean) {
    SWIPE_PIE(false), SWIPE_SAMPLE(false), SWIPE(false),
    HOLD_PIE(true), HOLD_SAMPLE(true), HOLD(true), LATCH(true);

    /** The shared setting of [d] this sets over: a pie its axis's. */
    fun sharedOf(d: Direction): SurfaceField = when (this) {
        SWIPE_PIE -> if (d.x) SurfaceField.SWIPE_PIE_X else SurfaceField.SWIPE_PIE_Y
        SWIPE_SAMPLE -> SurfaceField.SWIPE_SAMPLE
        SWIPE -> SurfaceField.SWIPE
        HOLD_PIE -> if (d.x) SurfaceField.HOLD_PIE_X else SurfaceField.HOLD_PIE_Y
        HOLD_SAMPLE -> SurfaceField.HOLD_SAMPLE
        HOLD -> SurfaceField.HOLD
        LATCH -> SurfaceField.LATCH
    }

    fun get(s: DirSettings): Any? = when (this) {
        SWIPE_PIE -> s.swipePie
        SWIPE_SAMPLE -> s.swipeSample
        SWIPE -> s.swipeDp
        HOLD_PIE -> s.holdPie
        HOLD_SAMPLE -> s.holdSample
        HOLD -> s.holdDp
        LATCH -> s.latch
    }

    fun same(a: DirSettings, b: DirSettings): Boolean = get(a) == get(b)

    /** [to] with [from]'s value. */
    fun take(to: DirSettings, from: DirSettings): DirSettings = when (this) {
        SWIPE_PIE -> to.copy(swipePie = from.swipePie)
        SWIPE_SAMPLE -> to.copy(swipeSample = from.swipeSample)
        SWIPE -> to.copy(swipeDp = from.swipeDp)
        HOLD_PIE -> to.copy(holdPie = from.holdPie)
        HOLD_SAMPLE -> to.copy(holdSample = from.holdSample)
        HOLD -> to.copy(holdDp = from.holdDp)
        LATCH -> to.copy(latch = from.latch)
    }

    /** A level's own [s] sets this for [d]. */
    fun isOwn(s: SurfaceSettings, d: Direction): Boolean = get(s.dirs?.getOrNull(d.ordinal) ?: DirSettings.NONE) != null

    /** [s] setting this for [d] to [from]'s value (null: following again). */
    fun own(s: SurfaceSettings, d: Direction, from: DirSettings): SurfaceSettings {
        val list = List(4) { s.dirs?.getOrNull(it) ?: DirSettings.NONE }.toMutableList()
        list[d.ordinal] = take(list[d.ordinal], from)
        return s.copy(dirs = list.takeUnless { l -> l.all { it.isEmpty } })
    }

    fun drop(s: SurfaceSettings, d: Direction): SurfaceSettings = own(s, d, DirSettings.NONE)

    /** [t] with [from]'s value of this for [d]. */
    fun take(t: TouchpadSettings, d: Direction, from: TouchpadSettings): TouchpadSettings =
        t.withDir(d) { take(it, from.dir(d)) }
}

/**
 * One Move X / Y's own setting ([MoveSettings], [MoveSlot]) over its surface's shared one
 * ([sharedOf]). [whole]: it goes for the whole motion of a finger count, kept on its Move X.
 */
enum class MoveField(val whole: Boolean = false) {
    SENSITIVITY, ACCELERATION, SCROLL_SPEED, NATURAL_SCROLL, MODE, PIE,
    CURSOR_DEADZONE(true), CATCH_UP(true), REARM(true), STILL(true), INERTIA(true), SAMPLE(true);

    /** The shared setting of [m] this sets over. */
    fun sharedOf(m: MoveSlot): SurfaceField = when (this) {
        SENSITIVITY -> SurfaceField.SENSITIVITY
        ACCELERATION -> SurfaceField.ACCELERATION
        SCROLL_SPEED -> SurfaceField.SCROLL_SPEED
        NATURAL_SCROLL -> SurfaceField.NATURAL_SCROLL
        MODE -> SurfaceField.MOVE_MODE
        PIE -> if (m.x) SurfaceField.MOVE_PIE_X else SurfaceField.MOVE_PIE_Y
        CURSOR_DEADZONE -> SurfaceField.CURSOR_DEADZONE
        CATCH_UP -> SurfaceField.CATCH_UP
        REARM -> SurfaceField.REARM
        STILL -> SurfaceField.STILL
        INERTIA -> SurfaceField.INERTIA
        SAMPLE -> SurfaceField.MOVE_SAMPLE
    }

    fun get(s: MoveSettings): Any? = when (this) {
        SENSITIVITY -> s.sensitivity
        ACCELERATION -> s.acceleration
        SCROLL_SPEED -> s.scrollSpeed
        NATURAL_SCROLL -> s.naturalScroll
        MODE -> s.mode
        PIE -> s.pie
        CURSOR_DEADZONE -> s.cursorDeadzoneDp
        CATCH_UP -> s.catchUpMs
        REARM -> s.deadzoneRearmMs
        STILL -> s.deadzoneStillDp
        INERTIA -> s.inertia
        SAMPLE -> s.sample
    }

    fun same(a: MoveSettings, b: MoveSettings): Boolean = get(a) == get(b)

    /** [to] with [from]'s value. */
    fun take(to: MoveSettings, from: MoveSettings): MoveSettings = when (this) {
        SENSITIVITY -> to.copy(sensitivity = from.sensitivity)
        ACCELERATION -> to.copy(acceleration = from.acceleration)
        SCROLL_SPEED -> to.copy(scrollSpeed = from.scrollSpeed)
        NATURAL_SCROLL -> to.copy(naturalScroll = from.naturalScroll)
        MODE -> to.copy(mode = from.mode)
        PIE -> to.copy(pie = from.pie)
        CURSOR_DEADZONE -> to.copy(cursorDeadzoneDp = from.cursorDeadzoneDp)
        CATCH_UP -> to.copy(catchUpMs = from.catchUpMs)
        REARM -> to.copy(deadzoneRearmMs = from.deadzoneRearmMs)
        STILL -> to.copy(deadzoneStillDp = from.deadzoneStillDp)
        INERTIA -> to.copy(inertia = from.inertia)
        SAMPLE -> to.copy(sample = from.sample)
    }

    /** A level's own [s] sets this for [m]. */
    fun isOwn(s: SurfaceSettings, m: MoveSlot): Boolean = get(s.moves?.getOrNull(m.ordinal) ?: MoveSettings.NONE) != null

    /** [s] setting this for [m] to [from]'s value (null: following again). */
    fun own(s: SurfaceSettings, m: MoveSlot, from: MoveSettings): SurfaceSettings {
        val list = List(MoveSlot.entries.size) { s.moves?.getOrNull(it) ?: MoveSettings.NONE }.toMutableList()
        list[m.ordinal] = take(list[m.ordinal], from)
        return s.copy(moves = list.takeUnless { l -> l.all { it.isEmpty } })
    }

    fun drop(s: SurfaceSettings, m: MoveSlot): SurfaceSettings = own(s, m, MoveSettings.NONE)

    /** [t] with [from]'s value of this for [m]. */
    fun take(t: TouchpadSettings, m: MoveSlot, from: TouchpadSettings): TouchpadSettings =
        t.withMove(m) { take(it, from.move(m)) }

    companion object {
        /** The fields [m] has: a Move Y not those of the whole motion. */
        fun of(m: MoveSlot): List<MoveField> = if (m.x) entries else entries.filter { !it.whole }
    }
}
