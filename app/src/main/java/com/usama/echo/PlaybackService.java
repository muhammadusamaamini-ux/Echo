package com.usama.echo;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plays the music in the background, with the notification and lock-screen controls. */
public class PlaybackService extends MediaSessionService {
    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        // Private Google Drive songs ("gdrive://") are played with your sign-in token.
        DataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20000)
                .setReadTimeoutMs(20000);
        DataSource.Factory base = new DefaultDataSource.Factory(this, http);
        DataSource.Factory sources = new ResolvingDataSource.Factory(base, this::resolve);
        ExoPlayer player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(sources))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, player)
                .setSessionActivity(pi)
                .setCallback(new Callback())
                .build();
    }

    private DataSpec resolve(DataSpec spec) throws IOException {
        android.net.Uri u = spec.uri;
        if (!"gdrive".equals(u.getScheme())) return spec;
        String fileId = u.getLastPathSegment();
        String account = u.getQueryParameter("a");
        String token;
        try {
            token = DriveAuth.token(getApplicationContext(), account);
        } catch (Exception e) {
            throw new IOException("Open Echo and sign in to Google again", e);
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        return spec.buildUpon()
                .setUri(android.net.Uri.parse(DriveApi.mediaUrl(fileId)))
                .build()
                .withAdditionalHeaders(headers);
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (session != null) {
            Player p = session.getPlayer();
            if (!p.getPlayWhenReady() || p.getMediaItemCount() == 0) stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.getPlayer().release();
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    private class Callback implements MediaSession.Callback {
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession mediaSession,
                MediaSession.ControllerInfo controller, List<MediaItem> mediaItems) {
            Library lib = Library.get(PlaybackService.this);
            List<MediaItem> out = new ArrayList<>();
            for (MediaItem m : mediaItems) out.add(lib.resolve(m));
            return Futures.immediateFuture(out);
        }
    }
}
