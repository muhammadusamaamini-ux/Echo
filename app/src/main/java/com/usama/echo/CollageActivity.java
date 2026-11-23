package com.usama.echo;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Collage: 2 to 9 photos, many arrangements for each number, any shape, spacing, rounded corners and background. */
public class CollageActivity extends Activity {
    private static final int REQ_PICK = 71, REQ_REPLACE = 72;
    static final String[] ASPECTS = {"1:1", "4:5", "9:16", "3:4", "4:3", "16:9", "2:3", "3:2"};
    static final String[] ASPECT_NAMES = {"Square", "4:5 (post)", "9:16 (story)", "3:4", "4:3", "16:9 (wide)", "2:3", "3:2"};
    static final int[] BG = {0xFFFFFFFF, 0xFF000000, 0xFF1C1C28, 0xFFF4EFE6, 0xFFFFD6E0, 0xFFD6ECFF, 0xFFE3F7D4, 0xFFFFE9B3, 0xFF7B5CFF, 0xFFFF5C8A};

    static final class Cell {
        int p;
        float zoom = 1, ox, oy;
    }

    private final List<Uri> uris = new ArrayList<>();
    private final List<Bitmap> bmps = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    private int layout = 0, bg = 0xFFFFFFFF, sel = -1;
    private String aspect = "1:1";
    private float gap = 12, radius = 0;
    private boolean swapping;
    private CollageView cv;
    private FrameLayout panel;
    private TextView count;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(0xFF07070F);
        LinearLayout bar = Ui.topBar(this, "Collage", R.drawable.ic_back);
        TextView add = Ui.pill(this, "+ Photos", false);
        add.setOnClickListener(v -> pick(REQ_PICK, true));
        TextView save = Ui.pill(this, "Save", true);
        save.setOnClickListener(v -> save());
        bar.addView(add);
        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(Ui.dp(this, 6), 1));
        bar.addView(save);
        root.addView(bar);
        count = Ui.text(this, "", 13, Ui.SUB, false);
        count.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 4));
        root.addView(count);
        cv = new CollageView(this);
        root.addView(cv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        panel = new FrameLayout(this);
        root.addView(panel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 230)));
        setContentView(root);
        ArrayList<String> start = getIntent().getStringArrayListExtra("uris");
        List<Uri> list = new ArrayList<>();
        if (start != null) for (String s : start) list.add(Uri.parse(s));
        if (list.isEmpty()) pick(REQ_PICK, true);
        addPhotos(list);
    }

    private void pick(int req, boolean many) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        if (many) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(i, many ? "Choose 2 to 9 photos" : "Choose a photo"), req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        List<Uri> got = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) got.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) got.add(data.getData());
        if (req == REQ_PICK) addPhotos(got);
        else if (req == REQ_REPLACE && !got.isEmpty() && sel >= 0) {
            final Cell c = cells.get(sel);
            final Uri u = got.get(0);
            new Thread(() -> {
                Bitmap bmp = Photos.load(this, u, 1600);
                runOnUiThread(() -> {
                    if (bmp == null) return;
                    uris.set(c.p, u);
                    bmps.set(c.p, bmp);
                    c.zoom = 1; c.ox = 0; c.oy = 0;
                    cv.invalidate();
                });
            }).start();
        }
    }

    private void addPhotos(List<Uri> list) {
        if (list.isEmpty()) { refreshAll(); return; }
        count.setText("Loading photos…");
        new Thread(() -> {
            List<Uri> okU = new ArrayList<>();
            List<Bitmap> okB = new ArrayList<>();
            for (Uri u : list) {
                if (uris.size() + okU.size() >= 9) break;
                if (uris.contains(u)) continue;
                Bitmap bmp = Photos.load(this, u, 1600);
                if (bmp != null) { okU.add(u); okB.add(bmp); }
            }
            runOnUiThread(() -> {
                if (uris.size() + list.size() > 9) Toast.makeText(this, "A collage can have up to 9 photos", Toast.LENGTH_SHORT).show();
                uris.addAll(okU);
                bmps.addAll(okB);
                resetCells();
            });
        }).start();
    }

    private void resetCells() {
        cells.clear();
        for (int i = 0; i < bmps.size(); i++) { Cell c = new Cell(); c.p = i; cells.add(c); }
        layout = Math.min(layout, Math.max(0, Photos.layouts(bmps.size()).size() - 1));
        sel = -1;
        swapping = false;
        refreshAll();
    }

    private void refreshAll() {
        int n = bmps.size();
        count.setText(n < 2 ? (n == 0 ? "Add 2 to 9 photos (+ Photos)." : "Add at least one more photo (+ Photos).") : n + " photos  ·  tap a box to change it, drag to move its photo, pinch to zoom");
        fillPanel();
        cv.invalidate();
    }

    // ---------- drawing ----------
    private int[] size(int longSide) {
        String[] p = aspect.split(":");
        float a = Float.parseFloat(p[0]), b = Float.parseFloat(p[1]);
        return a >= b ? new int[]{longSide, Math.round(longSide * b / a)} : new int[]{Math.round(longSide * a / b), longSide};
    }

    private List<RectF> boxes(float W, float H) {
        List<RectF> out = new ArrayList<>();
        List<float[][]> lays = Photos.layouts(bmps.size());
        if (lays.isEmpty()) return out;
        float[][] lay = lays.get(Math.min(layout, lays.size() - 1));
        float g = gap / 1000f * Math.min(W, H);
        for (float[] r : lay) {
            float l = r[0] * (W - g) + g, t = r[1] * (H - g) + g;
            out.add(new RectF(l, t, l + r[2] * (W - g) - g, t + r[3] * (H - g) - g));
        }
        return out;
    }

    private void drawCollage(Canvas c, float W, float H, boolean forSave) {
        c.drawColor(bg);
        float rad = radius / 1000f * Math.min(W, H);
        List<RectF> bx = boxes(W, H);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < bx.size() && i < cells.size(); i++) {
            RectF r = bx.get(i);
            Cell cell = cells.get(i);
            Bitmap bmp = bmps.get(cell.p);
            c.save();
            Path clip = new Path();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            float k = Math.max(r.width() / bmp.getWidth(), r.height() / bmp.getHeight()) * cell.zoom;
            float dw = bmp.getWidth() * k, dh = bmp.getHeight() * k;
            float ex = (dw - r.width()) / 2, ey = (dh - r.height()) / 2;
            float left = r.left - ex + cell.ox * ex, top = r.top - ey + cell.oy * ey;
            c.drawBitmap(bmp, null, new RectF(left, top, left + dw, top + dh), p);
            c.restore();
            if (!forSave && i == sel) {
                Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
                s.setStyle(Paint.Style.STROKE);
                s.setStrokeWidth(Ui.dp(this, 3));
                s.setColor(swapping ? 0xFFFFD400 : Ui.ACCENT);
                s.setPathEffect(new DashPathEffect(new float[]{16, 10}, 0));
                c.drawRoundRect(r, rad, rad, s);
            }
        }
    }

    private class CollageView extends View {
        private final RectF area = new RectF();
        private final ScaleGestureDetector scaler;
        private int dragCell = -1;
        private float lx, ly;
        private boolean moved;

        CollageView(Context c) {
            super(c);
            scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    if (sel < 0) return true;
                    Cell cell = cells.get(sel);
                    cell.zoom = Math.max(1, Math.min(4, cell.zoom * d.getScaleFactor()));
                    moved = true;
                    invalidate();
                    return true;
                }
            });
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bmps.size() < 2) {
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setColor(Ui.SUB);
                t.setTextSize(Ui.dp(getContext(), 15));
                t.setTextAlign(Paint.Align.CENTER);
                c.drawText(bmps.isEmpty() ? "Add 2 to 9 photos" : "Add at least one more photo", getWidth() / 2f, getHeight() / 2f, t);
                return;
            }
            int[] s = size(1000);
            float pad = Ui.dp(getContext(), 12);
            float k = Math.min((getWidth() - 2 * pad) / s[0], (getHeight() - 2 * pad) / s[1]);
            float W = s[0] * k, H = s[1] * k;
            area.set((getWidth() - W) / 2, (getHeight() - H) / 2, (getWidth() + W) / 2, (getHeight() + H) / 2);
            c.save();
            c.translate(area.left, area.top);
            drawCollage(c, W, H, false);
            c.restore();
        }

        private int hit(float x, float y) {
            List<RectF> bx = boxes(area.width(), area.height());
            for (int i = 0; i < bx.size(); i++) if (bx.get(i).contains(x - area.left, y - area.top)) return i;
            return -1;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (bmps.size() < 2) return true;
            scaler.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    int i = hit(e.getX(), e.getY());
                    moved = false;
                    lx = e.getX(); ly = e.getY();
                    if (i < 0) { sel = -1; swapping = false; fillPanel(); invalidate(); return true; }
                    if (swapping && sel >= 0 && i != sel) {
                        Cell a = cells.get(sel);
                        cells.set(sel, cells.get(i));
                        cells.set(i, a);
                        swapping = false;
                        sel = i;
                        fillPanel();
                        invalidate();
                        return true;
                    }
                    if (sel != i) { sel = i; swapping = false; fillPanel(); }
                    dragCell = i;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (dragCell < 0 || e.getPointerCount() > 1 || scaler.isInProgress()) return true;
                    Cell cell = cells.get(dragCell);
                    Bitmap bmp = bmps.get(cell.p);
                    RectF r = boxes(area.width(), area.height()).get(dragCell);
                    float k = Math.max(r.width() / bmp.getWidth(), r.height() / bmp.getHeight()) * cell.zoom;
                    float ex = Math.max(1, (bmp.getWidth() * k - r.width()) / 2), ey = Math.max(1, (bmp.getHeight() * k - r.height()) / 2);
                    cell.ox = Math.max(-1, Math.min(1, cell.ox + (e.getX() - lx) / ex));
                    cell.oy = Math.max(-1, Math.min(1, cell.oy + (e.getY() - ly) / ey));
                    lx = e.getX(); ly = e.getY();
                    moved = true;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragCell = -1;
                    if (moved) fillPanel();
                    return true;
                default:
                    return true;
            }
        }
    }

    /** A small picture of an arrangement. */
    private class LayoutThumb extends View {
        final float[][] lay;
        final boolean on;

        LayoutThumb(Context c, float[][] lay, boolean on) { super(c); this.lay = lay; this.on = on; }

        @Override
        protected void onDraw(Canvas c) {
            Paint bgp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgp.setColor(on ? Ui.ACCENT : 0x33FFFFFF);
            float w = getWidth(), h = getHeight(), g = Ui.dp(getContext(), 2);
            c.drawRoundRect(0, 0, w, h, g * 2, g * 2, bgp);
            Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
            box.setColor(on ? 0xFF003A44 : 0xFF6E63C8);
            for (float[] r : lay) c.drawRoundRect(r[0] * w + g, r[1] * h + g, (r[0] + r[2]) * w - g, (r[1] + r[3]) * h - g, g, g, box);
        }
    }

    // ---------- panel ----------
    private interface IntSet { void set(int v); }

    private View slider(String label, int max, int value, IntSet on) {
        LinearLayout c = Ui.column(this);
        LinearLayout r = Ui.row(this);
        r.addView(Ui.text(this, label, 13, Ui.TEXT, false), Ui.weight());
        final TextView val = Ui.text(this, String.valueOf(value), 13, Ui.SUB, false);
        r.addView(val);
        c.addView(r);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(value);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) { val.setText(String.valueOf(p)); on.set(p); cv.invalidate(); } }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        c.addView(sb);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        return r;
    }

    private HorizontalScrollView hscroll(View v) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(v);
        return h;
    }

    private void addPill(LinearLayout row, String label, boolean on, View.OnClickListener l) {
        TextView p = Ui.pill(this, label, on);
        p.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 6);
        row.addView(p, lp);
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 12, Ui.DIM, true);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        return t;
    }

    private void fillPanel() {
        panel.removeAllViews();
        if (bmps.size() < 2) return;
        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 16));
        sv.addView(box);
        panel.addView(sv);
        if (sel >= 0 && sel < cells.size()) { // the chosen box
            final Cell c = cells.get(sel);
            box.addView(label("THIS BOX"));
            LinearLayout r = row();
            addPill(r, swapping ? "Now tap the other box…" : "⇄ Swap", swapping, v -> { swapping = !swapping; fillPanel(); cv.invalidate(); });
            addPill(r, "Replace photo", false, v -> pick(REQ_REPLACE, false));
            addPill(r, "Fit again", false, v -> { c.zoom = 1; c.ox = 0; c.oy = 0; cv.invalidate(); });
            addPill(r, "Remove photo", false, v -> { bmps.remove(c.p); uris.remove(c.p); resetCells(); });
            addPill(r, "Done", true, v -> { sel = -1; swapping = false; fillPanel(); cv.invalidate(); });
            box.addView(hscroll(r));
        }
        List<float[][]> lays = Photos.layouts(bmps.size());
        box.addView(label("ARRANGEMENT (" + lays.size() + " FOR " + bmps.size() + " PHOTOS)"));
        LinearLayout lr = row();
        for (int i = 0; i < lays.size(); i++) {
            final int k = i;
            LayoutThumb t = new LayoutThumb(this, lays.get(i), i == layout);
            t.setOnClickListener(v -> { layout = k; fillPanel(); cv.invalidate(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54));
            lp.rightMargin = Ui.dp(this, 8);
            lr.addView(t, lp);
        }
        box.addView(hscroll(lr));
        box.addView(label("SHAPE"));
        LinearLayout ar = row();
        for (int i = 0; i < ASPECTS.length; i++) {
            final String a = ASPECTS[i];
            addPill(ar, ASPECT_NAMES[i], a.equals(aspect), v -> { aspect = a; fillPanel(); cv.invalidate(); });
        }
        box.addView(hscroll(ar));
        box.addView(slider("Spacing", 60, Math.round(gap), v -> gap = v));
        box.addView(slider("Rounded corners", 60, Math.round(radius), v -> radius = v));
        box.addView(label("BACKGROUND"));
        LinearLayout br = row();
        for (int col : BG) {
            View sw = new View(this);
            sw.setBackground(Ui.round(col, Ui.dp(this, 14)));
            if (col == bg) {
                android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
                ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                ring.setStroke(Ui.dp(this, 3), Ui.ACCENT);
                sw.setForeground(ring);
            }
            sw.setOnClickListener(v -> { bg = col; fillPanel(); cv.invalidate(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28));
            lp.rightMargin = Ui.dp(this, 8);
            br.addView(sw, lp);
        }
        box.addView(hscroll(br));
    }

    // ---------- saving ----------
    private void save() {
        if (bmps.size() < 2) { Toast.makeText(this, "Add 2 to 9 photos first", Toast.LENGTH_SHORT).show(); return; }
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 73);
            return;
        }
        String[] sizes = {"Large (2048 px)", "Small (1080 px, for sharing)", "Extra large (3000 px)"};
        final int[] px = {2048, 1080, 3000};
        new android.app.AlertDialog.Builder(this).setTitle("Save the collage").setItems(sizes, (d, which) -> {
            Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String msg;
                try {
                    int[] s = size(px[which]);
                    Bitmap out = Bitmap.createBitmap(s[0], s[1], Bitmap.Config.ARGB_8888);
                    drawCollage(new Canvas(out), s[0], s[1], true);
                    File tmp = new File(getCacheDir(), "collage.jpg");
                    try (FileOutputStream fo = new FileOutputStream(tmp)) { out.compress(Bitmap.CompressFormat.JPEG, 93, fo); }
                    String name = "Collage " + new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(new Date()) + ".jpg";
                    Gallery.save(this, tmp, name, "image/jpeg", Environment.DIRECTORY_PICTURES);
                    tmp.delete();
                    msg = "Saved to Pictures › Echo";
                } catch (Throwable e) {
                    msg = "Couldn't save: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
            }).start();
        }).show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 73 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) save();
    }
}
