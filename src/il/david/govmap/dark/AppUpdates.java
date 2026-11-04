package il.david.govmap.dark;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.Toast;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** App-owned updater. Website JavaScript cannot call it or change its source. */
final class AppUpdates {
    static final int JOB = 4701;
    private static final long HOUR = 60L * 60 * 1000;
    private static AppUpdates instance;
    static synchronized AppUpdates get(Context context) {
        if (instance == null) instance = new AppUpdates(context.getApplicationContext()); return instance;
    }
    private final Context context;
    private final SharedPreferences prefs;
    private final File directory, apk, metadata;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "GovMap-updates"); t.setDaemon(true); return t; });
    private final AtomicBoolean checking = new AtomicBoolean();
    private volatile UpdateRelease.Transfer activeTransfer;
    private WeakReference<Activity> activity = new WeakReference<>(null);
    private volatile boolean visible;
    private boolean dialogOpen;
    private volatile Intent confirmationAction;
    private volatile int confirmationSession = -1;
    private final Runnable launchCheck = () -> { if (visible && enabled()) check(false, new UpdateRelease.Transfer(), null); };
    private final Runnable offerSetup = this::offerSetup;
    private void offerSetup() {
        Activity a = current();
        if (a != null && a.hasWindowFocus() && !prefs.getBoolean("setupAsked", false) && !dialogOpen) setup(a);
    }
    private final Runnable installWhenClosed = () -> worker.execute(() -> install(false));

    private AppUpdates(Context context) {
        this.context = context; prefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE);
        directory = new File(context.getNoBackupFilesDir(), "app-updates"); directory.mkdirs();
        apk = new File(directory, "ready.apk"); metadata = new File(directory, "ready.json");
    }
    private boolean enabled() { return prefs.getBoolean("enabled", false); }
    private Activity current() { Activity a = activity.get(); return visible && a != null && !a.isFinishing() && !a.isDestroyed() ? a : null; }
    void resume(Activity a) {
        synchronized (this) { activity = new WeakReference<>(a); visible = true; }
        main.removeCallbacks(installWhenClosed); main.removeCallbacks(launchCheck); main.removeCallbacks(offerSetup);
        if (enabled()) { schedule(); worker.execute(() -> recoverSession()); main.postDelayed(launchCheck, 5000); main.postDelayed(() -> showConfirmation(false), 1500); }
        else if (!prefs.getBoolean("setupAsked", false)) main.postDelayed(offerSetup, 10000);
    }
    void pause(Activity a) {
        if (activity.get() != a) return;
        synchronized (this) { visible = false; }
        main.removeCallbacks(launchCheck); main.removeCallbacks(offerSetup);
        main.removeCallbacks(installWhenClosed); main.postDelayed(installWhenClosed, 2500);
    }
    void destroy(Activity a) { if (activity.get() == a) { visible = false; activity.clear(); dialogOpen = false; } }
    private void schedule() {
        if (!enabled()) return;
        try {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler.getPendingJob(JOB) == null) scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(context, UpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(6 * HOUR)
                .setRequiresBatteryNotLow(true).build());
        } catch (RuntimeException ignored) { /* Launch checks remain available on restricted devices. */ }
    }
    private void setup(Activity a) {
        if (dialogOpen) return;
        prefs.edit().putBoolean("setupAsked", true).apply(); dialogOpen = true;
        AlertDialog d = new AlertDialog.Builder(a).setTitle("עדכונים אוטומטיים")
            .setMessage("GovMap יבדוק גרסאות ב-GitHub ויוריד עדכונים חתומים. ההתקנה האוטומטית תתבצע כשהמפה סגורה, אם Android מאפשר זאת. ייתכן שתידרש הסכמה להתקנה.\n\nאפשר לשנות זאת בלחיצה ארוכה על הכותרת GovMap.")
            .setPositiveButton("הפעל", (dialog, which) -> {
                prefs.edit().putBoolean("enabled", true).apply(); schedule(); permission(a);
                check(true, new UpdateRelease.Transfer(), null);
            }).setNegativeButton("אחר כך", null).create();
        d.setOnDismissListener(dialog -> dialogOpen = false); d.show();
    }
    private boolean permission(Activity a) {
        if (context.getPackageManager().canRequestPackageInstalls()) return true;
        try { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()))); }
        catch (RuntimeException ignored) { toast("אפשר התקנת עדכונים בהגדרות Android"); }
        return false;
    }
    void controls(Activity a) {
        if (dialogOpen) return;
        dialogOpen = true;
        String version = "";
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        AlertDialog d = new AlertDialog.Builder(a).setTitle("GovMap " + version)
            .setMessage((enabled() ? "עדכונים אוטומטיים פעילים" : "עדכונים אוטומטיים כבויים") + "\n" + prefs.getString("status", "מקור: GitHub • davidreuveni/GovMap")
                + (prefs.contains("installError") ? "\n\n" + prefs.getString("installError", "") : ""))
            .setPositiveButton("בדוק / התקן", (dialog, which) -> {
                if (!enabled()) { main.post(() -> setup(a)); return; }
                if (!permission(a)) return;
                worker.execute(() -> {
                    if (!manualInstall()) check(true, new UpdateRelease.Transfer(), null);
                });
            }).setNeutralButton(enabled() ? "כבה עדכונים" : "הפעל עדכונים", (dialog, which) -> {
                if (!enabled()) { main.post(() -> setup(a)); return; }
                prefs.edit().putBoolean("enabled", false).apply();
                context.getSystemService(JobScheduler.class).cancel(JOB);
                UpdateRelease.Transfer t = activeTransfer; if (t != null) t.cancel();
                worker.execute(() -> { abandon(); finishCompatibility(); apk.delete(); metadata.delete(); });
                toast("עדכונים אוטומטיים כבויים");
            }).setNegativeButton("סגור", null).create();
        d.setOnDismissListener(dialog -> dialogOpen = false); d.show();
    }
    void check(boolean manual, UpdateRelease.Transfer transfer, Runnable finished) {
        if (!checking.compareAndSet(false, true)) { complete(finished); if (manual) toast("בדיקת עדכונים כבר פועלת"); return; }
        activeTransfer = transfer;
        worker.execute(() -> {
            try {
                if (!enabled()) return;
                recoverSession();
                if (compatibilityActive()) { status("מתקין Android עדיין מטפל בעדכון. חזור אליו להשלמת ההתקנה", manual); return; }
                long now = System.currentTimeMillis(), last = prefs.getLong("attempt", 0);
                if (!manual && now >= last && now - last < 15 * 60 * 1000L) { install(false); return; }
                prefs.edit().putLong("attempt", now).apply();
                if (manual) toast("בודק עדכונים…");
                String json = new String(UpdateRelease.fetch(UpdateRelease.FEED, 16384, transfer, UpdateRelease.HTTPS), StandardCharsets.UTF_8);
                UpdateRelease release = new UpdateRelease(json);
                transfer.check();
                if (release.code <= installedCode()) { status("הגרסה המותקנת מעודכנת", manual); return; }
                if (release.minSdk > Build.VERSION.SDK_INT) { status("הגרסה החדשה דורשת Android חדש יותר", manual); return; }
                UpdateRelease ready = ready();
                if (ready == null || ready.code != release.code || !ready.sha256.equals(release.sha256)) stage(release, transfer);
                transfer.check();
                if (!enabled()) return;
                status("עדכון " + release.version + " מוכן להתקנה כשהמפה סגורה", manual);
                if (manual && visible) main.post(() -> offerReady());
                else install(false);
            } catch (Exception error) {
                if (!transfer.cancelled) status("בדיקת GitHub נכשלה: " + detail(error), manual);
            } finally { activeTransfer = null; checking.set(false); complete(finished); }
        });
    }
    private void complete(Runnable finished) { if (finished != null) main.post(finished); }
    private void status(String status, boolean notify) { prefs.edit().putString("status", status).apply(); if (notify) toast(status); }
    private void toast(String text) { main.post(() -> { Activity a = current(); if (a != null) Toast.makeText(a, text, Toast.LENGTH_LONG).show(); }); }
    private long installedCode() throws Exception { return versionCode(context.getPackageManager().getPackageInfo(context.getPackageName(), 0)); }
    private static long versionCode(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    private void verifyApk(UpdateRelease release, File file) throws Exception {
        if (file.length() != release.size || !release.sha256.equals(UpdateRelease.hash(file))) throw new IOException("Wrong APK hash");
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
        if (candidate == null || !UpdateRelease.PACKAGE.equals(candidate.packageName) || versionCode(candidate) != release.code
            || !release.version.equals(candidate.versionName) || versionCode(candidate) <= versionCode(installed)
            || candidate.applicationInfo == null || candidate.applicationInfo.minSdkVersion != release.minSdk
            || candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) throw new IOException("Wrong APK identity or version");
        if (!UpdateRelease.CERTIFICATE.equals(certificate(candidate)) || !UpdateRelease.CERTIFICATE.equals(certificate(installed)))
            throw new IOException("Wrong APK signer");
    }
    private static String certificate(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null || info.signingInfo.hasMultipleSigners()) throw new IOException("Unexpected signer set");
            signatures = info.signingInfo.getApkContentsSigners();
        } else signatures = info.signatures;
        if (signatures == null || signatures.length != 1) throw new IOException("Missing signer");
        return UpdateRelease.hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
    }
    private void stage(UpdateRelease release, UpdateRelease.Transfer transfer) throws Exception {
        File part = new File(directory, "download.part"), meta = new File(directory, "metadata.part");
        try {
            try (FileOutputStream out = new FileOutputStream(part)) {
                UpdateRelease.download(release.url, out, UpdateRelease.MAX_APK, release.size, release.sha256, transfer, UpdateRelease.HTTPS);
                out.getFD().sync();
            }
            verifyApk(release, part); transfer.check();
            if (!enabled()) throw new IOException("Updates disabled");
            try (FileOutputStream out = new FileOutputStream(meta)) { out.write(release.json.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            // A crash between these replacements leaves a mismatched pair, rejected by ready().
            if (!part.renameTo(apk) || !meta.renameTo(metadata)) throw new IOException("Cannot save update");
        } finally { part.delete(); meta.delete(); }
    }
    private UpdateRelease ready() {
        try {
            if (!metadata.isFile() || metadata.length() > 16384 || !apk.isFile()) return null;
            byte[] bytes = new byte[(int) metadata.length()];
            try (DataInputStream in = new DataInputStream(new FileInputStream(metadata))) { in.readFully(bytes); }
            UpdateRelease release = new UpdateRelease(new String(bytes, StandardCharsets.UTF_8));
            verifyApk(release, apk); return release;
        } catch (Exception ignored) { return null; }
    }
    private void recoverSession() {
        int id = prefs.getInt("session", -1);
        try {
            long compatibility = prefs.getLong("compatibilityCode", 0);
            if (compatibility > 0 && compatibility <= installedCode()) {
                finishCompatibility(); prefs.edit().remove("installError").remove("retryAfter").putString("status", "העדכון הותקן בהצלחה").apply();
                apk.delete(); metadata.delete();
            } else if (compatibility > 0 && !compatibilityActive()) {
                finishCompatibility(); installationError("מתקין Android לא השלים את העדכון. אפשר לנסות התקנה רגילה שוב");
            }
            if (prefs.contains("installCode") && prefs.getLong("installCode", 0) <= installedCode()) { clearSession(); apk.delete(); metadata.delete(); }
            else if (id != -1 && (context.getPackageManager().getPackageInstaller().getSessionInfo(id) == null
                || System.currentTimeMillis() - prefs.getLong("sessionTime", 0) > 24 * HOUR)) abandon();
            int retained = prefs.getInt("session", -1);
            for (PackageInstaller.SessionInfo session : context.getPackageManager().getPackageInstaller().getMySessions())
                if (session.getSessionId() != retained) context.getPackageManager().getPackageInstaller().abandonSession(session.getSessionId());
        } catch (Exception ignored) {}
    }
    private void clearSession() { confirmationAction = null; confirmationSession = -1; prefs.edit().remove("session").remove("token").remove("confirmation").remove("confirmationNeeded").remove("installCode").remove("sessionTime").remove("promptedSession").commit(); }
    private void abandon() {
        int id = prefs.getInt("session", -1);
        if (id != -1) try { context.getPackageManager().getPackageInstaller().abandonSession(id); } catch (RuntimeException ignored) {}
        clearSession();
    }
    private boolean install(boolean manual) {
        if (!enabled() || compatibilityActive() || (!manual && visible) || !context.getPackageManager().canRequestPackageInstalls()) return false;
        recoverSession();
        if (prefs.getInt("session", -1) != -1) { main.post(() -> showConfirmation(manual)); return true; }
        if (!manual && System.currentTimeMillis() < prefs.getLong("retryAfter", 0)) return false;
        UpdateRelease release = ready(); if (release == null) return false;
        PackageInstaller installer = context.getPackageManager().getPackageInstaller(); int id = -1;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName()); params.setSize(release.size);
            params.setOriginatingUri(Uri.parse(release.url));
            if (Build.VERSION.SDK_INT >= 33) params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, release.size)) {
                    byte[] buffer = new byte[16384]; int count; while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count); session.fsync(out);
                }
                String token = "govmap-update:" + UUID.randomUUID();
                Intent result = new Intent(context, UpdateResultReceiver.class).setAction(context.getPackageName() + ".UPDATE_RESULT")
                    .setPackage(context.getPackageName()).setData(Uri.parse(token)).putExtra("govmapSession", id);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent receiver = PendingIntent.getBroadcast(context, id, result, flags);
                synchronized (this) {
                    // Never interrupt an active map with an automatic installation.
                    if (!enabled() || (!manual && visible)) { installer.abandonSession(id); return false; }
                    if (!prefs.edit().putInt("session", id).putString("token", token).putLong("installCode", release.code)
                        .putLong("sessionTime", System.currentTimeMillis()).commit()) throw new IOException("Cannot persist install state");
                    session.commit(receiver.getIntentSender());
                }
            }
            return true;
        } catch (Exception error) {
            if (id != -1) try { installer.abandonSession(id); } catch (RuntimeException ignored) {}
            clearSession(); prefs.edit().putLong("retryAfter", System.currentTimeMillis() + 6 * HOUR).apply();
            installationError("לא ניתן להתקין אוטומטית: " + detail(error));
            status("לחץ לחיצה ארוכה על GovMap להתקנה רגילה", manual); return false;
        }
    }
    void installationResult(Intent result) {
        if (result == null || !prefs.getString("token", "").equals(result.getDataString())
            || result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, result.getIntExtra("govmapSession", -2)) != prefs.getInt("session", -1)) return;
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent action = result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (action == null || !enabled()) { worker.execute(() -> abandon()); return; }
            // Preserve the original Intent, including OS/vendor parcelables and URI grants.
            // Intent.toUri()/parseUri() is not a lossless transport for installer intents.
            confirmationAction = new Intent(action); confirmationSession = prefs.getInt("session", -1);
            prefs.edit().remove("confirmation").putBoolean("confirmationNeeded", true).putString("status", "Android מבקש אישור להתקנת העדכון").commit();
            main.post(() -> showConfirmation(false));
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            clearSession(); prefs.edit().remove("retryAfter").remove("installError").putString("status", "העדכון הותקן בהצלחה").apply();
            worker.execute(() -> { apk.delete(); metadata.delete(); });
        } else {
            clearSession(); prefs.edit().putLong("retryAfter", System.currentTimeMillis() + 6 * HOUR)
                .putString("status", "ההתקנה לא הושלמה. אפשר לנסות שוב בלחיצה ארוכה על GovMap").apply();
            installationError("Android: " + resultName(status) + " (" + status + ")\n" + bounded(result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)));
        }
    }
    private void offerReady() {
        Activity a = current(); if (a == null || dialogOpen || !enabled()) return;
        dialogOpen = true;
        AlertDialog d = new AlertDialog.Builder(a).setTitle("עדכון GovMap מוכן")
            .setMessage("להתקין עכשיו? המפה תיסגר בזמן העדכון.")
            .setPositiveButton("התקן", (dialog, which) -> { if (permission(a)) worker.execute(() -> manualInstall()); })
            .setNegativeButton("אחר כך", null).create();
        d.setOnDismissListener(dialog -> dialogOpen = false); d.show();
    }
    private void showConfirmation(boolean manual) {
        Activity a = current(); int id = prefs.getInt("session", -1);
        boolean needed = prefs.getBoolean("confirmationNeeded", false) || prefs.contains("confirmation");
        if (a == null || !enabled() || compatibilityActive() || dialogOpen || !needed || id == -1
            || (!manual && prefs.getInt("promptedSession", -2) == id)) return;
        dialogOpen = true; prefs.edit().putInt("promptedSession", id).apply();
        AlertDialog d = new AlertDialog.Builder(a).setTitle("אישור עדכון GovMap")
            .setMessage("Android דורש אישור להתקנת העדכון שהורד מ-GitHub.")
            .setPositiveButton("המשך להתקנה", (dialog, which) -> {
                Intent original = confirmationSession == id ? confirmationAction : null;
                if (original == null) { worker.execute(() -> manualInstall()); return; }
                try { a.startActivity(new Intent(original)); }
                catch (Exception error) { installationError("לא ניתן לפתוח אישור Android: " + detail(error)); worker.execute(() -> manualInstall()); }
            }).setNeutralButton("התקנה רגילה", (dialog, which) -> worker.execute(() -> manualInstall()))
            .setNegativeButton("אחר כך", null).create();
        d.setOnDismissListener(dialog -> dialogOpen = false); d.show();
    }

    private boolean compatibilityActive() {
        try { return UpdateApkAccess.active(prefs.getLong("compatibilityCode", 0), prefs.getLong("compatibilityTime", 0), installedCode(), System.currentTimeMillis()); }
        catch (Exception e) { return false; }
    }
    private void finishCompatibility() { prefs.edit().remove("compatibilityCode").remove("compatibilityTime").remove("compatibilityRequest").commit(); }
    /** Explicit user action: launch the ordinary Android APK installer with a read-only content grant. */
    private boolean manualInstall() {
        if (!enabled()) return false;
        // Every caller is an explicit user action. Retrying starts a fresh installer flow,
        // rather than reopening an abandoned security-scan/session confirmation.
        finishCompatibility();
        recoverSession();
        UpdateRelease release = ready(); if (release == null) return false;
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            main.post(() -> { Activity a = current(); if (a != null) permission(a); }); return true;
        }
        abandon();
        final int request = 0x4000 + ((prefs.getInt("lastCompatibilityRequest", 0) + 1) % 0x4000);
        if (!prefs.edit().putLong("compatibilityCode", release.code).putLong("compatibilityTime", System.currentTimeMillis())
            .putInt("compatibilityRequest", request).putInt("lastCompatibilityRequest", request - 0x4000)
            .remove("installError").putString("status", "משלים התקנה באמצעות מתקין Android").commit()) {
            installationError("לא ניתן לשמור מצב התקנה"); return true;
        }
        main.post(() -> {
            Activity a = current();
            if (a == null || !enabled()) { finishCompatibility(); return; }
            try {
                Uri uri = Uri.parse(UpdateApkAccess.URI);
                Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true).putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.getPackageName())
                    .putExtra(Intent.EXTRA_ORIGINATING_URI, Uri.parse(release.url));
                intent.setClipData(ClipData.newRawUri("GovMap update", uri));
                a.startActivityForResult(intent, request);
            } catch (Exception error) { finishCompatibility(); installationError("לא ניתן לפתוח מתקין Android: " + detail(error)); toast("פתיחת ההתקנה נכשלה. פרטים בלחיצה ארוכה על GovMap"); }
        });
        return true;
    }
    void activityResult(int request, int result, Intent data) {
        if (request != prefs.getInt("compatibilityRequest", -1)) return;
        worker.execute(() -> {
            if (request != prefs.getInt("compatibilityRequest", -1)) return;
            try {
                long wanted = prefs.getLong("compatibilityCode", 0);
                if (wanted > 0 && installedCode() >= wanted) {
                    finishCompatibility(); prefs.edit().remove("installError").remove("retryAfter").putString("status", "העדכון הותקן בהצלחה").apply();
                    apk.delete(); metadata.delete(); return;
                }
                finishCompatibility(); prefs.edit().putLong("retryAfter", System.currentTimeMillis() + 6 * HOUR).apply();
                String message = data == null ? "" : bounded(data.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                String installerCode = data != null && data.hasExtra("android.intent.extra.INSTALL_RESULT")
                    ? ", installerCode=" + data.getIntExtra("android.intent.extra.INSTALL_RESULT", 0) : "";
                installationError((result == Activity.RESULT_CANCELED ? "ההתקנה בוטלה או נסגרה לפני השלמה" : "מתקין Android לא השלים את ההתקנה")
                    + " (result=" + result + installerCode + ")" + (message.isEmpty() ? "" : "\n" + message));
                status("הגרסה הישנה עדיין מותקנת. אפשר לנסות התקנה רגילה שוב", true);
            } catch (Exception error) { finishCompatibility(); installationError(detail(error)); }
        });
    }
    private void installationError(String text) { prefs.edit().putString("installError", bounded(text)).apply(); }
    private static String bounded(String text) { if (text == null) return ""; return text.length() > 600 ? text.substring(0, 600) : text; }
    private static String detail(Exception error) { return error.getClass().getSimpleName() + ": " + bounded(error.getMessage()); }
    private static String resultName(int code) {
        switch (code) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "ההתקנה נחסמה";
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "ההתקנה בוטלה";
            case PackageInstaller.STATUS_FAILURE_CONFLICT: return "התנגשות בחבילה מותקנת";
            case PackageInstaller.STATUS_FAILURE_INVALID: return "APK לא תקין";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "בעיית אחסון";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return "הגרסה אינה תואמת למכשיר";
            default: return "ההתקנה נכשלה";
        }
    }
}
