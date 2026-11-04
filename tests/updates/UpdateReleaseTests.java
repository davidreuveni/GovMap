package il.david.govmap.dark;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

public final class UpdateReleaseTests {
    private static int tests;
    interface Task { void run() throws Exception; }
    private static void reject(Task task) throws Exception {
        try { task.run(); } catch (Exception expected) { tests++; return; }
        throw new AssertionError("Unsafe input accepted");
    }
    private static JSONObject metadata() throws Exception {
        return new JSONObject().put("formatVersion", 1).put("packageName", UpdateRelease.PACKAGE)
            .put("versionCode", 14).put("versionName", "1.10.1").put("minSdk", 26).put("size", 4)
            .put("sha256", "a".repeat(64))
            .put("apkUrl", "https://github.com/davidreuveni/GovMap/releases/download/v1.10.1/GovMap-Dark.apk");
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); tests++; }
    private static final class Response extends HttpURLConnection {
        byte[] body; int status = 200; long declared; String location; boolean closed, follow;
        Map<String, String> headers = new HashMap<>();
        Response(String body) throws Exception { super(new URL(UpdateRelease.FEED)); this.body = body.getBytes(StandardCharsets.UTF_8); declared = this.body.length; }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : headers.get(name); }
        @Override public long getContentLengthLong() { return declared; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public void setInstanceFollowRedirects(boolean value) { follow = value; }
        @Override public void disconnect() { closed = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
    public static void main(String[] ignored) throws Exception {
        check(new UpdateRelease(metadata().toString()).code == 14);
        for (Object[] bad : new Object[][] {
            {"formatVersion",2},{"packageName","other.app"},{"versionCode",0},{"versionCode",2147483648L},
            {"versionCode",14.5},{"versionCode","14"},{"size",0},{"size",UpdateRelease.MAX_APK+1},
            {"minSdk",25},{"minSdk",26.1},{"sha256","a".repeat(63)},{"sha256","Z".repeat(64)},
            {"versionName","../1.10.1"},{"apkUrl","http://github.com/davidreuveni/GovMap/releases/download/v1.10.1/GovMap-Dark.apk"},
            {"apkUrl","https://github.com/attacker/GovMap/releases/download/v1.10.1/GovMap-Dark.apk"},
            {"apkUrl","https://github.com/davidreuveni/GovMap/releases/latest/download/GovMap-Dark.apk"},
            {"apkUrl","https://github.com/davidreuveni/GovMap/releases/download/v1.10.1/GovMap-Dark.apk?x=1"}
        }) reject(() -> new UpdateRelease(metadata().put((String) bad[0], bad[1]).toString()));
        for (String url : new String[] {"http://github.com/x", "https://github.com.evil.test/x", "https://github.com@evil.test/x",
            "https://github.com:444/x", "https://user:password@github.com/x", "https://github.com/x#fragment", "https://127.0.0.1/x",
            "https://raw.githubusercontent.com/x", "https://evil.githubusercontent.com/x"}) check(!UpdateRelease.trusted(new URL(url)));
        for (String host : new String[] {"github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"})
            check(UpdateRelease.trusted(new URL("https://"+host+"/asset?token=temporary")));

        Response good = new Response("data");
        String hash = UpdateRelease.hex(MessageDigest.getInstance("SHA-256").digest(good.body));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        UpdateRelease.download(UpdateRelease.FEED, out, 8, 4, hash, new UpdateRelease.Transfer(), url -> good);
        check(Arrays.equals(out.toByteArray(), good.body) && good.closed && !good.follow);
        check("identity".equals(good.getRequestProperty("Accept-Encoding")));
        check(good.getRequestProperty("Authorization") == null && good.getRequestProperty("Cookie") == null);

        Response truncated = new Response("dat"); truncated.declared = 4;
        reject(() -> UpdateRelease.download(UpdateRelease.FEED, new ByteArrayOutputStream(), 8, 4, hash, new UpdateRelease.Transfer(), url -> truncated));
        check(truncated.closed);
        Response corrupt = new Response("oops");
        reject(() -> UpdateRelease.download(UpdateRelease.FEED, new ByteArrayOutputStream(), 8, 4, hash, new UpdateRelease.Transfer(), url -> corrupt));
        Response oversize = new Response("too long"); oversize.declared = -1;
        reject(() -> UpdateRelease.fetch(UpdateRelease.FEED, 4, new UpdateRelease.Transfer(), url -> oversize));
        Response declaredTooBig = new Response("x"); declaredTooBig.declared = 1000;
        reject(() -> UpdateRelease.fetch(UpdateRelease.FEED, 4, new UpdateRelease.Transfer(), url -> declaredTooBig));
        Response gzip = new Response("data"); gzip.headers.put("Content-Encoding", "gzip");
        reject(() -> UpdateRelease.fetch(UpdateRelease.FEED, 8, new UpdateRelease.Transfer(), url -> gzip));
        Response denied = new Response("access denied"); denied.status = 403;
        reject(() -> UpdateRelease.fetch(UpdateRelease.FEED, 1024, new UpdateRelease.Transfer(), url -> denied));
        for (String target : new String[] {"http://github.com/file", "https://evil.test/file", "https://github.com:444/file"}) {
            Response redirect = new Response(""); redirect.status = 302; redirect.location = target;
            int[] calls = {0};
            reject(() -> UpdateRelease.fetch(UpdateRelease.FEED, 8, new UpdateRelease.Transfer(), url -> { calls[0]++; return redirect; }));
            check(calls[0] == 1 && redirect.closed);
        }
        Response loop = new Response(""); loop.status = 302; loop.location = UpdateRelease.FEED;
        int[] hops = {0};
        reject(() -> UpdateRelease.fetch(UpdateRelease.FEED, 8, new UpdateRelease.Transfer(), url -> { hops[0]++; return loop; }));
        check(hops[0] == 6);
        Response redirect = new Response(""); redirect.status = 302; redirect.location = "https://release-assets.githubusercontent.com/asset?token=example";
        Queue<Response> queue = new ArrayDeque<>(); queue.add(redirect); queue.add(new Response("data"));
        check(new String(UpdateRelease.fetch(UpdateRelease.FEED, 8, new UpdateRelease.Transfer(), url -> queue.remove()), StandardCharsets.UTF_8).equals("data"));
        UpdateRelease.Transfer cancel = new UpdateRelease.Transfer(); cancel.cancel();
        reject(() -> UpdateRelease.fetch(UpdateRelease.FEED, 8, cancel, url -> { throw new AssertionError("Cancelled request opened network"); }));
        File tmp = File.createTempFile("govmap-update-test", ".apk");
        try { try (OutputStream f = new FileOutputStream(tmp)) { f.write(good.body); } check(UpdateRelease.hash(tmp).equals(hash)); }
        finally { tmp.delete(); }
        System.out.println("Update release/download checks passed: " + tests);
    }
}
