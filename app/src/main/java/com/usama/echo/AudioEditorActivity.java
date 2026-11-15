package com.usama.echo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Audio editor: trim, split, cut out a section, join several audios, speed, voice pitch, volume,
 * fade in/out, mix with a background sound; save as one file, each part separately, or as a ringtone.
 */
public class AudioEditorActivity extends Activity {
    private static final int REQ_ADD = 1, REQ_MIX = 2, REQ_WRITE = 3;

    static final class Part {
        Uri uri;
        String name;
        long sourceMs, startMs, endMs;
        float speed = 1f, pitch = 1f, volume = 1f;

        long lengthMs() {
            return (long) (Math.max(0, endMs - startMs) / speed);
        }

        Part copy() {
            Part p = new Part();
            p.uri = uri; p.name = name; p.sourceMs = sourceMs; p.startMs = startMs; p.endMs = endMs;
            p.speed = speed; p.pitch = pitch; p.volume = volume;
            return p;
        }
    }

    private final List<Part> parts = new ArrayList<>();
    private int selected = -1;
    private long fadeInMs, fadeOutMs;
    private Uri mix;
    private String mixName;
    private float mixVolume = 0.5f;
    private boolean changed;
    private long cutMarkMs = -1; // first point of "Cut out a section"

    private ExoPlayer player;
    private LinearLayout timeline, trimBox;
    private TextView totalView, nowView, partTitle, startText, endText;
    private SeekBar posBar, startBar, endBar;
    private ImageButton playBtn;
    private boolean trimming, dragging;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Transformer transformer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.column(this);
        LinearLayout bar = Ui.topBar(this, "Edit audio", R.drawable.ic_back);
        bar.getChildAt(0).setOnClickListener(v -> onBackPressed());
        TextView save = Ui.pill(this, "Save", true);
        save.setOnClickListener(v -> askSave());
        bar.addView(save);
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        body.setPadding(0, 0, 0, Ui.dp(this, 16));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Player card
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.glass(this, 20));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 12));
        partTitle = Ui.line(this, "", 16, Ui.TEXT, true);
        card.addView(partTitle);
        nowView = Ui.text(this, "0:00", 34, Ui.GLOW, true);
        nowView.setGravity(Gravity.CENTER);
        nowView.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        card.addView(nowView);
        posBar = slider();
        card.addView(posBar);
        LinearLayout ctr = Ui.row(this);
        ctr.setGravity(Gravity.CENTER);
        TextView back5 = Ui.pill(this, "−5 s", false);
        back5.setOnClickListener(v -> nudge(-5000));
        playBtn = Ui.icon(this, R.drawable.ic_play, 60, 0xFF1A1446);
        playBtn.setBackground(Ui.round(Color.WHITE, Ui.dp(this, 30)));
        playBtn.setOnClickListener(v -> { if (player != null) Hub.togglePlay(player); });
        TextView fwd5 = Ui.pill(this, "+5 s", false);
        fwd5.setOnClickListener(v -> nudge(5000));
        ctr.addView(back5);
        ctr.addView(space());
        ctr.addView(playBtn);
        ctr.addView(space());
        ctr.addView(fwd5);
        card.addView(ctr);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 6));
        body.addView(card, clp);

        totalView = Ui.text(this, "", 13, Ui.SUB, false);
        totalView.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), 0);
        body.addView(totalView);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        timeline = Ui.row(this);
        timeline.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        hs.addView(timeline);
        body.addView(hs);

        trimBox = Ui.column(this);
        trimBox.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 4));
        startText = Ui.text(this, "", 12, Ui.SUB, false);
        startBar = slider();
        endText = Ui.text(this, "", 12, Ui.SUB, false);
        endBar = slider();
        trimBox.addView(startText);
        trimBox.addView(startBar);
        trimBox.addView(endText);
        trimBox.addView(endBar);
        LinearLayout marks = Ui.row(this);
        TextView setS = Ui.pill(this, "Start here", false);
        setS.setOnClickListener(v -> setStartHere());
        TextView setE = Ui.pill(this, "End here", false);
        setE.setOnClickListener(v -> setEndHere());
        marks.addView(setS);
        marks.addView(setE, margin());
        trimBox.addView(marks);
        body.addView(trimBox);
        hookSliders();

        body.addView(label("Cut"));
        body.addView(toolRow(new String[]{"Split here", "Cut out part", "Delete", "Copy", "Move ←", "Move →", "Add audio"},
                new int[]{R.drawable.ic_cut, R.drawable.ic_cut, R.drawable.ic_close, R.drawable.ic_add, R.drawable.ic_back,
                        R.drawable.ic_next, R.drawable.ic_music}));
        body.addView(label("Sound"));
        body.addView(toolRow(new String[]{"Speed", "Voice", "Volume", "Fade", "Mix sound"},
                new int[]{R.drawable.ic_speed, R.drawable.ic_headphones, R.drawable.ic_headphones, R.drawable.ic_filter,
                        R.drawable.ic_music}));
        TextView tip = Ui.text(this, "Tip: to remove the middle of a song, play to where it should start and tap "
                + "\"Cut out part\", then play to where it should end and tap it again.", 12, Ui.DIM, false);
        tip.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), 0);
        body.addView(tip);
        setContentView(root);

        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onEvents(Player p, Player.Events e) {
                playBtn.setImageResource(p.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            }
        });
        main.post(tick);
        Hub.pauseIfPlaying();

        Uri start = getIntent().getData();
        if (start != null) {
            List<Uri> one = new ArrayList<>();
            one.add(start);
            addParts(one, getIntent().getStringExtra("title"));
        } else {
            refresh();
            pick(REQ_ADD);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Part p = current();
            if (player != null && p != null && !dragging) {
                long pos = player.getCurrentPosition(); // inside the trimmed part
                nowView.setText(Ui.time(pos) + (cutMarkMs >= 0 ? "  ✂" : ""));
                long len = Math.max(1, p.endMs - p.startMs);
                posBar.setProgress((int) (pos * 1000 / len));
            }
            main.postDelayed(this, 200);
        }
    };

    // ---------- small UI pieces ----------

    private View space() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 18), 1));
        return v;
    }

    private LinearLayout.LayoutParams margin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        return lp;
    }

    private SeekBar slider() {
        SeekBar s = new SeekBar(this);
        s.setMax(1000);
        s.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        s.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        return s;
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 12, Ui.DIM, true);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), 0, 0);
        return t;
    }

    private View toolRow(String[] names, int[] icons) {
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
            b.setOnClickListener(v -> tool(n));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 78), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            row.addView(b, lp);
        }
        hs.addView(row);
        return hs;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private Part current() {
        return selected >= 0 && selected < parts.size() ? parts.get(selected) : null;
    }

    // ---------- adding audio ----------

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, req == REQ_ADD);
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            toast("No file picker found");
        }
    }

    private void addParts(List<Uri> uris, String firstTitle) {
        final AlertDialog wait = new AlertDialog.Builder(this).setMessage("Adding…").setCancelable(false).show();
        new Thread(() -> {
            List<Part> added = new ArrayList<>();
            for (int i = 0; i < uris.size(); i++) {
                Uri u = uris.get(i);
                Part p = new Part();
                p.uri = u;
                p.name = i == 0 && firstTitle != null ? firstTitle : nameOf(u);
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) {
                        r.setDataSource(u.toString(), new java.util.HashMap<>());
                    } else {
                        r.setDataSource(this, u);
                    }
                    String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    p.sourceMs = d == null ? 0 : Long.parseLong(d.trim());
                } catch (Exception ignored) {
                } finally {
                    try { r.release(); } catch (Exception ignored) { }
                }
                p.startMs = 0;
                p.endMs = p.sourceMs;
                added.add(p);
            }
            main.post(() -> {
                wait.dismiss();
                int at = selected < 0 ? parts.size() : selected + 1;
                parts.addAll(at, added);
                selected = at;
                changed = true;
                refresh();
                showPart(false);
                for (Part p : added) if (p.sourceMs <= 0) toast("Couldn't read the length of " + p.name);
            });
        }).start();
    }

    private String nameOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) {
                return c.getString(0).replaceAll("\\.[^.]+$", "");
            }
        } catch (Exception ignored) {
        }
        String last = u.getLastPathSegment();
        return last == null ? "Audio" : last;
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
            if (req == REQ_ADD && parts.isEmpty()) finish();
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
            if (!uris.isEmpty()) addParts(uris, null);
        } else if (req == REQ_MIX && data.getData() != null) {
            keepAccess(data.getData());
            mix = data.getData();
            mixName = nameOf(mix);
            changed = true;
            mixTool();
        }
    }

    // ---------- parts strip ----------

    private void refresh() {
        timeline.removeAllViews();
        for (int i = 0; i < parts.size(); i++) {
            final int idx = i;
            Part p = parts.get(i);
            LinearLayout chip = Ui.column(this);
            chip.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
            android.graphics.drawable.GradientDrawable g = Ui.gradFor(p.name, Ui.dp(this, 12));
            if (i == selected) g.setStroke(Ui.dp(this, 3), Ui.GLOW);
            chip.setBackground(g);
            chip.addView(Ui.line(this, (i + 1) + ".  " + p.name, 12, Color.WHITE, true));
            chip.addView(Ui.line(this, Ui.time(p.lengthMs()) + (p.speed != 1f ? "  " + p.speed + "x" : ""), 11, 0xDDFFFFFF, false));
            chip.setOnClickListener(v -> {
                selected = idx;
                cutMarkMs = -1;
                refresh();
                showPart(true);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 120), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            timeline.addView(chip, lp);
        }
        TextView add = Ui.text(this, "+ Add", 13, Ui.TEXT, true);
        add.setGravity(Gravity.CENTER);
        add.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        add.setBackground(Ui.glass(this, 12));
        add.setOnClickListener(v -> pick(REQ_ADD));
        timeline.addView(add);
        long total = 0;
        for (Part p : parts) total += p.lengthMs();
        totalView.setText(parts.isEmpty() ? "No audio yet" : parts.size() + (parts.size() == 1 ? " part" : " parts")
                + "  ·  total " + Ui.time(total)
                + (fadeInMs > 0 || fadeOutMs > 0 ? "  ·  fade" : "") + (mix != null ? "  ·  + " + mixName : ""));
        Part c = current();
        partTitle.setText(c == null ? "Add audio to start" : c.name);
        trimBox.setVisibility(c != null && c.sourceMs > 0 ? View.VISIBLE : View.GONE);
        updateTrimText();
    }

    private void updateTrimText() {
        Part c = current();
        if (c == null || c.sourceMs <= 0) return;
        trimming = true;
        startBar.setProgress((int) (c.startMs * 1000 / c.sourceMs));
        endBar.setProgress((int) (c.endMs * 1000 / c.sourceMs));
        trimming = false;
        startText.setText("Start  " + Ui.time(c.startMs));
        endText.setText("End  " + Ui.time(c.endMs) + "   (" + Ui.time(c.endMs - c.startMs) + " of " + Ui.time(c.sourceMs) + ")");
    }

    private void hookSliders() {
        posBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                Part c = current();
                if (user && c != null) nowView.setText(Ui.time((c.endMs - c.startMs) * p / 1000));
            }

            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                Part c = current();
                if (c != null) player.seekTo((c.endMs - c.startMs) * s.getProgress() / 1000);
            }
        });
        SeekBar.OnSeekBarChangeListener trim = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                Part c = current();
                if (!user || trimming || c == null || c.sourceMs <= 0) return;
                long ms = c.sourceMs * p / 1000;
                if (s == startBar) c.startMs = Math.max(0, Math.min(ms, c.endMs - 300));
                else c.endMs = Math.min(c.sourceMs, Math.max(ms, c.startMs + 300));
                startText.setText("Start  " + Ui.time(c.startMs));
                endText.setText("End  " + Ui.time(c.endMs) + "   (" + Ui.time(c.endMs - c.startMs) + ")");
            }

            @Override public void onStartTrackingTouch(SeekBar s) { player.pause(); }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                changed = true;
                refresh();
                showPart(false);
                Part c = current();
                if (c != null && s == endBar) player.seekTo(Math.max(0, c.endMs - c.startMs - 3000)); // hear the end
                player.play();
            }
        };
        startBar.setOnSeekBarChangeListener(trim);
        endBar.setOnSeekBarChangeListener(trim);
    }

    private void showPart(boolean play) {
        Part c = current();
        if (c == null) {
            player.stop();
            player.clearMediaItems();
            return;
        }
        MediaItem mi = new MediaItem.Builder().setUri(c.uri)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.startMs)
                        .setEndPositionMs(c.endMs > c.startMs ? c.endMs : Long.MIN_VALUE)
                        .build())
                .build();
        player.setMediaItem(mi);
        player.setPlaybackParameters(new PlaybackParameters(c.speed, c.pitch));
        player.setVolume(Math.min(1f, c.volume));
        player.prepare();
        if (play) player.play();
    }

    private void nudge(long ms) {
        Part c = current();
        if (c == null) return;
        long len = c.endMs - c.startMs;
        player.seekTo(Math.max(0, Math.min(len, player.getCurrentPosition() + ms)));
    }

    private long here() {
        Part c = current();
        return c == null ? 0 : c.startMs + player.getCurrentPosition();
    }

    private void setStartHere() {
        Part c = current();
        if (c == null) return;
        long at = here();
        if (at >= c.endMs - 300) {
            toast("That's at the very end");
            return;
        }
        c.startMs = at;
        changed = true;
        refresh();
        showPart(true);
        toast("Starts at " + Ui.time(at));
    }

    private void setEndHere() {
        Part c = current();
        if (c == null) return;
        long at = here();
        if (at <= c.startMs + 300) {
            toast("That's at the very start");
            return;
        }
        c.endMs = at;
        changed = true;
        refresh();
        showPart(false);
        toast("Ends at " + Ui.time(at));
    }

    // ---------- tools ----------

    private void tool(String name) {
        if (name.equals("Add audio")) {
            pick(REQ_ADD);
            return;
        }
        if (name.equals("Fade")) {
            fade();
            return;
        }
        if (name.equals("Mix sound")) {
            mixTool();
            return;
        }
        Part c = current();
        if (c == null) {
            toast("Add audio first");
            return;
        }
        switch (name) {
            case "Split here": {
                long at = here();
                if (at <= c.startMs + 300 || at >= c.endMs - 300) {
                    toast("Play to the point where you want to split, then tap Split here");
                    return;
                }
                Part second = c.copy();
                c.endMs = at;
                second.startMs = at;
                parts.add(selected + 1, second);
                changed = true;
                refresh();
                toast("Split at " + Ui.time(at) + ". Now there are 2 parts.");
                break;
            }
            case "Cut out part": cutOut(c); break;
            case "Delete":
                parts.remove(selected);
                selected = Math.min(selected, parts.size() - 1);
                changed = true;
                refresh();
                showPart(false);
                break;
            case "Copy":
                parts.add(selected + 1, c.copy());
                selected++;
                changed = true;
                refresh();
                break;
            case "Move ←":
                if (selected > 0) {
                    parts.remove(selected);
                    parts.add(--selected, c);
                    changed = true;
                    refresh();
                }
                break;
            case "Move →":
                if (selected < parts.size() - 1) {
                    parts.remove(selected);
                    parts.add(++selected, c);
                    changed = true;
                    refresh();
                }
                break;
            case "Speed": {
                final float[] v = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
                String[] n = {"0.5x", "0.75x", "Normal", "1.25x", "1.5x", "2x"};
                new AlertDialog.Builder(this).setTitle("Speed").setItems(n, (d, w) -> {
                    c.speed = v[w];
                    changed = true;
                    refresh();
                    showPart(true);
                }).show();
                break;
            }
            case "Voice": {
                final float[] v = {1f, 1.5f, 1.25f, 0.85f, 0.65f};
                String[] n = {"Normal", "Chipmunk", "Higher", "Deeper", "Monster"};
                new AlertDialog.Builder(this).setTitle("Voice / pitch").setItems(n, (d, w) -> {
                    c.pitch = v[w];
                    changed = true;
                    showPart(true);
                }).show();
                break;
            }
            case "Volume":
                sliderDialog("Volume of this part", 0, 300, Math.round(c.volume * 100), "%", val -> {
                    c.volume = val / 100f;
                    changed = true;
                    showPart(true);
                });
                break;
        }
    }

    /** Tap once at the start of the bit to remove, once at its end: that bit is cut out. */
    private void cutOut(Part c) {
        long at = here();
        if (cutMarkMs < 0) {
            cutMarkMs = at;
            toast("Start of the cut marked at " + Ui.time(at) + ". Now play to its end and tap Cut out part again.");
            return;
        }
        long a = Math.min(cutMarkMs, at), z = Math.max(cutMarkMs, at);
        cutMarkMs = -1;
        if (z - a < 200) {
            toast("The section is too short. Try again.");
            return;
        }
        a = Math.max(a, c.startMs);
        z = Math.min(z, c.endMs);
        List<Part> keep = new ArrayList<>();
        if (a - c.startMs >= 200) {
            Part first = c.copy();
            first.endMs = a;
            keep.add(first);
        }
        if (c.endMs - z >= 200) {
            Part last = c.copy();
            last.startMs = z;
            keep.add(last);
        }
        parts.remove(selected);
        parts.addAll(selected, keep);
        if (parts.isEmpty()) selected = -1;
        else selected = Math.min(selected, parts.size() - 1);
        changed = true;
        refresh();
        showPart(false);
        toast("Cut out " + Ui.time(z - a) + " (" + Ui.time(a) + " – " + Ui.time(z) + ")");
    }

    private void fade() {
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), 0);
        final TextView ti = Ui.text(this, "", 14, Ui.TEXT, false);
        final SeekBar si = slider();
        si.setMax(10);
        si.setProgress((int) (fadeInMs / 1000));
        final TextView to = Ui.text(this, "", 14, Ui.TEXT, false);
        final SeekBar so = slider();
        so.setMax(10);
        so.setProgress((int) (fadeOutMs / 1000));
        Runnable lbl = () -> {
            ti.setText("Fade in at the start:  " + (si.getProgress() == 0 ? "off" : si.getProgress() + " s"));
            to.setText("Fade out at the end:  " + (so.getProgress() == 0 ? "off" : so.getProgress() + " s"));
        };
        lbl.run();
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { lbl.run(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        };
        si.setOnSeekBarChangeListener(l);
        so.setOnSeekBarChangeListener(l);
        box.addView(ti);
        box.addView(si);
        box.addView(to);
        box.addView(so);
        new AlertDialog.Builder(this).setTitle("Fade").setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (d, w) -> {
                    fadeInMs = si.getProgress() * 1000L;
                    fadeOutMs = so.getProgress() * 1000L;
                    changed = true;
                    refresh();
                    toast("Fades are added when you save");
                }).show();
    }

    private void mixTool() {
        if (mix == null) {
            toast("Choose a sound to play under your audio (e.g. background music)");
            pick(REQ_MIX);
            return;
        }
        sliderDialog("Volume of \"" + mixName + "\"", 0, 150, Math.round(mixVolume * 100), "%", val -> {
            mixVolume = val / 100f;
            changed = true;
            refresh();
        });
        // a second dialog would hide the first; offer removal through a toast-free menu instead
        main.postDelayed(() -> toast("To remove the mixed sound, set its volume to 0%"), 300);
    }

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

    // ---------- saving ----------

    private void askSave() {
        if (parts.isEmpty()) {
            toast("Add audio first");
            return;
        }
        List<String> opts = new ArrayList<>();
        opts.add(parts.size() > 1 ? "Join all parts into one file" : "Save as a new file");
        if (parts.size() > 1) opts.add("Save each part as its own file (" + parts.size() + " files)");
        opts.add("Save as a ringtone");
        new AlertDialog.Builder(this).setTitle("Save audio")
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    String o = opts.get(w);
                    if (o.startsWith("Save each")) saveParts(0, new ArrayList<>());
                    else export(parts, o.startsWith("Save as a ringtone"), null);
                }).show();
    }

    /** Saves every part separately, one after another. */
    private void saveParts(int i, List<Uri> done) {
        if (i >= parts.size()) {
            new AlertDialog.Builder(this).setTitle("Saved " + done.size() + " files")
                    .setMessage("They're in Music › Echo and in your Echo songs.")
                    .setPositiveButton("OK", null).show();
            changed = false;
            return;
        }
        List<Part> one = new ArrayList<>();
        one.add(parts.get(i));
        export(one, false, uri -> {
            done.add(uri);
            saveParts(i + 1, done);
        });
    }

    interface Saved {
        void onSaved(Uri uri);
    }

    private List<AudioProcessor> processors(Part p, boolean first, boolean last) {
        List<AudioProcessor> a = new ArrayList<>();
        if (p.speed != 1f || p.pitch != 1f) {
            SonicAudioProcessor s = new SonicAudioProcessor();
            s.setSpeed(p.speed);
            s.setPitch(p.pitch);
            a.add(s);
        }
        if (p.volume != 1f) {
            ChannelMixingAudioProcessor m = new ChannelMixingAudioProcessor();
            m.putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(p.volume));
            m.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(p.volume));
            a.add(m);
        }
        long in = first ? fadeInMs : 0, out = last ? fadeOutMs : 0;
        if (in > 0 || out > 0) a.add(new FadeAudioProcessor(in, out, p.lengthMs()));
        return a;
    }

    private void export(List<Part> list, boolean ringtone, Saved next) {
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            toast("Allow storage access, then tap Save again");
            return;
        }
        player.pause();
        List<EditedMediaItem> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Part p = list.get(i);
            MediaItem mi = new MediaItem.Builder().setUri(p.uri)
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(p.startMs)
                            .setEndPositionMs(p.endMs > p.startMs ? p.endMs : Long.MIN_VALUE)
                            .build())
                    .build();
            items.add(new EditedMediaItem.Builder(mi)
                    .setRemoveVideo(true)
                    .setEffects(new Effects(processors(p, i == 0, i == list.size() - 1), ImmutableList.of()))
                    .build());
        }
        List<EditedMediaItemSequence> seqs = new ArrayList<>();
        seqs.add(new EditedMediaItemSequence(items));
        if (mix != null && mixVolume > 0 && next == null) {
            List<AudioProcessor> mp = new ArrayList<>();
            ChannelMixingAudioProcessor m = new ChannelMixingAudioProcessor();
            m.putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(mixVolume));
            m.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(mixVolume));
            mp.add(m);
            seqs.add(new EditedMediaItemSequence(ImmutableList.of(new EditedMediaItem.Builder(MediaItem.fromUri(mix))
                    .setRemoveVideo(true).setEffects(new Effects(mp, ImmutableList.of())).build()), true));
        }
        Composition comp = new Composition.Builder(seqs).build();

        final File out = new File(getCacheDir(), "audio_export.m4a");
        if (out.exists()) out.delete();
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 8));
        final TextView msg = Ui.text(this, "Preparing…", 15, Ui.TEXT, false);
        box.addView(msg);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        box.addView(bar);
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Saving " + list.get(0).name)
                .setView(box).setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> {
                    if (transformer != null) transformer.cancel();
                    transformer = null;
                }).show();
        transformer = new Transformer.Builder(this)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        transformer = null;
                        msg.setText("Saving…");
                        String base = list.size() == 1 ? list.get(0).name : parts.get(0).name + " (edited)";
                        saveFile(out, base, ringtone, dlg, next);
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result, ExportException e) {
                        transformer = null;
                        dlg.dismiss();
                        new AlertDialog.Builder(AudioEditorActivity.this).setTitle("Couldn't save")
                                .setMessage("Saving didn't work for this audio (" + e.getErrorCodeName() + ").")
                                .setPositiveButton("OK", null).show();
                    }
                })
                .build();
        try {
            transformer.start(comp, out.getAbsolutePath());
        } catch (Exception e) {
            dlg.dismiss();
            transformer = null;
            toast("Couldn't start saving: " + e.getMessage());
            return;
        }
        final ProgressHolder holder = new ProgressHolder();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (transformer == null) return;
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    bar.setProgress(holder.progress);
                    msg.setText(holder.progress + "%");
                }
                main.postDelayed(this, 300);
            }
        });
    }

    private void saveFile(File out, String base, boolean ringtone, AlertDialog dlg, Saved next) {
        new Thread(() -> {
            Uri saved = null;
            try {
                String stamp = new SimpleDateFormat("HHmmss", Locale.US).format(new Date());
                String name = base.replaceAll("[\\\\/:*?\"<>|]", "_") + "_" + stamp + ".m4a";
                saved = Gallery.save(this, out, name, "audio/mp4",
                        ringtone ? Environment.DIRECTORY_RINGTONES : Environment.DIRECTORY_MUSIC);
            } catch (Exception ignored) {
            }
            final Uri result = saved;
            main.post(() -> {
                dlg.dismiss();
                if (result == null) {
                    toast("Couldn't save the file");
                    return;
                }
                addToSongs(result, base);
                if (next != null) {
                    next.onSaved(result);
                    return;
                }
                changed = false;
                new AlertDialog.Builder(this).setTitle(ringtone ? "Ringtone saved" : "Audio saved")
                        .setMessage(ringtone
                                ? "Saved to Ringtones › Echo. Choose it in your phone's Settings › Sound › Ringtone."
                                : "Saved to Music › Echo and added to your Echo songs.")
                        .setPositiveButton("Play", (d, w) -> {
                            Library.Song s = Library.get(this).song(lastAddedId);
                            if (s != null) {
                                List<Library.Song> one = new ArrayList<>();
                                one.add(s);
                                Hub.play(this, one, 0, false);
                            }
                        })
                        .setNeutralButton("Share", (d, w) -> {
                            Intent s = new Intent(Intent.ACTION_SEND).setType("audio/*")
                                    .putExtra(Intent.EXTRA_STREAM, result).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(s, "Share"));
                        })
                        .setNegativeButton("Keep editing", null).show();
            });
        }).start();
    }

    private String lastAddedId;

    private void addToSongs(Uri u, String title) {
        Library.Song s = new Library.Song();
        s.id = "aed_" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 999), 36);
        s.uri = u.toString();
        s.source = "device";
        s.title = title;
        s.artist = "Edited audio";
        s.album = "";
        s.added = System.currentTimeMillis();
        Library lib = Library.get(this);
        lib.addSong(s);
        lib.save();
        lastAddedId = s.id;
    }

    // ---------- lifecycle ----------

    @Override
    public void onBackPressed() {
        if (changed && !parts.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Leave the audio editor?")
                    .setMessage("Your changes haven't been saved.")
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
