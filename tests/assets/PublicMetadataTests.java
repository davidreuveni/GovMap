package il.david.govmap.dark;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.*;

public final class PublicMetadataTests {
    static final String URL="https://www.govmap.gov.il/api/layers-catalog/catalog?lang=he";
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static final class Queue implements Executor {final ArrayDeque<Runnable> tasks=new ArrayDeque<>();public void execute(Runnable r){tasks.add(r);}void run(){while(!tasks.isEmpty())tasks.removeFirst().run();}}
    static Map<String,String> headers(){Map<String,String> h=new HashMap<>();h.put("content-type","application/json");h.put("cache-control","public, max-age=60");h.put("etag","\"v1\"");return h;}
    static PersistentAssetStore.Download response(int code,Map<String,String> headers,String text){return new PersistentAssetStore.Download(code,headers,new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));}
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("govmap-metadata-test").toFile();AtomicLong now=new AtomicLong(1_800_000_000_000L);Queue queue=new Queue();PublicMetadataCache cache=new PublicMetadataCache(root,queue,now::get);AtomicInteger calls=new AtomicInteger(),version=new AtomicInteger(1);
        PublicMetadataCache.Fetcher fetch=(url,conditional)->{calls.incrementAndGet();if(conditional.containsKey("If-None-Match")&&version.get()==1){check("\"v1\"".equals(conditional.get("If-None-Match")),"ETag validator preserved");return response(304,headers(),"");}return response(200,headers(),"{\"layers\":["+version.get()+"]}");};
        check(cache.get(URL,Collections.emptyMap(),fetch)==null&&calls.get()==0&&queue.tasks.size()==1,"Cold miss returns immediately without a synchronous HTTP probe");queue.run();
        check(cache.get(URL,Collections.emptyMap(),fetch)!=null&&calls.get()==1&&queue.tasks.isEmpty(),"Fresh catalog reuses the stored response");
        check(cache.get(URL.replace("www.govmap.gov.il","WWW.GOVMAP.GOV.IL:443"),Collections.emptyMap(),fetch)!=null&&queue.tasks.isEmpty(),"Equivalent host/port spelling uses the same bounded cache variant");
        now.addAndGet(31_000);check(cache.get(URL,Collections.emptyMap(),fetch)!=null&&queue.tasks.size()==1,"Refresh is scheduled while cached metadata is still fresh");cache.get(URL,Collections.emptyMap(),fetch);check(queue.tasks.size()==1,"Background refreshes are deduplicated");queue.run();check(calls.get()==2,"304 refresh completed");
        PublicMetadataCache reopened=new PublicMetadataCache(root,queue,now::get);check(reopened.get(URL,Collections.emptyMap(),fetch)!=null&&calls.get()==2,"Fresh validated metadata survives reopening the store");
        now.addAndGet(61_000);version.set(2);check(cache.get(URL,Collections.emptyMap(),fetch)==null,"Expired data is not served stale");queue.run();check(new String(cache.get(URL,Collections.emptyMap(),fetch).body,StandardCharsets.UTF_8).contains("[2]"),"Changed catalog is installed for subsequent requests");
        for(Map<String,String> request:Arrays.asList(Collections.singletonMap("Authorization","Bearer test"),Collections.singletonMap("Cookie","session=test"),Collections.singletonMap("Range","bytes=0-1"),Collections.singletonMap("Cache-Control","no-store"))){check(cache.get(URL,request,fetch)==null&&queue.tasks.isEmpty(),"Credential/range/no-store request bypasses both reuse and warming");}
        check(cache.get(URL,Collections.singletonMap("Cache-Control","no-cache"),fetch)==null,"Explicit reload does not get an old cached response");queue.run();
        check(!PublicMetadataCache.eligible(URL+"&token=x")&&!PublicMetadataCache.eligible(URL.replace("lang=he","lang=fr"))&&!PublicMetadataCache.eligible(URL.replace("layers-catalog/catalog","users-management/profile")),"Only the exact anonymous public catalog variants qualify");
        Map<String,String> h=headers();h.put("age","59");check(PublicMetadataCache.freshLifetime(h,now.get())==1000,"Origin Age shortens remaining freshness");
        h.put("age","3599");h.put("cache-control","public,max-age=3600");check(PublicMetadataCache.freshLifetime(h,now.get())==1000,"App cap does not reset origin age");
        for(String policy:new String[]{"private,max-age=60","no-store","no-cache,max-age=60","max-age=0","max-age=oops","max-age=60,max-age=120"}){h=headers();h.put("cache-control",policy);check(PublicMetadataCache.freshLifetime(h,now.get())==0,"Conservative response directive handling: "+policy);}
        h=headers();h.put("vary","Cookie");check(PublicMetadataCache.freshLifetime(h,now.get())==0,"Vary: Cookie cannot enter anonymous cache");
        h=headers();h.put("set-cookie","session=x");check(PublicMetadataCache.freshLifetime(h,now.get())==0,"Cookie-setting responses are not retained");
        h=headers();h.remove("cache-control");check(PublicMetadataCache.freshLifetime(h,now.get())==30_000,"Anonymous JSON without explicit freshness has only a short heuristic lifetime");
        h.put("date",DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now.get()).atZone(ZoneOffset.UTC)));h.put("expires",DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now.get()+10_000).atZone(ZoneOffset.UTC)));check(PublicMetadataCache.freshLifetime(h,now.get())==10_000,"Explicit Expires is honored");
        Queue deniedQueue=new Queue();PublicMetadataCache denied=new PublicMetadataCache(new File(root,"denied"),deniedQueue,now::get);PublicMetadataCache.Fetcher forbidden=(url,validators)->response(403,headers(),"");
        check(denied.get(URL,Collections.emptyMap(),forbidden)==null,"Access denial never blocks the browser's request");deniedQueue.run();denied.get(URL,Collections.emptyMap(),forbidden);check(deniedQueue.tasks.isEmpty(),"Denied anonymous endpoint is backed off instead of repeatedly probed");
        // A freshly cached response becomes unusable when a refresh forbids storage.
        now.addAndGet(31_000);PublicMetadataCache.Fetcher privateRefresh=(url,validators)->{Map<String,String> privateHeaders=headers();privateHeaders.put("cache-control","no-store");return response(200,privateHeaders,"not json");};
        cache.get(URL,Collections.emptyMap(),privateRefresh);queue.run();check(cache.get(URL,Collections.emptyMap(),privateRefresh)==null,"New no-store policy invalidates the old anonymous entry even if the new body is not JSON");
        System.out.println("PASS: nonblocking misses; bounded freshness/Age/Expires; ETag/background refresh; persisted reuse; credential/private/expired bypass; access-denial backoff.");
    }
}
