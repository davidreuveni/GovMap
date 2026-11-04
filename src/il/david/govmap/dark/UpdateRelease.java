package il.david.govmap.dark;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Public release metadata and bounded, HTTPS-only downloads. No credentials. */
final class UpdateRelease {
    static final String REPOSITORY = "davidreuveni/GovMap";
    static final String PACKAGE = "il.david.govmap.dark";
    static final String CERTIFICATE = "3f3095199892e5e1b42d0e7f9453bfd8277380a7e7cc1acc0098196a6cac7919";
    static final String FEED = "https://github.com/" + REPOSITORY + "/releases/latest/download/update.json";
    static final long MAX_APK = 32L * 1024 * 1024;
    final long code, size;
    final int minSdk;
    final String version, url, sha256, json;

    UpdateRelease(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        this.json = json;
        if (integer(o, "formatVersion") != 1 || !PACKAGE.equals(o.getString("packageName")))
            throw new IOException("Wrong release format or package");
        code = integer(o, "versionCode"); size = integer(o, "size");
        long minimum = integer(o, "minSdk");
        version = o.getString("versionName"); url = o.getString("apkUrl"); sha256 = o.getString("sha256");
        if (code < 1 || code > Integer.MAX_VALUE || size < 1 || size > MAX_APK
            || minimum < 26 || minimum > 100 || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
            || !sha256.matches("[0-9a-f]{64}")
            || !url.equals("https://github.com/" + REPOSITORY + "/releases/download/v" + version + "/GovMap-Dark.apk"))
            throw new IOException("Invalid release metadata");
        minSdk = (int) minimum;
    }
    private static long integer(JSONObject o, String key) throws Exception {
        Object value = o.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IOException("Invalid integer: " + key);
        return ((Number) value).longValue();
    }
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    static final Connections HTTPS = url -> (HttpURLConnection) url.openConnection();
    static final class Transfer {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        final long started = System.nanoTime();
        void cancel() { cancelled = true; HttpURLConnection c = connection; if (c != null) c.disconnect(); }
        void check() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()
                || System.nanoTime() - started > 120L * 1000000000L) throw new IOException("Transfer stopped");
        }
    }
    static boolean trusted(URL url) {
        String host = url.getHost();
        return "https".equals(url.getProtocol()) && url.getUserInfo() == null
            && (url.getPort() == -1 || url.getPort() == 443) && url.getRef() == null
            && ("github.com".equalsIgnoreCase(host) || "release-assets.githubusercontent.com".equalsIgnoreCase(host)
                || "objects.githubusercontent.com".equalsIgnoreCase(host));
    }
    static byte[] fetch(String address, int limit, Transfer transfer, Connections connections) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        download(address, out, limit, -1, null, transfer, connections);
        return out.toByteArray();
    }
    static void download(String address, OutputStream out, long limit, long expectedSize, String expectedHash,
                         Transfer transfer, Connections connections) throws Exception {
        URL url = new URL(address);
        for (int redirects = 0; redirects <= 5; redirects++) {
            transfer.check();
            if (!trusted(url)) throw new IOException("Untrusted release host");
            HttpURLConnection c = connections.open(url); transfer.connection = c;
            try {
                c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(15000);
                c.setUseCaches(false); c.setRequestProperty("Cache-Control", "no-cache");
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("User-Agent", "GovMap-Updater");
                c.setRequestProperty("Accept", "application/octet-stream");
                int status = c.getResponseCode(); transfer.check();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect");
                    url = new URL(url, location); continue;
                }
                if (status != 200) throw new IOException("Release HTTP " + status);
                long declared = c.getContentLengthLong();
                if (declared > limit || (expectedSize >= 0 && declared >= 0 && declared != expectedSize))
                    throw new IOException("Wrong download length");
                String encoding = c.getHeaderField("Content-Encoding");
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new IOException("Unexpected encoding");
                MessageDigest hash = MessageDigest.getInstance("SHA-256"); long count = 0;
                try (InputStream in = c.getInputStream()) {
                    byte[] buffer = new byte[16384]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        transfer.check(); count += n;
                        if (count > limit || (expectedSize >= 0 && count > expectedSize)) throw new IOException("Download too large");
                        hash.update(buffer, 0, n); out.write(buffer, 0, n);
                    }
                }
                transfer.check();
                if ((declared >= 0 && declared != count) || (expectedSize >= 0 && count != expectedSize)
                    || (expectedHash != null && !expectedHash.equals(hex(hash.digest())))) throw new IOException("Incomplete or corrupt release");
                return;
            } finally { transfer.connection = null; c.disconnect(); }
        }
        throw new IOException("Too many release redirects");
    }
    static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[16384]; int count;
            while ((count = in.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        return hex(digest.digest());
    }
    static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return s.toString();
    }
}
