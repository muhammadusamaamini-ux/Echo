package com.usama.echo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Small built-in browser for Google and YouTube searches. */
public class WebActivity extends Activity {
    private WebView web;
    private TextView importBtn;
    private TextView titleView;
    private ProgressBar loading;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.topBar(this, "Search", R.drawable.ic_back);
        titleView = (TextView) bar.getChildAt(1);
        importBtn = Ui.pill(this, "Import playlist", true);
        importBtn.setVisibility(View.GONE);
        importBtn.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra("link", web.getUrl()));
            finish();
        });
        bar.addView(importBtn);
        root.addView(bar);

        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100);
        loading.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        root.addView(loading, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 3)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                onUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                onUrl(url);
                String t = view.getTitle();
                if (t != null && !t.isEmpty()) titleView.setText(t);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                loading.setProgress(p);
                loading.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        String url = getIntent().getStringExtra("url");
        web.loadUrl(url == null ? "https://www.google.com" : url);
    }

    private void onUrl(String url) {
        boolean list = url != null && url.contains("youtube.com") && YouTube.listId(url) != null;
        importBtn.setVisibility(list ? View.VISIBLE : View.GONE);
    }

    static Intent google(Activity a, String q) {
        return new Intent(a, WebActivity.class).putExtra("url",
                "https://www.google.com/search?q=" + Uri.encode(q));
    }

    static Intent youtube(Activity a, String q) {
        return new Intent(a, WebActivity.class).putExtra("url",
                "https://m.youtube.com/results?search_query=" + Uri.encode(q));
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
