package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;

/**
 * Liquid-glass rendering.
 * <p>
 * {@link Backdrop} records everything behind the floating chrome into a {@link RenderNode}. Each glass
 * element re-draws that node (blurred) through an AGSL signed-distance-field shader that produces the
 * rounded silhouette, smooth "metaball" unions between shapes, edge refraction, chromatic fringe, a
 * magnifying lens and a specular rim. Falls back to blur-only (API 31/32) or a flat frosted fill.
 */
/** Prism liquid glass (copied from Prism_Music_Android Glass.java — keep the two in sync; see skill prism-liquid-glass). */
final class Glass {
    private Glass() {}

    static boolean shadersOk = Build.VERSION.SDK_INT >= 33;

    static final String SHADER =
            "uniform shader content;\n" +
            "uniform float4 s0; uniform float s0r;\n" +
            "uniform float4 s1; uniform float s1r;\n" +
            "uniform float4 s2; uniform float s2r;\n" +
            "uniform float smoothK;\n" +
            "uniform float edgeW;\n" +
            "uniform float refr;\n" +
            "uniform float chroma;\n" +
            "uniform float4 tint;\n" +
            "uniform float rim;\n" +
            "uniform float alphaMul;\n" +
            "uniform float2 magC;\n" +
            "uniform float mag;\n" +
            "uniform float outerW;\n" +       // width of the thin, stronger rim band
            "uniform float outerRefr;\n" +
            "uniform float edgeDark;\n" +     // faint dark line just inside the rim
            "uniform float2 lightDir;\n" +    // direction the specular highlight comes from
            "uniform float vibrance;\n" +     // saturation of what's seen through the glass
            "uniform float4 press;\n" +       // x, y, radius, strength of the touch glow
            "float sdRR(float2 p, float4 r, float rad) {\n" +
            "  float2 hs = r.zw * 0.5;\n" +
            "  float rr = min(rad, min(hs.x, hs.y));\n" +
            "  float2 q = abs(p - (r.xy + hs)) - hs + float2(rr, rr);\n" +
            "  return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - rr;\n" +
            "}\n" +
            "float smin(float a, float b, float k) {\n" +
            "  if (k <= 0.0) { return min(a, b); }\n" +
            "  float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);\n" +
            "  return mix(b, a, h) - k * h * (1.0 - h);\n" +
            "}\n" +
            "float scene(float2 p) {\n" +
            "  float d = sdRR(p, s0, s0r);\n" +
            "  if (s1.z > 0.5) { d = smin(d, sdRR(p, s1, s1r), smoothK); }\n" +
            "  if (s2.z > 0.5) { d = smin(d, sdRR(p, s2, s2r), smoothK); }\n" +
            "  return d;\n" +
            "}\n" +
            // height of a quarter-circle bevel: 1 at the rim, 0 once 'depth' reaches 'width'
            "float bevel(float depth, float width) {\n" +
            "  float t = clamp(depth / max(width, 0.001), 0.0, 1.0);\n" +
            "  return 1.0 - sqrt(1.0 - (1.0 - t) * (1.0 - t));\n" +
            "}\n" +
            "float4 look(float2 at) { return float4(content.eval(at)); }\n" +
            "half4 main(float2 p) {\n" +
            "  float d = scene(p);\n" +
            "  if (d > 1.5) { return half4(0.0, 0.0, 0.0, 0.0); }\n" +
            "  float2 g = float2(scene(p + float2(1.0, 0.0)) - scene(p - float2(1.0, 0.0)),\n" +
            "                    scene(p + float2(0.0, 1.0)) - scene(p - float2(0.0, 1.0)));\n" +
            "  float gl = length(g);\n" +
            "  float2 n = float2(0.0, 0.0);\n" +
            "  if (gl > 0.0001) { n = g / gl; }\n" +
            "  float depth = max(-d, 0.0);\n" +
            // body bevel + a thinner, steeper lip right at the rim
            "  float lip = 1.0 - smoothstep(0.0, max(outerW, 0.001), depth);\n" +
            "  float bend = refr * bevel(depth, edgeW) + outerRefr * bevel(depth, outerW) * lip;\n" +
            "  float2 q = magC + (p - magC) / mag;\n" +
            "  float2 off = n * bend;\n" +
            "  float4 c = look(q + off);\n" +
            "  if (chroma > 0.0 && bend > 0.05) {\n" +
            // dispersion peaks where the normal runs diagonally (rounded corners) and fades on flat sides
            "    float diag = clamp(abs(n.x * n.y) * 2.0, 0.0, 1.0);\n" +
            "    float spread = chroma * (0.3 + 0.7 * diag);\n" +
            "    float4 r1 = look(q + off * (1.0 - spread));\n" +
            "    float4 r2 = look(q + off * (1.0 - 0.5 * spread));\n" +
            "    float4 b1 = look(q + off * (1.0 + 0.5 * spread));\n" +
            "    float4 b2 = look(q + off * (1.0 + spread));\n" +
            "    c = float4((r1.r + r2.r) * 0.5, (r2.g + c.g * 2.0 + b1.g) * 0.25, (b1.b + b2.b) * 0.5, c.a);\n" +
            "  }\n" +
            // vibrancy: what shows through the glass is a little richer than the page itself
            "  float lum = dot(c.rgb, float3(0.2126, 0.7152, 0.0722));\n" +
            "  c.rgb = clamp(mix(float3(lum), c.rgb, vibrance), 0.0, 1.0);\n" +
            "  c = float4(mix(c.rgb, tint.rgb, tint.a), mix(c.a, 1.0, tint.a));\n" +
            // touch glow: soft gaussian bloom around the finger plus a faint lift of the whole surface
            "  if (press.w > 0.001) {\n" +
            "    float2 k = (p - press.xy) / max(press.z, 1.0);\n" +
            "    float bloom = exp(-0.5 * dot(k, k)) * press.w;\n" +
            "    float3 lifted = c.rgb * 1.08 + float3(0.07);\n" +
            "    c.rgb = mix(c.rgb, lifted, clamp(bloom + press.w * 0.25, 0.0, 1.0));\n" +
            "  }\n" +
            // rim: thin dark inner line, then a highlight strongest facing the light and weaker opposite it
            "  float line = smoothstep(-3.0, -1.2, d) * (1.0 - smoothstep(-1.2, 0.2, d));\n" +
            "  float facing = max(dot(n, -lightDir), 0.0);\n" +
            "  float back = max(dot(n, lightDir), 0.0);\n" +
            "  float shine = facing * facing + 0.45 * back * back;\n" +
            "  c.rgb = c.rgb * (1.0 - edgeDark * line * (1.0 - shine));\n" +
            "  float rimMask = smoothstep(-3.5, 0.0, d) * (1.0 - smoothstep(0.0, 1.0, d));\n" +
            "  c.rgb = c.rgb + float3(rim * rimMask * (0.25 + 0.75 * shine));\n" +
            "  float a = clamp(0.5 - d, 0.0, 1.0) * alphaMul;\n" +
            "  return half4(c * a);\n" +
            "}\n";
    static RuntimeShader newShader() {
        if (!shadersOk) return null;
        try {
            return new RuntimeShader(SHADER);
        } catch (Throwable t) {
            // older Skia builds only know the legacy sample(shader, coord) spelling
            try {
                return new RuntimeShader(SHADER.replace("content.eval(at)", "sample(content, at)"));
            } catch (Throwable t2) {
                shadersOk = false;
                return null;
            }
        }
    }

    /** Container whose drawing is mirrored into a RenderNode so glass can sample it. */
    static final class Backdrop extends FrameLayout {
        final RenderNode node = new RenderNode("backdrop");
        boolean recorded;

        Backdrop(Context c) { super(c); }

        @Override public void draw(Canvas canvas) {
            if (!canvas.isHardwareAccelerated()) { super.draw(canvas); recorded = false; return; }
            node.setPosition(0, 0, getWidth(), getHeight());
            RecordingCanvas rc = node.beginRecording(getWidth(), getHeight());
            try {
                super.draw(rc);
            } finally {
                node.endRecording();
            }
            recorded = true;
            canvas.drawRenderNode(node);
        }
    }

    /** One glass surface made of up to three rounded rects (smooth-unioned). Coordinates are view-local. */
    static final class Surface {
        final RectF a = new RectF(), b = new RectF(), c = new RectF();
        float ra, rb, rc;
        float smoothK;
        float blurDp = 14f;
        float refractDp = 9f, edgeDp = 16f, chroma = 0.0f, rim = 0.18f;
        float mag = 1f, magX, magY;
        // edgeDark / shadow keep glass visible even over a pure-white page, as in the reference
        float outerDp = 5f, outerRefractDp = 5f, edgeDark = 0.32f, vibrance = 1.25f;
        float lightX = -0.45f, lightY = -0.89f;
        /** Touch glow in host-local px; strength 0 = off. */
        float pressX, pressY, pressR, pressAmt;
        int tint = Ui.glassTint();
        float alpha = 1f;
        boolean blur = true;
        /** Optional node (in host-local coordinates) composited over the backdrop before refraction. */
        RenderNode extra;

        private final RenderNode node = new RenderNode("glass");
        private final RuntimeShader shader = Glass.newShader();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF tmp = new RectF();
        private float lastBlur = -1;
        private RenderEffect blurEffect;
        int strokeColor = Ui.glassStroke();
        float shadowAlpha = 0.16f;

        Surface() {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, Ui.dpf(0.6f)));
        }

        void shapes(RectF ra0, float r0, RectF rb0, float r1, RectF rc0, float r2) {
            a.set(ra0); ra = r0;
            if (rb0 != null) { b.set(rb0); rb = r1; } else b.setEmpty();
            if (rc0 != null) { c.set(rc0); rc = r2; } else c.setEmpty();
        }

        Path outline() {
            path.reset();
            if (!a.isEmpty()) path.addRoundRect(a, Math.min(ra, a.height() / 2), Math.min(ra, a.height() / 2), Path.Direction.CW);
            if (!b.isEmpty()) path.addRoundRect(b, Math.min(rb, b.height() / 2), Math.min(rb, b.height() / 2), Path.Direction.CW);
            if (!c.isEmpty()) path.addRoundRect(c, Math.min(rc, c.height() / 2), Math.min(rc, c.height() / 2), Path.Direction.CW);
            return path;
        }

        /** Draws the glass. {@code host} is the view whose canvas we draw into; {@code backdrop} supplies content. */
        void draw(Canvas canvas, View host, Backdrop backdrop) {
            if (alpha <= 0.001f || a.isEmpty()) return;
            outline();
            // soft drop shadow underneath (hidden inside because glass output is opaque there)
            if (shadowAlpha > 0) {
                fill.setColor(0x01FFFFFF);
                fill.setShadowLayer(Ui.dpf(14), 0, Ui.dpf(4), Ui.alpha(0xFF000000, shadowAlpha * alpha * (Ui.dark ? 2.2f : 1f)));
                canvas.drawPath(path, fill);
                fill.clearShadowLayer();
            }
            // a surface may refract the page (backdrop) and/or its own host content (extra), e.g. a slider thumb over its track
            boolean hw = canvas.isHardwareAccelerated() && Ui.glass && ((backdrop != null && backdrop.recorded) || extra != null);
            if (hw) {
                float pad = Ui.dpf(blurDp) * 2f + Ui.dpf(refractDp);
                tmp.set(a);
                if (!b.isEmpty()) tmp.union(b);
                if (!c.isEmpty()) tmp.union(c);
                float[] off = backdrop != null ? offsetIn(host, backdrop) : new float[]{0f, 0f};
                int w = (int) Math.ceil(tmp.width() + pad * 2), h = (int) Math.ceil(tmp.height() + pad * 2);
                float ox = tmp.left - pad, oy = tmp.top - pad;
                node.setPosition(0, 0, w, h);
                RenderEffect fx = null;
                float br = blur ? Ui.dpf(blurDp) : 0f;
                if (br > 0.5f) {
                    if (br != lastBlur || blurEffect == null) {
                        blurEffect = RenderEffect.createBlurEffect(br, br, Shader.TileMode.CLAMP);
                        lastBlur = br;
                    }
                    fx = blurEffect;
                }
                if (shader != null && Glass.shadersOk) {
                    // rebuild the effect only when something that feeds the shader actually changed:
                    // avoids allocating effects every frame while the page scrolls underneath
                    int sig = signature(-ox, -oy, br);
                    if (sig != lastSig || cachedFx == null) {
                        setUniforms(-ox, -oy);
                        RenderEffect sh = RenderEffect.createRuntimeShaderEffect(shader, "content");
                        cachedFx = fx == null ? sh : RenderEffect.createChainEffect(sh, fx);
                        lastSig = sig;
                        node.setRenderEffect(cachedFx);
                    }
                } else {
                    node.setRenderEffect(fx);
                }
                RecordingCanvas rc = node.beginRecording(w, h);
                try {
                    rc.translate(-ox, -oy);
                    if (backdrop != null && backdrop.recorded) {
                        rc.save();
                        rc.translate(-off[0], -off[1]);
                        rc.drawRenderNode(backdrop.node);
                        rc.restore();
                    }
                    if (extra != null) rc.drawRenderNode(extra);
                } finally {
                    node.endRecording();
                }
                canvas.save();
                if (shader == null || !Glass.shadersOk) {
                    canvas.clipPath(path);
                }
                canvas.translate(ox, oy);
                if (shader == null || !Glass.shadersOk) {
                    node.setAlpha(alpha);
                    canvas.drawRenderNode(node);
                    canvas.restore();
                    fill.setColor(Ui.alpha(tint, alpha));
                    canvas.drawPath(path, fill);
                } else {
                    node.setAlpha(1f);
                    canvas.drawRenderNode(node);
                    canvas.restore();
                }
            } else {
                int base = Ui.mix(Ui.bg(), tint | 0xFF000000, 0.55f);
                fill.setColor(Ui.alpha(base, 0.96f * alpha));
                canvas.drawPath(path, fill);
            }
            if (strokeColor != 0) {
                stroke.setColor(Ui.alpha(strokeColor, alpha));
                canvas.drawPath(path, stroke);
            }
        }

        private RenderEffect cachedFx;
        private int lastSig;

        private int signature(float dx, float dy, float br) {
            float[] v = {a.left + dx, a.top + dy, a.right + dx, a.bottom + dy, ra, b.left + dx, b.top + dy, b.right + dx, b.bottom + dy, rb,
                    c.left + dx, c.top + dy, c.right + dx, c.bottom + dy, rc, smoothK, refractDp, edgeDp, chroma, rim, alpha, mag, magX + dx, magY + dy,
                    outerDp, outerRefractDp, edgeDark, vibrance, pressX + dx, pressY + dy, pressR, pressAmt, br, tint, Ui.dark ? 1 : 0};
            int h = 17;
            // quantise positions to 1/4 px so sub-pixel jitter doesn't force a rebuild
            for (float f : v) h = h * 31 + Math.round(f * 4f);
            return h;
        }

        private void setUniforms(float dx, float dy) {
            RuntimeShader s = shader;
            s.setFloatUniform("s0", a.left + dx, a.top + dy, a.width(), a.height());
            s.setFloatUniform("s0r", Math.min(ra, a.height() / 2f));
            if (!b.isEmpty()) {
                s.setFloatUniform("s1", b.left + dx, b.top + dy, b.width(), b.height());
                s.setFloatUniform("s1r", Math.min(rb, b.height() / 2f));
            } else { s.setFloatUniform("s1", 0f, 0f, 0f, 0f); s.setFloatUniform("s1r", 0f); }
            if (!c.isEmpty()) {
                s.setFloatUniform("s2", c.left + dx, c.top + dy, c.width(), c.height());
                s.setFloatUniform("s2r", Math.min(rc, c.height() / 2f));
            } else { s.setFloatUniform("s2", 0f, 0f, 0f, 0f); s.setFloatUniform("s2r", 0f); }
            s.setFloatUniform("smoothK", smoothK);
            s.setFloatUniform("edgeW", Ui.dpf(edgeDp));
            s.setFloatUniform("refr", Ui.dpf(refractDp));
            s.setFloatUniform("chroma", chroma);
            float ta = ((tint >>> 24) / 255f);
            s.setFloatUniform("tint", ((tint >> 16) & 255) / 255f, ((tint >> 8) & 255) / 255f, (tint & 255) / 255f, ta);
            s.setFloatUniform("rim", rim);
            s.setFloatUniform("alphaMul", alpha);
            s.setFloatUniform("magC", magX + dx, magY + dy);
            s.setFloatUniform("mag", Math.max(0.2f, mag));
            s.setFloatUniform("outerW", Ui.dpf(outerDp));
            s.setFloatUniform("outerRefr", Ui.dpf(outerRefractDp));
            s.setFloatUniform("edgeDark", Ui.dark ? edgeDark * 0.4f : edgeDark);
            s.setFloatUniform("lightDir", lightX, lightY);
            s.setFloatUniform("vibrance", vibrance);
            s.setFloatUniform("press", pressX + dx, pressY + dy, Math.max(1f, pressR), pressAmt);
        }
    }

    /** Offset of {@code v}'s origin inside {@code backdrop}, accounting for translations and scrolls. */
    static float[] offsetIn(View v, View backdrop) {
        float[] a = abs(v), b = abs(backdrop);
        return new float[]{a[0] - b[0], a[1] - b[1]};
    }

    static float[] abs(View v) {
        float x = 0, y = 0;
        View cur = v;
        while (cur != null) {
            x += cur.getLeft() + cur.getTranslationX();
            y += cur.getTop() + cur.getTranslationY();
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            View pv = (View) p;
            x -= pv.getScrollX();
            y -= pv.getScrollY();
            cur = pv;
        }
        return new float[]{x, y};
    }
}
