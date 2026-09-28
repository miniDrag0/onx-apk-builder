package com.onx.membersite;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
  private static final String PREFS = "onx_apk_bootstrap";
  private static final String PREF_URL = "membersite_url";
  private static final int BOOTSTRAP_TIMEOUT_MS = 8000;

  private WebView webView;
  private String membersiteUrl;
  private final AtomicBoolean readyToDraw = new AtomicBoolean(false);
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private Runnable bootstrapTimeout;

  @SuppressLint("SetJavaScriptEnabled")
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
    splashScreen.setKeepOnScreenCondition(() -> !readyToDraw.get());

    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    membersiteUrl = cachedOrFallbackUrl();
    webView = findViewById(R.id.webview);
    configureWebView(webView);

    resolveMembersiteUrlAndLoad();
  }

  private void configureWebView(WebView view) {
    WebSettings settings = view.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setLoadWithOverviewMode(true);
    settings.setUseWideViewPort(true);
    settings.setBuiltInZoomControls(false);
    settings.setDisplayZoomControls(false);
    settings.setMediaPlaybackRequiresUserGesture(false);

    view.setWebChromeClient(new WebChromeClient());
    view.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return false;
      }
    });
  }

  private String cachedOrFallbackUrl() {
    SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    String cached = prefs.getString(PREF_URL, null);
    if (cached != null && !cached.trim().isEmpty()) {
      return cached.trim();
    }
    return getString(R.string.membersite_url);
  }

  private void resolveMembersiteUrlAndLoad() {
    final String bootstrapUrl = getString(R.string.bootstrap_url);
    final String fallback = cachedOrFallbackUrl();

    if (bootstrapUrl == null || bootstrapUrl.trim().isEmpty()) {
      finishBootstrap(fallback);
      return;
    }

    bootstrapTimeout = () -> {
      if (!readyToDraw.get()) {
        finishBootstrap(fallback);
      }
    };
    mainHandler.postDelayed(bootstrapTimeout, BOOTSTRAP_TIMEOUT_MS);

    executor.execute(() -> {
      String resolved = fetchBootstrapUrl(bootstrapUrl.trim());
      String finalUrl = (resolved != null && !resolved.isEmpty()) ? resolved : fallback;
      if (resolved != null && !resolved.isEmpty()) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_URL, resolved)
            .apply();
      }
      mainHandler.post(() -> finishBootstrap(finalUrl));
    });
  }

  private void finishBootstrap(String url) {
    if (bootstrapTimeout != null) {
      mainHandler.removeCallbacks(bootstrapTimeout);
      bootstrapTimeout = null;
    }

    if (!readyToDraw.compareAndSet(false, true)) {
      return;
    }

    if (isFinishing() || isDestroyed()) {
      return;
    }

    membersiteUrl = url;
    if (webView != null && url != null && !url.isEmpty()) {
      webView.loadUrl(url);
    }
  }

  private String fetchBootstrapUrl(String bootstrapUrl) {
    HttpURLConnection connection = null;
    try {
      URL url = new URL(bootstrapUrl);
      connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(5000);
      connection.setRequestMethod("GET");
      connection.setRequestProperty("Accept", "application/json");
      connection.setRequestProperty("User-Agent", "ONX-MembersiteApk/1.0");
      connection.setInstanceFollowRedirects(true);

      int code = connection.getResponseCode();
      if (code < 200 || code >= 300) {
        return null;
      }

      StringBuilder body = new StringBuilder();
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          body.append(line);
        }
      }

      JSONObject json = new JSONObject(body.toString());
      if (!json.optBoolean("ok", false)) {
        return null;
      }
      String membersite = json.optString("membersite_url", "").trim();
      if (membersite.isEmpty()) {
        return null;
      }
      // Basic sanity: only allow http(s) targets from bootstrap.
      if (!membersite.startsWith("https://") && !membersite.startsWith("http://")) {
        return null;
      }
      return membersite;
    } catch (Exception ignored) {
      return null;
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    menu.add(0, 1, 0, "Reload WebView");
    menu.add(0, 2, 1, "Buka Membersite");
    menu.add(0, 3, 2, "Refresh URL");
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (webView == null) return super.onOptionsItemSelected(item);
    if (item.getItemId() == 1) {
      webView.reload();
      return true;
    }
    if (item.getItemId() == 2) {
      if (membersiteUrl != null && !membersiteUrl.isEmpty()) {
        webView.loadUrl(membersiteUrl);
      }
      return true;
    }
    if (item.getItemId() == 3) {
      refreshMembersiteUrl();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void refreshMembersiteUrl() {
    final String bootstrapUrl = getString(R.string.bootstrap_url);
    final String fallback = cachedOrFallbackUrl();
    if (bootstrapUrl == null || bootstrapUrl.trim().isEmpty()) {
      membersiteUrl = fallback;
      if (webView != null && !isDestroyed()) webView.loadUrl(membersiteUrl);
      return;
    }

    executor.execute(() -> {
      String resolved = fetchBootstrapUrl(bootstrapUrl.trim());
      String finalUrl = (resolved != null && !resolved.isEmpty()) ? resolved : fallback;
      if (resolved != null && !resolved.isEmpty()) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_URL, resolved)
            .apply();
      }
      mainHandler.post(() -> {
        if (isFinishing() || isDestroyed()) return;
        membersiteUrl = finalUrl;
        if (webView != null && finalUrl != null && !finalUrl.isEmpty()) {
          webView.loadUrl(finalUrl);
        }
      });
    });
  }

  @Override
  public void onBackPressed() {
    if (webView != null && webView.canGoBack()) {
      webView.goBack();
    } else {
      super.onBackPressed();
    }
  }

  @Override
  protected void onDestroy() {
    if (bootstrapTimeout != null) {
      mainHandler.removeCallbacks(bootstrapTimeout);
      bootstrapTimeout = null;
    }
    mainHandler.removeCallbacksAndMessages(null);
    readyToDraw.set(true);
    executor.shutdownNow();
    super.onDestroy();
  }
}
