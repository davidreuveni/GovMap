package il.david.govmap.dark;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

public final class DeveloperUtilitiesTests {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static final class Response extends HttpURLConnection {
        int status=200,reads;long length=-1;boolean closed;String redirect,encoding;byte[] body=new byte[0];
        Response()throws Exception{super(new URL(UpdateRelease.FEED));}
        @Override public void connect(){}
        @Override public void disconnect(){closed=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public int getResponseCode(){return status;}
        @Override public long getContentLengthLong(){return length;}
        @Override public String getHeaderField(String key){return "Location".equals(key)?redirect:"Content-Encoding".equals(key)?encoding:null;}
        @Override public InputStream getInputStream(){reads++;return new ByteArrayInputStream(body);}
    }
    private static String metadata()throws Exception{return new JSONObject().put("formatVersion",1).put("packageName",UpdateRelease.PACKAGE)
        .put("versionCode",17).put("versionName","1.10.04").put("size",1234).put("minSdk",26).put("sha256","a".repeat(64))
        .put("apkUrl","https://github.com/"+UpdateRelease.REPOSITORY+"/releases/download/v1.10.04/GovMap-Dark.apk").toString();}
    public static void main(String[] ignored)throws Exception{
        check(DeveloperFacts.fixAgeMs(2_000_000_000L,5_000_000_000L,0,0)==3000);
        check(DeveloperFacts.fixAgeMs(6_000_000_000L,5_000_000_000L,100,1000)==-1);
        check(DeveloperFacts.fixAgeMs(0,5_000_000_000L,100,1100)==1000);
        check(DeveloperFacts.fixAgeMs(0,0,1200,1100)==-1);check(DeveloperFacts.fixAgeMs(0,0,0,1100)==-1);
        check(DeveloperFacts.time(0).equals("not recorded"));
        Path root=Files.createTempDirectory("govmap-storage-test-");
        try{
            Path folder=Files.createDirectory(root.resolve("cache"));Files.write(folder.resolve("a"),new byte[10]);
            Path nested=Files.createDirectory(folder.resolve("nested"));Files.write(nested.resolve("b"),new byte[20]);
            Path outside=root.resolve("private");Files.write(outside,new byte[1000]);Files.createSymbolicLink(folder.resolve("link"),outside);
            Files.createSymbolicLink(nested.resolve("cycle"),folder);
            DeveloperStorage.Totals total=DeveloperStorage.scan(folder.toFile());check(total.present&&!total.partial&&total.files==2&&total.bytes==30);
            check(DeveloperStorage.scan(folder.toFile(),1,12).partial);check(DeveloperStorage.scan(folder.toFile(),100,1).partial);
            check(!DeveloperStorage.scan(root.resolve("missing").toFile()).present);
            Path bodies=Files.createDirectory(root.resolve("assets"));Files.write(bodies.resolve("a".repeat(64)+".body"),new byte[15]);
            Files.write(bodies.resolve("a".repeat(64)+".json"),new byte[20]);Files.createSymbolicLink(bodies.resolve("b".repeat(64)+".body"),outside);
            DeveloperStorage.Totals assets=DeveloperStorage.bodies(bodies.toFile());check(assets.files==1&&assets.bytes==15);
        }finally{try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
        Response map=new Response();map.status=403;
        DeveloperNetwork.Result denied=DeveloperNetwork.check(false,url->map);check(denied.status==403&&denied.text().contains("server response")&&map.reads==0&&map.closed);
        Response rootPage=new Response();DeveloperNetwork.Result reachable=DeveloperNetwork.check(false,url->rootPage);check(reachable.error.isEmpty()&&rootPage.reads==0&&rootPage.closed);
        Response feed=new Response();feed.body=metadata().getBytes(StandardCharsets.UTF_8);
        List<String> requests=new ArrayList<>();DeveloperNetwork.Result valid=DeveloperNetwork.check(true,url->{requests.add(url.toString());return feed;});
        check(valid.release!=null&&valid.release.code==17&&feed.closed&&requests.equals(Collections.singletonList(UpdateRelease.FEED)));
        check(feed.getRequestProperty("Authorization")==null&&feed.getRequestProperty("Cookie")==null);
        Response evil=new Response();evil.status=302;evil.redirect="http://github.com/unsafe";int[] calls={0};
        DeveloperNetwork.Result blocked=DeveloperNetwork.check(true,url->{calls[0]++;return evil;});check(!blocked.error.isEmpty()&&calls[0]==1&&evil.closed);
        Response tooBig=new Response();tooBig.length=20000;check(DeveloperNetwork.check(true,url->tooBig).release==null&&tooBig.reads==0&&tooBig.closed);
        Response unlimited=new Response();unlimited.body=new byte[20000];check(DeveloperNetwork.check(true,url->unlimited).release==null&&unlimited.closed);
        Response gzip=new Response();gzip.encoding="gzip";check(DeveloperNetwork.check(true,url->gzip).release==null&&gzip.reads==0);
        DeveloperNetwork.Result failure=DeveloperNetwork.check(false,url->{throw new UnknownHostException("private URL token=secret");});
        check(failure.status==0&&failure.error.contains("UnknownHostException")&&!failure.text().contains("secret"));
        check(!DeveloperNetwork.trusted(new URL("https://evil.govmap.gov.il.attacker.test/"),false));
        check(!DeveloperNetwork.trusted(new URL("https://user:password@www.govmap.gov.il/"),false));
        long run=DeveloperTrace.beginStartup();DeveloperTrace.mark("Latest content visible");check(DeveloperTrace.startupSummary().contains("First content visible: not observed"));
        DeveloperTrace.mark("Latest page start");DeveloperTrace.mark("Latest content visible");DeveloperTrace.mark("Latest load finished");
        String url="https://www.govmap.gov.il/assets/main-deadbeef123456.js";
        DeveloperTrace.served(url,true,10);DeveloperTrace.downloaded(url,20,40,5,true,true);DeveloperTrace.endStartup(run,"complete");
        String snapshot=DeveloperTrace.startupSummary();check(snapshot.contains("Permanent-store response delta: 1")&&snapshot.contains("wire body bytes: 20"));
        DeveloperTrace.downloaded(url,20,40,5,true,true);check(snapshot.equals(DeveloperTrace.startupSummary()));
        long next=DeveloperTrace.beginStartup();DeveloperTrace.endStartup(run,"old callback");check(DeveloperTrace.startupSummary().contains("running"));
        DeveloperTrace.endStartup(next,"partial");String counters=DeveloperTrace.cacheSummary();DeveloperTrace.event("ERROR previous");
        check(DeveloperTrace.recentErrors().contains("ERROR previous"));DeveloperTrace.freshLog();check(DeveloperTrace.recentErrors().equals("none recorded\n"));
        check(DeveloperTrace.cacheSummary().equals(counters)&&!DeveloperTrace.log().contains("ERROR previous"));
        System.out.println("Developer utilities passed: fix freshness, bounded non-destructive storage scans, safe public probes, reload isolation and fresh-log reset.");
    }
}
