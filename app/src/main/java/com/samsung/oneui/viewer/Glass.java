package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
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
 * Optical Liquid Glass rendering engine unified to Prism's exact AGSL shader & optical parameters.
 * Safely degrades on API 26-32.
 */
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
            "uniform float outerW;\n" +
            "uniform float outerRefr;\n" +
            "uniform float edgeDark;\n" +
            "uniform float2 lightDir;\n" +
            "uniform float vibrance;\n" +
            "uniform float4 press;\n" +
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
            "  float lip = 1.0 - smoothstep(0.0, max(outerW, 0.001), depth);\n" +
            "  float bend = refr * bevel(depth, edgeW) + outerRefr * bevel(depth, outerW) * lip;\n" +
            "  float2 q = magC + (p - magC) / mag;\n" +
            "  float2 off = n * bend;\n" +
            "  float4 c = look(q + off);\n" +
            "  if (chroma > 0.0 && bend > 0.05) {\n" +
            "    float diag = clamp(abs(n.x * n.y) * 2.0, 0.0, 1.0);\n" +
            "    float spread = chroma * (0.3 + 0.7 * diag);\n" +
            "    float4 r1 = look(q + off * (1.0 - spread));\n" +
            "    float4 r2 = look(q + off * (1.0 - 0.5 * spread));\n" +
            "    float4 b1 = look(q + off * (1.0 + 0.5 * spread));\n" +
            "    float4 b2 = look(q + off * (1.0 + spread));\n" +
            "    c = float4((r1.r + r2.r) * 0.5, (r2.g + c.g * 2.0 + b1.g) * 0.25, (b1.b + b2.b) * 0.5, c.a);\n" +
            "  }\n" +
            "  float lum = dot(c.rgb, float3(0.2126, 0.7152, 0.0722));\n" +
            "  c.rgb = clamp(mix(float3(lum), c.rgb, vibrance), 0.0, 1.0);\n" +
            "  c = float4(mix(c.rgb, tint.rgb, tint.a), mix(c.a, 1.0, tint.a));\n" +
            "  if (press.w > 0.001) {\n" +
            "    float2 k = (p - press.xy) / max(press.z, 1.0);\n" +
            "    float bloom = exp(-0.5 * dot(k, k)) * press.w;\n" +
            "    float3 lifted = c.rgb * 1.08 + float3(0.07);\n" +
            "    c.rgb = mix(c.rgb, lifted, clamp(bloom + press.w * 0.25, 0.0, 1.0));\n" +
            "  }\n" +
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
        float blurDp = 6f;
        float refractDp = 17f;
        float edgeDp = 26f;
        float outerDp = 7f;
        float outerRefractDp = 12f;
        float chroma = 0f;
        float rim = 0.32f;
        float edgeDark = 0.32f;
        float vibrance = 1.35f;
        float lightX = -0.45f, lightY = -0.89f;
        float mag = 1f;
        float magX, magY;
        float pressX, pressY, pressR, pressAmt;
        int tint = Ui.glassTint();
        float alpha = 1f;
        float shadowAlpha = 0.16f;
        int strokeColor = Ui.glassStroke();
        boolean blur = true;
        Object extra;

        Surface() {
            nodeObj = Build.VERSION.SDK_INT >= 29 ? new RenderNode("glass-surface") : null;
            shadowPaint.setColor(0x01FFFFFF);
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
                shadowPaint.setShadowLayer(Ui.dpf(14), 0, Ui.dpf(4), Ui.alpha(0xFF000000, shadowAlpha * alpha * (Ui.dark ? 2.2f : 1f)));
                for (int i = 0; i < 3; i++) {
                    if (!shapes[i].isEmpty()) canvas.drawRoundRect(shapes[i], radii[i], radii[i], shadowPaint);
                }
                shadowPaint.clearShadowLayer();
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
            float pad = Ui.dpf(blurDp) * 2f + Ui.dpf(refractDp);
            int l = (int) Math.floor(bounds.left - pad);
            int t = (int) Math.floor(bounds.top - pad);
            int r = (int) Math.ceil(bounds.right + pad);
            int b = (int) Math.ceil(bounds.bottom + pad);
            int w = r - l, h = b - t;
            if (w <= 0 || h <= 0) return;

            try {
                if (shaderObj == null) {
                    try { shaderObj = new RuntimeShader(SHADER); }
                    catch (Throwable t1) { shaderObj = new RuntimeShader(SHADER.replace("content.eval(at)", "sample(content, at)")); }
                }
                RuntimeShader shader = (RuntimeShader) shaderObj;
                for (int i = 0; i < 3; i++) {
                    RectF s = shapes[i];
                    if (s.isEmpty()) {
                        shader.setFloatUniform("s" + i, 0f, 0f, 0f, 0f);
                        shader.setFloatUniform("s" + i + "r", 0f);
                    } else {
                        shader.setFloatUniform("s" + i, s.left - l, s.top - t, s.width(), s.height());
                        shader.setFloatUniform("s" + i + "r", Math.min(radii[i], s.height() / 2f));
                    }
                }
                shader.setFloatUniform("smoothK", smoothK);
                shader.setFloatUniform("edgeW", Ui.dpf(edgeDp));
                shader.setFloatUniform("refr", Ui.dpf(refractDp));
                shader.setFloatUniform("chroma", chroma);
                int tc = tint;
                shader.setFloatUniform("tint", ((tc >> 16) & 255) / 255f, ((tc >> 8) & 255) / 255f, (tc & 255) / 255f, (tc >>> 24) / 255f);
                shader.setFloatUniform("rim", rim);
                shader.setFloatUniform("alphaMul", alpha);
                shader.setFloatUniform("magC", (magX == 0 ? bounds.centerX() : magX) - l, (magY == 0 ? bounds.centerY() : magY) - t);
                shader.setFloatUniform("mag", Math.max(0.2f, mag));
                shader.setFloatUniform("outerW", Ui.dpf(outerDp));
                shader.setFloatUniform("outerRefr", Ui.dpf(outerRefractDp));
                shader.setFloatUniform("edgeDark", Ui.dark ? edgeDark * 0.4f : edgeDark);
                shader.setFloatUniform("lightDir", lightX, lightY);
                shader.setFloatUniform("vibrance", vibrance);
                shader.setFloatUniform("press", pressX - l, pressY - t, Math.max(1f, pressR), pressAmt);

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
                    strokePaint.setStrokeWidth(Math.max(1f, Ui.dpf(0.6f)));
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
            int col = Ui.mix(Ui.bg(), tint | 0xFF000000, 0.55f);
            if ((tint >>> 24) < 0x30) col = Ui.dark ? 0x26FFFFFF : 0x22000000;
            fallbackPaint.setColor(Ui.alpha(col, 0.96f * alpha));
            strokePaint.setColor(Ui.alpha(strokeColor == 0 ? Ui.glassStroke() : strokeColor, alpha));
            strokePaint.setStrokeWidth(Math.max(1f, Ui.dpf(0.6f)));
            for (int i = 0; i < 3; i++) {
                if (!shapes[i].isEmpty()) {
                    canvas.drawRoundRect(shapes[i], radii[i], radii[i], fallbackPaint);
                    canvas.drawRoundRect(shapes[i], radii[i], radii[i], strokePaint);
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
