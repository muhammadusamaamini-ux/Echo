package com.usama.echo;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Photos: folders → a folder's photos (sort, Select for a collage or delete) → the viewer. */
public class PhotosActivity extends Activity {
    private static final int REQ_PERM = 31, REQ_DELETE = 32;
    static final String[] FOLDER_CODES = {"name", "nameDesc", "most", "newest"};
    static final String[] FOLDER_LABELS = {"Name (A–Z)", "Name (Z–A)", "Most photos first", "Newest photos first"};
    static final String[] PHOTO_CODES = {"newest", "oldest", "name", "nameDesc", "biggest", "smallest"};
    static final String[] PHOTO_LABELS = {"Newest first", "Oldest first", "Name (A–Z)", "Name (Z–A)", "Size (largest first)", "Size (smallest first)"};

    private String folder; // null: the list of folders
    private List<Photos.Folder> folders = new ArrayList<>();
    private List<Photos.Photo> photos = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private boolean selecting;
    private GridView grid;
    private BaseAdapter adapter;
    private TextView status;
    private LinearLayout selBar;
    private TextView selCount, selectBtn;
    private List<Photos.Photo> pendingDelete;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        folder = getIntent().getStringExtra("folder");
        LinearLayout root = Ui.column(this);
        root.setBackgroundResource(R.drawable.bg_app);
        LinearLayout bar = Ui.topBar(this, folder == null ? "Photos" : folder, R.drawable.ic_back);
        bar.addView(Sorter.button(this, folder == null ? "pfolders" : "p:" + folder,
                folder == null ? FOLDER_CODES : PHOTO_CODES, folder == null ? FOLDER_LABELS : PHOTO_LABELS,
                folder == null ? "name" : "newest", this::show));
        root.addView(bar);
        // actions
        LinearLayout actions = Ui.row(this);
        actions.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 6));
        TextView collage = Ui.pill(this, "▦ Collage", true);
        collage.setOnClickListener(v -> startActivity(new Intent(this, CollageActivity.class)));
        TextView edit = Ui.pill(this, "✏ Edit a photo", false);
        edit.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 41));
        actions.addView(collage);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        actions.addView(edit, lp);
        if (folder != null) {
            selectBtn = Ui.pill(this, "Select", false);
            selectBtn.setOnClickListener(v -> setSelecting(!selecting));
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp2.leftMargin = Ui.dp(this, 8);
            actions.addView(selectBtn, lp2);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(actions);
        root.addView(hs);
        status = Ui.text(this, "Looking for photos…", 14, Ui.SUB, false);
        status.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 8));
        root.addView(status);

        grid = new GridView(this);
        grid.setNumColumns(folder == null ? 2 : 3);
        int gap = Ui.dp(this, folder == null ? 10 : 3);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 90));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return folder == null ? folders.size() : photos.size(); }
            @Override public Object getItem(int i) { return i; }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                return folder == null ? folderTile(folders.get(i)) : photoTile(photos.get(i));
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            if (folder == null) {
                startActivity(new Intent(this, PhotosActivity.class).putExtra("folder", folders.get(pos).name));
            } else if (selecting) {
                String u = photos.get(pos).uri;
                if (!selected.remove(u)) selected.add(u);
                adapter.notifyDataSetChanged();
                selCount.setText(selected.size() + " selected");
            } else {
                Photos.shown = new ArrayList<>(photos);
                startActivity(new Intent(this, PhotoViewerActivity.class).putExtra("i", pos));
            }
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            if (folder == null) return false;
            if (!selecting) setSelecting(true);
            selected.add(photos.get(pos).uri);
            adapter.notifyDataSetChanged();
            selCount.setText(selected.size() + " selected");
            return true;
        });
        FrameLayout frame = new FrameLayout(this);
        frame.addView(grid);
        // the bar for selected photos
        selBar = Ui.row(this);
        selBar.setBackground(Ui.round(Ui.PANEL, Ui.dp(this, 18)));
        selBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        selCount = Ui.text(this, "0 selected", 14, Ui.TEXT, true);
        selBar.addView(selCount, Ui.weight());
        TextView del = Ui.pill(this, "Delete", false);
        del.setOnClickListener(v -> deleteSelected());
        TextView mk = Ui.pill(this, "▦ Collage", true);
        mk.setOnClickListener(v -> {
            if (selected.size() < 2 || selected.size() > 9) {
                Toast.makeText(this, "Choose 2 to 9 photos for a collage", Toast.LENGTH_SHORT).show();
                return;
            }
            ArrayList<String> uris = new ArrayList<>();
            for (Photos.Photo p : photos) if (selected.contains(p.uri)) uris.add(p.uri);
            setSelecting(false);
            startActivity(new Intent(this, CollageActivity.class).putStringArrayListExtra("uris", uris));
        });
        selBar.addView(del);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp3.leftMargin = Ui.dp(this, 8);
        selBar.addView(mk, lp3);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        fl.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 16));
        selBar.setVisibility(View.GONE);
        frame.addView(selBar, fl);
        root.addView(frame, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        load();
    }

    private void setSelecting(boolean on) {
        selecting = on;
        selected.clear();
        selBar.setVisibility(on ? View.VISIBLE : View.GONE);
        selCount.setText("0 selected");
        if (selectBtn != null) selectBtn.setText(on ? "Done" : "Select");
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onBackPressed() {
        if (selecting) setSelecting(false);
        else super.onBackPressed();
    }

    private void load() {
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Echo needs permission to show the photos on your phone.");
            requestPermissions(new String[]{perm}, REQ_PERM);
            return;
        }
        new Thread(() -> {
            List<Photos.Folder> found = Photos.scan(this);
            runOnUiThread(() -> {
                folders = found;
                if (folder != null) {
                    photos = new ArrayList<>();
                    for (Photos.Folder f : found) if (f.name.equals(folder)) photos.addAll(f.photos);
                }
                show();
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_PERM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) load();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        load(); // photos may have been edited, saved or deleted
    }

    /** Applies this list's own sort. */
    private void show() {
        final Collator col = Collator.getInstance();
        col.setStrength(Collator.PRIMARY);
        if (folder == null) {
            String mode = Sorter.get(this, "pfolders", "name");
            Collections.sort(folders, (x, y) -> {
                switch (mode) {
                    case "nameDesc": return col.compare(y.name, x.name);
                    case "most": return Integer.compare(y.photos.size(), x.photos.size());
                    case "newest": return Long.compare(y.photos.isEmpty() ? 0 : y.photos.get(0).date, x.photos.isEmpty() ? 0 : x.photos.get(0).date);
                    default: return col.compare(x.name, y.name);
                }
            });
            int n = 0;
            for (Photos.Folder f : folders) n += f.photos.size();
            status.setText(n == 0 ? "No photos found on this phone." : n + " photos in " + folders.size() + (folders.size() == 1 ? " folder" : " folders"));
        } else {
            String mode = Sorter.get(this, "p:" + folder, "newest");
            Collections.sort(photos, (x, y) -> {
                switch (mode) {
                    case "oldest": return Long.compare(x.date, y.date);
                    case "name": return col.compare(x.name, y.name);
                    case "nameDesc": return col.compare(y.name, x.name);
                    case "biggest": return Long.compare(y.size, x.size);
                    case "smallest": return Long.compare(x.size, y.size);
                    default: return Long.compare(y.date, x.date);
                }
            });
            status.setText(photos.size() + (photos.size() == 1 ? " photo" : " photos") + "  ·  long-press to select");
        }
        adapter.notifyDataSetChanged();
    }

    private View folderTile(Photos.Folder f) {
        LinearLayout c = Ui.column(this);
        FrameLayout box = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 14)));
        iv.setClipToOutline(true);
        int h = (getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 40)) / 2;
        box.addView(iv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h));
        if (!f.photos.isEmpty()) Photos.thumb(this, f.photos.get(0), iv);
        c.addView(box);
        c.addView(Ui.line(this, f.name, 15, Ui.TEXT, true));
        c.addView(Ui.line(this, f.photos.size() + (f.photos.size() == 1 ? " photo" : " photos"), 13, Ui.SUB, false));
        c.setPadding(0, 0, 0, Ui.dp(this, 8));
        return c;
    }

    private View photoTile(Photos.Photo p) {
        FrameLayout box = new FrameLayout(this);
        int s = (getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 26)) / 3;
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(Ui.SURFACE);
        box.addView(iv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, s));
        Photos.thumb(this, p, iv);
        if (selecting) {
            boolean on = selected.contains(p.uri);
            TextView tick = Ui.text(this, on ? "✓" : "", 14, Color.BLACK, true);
            tick.setGravity(Gravity.CENTER);
            tick.setBackground(Ui.round(on ? Ui.ACCENT : 0x66000000, Ui.dp(this, 13)));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26));
            lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), 0, 0);
            box.addView(tick, lp);
            if (on) iv.setAlpha(0.7f);
        }
        return box;
    }

    // ---------- deleting ----------
    private void deleteSelected() {
        if (selected.isEmpty()) return;
        List<Photos.Photo> list = new ArrayList<>();
        for (Photos.Photo p : photos) if (selected.contains(p.uri)) list.add(p);
        delete(list);
    }

    private void delete(List<Photos.Photo> list) {
        List<Uri> uris = new ArrayList<>();
        for (Photos.Photo p : list) uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, p.id));
        if (Build.VERSION.SDK_INT >= 30) { // Android asks you to confirm
            try {
                PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), uris, true);
                pendingDelete = list;
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't delete", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        new android.app.AlertDialog.Builder(this).setTitle(list.size() == 1 ? "Delete this photo?" : "Delete " + list.size() + " photos?")
                .setMessage("They are deleted from your phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    int n = 0;
                    for (Uri u : uris) try { n += getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
                    Toast.makeText(this, n + " deleted", Toast.LENGTH_SHORT).show();
                    setSelecting(false);
                    load();
                }).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_DELETE && res == RESULT_OK) {
            Toast.makeText(this, (pendingDelete == null ? 0 : pendingDelete.size()) + " moved to the bin", Toast.LENGTH_SHORT).show();
            setSelecting(false);
            load();
        } else if (req == 41 && res == RESULT_OK && data != null && data.getData() != null) {
            startActivity(new Intent(this, PhotoEditorActivity.class).setData(data.getData()));
        }
    }
}
