package com.usama.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.widget.ScrollView;
import android.widget.TextView;

/** ⋮ → About: the build number and everything Echo can do, so anyone using the app knows its features. */
final class About {
    private About() {}

    private static final String[][] SECTIONS = {
            {"🎵  Music player",
                    "Spotify-style player with playlists, shuffle, repeat and a queue",
                    "Keeps playing with the screen off, with controls in the notification and on the lock screen",
                    "Bottom player with play, next and ✕ to close it; tap it for the full-screen player",
                    "Full-screen player: artwork, seek bar, shuffle, repeat and the queue (tap ☰ to jump to a song)"},
            {"🏠  Home, Search, Library",
                    "Home: greeting, Recently played, and Your folders as big cards",
                    "Chips on Home: Playlists, Videos, Songs, YouTube, Drive",
                    "Search: find songs in your library, or search Google and YouTube inside the app",
                    "Library: every folder and playlist in one list"},
            {"📁  Folders",
                    "Make folders and folders inside folders (+ → New folder)",
                    "Move a folder into another folder, rename it, play all or shuffle all (tap ⋮ or long-press)",
                    "Hide a folder: ⋮ → Hide. See hidden ones in ⋮ (top right) → Hidden folders",
                    "Delete a folder: removes it with its sub-folders and songs (songs also in other folders stay there)"},
            {"☑️  Selecting songs",
                    "Long-press a song to select it, then tap more songs; All selects everything",
                    "Move, copy, add to queue, play, or remove the selected songs from Echo"},
            {"☁️  Google Drive",
                    "+ → Import music → Google Drive folder: paste a folder link shared as \"Anyone with the link\"",
                    "Songs play online from Drive, nothing is downloaded; sub-folders keep their structure",
                    "Syncs automatically when Echo opens and about every hour: new, renamed and deleted songs and folders",
                    "Sync now: ⋮ on the folder → Sync with Drive now. Tip: share a Drive folder to Echo to add it"},
            {"▶️  YouTube",
                    "Import a YouTube playlist (paste the link, or share it from the YouTube app to Echo)",
                    "Plays in YouTube's own player inside Echo",
                    "Find these in my songs: makes an offline playlist from matching songs you already have",
                    "Download a YouTube video as audio or video: + → Download from YouTube link"},
            {"📱  Music on your phone",
                    "+ → Import music → All music on this phone, or Pick files on this phone",
                    "Share audio files from any app to Echo to add them"},
            {"🎬  Video player (like VLC)",
                    "Videos chip: all the videos on your phone by folder, with thumbnails",
                    "Swipe up/down on the left side for brightness, on the right side for volume",
                    "Swipe sideways to seek; double-tap left or right to skip 10 seconds",
                    "Pinch with two fingers to zoom in or out; drag with two fingers to move around when zoomed",
                    "Fit / Zoom / Stretch (resets the pinch zoom), rotate, picture-in-picture, lock controls",
                    "Speed 0.25×–3×, audio tracks and subtitles (settings gear), subtitle files (.srt .vtt .ass)",
                    "Sleep timer, repeat, and it remembers where you stopped each video",
                    "Play as audio: keeps a video's sound playing with the screen off",
                    "Opens any video or audio file from other apps (Open with → Echo), or a network stream link"},
            {"✂️  Video editor (CapCut / YouCut style)",
                    "Videos chip → Edit a video, or ⋮ → Edit in the player",
                    "Join several videos and photos; trim, split, copy, reorder and delete clips",
                    "Speed 0.25×–4× (slow motion / fast), clip volume, voice effects, photo duration",
                    "10 filters, brightness / contrast / saturation, rotate, flip",
                    "Canvas sizes: 9:16 (TikTok, Reels, Shorts), 16:9 (YouTube), 1:1, 4:5, 4:3, 3:4 — crop or black bars",
                    "Text (colour, size, position, box), a sticker or picture, background music with its own volume",
                    "Export 480p, 720p, 1080p or original to Movies › Echo",
                    "Extract audio (saved to Music › Echo and added to your songs) and Save frame as a picture"},
            {"🔄  Updates",
                    "⋮ → Check for updates installs the newest build and keeps your music and playlists",
                    "Echo checks by itself and shows a notification with what's new"},
    };

    static void show(Activity a) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Your personal music & video app — build ").append(String.valueOf(Updater.installedBuild(a))).append("\n");
        for (String[] sec : SECTIONS) {
            sb.append("\n");
            int start = sb.length();
            sb.append(sec[0]).append("\n");
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(Ui.GLOW), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(1.1f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            for (int i = 1; i < sec.length; i++) sb.append("•  ").append(sec[i]).append("\n");
        }
        TextView t = Ui.text(a, "", 14, Ui.TEXT, false);
        t.setText(sb);
        t.setLineSpacing(Ui.dp(a, 3), 1f);
        t.setPadding(Ui.dp(a, 22), Ui.dp(a, 6), Ui.dp(a, 22), Ui.dp(a, 10));
        ScrollView sv = new ScrollView(a);
        sv.addView(t);
        new AlertDialog.Builder(a).setTitle("Echo — what it can do").setView(sv)
                .setPositiveButton("Check for updates", (d, w) -> Updater.check(a))
                .setNegativeButton("Close", null).show();
    }
}
