package com.oleroma.gyrophone.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import com.oleroma.gyrophone.core.engine.LaserPaint
import com.oleroma.gyrophone.core.engine.LaserTrace

/**
 * Draws a pointer's paint map ([LaserPaint]) on its sphere on the GPU: every pixel of the sphere's
 * picture finds the point of the sphere it shows (in perspective or flat, the near or the far
 * half), reads the map round it and shows the paint there, so the line is smooth, foreshortened and
 * bigger nearer as paint on a ball is. One per pointer (it keeps the map's upload).
 */
internal class PointerPaintShader {
    private val near = RuntimeShader(SOURCE)
    private val far = RuntimeShader(SOURCE)
    private val paint = Paint()
    private var amountMap: Bitmap? = null
    private var fireMap: Bitmap? = null
    private var aimMap: Bitmap? = null
    private var glowMap: Bitmap? = null
    private var glowFireMap: Bitmap? = null
    private var glowAimMap: Bitmap? = null
    private var wayMap: Bitmap? = null
    private var uploaded = Long.MIN_VALUE
    private var uploadedMs = Long.MIN_VALUE / 2
    private var uploadedOf: LaserPaint? = null
    // The way colours' picture missed some paint (it is kept only while colour by way is on).
    private var wayStale = true
    private val col = FloatArray(3)

    /**
     * What every draw of one frame shares. One is kept per display and filled in each frame
     * ([com.oleroma.gyrophone.ui.GyroDisplaysLayer]), so drawing makes none.
     */
    class Look {
        var eye = 0f
        var byWay = false
        var glow = 0f
        var outline = false
        var shade = 0f
        var inside = 0f
        var light = 0f
        var lightFrom = 0
        var shine = 0f
        val moves = IntArray(6)
        var fireAhead = 0
        var fireBehind = 0
        var outlineColor = 0
        var wayAlpha = 0f
        var aimAhead = 0
        var aimBehind = 0
        var fireGlow = 0f
        var aimGlow = 0f
    }

    /**
     * Draws [lp]'s paint on the sphere of radius [r] at ([cx], [cy]): its [front] (near) half or far
     * half, the shown frame seen through [view] (shown-frame direction to picture, as [LaserTrace.view]).
     * The paint's colours (as light would) stay with the scene, seen through [scene] (the view without
     * the sphere's turn), so in Fixed pointer mode they don't turn with the sphere. [nowMs] is the
     * frame's time: new paint goes up to the GPU at most every [UPLOAD_MS], the rest at the next.
     */
    fun draw(
        canvas: Canvas, lp: LaserPaint, front: Boolean, cx: Float, cy: Float, r: Float,
        view: (Float, Float, Float, FloatArray) -> Unit, scene: (Float, Float, Float, FloatArray) -> Unit, look: Look,
        nowMs: Long,
    ) {
        val s = if (front) near else far
        val fresh = lp !== uploadedOf || amountMap?.width != lp.width || (look.byWay && lp.way != null && (wayMap == null || wayStale))
        // While the beam paints, the map changes on nearly every frame: it goes up about 30 times a
        // second (the beam's dot and the snake still move every frame), a new or cleared map at once.
        if (fresh || (lp.version != uploaded && (nowMs - uploadedMs >= UPLOAD_MS || nowMs < uploadedMs || lp.changedTo - lp.changedFrom >= lp.height - 1))) {
            if (amountMap?.width != lp.width) {
                amountMap = null; fireMap = null; aimMap = null; glowMap = null; glowFireMap = null; glowAimMap = null; wayMap = null
            }
            // The map's bytes as they are, each in an alpha-only bitmap: alpha is never colour-converted
            // on the way to the GPU, so the amounts arrive exact. (A plain copy: fast, and the GPU takes
            // the whole picture up again anyway.)
            fun a8(old: Bitmap?, bytes: ByteArray): Bitmap =
                (old ?: Bitmap.createBitmap(lp.width, lp.height, Bitmap.Config.ALPHA_8)).also { it.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes)) }
            val am = a8(amountMap, lp.amount).also { amountMap = it }
            val fm = a8(fireMap, lp.fire).also { fireMap = it }
            val gm = a8(glowMap, lp.glow).also { glowMap = it }
            val im = a8(aimMap, lp.aim).also { aimMap = it }
            val gfm = a8(glowFireMap, lp.glowFire).also { glowFireMap = it }
            val gim = a8(glowAimMap, lp.glowAim).also { glowAimMap = it }
            val ways = lp.way
            if (ways != null && look.byWay) {
                // The way colours are converted pixel by pixel: only the rows painted since the last upload.
                val all = fresh || wayMap == null
                val wb = wayMap ?: Bitmap.createBitmap(lp.width, lp.height, Bitmap.Config.ARGB_8888).also { wayMap = it }
                val j0 = if (all) 0 else lp.changedFrom.coerceAtLeast(0)
                val j1 = if (all) lp.height - 1 else lp.changedTo.coerceAtMost(lp.height - 1)
                if (j1 >= j0) wb.setPixels(ways, j0 * lp.width, lp.width, 0, j0, lp.width, j1 - j0 + 1)
                wayStale = false
            } else wayStale = true
            lp.takeChanged()
            uploaded = lp.version
            uploadedMs = nowMs
            uploadedOf = lp
            // New shaders for the changed pixels, so the GPU takes them up; both halves share them.
            val way = smooth(wayMap ?: am)
            val maps = arrayOf(smooth(am), smooth(fm), smooth(gm), smooth(im), smooth(gfm), smooth(gim))
            for (sh in arrayOf(near, far)) {
                sh.setInputBuffer("amount", maps[0])
                sh.setInputBuffer("fire", maps[1])
                sh.setInputBuffer("glow", maps[2])
                sh.setInputBuffer("aim", maps[3])
                sh.setInputBuffer("glowFire", maps[4])
                sh.setInputBuffer("glowAim", maps[5])
                sh.setInputBuffer("way", way)
            }
        }
        s.setFloatUniform("mapSize", lp.width.toFloat(), lp.height.toFloat())
        s.setFloatUniform("center", cx, cy)
        s.setFloatUniform("radius", r)
        s.setFloatUniform("eye", look.eye)
        s.setFloatUniform("front", if (front) 1f else 0f)
        view(1f, 0f, 0f, col); s.setFloatUniform("colX", col[0], col[1], col[2])
        view(0f, 1f, 0f, col); s.setFloatUniform("colY", col[0], col[1], col[2])
        view(0f, 0f, 1f, col); s.setFloatUniform("colZ", col[0], col[1], col[2])
        scene(1f, 0f, 0f, col); s.setFloatUniform("sceneX", col[0], col[1], col[2])
        scene(0f, 1f, 0f, col); s.setFloatUniform("sceneY", col[0], col[1], col[2])
        scene(0f, 0f, 1f, col); s.setFloatUniform("sceneZ", col[0], col[1], col[2])
        s.setFloatUniform("wayMode", if (look.byWay && wayMap != null) 1f else 0f)
        s.setFloatUniform("glowK", look.glow)
        s.setFloatUniform("fireGlowK", look.fireGlow)
        s.setFloatUniform("aimGlowK", look.aimGlow)
        s.setFloatUniform("outline", if (look.outline) 1f else 0f)
        s.setFloatUniform("shade", look.shade)
        s.setFloatUniform("inside", look.inside)
        s.setFloatUniform("light", look.light)
        s.setFloatUniform("shine", look.shine)
        val l = lightDir(look.lightFrom)
        s.setFloatUniform("lightDir", l[0], l[1], l[2])
        s.setFloatUniform("wayAlpha", look.wayAlpha)
        for ((i, name) in MOVE_NAMES.withIndex()) s.setColorUniform(name, look.moves[i])
        s.setColorUniform("fireAhead", look.fireAhead)
        s.setColorUniform("fireBehind", look.fireBehind)
        s.setColorUniform("aimAhead", look.aimAhead)
        s.setColorUniform("aimBehind", look.aimBehind)
        s.setColorUniform("outlineColor", look.outlineColor)
        paint.shader = s
        val e = r * 1.05f
        canvas.drawRect(cx - e, cy - e, cx + e, cy + e, paint)
    }

    // The map read smoothly between texels (its values are premultiplied amounts), round the sphere in longitude.
    private fun smooth(bm: Bitmap) = BitmapShader(bm, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }

    companion object {
        /** The scene's light, a unit direction in the picture (x right, y up, z toward you), from 0 upper left, 1 above, 2 upper right, 3 front. */
        fun lightDir(from: Int): FloatArray = LIGHTS[from.coerceIn(0, 3)]

        /** How often new paint goes up to the GPU while the beam paints (ms): about 30 times a second. */
        const val UPLOAD_MS = 33L

        private val LIGHTS = arrayOf(
            floatArrayOf(-0.5f, 0.6f, 0.6f), floatArrayOf(0f, 0.8f, 0.6f), floatArrayOf(0.5f, 0.6f, 0.6f), floatArrayOf(0f, 0f, 1f),
        ).onEach { LaserTrace.unit(it) }

        // In LaserTrace.directionColor's order.
        private val MOVE_NAMES = arrayOf("cRight", "cLeft", "cUp", "cDown", "cToward", "cAway")

        private val SOURCE = """
            uniform shader amount;
            uniform shader fire;
            uniform shader glow;
            uniform shader aim;
            uniform shader glowFire;
            uniform shader glowAim;
            uniform shader way;
            uniform float2 mapSize;
            uniform float2 center;
            uniform float radius;
            uniform float eye;
            uniform float front;
            uniform float3 colX;
            uniform float3 colY;
            uniform float3 colZ;
            uniform float3 sceneX;
            uniform float3 sceneY;
            uniform float3 sceneZ;
            uniform float wayMode;
            uniform float glowK;
            uniform float fireGlowK;
            uniform float aimGlowK;
            uniform float outline;
            uniform float shade;
            uniform float inside;
            uniform float light;
            uniform float shine;
            uniform float3 lightDir;
            uniform float wayAlpha;
            layout(color) uniform half4 cRight;
            layout(color) uniform half4 cLeft;
            layout(color) uniform half4 cUp;
            layout(color) uniform half4 cDown;
            layout(color) uniform half4 cToward;
            layout(color) uniform half4 cAway;
            layout(color) uniform half4 fireAhead;
            layout(color) uniform half4 fireBehind;
            layout(color) uniform half4 aimAhead;
            layout(color) uniform half4 aimBehind;
            layout(color) uniform half4 outlineColor;

            half4 main(float2 xy) {
                float2 q = (xy - center) / radius;
                q.y = -q.y;
                float3 p;
                float facing;
                if (eye > 0.0) {
                    float3 e = float3(0.0, 0.0, eye);
                    float3 d = normalize(float3(q, -eye));
                    float b = dot(e, d);
                    float disc = b * b - (eye * eye - 1.0);
                    if (disc < 0.0) return half4(0.0);
                    float t = front > 0.5 ? -b - sqrt(disc) : -b + sqrt(disc);
                    p = e + t * d;
                    facing = abs(dot(p, d));
                } else {
                    float rr = dot(q, q);
                    if (rr >= 1.0) return half4(0.0);
                    float z = sqrt(1.0 - rr);
                    p = float3(q, front > 0.5 ? z : -z);
                    facing = z;
                }
                // The point in the shown frame, and its texel.
                float3 s = float3(dot(colX, p), dot(colY, p), dot(colZ, p));
                float lon = atan(s.x, -s.z);
                float lat = asin(clamp(s.y, -1.0, 1.0));
                float2 uv = float2((lon / 6.28318531 + 0.5) * mapSize.x, (0.5 - lat / 3.14159265) * mapSize.y);
                float c = float(amount.eval(uv).a);
                float gl = float(glow.eval(uv).a);
                if (c < 0.004 && gl < 0.004) return half4(0.0);
                float cs = max(c, 0.004);
                float fired = clamp(float(fire.eval(uv).a) / cs, 0.0, 1.0);
                float aimed = clamp(float(aim.eval(uv).a) / cs, 0.0, 1.0 - fired);
                // The glow's shares that are shots' and aiming's.
                float gs = max(gl, 0.004);
                float gFired = clamp(float(glowFire.eval(uv).a) / gs, 0.0, 1.0);
                float gAimed = clamp(float(glowAim.eval(uv).a) / gs, 0.0, 1.0 - gFired);
                // The point in the scene's frame: what its colour goes by.
                float3 g = float3(dot(sceneX, p), dot(sceneY, p), dot(sceneZ, p));

                float4 base;
                if (wayMode > 0.5) {
                    base = float4(clamp(float3(way.eval(uv).rgb) / cs, 0.0, 1.0), wayAlpha);
                } else {
                    // The sphere's own colour here, blended as a normal map ([LaserTrace.directionColor]).
                    float4 mid = float4(cRight + cLeft + cUp + cDown + cToward + cAway) / 6.0;
                    float3 n = abs(g);
                    base = mid + n.x * (float4(g.x >= 0.0 ? cRight : cLeft) - mid)
                        + n.y * (float4(g.y >= 0.0 ? cUp : cDown) - mid)
                        + n.z * (float4(g.z >= 0.0 ? cToward : cAway) - mid);
                }
                float back = clamp((1.0 + g.z) / 2.0, 0.0, 1.0);
                float4 fc = mix(float4(fireAhead), float4(fireBehind), back);
                float4 ac = mix(float4(aimAhead), float4(aimBehind), back);
                float4 col = base * (1.0 - fired - aimed) + fc * fired + ac * aimed;
                // The glow takes the colours of what laid it, each as strong as its own glow (up to 3: a bright bloom).
                float4 gcol = base * (1.0 - gFired - gAimed) + fc * gFired + ac * gAimed;
                float gk = glowK * (1.0 - gFired - gAimed) + fireGlowK * gFired + aimGlowK * gAimed;
                // Facing shade (darker where the surface turns away), the far half seen from inside darker still.
                float k = mix(1.0, 0.25 + 0.75 * facing, shade) * (front > 0.5 ? 1.0 : inside);
                // The scene's light on the side you see (outside on the near half, inside on the far).
                float3 nv = front > 0.5 ? p : -p;
                float diffuse = max(dot(nv, lightDir), 0.0);
                k *= mix(1.0, 0.3 + 0.9 * diffuse, light);
                float spec = shine * pow(max(dot(nv, normalize(lightDir + float3(0.0, 0.0, 1.0))), 0.0), 24.0);
                col.rgb = min(col.rgb * k + spec, float3(1.0));
                gcol.rgb = min(gcol.rgb * k, float3(1.0));
                // A glow over 100% also lights the line's core toward white, as hot light.
                float hot = clamp((fireGlowK - 1.0) / 2.0, 0.0, 1.0) * fired + clamp((aimGlowK - 1.0) / 2.0, 0.0, 1.0) * aimed;
                col.rgb = mix(col.rgb, float3(1.0), 0.6 * hot);

                float paintA = c * col.a;
                // Glow over 100% blooms: brighter, and wider as its faint edge is lifted (gl^(1/k)).
                float glowA = (gk <= 1.0 ? gk * 0.45 * gl : min(0.9, 0.45 * pow(gl, 1.0 / gk) * sqrt(gk))) * gcol.a;
                float4 o = float4(gcol.rgb, 1.0) * glowA;
                float oa = outline * smoothstep(0.2, 0.4, gl) * (1.0 - c) * float(outlineColor.a);
                o = float4(float3(outlineColor.rgb) * oa, oa) + o * (1.0 - oa);
                o = float4(col.rgb * paintA, paintA) + o * (1.0 - paintA);
                return half4(o);
            }
        """.trimIndent()
    }
}
