package com.usama.echo;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;

/** Full-screen photo viewer: swipe for the next or previous photo, pinch or double-tap to zoom. */
public class PhotoViewerActivity extends Activity {
    private int i;
    private ZoomView view;
    private TextView title, info;
    private LinearLayout top, bottom;
    private boolean chrome = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Photos.shown.isEmpty()) { finish(); return; }
        i = Math.max(0, Math.min(getIntent().getIntExtra("i", 0), Photos.shown.size() - 1));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new ZoomView(this);
        root.addView(view);
        top = Ui.row(this);
        top.setBackgroundColor(0x99000000);
        top.setPadding(Ui.dp(this, 4), Ui.dp(this, 26), Ui.dp(this, 12), Ui.dp(this, 6));
        ImageButton back = Ui.icon(this, R.drawable.ic_back, 44, Ui.TEXT);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        LinearLayout tt = Ui.column(this);
        title = Ui.line(this, "", 16, Ui.TEXT, true);
        info = Ui.line(this, "", 12, Ui.SUB, false);
        tt.addView(title);
        tt.addView(info);
        top.addView(tt, Ui.weight());
        root.addView(top, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        bottom = Ui.row(this);
        bottom.setBackgroundColor(0x99000000);
        bottom.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 18));
        bottom.addView(action("✏", "Edit", v -> startActivity(new Intent(this, PhotoEditorActivity.class).setData(Uri.parse(cur().uri)))), Ui.weight());
        bottom.addView(action("▦", "Collage", v -> {
            ArrayList<String> u = new ArrayList<>(Collections.singletonList(cur().uri));
            startActivity(new Intent(this, CollageActivity.class).putStringArrayListExtra("uris", u));
        }), Ui.weight());
        bottom.addView(action("↗", "Share", v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("image/*")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(cur().uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share photo"))), Ui.weight());
        bottom.addView(action("⟳", "Turn", v -> view.turn()), Ui.weight());
        bottom.addView(action("🗑", "Delete", v -> deleteCurrent()), Ui.weight());
        root.addView(bottom, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        show();
    }

    private Photos.Photo cur() { return Photos.shown.get(i); }

    private View action(String icon, String label, View.OnClickListener l) {
        LinearLayout c = Ui.column(this);
        c.setGravity(Gravity.CENTER);
        TextView ic = Ui.text(this, icon, 20, Ui.TEXT, false);
        ic.setGravity(Gravity.CENTER);
        TextView t = Ui.text(this, label, 12, Ui.SUB, false);
        t.setGravity(Gravity.CENTER);
        c.addView(ic);
        c.addView(t);
        c.setOnClickListener(l);
        Ui.ripple(c, 0, Ui.dp(this, 12));
        return c;
    }

    private void show() {
        final Photos.Photo p = cur();
        title.setText(p.name);
        info.setText((i + 1) + " of " + Photos.shown.size() + "  ·  " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(p.date)
                + (p.w > 0 ? "  ·  " + p.w + " × " + p.h : ""));
        view.setImageBitmap(null);
        final int at = i;
        new Thread(() -> {
            Bitmap bmp = Photos.load(this, Uri.parse(p.uri), 2560);
            runOnUiThread(() -> { if (at == i) view.setPhoto(bmp); });
        }).start();
    }

    private void step(int d) {
        int n = i + d;
        if (n < 0 || n >= Photos.shown.size()) return;
        i = n;
        show();
    }

    private void toggleChrome() {
        chrome = !chrome;
        top.setVisibility(chrome ? View.VISIBLE : View.GONE);
        bottom.setVisibility(chrome ? View.VISIBLE : View.GONE);
    }

    private void deleteCurrent() {
        Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur().id);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), Collections.singletonList(u), true);
                startIntentSenderForResult(pi.getIntentSender(), 51, null, 0, 0, 0);
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't delete", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        new android.app.AlertDialog.Builder(this).setTitle("Delete this photo?").setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    try { getContentResolver().delete(u, null, null); removed(); } catch (Exception e) { Toast.makeText(this, "Couldn't delete", Toast.LENGTH_SHORT).show(); }
                }).show();
    }

    private void removed() {
        Photos.shown.remove(i);
        if (Photos.shown.isEmpty()) { finish(); return; }
        i = Math.min(i, Photos.shown.size() - 1);
        show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 51 && res == RESULT_OK) removed();
    }

    /** An image you can pinch, double-tap and drag; swipes change the photo when not zoomed in. */
    private class ZoomView extends ImageView {
        private final Matrix m = new Matrix();
        private float base = 1, zoom = 1;
        private int turn = 0;
        private Bitmap bmp;
        private final ScaleGestureDetector scaler;
        private final GestureDetector gestures;

        ZoomView(Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
            scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    float f = d.getScaleFactor();
                    float nz = Math.max(1f, Math.min(8f, zoom * f));
                    f = nz / zoom;
                    zoom = nz;
                    m.postScale(f, f, d.getFocusX(), d.getFocusY());
                    clamp();
                    return true;
                }
            });
            gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (zoom > 1.01f) fit();
                    else { zoom = 2.5f; m.postScale(2.5f, 2.5f, e.getX(), e.getY()); clamp(); }
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    toggleChrome();
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent a, MotionEvent b2, float dx, float dy) {
                    if (zoom > 1.01f) { m.postTranslate(-dx, -dy); clamp(); }
                    return true;
                }

                @Override
                public boolean onFling(MotionEvent a, MotionEvent b2, float vx, float vy) {
                    if (zoom > 1.01f || a == null) return false;
                    float dx = b2.getX() - a.getX();
                    if (Math.abs(dx) > Ui.dp(getContext(), 60) && Math.abs(vx) > Math.abs(vy)) step(dx < 0 ? 1 : -1);
                    return true;
                }
            });
        }

        void setPhoto(Bitmap b) {
            bmp = b;
            turn = 0;
            setImageBitmap(b);
            fit();
        }

        void turn() {
            if (bmp == null) return;
            Matrix r = new Matrix();
            r.postRotate(90);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), r, true);
            setImageBitmap(bmp);
            fit();
        }

        void fit() {
            if (bmp == null || getWidth() == 0) { post(this::fit); return; }
            base = Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
            zoom = 1;
            m.reset();
            m.postScale(base, base);
            m.postTranslate((getWidth() - bmp.getWidth() * base) / 2, (getHeight() - bmp.getHeight() * base) / 2);
            setImageMatrix(m);
        }

        private void clamp() {
            if (bmp == null) return;
            RectF r = new RectF(0, 0, bmp.getWidth(), bmp.getHeight());
            m.mapRect(r);
            float dx = 0, dy = 0;
            if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2 - r.left;
            else if (r.left > 0) dx = -r.left;
            else if (r.right < getWidth()) dx = getWidth() - r.right;
            if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2 - r.top;
            else if (r.top > 0) dy = -r.top;
            else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
            m.postTranslate(dx, dy);
            setImageMatrix(m);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            fit();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaler.onTouchEvent(e);
            gestures.onTouchEvent(e);
            return true;
        }
    }
}
