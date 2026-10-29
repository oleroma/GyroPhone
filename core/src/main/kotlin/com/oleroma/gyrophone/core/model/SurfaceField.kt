package com.oleroma.gyrophone.core.model

/**
 * One touch surface setting that a level sets over its parent: a swipe button's own
 * ([SurfaceSettings], null = the workspace touchpad's) or the touchpad's (over the built-in
 * values). [button]: a swipe button can set it ([SurfaceSettings] has it); the others are the
 * touchpad's only. Length zones are no field: they are always the surface's own.
 */
enum class SurfaceField(val button: Boolean = true) {
    ENABLED(button = false),
    SENSITIVITY, SENSITIVITY_Y, ACCELERATION, SCROLL_SPEED, NATURAL_SCROLL, INERTIA,
    INERTIA_GLIDE(button = false),
    CURSOR_DEADZONE, CATCH_UP, REARM, STILL, VERTICAL_LOCK, HORIZONTAL_LOCK,
    DIR_SAMPLE, SWIPE, HOLD, HOLD_MODE, DRAG_START, RELEASE, PIES, LATCH, LATCH_RELEASE;

    private fun value(t: TouchpadSettings): Any? = when (this) {
        ENABLED -> t.enabled
        SENSITIVITY -> t.sensitivity
        SENSITIVITY_Y -> t.sensitivityY
        ACCELERATION -> t.acceleration
        SCROLL_SPEED -> t.scrollSpeed
        NATURAL_SCROLL -> t.naturalScroll
        INERTIA -> t.inertia
        INERTIA_GLIDE -> t.inertiaGlideMs
        CURSOR_DEADZONE -> t.cursorDeadzoneDp
        CATCH_UP -> t.catchUpMs
        REARM -> t.deadzoneRearmMs
        STILL -> t.deadzoneStillDp
        VERTICAL_LOCK -> t.verticalLockDeg
        HORIZONTAL_LOCK -> t.horizontalLockDeg
        DIR_SAMPLE -> t.dirSampleDp
        SWIPE -> t.swipeDp
        HOLD -> t.holdDp
        HOLD_MODE -> t.holdMode
        DRAG_START -> t.dragStart
        RELEASE -> t.releaseDp
        PIES -> t.pies
        LATCH -> t.latch
        LATCH_RELEASE -> t.latchReleaseMs
    }

    /** [a] and [b] have the same value of this setting. */
    fun same(a: TouchpadSettings, b: TouchpadSettings): Boolean = value(a) == value(b)

    /** [to] with [from]'s value of this setting. */
    fun take(to: TouchpadSettings, from: TouchpadSettings): TouchpadSettings = when (this) {
        ENABLED -> to.copy(enabled = from.enabled)
        SENSITIVITY -> to.copy(sensitivity = from.sensitivity)
        SENSITIVITY_Y -> to.copy(sensitivityY = from.sensitivityY)
        ACCELERATION -> to.copy(acceleration = from.acceleration)
        SCROLL_SPEED -> to.copy(scrollSpeed = from.scrollSpeed)
        NATURAL_SCROLL -> to.copy(naturalScroll = from.naturalScroll)
        INERTIA -> to.copy(inertia = from.inertia)
        INERTIA_GLIDE -> to.copy(inertiaGlideMs = from.inertiaGlideMs)
        CURSOR_DEADZONE -> to.copy(cursorDeadzoneDp = from.cursorDeadzoneDp)
        CATCH_UP -> to.copy(catchUpMs = from.catchUpMs)
        REARM -> to.copy(deadzoneRearmMs = from.deadzoneRearmMs)
        STILL -> to.copy(deadzoneStillDp = from.deadzoneStillDp)
        VERTICAL_LOCK -> to.copy(verticalLockDeg = from.verticalLockDeg)
        HORIZONTAL_LOCK -> to.copy(horizontalLockDeg = from.horizontalLockDeg)
        DIR_SAMPLE -> to.copy(dirSampleDp = from.dirSampleDp)
        SWIPE -> to.copy(swipeDp = from.swipeDp)
        HOLD -> to.copy(holdDp = from.holdDp)
        HOLD_MODE -> to.copy(holdMode = from.holdMode)
        DRAG_START -> to.copy(dragStart = from.dragStart)
        RELEASE -> to.copy(releaseDp = from.releaseDp)
        PIES -> to.copy(pies = from.pies)
        LATCH -> to.copy(latch = from.latch)
        LATCH_RELEASE -> to.copy(latchReleaseMs = from.latchReleaseMs)
    }

    /** A swipe button's [s] sets this itself. */
    fun isOwn(s: SurfaceSettings): Boolean = when (this) {
        ENABLED, INERTIA_GLIDE -> false
        SENSITIVITY -> s.sensitivity != null
        SENSITIVITY_Y -> s.sensitivityY != null
        ACCELERATION -> s.acceleration != null
        SCROLL_SPEED -> s.scrollSpeed != null
        NATURAL_SCROLL -> s.naturalScroll != null
        INERTIA -> s.inertia != null
        CURSOR_DEADZONE -> s.cursorDeadzoneDp != null
        CATCH_UP -> s.catchUpMs != null
        REARM -> s.deadzoneRearmMs != null
        STILL -> s.deadzoneStillDp != null
        VERTICAL_LOCK -> s.verticalLockDeg != null
        HORIZONTAL_LOCK -> s.horizontalLockDeg != null
        DIR_SAMPLE -> s.dirSampleDp != null
        SWIPE -> s.swipeDp != null
        HOLD -> s.holdDp != null
        HOLD_MODE -> s.holdMode != null
        DRAG_START -> s.dragStart != null
        RELEASE -> s.releaseDp != null
        PIES -> s.pies != null || s.classicPies != null
        LATCH -> s.latch != null
        LATCH_RELEASE -> s.latchReleaseMs != null
    }

    /** [s] setting this itself, to [from]'s value. */
    fun own(s: SurfaceSettings, from: TouchpadSettings): SurfaceSettings = when (this) {
        ENABLED, INERTIA_GLIDE -> s
        SENSITIVITY -> s.copy(sensitivity = from.sensitivity)
        SENSITIVITY_Y -> s.copy(sensitivityY = from.verticalSensitivity)
        ACCELERATION -> s.copy(acceleration = from.acceleration)
        SCROLL_SPEED -> s.copy(scrollSpeed = from.scrollSpeed)
        NATURAL_SCROLL -> s.copy(naturalScroll = from.naturalScroll)
        INERTIA -> s.copy(inertia = from.inertia)
        CURSOR_DEADZONE -> s.copy(cursorDeadzoneDp = from.cursorDeadzoneDp)
        CATCH_UP -> s.copy(catchUpMs = from.catchUpMs)
        REARM -> s.copy(deadzoneRearmMs = from.deadzoneRearmMs)
        STILL -> s.copy(deadzoneStillDp = from.deadzoneStillDp)
        VERTICAL_LOCK -> s.copy(verticalLockDeg = from.verticalLockDeg)
        HORIZONTAL_LOCK -> s.copy(horizontalLockDeg = from.horizontalLockDeg)
        DIR_SAMPLE -> s.copy(dirSampleDp = from.dirSampleDp)
        SWIPE -> s.copy(swipeDp = from.swipeDp)
        HOLD -> s.copy(holdDp = from.holdDp)
        HOLD_MODE -> s.copy(holdMode = from.holdMode)
        DRAG_START -> s.copy(dragStart = from.dragStart)
        RELEASE -> s.copy(releaseDp = from.releaseDp)
        PIES -> s.copy(pies = from.pies, classicPies = if (from.pies == null) true else null)
        LATCH -> s.copy(latch = from.latch)
        LATCH_RELEASE -> s.copy(latchReleaseMs = from.latchReleaseMs)
    }

    /** [s] following the parent for this again. */
    fun drop(s: SurfaceSettings): SurfaceSettings = when (this) {
        ENABLED, INERTIA_GLIDE -> s
        SENSITIVITY -> s.copy(sensitivity = null)
        SENSITIVITY_Y -> s.copy(sensitivityY = null)
        ACCELERATION -> s.copy(acceleration = null)
        SCROLL_SPEED -> s.copy(scrollSpeed = null)
        NATURAL_SCROLL -> s.copy(naturalScroll = null)
        INERTIA -> s.copy(inertia = null)
        CURSOR_DEADZONE -> s.copy(cursorDeadzoneDp = null)
        CATCH_UP -> s.copy(catchUpMs = null)
        REARM -> s.copy(deadzoneRearmMs = null)
        STILL -> s.copy(deadzoneStillDp = null)
        VERTICAL_LOCK -> s.copy(verticalLockDeg = null)
        HORIZONTAL_LOCK -> s.copy(horizontalLockDeg = null)
        DIR_SAMPLE -> s.copy(dirSampleDp = null)
        SWIPE -> s.copy(swipeDp = null)
        HOLD -> s.copy(holdDp = null)
        HOLD_MODE -> s.copy(holdMode = null)
        DRAG_START -> s.copy(dragStart = null)
        RELEASE -> s.copy(releaseDp = null)
        PIES -> s.copy(pies = null, classicPies = null)
        LATCH -> s.copy(latch = null)
        LATCH_RELEASE -> s.copy(latchReleaseMs = null)
    }

    companion object {
        /** A swipe button's own settings after its settings in effect went from [was] to [now]: the changed ones become its own. */
        fun edited(own: SurfaceSettings, was: TouchpadSettings, now: TouchpadSettings): SurfaceSettings =
            entries.filter { it.button && !it.same(was, now) }.fold(own) { s, f -> f.own(s, now) }

        /** [s] following the parent for every setting; its length zones stay. */
        fun dropAll(s: SurfaceSettings): SurfaceSettings =
            SurfaceSettings(holdZones = s.holdZones, dirHoldZones = s.dirHoldZones, releaseTapMs = s.releaseTapMs)

        /** [t] with [parent]'s value of every setting; everything else (mappings, zones) stays. */
        fun takeAll(t: TouchpadSettings, parent: TouchpadSettings): TouchpadSettings =
            entries.fold(t) { r, f -> f.take(r, parent) }
    }
}
