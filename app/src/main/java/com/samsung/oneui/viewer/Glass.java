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
import android.widget.FrameLayout;

/**
 * Optical glass rendering engine inspired by Kyant0/AndroidLiquidGlass and MeiloX.
 * Combines analytic rounded-rect SDF, spherical {@code circleMap} lens refraction with
 * {@code depthEffect}, physically weighted 7-band spectral chromatic dispersion,
 * 45-degree directional rim highlight, vibrancy boost, inner shadow, and smooth-union
 * metaball morphing ({@code smin}) + touch bloom. Safely degrades on API 26–32.
 */
final class Glass {
    private Glass() {}

    static boolean shadersOk = Build.VERSION.SDK_INT >= 33;

    static final String SHADER =
            "uniform shader content;\n" +
            "uniform float2 iRes;\n" +
            "uniform float4 s0, s1, s2;\n" +
            "uniform float3 radii;\n" +
            "uniform float smoothK, refr, edgeW, outerW, outerRefr, chroma, rim, vib, depthEff, innerShad;\n" +
            "uniform float mag;\n" +
            "uniform float2 magCenter;\n" +
            "uniform float4 tint;\n" +
            "uniform float alpha, dark;\n" +
            "uniform float2 pressPt;\n" +
            "uniform float pressR, pressAmt;\n" +
            "\n" +
            "float rbox(float2 p, float4 b, float r) {\n" +
            "    if (b.z <= 0.0 || b.w <= 0.0) return 1e5;\n" +
            "    float rr = min(r, min(b.z, b.w));\n" +
            "    float2 q = abs(p - b.xy) - (b.zw - float2(rr));\n" +
            "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rr;\n" +
            "}\n" +
            "float2 gradSdRR(float2 cc, float2 hs, float rad) {\n" +
            "    float2 corner = abs(cc) - (hs - float2(rad));\n" +
            "    if (corner.x >= 0.0 || corner.y >= 0.0) {\n" +
            "        float2 mc = max(corner, float2(0.0));\n" +
            "        float ml = length(mc);\n" +
            "        return ml > 1e-4 ? sign(cc) * (mc / ml) : float2(0.0);\n" +
            "    } else {\n" +
            "        float gx = step(corner.y, corner.x);\n" +
            "        return sign(cc) * float2(gx, 1.0 - gx);\n" +
            "    }\n" +
            "}\n" +
            "float smin(float a, float b, float k) {\n" +
            "    if (k <= 0.001) return min(a, b);\n" +
            "    float h = max(k - abs(a - b), 0.0) / k;\n" +
            "    return min(a, b) - h * h * k * 0.25;\n" +
            "}\n" +
            "float sdf(float2 p) {\n" +
            "    float d = rbox(p, s0, radii.x);\n" +
            "    d = smin(d, rbox(p, s1, radii.y), smoothK);\n" +
            "    d = smin(d, rbox(p, s2, radii.z), smoothK);\n" +
            "    return d;\n" +
            "}\n" +
            "float circleMap(float x) {\n" +
            "    float c = clamp(x, 0.0, 1.0);\n" +
            "    return 1.0 - sqrt(max(0.0, 1.0 - c * c));\n" +
            "}\n" +
            "half4 sampleC(float2 p) {\n" +
            "    return content.eval(clamp(p, float2(0.5), iRes - float2(0.5)));\n" +
            "}\n" +
            "half4 main(float2 p) {\n" +
            "    float d = sdf(p);\n" +
            "    float mask = clamp(0.5 - d, 0.0, 1.0);\n" +
            "    if (mask <= 0.001) return half4(0.0);\n" +
            "    float depth = max(-d, 0.0);\n" +
            "    bool single = (s1.z <= 0.5 && s2.z <= 0.5);\n" +
            "    float2 hs = max(s0.zw, float2(1.0));\n" +
            "    float2 cc = p - s0.xy;\n" +
            "    float2 gRim;\n" +
            "    float2 n;\n" +
            "    if (single) {\n" +
            "        float gradR = min(radii.x * 1.5, min(hs.x, hs.y));\n" +
            "        gRim = gradSdRR(cc, hs, gradR);\n" +
            "        float cl = length(cc);\n" +
            "        float2 radial = cl > 1e-4 ? (cc / cl) : float2(0.0);\n" +
            "        float2 comb = gRim + depthEff * radial;\n" +
            "        float cl2 = length(comb);\n" +
            "        n = cl2 > 1e-4 ? (comb / cl2) : gRim;\n" +
            "    } else {\n" +
            "        float2 e = float2(1.0, 0.0);\n" +
            "        float2 g = float2(sdf(p + e.xy) - sdf(p - e.xy), sdf(p + e.yx) - sdf(p - e.yx));\n" +
            "        float gl = length(g);\n" +
            "        gRim = gl > 1e-4 ? g / gl : float2(0.0, 1.0);\n" +
            "        n = gRim;\n" +
            "    }\n" +
            "    float tIn = clamp(1.0 - depth / max(edgeW, 1.0), 0.0, 1.0);\n" +
            "    float tOut = clamp(1.0 - depth / max(outerW, 1.0), 0.0, 1.0);\n" +
            "    float bend = refr * circleMap(tIn) + outerRefr * circleMap(tOut) * tOut;\n" +
            "    float2 baseP = magCenter + (p - magCenter) / max(mag, 0.01);\n" +
            "    float2 off = n * bend;\n" +
            "    float2 uv = baseP + off;\n" +
            "    float3 rgb;\n" +
            "    if (chroma > 0.005 && bend > 0.05) {\n" +
            "        float cw = single ? clamp(abs((cc.x * cc.y) / max(hs.x * hs.y, 1.0)) * 1.45, 0.0, 1.0) : clamp(abs(gRim.x * gRim.y) * 2.0, 0.0, 1.0);\n" +
            "        float2 disp = off * (chroma * (0.16 + 0.84 * cw));\n" +
            "        float3 red = float3(sampleC(uv + disp).rgb);\n" +
            "        float3 orange = float3(sampleC(uv + disp * 0.666667).rgb);\n" +
            "        float3 yellow = float3(sampleC(uv + disp * 0.333333).rgb);\n" +
            "        float3 green = float3(sampleC(uv).rgb);\n" +
            "        float3 cyan = float3(sampleC(uv - disp * 0.333333).rgb);\n" +
            "        float3 blue = float3(sampleC(uv - disp * 0.666667).rgb);\n" +
            "        float3 purple = float3(sampleC(uv - disp).rgb);\n" +
            "        rgb.r = (red.r + orange.r + yellow.r) / 3.5 + purple.r / 7.0;\n" +
            "        rgb.g = orange.g / 7.0 + (yellow.g + green.g + cyan.g) / 3.5;\n" +
            "        rgb.b = (cyan.b + blue.b + purple.b) / 3.0;\n" +
            "    } else {\n" +
            "        rgb = float3(sampleC(uv).rgb);\n" +
            "    }\n" +
            "    float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));\n" +
            "    rgb = clamp(mix(float3(luma), rgb, vib), 0.0, 1.0);\n" +
            "    rgb = mix(rgb, tint.rgb, tint.a);\n" +
            "    if (innerShad > 0.001) {\n" +
            "        float isBand = 1.0 - smoothstep(0.0, max(outerW * 2.2, 6.0), depth);\n" +
            "        rgb *= (1.0 - innerShad * 0.14 * isBand);\n" +
            "    }\n" +
            "    float hd = dot(gRim, float2(0.7071, 0.7071));\n" +
            "    float hiMag = pow(abs(hd), 1.35);\n" +
            "    float shine = max(-hd, 0.0) * hiMag + 0.42 * max(hd, 0.0) * hiMag;\n" +
            "    float edge = smoothstep(3.4, 0.0, depth);\n" +
            "    float darkLine = smoothstep(-3.0, -1.2, d) * (1.0 - smoothstep(-1.2, 0.2, d));\n" +
            "    rgb *= (1.0 - (dark > 0.5 ? 0.12 : 0.26) * darkLine * (1.0 - shine));\n" +
            "    rgb = clamp(rgb + float3(edge * (0.22 + 0.88 * shine) * rim), 0.0, 1.0);\n" +
            "    if (pressAmt > 0.001 && pressR > 1.0) {\n" +
            "        float pd = length(p - pressPt) / pressR;\n" +
            "        float glow = exp(-pd * pd * 1.35) * pressAmt;\n" +
            "        float pEdge = edge * clamp(1.0 - pd * 0.7, 0.0, 1.0) * pressAmt;\n" +
            "        rgb = mix(rgb, float3(1.0), clamp(glow * 0.44 + pEdge * 0.42, 0.0, 0.82));\n" +
            "    }\n" +
            "    float a = mask * alpha;\n" +
            "    return half4(half3(rgb * a), half(a));\n" +
            "}\n";

    static final class Backdrop extends FrameLayout {
        final Object nodeObj;
        boolean recorded;
        private boolean dirty = true;

        Backdrop(Context c) {
            super(c);
            nodeObj = Build.VERSION.SDK_INT >= 29 ? new RenderNode("glass-backdrop") : null;
        }

        @Override public void onDescendantInvalidated(View child, View target) {
            dirty = true;
            super.onDescendantInvalidated(child, target);
        }

        @Override public void invalidate() {
            dirty = true;
            super.invalidate();
        }

        boolean isBackdropDirty() { return dirty; }

        @Override protected void dispatchDraw(Canvas c) {
            dirty = false;
            if (Build.VERSION.SDK_INT >= 29 && nodeObj != null && c.isHardwareAccelerated() && getWidth() > 0 && getHeight() > 0) {
                RenderNode node = (RenderNode) nodeObj;
                node.setPosition(0, 0, getWidth(), getHeight());
                RecordingCanvas rc = node.beginRecording(getWidth(), getHeight());
                try {
                    super.dispatchDraw(rc);
                } finally {
                    node.endRecording();
                }
                recorded = true;
                c.drawRenderNode(node);
            } else {
                super.dispatchDraw(c);
            }
        }
    }

    static final class Surface {
        final Object nodeObj;
        private Object shaderObj;
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF[] shapes = {new RectF(), new RectF(), new RectF()};
        private final float[] radii = new float[3];
        private final RectF bounds = new RectF();

        float smoothK;
        float blurDp = 2.5f;
        float refractDp = 15f;
        float edgeDp = 22f;
        float outerDp = 5.5f;
        float outerRefractDp = 11f;
        float chroma = 0.75f;
        float rim = 0.45f;
        float vibrance = 1.30f;
        float depthEffect = 0.38f;
        float innerShadow = 0f;
        float mag = 1f;
        float magX, magY;
        float pressX, pressY, pressR, pressAmt;
        int tint = 0x52FFFFFF;
        float alpha = 1f;
        float shadowAlpha = 0.14f;
        int strokeColor = 0x26000000;
        boolean blur = true;
        Object extra;

        Surface() {
            nodeObj = Build.VERSION.SDK_INT >= 29 ? new RenderNode("glass-surface") : null;
            shadowPaint.setColor(0x01000000);
            strokePaint.setStyle(Paint.Style.STROKE);
        }

        void shapes(RectF a, float ra, RectF b, float rb, RectF c, float rc) {
            set(0, a, ra);
            set(1, b, rb);
            set(2, c, rc);
        }

        private void set(int i, RectF r, float rad) {
            if (r == null || r.isEmpty()) { shapes[i].setEmpty(); radii[i] = 0; }
            else { shapes[i].set(r); radii[i] = rad; }
        }

        void draw(Canvas canvas, View host, Backdrop backdrop) {
            if (alpha <= 0.01f) return;
            bounds.setEmpty();
            for (RectF r : shapes) if (!r.isEmpty()) bounds.union(r);
            if (bounds.isEmpty()) return;

            if (shadowAlpha > 0.005f) {
                shadowPaint.setShadowLayer(Ui.dpf(13), 0, Ui.dpf(5), Ui.alpha(0xFF000000, shadowAlpha * alpha));
                for (int i = 0; i < 3; i++) {
                    if (!shapes[i].isEmpty()) canvas.drawRoundRect(shapes[i], radii[i], radii[i], shadowPaint);
                }
            }

            if (Ui.gStyle) {
                drawGStyle(canvas, host, backdrop);
                return;
            }

            boolean canShade = Build.VERSION.SDK_INT >= 33 && shadersOk && Ui.glass
                    && canvas.isHardwareAccelerated() && nodeObj != null
                    && ((backdrop != null && backdrop.recorded && backdrop.nodeObj != null) || extra != null);
            if (canShade) {
                drawAgsl(canvas, host, backdrop);
                return;
            }

            if (Build.VERSION.SDK_INT >= 31 && Ui.glass && canvas.isHardwareAccelerated() && nodeObj != null
                    && ((backdrop != null && backdrop.recorded && backdrop.nodeObj != null) || extra != null)) {
                drawBlur31(canvas, host, backdrop);
                return;
            }

            drawFallback(canvas);
        }

        private void drawAgsl(Canvas canvas, View host, Backdrop backdrop) {
            if (Build.VERSION.SDK_INT < 33) return;
            int pad = Math.round(Ui.dpf(refractDp + outerRefractDp + 6));
            int l = Math.max(0, (int) Math.floor(bounds.left) - pad);
            int t = Math.max(0, (int) Math.floor(bounds.top) - pad);
            int r = Math.min(host.getWidth(), (int) Math.ceil(bounds.right) + pad);
            int b = Math.min(host.getHeight(), (int) Math.ceil(bounds.bottom) + pad);
            int w = r - l, h = b - t;
            if (w <= 0 || h <= 0) return;

            try {
                if (shaderObj == null) {
                    try { shaderObj = new RuntimeShader(SHADER); }
                    catch (Throwable t1) { shaderObj = new RuntimeShader(SHADER.replace("content.eval(", "sample(content, ")); }
                }
                RuntimeShader shader = (RuntimeShader) shaderObj;
                shader.setFloatUniform("iRes", (float) w, (float) h);
                for (int i = 0; i < 3; i++) {
                    RectF s = shapes[i];
                    if (s.isEmpty()) shader.setFloatUniform("s" + i, 0f, 0f, 0f, 0f);
                    else shader.setFloatUniform("s" + i, s.centerX() - l, s.centerY() - t, s.width() * 0.5f, s.height() * 0.5f);
                }
                shader.setFloatUniform("radii", radii[0], radii[1], radii[2]);
                shader.setFloatUniform("smoothK", smoothK);
                shader.setFloatUniform("refr", Ui.dpf(refractDp));
                shader.setFloatUniform("edgeW", Ui.dpf(edgeDp));
                shader.setFloatUniform("outerW", Ui.dpf(outerDp));
                shader.setFloatUniform("outerRefr", Ui.dpf(outerRefractDp));
                shader.setFloatUniform("chroma", chroma);
                shader.setFloatUniform("rim", rim);
                shader.setFloatUniform("vib", vibrance);
                shader.setFloatUniform("depthEff", depthEffect);
                shader.setFloatUniform("innerShad", innerShadow);
                shader.setFloatUniform("mag", mag);
                shader.setFloatUniform("magCenter", (magX == 0 ? bounds.centerX() : magX) - l, (magY == 0 ? bounds.centerY() : magY) - t);
                int tc = tint;
                shader.setFloatUniform("tint", ((tc >> 16) & 255) / 255f, ((tc >> 8) & 255) / 255f, (tc & 255) / 255f, (tc >>> 24) / 255f);
                shader.setFloatUniform("alpha", alpha);
                shader.setFloatUniform("dark", Ui.dark ? 1f : 0f);
                shader.setFloatUniform("pressPt", pressX - l, pressY - t);
                shader.setFloatUniform("pressR", pressR);
                shader.setFloatUniform("pressAmt", pressAmt);

                RenderEffect fx = RenderEffect.createRuntimeShaderEffect(shader, "content");
                if (blur && blurDp > 0.2f) {
                    float bp = Ui.dpf(blurDp);
                    fx = RenderEffect.createChainEffect(fx, RenderEffect.createBlurEffect(bp, bp, Shader.TileMode.CLAMP));
                }

                RenderNode node = (RenderNode) nodeObj;
                node.setPosition(l, t, r, b);
                node.setRenderEffect(fx);
                RecordingCanvas rc = node.beginRecording(w, h);
                try {
                    if (backdrop != null && backdrop.recorded && backdrop.nodeObj != null) {
                        float[] off = offsetIn(host, backdrop);
                        rc.save();
                        rc.translate(-(off[0] + l), -(off[1] + t));
                        rc.drawRenderNode((RenderNode) backdrop.nodeObj);
                        rc.restore();
                    }
                    if (extra instanceof RenderNode) {
                        rc.save();
                        rc.translate(-l, -t);
                        rc.drawRenderNode((RenderNode) extra);
                        rc.restore();
                    }
                } finally {
                    node.endRecording();
                }
                canvas.drawRenderNode(node);

                if (strokeColor != 0 && smoothK <= 0.5f) {
                    strokePaint.setColor(Ui.alpha(strokeColor, alpha));
                    strokePaint.setStrokeWidth(Math.max(1f, Ui.dpf(0.55f)));
                    for (int i = 0; i < 3; i++) {
                        if (!shapes[i].isEmpty()) canvas.drawRoundRect(shapes[i], radii[i], radii[i], strokePaint);
                    }
                }
            } catch (Throwable ignored) {
                shadersOk = false;
                drawFallback(canvas);
            }
        }

        private void drawBlur31(Canvas canvas, View host, Backdrop backdrop) {
            if (Build.VERSION.SDK_INT < 31 || nodeObj == null) { drawFallback(canvas); return; }
            int l = Math.max(0, (int) Math.floor(bounds.left));
            int t = Math.max(0, (int) Math.floor(bounds.top));
            int r = Math.min(host.getWidth(), (int) Math.ceil(bounds.right));
            int b = Math.min(host.getHeight(), (int) Math.ceil(bounds.bottom));
            int w = r - l, h = b - t;
            if (w <= 0 || h <= 0) return;

            clip.reset();
            for (int i = 0; i < 3; i++) {
                if (!shapes[i].isEmpty()) clip.addRoundRect(shapes[i], radii[i], radii[i], Path.Direction.CW);
            }
            RenderNode node = (RenderNode) nodeObj;
            float bp = Ui.dpf(Math.max(2f, blurDp));
            node.setPosition(l, t, r, b);
            node.setRenderEffect(blur ? RenderEffect.createBlurEffect(bp, bp, Shader.TileMode.CLAMP) : null);
            RecordingCanvas rc = node.beginRecording(w, h);
            try {
                if (backdrop != null && backdrop.recorded && backdrop.nodeObj != null) {
                    float[] off = offsetIn(host, backdrop);
                    rc.save();
                    rc.translate(-(off[0] + l), -(off[1] + t));
                    rc.drawRenderNode((RenderNode) backdrop.nodeObj);
                    rc.restore();
                }
                if (extra instanceof RenderNode) {
                    rc.save();
                    rc.translate(-l, -t);
                    rc.drawRenderNode((RenderNode) extra);
                    rc.restore();
                }
            } finally {
                node.endRecording();
            }
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawRenderNode(node);
            fallbackPaint.setColor(Ui.alpha(tint, alpha));
            canvas.drawPath(clip, fallbackPaint);
            canvas.restore();
            strokePaint.setColor(Ui.alpha(strokeColor == 0 ? Ui.glassStroke() : strokeColor, alpha));
            strokePaint.setStrokeWidth(Math.max(1f, Ui.dpf(0.6f)));
            canvas.drawPath(clip, strokePaint);
        }

        private void drawGStyle(Canvas canvas, View host, Backdrop backdrop) {
            if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated() && nodeObj != null && backdrop != null && backdrop.recorded && backdrop.nodeObj != null) {
                int l = Math.max(0, (int) Math.floor(bounds.left));
                int t = Math.max(0, (int) Math.floor(bounds.top));
                int r = Math.min(host.getWidth(), (int) Math.ceil(bounds.right));
                int b = Math.min(host.getHeight(), (int) Math.ceil(bounds.bottom));
                int w = r - l, h = b - t;
                if (w > 0 && h > 0) {
                    clip.reset();
                    for (int i = 0; i < 3; i++) {
                        if (!shapes[i].isEmpty()) clip.addRoundRect(shapes[i], radii[i], radii[i], Path.Direction.CW);
                    }
                    RenderNode node = (RenderNode) nodeObj;
                    float bp = Ui.dpf(22f);
                    node.setPosition(l, t, r, b);
                    node.setRenderEffect(RenderEffect.createBlurEffect(bp, bp, Shader.TileMode.CLAMP));
                    RecordingCanvas rc = node.beginRecording(w, h);
                    try {
                        float[] off = offsetIn(host, backdrop);
                        rc.save();
                        rc.translate(-(off[0] + l), -(off[1] + t));
                        rc.drawRenderNode((RenderNode) backdrop.nodeObj);
                        rc.restore();
                    } finally {
                        node.endRecording();
                    }
                    canvas.save();
                    canvas.clipPath(clip);
                    canvas.drawRenderNode(node);
                    fallbackPaint.setColor(Ui.alpha(Ui.gTint(tint), alpha));
                    canvas.drawPath(clip, fallbackPaint);
                    canvas.restore();
                    strokePaint.setColor(Ui.alpha(Ui.gStroke(), alpha));
                    strokePaint.setStrokeWidth(Math.max(1f, Ui.dpf(0.75f)));
                    canvas.drawPath(clip, strokePaint);
                    return;
                }
            }
            fallbackPaint.setColor(Ui.alpha(Ui.gTint(tint), alpha));
            strokePaint.setColor(Ui.alpha(Ui.gStroke(), alpha));
            strokePaint.setStrokeWidth(Math.max(1f, Ui.dpf(0.75f)));
            for (int i = 0; i < 3; i++) {
                if (!shapes[i].isEmpty()) {
                    canvas.drawRoundRect(shapes[i], radii[i], radii[i], fallbackPaint);
                    canvas.drawRoundRect(shapes[i], radii[i], radii[i], strokePaint);
                }
            }
        }

        private void drawFallback(Canvas canvas) {
            int col = Ui.dark ? 0xD91C1C1E : 0xEBFAFAFC;
            if ((tint >>> 24) < 0x30) col = Ui.dark ? 0x26FFFFFF : 0x22000000;
            fallbackPaint.setColor(Ui.alpha(col, alpha));
            strokePaint.setColor(Ui.alpha(strokeColor == 0 ? Ui.glassStroke() : strokeColor, alpha));
            strokePaint.setStrokeWidth(Math.max(1f, Ui.dpf(0.75f)));
            for (int i = 0; i < 3; i++) {
                if (!shapes[i].isEmpty()) {
                    canvas.drawRoundRect(shapes[i], radii[i], radii[i], fallbackPaint);
                    canvas.drawRoundRect(shapes[i], radii[i], radii[i], strokePaint);
                    if (pressAmt > 0.01f && pressR > 1f) {
                        fallbackPaint.setColor(Ui.alpha(Ui.dark ? 0x44FFFFFF : 0x24000000, pressAmt * alpha));
                        canvas.drawRoundRect(shapes[i], radii[i], radii[i], fallbackPaint);
                        fallbackPaint.setColor(Ui.alpha(col, alpha));
                    }
                }
            }
        }
    }

    static float[] offsetIn(View host, View target) {
        float[] a = abs(host), b = abs(target);
        return new float[]{a[0] - b[0], a[1] - b[1]};
    }

    static float[] abs(View v) {
        float x = 0, y = 0;
        View cur = v;
        while (cur != null) {
            x += cur.getX();
            y += cur.getY();
            if (!(cur.getParent() instanceof View)) break;
            cur = (View) cur.getParent();
        }
        return new float[]{x, y};
    }
}
