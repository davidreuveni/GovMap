package il.david.govmap.dark;

import java.io.File;
import java.io.FileNotFoundException;

/** Exact, read-only capability for the staged public APK, never other app data. */
final class UpdateApkAccess {
    static final String URI = "content://il.david.govmap.dark.updates/ready.apk";
    static File file(File noBackup, String uri, String mode) throws Exception {
        if (!URI.equals(uri) || !"r".equals(mode)) throw new SecurityException("Only the update APK may be read");
        File folder = new File(noBackup.getCanonicalFile(), "app-updates");
        File apk = new File(folder, "ready.apk");
        // Reject a symlink escaping this one directory/file, even inside private app storage.
        if (!folder.getCanonicalFile().equals(folder.getAbsoluteFile())
            || !apk.getCanonicalFile().equals(apk.getAbsoluteFile())) throw new SecurityException("Unexpected APK path");
        if (!apk.isFile() || apk.length() < 1 || apk.length() > UpdateRelease.MAX_APK) throw new FileNotFoundException("Update APK unavailable");
        return apk;
    }
    static boolean active(long code, long started, long installed, long now) {
        return code > installed && started > 0 && now >= started && now - started < 15L * 60 * 1000;
    }
}
