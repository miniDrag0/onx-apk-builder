package com.onx.membersite;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
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
  private static final long FOREGROUND_POLL_MS = 90_000L;
  private static final long ERROR_RECOVERY_COOLDOWN_MS = 15_000L;
  private static final long RESUME_MIN_INTERVAL_MS = 5_000L;

  private WebView webView;
  private LinearLayout errorPanel;
  private TextView errorMessage;
  private String membersiteUrl;
  private final AtomicBoolean readyToDraw = new AtomicBoolean(false);
  private final AtomicBoolean softRefreshInFlight = new AtomicBoolean(false);
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private Runnable bootstrapTimeout;
  private Runnable foregroundPoll;
  private long lastErrorRecoveryAtMs = 0L;
  private long lastSoftRefreshAtMs = 0L;
  private boolean resumed = false;

  @SuppressLint("SetJavaScriptEnabled")
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
    splashScreen.setKeepOnScreenCondition(() -> !readyToDraw.get());

    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    membersiteUrl = cachedOrFallbackUrl();
    webView = findViewById(R.id.webview);
    errorPanel = findViewById(R.id.error_panel);
    errorMessage = findViewById(R.id.error_message);
    Button errorRetry = findViewById(R.id.error_retry);
    errorRetry.setOnClickListener(v -> softRefreshMembersiteUrl(true));

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
    // Identify as in-app WebView so membersite can treat us as installed app,
    // and keep a mobile Chrome baseline for layout width.
    settings.setUserAgentString(
        settings.getUserAgentString() + " ONXMembersiteApp/1.0"
    );

    view.setWebChromeClient(new WebChromeClient());
    view.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return false;
      }

      @Override
      public void onPageStarted(WebView view, String url, Bitmap favicon) {
        hideErrorPanel();
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        hideMembersiteAppDownloadChrome(view);
      }

      @Override
      public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request != null && !request.isForMainFrame()) {
          return;
        }
        handleMembersiteLoadFailure();
      }

      @Override
      @SuppressWarnings("deprecation")
      public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        // Pre-API23 path; main-frame only heuristic via failingUrl match.
        if (failingUrl != null && membersiteUrl != null && !sameMembersite(failingUrl, membersiteUrl)) {
          return;
        }
        handleMembersiteLoadFailure();
      }
    });
  }

  /**
   * Membersite often shows a sticky "Download APK / Aplikasi Mobile" bar when
   * opened in a browser. Inside our installed WebView that bar is redundant —
   * shrink/hide common promo chrome so content uses full width.
   */
  private void hideMembersiteAppDownloadChrome(WebView view) {
    if (view == null) return;
    String js =
        "(function(){"
            + "try{"
            + "var nodes=document.querySelectorAll('body *');"
            + "for(var i=0;i<nodes.length;i++){"
            + "var el=nodes[i];"
            + "if(!el||!el.textContent) continue;"
            + "var t=(el.textContent||'').replace(/\\s+/g,' ').trim();"
            + "if(t.length>120) continue;"
            + "var hit=/Aplikasi Mobile|Download App|Unduh Aplikasi|\\bUNDUH\\b/i.test(t);"
            + "if(!hit) continue;"
            + "var box=el.closest('header,nav,section,div,aside')||el;"
            + "var style=window.getComputedStyle(box);"
            + "var fixed=style.position==='fixed'||style.position==='sticky';"
            + "var topish=(box.getBoundingClientRect().top<120);"
            + "if(fixed||topish){box.style.setProperty('display','none','important');}"
            + "}"
            + "}catch(e){}"
            + "})();";
    view.evaluateJavascript(js, null);
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
      persistResolvedUrl(resolved);
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

    applyMembersiteUrl(url, true);
  }

  /**
   * Soft refresh used by onResume / poll / WebView error / menu.
   * Reloads WebView only when URL changed, unless forceReload is true.
   */
  private void softRefreshMembersiteUrl(boolean forceReload) {
    if (isFinishing() || isDestroyed()) return;
    if (!readyToDraw.get()) return; // wait until cold-start splash finishes

    long now = System.currentTimeMillis();
    if (!forceReload && (now - lastSoftRefreshAtMs) < RESUME_MIN_INTERVAL_MS) {
      return;
    }
    if (!softRefreshInFlight.compareAndSet(false, true)) {
      return;
    }
    lastSoftRefreshAtMs = now;

    final String bootstrapUrl = getString(R.string.bootstrap_url);
    final String fallback = cachedOrFallbackUrl();
    final String current = membersiteUrl;

    if (bootstrapUrl == null || bootstrapUrl.trim().isEmpty()) {
      softRefreshInFlight.set(false);
      if (forceReload) {
        applyMembersiteUrl(fallback, true);
      }
      return;
    }

    executor.execute(() -> {
      String resolved = fetchBootstrapUrl(bootstrapUrl.trim());
      persistResolvedUrl(resolved);
      String finalUrl = (resolved != null && !resolved.isEmpty()) ? resolved : fallback;
      boolean changed = !sameMembersite(current, finalUrl);

      mainHandler.post(() -> {
        softRefreshInFlight.set(false);
        if (isFinishing() || isDestroyed()) return;

        if (changed) {
          hideErrorPanel();
          applyMembersiteUrl(finalUrl, true);
        } else if (forceReload) {
          applyMembersiteUrl(finalUrl, true);
        } else if (errorPanel != null && errorPanel.getVisibility() == View.VISIBLE) {
          // Still broken and URL unchanged — keep panel, user can retry.
        }
      });
    });
  }

  private void applyMembersiteUrl(String url, boolean load) {
    if (url == null || url.trim().isEmpty()) return;
    membersiteUrl = url.trim();
    if (load && webView != null && !isDestroyed()) {
      webView.loadUrl(membersiteUrl);
    }
  }

  private void persistResolvedUrl(String resolved) {
    if (resolved == null || resolved.isEmpty()) return;
    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_URL, resolved)
        .apply();
  }

  private void handleMembersiteLoadFailure() {
    if (isFinishing() || isDestroyed()) return;
    if (!readyToDraw.get()) return;

    showErrorPanel();

    long now = System.currentTimeMillis();
    if ((now - lastErrorRecoveryAtMs) < ERROR_RECOVERY_COOLDOWN_MS) {
      return;
    }
    lastErrorRecoveryAtMs = now;
    softRefreshMembersiteUrl(false);
  }

  private void showErrorPanel() {
    if (errorPanel == null) return;
    if (errorMessage != null) {
      errorMessage.setText(R.string.membersite_unreachable);
    }
    errorPanel.setVisibility(View.VISIBLE);
  }

  private void hideErrorPanel() {
    if (errorPanel != null) {
      errorPanel.setVisibility(View.GONE);
    }
  }

  private boolean sameMembersite(String a, String b) {
    return normalizeUrl(a).equals(normalizeUrl(b));
  }

  private String normalizeUrl(String raw) {
    if (raw == null) return "";
    String value = raw.trim();
    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    return value.toLowerCase();
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

  private void startForegroundPoll() {
    stopForegroundPoll();
    foregroundPoll = new Runnable() {
      @Override
      public void run() {
        if (!resumed || isFinishing() || isDestroyed()) return;
        softRefreshMembersiteUrl(false);
        mainHandler.postDelayed(this, FOREGROUND_POLL_MS);
      }
    };
    mainHandler.postDelayed(foregroundPoll, FOREGROUND_POLL_MS);
  }

  private void stopForegroundPoll() {
    if (foregroundPoll != null) {
      mainHandler.removeCallbacks(foregroundPoll);
      foregroundPoll = null;
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    softRefreshMembersiteUrl(false);
    startForegroundPoll();
  }

  @Override
  protected void onPause() {
    resumed = false;
    stopForegroundPoll();
    super.onPause();
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
    resumed = false;
    stopForegroundPoll();
    if (bootstrapTimeout != null) {
      mainHandler.removeCallbacks(bootstrapTimeout);
      bootstrapTimeout = null;
    }
    mainHandler.removeCallbacksAndMessages(null);
    readyToDraw.set(true);
    softRefreshInFlight.set(false);
    executor.shutdownNow();
    super.onDestroy();
  }
}
