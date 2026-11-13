package com.usama.naghma;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;

import java.util.Arrays;

/** The small "now playing" bar at the bottom of a screen. */
final class MiniPlayer implements Player.Listener {
    private final Activity a;
    private final Runnable onTrackChanged;
    final LinearLayout view;
    private final ProgressBar progress;
    private final ImageView art;
    private final TextView title, artist;
    private final ImageButton playBtn;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaController controller;
    private byte[] artData;
    private boolean alive = true;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (alive) handler.postDelayed(this, 500);
        }
    };

    MiniPlayer(Activity a, Runnable onTrackChanged) {
        this.a = a;
        this.onTrackChanged = onTrackChanged;
        view = Ui.column(a);
        view.setBackgroundColor(0xFF242424);
        progress = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF444444));
        view.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 3)));

        LinearLayout row = Ui.row(a);
        row.setPadding(Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
        art = new ImageView(a);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        art.setBackground(Ui.round(Ui.SURFACE2, Ui.dp(a, 4)));
        row.addView(art, new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
        LinearLayout col = Ui.column(a);
        col.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 6), 0);
        title = Ui.line(a, "", 15, Ui.TEXT, true);
        artist = Ui.line(a, "", 13, Ui.SUB, false);
        col.addView(title);
        col.addView(artist);
        row.addView(col, Ui.weight());
        playBtn = Ui.icon(a, R.drawable.ic_play, 48, Ui.TEXT);
        row.addView(playBtn);
        ImageButton next = Ui.icon(a, R.drawable.ic_next, 44, Ui.TEXT);
        row.addView(next);
        view.addView(row);
        view.setVisibility(View.GONE);

        playBtn.setOnClickListener(v -> { if (controller != null) Hub.togglePlay(controller); });
        next.setOnClickListener(v -> { if (controller != null && controller.hasNextMediaItem()) controller.seekToNextMediaItem(); });
        row.setOnClickListener(v -> a.startActivity(new Intent(a, NowPlayingActivity.class)));

        Hub.with(a, c -> {
            if (!alive) return;
            controller = c;
            c.addListener(this);
            update();
        });
        handler.post(tick);
    }

    String currentId() {
        if (controller == null) return null;
        MediaItem m = controller.getCurrentMediaItem();
        return m == null ? null : m.mediaId;
    }

    void release() {
        alive = false;
        handler.removeCallbacksAndMessages(null);
        if (controller != null) controller.removeListener(this);
    }

    @Override
    public void onEvents(Player player, Player.Events events) {
        update();
    }

    private void update() {
        if (controller == null) return;
        MediaItem item = controller.getCurrentMediaItem();
        if (item == null) {
            view.setVisibility(View.GONE);
            onTrackChanged.run();
            return;
        }
        view.setVisibility(View.VISIBLE);
        MediaMetadata md = controller.getMediaMetadata();
        CharSequence t = md.title != null ? md.title : item.mediaMetadata.title;
        CharSequence ar = md.artist != null ? md.artist : item.mediaMetadata.artist;
        title.setText(t == null ? "" : t);
        artist.setText(ar == null ? "" : ar);
        playBtn.setImageResource(controller.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        setArt(art, md.artworkData, artData);
        artData = md.artworkData;
        updateProgress();
        onTrackChanged.run();
    }

    static void setArt(ImageView iv, byte[] data, byte[] previous) {
        if (data != null && previous != null && Arrays.equals(data, previous) && iv.getDrawable() != null) return;
        if (data != null) {
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b != null) {
                iv.setImageBitmap(b);
                iv.setColorFilter(null);
                return;
            }
        }
        iv.setImageResource(R.drawable.ic_music);
        iv.setColorFilter(Ui.DIM);
    }

    private void updateProgress() {
        if (controller == null) return;
        long d = controller.getDuration();
        long p = controller.getCurrentPosition();
        progress.setProgress(d > 0 ? (int) (p * 1000 / d) : 0);
    }
}
