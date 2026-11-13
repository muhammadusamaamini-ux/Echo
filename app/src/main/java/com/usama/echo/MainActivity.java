package com.usama.echo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_FILES = 1, REQ_PERM = 2, REQ_WEB = 3, REQ_NOTIFY = 4;

    private final TextView[] tabs = new TextView[3];
    private final View[] pages = new View[3];
    private int tab;
    private SongAdapter songAdapter, searchAdapter;
    private BaseAdapter listAdapter;
    private List<Library.Playlist> playlists = new ArrayList<>();
    private EditText searchBox;
    private TextView searchHint;
    private MiniPlayer mini;
    private AlertDialog progressDialog;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        // Header
        LinearLayout head = Ui.row(this);
        head.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 6));
        TextView name = Ui.text(this, "Echo", 28, Ui.TEXT, true);
        head.addView(name, Ui.weight());
        TextView imp = Ui.pill(this, "+ Import", true);
        imp.setOnClickListener(v -> showImport());
        head.addView(imp);
        ImageButton menu = Ui.icon(this, R.drawable.ic_more, 44, Ui.TEXT);
        menu.setOnClickListener(this::mainMenu);
        head.addView(menu);
        root.addView(head);

        // Tabs
        LinearLayout tabRow = Ui.row(this);
        tabRow.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 8));
        String[] labels = {"Playlists", "Songs", "Search"};
        for (int i = 0; i < 3; i++) {
            TextView t = Ui.text(this, labels[i], 14, Ui.TEXT, true);
            t.setPadding(Ui.dp(this, 16), Ui.dp(this, 7), Ui.dp(this, 16), Ui.dp(this, 7));
            final int idx = i;
            t.setOnClickListener(v -> showTab(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 8);
            tabRow.addView(t, lp);
            tabs[i] = t;
        }
        root.addView(tabRow);

        FrameLayout content = new FrameLayout(this);
        pages[0] = buildPlaylistsPage();
        pages[1] = buildSongsPage();
        pages[2] = buildSearchPage();
        for (View p : pages) content.addView(p, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        mini = new MiniPlayer(this, () -> {
            String id = mini == null ? null : mini.currentId();
            if (songAdapter != null) songAdapter.setCurrent(id);
            if (searchAdapter != null) searchAdapter.setCurrent(id);
        });
        root.addView(mini.view);

        setContentView(root);
        showTab(0);
        refresh();
        handleShare(getIntent());
        setupUpdates(getIntent());
    }

    // ---------- updates ----------

    private void setupUpdates(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences("updates", MODE_PRIVATE).getBoolean("askedNotify", false)) {
            getSharedPreferences("updates", MODE_PRIVATE).edit().putBoolean("askedNotify", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
        Updater.schedule(this);
        if (intent != null && intent.getBooleanExtra("update", false)) {
            intent.removeExtra("update");
            Updater.check(this);
        } else {
            // Quiet check each time Echo opens; shows the notification if a new build is out.
            final android.content.Context app = getApplicationContext();
            new Thread(() -> {
                try {
                    Updater.checkAndNotify(app);
                } catch (Exception ignored) {
                }
            }).start();
        }
    }

    private void mainMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Check for updates");
        m.getMenu().add(0, 2, 1, "About (build " + Updater.installedBuild(this) + ")");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                Updater.check(this);
            } else {
                new AlertDialog.Builder(this).setTitle("Echo")
                        .setMessage("Build " + Updater.installedBuild(this) + "\n\nYour personal music player. "
                                + "Echo checks for updates every few hours and tells you with a notification.")
                        .setPositiveButton("Check for updates", (d, w) -> Updater.check(this))
                        .setNegativeButton("Close", null).show();
            }
            return true;
        });
        m.show();
    }

    // ---------- pages ----------

    private View buildSongsPage() {
        FrameLayout f = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        songAdapter = new SongAdapter(this, this::songMenu);
        list.setAdapter(songAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> Hub.play(this, songAdapter.items(), pos, false));
        TextView empty = Ui.text(this, "No songs yet.\n\nTap \"+ Import\" to play music from Google Drive, "
                + "your phone, or a YouTube playlist.", 16, Ui.SUB, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), Ui.dp(this, 60));
        f.addView(list);
        f.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);
        return f;
    }

    private View buildPlaylistsPage() {
        ListView list = new ListView(this);
        list.setDivider(null);
        listAdapter = new BaseAdapter() {
            @Override public int getCount() { return playlists.size() + 1; }
            @Override public Object getItem(int i) { return i == 0 ? null : playlists.get(i - 1); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                if (i == 0) return Folders.addRow(MainActivity.this, "New folder");
                return Folders.row(MainActivity.this, playlists.get(i - 1), MainActivity.this::refresh);
            }
        };
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((parent, v, pos, id) -> {
            if (pos == 0) Folders.newFolder(this, null, f -> { refresh(); openPlaylist(f); });
            else openPlaylist(playlists.get(pos - 1));
        });
        return list;
    }

    private View buildSearchPage() {
        LinearLayout page = Ui.column(this);
        LinearLayout boxRow = Ui.row(this);
        boxRow.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 8));
        searchBox = new EditText(this);
        searchBox.setHint("Songs, artists, anything about music");
        searchBox.setHintTextColor(Ui.DIM);
        searchBox.setTextColor(Ui.TEXT);
        searchBox.setSingleLine(true);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setBackground(Ui.round(Ui.SURFACE2, Ui.dp(this, 8)));
        searchBox.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        searchBox.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        searchBox.setCompoundDrawablePadding(Ui.dp(this, 10));
        boxRow.addView(searchBox, Ui.weight());
        page.addView(boxRow);

        LinearLayout web = Ui.row(this);
        web.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        TextView g = Ui.pill(this, "Search Google", false);
        g.setOnClickListener(v -> startActivityForResult(WebActivity.google(this, query("music")), REQ_WEB));
        TextView y = Ui.pill(this, "Search YouTube", false);
        y.setOnClickListener(v -> startActivityForResult(WebActivity.youtube(this, query("songs")), REQ_WEB));
        web.addView(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        web.addView(y, lp);
        page.addView(web);

        searchHint = Ui.text(this, "Type to find songs in your library, or search Google / YouTube. "
                + "On YouTube, open a playlist and tap \"Import playlist\".", 14, Ui.SUB, false);
        searchHint.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        page.addView(searchHint);

        ListView list = new ListView(this);
        list.setDivider(null);
        searchAdapter = new SongAdapter(this, this::songMenu);
        list.setAdapter(searchAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> Hub.play(this, searchAdapter.items(), pos, false));
        page.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { runSearch(); }
        });
        searchBox.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH && songCountFor(searchBox.getText().toString()) == 0
                    && searchBox.getText().length() > 0) {
                startActivityForResult(WebActivity.google(this, query("music")), REQ_WEB);
                return true;
            }
            return false;
        });
        return page;
    }

    private String query(String fallback) {
        String q = searchBox.getText().toString().trim();
        return q.isEmpty() ? fallback : q;
    }

    private int songCountFor(String q) {
        return Library.get(this).search(q).size();
    }

    private void runSearch() {
        String q = searchBox.getText().toString();
        List<Library.Song> res = Library.get(this).search(q);
        searchAdapter.setItems(res);
        if (q.trim().isEmpty()) {
            searchHint.setText("Type to find songs in your library, or search Google / YouTube. "
                    + "On YouTube, open a playlist and tap \"Import playlist\".");
            searchHint.setVisibility(View.VISIBLE);
        } else if (res.isEmpty()) {
            searchHint.setText("Nothing in your library for \"" + q.trim() + "\". Try Google or YouTube above.");
            searchHint.setVisibility(View.VISIBLE);
        } else {
            searchHint.setVisibility(View.GONE);
        }
    }

    private void showTab(int i) {
        tab = i;
        for (int k = 0; k < 3; k++) {
            boolean on = k == i;
            pages[k].setVisibility(on ? View.VISIBLE : View.GONE);
            tabs[k].setTextColor(on ? 0xFF000000 : Ui.TEXT);
            tabs[k].setBackground(Ui.round(on ? Ui.ACCENT : Ui.SURFACE2, Ui.dp(this, 20)));
        }
    }

    void refresh() {
        Library lib = Library.get(this);
        songAdapter.setItems(lib.sortedSongs());
        playlists = lib.children(null);
        listAdapter.notifyDataSetChanged();
        if (searchBox != null) runSearch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onBackPressed() {
        if (tab != 0) showTab(0);
        else super.onBackPressed();
    }

    // ---------- menus ----------

    private void songMenu(View anchor, Library.Song s, int pos) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Play next");
        m.getMenu().add(0, 2, 1, "Add to queue");
        m.getMenu().add(0, 3, 2, "Add to folder…");
        m.getMenu().add(0, 4, 3, "Search on Google");
        m.getMenu().add(0, 5, 4, s.source.equals("file") ? "Delete" : "Remove from Echo");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: Hub.playNext(this, s); toast("Plays next"); break;
                case 2: Hub.addToQueue(this, s); toast("Added to queue"); break;
                case 3: addToPlaylist(s); break;
                case 4: startActivityForResult(WebActivity.google(this,
                        s.title + (s.artist == null || s.artist.isEmpty() ? " song" : " " + s.artist)), REQ_WEB); break;
                case 5: confirmDelete(s); break;
            }
            return true;
        });
        m.show();
    }

    private void confirmDelete(Library.Song s) {
        String msg = s.source.equals("drive")
                ? "Remove \"" + s.title + "\" from Echo? It stays in your Google Drive."
                : s.source.equals("device")
                ? "Remove \"" + s.title + "\" from Echo? The file stays on your phone."
                : "Delete \"" + s.title + "\"? It is removed from Echo and all playlists.";
        new AlertDialog.Builder(this).setMessage(msg).setNegativeButton("Cancel", null)
                .setPositiveButton(s.source.equals("file") ? "Delete" : "Remove", (d, w) -> {
                    Library.get(this).deleteSong(s);
                    refresh();
                }).show();
    }

    private void addToPlaylist(Library.Song s) {
        final List<Library.Playlist> local = Library.get(this).localPlaylists();
        String[] names = new String[local.size() + 1];
        names[0] = "+ New folder";
        for (int i = 0; i < local.size(); i++) names[i + 1] = Library.get(this).path(local.get(i));
        new AlertDialog.Builder(this).setTitle("Add to folder")
                .setItems(names, (d, which) -> {
                    if (which == 0) {
                        newPlaylist(s);
                    } else {
                        Library.Playlist p = local.get(which - 1);
                        if (!p.songIds.contains(s.id)) p.songIds.add(s.id);
                        Library.get(this).save();
                        toast("Added to " + p.name);
                        refresh();
                    }
                }).show();
    }

    private void newPlaylist(Library.Song first) {
        askName("New folder", "My playlist", n -> {
            Library.Playlist p = Library.get(this).newPlaylist(n);
            if (first != null) {
                p.songIds.add(first.id);
                Library.get(this).save();
                toast("Added to " + p.name);
                refresh();
            } else {
                refresh();
                openPlaylist(p);
            }
        });
    }

    interface NameCallback {
        void onName(String name);
    }

    private void askName(String title, String def, NameCallback cb) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText(def);
        e.setSelectAllOnFocus(true);
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(e);
        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (d, w) -> {
                    String n = e.getText().toString().trim();
                    cb.onName(n.isEmpty() ? def : n);
                }).show();
        e.requestFocus();
    }

    private void openPlaylist(Library.Playlist p) {
        startActivity(new Intent(this, p.youtube ? YouTubeActivity.class : PlaylistActivity.class).putExtra("id", p.id));
    }

    // ---------- importing ----------

    private void showImport() {
        String[] options = {
                "Google Drive folder (plays online)",
                "YouTube playlist",
                "All music on this phone",
                "Pick files on this phone"
        };
        new AlertDialog.Builder(this).setTitle("Import music")
                .setItems(options, (d, which) -> {
                    if (which == 0) Drive.askLink(this, null);
                    else if (which == 1) YouTube.askLink(this, null);
                    else if (which == 2) importDevice();
                    else pickFiles();
                }).show();
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_FILES);
        } catch (Exception e) {
            toast("No file picker found");
        }
    }

    private void importFiles(List<Uri> uris) {
        if (uris.isEmpty()) return;
        final TextView msg = Ui.text(this, "Copying songs…", 15, Ui.TEXT, false);
        msg.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 8));
        progressDialog = new AlertDialog.Builder(this).setTitle("Adding songs").setView(msg).setCancelable(false).show();
        Importer.importFiles(this, uris,
                (done, total) -> msg.setText("Copying song " + (done + 1) + " of " + total
                        + "…"),
                (added, failed) -> {
                    if (progressDialog != null) progressDialog.dismiss();
                    refresh();
                    if (added.isEmpty()) {
                        toast("No songs were imported");
                        return;
                    }
                    if (failed > 0) toast(failed + " files couldn't be imported");
                    offerPlaylist(added);
                });
    }

    private void offerPlaylist(List<Library.Song> added) {
        String date = new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date());
        new AlertDialog.Builder(this)
                .setTitle(added.size() + (added.size() == 1 ? " song added" : " songs added"))
                .setMessage("Make a playlist from these songs?")
                .setNegativeButton("No", null)
                .setPositiveButton("Make playlist", (d, w) -> askName("Playlist name", "Imported " + date, n -> {
                    Library lib = Library.get(this);
                    Library.Playlist p = lib.newPlaylist(n);
                    for (Library.Song s : added) p.songIds.add(s.id);
                    lib.save();
                    refresh();
                    openPlaylist(p);
                })).show();
    }

    private void importDevice() {
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{perm}, REQ_PERM);
            return;
        }
        toast("Looking for music on your phone…");
        Importer.scanDevice(this, (added, failed) -> {
            refresh();
            if (added.isEmpty()) toast("No new songs found on this phone");
            else offerPlaylist(added);
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) importDevice();
            else toast("Echo needs permission to see the music on your phone");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_FILES) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            importFiles(uris);
        } else if (req == REQ_WEB) {
            String link = data.getStringExtra("link");
            if (link != null) YouTube.askLink(this, link);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
        if (intent.getBooleanExtra("update", false)) {
            intent.removeExtra("update");
            Updater.check(this);
        }
    }

    /** Something was shared to Echo: a YouTube link or audio files. */
    @SuppressWarnings("deprecation")
    private void handleShare(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();
        if (type == null) return;
        if (Intent.ACTION_SEND.equals(action) && type.startsWith("text/")) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && Drive.folderId(text) != null && text.contains("drive.google.com")) {
                int at = text.indexOf("http");
                Drive.askLink(this, at >= 0 ? text.substring(at).split("\\s")[0] : text);
            } else if (text != null && YouTube.listId(text) != null) {
                String link = text;
                int at = text.indexOf("http");
                if (at >= 0) link = text.substring(at).split("\\s")[0];
                YouTube.askLink(this, link);
            } else if (text != null) {
                toast("That link isn't a YouTube playlist or Drive folder");
            }
        } else if (type.startsWith("audio/")) {
            List<Uri> uris = new ArrayList<>();
            if (Intent.ACTION_SEND.equals(action)) {
                Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) uris.add(u);
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null) uris.addAll(list);
            }
            importFiles(uris);
        }
        intent.setAction(null);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (mini != null) mini.release();
        super.onDestroy();
    }
}
