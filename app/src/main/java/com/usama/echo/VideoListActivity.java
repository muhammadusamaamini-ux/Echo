package com.usama.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The videos in one folder, with thumbnails. */
public class VideoListActivity extends Activity {
    private List<Videos.Video> videos = new ArrayList<>();
    private ListGrid grid;
    private BaseAdapter adapter;
    private TextView status;
    private String folder;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        folder = getIntent().getStringExtra("folder");
        LinearLayout root = Ui.column(this);
        LinearLayout bar = Ui.topBar(this, folder == null ? "Videos" : folder, R.drawable.ic_back);
        bar.addView(Sorter.button(this, "v:" + (folder == null ? "*" : folder), Sorter.VIDEO_CODES, Sorter.VIDEO_LABELS, "newest", () -> {
            videos = Sorter.videos(this, "v:" + (folder == null ? "*" : folder), videos); // this folder's own sort
            adapter.notifyDataSetChanged();
        }));
        bar.addView(ListGrid.toggleButton(this, () -> grid.apply()));
        root.addView(bar);
        status = Ui.text(this, "Loading…", 14, Ui.SUB, false);
        status.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 8));
        root.addView(status);

        ListGrid list = new ListGrid(this);
        grid = list;
        adapter = new BaseAdapter() {
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) { return ListGrid.isGrid(VideoListActivity.this) ? 1 : 0; }
            @Override public int getCount() { return videos.size(); }
            @Override public Object getItem(int i) { return videos.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                Videos.Video v = videos.get(i);
                if (ListGrid.isGrid(VideoListActivity.this)) return tile(v, i);
                LinearLayout row = Ui.row(VideoListActivity.this);
                row.setPadding(Ui.dp(VideoListActivity.this, 14), Ui.dp(VideoListActivity.this, 8),
                        Ui.dp(VideoListActivity.this, 4), Ui.dp(VideoListActivity.this, 8));
                FrameLayout thumbBox = new FrameLayout(VideoListActivity.this);
                ImageView thumb = new ImageView(VideoListActivity.this);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(Ui.gradFor(v.title, Ui.dp(VideoListActivity.this, 10)));
                thumb.setClipToOutline(true);
                thumbBox.addView(thumb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                TextView dur = Ui.text(VideoListActivity.this, Ui.time(v.duration), 11, Ui.TEXT, true);
                dur.setBackground(Ui.round(0xAA000000, Ui.dp(VideoListActivity.this, 4)));
                dur.setPadding(Ui.dp(VideoListActivity.this, 4), 0, Ui.dp(VideoListActivity.this, 4), 0);
                FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.END | android.view.Gravity.BOTTOM);
                dlp.setMargins(0, 0, Ui.dp(VideoListActivity.this, 4), Ui.dp(VideoListActivity.this, 4));
                thumbBox.addView(dur, dlp);
                row.addView(thumbBox, new LinearLayout.LayoutParams(Ui.dp(VideoListActivity.this, 128),
                        Ui.dp(VideoListActivity.this, 72)));
                Videos.thumb(VideoListActivity.this, v, thumb);
                LinearLayout col = Ui.column(VideoListActivity.this);
                col.setPadding(Ui.dp(VideoListActivity.this, 12), 0, Ui.dp(VideoListActivity.this, 4), 0);
                TextView t = Ui.text(VideoListActivity.this, v.title, 15, Ui.TEXT, false);
                t.setMaxLines(2);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.addView(t);
                col.addView(Ui.line(VideoListActivity.this, v.info(), 12, Ui.SUB, false));
                row.addView(col, Ui.weight());
                ImageButton more = Ui.icon(VideoListActivity.this, R.drawable.ic_more, 40, Ui.SUB);
                more.setOnClickListener(x -> menu(x, i));
                row.addView(more);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> VideoPlayerActivity.play(this, videos, pos));
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        load();
    }

    /** A tile (grid view) for a video: thumbnail, length, name. */
    private View tile(Videos.Video v, int i) {
        LinearLayout c = Ui.column(this);
        c.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 4));
        c.setBackground(Ui.glass(this, 14));
        FrameLayout box = new FrameLayout(this);
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(Ui.gradFor(v.title, Ui.dp(this, 10)));
        thumb.setClipToOutline(true);
        box.addView(thumb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView dur = Ui.text(this, Ui.time(v.duration), 11, Ui.TEXT, true);
        dur.setBackground(Ui.round(0xAA000000, Ui.dp(this, 4)));
        dur.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.END | android.view.Gravity.BOTTOM);
        dlp.setMargins(0, 0, Ui.dp(this, 4), Ui.dp(this, 4));
        box.addView(dur, dlp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 92));
        blp.rightMargin = Ui.dp(this, 4);
        c.addView(box, blp);
        Videos.thumb(this, v, thumb);
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 4), 0, 0);
        TextView t = Ui.text(this, v.title, 13, Ui.TEXT, false);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t, Ui.weight());
        ImageButton more = Ui.icon(this, R.drawable.ic_more, 32, Ui.SUB);
        more.setOnClickListener(x -> menu(x, i));
        row.addView(more);
        c.addView(row);
        return c;
    }

    private void load() {
        new Thread(() -> {
            List<Videos.Folder> all = Videos.scan(this);
            List<Videos.Video> found = new ArrayList<>();
            for (Videos.Folder f : all) if (folder == null || f.name.equals(folder)) found.addAll(f.videos);
            runOnUiThread(() -> {
                videos = Sorter.videos(this, "v:" + (folder == null ? "*" : folder), found);
                adapter.notifyDataSetChanged();
                status.setText(found.size() + (found.size() == 1 ? " video" : " videos"));
            });
        }).start();
    }

    private void menu(View anchor, int i) {
        Videos.Video v = videos.get(i);
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Play");
        m.getMenu().add(0, 2, 1, "Edit video");
        m.getMenu().add(0, 3, 2, "Share");
        m.getMenu().add(0, 4, 3, "Details");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: VideoPlayerActivity.play(this, videos, i); break;
                case 2: startActivity(new Intent(this, EditorActivity.class).setData(Uri.parse(v.uri))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); break;
                case 3:
                    Intent s = new Intent(Intent.ACTION_SEND).setType("video/*")
                            .putExtra(Intent.EXTRA_STREAM, Uri.parse(v.uri))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(s, "Share video"));
                    break;
                case 4:
                    new AlertDialog.Builder(this).setTitle(v.title)
                            .setMessage("Folder: " + v.folder + "\nLength: " + Ui.time(v.duration)
                                    + "\nSize: " + v.width + " × " + v.height
                                    + String.format(java.util.Locale.US, "\nFile size: %.1f MB", v.size / 1048576.0))
                            .setPositiveButton("OK", null).show();
                    break;
            }
            return true;
        });
        m.show();
    }
}
