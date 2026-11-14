package com.usama.echo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Video editor (CapCut / YouCut style): several clips and photos, trim, split, speed, volume, voice,
 * rotate, flip, canvas/crop, filters, adjust, text, sticker, music, quality, extract audio, save frame.
 */
public class EditorActivity extends Activity {
    private static final int REQ_ADD = 1, REQ_MUSIC = 2, REQ_STICKER = 3, REQ_WRITE = 4;

    private final EditProject project = new EditProject();
    private int selected = -1;
    private boolean changed;

    private ExoPlayer player;
    private PlayerView preview;
    private ImageView photoPreview;
    private LinearLayout timeline, trimBox;
    private TextView totalView, trimStartText, trimEndText, emptyHint;
    private SeekBar trimStart, trimEnd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Transformer transformer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.column(this);

        LinearLayout bar = Ui.topBar(this, "Edit video", R.drawable.ic_back);
        bar.getChildAt(0).setOnClickListener(v -> onBackPressed());
        TextView export = Ui.pill(this, "Export", true);
        export.setOnClickListener(v -> askExport());
        bar.addView(export);
        root.addView(bar);

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        preview = new PlayerView(this);
        preview.setShowNextButton(false);
        preview.setShowPreviousButton(false);
        stage.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        photoPreview = new ImageView(this);
        photoPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photoPreview.setVisibility(View.GONE);
        stage.addView(photoPreview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        emptyHint = Ui.text(this, "Tap  + Add  to choose videos or photos", 15, Ui.SUB, false);
        emptyHint.setGravity(Gravity.CENTER);
        stage.addView(emptyHint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.36f);
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        totalView = Ui.text(this, "", 13, Ui.SUB, false);
        totalView.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 2));
        root.addView(totalView);

        // Clips timeline
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        timeline = Ui.row(this);
        timeline.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        hs.addView(timeline);
        root.addView(hs);

        // Trim for the selected clip
        trimBox = Ui.column(this);
        trimBox.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 4));
        trimStartText = Ui.text(this, "", 12, Ui.SUB, false);
        trimStart = slider();
        trimEndText = Ui.text(this, "", 12, Ui.SUB, false);
        trimEnd = slider();
        trimBox.addView(trimStartText);
        trimBox.addView(trimStart);
        trimBox.addView(trimEndText);
        trimBox.addView(trimEnd);
        root.addView(trimBox);
        hookTrim();

        // Tools
        root.addView(sectionLabel("Clip"));
        root.addView(toolRow(new String[]{"Split", "Delete", "Copy", "Move ←", "Move →", "Speed", "Volume",
                "Voice", "Photo time", "Save frame"}, new int[]{R.drawable.ic_cut, R.drawable.ic_close, R.drawable.ic_add,
                R.drawable.ic_back, R.drawable.ic_next, R.drawable.ic_speed, R.drawable.ic_headphones,
                R.drawable.ic_music, R.drawable.ic_speed, R.drawable.ic_image}, this::clipTool));
        root.addView(sectionLabel("Video"));
        root.addView(toolRow(new String[]{"Filters", "Adjust", "Canvas", "Rotate", "Flip", "Text", "Sticker",
                "Music", "Extract audio"}, new int[]{R.drawable.ic_filter, R.drawable.ic_filter, R.drawable.ic_aspect,
                R.drawable.ic_rotate, R.drawable.ic_aspect, R.drawable.ic_text, R.drawable.ic_image,
                R.drawable.ic_music, R.drawable.ic_headphones}, this::videoTool));
        setContentView(root);

        player = new ExoPlayer.Builder(this).build();
        preview.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                // Some phones can't preview effects live: show the plain clip instead.
                try {
                    player.setVideoEffects(new ArrayList<>());
                    player.prepare();
                } catch (Exception ignored) {
                }
            }
        });
        Hub.pauseIfPlaying();

        Uri start = getIntent().getData();
        String extra = getIntent().getStringExtra("uri");
        if (start == null && extra != null) start = Uri.parse(extra);
        if (start != null) {
            List<Uri> one = new ArrayList<>();
            one.add(start);
            addClips(one);
        } else {
            refresh();
            pickClips();
        }
    }

    // ---------- small UI pieces ----------

    private SeekBar slider() {
        SeekBar s = new SeekBar(this);
        s.setMax(1000);
        s.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        s.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        return s;
    }

    private TextView sectionLabel(String s) {
        TextView t = Ui.text(this, s, 12, Ui.DIM, true);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), 0, 0);
        return t;
    }

    interface Tool {
        void run(String name);
    }

    private View toolRow(String[] names, int[] icons, Tool tool) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        for (int i = 0; i < names.length; i++) {
            LinearLayout b = Ui.column(this);
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
            b.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF), Ui.glass(this, 14), null));
            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            ic.setColorFilter(Ui.GLOW);
            b.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
            TextView t = Ui.text(this, names[i], 11, Ui.TEXT, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 4), 0, 0);
            b.addView(t);
            final String n = names[i];
            b.setOnClickListener(v -> tool.run(n));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 74), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            row.addView(b, lp);
        }
        hs.addView(row);
        return hs;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private EditProject.Clip current() {
        return selected >= 0 && selected < project.clips.size() ? project.clips.get(selected) : null;
    }

    // ---------- adding clips ----------

    private void pickClips() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "image/*"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_ADD);
        } catch (Exception e) {
            toast("No file picker found");
        }
    }

    private void addClips(List<Uri> uris) {
        final AlertDialog wait = new AlertDialog.Builder(this).setMessage("Adding…").setCancelable(false).show();
        new Thread(() -> {
            List<EditProject.Clip> added = new ArrayList<>();
            for (Uri u : uris) {
                String mime = getContentResolver().getType(u);
                added.add(EditProject.probe(this, u, nameOf(u), mime));
            }
            main.post(() -> {
                wait.dismiss();
                int at = selected < 0 ? project.clips.size() : selected + 1;
                project.clips.addAll(at, added);
                selected = at;
                changed = true;
                refresh();
                showClip(true);
            });
        }).start();
    }

    private String nameOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = u.getLastPathSegment();
        return last == null ? "Clip" : last;
    }

    private void keepAccess(Uri u) {
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) {
            if (req == REQ_ADD && project.clips.isEmpty()) finish();
            return;
        }
        if (req == REQ_ADD) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            for (Uri u : uris) keepAccess(u);
            if (!uris.isEmpty()) addClips(uris);
        } else if (req == REQ_MUSIC && data.getData() != null) {
            keepAccess(data.getData());
            project.music = data.getData();
            project.musicName = nameOf(data.getData());
            changed = true;
            musicTool();
        } else if (req == REQ_STICKER && data.getData() != null) {
            keepAccess(data.getData());
            project.sticker = data.getData();
            changed = true;
            stickerTool();
            showClip(false);
        }
    }

    // ---------- timeline ----------

    private void refresh() {
        timeline.removeAllViews();
        for (int i = 0; i < project.clips.size(); i++) {
            final int idx = i;
            EditProject.Clip c = project.clips.get(i);
            FrameLayout box = new FrameLayout(this);
            ImageView th = new ImageView(this);
            th.setScaleType(ImageView.ScaleType.CENTER_CROP);
            th.setClipToOutline(true);
            th.setBackground(Ui.gradFor(c.name, Ui.dp(this, 10)));
            if (c.thumb != null) th.setImageBitmap(c.thumb);
            box.addView(th, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            String label = Ui.time(c.lengthMs()) + (c.speed != 1f ? "  " + c.speed + "x" : "");
            TextView t = Ui.text(this, label, 11, Color.WHITE, true);
            t.setBackground(Ui.round(0xAA000000, Ui.dp(this, 4)));
            t.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            box.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
            android.graphics.drawable.GradientDrawable frame = Ui.round(0, Ui.dp(this, 10));
            frame.setStroke(Ui.dp(this, i == selected ? 3 : 1), i == selected ? Ui.GLOW : 0x33FFFFFF);
            box.setForeground(frame);
            box.setOnClickListener(v -> {
                selected = idx;
                refresh();
                showClip(true);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 58));
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            timeline.addView(box, lp);
        }
        TextView add = Ui.text(this, "+ Add", 13, Ui.TEXT, true);
        add.setGravity(Gravity.CENTER);
        add.setBackground(Ui.glass(this, 10));
        add.setOnClickListener(v -> pickClips());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 80), Ui.dp(this, 58));
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        timeline.addView(add, lp);

        int[] size = project.outSize();
        totalView.setText(project.clips.isEmpty() ? "No clips yet"
                : project.clips.size() + (project.clips.size() == 1 ? " clip" : " clips") + "  ·  "
                + Ui.time(project.totalMs()) + "  ·  " + size[0] + "×" + size[1]
                + (project.filter.equals("None") ? "" : "  ·  " + project.filter)
                + (project.music != null ? "  ·  ♪ music" : ""));
        emptyHint.setVisibility(project.clips.isEmpty() ? View.VISIBLE : View.GONE);
        updateTrim();
    }

    private boolean trimming;

    private void updateTrim() {
        EditProject.Clip c = current();
        boolean show = c != null && !c.image && c.sourceMs > 0;
        trimBox.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        trimming = true;
        trimStart.setProgress((int) (c.startMs * 1000 / c.sourceMs));
        trimEnd.setProgress((int) (c.endMs * 1000 / c.sourceMs));
        trimming = false;
        trimStartText.setText("Start  " + Ui.time(c.startMs));
        trimEndText.setText("End  " + Ui.time(c.endMs) + "   (clip " + Ui.time(c.endMs - c.startMs) + " of " + Ui.time(c.sourceMs) + ")");
    }

    private void hookTrim() {
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                EditProject.Clip c = current();
                if (!fromUser || trimming || c == null || c.sourceMs <= 0) return;
                long ms = c.sourceMs * p / 1000;
                if (s == trimStart) c.startMs = Math.min(ms, c.endMs - 500);
                else c.endMs = Math.max(ms, c.startMs + 500);
                c.startMs = Math.max(0, c.startMs);
                c.endMs = Math.min(c.sourceMs, c.endMs);
                trimStartText.setText("Start  " + Ui.time(c.startMs));
                trimEndText.setText("End  " + Ui.time(c.endMs) + "   (clip " + Ui.time(c.endMs - c.startMs) + ")");
                if (player != null) player.seekTo(s == trimStart ? c.startMs : Math.max(c.startMs, c.endMs - 1500));
            }

            @Override public void onStartTrackingTouch(SeekBar s) {
                if (player != null) {
                    player.pause();
                    EditProject.Clip c = current();
                    if (c != null) { // preview the whole file while trimming
                        player.setMediaItem(MediaItem.fromUri(c.uri), player.getCurrentPosition());
                        player.prepare();
                    }
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                changed = true;
                refresh();
                showClip(false);
            }
        };
        trimStart.setOnSeekBarChangeListener(l);
        trimEnd.setOnSeekBarChangeListener(l);
    }

    /** Shows the selected clip in the preview, with the current effects. */
    private void showClip(boolean play) {
        EditProject.Clip c = current();
        if (c == null) {
            player.stop();
            player.clearMediaItems();
            photoPreview.setVisibility(View.GONE);
            return;
        }
        if (c.image) {
            player.stop();
            photoPreview.setVisibility(View.VISIBLE);
            final Uri u = c.uri;
            new Thread(() -> {
                Bitmap bm = null;
                try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 2;
                    bm = android.graphics.BitmapFactory.decodeStream(in, null, o);
                } catch (Exception ignored) {
                }
                final Bitmap res = bm;
                main.post(() -> photoPreview.setImageBitmap(res));
            }).start();
            return;
        }
        photoPreview.setVisibility(View.GONE);
        try {
            player.setVideoEffects(project.pictureEffects(this, true));
        } catch (Exception e) {
            try {
                player.setVideoEffects(new ArrayList<>());
            } catch (Exception ignored) {
            }
        }
        MediaItem mi = new MediaItem.Builder().setUri(c.uri)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.startMs)
                        .setEndPositionMs(c.endMs > c.startMs ? c.endMs : Long.MIN_VALUE)
                        .build())
                .build();
        player.setMediaItem(mi);
        player.setPlaybackParameters(new androidx.media3.common.PlaybackParameters(c.speed, c.pitch));
        player.setVolume(Math.min(1f, c.volume * project.originalVolume));
        player.prepare();
        if (play) player.play();
    }

    // ---------- clip tools ----------

    private void clipTool(String name) {
        EditProject.Clip c = current();
        if (c == null) {
            toast("Add a clip first");
            return;
        }
        switch (name) {
            case "Split": split(c); break;
            case "Delete":
                project.clips.remove(selected);
                selected = Math.min(selected, project.clips.size() - 1);
                changed = true;
                refresh();
                showClip(false);
                break;
            case "Copy":
                project.clips.add(selected + 1, c.copy());
                selected++;
                changed = true;
                refresh();
                break;
            case "Move ←":
                if (selected > 0) {
                    project.clips.remove(selected);
                    project.clips.add(--selected, c);
                    changed = true;
                    refresh();
                }
                break;
            case "Move →":
                if (selected < project.clips.size() - 1) {
                    project.clips.remove(selected);
                    project.clips.add(++selected, c);
                    changed = true;
                    refresh();
                }
                break;
            case "Speed": speed(c); break;
            case "Volume": volume(c); break;
            case "Voice": voice(c); break;
            case "Photo time": photoTime(c); break;
            case "Save frame": saveFrame(c); break;
        }
    }

    private void split(EditProject.Clip c) {
        if (c.image) {
            toast("Photos can't be split. Use Photo time instead.");
            return;
        }
        long pos = player.getCurrentPosition(); // inside the trimmed clip
        long at = c.startMs + (long) (pos * 1f);
        if (at <= c.startMs + 300 || at >= c.endMs - 300) {
            toast("Play or move to the point where you want to split, then tap Split");
            return;
        }
        EditProject.Clip second = c.copy();
        c.endMs = at;
        second.startMs = at;
        project.clips.add(selected + 1, second);
        changed = true;
        refresh();
        toast("Split at " + Ui.time(at));
    }

    private void speed(EditProject.Clip c) {
        final float[] v = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f};
        String[] n = new String[v.length];
        for (int i = 0; i < v.length; i++) n[i] = v[i] == 1f ? "Normal (1x)" : v[i] + "x" + (v[i] < 1 ? "  slow motion" : "  fast");
        new AlertDialog.Builder(this).setTitle("Clip speed").setItems(n, (d, w) -> {
            c.speed = v[w];
            changed = true;
            refresh();
            showClip(true);
        }).show();
    }

    private void volume(EditProject.Clip c) {
        sliderDialog("Clip volume", 0, 200, Math.round(c.volume * 100), "%", val -> {
            c.volume = val / 100f;
            changed = true;
            showClip(true);
        });
    }

    private void voice(EditProject.Clip c) {
        final float[] p = {1f, 1.5f, 1.25f, 0.8f, 0.6f};
        String[] n = {"Normal", "Chipmunk", "Higher", "Deeper", "Monster"};
        new AlertDialog.Builder(this).setTitle("Voice effect").setItems(n, (d, w) -> {
            c.pitch = p[w];
            changed = true;
            showClip(true);
            toast("Voice: " + n[w]);
        }).show();
    }

    private void photoTime(EditProject.Clip c) {
        if (!c.image) {
            toast("This is for photos. Use the Start and End sliders to trim a video.");
            return;
        }
        sliderDialog("Show photo for (seconds)", 1, 15, (int) (c.photoMs / 1000), " s", val -> {
            c.photoMs = val * 1000L;
            changed = true;
            refresh();
        });
    }

    private void saveFrame(EditProject.Clip c) {
        final long at = c.image ? 0 : c.startMs + player.getCurrentPosition();
        final Uri u = c.uri;
        toast("Saving picture…");
        new Thread(() -> {
            String msg;
            try {
                Bitmap bm = null;
                if (c.image) {
                    try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                        bm = android.graphics.BitmapFactory.decodeStream(in);
                    }
                } else {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    r.setDataSource(this, u);
                    bm = r.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
                    try { r.release(); } catch (Exception ignored) { }
                }
                if (bm == null) throw new Exception("no frame");
                File f = new File(getCacheDir(), "frame.png");
                try (FileOutputStream os = new FileOutputStream(f)) {
                    bm.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                Gallery.save(this, f, "Echo_" + stamp() + ".png", "image/png", Environment.DIRECTORY_PICTURES);
                msg = "Saved to Pictures › Echo";
            } catch (Exception e) {
                msg = "Couldn't save the picture";
            }
            final String m = msg;
            main.post(() -> toast(m));
        }).start();
    }

    // ---------- video tools ----------

    private void videoTool(String name) {
        if (project.clips.isEmpty()) {
            toast("Add a clip first");
            return;
        }
        switch (name) {
            case "Filters": filters(); break;
            case "Adjust": adjust(); break;
            case "Canvas": canvas(); break;
            case "Rotate":
                project.rotate = (project.rotate + 90) % 360;
                changed = true;
                refresh();
                showClip(true);
                toast("Rotated " + project.rotate + "°");
                break;
            case "Flip":
                new AlertDialog.Builder(this).setTitle("Flip")
                        .setItems(new String[]{"Mirror (left ↔ right)", "Upside down", "No flip"}, (d, w) -> {
                            project.flipH = w == 0 ? !project.flipH : (w == 2 ? false : project.flipH);
                            project.flipV = w == 1 ? !project.flipV : (w == 2 ? false : project.flipV);
                            changed = true;
                            showClip(true);
                        }).show();
                break;
            case "Text": textTool(); break;
            case "Sticker": stickerTool(); break;
            case "Music": musicTool(); break;
            case "Extract audio": export(true); break;
        }
    }

    private void filters() {
        new AlertDialog.Builder(this).setTitle("Filter").setItems(EditProject.FILTERS, (d, w) -> {
            project.filter = EditProject.FILTERS[w];
            changed = true;
            refresh();
            showClip(true);
        }).show();
    }

    private void adjust() {
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        final SeekBar br = labelled(box, "Brightness", project.brightness);
        final SeekBar co = labelled(box, "Contrast", project.contrast);
        final SeekBar sa = labelled(box, "Saturation", project.saturation);
        new AlertDialog.Builder(this).setTitle("Adjust").setView(box)
                .setNeutralButton("Reset", (d, w) -> {
                    project.brightness = project.contrast = project.saturation = 0;
                    changed = true;
                    showClip(true);
                })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (d, w) -> {
                    project.brightness = br.getProgress() - 100;
                    project.contrast = co.getProgress() - 100;
                    project.saturation = sa.getProgress() - 100;
                    changed = true;
                    showClip(true);
                }).show();
    }

    private SeekBar labelled(LinearLayout box, String name, float value) {
        final TextView t = Ui.text(this, name + "  " + Math.round(value), 13, Ui.SUB, false);
        box.addView(t);
        SeekBar s = slider();
        s.setMax(200);
        s.setProgress(Math.round(value) + 100);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { t.setText(name + "  " + (p - 100)); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(s);
        return s;
    }

    private void canvas() {
        final float[] ratios = {0f, 9 / 16f, 16 / 9f, 1f, 4 / 5f, 4 / 3f, 3 / 4f};
        String[] names = {"Original", "9:16  TikTok · Reels · Shorts", "16:9  YouTube", "1:1  Square",
                "4:5  Instagram post", "4:3", "3:4"};
        new AlertDialog.Builder(this).setTitle("Canvas size").setItems(names, (d, w) -> {
            project.aspect = ratios[w];
            if (w == 0) {
                changed = true;
                refresh();
                showClip(true);
                return;
            }
            new AlertDialog.Builder(this).setTitle("Fit the video")
                    .setItems(new String[]{"Fill (crop the edges)", "Fit (add black bars)"}, (d2, w2) -> {
                        project.aspectFill = w2 == 0;
                        changed = true;
                        refresh();
                        showClip(true);
                    }).show();
        }).show();
    }

    // ---------- text ----------

    private void textTool() {
        List<String> names = new ArrayList<>();
        names.add("+ Add text");
        for (EditProject.TextItem t : project.texts) names.add("✎  " + t.text.replace('\n', ' '));
        new AlertDialog.Builder(this).setTitle("Text on video")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    if (w == 0) editText(null);
                    else editText(project.texts.get(w - 1));
                }).show();
    }

    private void editText(EditProject.TextItem existing) {
        final EditProject.TextItem t = existing != null ? existing : new EditProject.TextItem();
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        final EditText e = new EditText(this);
        e.setHint("Your text");
        e.setText(t.text);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(e);

        final int[] colors = {Color.WHITE, Color.BLACK, 0xFFFFD60A, Ui.GLOW, 0xFFFF4D9D, 0xFFFF3B30, 0xFF34C759};
        final int[] chosen = {t.color};
        LinearLayout swatches = Ui.row(this);
        swatches.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        final List<View> sw = new ArrayList<>();
        for (int col : colors) {
            View v = new View(this);
            android.graphics.drawable.GradientDrawable g = Ui.round(col, Ui.dp(this, 15));
            g.setStroke(Ui.dp(this, col == chosen[0] ? 3 : 1), col == chosen[0] ? Ui.GLOW : 0x66FFFFFF);
            v.setBackground(g);
            v.setOnClickListener(x -> {
                chosen[0] = col;
                for (int i = 0; i < sw.size(); i++) {
                    android.graphics.drawable.GradientDrawable gg = Ui.round(colors[i], Ui.dp(this, 15));
                    gg.setStroke(Ui.dp(this, colors[i] == col ? 3 : 1), colors[i] == col ? Ui.GLOW : 0x66FFFFFF);
                    sw.get(i).setBackground(gg);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30));
            lp.rightMargin = Ui.dp(this, 8);
            swatches.addView(v, lp);
            sw.add(v);
        }
        box.addView(swatches);

        final TextView sizeT = Ui.text(this, "Size", 13, Ui.SUB, false);
        box.addView(sizeT);
        final SeekBar size = slider();
        size.setMax(100);
        size.setProgress(Math.round((t.size - 0.03f) / 0.15f * 100));
        box.addView(size);

        final TextView posT = Ui.text(this, "Position", 13, Ui.SUB, false);
        box.addView(posT);
        final SeekBar pos = slider();
        pos.setMax(100);
        pos.setProgress(Math.round(t.y * 100));
        box.addView(pos);

        final android.widget.CheckBox bg = new android.widget.CheckBox(this);
        bg.setText("Box behind the text");
        bg.setTextColor(Ui.TEXT);
        bg.setChecked(t.background);
        box.addView(bg);

        AlertDialog.Builder dlg = new AlertDialog.Builder(this).setTitle(existing == null ? "Add text" : "Edit text")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Done", (d, w) -> {
                    t.text = e.getText().toString();
                    t.color = chosen[0];
                    t.size = 0.03f + size.getProgress() / 100f * 0.15f;
                    t.y = Math.max(0.06f, Math.min(0.94f, pos.getProgress() / 100f));
                    t.background = bg.isChecked();
                    if (existing == null && !t.text.trim().isEmpty()) project.texts.add(t);
                    changed = true;
                    showClip(true);
                });
        if (existing != null) dlg.setNeutralButton("Delete", (d, w) -> {
            project.texts.remove(existing);
            changed = true;
            showClip(true);
        });
        dlg.show();
    }

    // ---------- sticker / picture on top ----------

    private void stickerTool() {
        if (project.sticker == null) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            try {
                startActivityForResult(i, REQ_STICKER);
                toast("Choose a picture or sticker (PNG with a clear background works best)");
            } catch (Exception e) {
                toast("No file picker found");
            }
            return;
        }
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(Ui.text(this, "Size", 13, Ui.SUB, false));
        final SeekBar size = slider();
        size.setMax(100);
        size.setProgress(Math.round(project.stickerSize * 100));
        box.addView(size);
        box.addView(Ui.text(this, "Left ↔ right", 13, Ui.SUB, false));
        final SeekBar x = slider();
        x.setMax(100);
        x.setProgress(Math.round(project.stickerX * 100));
        box.addView(x);
        box.addView(Ui.text(this, "Top ↕ bottom", 13, Ui.SUB, false));
        final SeekBar y = slider();
        y.setMax(100);
        y.setProgress(Math.round(project.stickerY * 100));
        box.addView(y);
        new AlertDialog.Builder(this).setTitle("Sticker").setView(box)
                .setNeutralButton("Remove", (d, w) -> {
                    project.sticker = null;
                    changed = true;
                    showClip(true);
                })
                .setNegativeButton("Change picture", (d, w) -> {
                    project.sticker = null;
                    stickerTool();
                })
                .setPositiveButton("Done", (d, w) -> {
                    project.stickerSize = Math.max(0.05f, size.getProgress() / 100f);
                    project.stickerX = x.getProgress() / 100f;
                    project.stickerY = y.getProgress() / 100f;
                    changed = true;
                    showClip(true);
                }).show();
    }

    // ---------- music ----------

    private void musicTool() {
        if (project.music == null) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
            try {
                startActivityForResult(i, REQ_MUSIC);
            } catch (Exception e) {
                toast("No file picker found");
            }
            return;
        }
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(Ui.text(this, "♪ " + project.musicName, 14, Ui.TEXT, true));
        final TextView mt = Ui.text(this, "", 13, Ui.SUB, false);
        box.addView(mt);
        final SeekBar mv = slider();
        mv.setMax(150);
        mv.setProgress(Math.round(project.musicVolume * 100));
        box.addView(mv);
        final TextView ot = Ui.text(this, "", 13, Ui.SUB, false);
        box.addView(ot);
        final SeekBar ov = slider();
        ov.setMax(150);
        ov.setProgress(Math.round(project.originalVolume * 100));
        box.addView(ov);
        Runnable labels = () -> {
            mt.setText("Music volume  " + mv.getProgress() + "%");
            ot.setText("Original sound  " + ov.getProgress() + "%");
        };
        labels.run();
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { labels.run(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        };
        mv.setOnSeekBarChangeListener(l);
        ov.setOnSeekBarChangeListener(l);
        new AlertDialog.Builder(this).setTitle("Background music").setView(box)
                .setNeutralButton("Remove music", (d, w) -> {
                    project.music = null;
                    changed = true;
                    refresh();
                })
                .setNegativeButton("Change song", (d, w) -> {
                    project.music = null;
                    musicTool();
                })
                .setPositiveButton("Done", (d, w) -> {
                    project.musicVolume = mv.getProgress() / 100f;
                    project.originalVolume = ov.getProgress() / 100f;
                    changed = true;
                    refresh();
                    showClip(true);
                }).show();
    }

    // ---------- dialogs ----------

    interface IntCallback {
        void onValue(int v);
    }

    private void sliderDialog(String title, int min, int max, int value, String unit, IntCallback cb) {
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), 0);
        final TextView t = Ui.text(this, value + unit, 18, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        final SeekBar s = slider();
        s.setMax(max - min);
        s.setProgress(value - min);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { t.setText((p + min) + unit); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(s);
        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (d, w) -> cb.onValue(s.getProgress() + min)).show();
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    // ---------- export ----------

    private void askExport() {
        if (project.clips.isEmpty()) {
            toast("Add a clip first");
            return;
        }
        final int[] q = {480, 720, 1080, 0};
        String[] n = {"480p  (small file)", "720p  HD", "1080p  Full HD (recommended)", "Original quality"};
        new AlertDialog.Builder(this).setTitle("Export quality").setItems(n, (d, w) -> {
            project.quality = q[w];
            refresh();
            export(false);
        }).show();
    }

    private void export(final boolean audioOnly) {
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            toast("Allow storage access, then tap Export again");
            return;
        }
        if (audioOnly && allPhotos()) {
            toast("There's no sound to extract: all clips are photos");
            return;
        }
        player.pause();
        final File out = new File(getCacheDir(), audioOnly ? "export.m4a" : "export.mp4");
        if (out.exists()) out.delete();

        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 8));
        final TextView msg = Ui.text(this, "Preparing…", 15, Ui.TEXT, false);
        box.addView(msg);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        box.addView(bar);
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle(audioOnly ? "Extracting audio" : "Exporting video")
                .setView(box).setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> {
                    if (transformer != null) transformer.cancel();
                    transformer = null;
                }).show();

        Composition comp;
        try {
            comp = project.composition(this, audioOnly);
        } catch (Exception e) {
            dlg.dismiss();
            showError("Couldn't prepare the export: " + e.getMessage());
            return;
        }
        transformer = new Transformer.Builder(this)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        transformer = null;
                        msg.setText("Saving…");
                        saveExport(out, audioOnly, dlg);
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result, ExportException e) {
                        transformer = null;
                        dlg.dismiss();
                        showError("The export didn't work on this phone (" + e.getErrorCodeName() + ").\n\n"
                                + "Try a lower quality, fewer effects, or a shorter video.");
                    }
                })
                .build();
        try {
            transformer.start(comp, out.getAbsolutePath());
        } catch (Exception e) {
            dlg.dismiss();
            transformer = null;
            showError("Couldn't start the export: " + e.getMessage());
            return;
        }
        final ProgressHolder holder = new ProgressHolder();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (transformer == null) return;
                int state = transformer.getProgress(holder);
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    bar.setProgress(holder.progress);
                    msg.setText(holder.progress + "%  ·  keep Echo open");
                } else {
                    msg.setText("Working…  keep Echo open");
                }
                main.postDelayed(this, 400);
            }
        });
    }

    private boolean allPhotos() {
        for (EditProject.Clip c : project.clips) if (!c.image) return false;
        return true;
    }

    private void saveExport(File out, boolean audioOnly, AlertDialog dlg) {
        new Thread(() -> {
            Uri saved = null;
            String err = null;
            try {
                String name = "Echo_" + stamp() + (audioOnly ? ".m4a" : ".mp4");
                saved = Gallery.save(this, out, name, audioOnly ? "audio/mp4" : "video/mp4",
                        audioOnly ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final Uri result = saved;
            final String error = err;
            main.post(() -> {
                dlg.dismiss();
                if (result == null) {
                    showError("Couldn't save the file" + (error == null ? "." : ": " + error));
                    return;
                }
                changed = false;
                if (audioOnly) addToSongs(result);
                done(result, audioOnly);
            });
        }).start();
    }

    /** Extracted audio goes straight into your Echo songs. */
    private void addToSongs(Uri u) {
        Library.Song s = new Library.Song();
        s.id = "ext_" + Long.toString(System.currentTimeMillis(), 36);
        s.uri = u.toString();
        s.source = "device";
        s.title = project.clips.isEmpty() ? "Extracted audio" : project.clips.get(0).name.replaceAll("\\.[^.]+$", "");
        s.artist = "Extracted audio";
        s.album = "";
        s.added = System.currentTimeMillis();
        Library lib = Library.get(this);
        lib.addSong(s);
        lib.save();
    }

    private void done(Uri result, boolean audioOnly) {
        new AlertDialog.Builder(this).setTitle(audioOnly ? "Audio saved" : "Video saved")
                .setMessage(audioOnly ? "Saved to Music › Echo, and added to your Echo songs."
                        : "Saved to Movies › Echo. You'll find it in your gallery and in Echo's Videos.")
                .setPositiveButton(audioOnly ? "OK" : "Play", (d, w) -> {
                    if (!audioOnly) {
                        startActivity(new Intent(this, VideoPlayerActivity.class).setData(result)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    }
                })
                .setNeutralButton("Share", (d, w) -> {
                    Intent s = new Intent(Intent.ACTION_SEND).setType(audioOnly ? "audio/*" : "video/*")
                            .putExtra(Intent.EXTRA_STREAM, result).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(s, "Share"));
                })
                .setNegativeButton("Keep editing", null)
                .show();
    }

    private void showError(String m) {
        new AlertDialog.Builder(this).setTitle("Export").setMessage(m).setPositiveButton("OK", null).show();
    }

    // ---------- lifecycle ----------

    @Override
    public void onBackPressed() {
        if (changed && !project.clips.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Leave the editor?")
                    .setMessage("Your edits haven't been exported and will be lost.")
                    .setNegativeButton("Stay", null)
                    .setPositiveButton("Leave", (d, w) -> finish()).show();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (transformer != null) transformer.cancel();
        if (player != null) player.release();
        super.onDestroy();
    }
}
