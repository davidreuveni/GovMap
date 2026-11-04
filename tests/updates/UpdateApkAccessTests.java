package il.david.govmap.dark;

import java.io.*;
import java.nio.file.*;
import java.util.Comparator;

public final class UpdateApkAccessTests {
    interface Task { void run() throws Exception; }
    static int cases;
    static void reject(Task task) throws Exception {
        try { task.run(); } catch (Exception expected) { cases++; return; }
        throw new AssertionError("Unauthorized APK access accepted");
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); cases++; }
    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("govmap-apk-grant-");
        try {
            Path base = Files.createDirectory(root.resolve("no_backup"));
            Path folder = Files.createDirectory(base.resolve("app-updates"));
            Path apk = folder.resolve("ready.apk"); Files.write(apk, new byte[]{1,2,3,4});
            check(UpdateApkAccess.file(base.toFile(), UpdateApkAccess.URI, "r").equals(apk.toFile()));
            for (String mode : new String[]{"w","rw","rwt","wa","rt","",null})
                reject(() -> UpdateApkAccess.file(base.toFile(), UpdateApkAccess.URI, mode));
            for (String uri : new String[]{
                "content://il.david.govmap.dark.updates/../signing/release.p12",
                "content://il.david.govmap.dark.updates/%2e%2e/signing/password.txt",
                UpdateApkAccess.URI + "?file=password.txt", UpdateApkAccess.URI + "#x",
                "content://evil.updates/ready.apk", "file:///ready.apk", "https://example.test/ready.apk",
                "content://il.david.govmap.dark.updates/ready.apk/extra", null})
                reject(() -> UpdateApkAccess.file(base.toFile(), uri, "r"));
            // Android may provide /data/user/0 as an alias: the base can legitimately be a symlink.
            Path alias = root.resolve("base-alias"); Files.createSymbolicLink(alias, base);
            check(UpdateApkAccess.file(alias.toFile(), UpdateApkAccess.URI, "r").equals(apk.toFile()));
            Path privateFile = root.resolve("private-key"); Files.write(privateFile, new byte[]{9,9,9});
            Files.delete(apk); Files.createSymbolicLink(apk, privateFile);
            reject(() -> UpdateApkAccess.file(base.toFile(), UpdateApkAccess.URI, "r"));
            Files.delete(apk); Files.write(apk, new byte[0]);
            reject(() -> UpdateApkAccess.file(base.toFile(), UpdateApkAccess.URI, "r"));
            try (RandomAccessFile out = new RandomAccessFile(apk.toFile(), "rw")) { out.setLength(UpdateRelease.MAX_APK + 1); }
            reject(() -> UpdateApkAccess.file(base.toFile(), UpdateApkAccess.URI, "r"));
            Files.delete(apk); Files.delete(folder);
            Files.createSymbolicLink(folder, root);
            reject(() -> UpdateApkAccess.file(base.toFile(), UpdateApkAccess.URI, "r"));
            long now = 10000000;
            check(UpdateApkAccess.active(15, now-1000, 13, now));
            check(!UpdateApkAccess.active(15, now-1000, 15, now));
            check(!UpdateApkAccess.active(15, now-1000, 16, now));
            check(!UpdateApkAccess.active(0, now-1000, 13, now));
            check(!UpdateApkAccess.active(15, now+1000, 13, now));
            check(!UpdateApkAccess.active(15, now-15*60*1000, 13, now));
            check(!UpdateApkAccess.active(15, 0, 13, now));
            System.out.println("APK grant/installer-state checks passed: " + cases);
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
            }
        }
    }
}
