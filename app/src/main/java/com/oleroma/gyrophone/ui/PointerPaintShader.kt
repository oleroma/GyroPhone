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
    private var map: Bitmap? = null
    private var wayMap: Bitmap? = null
    private var uploaded = Long.MIN_VALUE
    private val col = FloatArray(3)

    /** What every draw of one frame shares. */
    class Look(
        val eye: Float, val snakeDeg: Float, val byWay: Boolean, val soft: Float, val glow: Float, val outline: Boolean,
        val shade: Float, val inside: Float, val moves: IntArray, val fireAhead: Int, val fireBehind: Int,
        val outlineColor: Int, val wayAlpha: Float,
    )

    /**
     * Draws [lp]'s paint on the sphere of radius [r] at ([cx], [cy]): its [front] (near) half or far
     * half, the shown frame seen through [view] (shown-frame direction to picture, as [LaserTrace.view]).
     */
    fun draw(canvas: Canvas, lp: LaserPaint, front: Boolean, cx: Float, cy: Float, r: Float, view: (Float, Float, Float, FloatArray) -> Unit, look: Look) {
        val s = if (front) near else far
        if (lp.version != uploaded || map == null) {
            val bm = map ?: Bitmap.createBitmap(lp.width, lp.height, Bitmap.Config.ARGB_8888).also { map = it }
            bm.setPixels(lp.data, 0, lp.width, 0, 0, lp.width, lp.height)
            val ways = lp.way
            if (ways != null) {
                val wb = wayMap ?: Bitmap.createBitmap(lp.width, lp.height, Bitmap.Config.ARGB_8888).also { wayMap = it }
                wb.setPixels(ways, 0, lp.width, 0, 0, lp.width, lp.height)
            }
            uploaded = lp.version
            // New shaders for the changed pixels, so the GPU takes them up.
            for (sh in arrayOf(near, far)) {
                sh.setInputBuffer("paint", BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                sh.setInputBuffer("way", BitmapShader(wayMap ?: bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
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
        s.setFloatUniform("newest", lp.newest.toFloat())
        s.setFloatUniform("snake", if (look.snakeDeg > 0f) look.snakeDeg / LaserPaint.ALONG_UNIT else 0f)
        s.setFloatUniform("wayMode", if (look.byWay && lp.way != null) 1f else 0f)
        s.setFloatUniform("edge", LaserPaint.EDGE)
        s.setFloatUniform("soft", look.soft)
        s.setFloatUniform("glow", look.glow)
        s.setFloatUniform("outline", if (look.outline) 1f else 0f)
        s.setFloatUniform("shade", look.shade)
        s.setFloatUniform("inside", look.inside)
        s.setFloatUniform("wayAlpha", look.wayAlpha)
        for ((i, name) in MOVE_NAMES.withIndex()) s.setColorUniform(name, look.moves[i])
        s.setColorUniform("fireAhead", look.fireAhead)
        s.setColorUniform("fireBehind", look.fireBehind)
        s.setColorUniform("outlineColor", look.outlineColor)
        paint.shader = s
        val e = r * 1.05f
        canvas.drawRect(cx - e, cy - e, cx + e, cy + e, paint)
    }

    companion object {
        // In LaserTrace.directionColor's order.
        private val MOVE_NAMES = arrayOf("cRight", "cLeft", "cUp", "cDown", "cToward", "cAway")

        private val SOURCE = """
            uniform shader paint;
            uniform shader way;
            uniform float2 mapSize;
            uniform float2 center;
            uniform float radius;
            uniform float eye;
            uniform float front;
            uniform float3 colX;
            uniform float3 colY;
            uniform float3 colZ;
            uniform float newest;
            uniform float snake;
            uniform float wayMode;
            uniform float edge;
            uniform float soft;
            uniform float glow;
            uniform float outline;
            uniform float shade;
            uniform float inside;
            uniform float wayAlpha;
            layout(color) uniform half4 cRight;
            layout(color) uniform half4 cLeft;
            layout(color) uniform half4 cUp;
            layout(color) uniform half4 cDown;
            layout(color) uniform half4 cToward;
            layout(color) uniform half4 cAway;
            layout(color) uniform half4 fireAhead;
            layout(color) uniform half4 fireBehind;
            layout(color) uniform half4 outlineColor;

            float2 at(float i, float j) {
                return float2(mod(i, mapSize.x) + 0.5, clamp(j, 0.0, mapSize.y - 1.0) + 0.5);
            }

            // A texel's reach (as much as shows) and its share fired.
            float2 cell(float i, float j) {
                float4 t = float4(paint.eval(at(i, j)));
                float r8 = floor(t.r * 255.0 + 0.5);
                float c = floor(r8 / 2.0) / 127.0;
                if (snake > 0.0) {
                    float a = floor(t.g * 255.0 + 0.5) * 256.0 + floor(t.b * 255.0 + 0.5);
                    float age = mod(newest - a + 65536.0, 65536.0);
                    c *= clamp((snake - age) / (0.2 * snake), 0.0, 1.0);
                }
                return float2(c, c * mod(r8, 2.0));
            }

            float3 wayAt(float i, float j) {
                return float3(way.eval(at(i, j)).rgb);
            }

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
                float u = (lon / 6.28318531 + 0.5) * mapSize.x - 0.5;
                float v = (0.5 - lat / 3.14159265) * mapSize.y - 0.5;
                float i0 = floor(u);
                float j0 = floor(v);
                float fx = u - i0;
                float fy = v - j0;
                float w00 = (1.0 - fx) * (1.0 - fy);
                float w10 = fx * (1.0 - fy);
                float w01 = (1.0 - fx) * fy;
                float w11 = fx * fy;
                float2 a00 = cell(i0, j0);
                float2 a10 = cell(i0 + 1.0, j0);
                float2 a01 = cell(i0, j0 + 1.0);
                float2 a11 = cell(i0 + 1.0, j0 + 1.0);
                float c = w00 * a00.x + w10 * a10.x + w01 * a01.x + w11 * a11.x;
                if (c < 0.004) return half4(0.0);
                float fired = (w00 * a00.y + w10 * a10.y + w01 * a01.y + w11 * a11.y) / c;

                float4 base;
                if (wayMode > 0.5) {
                    float3 w = w00 * a00.x * wayAt(i0, j0) + w10 * a10.x * wayAt(i0 + 1.0, j0)
                        + w01 * a01.x * wayAt(i0, j0 + 1.0) + w11 * a11.x * wayAt(i0 + 1.0, j0 + 1.0);
                    base = float4(w / c, wayAlpha);
                } else {
                    // The sphere's own colour here, blended as a normal map ([LaserTrace.directionColor]).
                    float4 mid = float4(cRight + cLeft + cUp + cDown + cToward + cAway) / 6.0;
                    float3 n = abs(s);
                    base = mid + n.x * (float4(s.x >= 0.0 ? cRight : cLeft) - mid)
                        + n.y * (float4(s.y >= 0.0 ? cUp : cDown) - mid)
                        + n.z * (float4(s.z >= 0.0 ? cToward : cAway) - mid);
                }
                float4 fc = mix(float4(fireAhead), float4(fireBehind), clamp((1.0 + s.z) / 2.0, 0.0, 1.0));
                float4 col = mix(base, fc, fired);
                // Facing shade (darker where the surface turns away), the far half seen from inside darker still.
                float k = mix(1.0, 0.25 + 0.75 * facing, shade) * (front > 0.5 ? 1.0 : inside);
                col.rgb *= k;

                float sw = mix(0.03, 0.3, soft);
                float paintA = smoothstep(edge - sw, edge + sw, c) * col.a;
                float glowA = glow * 0.45 * smoothstep(0.0, edge, c) * col.a;
                float4 o = float4(col.rgb, 1.0) * glowA;
                float oa = outline * smoothstep(edge - sw - 0.14, edge - sw - 0.05, c) * float(outlineColor.a);
                o = float4(float3(outlineColor.rgb) * oa, oa) + o * (1.0 - oa);
                o = float4(col.rgb * paintA, paintA) + o * (1.0 - paintA);
                return half4(o);
            }
        """.trimIndent()
    }
}
