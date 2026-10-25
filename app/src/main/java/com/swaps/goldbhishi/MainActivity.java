package com.swaps.goldbhishi;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.Scope;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;

public class MainActivity extends Activity {

    private static final int REQ_AUTH = 1001;
    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata";
    private static final String FILE_NAME = "gold-bhishi-data.json";

    private WebView web;
    private String pendingAction = "pull";
    private String pendingPush = "";

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

    private SharedPreferences prefs() {
        return getSharedPreferences("gb", MODE_PRIVATE);
    }

    /** Call a function on the web page, passing plain text arguments. */
    private void js(String fn, String... args) {
        StringBuilder sb = new StringBuilder("window." + fn + "&&window." + fn + "(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(JSONObject.quote(args[i]));
        }
        sb.append(")");
        final String code = sb.toString();
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    /* ---------------- Google authorization ---------------- */

    private void authorize(final String then) {
        AuthorizationRequest req = AuthorizationRequest.builder()
                .setRequestedScopes(Arrays.asList(new Scope(DRIVE_SCOPE), new Scope(Scopes.EMAIL)))
                .build();
        Identity.getAuthorizationClient(this).authorize(req)
                .addOnSuccessListener(result -> {
                    if (result.hasResolution()) {
                        try {
                            pendingAction = then;
                            IntentSender sender = result.getPendingIntent().getIntentSender();
                            startIntentSenderForResult(sender, REQ_AUTH, null, 0, 0, 0);
                        } catch (IntentSender.SendIntentException e) {
                            js("onSyncError", "Could not open Google sign-in");
                        }
                    } else {
                        gotAuth(result, then);
                    }
                })
                .addOnFailureListener(e -> js("onSyncError", "Google sign-in failed: " + e.getMessage()));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_AUTH) {
            if (resultCode == RESULT_OK && data != null) {
                try {
                    AuthorizationResult r = Identity.getAuthorizationClient(this)
                            .getAuthorizationResultFromIntent(data);
                    gotAuth(r, pendingAction);
                } catch (Exception e) {
                    js("onSyncError", "Sign-in failed: " + e.getMessage());
                }
            } else {
                js("onSyncError", "Sign-in cancelled");
            }
        }
    }

    private void gotAuth(AuthorizationResult r, final String then) {
        final String token = r.getAccessToken();
        if (token == null) {
            js("onSyncError", "Google did not give access");
            return;
        }
        try {
            if (r.toGoogleSignInAccount() != null && r.toGoogleSignInAccount().getEmail() != null) {
                prefs().edit().putString("email", r.toGoogleSignInAccount().getEmail()).apply();
            }
        } catch (Exception ignored) {
        }
        if (!prefs().contains("email")) {
            prefs().edit().putString("email", "Google account").apply();
        }
        final String email = prefs().getString("email", "");
        final String toPush = pendingPush;

        new Thread(() -> {
            try {
                if ("push".equals(then)) {
                    drivePush(token, toPush);
                    js("onPushed");
                } else {
                    String remote = driveRead(token);
                    js("onPulled", remote == null ? "" : remote, email);
                }
            } catch (Exception e) {
                js("onSyncError", "Sync failed: " + e.getMessage());
            }
        }).start();
    }

    /* ---------------- Google Drive (private app folder) ---------------- */

    private String http(String method, String url, String token, String contentType, byte[] body)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if ("PATCH".equals(method)) {
            c.setRequestMethod("POST");
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            c.setRequestMethod(method);
        }
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body);
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            text = new String(out.toByteArray(), "UTF-8");
        }
        if (code >= 400) {
            throw new Exception("Drive error " + code);
        }
        return text;
    }

    private String driveFindId(String token) throws Exception {
        String q = URLEncoder.encode("name='" + FILE_NAME + "'", "UTF-8");
        String url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&fields=files(id)&q=" + q;
        JSONArray files = new JSONObject(http("GET", url, token, null, null)).getJSONArray("files");
        return files.length() > 0 ? files.getJSONObject(0).getString("id") : null;
    }

    private String driveRead(String token) throws Exception {
        String id = driveFindId(token);
        if (id == null) return null;
        return http("GET", "https://www.googleapis.com/drive/v3/files/" + id + "?alt=media",
                token, null, null);
    }

    private void drivePush(String token, String json) throws Exception {
        String id = driveFindId(token);
        if (id == null) {
            String boundary = "gbBoundary7391";
            String meta = "{\"name\":\"" + FILE_NAME + "\",\"parents\":[\"appDataFolder\"]}";
            String body = "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                    + meta + "\r\n--" + boundary + "\r\nContent-Type: application/json\r\n\r\n"
                    + json + "\r\n--" + boundary + "--";
            http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
                    token, "multipart/related; boundary=" + boundary, body.getBytes("UTF-8"));
        } else {
            http("PATCH", "https://www.googleapis.com/upload/drive/v3/files/" + id + "?uploadType=media",
                    token, "application/json", json.getBytes("UTF-8"));
        }
    }

    /* ---------------- City gold rate ---------------- */

    private static String slugFor(String city) {
        String c = city.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "-");
        if (c.equals("bengaluru")) return "bangalore";
        return c;
    }

    private String httpGetPage(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("page error " + code);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < 3000000) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    /* ---------------- Functions the web page can call ---------------- */

    class Bridge {
        @JavascriptInterface
        public String load() {
            return prefs().getString("data", "");
        }

        @JavascriptInterface
        public void save(String data) {
            prefs().edit().putString("data", data).apply();
        }

        @JavascriptInterface
        public String getEmail() {
            return prefs().getString("email", "");
        }

        @JavascriptInterface
        public void signIn() {
            runOnUiThread(() -> authorize("pull"));
        }

        @JavascriptInterface
        public void pull() {
            runOnUiThread(() -> authorize("pull"));
        }

        @JavascriptInterface
        public void push(String json) {
            pendingPush = json;
            runOnUiThread(() -> authorize("push"));
        }

        @JavascriptInterface
        public void fetchRate(final String city) {
            new Thread(() -> {
                String slug = slugFor(city);
                String[] urls = {
                        "https://bb.bankbazaar.com/gold-rate-" + slug + ".html",
                        "https://www.bankbazaar.com/gold-rate-" + slug + ".html"
                };
                String lastErr = "no page";
                for (String u : urls) {
                    try {
                        RateParser.Result r = RateParser.parse(httpGetPage(u));
                        if (r != null) {
                            js("onRate", city, String.valueOf(r.r22), String.valueOf(r.r24),
                                    r.date == null ? "" : r.date);
                            return;
                        }
                        lastErr = "rate not found on page";
                    } catch (Exception e) {
                        lastErr = String.valueOf(e.getMessage());
                    }
                }
                js("onRateError", city, lastErr);
            }).start();
        }

        @JavascriptInterface
        public void signOut() {
            prefs().edit().remove("email").apply();
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
