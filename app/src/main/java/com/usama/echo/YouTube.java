package com.usama.echo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube helpers. Everything goes through YouTube's official embedded player,
 * so videos are played by YouTube itself and nothing is downloaded.
 */
final class YouTube {
    private YouTube() {}

    static String base(Context c) {
        return "https://" + c.getPackageName();
    }

    /** Page that loads YouTube's official player. pv = player settings (JSON), js = extra script. */
    static String page(Context c, String pv, String js) {
        return "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}"
                + "#p{position:absolute;top:0;left:0;width:100%;height:100%}</style></head><body><div id='p'></div>"
                + "<script>var player;"
                + "function onYouTubeIframeAPIReady(){player=new YT.Player('p',{width:'100%',height:'100%',playerVars:" + pv + ","
                + "events:{onReady:function(){if(window.ready)ready();},"
                + "onStateChange:function(e){if(window.state)state(e.data);},"
                + "onError:function(e){Android.onError(e.data);}}});}"
                + js
                + "</script><script src='https://www.youtube.com/iframe_api'></script></body></html>";
    }

    static String playerVars(Context c) {
        return "{playsinline:1,rel:0,modestbranding:1,origin:'" + base(c) + "'}";
    }

    @SuppressLint("SetJavaScriptEnabled")
    static WebView webView(Context c) {
        WebView w = new WebView(c);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        w.setBackgroundColor(0xFF000000);
        w.setWebChromeClient(new WebChromeClient());
        return w;
    }

    private static final Pattern LIST = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");

    /** Finds the playlist ID in a link like youtube.com/playlist?list=PL... */
    static String listId(String text) {
        if (text == null) return null;
        Matcher m = LIST.matcher(text);
        if (m.find()) return m.group(1);
        String t = text.trim();
        if (t.matches("[A-Za-z0-9_-]{12,}") && !t.contains(".")) return t;
        return null;
    }

    static String cleanAuthor(String a) {
        if (a == null) return "";
        return a.replace(" - Topic", "").replaceAll("VEVO$", "").trim();
    }

    // ---------- import a playlist ----------

    /** Step 1: ask for the link. */
    static void askLink(Activity a, String prefill) {
        LinearLayout box = Ui.column(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 8), p, 0);
        TextView help = Ui.text(a, "Paste a YouTube playlist link. It must be Public or Unlisted.\n"
                + "Tip: in the YouTube app, open the playlist, tap Share, and choose Echo.", 14, Ui.SUB, false);
        box.addView(help);
        final EditText link = new EditText(a);
        link.setHint("https://youtube.com/playlist?list=…");
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (prefill != null) link.setText(prefill);
        box.addView(link);
        final EditText name = new EditText(a);
        name.setHint("Playlist name (optional)");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(name);
        new AlertDialog.Builder(a)
                .setTitle("Import YouTube playlist")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Import", (d, w) -> {
                    String id = listId(link.getText().toString());
                    if (id == null) {
                        new AlertDialog.Builder(a).setMessage("That doesn't look like a playlist link. It should contain \"list=\".")
                                .setPositiveButton("OK", null).show();
                        return;
                    }
                    String n = name.getText().toString().trim();
                    readPlaylist(a, id, n.isEmpty() ? "YouTube playlist" : n);
                })
                .show();
    }

    /** Step 2: YouTube's player reads the list of videos; step 3: get each video's name. */
    @SuppressLint("AddJavascriptInterface")
    static void readPlaylist(Activity a, String listId, String name) {
        final Handler main = new Handler(Looper.getMainLooper());
        LinearLayout box = Ui.column(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 8), p, Ui.dp(a, 8));
        final WebView w = webView(a);
        int width = a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 100);
        box.addView(w, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, width * 9 / 16));
        final TextView status = Ui.text(a, "Opening the playlist…", 14, Ui.SUB, false);
        status.setPadding(0, Ui.dp(a, 12), 0, 0);
        box.addView(status);
        final boolean[] cancelled = {false};
        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Importing")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, x) -> cancelled[0] = true)
                .show();

        w.addJavascriptInterface(new Object() {
            boolean got;

            @JavascriptInterface
            public void onIds(String json) {
                if (got) return;
                got = true;
                main.post(() -> {
                    if (cancelled[0]) return;
                    w.destroy();
                    List<String> ids = new ArrayList<>();
                    try {
                        JSONArray arr = new JSONArray(json);
                        for (int i = 0; i < arr.length(); i++) ids.add(arr.getString(i));
                    } catch (Exception ignored) {
                    }
                    fetchNames(a, ids, listId, name, status, dialog, cancelled);
                });
            }

            @JavascriptInterface
            public void onError(int code) {
                if (got) return;
                got = true;
                main.post(() -> {
                    if (cancelled[0]) return;
                    w.destroy();
                    dialog.dismiss();
                    String msg = code == -1
                            ? "Couldn't read this playlist. Check that it's Public or Unlisted, then try again."
                            : "YouTube's player gave error " + code + ". Check that the playlist is Public or Unlisted.";
                    new AlertDialog.Builder(a).setTitle("Not imported").setMessage(msg).setPositiveButton("OK", null).show();
                });
            }
        }, "Android");

        String pv = "{playsinline:1,rel:0,listType:'playlist',list:'" + listId + "',origin:'" + base(a) + "'}";
        String js = "function ready(){try{player.cuePlaylist({listType:'playlist',list:'" + listId + "'});}catch(e){}"
                + "var n=0;var t=setInterval(function(){n++;var l=null;try{l=player.getPlaylist();}catch(e){}"
                + "if(l&&l.length){clearInterval(t);Android.onIds(JSON.stringify(l));}"
                + "else if(n>50){clearInterval(t);Android.onError(-1);}},400);}";
        w.loadDataWithBaseURL(base(a), page(a, pv, js), "text/html", "utf-8", null);
    }

    private static void fetchNames(Activity a, List<String> ids, String listId, String name, TextView status,
                                   AlertDialog dialog, boolean[] cancelled) {
        final Handler main = new Handler(Looper.getMainLooper());
        final int total = ids.size();
        status.setText("Found " + total + " videos. Getting their names…");
        final Library.YtItem[] out = new Library.YtItem[total];
        final AtomicInteger done = new AtomicInteger();
        new Thread(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            for (int i = 0; i < total; i++) {
                final int idx = i;
                pool.execute(() -> {
                    if (cancelled[0]) return;
                    out[idx] = oembed(ids.get(idx));
                    int d = done.incrementAndGet();
                    main.post(() -> status.setText("Getting names… " + d + " of " + total));
                });
            }
            pool.shutdown();
            try {
                pool.awaitTermination(10, TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {
            }
            main.post(() -> {
                if (cancelled[0]) return;
                dialog.dismiss();
                Library lib = Library.get(a);
                Library.Playlist pl = new Library.Playlist();
                pl.id = Library.newId();
                pl.name = name;
                pl.youtube = true;
                pl.ytListId = listId;
                for (Library.YtItem it : out) if (it != null) pl.ytItems.add(it);
                lib.playlists.add(pl);
                lib.save();
                int skipped = total - pl.ytItems.size();
                if (a instanceof MainActivity) ((MainActivity) a).refresh();
                Intent open = new Intent(a, YouTubeActivity.class).putExtra("id", pl.id);
                if (skipped > 0) open.putExtra("note", skipped + " private or deleted videos were skipped.");
                a.startActivity(open);
            });
        }).start();
    }

    /** YouTube's public "oEmbed" service gives a video's title and channel without any key. */
    private static Library.YtItem oembed(String videoId) {
        HttpURLConnection con = null;
        try {
            String watch = "https://www.youtube.com/watch?v=" + videoId;
            URL url = new URL("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(watch, "UTF-8"));
            con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(15000);
            if (con.getResponseCode() != 200) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            }
            JSONObject o = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            Library.YtItem it = new Library.YtItem();
            it.videoId = videoId;
            it.title = o.optString("title", "");
            it.author = cleanAuthor(o.optString("author_name", ""));
            return it;
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    static void openInYouTube(Context c, String listId, String videoId) {
        String url = listId != null ? "https://www.youtube.com/playlist?list=" + listId
                : "https://www.youtube.com/watch?v=" + videoId;
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }
}
