package il.david.govmap.dark;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
import android.view.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayInputStream;

public class MainActivity extends Activity {
    private WebView web;
    private PersistentAssetLoader assets;
    private boolean darkMode;
    private int themeMode;
    private String darkCss, themeScript, locationScript = "";
    private long themeDocument, appliedThemeDocument = -1, pendingThemeDocument = -1;
    private int appliedThemeMode = -1, pendingThemeMode = -1;
    private LinearLayout toolbar;
    private TextView toolbarTitle, loadMessage;
    private Button themeButton, homeButton, reloadButton;
    private final Handler locationHandler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private LocationListener warmListener;
    private boolean foreground;
    private boolean locationDocumentReady;
    private final Runnable stopWarmup = () -> stopLocationWarmup();
    private String pendingOrigin;
    private GeolocationPermissions.Callback pendingLocation;
    private static final String HOME = "https://www.govmap.gov.il/?lay=23";
    static boolean allowed(Uri uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && host != null
            && (host.equalsIgnoreCase("govmap.gov.il") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".govmap.gov.il"))
            && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
    }
    private boolean block(Uri uri) {
        if (allowed(uri)) return false;
        Toast.makeText(this, "אפשר לפתוח רק את GovMap", Toast.LENGTH_SHORT).show();
        return true;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        themeMode = getPreferences(MODE_PRIVATE).getInt("themeMode", getPreferences(MODE_PRIVATE).getBoolean("darkMode", true) ? 1 : 0);
        if (themeMode < 0 || themeMode > 2) themeMode = 1;
        darkMode = themeMode != 0;
        try {
            darkCss = readAsset("dark.css"); themeScript = readAsset("theme.js");
        } catch (java.io.IOException error) {
            darkCss = ""; themeScript = ""; darkMode = false;
        }
        try { locationScript = readAsset("location-preload.js"); } catch (java.io.IOException ignored) {}
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(2), dp(8), dp(2));
        bar.setBackgroundColor(Color.rgb(248, 250, 253));
        TextView title = new TextView(this); title.setText("GovMap"); title.setTextSize(16);
        title.setContentDescription("GovMap — לחיצה ארוכה לעדכוני אפליקציה");
        title.setOnLongClickListener(v -> { AppUpdates.get(this).controls(this); return true; });
        title.setTextColor(Color.rgb(26, 48, 78)); title.setTypeface(null, Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        Button home = toolbarButton("בית", "חזרה למפה הראשית");
        home.setOnClickListener(v -> { if (web == null) { recreate(); return; } startLocationWarmup(); web.loadUrl(HOME); });
        Button reload = toolbarButton("רענון", "רענון המפה");
        reload.setOnClickListener(v -> { if (web == null) { recreate(); return; } startLocationWarmup(); web.reload(); });
        themeButton = toolbarButton("בהיר", "מעבר בין מצב כהה ובהיר");
        themeButton.setOnClickListener(v -> {
            themeMode = (themeMode + 1) % 3; darkMode = themeMode != 0;
            getPreferences(MODE_PRIVATE).edit().putInt("themeMode", themeMode).apply();
            updateNativeTheme(); applyWebTheme();
        });
        toolbar = bar; toolbarTitle = title; homeButton = home; reloadButton = reload;
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(home); bar.addView(reload); bar.addView(themeButton); root.addView(bar);
        assets = PersistentAssetLoader.get(this);
        web = new WebView(this); root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        WebSettings settings = web.getSettings();
        if (android.os.Build.VERSION.SDK_INT >= 33) settings.setAlgorithmicDarkeningAllowed(false);
        else if (android.os.Build.VERSION.SDK_INT >= 29) settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        updateNativeTheme();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return block(request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return block(Uri.parse(url)); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request.isForMainFrame() && !allowed(request.getUrl()))
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                return assets.intercept(request);
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                themeDocument++;
                locationDocumentReady = false;
                if (!allowed(Uri.parse(url))) { view.stopLoading(); view.loadUrl(HOME); }
            }
            @Override public void onPageCommitVisible(WebView view, String url) { locationDocumentReady = true; applyWebTheme(); startLocationPreload(); }
            @Override public void onPageFinished(WebView view, String url) { locationDocumentReady = true; verifyWebTheme(); startLocationPreload(); }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                locationDocumentReady = false;
                stopLocationWarmup(); cancelPendingLocation();
                android.view.ViewGroup parent = (android.view.ViewGroup) view.getParent();
                if (parent != null) parent.removeView(view);
                view.destroy(); web = null;
                loadMessage = new TextView(MainActivity.this);
                loadMessage.setText("המפה נסגרה. לחץ רענון לטעינה מחדש");
                loadMessage.setGravity(Gravity.CENTER); loadMessage.setTextSize(16);
                loadMessage.setPadding(dp(24), dp(24), dp(24), dp(24));
                if (parent != null) parent.addView(loadMessage, new LinearLayout.LayoutParams(-1, 0, 1));
                updateNativeTheme();
                return true;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError error) {
                if (req.isForMainFrame()) Toast.makeText(MainActivity.this, "טעינה נכשלה. בדוק חיבור ולחץ רענון", Toast.LENGTH_LONG).show();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, android.os.Message message) {
                Toast.makeText(MainActivity.this, "פתיחת חלון נוסף חסומה", Toast.LENGTH_SHORT).show(); return false;
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (!foreground || !allowed(Uri.parse(origin))) { callback.invoke(origin, false, false); return; }
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    startLocationWarmup();
                    callback.invoke(origin, true, false); return;
                }
                cancelPendingLocation(); pendingOrigin = origin; pendingLocation = callback;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            }
            @Override public void onGeolocationPermissionsHidePrompt() { cancelPendingLocation(); }
        });
        web.setDownloadListener((url, agent, disposition, mime, size) -> Toast.makeText(this, "הורדות חסומות בגרסה זו", Toast.LENGTH_SHORT).show());
        web.loadUrl(HOME);
    }
    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private void startLocationPreload() {
        if (!foreground || !locationDocumentReady || web == null || locationScript.isEmpty() || web.getUrl() == null || !allowed(Uri.parse(web.getUrl()))) return;
        boolean granted = hasLocationPermission();
        if (!granted) {
            // Automatic preloading may ask once; a declined permission can still be requested using the site's button.
            if (getPreferences(MODE_PRIVATE).getBoolean("locationPreloadAsked", false)
                || shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                || shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) return;
            getPreferences(MODE_PRIVATE).edit().putBoolean("locationPreloadAsked", true).apply();
        }
        web.evaluateJavascript(locationScript.replace("/*RETRY_VALUE*/", Boolean.toString(granted)), null);
    }
    private void stopLocationPreload() {
        if (web != null && web.getUrl() != null && allowed(Uri.parse(web.getUrl())))
            web.evaluateJavascript("if(window.__govmapLocationPreload){window.__govmapLocationPreload.stop();}", null);
    }
    private String readAsset(String name) throws java.io.IOException {
        try (java.io.InputStream stream = getAssets().open(name)) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] bytes = new byte[4096]; int count;
            while ((count = stream.read(bytes)) != -1) buffer.write(bytes, 0, count);
            return new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private void applyWebTheme() {
        if (web == null || web.getUrl() == null || !allowed(Uri.parse(web.getUrl()))) return;
        if ((appliedThemeDocument == themeDocument && appliedThemeMode == themeMode)
            || (pendingThemeDocument == themeDocument && pendingThemeMode == themeMode)) return;
        final long document = themeDocument;
        final int mode = themeMode;
        pendingThemeDocument = document; pendingThemeMode = mode;
        String script = themeScript.replace("/*CSS_VALUE*/", org.json.JSONObject.quote(darkCss))
            .replace("/*DARK_VALUE*/", Boolean.toString(darkMode))
            .replace("/*OLED_VALUE*/", Boolean.toString(themeMode == 2));
        web.evaluateJavascript(script, value -> {
            if (document != themeDocument) return;
            if (pendingThemeDocument == document && pendingThemeMode == mode) pendingThemeDocument = -1;
            if ("true".equals(value)) { appliedThemeDocument = document; appliedThemeMode = mode; }
        });
    }
    private void verifyWebTheme() {
        if (web == null || web.getUrl() == null || !allowed(Uri.parse(web.getUrl()))) return;
        final long document = themeDocument; final int mode = themeMode;
        // A small probe avoids resending the stylesheet, but repairs it if the site replaced its head.
        String probe = "(function(){var s=document.getElementById('govmap-wrapper-dark-style');return !!s && s.textContent.length=="
            + darkCss.length() + " && !!document.getElementById('govmap-oled-filters') && document.documentElement.getAttribute('data-govmap-dark')==='"
            + Boolean.toString(mode != 0) + "' && document.documentElement.getAttribute('data-govmap-oled')==='" + Boolean.toString(mode == 2) + "';})()";
        web.evaluateJavascript(probe, value -> {
            if (document != themeDocument || mode != themeMode || "true".equals(value)) return;
            appliedThemeDocument = -1; pendingThemeDocument = -1; applyWebTheme();
        });
    }
    private void updateNativeTheme() {
        int bg = themeMode == 2 ? Color.BLACK : darkMode ? Color.rgb(21, 28, 39) : Color.rgb(248, 250, 253);
        int fg = darkMode ? Color.rgb(237, 243, 252) : Color.rgb(26, 48, 78);
        toolbar.setBackgroundColor(bg); toolbarTitle.setTextColor(fg);
        getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
        int flags = darkMode ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (web != null) web.setBackgroundColor(bg);
        if (loadMessage != null) { loadMessage.setBackgroundColor(bg); loadMessage.setTextColor(fg); }
        for (Button button : new Button[]{homeButton, reloadButton, themeButton}) styleToolbarButton(button);
        themeButton.setText(themeMode == 2 ? "OLED" : darkMode ? "כהה" : "בהיר");
        themeButton.setContentDescription(themeMode == 0 ? "מעבר למצב כהה" : themeMode == 1 ? "מעבר למצב לילה OLED" : "מעבר למצב בהיר");
    }
    private void cancelPendingLocation() {
        GeolocationPermissions.Callback callback = pendingLocation; String origin = pendingOrigin;
        pendingLocation = null; pendingOrigin = null;
        if (callback != null) callback.invoke(origin, false, false);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1) {
            boolean grant = hasLocationPermission();
            GeolocationPermissions.Callback callback = pendingLocation; String origin = pendingOrigin;
            pendingLocation = null; pendingOrigin = null;
            if (callback != null) callback.invoke(origin, grant, false);
            if (grant) { startLocationWarmup(); startLocationPreload(); }
            else if (foreground) Toast.makeText(this, "מיקום לא אושר. אפשר לאפשר בהגדרות האפליקציה", Toast.LENGTH_LONG).show();
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private Button toolbarButton(String text, String description) {
        Button button = new Button(this); button.setText(text); button.setTextSize(12);
        button.setAllCaps(false); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setMinHeight(0); button.setMinimumHeight(0); button.setPadding(dp(10), 0, dp(10), 0);
        button.setContentDescription(description);
        styleToolbarButton(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(64), dp(48));
        params.setMargins(dp(4), 0, 0, 0); button.setLayoutParams(params);
        return button;
    }
    private void styleToolbarButton(Button button) {
        button.setTextColor(darkMode ? Color.rgb(190, 218, 255) : Color.rgb(32, 78, 138));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(themeMode == 2 ? Color.BLACK : darkMode ? Color.rgb(40, 56, 78) : Color.rgb(233, 240, 250));
        shape.setCornerRadius(dp(14));
        if (themeMode == 2) shape.setStroke(dp(1), Color.rgb(83, 106, 139));
        android.graphics.drawable.InsetDrawable inset = new android.graphics.drawable.InsetDrawable(shape, 0, dp(9), 0, dp(9));
        android.content.res.ColorStateList ripple = android.content.res.ColorStateList.valueOf(darkMode ? 0x5079B2FF : 0x302568B0);
        button.setBackground(new android.graphics.drawable.RippleDrawable(ripple, inset, null));
    }
    // Prime Android's location providers for WebView, without overriding website geolocation.
    // Foreground only, no stored coordinates, maximum 25 seconds per warm-up.
    private void startLocationWarmup() {
        stopLocationWarmup();
        if (!foreground) return;
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return;
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) return;
        warmListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (location.hasAccuracy() && location.getAccuracy() <= 30f) stopLocationWarmup();
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        boolean started = false;
        String[] providers = fine ? new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}
            : new String[]{LocationManager.NETWORK_PROVIDER};
        for (String provider : providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 1000L, 0f, warmListener, Looper.getMainLooper());
                    started = true;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {}
        }
        if (started) locationHandler.postDelayed(stopWarmup, 25000L);
        else stopLocationWarmup();
    }
    private void stopLocationWarmup() {
        locationHandler.removeCallbacks(stopWarmup);
        if (locationManager != null && warmListener != null) {
            try { locationManager.removeUpdates(warmListener); } catch (SecurityException ignored) {}
        }
        warmListener = null;
    }
    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        getWindow().getDecorView().requestApplyInsets();
        if (web != null) { web.requestLayout(); web.invalidate(); }
    }
    @Override protected void onPause() { AppUpdates.get(this).pause(this); foreground = false; stopLocationPreload(); stopLocationWarmup(); if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data); AppUpdates.get(this).activityResult(request, result, data);
    }
    @Override protected void onResume() { super.onResume(); foreground = true; if (web != null) web.onResume(); startLocationWarmup(); startLocationPreload(); AppUpdates.get(this).resume(this); }
    @Override protected void onDestroy() { AppUpdates.get(this).destroy(this); stopLocationPreload(); stopLocationWarmup(); cancelPendingLocation(); if (web != null) web.destroy(); super.onDestroy(); }
}
