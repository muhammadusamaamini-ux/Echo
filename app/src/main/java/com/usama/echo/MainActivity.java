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
    private static final int REQ_FILES = 1, REQ_PERM = 2, REQ_WEB = 3, REQ_NOTIFY = 4, REQ_VPERM = 6, REQ_VIDEO_FILE = 7;

    private static final int P_HOME = 0, P_SONGS = 1, P_FILTER = 2, P_SEARCH = 3, P_LIBRARY = 4, P_VIDEOS = 5;
    private final View[] pages = new View[6];
    private final TextView[] chips = new TextView[5];
    private List<Videos.Folder> videoFolders = new ArrayList<>();
    private BaseAdapter videoAdapter;
    private TextView videoStatus;
    private final LinearLayout[] navItems = new LinearLayout[3];
    private int nav, chip;
    private TextView greeting;
    private android.widget.HorizontalScrollView chipScroll;
    private LinearLayout homeBox;
    private List<Library.Playlist> filtered = new ArrayList<>();
    private BaseAdapter filterAdapter;
    private TextView filterEmpty;
    private final List<ListGrid> grids = new ArrayList<>();
    private ImageButton viewToggle;
    private SongAdapter songAdapter, searchAdapter;
    private BaseAdapter listAdapter;
    private List<Library.Playlist> playlists = new ArrayList<>();
    private EditText searchBox;
    private TextView searchHint;
    private MiniPlayer mini;
    private AlertDialog progressDialog;
    private Selection songSel, searchSel;
    private static final int A_ADD = 1, A_QUEUE = 2, A_PLAY = 3, A_REMOVE = 4;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        // Header: greeting, + and ⋮
        LinearLayout head = Ui.row(this);
        head.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 4));
        android.widget.ImageView avatar = new android.widget.ImageView(this);
        avatar.setImageResource(R.drawable.ic_music);
        avatar.setColorFilter(Ui.TEXT);
        int ap = Ui.dp(this, 9);
        avatar.setPadding(ap, ap, ap, ap);
        avatar.setBackground(Ui.accentGrad(Ui.dp(this, 21)));
        head.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        greeting = Ui.line(this, greetingText(), 26, Ui.TEXT, true);
        greeting.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        head.addView(greeting, Ui.weight());
        Ui.gradientText(greeting);
        viewToggle = ListGrid.toggleButton(this, () -> {
            for (ListGrid g : grids) g.apply();
        });
        head.addView(viewToggle);
        ImageButton add = Ui.icon(this, R.drawable.ic_add, 44, Ui.TEXT);
        add.setOnClickListener(this::addMenu);
        head.addView(add);
        ImageButton menu = Ui.icon(this, R.drawable.ic_more, 44, Ui.TEXT);
        menu.setOnClickListener(this::mainMenu);
        head.addView(menu);
        root.addView(head);

        // Filter chips (Home)
        chipScroll = new android.widget.HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = Ui.row(this);
        chipRow.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 6));
        String[] labels = {"Playlists", "Videos", "Songs", "YouTube", "Drive"};
        for (int i = 0; i < 5; i++) {
            TextView t = Ui.text(this, labels[i], 14, Ui.TEXT, true);
            t.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
            final int idx = i;
            t.setOnClickListener(v -> showChip(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 8);
            chipRow.addView(t, lp);
            chips[i] = t;
        }
        chipScroll.addView(chipRow);
        root.addView(chipScroll);

        FrameLayout content = new FrameLayout(this);
        pages[P_HOME] = buildHomePage();
        pages[P_SONGS] = buildSongsPage();
        pages[P_FILTER] = buildFilterPage();
        pages[P_SEARCH] = buildSearchPage();
        pages[P_LIBRARY] = buildPlaylistsPage();
        pages[P_VIDEOS] = buildVideosPage();
        for (View p : pages) content.addView(p, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        mini = new MiniPlayer(this, () -> {
            String id = mini == null ? null : mini.currentId();
            if (songAdapter != null) songAdapter.setCurrent(id);
            if (searchAdapter != null) searchAdapter.setCurrent(id);
        });
        root.addView(mini.view);
        root.addView(buildNav());

        setContentView(root);
        showNav(0);
        refresh();
        handleShare(getIntent());
        setupUpdates(getIntent());
        Drive.schedule(this);
    }

    /** Checks the imported Drive folders for changes whenever Echo is opened. */
    private void syncDrive() {
        if (System.currentTimeMillis() - Drive.lastSync(this) < 2 * 60 * 1000L) return;
        Drive.syncAll(this, r -> {
            if (isFinishing()) return;
            refresh();
            if (r.changed()) toast("Google Drive: " + r.summary(""));
        });
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
        m.getMenu().add(0, 3, 0, "Hidden folders");
        m.getMenu().add(0, 1, 1, "Check for updates");
        m.getMenu().add(0, 2, 2, "About & features (build " + Updater.installedBuild(this) + ")");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 3) {
                Folders.showHidden(this, this::refresh);
            } else if (item.getItemId() == 1) {
                Updater.check(this);
            } else {
                About.show(this);
            }
            return true;
        });
        m.show();
    }

    // ---------- pages ----------

    private Selection selectionFor(SongAdapter ad) {
        return new Selection(this, ad, "Add to", A_ADD, new Selection.Actions() {
            @Override
            public void fillMenu(android.view.Menu m) {
                m.add(0, A_PLAY, 0, "Play");
                m.add(0, A_QUEUE, 1, "Add to queue");
                m.add(0, A_ADD, 2, "Add to folder…");
                m.add(0, A_REMOVE, 3, "Remove from Echo");
            }

            @Override
            public boolean onAction(int id, List<Library.Song> list) {
                switch (id) {
                    case A_PLAY: Hub.play(MainActivity.this, list, 0, false); return true;
                    case A_QUEUE: Selection.queue(MainActivity.this, list); return true;
                    case A_ADD:
                        Selection.addToFolder(MainActivity.this, list, null, false, () -> {
                            exitSelections();
                            refresh();
                        });
                        return false;
                    case A_REMOVE:
                        Selection.removeFromEcho(MainActivity.this, list, () -> {
                            exitSelections();
                            refresh();
                        });
                        return false;
                }
                return false;
            }
        });
    }

    private void exitSelections() {
        if (songSel != null) songSel.exit();
        if (searchSel != null) searchSel.exit();
    }

    private View buildSongsPage() {
        LinearLayout page = Ui.column(this);
        FrameLayout f = new FrameLayout(this);
        ListGrid list = new ListGrid(this);
        grids.add(list);
        songAdapter = new SongAdapter(this, this::songMenu);
        list.setAdapter(songAdapter);
        songSel = selectionFor(songAdapter);
        page.addView(songSel.bar);
        page.addView(f, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (songSel.active()) songSel.toggle(pos);
            else Hub.play(this, songAdapter.items(), pos, false);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            songSel.toggle(pos);
            return true;
        });
        TextView empty = Ui.text(this, "No songs yet.\n\nTap \"+ Import\" to play music from Google Drive, "
                + "your phone, or a YouTube playlist.", 16, Ui.SUB, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), Ui.dp(this, 60));
        f.addView(list);
        f.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);
        return page;
    }

    private View buildPlaylistsPage() {
        ListGrid list = new ListGrid(this);
        grids.add(list);
        listAdapter = new BaseAdapter() {
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) { return ListGrid.isGrid(MainActivity.this) ? 1 : 0; }
            @Override public int getCount() { return playlists.size() + 1; }
            @Override public Object getItem(int i) { return i == 0 ? null : playlists.get(i - 1); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                boolean g = ListGrid.isGrid(MainActivity.this);
                if (i == 0) return g ? Folders.addTile(MainActivity.this, "New folder")
                        : Folders.addRow(MainActivity.this, "New folder");
                Library.Playlist pl = playlists.get(i - 1);
                return g ? Folders.tile(MainActivity.this, pl, MainActivity.this::refresh)
                        : Folders.row(MainActivity.this, pl, MainActivity.this::refresh);
            }
        };
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((parent, v, pos, id) -> {
            if (pos == 0) Folders.newFolder(this, null, f -> { refresh(); openPlaylist(f); });
            else openPlaylist(playlists.get(pos - 1));
        });
        list.setOnItemLongClickListener((parent, v, pos, id) -> {
            if (pos > 0) Folders.menu(this, v, playlists.get(pos - 1), this::refresh);
            return true;
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
        searchSel = selectionFor(searchAdapter);
        page.addView(searchSel.bar, 0);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (searchSel.active()) searchSel.toggle(pos);
            else Hub.play(this, searchAdapter.items(), pos, false);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            searchSel.toggle(pos);
            return true;
        });
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

    private String greetingText() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (h >= 4 && h < 12) return "Good morning";
        if (h >= 12 && h < 17) return "Good afternoon";
        return "Good evening";
    }

    // ---------- bottom bar: Home, Search, Library ----------

    private View buildNav() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(0xEB0B0B1E);
        bar.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        String[] labels = {"Home", "Search", "Library"};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_search, R.drawable.ic_library};
        for (int i = 0; i < 3; i++) {
            LinearLayout item = Ui.column(this);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageResource(icons[i]);
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
            TextView t = Ui.text(this, labels[i], 12, Ui.DIM, false);
            t.setGravity(Gravity.CENTER);
            item.addView(t);
            final int idx = i;
            item.setOnClickListener(v -> showNav(idx));
            Ui.ripple(item, 0, Ui.dp(this, 16));
            bar.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            navItems[i] = item;
        }
        return bar;
    }

    private void showNav(int i) {
        nav = i;
        for (int k = 0; k < 3; k++) {
            int c = k == i ? Ui.GLOW : Ui.DIM;
            ((android.widget.ImageView) navItems[k].getChildAt(0)).setColorFilter(c);
            ((TextView) navItems[k].getChildAt(1)).setTextColor(c);
        }
        chipScroll.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        if (i == 0) showChip(chip);
        else showPage(i == 1 ? P_SEARCH : P_LIBRARY);
    }

    private void showChip(int c) {
        chip = c;
        for (int k = 0; k < 5; k++) {
            boolean on = k == c;
            chips[k].setTextColor(on ? Ui.TEXT : Ui.SUB);
            chips[k].setBackground(on ? Ui.accentGrad(Ui.dp(this, 20)) : Ui.glass(this, 20));
        }
        if (c == 0) showPage(P_HOME);
        else if (c == 1) {
            showPage(P_VIDEOS);
            loadVideos();
        } else if (c == 2) showPage(P_SONGS);
        else {
            refreshFilter();
            showPage(P_FILTER);
        }
    }

    private void showPage(int p) {
        exitSelections();
        for (int k = 0; k < pages.length; k++) pages[k].setVisibility(k == p ? View.VISIBLE : View.GONE);
        // list / tile switch for the screens that are lists
        if (viewToggle != null) {
            viewToggle.setVisibility(p == P_HOME || p == P_SEARCH ? View.GONE : View.VISIBLE);
            ListGrid.syncButton(viewToggle, this);
        }
    }

    // ---------- Home: Recently played + Your folders ----------

    private View buildHomePage() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        homeBox = Ui.column(this);
        homeBox.setPadding(0, 0, 0, Ui.dp(this, 16));
        sv.addView(homeBox);
        return sv;
    }

    private TextView section(String title) {
        TextView t = Ui.text(this, title, 20, Ui.TEXT, true);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 10));
        return t;
    }

    private void refreshHome() {
        homeBox.removeAllViews();
        Library lib = Library.get(this);
        List<Library.Playlist> recent = lib.recentFolders(4);
        List<Library.Playlist> top = lib.visibleChildren(null);
        if (top.isEmpty() && recent.isEmpty()) {
            TextView empty = Ui.text(this, "Welcome to Echo.\n\nTap + to add music from a Google Drive folder, "
                    + "a YouTube playlist, or your phone.", 16, Ui.SUB, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(Ui.dp(this, 32), Ui.dp(this, 80), Ui.dp(this, 32), 0);
            homeBox.addView(empty);
            return;
        }
        if (!recent.isEmpty()) {
            homeBox.addView(section("Recently played"));
            LinearLayout grid = Ui.column(this);
            grid.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
            for (int i = 0; i < recent.size(); i += 2) {
                LinearLayout row = Ui.row(this);
                row.addView(recentTile(recent.get(i)), tileLp());
                if (i + 1 < recent.size()) row.addView(recentTile(recent.get(i + 1)), tileLp());
                else row.addView(new View(this), tileLp());
                grid.addView(row);
            }
            homeBox.addView(grid);
        }
        homeBox.addView(section("Your folders"));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout cards = Ui.row(this);
        cards.setGravity(Gravity.TOP);
        cards.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        for (Library.Playlist p : top) cards.addView(folderCard(p));
        cards.addView(newFolderCard());
        hs.addView(cards);
        homeBox.addView(hs);
    }

    private LinearLayout.LayoutParams tileLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 60), 1f);
        lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        return lp;
    }

    private android.widget.ImageView gradIcon(Library.Playlist p, int sizeDp, int iconDp, float radiusDp) {
        android.widget.ImageView icon = new android.widget.ImageView(this);
        boolean folder = !Library.get(this).children(p.id).isEmpty() || p.songIds.isEmpty();
        icon.setImageResource(p.youtube ? R.drawable.ic_play : folder ? R.drawable.ic_folder : R.drawable.ic_music);
        icon.setColorFilter(Ui.TEXT);
        icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = Ui.dp(this, (sizeDp - iconDp) / 2f);
        icon.setPadding(pad, pad, pad, pad);
        icon.setBackground(p.youtube ? Ui.ytGrad(Ui.dp(this, radiusDp)) : Ui.gradFor(p.name, Ui.dp(this, radiusDp)));
        return icon;
    }

    private View recentTile(Library.Playlist p) {
        LinearLayout t = Ui.row(this);
        t.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        Ui.ripple(t, 0, Ui.dp(this, 14));
        t.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), Ui.glass(this, 14), null));
        t.addView(gradIcon(p, 42, 22, 11), new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        TextView n = Ui.line(this, p.name, 14, Ui.TEXT, true);
        n.setPadding(Ui.dp(this, 10), 0, 0, 0);
        t.addView(n, Ui.weight());
        t.setOnClickListener(v -> Folders.open(this, p));
        t.setOnLongClickListener(v -> {
            Folders.menu(this, v, p, this::refresh);
            return true;
        });
        return t;
    }

    private View folderCard(Library.Playlist p) {
        LinearLayout c = Ui.column(this);
        int w = Ui.dp(this, 150);
        c.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 12));
        c.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), Ui.glass(this, 18), null));
        c.addView(gradIcon(p, 130, 54, 14), new LinearLayout.LayoutParams(w - Ui.dp(this, 20), Ui.dp(this, 112)));
        TextView n = Ui.line(this, p.name, 15, Ui.TEXT, true);
        n.setPadding(0, Ui.dp(this, 10), 0, 0);
        c.addView(n);
        int sub = Library.get(this).visibleChildren(p.id).size();
        c.addView(Ui.line(this, p.subtitle(sub) + (p.driveFolder != null ? "  ·  Drive" : ""), 12, Ui.SUB, false));
        c.setOnClickListener(v -> Folders.open(this, p));
        c.setOnLongClickListener(v -> {
            Folders.menu(this, v, p, this::refresh);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        c.setLayoutParams(lp);
        return c;
    }

    private View newFolderCard() {
        LinearLayout c = Ui.column(this);
        c.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = Ui.round(0x0DFFFFFF, Ui.dp(this, 18));
        g.setStroke(Ui.dp(this, 1), 0x40FFFFFF, Ui.dp(this, 6), Ui.dp(this, 4));
        c.setBackground(g);
        android.widget.ImageView plus = new android.widget.ImageView(this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setColorFilter(Ui.SUB);
        c.addView(plus, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        TextView t = Ui.text(this, "New folder", 13, Ui.SUB, false);
        t.setPadding(0, Ui.dp(this, 6), 0, 0);
        c.addView(t);
        c.setOnClickListener(v -> Folders.newFolder(this, null, f -> { refresh(); openPlaylist(f); }));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 190));
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        c.setLayoutParams(lp);
        return c;
    }

    // ---------- YouTube / Drive chips ----------

    private View buildFilterPage() {
        FrameLayout f = new FrameLayout(this);
        ListGrid list = new ListGrid(this);
        grids.add(list);
        filterAdapter = new BaseAdapter() {
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) { return ListGrid.isGrid(MainActivity.this) ? 1 : 0; }
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int i) { return filtered.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                return ListGrid.isGrid(MainActivity.this)
                        ? Folders.tile(MainActivity.this, filtered.get(i), MainActivity.this::refresh)
                        : Folders.row(MainActivity.this, filtered.get(i), MainActivity.this::refresh);
            }
        };
        list.setAdapter(filterAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> Folders.open(this, filtered.get(pos)));
        filterEmpty = Ui.text(this, "", 15, Ui.SUB, false);
        filterEmpty.setGravity(Gravity.CENTER);
        filterEmpty.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), Ui.dp(this, 60));
        f.addView(list);
        f.addView(filterEmpty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(filterEmpty);
        return f;
    }

    private void refreshFilter() {
        Library lib = Library.get(this);
        filtered = new ArrayList<>();
        for (Library.Playlist p : lib.playlists) {
            if (lib.isHidden(p)) continue;
            if (chip == 3 && p.youtube) filtered.add(p);
            if (chip == 4 && p.driveFolder != null && (p.driveRoot == null || p.driveRoot.equals(p.driveFolder))) {
                filtered.add(p);
            }
        }
        java.util.Collections.sort(filtered, (x, y) -> x.name.compareToIgnoreCase(y.name));
        filterEmpty.setText(chip == 3 ? "No YouTube playlists yet.\n\nTap + → Import music → YouTube playlist."
                : "No Google Drive folders yet.\n\nTap + → Import music → Google Drive folder.");
        filterAdapter.notifyDataSetChanged();
    }

    // ---------- Videos ----------

    private View buildVideosPage() {
        LinearLayout page = Ui.column(this);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = Ui.row(this);
        actions.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        TextView edit = Ui.pill(this, "✂  Edit a video", true);
        edit.setOnClickListener(v -> startActivity(new Intent(this, EditorActivity.class)));
        TextView audioEd = Ui.pill(this, "🎵  Edit audio", false);
        audioEd.setOnClickListener(v -> startActivity(new Intent(this, AudioEditorActivity.class)));
        TextView open = Ui.pill(this, "Open file", false);
        open.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
            try {
                startActivityForResult(i, REQ_VIDEO_FILE);
            } catch (Exception e) {
                toast("No file picker found");
            }
        });
        TextView stream = Ui.pill(this, "Network stream", false);
        stream.setOnClickListener(v -> askStream());
        actions.addView(edit);
        actions.addView(audioEd, chipMargin());
        actions.addView(open, chipMargin());
        actions.addView(stream, chipMargin());
        hs.addView(actions);
        page.addView(hs);
        videoStatus = Ui.text(this, "", 14, Ui.SUB, false);
        videoStatus.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 4));
        page.addView(videoStatus);
        ListGrid list = new ListGrid(this);
        grids.add(list);
        videoAdapter = new BaseAdapter() {
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) { return ListGrid.isGrid(MainActivity.this) ? 1 : 0; }
            @Override public int getCount() { return videoFolders.size(); }
            @Override public Object getItem(int i) { return videoFolders.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                Videos.Folder f = videoFolders.get(i);
                if (ListGrid.isGrid(MainActivity.this)) {
                    LinearLayout c = Ui.column(MainActivity.this);
                    c.setPadding(Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 8),
                            Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 8));
                    c.setBackground(Ui.glass(MainActivity.this, 16));
                    android.widget.ImageView th = new android.widget.ImageView(MainActivity.this);
                    th.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                    th.setBackground(Ui.gradFor(f.name, Ui.dp(MainActivity.this, 12)));
                    th.setClipToOutline(true);
                    c.addView(th, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                            Ui.dp(MainActivity.this, 96)));
                    if (!f.videos.isEmpty()) Videos.thumb(MainActivity.this, f.videos.get(0), th);
                    TextView n = Ui.line(MainActivity.this, f.name, 14, Ui.TEXT, true);
                    n.setPadding(0, Ui.dp(MainActivity.this, 6), 0, 0);
                    c.addView(n);
                    c.addView(Ui.line(MainActivity.this, f.videos.size() + (f.videos.size() == 1 ? " video" : " videos"),
                            11, Ui.SUB, false));
                    return c;
                }
                LinearLayout row = Ui.row(MainActivity.this);
                row.setPadding(Ui.dp(MainActivity.this, 16), Ui.dp(MainActivity.this, 8),
                        Ui.dp(MainActivity.this, 16), Ui.dp(MainActivity.this, 8));
                android.widget.ImageView thumb = new android.widget.ImageView(MainActivity.this);
                thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(Ui.gradFor(f.name, Ui.dp(MainActivity.this, 12)));
                thumb.setClipToOutline(true);
                row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 96), Ui.dp(MainActivity.this, 60)));
                if (!f.videos.isEmpty()) Videos.thumb(MainActivity.this, f.videos.get(0), thumb);
                LinearLayout col = Ui.column(MainActivity.this);
                col.setPadding(Ui.dp(MainActivity.this, 14), 0, 0, 0);
                col.addView(Ui.line(MainActivity.this, f.name, 16, Ui.TEXT, true));
                col.addView(Ui.line(MainActivity.this, f.videos.size() + (f.videos.size() == 1 ? " video" : " videos"),
                        13, Ui.SUB, false));
                row.addView(col, Ui.weight());
                return row;
            }
        };
        list.setAdapter(videoAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> startActivity(new Intent(this, VideoListActivity.class)
                .putExtra("folder", videoFolders.get(pos).name)));
        page.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private LinearLayout.LayoutParams chipMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        return lp;
    }

    private void loadVideos() {
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            videoStatus.setText("Echo needs permission to show the videos on your phone.");
            requestPermissions(new String[]{perm}, REQ_VPERM);
            return;
        }
        videoStatus.setText("Looking for videos…");
        new Thread(() -> {
            List<Videos.Folder> found = Videos.scan(this);
            runOnUiThread(() -> {
                videoFolders = found;
                videoAdapter.notifyDataSetChanged();
                int n = 0;
                for (Videos.Folder f : found) n += f.videos.size();
                videoStatus.setText(n == 0 ? "No videos found on this phone." : n + " videos in " + found.size()
                        + (found.size() == 1 ? " folder" : " folders"));
            });
        }).start();
    }

    private void askStream() {
        final EditText e = new EditText(this);
        e.setHint("https://…  (video or audio link, .m3u8, .mp4 …)");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(e);
        new AlertDialog.Builder(this).setTitle("Open network stream").setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Play", (d, w) -> {
                    String u = e.getText().toString().trim();
                    if (u.isEmpty()) return;
                    if (!u.contains("://")) u = "https://" + u;
                    startActivity(new Intent(this, VideoPlayerActivity.class).setData(Uri.parse(u)));
                }).show();
    }

    private void editAudio(Library.Song s) {
        startActivity(new Intent(this, AudioEditorActivity.class).setData(Uri.parse(s.uri))
                .putExtra("title", s.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    private void addMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Import music");
        m.getMenu().add(0, 2, 1, "New folder");
        m.getMenu().add(0, 3, 2, "Download from YouTube link");
        m.getMenu().add(0, 4, 3, "Audio editor (cut, trim, join…)");
        m.getMenu().add(0, 5, 4, "Video editor");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showImport();
            else if (item.getItemId() == 4) startActivity(new Intent(this, AudioEditorActivity.class));
            else if (item.getItemId() == 5) startActivity(new Intent(this, EditorActivity.class));
            else if (item.getItemId() == 3) Saver.askLink(this, null);
            else Folders.newFolder(this, null, f -> { refresh(); openPlaylist(f); });
            return true;
        });
        m.show();
    }

    void refresh() {
        Library lib = Library.get(this);
        songAdapter.setItems(lib.sortedSongs());
        playlists = lib.visibleChildren(null);
        listAdapter.notifyDataSetChanged();
        if (searchBox != null) runSearch();
        refreshHome();
        if (chip >= 3) refreshFilter();
        if (greeting != null && !greeting.getText().toString().equals(greetingText())) {
            greeting.setText(greetingText());
            Ui.gradientText(greeting);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (ListGrid g : grids) g.apply(); // the view may have been switched on another screen
        if (viewToggle != null) ListGrid.syncButton(viewToggle, this);
        refresh();
        syncDrive();
    }

    @Override
    public void onBackPressed() {
        if ((songSel != null && songSel.active()) || (searchSel != null && searchSel.active())) exitSelections();
        else if (nav != 0) showNav(0);
        else if (chip != 0) showChip(0);
        else super.onBackPressed();
    }

    // ---------- menus ----------

    private void songMenu(View anchor, Library.Song s, int pos) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Play next");
        m.getMenu().add(0, 2, 1, "Add to queue");
        m.getMenu().add(0, 3, 2, "Add to folder…");
        m.getMenu().add(0, 4, 3, "Search on Google");
        m.getMenu().add(0, 7, 4, "Edit audio (cut, trim…)");
        m.getMenu().add(0, 5, 5, "Remove from Echo");
        if (Selection.isDrive(s)) m.getMenu().add(0, 6, 5, "Open in Google Drive");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: Hub.playNext(this, s); toast("Plays next"); break;
                case 2: Hub.addToQueue(this, s); toast("Added to queue"); break;
                case 3: addToPlaylist(s); break;
                case 4: startActivityForResult(WebActivity.google(this,
                        s.title + (s.artist == null || s.artist.isEmpty() ? " song" : " " + s.artist)), REQ_WEB); break;
                case 5: Selection.removeFromEcho(this, java.util.Collections.singletonList(s), this::refresh); break;
                case 6: Selection.openInDrive(this, s); break;
                case 7: editAudio(s); break;
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
        if (code == REQ_VPERM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) loadVideos();
            else videoStatus.setText("Without permission Echo can't list your videos. You can still use Open file.");
            return;
        }
        if (code == REQ_PERM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) importDevice();
            else toast("Echo needs permission to see the music on your phone");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_VIDEO_FILE && data.getData() != null) {
            startActivity(new Intent(this, VideoPlayerActivity.class).setData(data.getData())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            return;
        }
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
            } else if (text != null && (YouTube.listId(text) != null || Saver.videoId(text) != null)) {
                String link = text;
                int at = text.indexOf("http");
                if (at >= 0) link = text.substring(at).split("\\s")[0];
                final String l = link;
                String vid = Saver.videoId(link);
                if (YouTube.listId(link) == null) {
                    Saver.start(this, vid);
                } else if (vid == null) {
                    YouTube.askLink(this, link);
                } else {
                    new AlertDialog.Builder(this).setTitle("YouTube link")
                            .setItems(new String[]{"Download this video (audio or video)", "Import the whole playlist"},
                                    (d, which) -> {
                                        if (which == 0) Saver.start(this, vid);
                                        else YouTube.askLink(this, l);
                                    }).show();
                }
            } else if (text != null) {
                toast("That link isn't a YouTube video, playlist or Drive folder");
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
