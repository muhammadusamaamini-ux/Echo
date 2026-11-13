package com.usama.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Browse your own Google Drive (private folders too) and pick a folder to add to Echo. */
public class DriveBrowserActivity extends Activity {
    private static final String TOP = "#top", SHARED = "#shared";

    private String account;
    private final List<String[]> stack = new ArrayList<>(); // {folderId, name}
    private List<DriveApi.Item> items = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView titleView, status;
    private TextView importBtn;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int loadToken;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        account = getIntent().getStringExtra("account");
        if (account == null) {
            finish();
            return;
        }
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.topBar(this, "Google Drive", R.drawable.ic_back);
        bar.getChildAt(0).setOnClickListener(v -> onBackPressed());
        titleView = (TextView) bar.getChildAt(1);
        root.addView(bar);
        TextView acc = Ui.line(this, account, 13, Ui.SUB, false);
        acc.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 6));
        root.addView(acc);

        status = Ui.text(this, "", 15, Ui.SUB, false);
        status.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16));
        root.addView(status);

        ListView list = new ListView(this);
        list.setDivider(null);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                DriveApi.Item it = items.get(i);
                LinearLayout row = Ui.row(DriveBrowserActivity.this);
                row.setPadding(Ui.dp(DriveBrowserActivity.this, 16), Ui.dp(DriveBrowserActivity.this, 10),
                        Ui.dp(DriveBrowserActivity.this, 16), Ui.dp(DriveBrowserActivity.this, 10));
                ImageView icon = new ImageView(DriveBrowserActivity.this);
                icon.setImageResource(it.folder ? R.drawable.ic_folder : R.drawable.ic_music);
                icon.setColorFilter(it.folder ? Ui.ACCENT : Ui.SUB);
                row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(DriveBrowserActivity.this, 28),
                        Ui.dp(DriveBrowserActivity.this, 28)));
                TextView t = Ui.line(DriveBrowserActivity.this, it.name, 16, it.folder ? Ui.TEXT : Ui.SUB, false);
                t.setPadding(Ui.dp(DriveBrowserActivity.this, 16), 0, 0, 0);
                row.addView(t, Ui.weight());
                return row;
            }

            @Override
            public boolean isEnabled(int i) {
                return items.get(i).folder;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            DriveApi.Item it = items.get(pos);
            if (!it.folder) return;
            stack.add(new String[]{it.id, it.name});
            load();
        });
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = Ui.row(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 14));
        bottom.setBackgroundColor(0xFF1A1A1A);
        importBtn = Ui.pill(this, "Add this folder to Echo", true);
        importBtn.setOnClickListener(v -> importCurrent());
        bottom.addView(importBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(bottom);
        setContentView(root);

        stack.add(new String[]{TOP, "Google Drive"});
        load();
    }

    private String[] current() {
        return stack.get(stack.size() - 1);
    }

    private void load() {
        final String[] cur = current();
        final int my = ++loadToken;
        titleView.setText(cur[1]);
        boolean real = !TOP.equals(cur[0]) && !SHARED.equals(cur[0]);
        importBtn.setVisibility(real ? View.VISIBLE : View.GONE);
        items = new ArrayList<>();
        adapter.notifyDataSetChanged();
        if (TOP.equals(cur[0])) {
            DriveApi.Item myDrive = new DriveApi.Item();
            myDrive.id = "root";
            myDrive.name = "My Drive";
            myDrive.folder = true;
            DriveApi.Item shared = new DriveApi.Item();
            shared.id = SHARED;
            shared.name = "Shared with me";
            shared.folder = true;
            items.add(myDrive);
            items.add(shared);
            status.setText("Choose where your music is.");
            adapter.notifyDataSetChanged();
            return;
        }
        status.setText("Loading…");
        new Thread(() -> {
            List<DriveApi.Item> got = null;
            Exception err = null;
            try {
                got = SHARED.equals(cur[0]) ? DriveApi.sharedWithMe(this, account)
                        : DriveApi.children(this, account, cur[0]);
            } catch (Exception e) {
                err = e;
            }
            final List<DriveApi.Item> result = got;
            final Exception error = err;
            main.post(() -> {
                if (my != loadToken || isFinishing()) return;
                if (error instanceof DriveAuth.NeedsSignIn) {
                    status.setText("Waiting for Google sign-in…");
                    SignInActivity.run(this, account, this::load);
                    return;
                }
                if (error != null) {
                    status.setText(error instanceof java.io.IOException
                            ? "Couldn't load. Check your internet and try again."
                            : DriveAuth.explain(error));
                    return;
                }
                items = new ArrayList<>();
                int folders = 0, songs = 0;
                for (DriveApi.Item it : result) {
                    if (it.folder || it.audio) items.add(it);
                    if (it.folder) folders++;
                    else if (it.audio) songs++;
                }
                adapter.notifyDataSetChanged();
                if (items.isEmpty()) status.setText("This folder is empty.");
                else status.setText(folders + (folders == 1 ? " folder" : " folders") + "  ·  " + songs
                        + (songs == 1 ? " song" : " songs"));
            });
        }).start();
    }

    private void importCurrent() {
        final String[] cur = current();
        final TextView msg = Ui.text(this, "Reading \"" + cur[1] + "\" and its folders…", 15, Ui.TEXT, false);
        msg.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 8));
        final AlertDialog wait = new AlertDialog.Builder(this).setTitle("Adding to Echo").setView(msg)
                .setCancelable(false).show();
        new Thread(() -> {
            final Drive.Folder f = DriveApi.readTree(this, account, cur[0], cur[1]);
            main.post(() -> {
                wait.dismiss();
                if (isFinishing()) return;
                if (f.needsSignIn != null) {
                    SignInActivity.run(this, account, this::importCurrent);
                    return;
                }
                if (f.error != null || f.root == null) {
                    new AlertDialog.Builder(this).setTitle("Couldn't add the folder")
                            .setMessage(f.error == null ? "Something went wrong." : f.error)
                            .setPositiveButton("OK", null).show();
                    return;
                }
                Drive.Result r = Drive.apply(Library.get(this), f, cur[0], null);
                startActivity(new Intent(this, PlaylistActivity.class).putExtra("id", r.root.id));
                setResult(RESULT_OK);
                finish();
            });
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            load();
        } else {
            super.onBackPressed();
        }
    }
}
