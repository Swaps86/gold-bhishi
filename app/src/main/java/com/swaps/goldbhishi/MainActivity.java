package com.swaps.goldbhishi;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);

        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.goBack ? window.goBack() : false)", value -> {
            if (!"true".equals(value)) {
                finish();
            }
        });
    }

    /** Functions the web page can call to save data on the phone. */
    class Bridge {
        @JavascriptInterface
        public String load() {
            return getSharedPreferences("gb", MODE_PRIVATE).getString("data", "");
        }

        @JavascriptInterface
        public void save(String data) {
            getSharedPreferences("gb", MODE_PRIVATE).edit().putString("data", data).apply();
        }

        @JavascriptInterface
        public void share(final String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, "Gold Bhishi backup");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Save backup to"));
            });
        }
    }
}
