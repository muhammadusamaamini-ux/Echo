package com.usama.echo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Photo editor: crop, turn, flip, straighten, light and colour, filters, text and drawing. Saves a copy to Pictures › Echo. */
public class PhotoEditorActivity extends Activity {
    static final String[] FILTERS = {"none", "vivid", "warm", "cool", "bw", "noir", "sepia", "vintage", "fade", "drama", "pastel", "punch"};
    static final String[] FILTER_NAMES = {"Original", "Vivid", "Warm", "Cool", "Black & white", "Noir", "Sepia", "Vintage", "Faded", "Drama", "Pastel", "Punch"};
    static final String[] ASPECTS = {"free", "orig", "1:1", "4:5", "5:4", "3:4", "4:3", "2:3", "3:2", "9:16", "16:9"};
    static final String[] ASPECT_NAMES = {"Free", "Original", "Square", "4:5", "5:4", "3:4", "4:3", "2:3", "3:2", "9:16", "16:9"};
    static final String[] ADJ = {"Brightness", "Exposure", "Contrast", "Saturation", "Warmth", "Tint (green – pink)", "Fade", "Vignette"};
    static final int[] COLORS = {0xFFFFFFFF, 0xFF000000, 0xFFFFD400, 0xFFFF3B30, 0xFF34C759, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFF9F0A, 0xFF00E5FF};

    static final class TextItem {
        String text;
        float x = 0.5f, y = 0.5f, size = 0.08f;
        int color = Color.WHITE;
        boolean bold = true, shadow = true;

        TextItem copy() { TextItem t = new TextItem(); t.text = text; t.x = x; t.y = y; t.size = size; t.color = color; t.bold = bold; t.shadow = shadow; return t; }
    }

    static final class Stroke {
        int color;
        float size, alpha = 1;
        final List<float[]> pts = new ArrayList<>();

        Stroke copy() { Stroke s = new Stroke(); s.color = color; s.size = size; s.alpha = alpha; for (float[] p : pts) s.pts.add(p.clone()); return s; }
    }

    static final class St {
        int rot;
        boolean flipH, flipV;
        float straight;
        RectF crop = new RectF(0, 0, 1, 1);
        String aspect = "free", filter = "none";
        final float[] adj = new float[ADJ.length]; // -100…100 (fade and vignette 0…100)
        final List<TextItem> texts = new ArrayList<>();
        final List<Stroke> strokes = new ArrayList<>();

        St copy() {
            St s = new St();
            s.rot = rot; s.flipH = flipH; s.flipV = flipV; s.straight = straight; s.crop = new RectF(crop); s.aspect = aspect; s.filter = filter;
            System.arraycopy(adj, 0, s.adj, 0, adj.length);
            for (TextItem t : texts) s.texts.add(t.copy());
            for (Stroke k : strokes) s.strokes.add(k.copy());
            return s;
        }
    }

    private Uri uri;
    private Bitmap small; // the preview copy
    private St st = new St();
    private final List<St> hist = new ArrayList<>();
    private int hi = -1;
    private String tab = "crop";
    private int selText = -1;
    private int penColor = 0xFFFF3B30;
    private float penSize = 8;
    private EditView ev;
    private FrameLayout panel;
    private LinearLayout tabs;
    private String name = "Photo";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        uri = getIntent().getData();
        if (uri == null) { finish(); return; }
        String last = uri.getLastPathSegment();
        if (last != null && !last.matches("\\d+")) name = last.replaceAll(".*/", "").replaceAll("\\.[^.]+$", "");
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(0xFF07070F);
        LinearLayout bar = Ui.topBar(this, "Edit photo", R.drawable.ic_back);
        TextView undo = Ui.pill(this, "↶", false);
        undo.setOnClickListener(v -> undo());
        TextView redo = Ui.pill(this, "↷", false);
        redo.setOnClickListener(v -> redo());
        TextView save = Ui.pill(this, "Save", true);
        save.setOnClickListener(v -> save());
        bar.addView(undo);
        bar.addView(space());
        bar.addView(redo);
        bar.addView(space());
        bar.addView(save);
        root.addView(bar);
        ev = new EditView(this);
        root.addView(ev, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        tabs = Ui.row(this);
        tabs.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 4));
        hs.addView(tabs);
        root.addView(hs);
        panel = new FrameLayout(this);
        root.addView(panel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 200)));
        setContentView(root);
        drawTabs();
        new Thread(() -> {
            Bitmap bmp = Photos.load(this, uri, 1600);
            runOnUiThread(() -> {
                if (bmp == null) { Toast.makeText(this, "This photo couldn't be opened", Toast.LENGTH_LONG).show(); finish(); return; }
                small = bmp;
                push();
                fillPanel();
                ev.changed();
            });
        }).start();
    }

    private View space() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 6), 1)); return v; }

    // ---------- history ----------
    private void push() {
        while (hist.size() > hi + 1) hist.remove(hist.size() - 1);
        hist.add(st.copy());
        if (hist.size() > 40) hist.remove(0);
        hi = hist.size() - 1;
    }
    private void undo() { if (hi > 0) { hi--; st = hist.get(hi).copy(); selText = -1; fillPanel(); ev.changed(); } }
    private void redo() { if (hi < hist.size() - 1) { hi++; st = hist.get(hi).copy(); fillPanel(); ev.changed(); } }

    @Override
    public void onBackPressed() {
        if (hi > 0) new AlertDialog.Builder(this).setTitle("Leave without saving?").setMessage("Your changes to this photo will be lost.")
                .setNegativeButton("Stay", null).setPositiveButton("Leave", (d, w) -> finish()).show();
        else super.onBackPressed();
    }

    // ---------- the picture ----------
    static Bitmap oriented(Bitmap src, St s) {
        Matrix m = new Matrix();
        m.postScale(s.flipH ? -1 : 1, s.flipV ? -1 : 1);
        m.postRotate(s.rot);
        Bitmap o = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (s.straight == 0) return o;
        int W = o.getWidth(), H = o.getHeight();
        double a = Math.toRadians(s.straight), ca = Math.abs(Math.cos(a)), sa = Math.abs(Math.sin(a));
        float k = (float) Math.max((W * ca + H * sa) / W, (W * sa + H * ca) / H);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.translate(W / 2f, H / 2f);
        c.rotate(s.straight);
        c.scale(k, k);
        c.drawBitmap(o, -W / 2f, -H / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    static ColorMatrix colors(St s) {
        float[] a = s.adj;
        float bright = a[0], expo = a[1], contrast = a[2], sat = a[3], warm = a[4], tint = a[5];
        float fSat = 1, fCon = 1, fBri = 0, sepia = 0;
        switch (s.filter) {
            case "vivid": fSat = 1.45f; fCon = 1.1f; break;
            case "warm": warm += 35; break;
            case "cool": warm -= 35; break;
            case "bw": fSat = 0; fCon = 1.1f; break;
            case "noir": fSat = 0; fCon = 1.45f; fBri = -10; break;
            case "sepia": sepia = 0.85f; break;
            case "vintage": sepia = 0.35f; fCon = 0.9f; fSat = 0.85f; break;
            case "fade": fCon = 0.85f; fBri = 8; fSat = 0.8f; break;
            case "drama": fCon = 1.35f; fSat = 1.15f; break;
            case "pastel": fSat = 0.7f; fBri = 12; break;
            case "punch": fSat = 1.7f; fCon = 1.2f; break;
            default: break;
        }
        ColorMatrix m = new ColorMatrix();
        ColorMatrix t = new ColorMatrix();
        t.setSaturation(Math.max(0, (1 + sat / 100f) * fSat));
        m.postConcat(t);
        float k = (1 + contrast / 100f) * fCon * (1 + expo / 120f);
        float off = 128 * (1 - (1 + contrast / 100f) * fCon) + bright * 1.1f + fBri;
        t = new ColorMatrix(new float[]{k, 0, 0, 0, off, 0, k, 0, 0, off, 0, 0, k, 0, off, 0, 0, 0, 1, 0});
        m.postConcat(t);
        if (sepia > 0) {
            float r = 1 - sepia;
            t = new ColorMatrix(new float[]{
                    r + sepia * 0.393f, sepia * 0.769f, sepia * 0.189f, 0, 0,
                    sepia * 0.349f, r + sepia * 0.686f, sepia * 0.168f, 0, 0,
                    sepia * 0.272f, sepia * 0.534f, r + sepia * 0.131f, 0, 0,
                    0, 0, 0, 1, 0});
            m.postConcat(t);
        }
        if (warm != 0 || tint != 0) {
            t = new ColorMatrix(new float[]{1, 0, 0, 0, warm * 0.55f + tint * 0.25f, 0, 1, 0, 0, -tint * 0.45f, 0, 0, 1, 0, -warm * 0.55f + tint * 0.25f, 0, 0, 0, 1, 0});
            m.postConcat(t);
        }
        return m;
    }

    /** The finished picture: cropped, coloured, with drawing and text. */
    static Bitmap render(Bitmap src, St s, boolean wholePhoto) {
        Bitmap o = oriented(src, s);
        RectF cr = wholePhoto ? new RectF(0, 0, 1, 1) : s.crop;
        Rect from = new Rect(Math.round(cr.left * o.getWidth()), Math.round(cr.top * o.getHeight()),
                Math.max(1, Math.round(cr.right * o.getWidth())), Math.max(1, Math.round(cr.bottom * o.getHeight())));
        int W = Math.max(1, from.width()), H = Math.max(1, from.height());
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(colors(s)));
        c.drawBitmap(o, from, new Rect(0, 0, W, H), p);
        float fade = s.adj[6] + ("vintage".equals(s.filter) ? 30 : "fade".equals(s.filter) ? 25 : 0);
        float vig = s.adj[7] + ("vintage".equals(s.filter) ? 25 : "drama".equals(s.filter) ? 45 : 0);
        if (fade > 0) {
            Paint f = new Paint();
            f.setColor(Color.argb(Math.round(Math.min(1, fade / 100f * 0.45f) * 255), 138, 138, 138));
            f.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.LIGHTEN));
            c.drawRect(0, 0, W, H, f);
        }
        if (vig > 0) {
            Paint v = new Paint();
            v.setShader(new RadialGradient(W / 2f, H / 2f, (float) Math.hypot(W, H) / 2f, new int[]{0, 0, Color.argb(Math.round(Math.min(1, vig / 100f * 0.85f) * 255), 0, 0, 0)},
                    new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, v);
        }
        if (!wholePhoto) {
            for (Stroke k : s.strokes) {
                if (k.pts.isEmpty()) continue;
                Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                sp.setStyle(Paint.Style.STROKE);
                sp.setStrokeCap(Paint.Cap.ROUND);
                sp.setStrokeJoin(Paint.Join.ROUND);
                sp.setColor(k.color);
                sp.setAlpha(Math.round(k.alpha * 255));
                sp.setStrokeWidth(Math.max(1, k.size * Math.min(W, H)));
                Path path = new Path();
                path.moveTo(k.pts.get(0)[0] * W, k.pts.get(0)[1] * H);
                for (float[] pt : k.pts) path.lineTo(pt[0] * W, pt[1] * H);
                if (k.pts.size() == 1) path.lineTo(k.pts.get(0)[0] * W + 0.5f, k.pts.get(0)[1] * H);
                c.drawPath(path, sp);
            }
            for (TextItem t : s.texts) drawText(c, t, W, H);
        }
        return out;
    }

    static Paint textPaint(TextItem t, int H) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(t.color);
        p.setTextSize(Math.max(6, t.size * H));
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, t.bold ? Typeface.BOLD : Typeface.NORMAL));
        p.setTextAlign(Paint.Align.CENTER);
        if (t.shadow) p.setShadowLayer(t.size * H * 0.18f, 0, t.size * H * 0.05f, 0xB4000000);
        return p;
    }

    static void drawText(Canvas c, TextItem t, int W, int H) {
        Paint p = textPaint(t, H);
        String[] lines = t.text.split("\n");
        float lh = t.size * H * 1.15f;
        float y0 = t.y * H - (lines.length - 1) * lh / 2f - (p.ascent() + p.descent()) / 2f;
        for (int i = 0; i < lines.length; i++) c.drawText(lines[i], t.x * W, y0 + i * lh, p);
    }

    static RectF textBox(TextItem t, int W, int H) {
        Paint p = textPaint(t, H);
        String[] lines = t.text.split("\n");
        float w = 0;
        for (String l : lines) w = Math.max(w, p.measureText(l));
        float h = t.size * H * 1.15f * lines.length;
        return new RectF(t.x * W - w / 2 - 12, t.y * H - h / 2 - 12, t.x * W + w / 2 + 12, t.y * H + h / 2 + 12);
    }

    private float aspectRatio() { // width/height of the crop in 0–1 units of the turned photo
        if ("free".equals(st.aspect) || "orig".equals(st.aspect)) return 0;
        String[] p = st.aspect.split(":");
        float want = Float.parseFloat(p[0]) / Float.parseFloat(p[1]);
        boolean side = st.rot % 180 != 0;
        float ow = side ? small.getHeight() : small.getWidth(), oh = side ? small.getWidth() : small.getHeight();
        return want / (ow / oh);
    }

    private void fitAspect() {
        if ("orig".equals(st.aspect)) { st.crop = new RectF(0, 0, 1, 1); return; }
        float r = aspectRatio();
        if (r == 0) return;
        float w = st.crop.width(), h = w / r;
        if (h > 1) { h = 1; w = h * r; }
        if (w > 1) { w = 1; h = w / r; }
        float cx = st.crop.centerX(), cy = st.crop.centerY();
        float x = Math.max(0, Math.min(1 - w, cx - w / 2)), y = Math.max(0, Math.min(1 - h, cy - h / 2));
        st.crop = new RectF(x, y, x + w, y + h);
    }

    // ---------- the picture on screen ----------
    private class EditView extends View {
        private Bitmap shown;
        private final RectF dst = new RectF();
        private final Paint dim = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG), handle = new Paint(Paint.ANTI_ALIAS_FLAG), sel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int dragMode = 0; // 1 crop, 2 text, 3 draw
        private String cropHandle;
        private float lx, ly;
        private RectF crop0;
        private Stroke cur;

        EditView(Context c) {
            super(c);
            dim.setColor(0x99000000);
            line.setColor(Color.WHITE);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Ui.dp(c, 2));
            handle.setColor(Color.WHITE);
            sel.setColor(Ui.ACCENT);
            sel.setStyle(Paint.Style.STROKE);
            sel.setStrokeWidth(Ui.dp(c, 2));
            sel.setPathEffect(new android.graphics.DashPathEffect(new float[]{12, 8}, 0));
        }

        void changed() {
            if (small == null) return;
            shown = "crop".equals(tab) ? render(small, st, true) : render(small, st, false);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (shown == null) return;
            float pad = Ui.dp(getContext(), 14);
            float k = Math.min((getWidth() - 2 * pad) / shown.getWidth(), (getHeight() - 2 * pad) / shown.getHeight());
            float w = shown.getWidth() * k, h = shown.getHeight() * k;
            dst.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
            c.drawBitmap(shown, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
            if ("crop".equals(tab)) {
                RectF r = new RectF(dst.left + st.crop.left * w, dst.top + st.crop.top * h, dst.left + st.crop.right * w, dst.top + st.crop.bottom * h);
                c.drawRect(dst.left, dst.top, dst.right, r.top, dim);
                c.drawRect(dst.left, r.bottom, dst.right, dst.bottom, dim);
                c.drawRect(dst.left, r.top, r.left, r.bottom, dim);
                c.drawRect(r.right, r.top, dst.right, r.bottom, dim);
                c.drawRect(r, line);
                Paint thin = new Paint(line);
                thin.setStrokeWidth(1);
                thin.setAlpha(110);
                for (int i = 1; i < 3; i++) {
                    c.drawLine(r.left + r.width() * i / 3, r.top, r.left + r.width() * i / 3, r.bottom, thin);
                    c.drawLine(r.left, r.top + r.height() * i / 3, r.right, r.top + r.height() * i / 3, thin);
                }
                float hs = Ui.dp(getContext(), 7);
                for (float[] pt : new float[][]{{r.left, r.top}, {r.right, r.top}, {r.left, r.bottom}, {r.right, r.bottom}})
                    c.drawCircle(pt[0], pt[1], hs, handle);
            } else if ("text".equals(tab) && selText >= 0 && selText < st.texts.size()) {
                RectF b = textBox(st.texts.get(selText), shown.getWidth(), shown.getHeight());
                c.drawRect(dst.left + b.left * k, dst.top + b.top * k, dst.left + b.right * k, dst.top + b.bottom * k, sel);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (shown == null) return true;
            float w = dst.width(), h = dst.height();
            float nx = (e.getX() - dst.left) / w, ny = (e.getY() - dst.top) / h;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lx = e.getX(); ly = e.getY();
                    if ("crop".equals(tab)) {
                        RectF r = new RectF(dst.left + st.crop.left * w, dst.top + st.crop.top * h, dst.left + st.crop.right * w, dst.top + st.crop.bottom * h);
                        float t = Ui.dp(getContext(), 30);
                        boolean L = Math.abs(e.getX() - r.left) < t, R = Math.abs(e.getX() - r.right) < t, T = Math.abs(e.getY() - r.top) < t, B = Math.abs(e.getY() - r.bottom) < t;
                        cropHandle = (T ? "n" : B ? "s" : "") + (L ? "w" : R ? "e" : "");
                        if (cropHandle.isEmpty() && r.contains(e.getX(), e.getY())) cropHandle = "move";
                        if (cropHandle.isEmpty()) return true;
                        crop0 = new RectF(st.crop);
                        dragMode = 1;
                    } else if ("text".equals(tab)) {
                        selText = -1;
                        for (int i = st.texts.size() - 1; i >= 0; i--) {
                            RectF b = textBox(st.texts.get(i), shown.getWidth(), shown.getHeight());
                            float bx = nx * shown.getWidth(), by = ny * shown.getHeight();
                            if (b.contains(bx, by)) { selText = i; break; }
                        }
                        dragMode = selText >= 0 ? 2 : 0;
                        fillPanel();
                        invalidate();
                    } else if ("draw".equals(tab)) {
                        cur = new Stroke();
                        cur.color = penColor;
                        cur.size = penSize / 1000f;
                        cur.pts.add(new float[]{nx, ny});
                        st.strokes.add(cur);
                        dragMode = 3;
                        changed();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragMode == 1) {
                        float dx = (e.getX() - lx) / w, dy = (e.getY() - ly) / h;
                        float x = crop0.left, y = crop0.top, cw = crop0.width(), ch = crop0.height(), min = 0.06f;
                        if ("move".equals(cropHandle)) { x = Math.max(0, Math.min(1 - cw, x + dx)); y = Math.max(0, Math.min(1 - ch, y + dy)); }
                        else {
                            if (cropHandle.contains("e")) cw = Math.max(min, Math.min(1 - x, cw + dx));
                            if (cropHandle.contains("s")) ch = Math.max(min, Math.min(1 - y, ch + dy));
                            if (cropHandle.contains("w")) { float nx2 = Math.max(0, Math.min(x + cw - min, x + dx)); cw += x - nx2; x = nx2; }
                            if (cropHandle.contains("n")) { float ny2 = Math.max(0, Math.min(y + ch - min, y + dy)); ch += y - ny2; y = ny2; }
                            float r = aspectRatio();
                            if (r > 0) {
                                if (cropHandle.equals("n") || cropHandle.equals("s")) cw = ch * r; else ch = cw / r;
                                if (x + cw > 1) { cw = 1 - x; ch = cw / r; }
                                if (y + ch > 1) { ch = 1 - y; cw = ch * r; }
                                if (cropHandle.contains("w")) x = crop0.right - cw;
                                if (cropHandle.contains("n")) y = crop0.bottom - ch;
                            }
                        }
                        st.crop = new RectF(x, y, x + cw, y + ch);
                        invalidate();
                    } else if (dragMode == 2 && selText >= 0) {
                        TextItem t = st.texts.get(selText);
                        t.x = Math.max(0, Math.min(1, t.x + (e.getX() - lx) / w));
                        t.y = Math.max(0, Math.min(1, t.y + (e.getY() - ly) / h));
                        lx = e.getX(); ly = e.getY();
                        changed();
                    } else if (dragMode == 3 && cur != null) {
                        cur.pts.add(new float[]{nx, ny});
                        changed();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragMode != 0) push();
                    dragMode = 0;
                    cur = null;
                    return true;
                default:
                    return true;
            }
        }
    }

    // ---------- tabs and tools ----------
    private void drawTabs() {
        tabs.removeAllViews();
        String[][] list = {{"crop", "✂ Crop & turn"}, {"adjust", "☀ Adjust"}, {"filters", "🎨 Filters"}, {"text", "T Text"}, {"draw", "✎ Draw"}};
        for (String[] t : list) {
            TextView p = Ui.pill(this, t[1], t[0].equals(tab));
            p.setOnClickListener(v -> { tab = t[0]; selText = -1; drawTabs(); fillPanel(); ev.changed(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 6);
            tabs.addView(p, lp);
        }
    }

    private interface IntSet { void set(int v); }

    private View slider(String label, int min, int max, int value, IntSet onChange) {
        LinearLayout c = Ui.column(this);
        c.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 2));
        LinearLayout r = Ui.row(this);
        r.addView(Ui.text(this, label, 13, Ui.TEXT, false), Ui.weight());
        final TextView val = Ui.text(this, String.valueOf(value), 13, Ui.SUB, false);
        r.addView(val);
        c.addView(r);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) { val.setText(String.valueOf(p + min)); onChange.set(p + min); ev.changed(); } }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { push(); }
        });
        c.addView(sb);
        return c;
    }

    private LinearLayout pillsRow() {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        return r;
    }

    private void addPill(LinearLayout row, String label, boolean on, View.OnClickListener l) {
        TextView p = Ui.pill(this, label, on);
        p.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 6);
        row.addView(p, lp);
    }

    private HorizontalScrollView hscroll(View v) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(v);
        return h;
    }

    private View swatches(int current, IntSet onPick) {
        LinearLayout r = pillsRow();
        for (int col : COLORS) {
            View sw = new View(this);
            sw.setBackground(Ui.round(col, Ui.dp(this, 14)));
            if (col == current) sw.setForeground(strokeRing());
            sw.setOnClickListener(v -> onPick.set(col));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28));
            lp.rightMargin = Ui.dp(this, 8);
            r.addView(sw, lp);
        }
        return hscroll(r);
    }

    private android.graphics.drawable.GradientDrawable strokeRing() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setStroke(Ui.dp(this, 3), Ui.ACCENT);
        return g;
    }

    private void fillPanel() {
        panel.removeAllViews();
        if (small == null) return;
        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 12));
        sv.addView(box);
        panel.addView(sv);
        switch (tab) {
            case "crop": {
                LinearLayout asp = pillsRow();
                for (int i = 0; i < ASPECTS.length; i++) {
                    final String a = ASPECTS[i];
                    addPill(asp, ASPECT_NAMES[i], a.equals(st.aspect), v -> { st.aspect = a; fitAspect(); push(); fillPanel(); ev.changed(); });
                }
                box.addView(hscroll(asp));
                LinearLayout turn = pillsRow();
                addPill(turn, "⟲ Left", false, v -> { st.rot = (st.rot + 270) % 360; swapCrop(); fitAspect(); push(); ev.changed(); });
                addPill(turn, "⟳ Right", false, v -> { st.rot = (st.rot + 90) % 360; swapCrop(); fitAspect(); push(); ev.changed(); });
                addPill(turn, "⇋ Flip", false, v -> { st.flipH = !st.flipH; push(); ev.changed(); });
                addPill(turn, "⇵ Flip up-down", false, v -> { st.flipV = !st.flipV; push(); ev.changed(); });
                addPill(turn, "Undo crop", false, v -> { st.crop = new RectF(0, 0, 1, 1); st.aspect = "free"; push(); fillPanel(); ev.changed(); });
                box.addView(hscroll(turn));
                box.addView(slider("Straighten (°)", -45, 45, Math.round(st.straight), v -> st.straight = v));
                break;
            }
            case "adjust": {
                for (int i = 0; i < ADJ.length; i++) {
                    final int k = i;
                    box.addView(slider(ADJ[i], i >= 6 ? 0 : -100, 100, Math.round(st.adj[i]), v -> st.adj[k] = v));
                }
                break;
            }
            case "filters": {
                LinearLayout row = pillsRow();
                float k = 130f / Math.max(small.getWidth(), small.getHeight());
                Bitmap thumbSrc = Bitmap.createScaledBitmap(small, Math.max(1, Math.round(small.getWidth() * k)), Math.max(1, Math.round(small.getHeight() * k)), true);
                for (int i = 0; i < FILTERS.length; i++) {
                    final String f = FILTERS[i];
                    St t = st.copy();
                    t.filter = f;
                    t.texts.clear();
                    t.strokes.clear();
                    LinearLayout c = Ui.column(this);
                    c.setGravity(Gravity.CENTER_HORIZONTAL);
                    ImageView iv = new ImageView(this);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setImageBitmap(render(thumbSrc, t, false));
                    if (f.equals(st.filter)) iv.setForeground(Ui.round(0, Ui.dp(this, 8)));
                    iv.setBackground(f.equals(st.filter) ? Ui.round(Ui.ACCENT, Ui.dp(this, 10)) : null);
                    iv.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
                    c.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 84)));
                    c.addView(Ui.text(this, FILTER_NAMES[i], 12, f.equals(st.filter) ? Ui.ACCENT : Ui.SUB, false));
                    c.setOnClickListener(v -> { st.filter = f; push(); fillPanel(); ev.changed(); });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.rightMargin = Ui.dp(this, 8);
                    row.addView(c, lp);
                }
                box.addView(hscroll(row));
                break;
            }
            case "text": {
                LinearLayout r = pillsRow();
                addPill(r, "+ Add text", true, v -> askText(null));
                if (selText >= 0 && selText < st.texts.size()) {
                    final TextItem t = st.texts.get(selText);
                    addPill(r, "Change words", false, v -> askText(t));
                    addPill(r, t.bold ? "Bold ✓" : "Bold", t.bold, v -> { t.bold = !t.bold; push(); fillPanel(); ev.changed(); });
                    addPill(r, t.shadow ? "Shadow ✓" : "Shadow", t.shadow, v -> { t.shadow = !t.shadow; push(); fillPanel(); ev.changed(); });
                    addPill(r, "Delete", false, v -> { st.texts.remove(t); selText = -1; push(); fillPanel(); ev.changed(); });
                    box.addView(hscroll(r));
                    box.addView(swatches(t.color, col -> { t.color = col; push(); fillPanel(); ev.changed(); }));
                    box.addView(slider("Size", 2, 40, Math.round(t.size * 100), v -> t.size = v / 100f));
                } else {
                    box.addView(hscroll(r));
                    box.addView(Ui.text(this, st.texts.isEmpty() ? "Add text, then drag it where you want it." : "Tap a text on the photo to change it; drag it to move it.", 13, Ui.SUB, false));
                }
                break;
            }
            case "draw": {
                box.addView(swatches(penColor, col -> { penColor = col; fillPanel(); }));
                box.addView(slider("Pen size", 1, 60, Math.round(penSize), v -> penSize = v));
                LinearLayout r = pillsRow();
                addPill(r, "Remove last line", false, v -> { if (!st.strokes.isEmpty()) { st.strokes.remove(st.strokes.size() - 1); push(); ev.changed(); } });
                addPill(r, "Clear drawing", false, v -> { if (!st.strokes.isEmpty()) { st.strokes.clear(); push(); ev.changed(); } });
                box.addView(hscroll(r));
                box.addView(Ui.text(this, "Draw on the photo with your finger.", 13, Ui.SUB, false));
                break;
            }
            default:
                break;
        }
    }

    private void swapCrop() { st.crop = new RectF(st.crop.top, st.crop.left, st.crop.bottom, st.crop.right); }

    private void askText(TextItem edit) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (edit != null) e.setText(edit.text);
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(e);
        new AlertDialog.Builder(this).setTitle(edit == null ? "Add text" : "Change the words").setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (d, w) -> {
                    String s = e.getText().toString().trim();
                    if (s.isEmpty()) return;
                    if (edit != null) edit.text = s;
                    else { TextItem t = new TextItem(); t.text = s; st.texts.add(t); selText = st.texts.size() - 1; }
                    push();
                    fillPanel();
                    ev.changed();
                }).show();
    }

    // ---------- saving ----------
    private void save() {
        if (small == null) return;
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 61);
            return;
        }
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
        final St s = st.copy();
        new Thread(() -> {
            String msg;
            try {
                Bitmap full = Photos.load(this, uri, 4000);
                if (full == null) throw new Exception("couldn't read the photo");
                Bitmap out = render(full, s, false);
                File tmp = new File(getCacheDir(), "edited.jpg");
                try (FileOutputStream fo = new FileOutputStream(tmp)) { out.compress(Bitmap.CompressFormat.JPEG, 93, fo); }
                Gallery.save(this, tmp, name + " (edited) " + System.currentTimeMillis() / 1000 + ".jpg", "image/jpeg", Environment.DIRECTORY_PICTURES);
                tmp.delete();
                msg = "Saved to Pictures › Echo";
            } catch (Throwable e) {
                msg = "Couldn't save: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                if (m.startsWith("Saved")) { hist.clear(); push(); }
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 61 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) save();
    }
}
