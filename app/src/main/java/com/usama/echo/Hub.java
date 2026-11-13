package com.usama.echo;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

/** One shared connection from the screens to the music player service. */
final class Hub {
    interface Ready {
        void run(MediaController c);
    }

    private static ListenableFuture<MediaController> future;
    private static MediaController controller;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Hub() {}

    static MediaController now() {
        return (controller != null && controller.isConnected()) ? controller : null;
    }

    static void with(Context ctx, Ready r) {
        MediaController c = now();
        if (c != null) {
            r.run(c);
            return;
        }
        if (controller != null) {
            controller.release();
            controller = null;
            future = null;
        }
        Context app = ctx.getApplicationContext();
        if (future == null) {
            SessionToken token = new SessionToken(app, new ComponentName(app, PlaybackService.class));
            future = new MediaController.Builder(app, token).buildAsync();
        }
        final ListenableFuture<MediaController> f = future;
        f.addListener(() -> {
            try {
                controller = f.get();
                r.run(controller);
            } catch (Exception e) {
                future = null;
            }
        }, main::post);
    }

    static void play(Context ctx, List<Library.Song> songs, int index, boolean shuffle) {
        if (songs.isEmpty()) return;
        final List<MediaItem> items = Library.items(songs);
        final int start = shuffle ? (int) (Math.random() * songs.size()) : Math.max(0, Math.min(index, songs.size() - 1));
        with(ctx, c -> {
            if (shuffle) c.setShuffleModeEnabled(true);
            c.setMediaItems(items, start, 0);
            c.prepare();
            c.play();
        });
    }

    static void playNext(Context ctx, Library.Song s) {
        final MediaItem item = Library.item(s);
        with(ctx, c -> {
            if (c.getMediaItemCount() == 0) {
                c.setMediaItem(item);
                c.prepare();
                c.play();
            } else {
                c.addMediaItem(c.getCurrentMediaItemIndex() + 1, item);
            }
        });
    }

    static void addToQueue(Context ctx, Library.Song s) {
        final MediaItem item = Library.item(s);
        with(ctx, c -> {
            c.addMediaItem(item);
            if (c.getMediaItemCount() == 1) {
                c.prepare();
                c.play();
            }
        });
    }

    static void togglePlay(Player c) {
        if (c.isPlaying()) {
            c.pause();
            return;
        }
        if (c.getPlaybackState() == Player.STATE_IDLE) c.prepare();
        else if (c.getPlaybackState() == Player.STATE_ENDED) c.seekToDefaultPosition();
        c.play();
    }

    static void pauseIfPlaying() {
        MediaController c = now();
        if (c != null && c.isPlaying()) c.pause();
    }
}
