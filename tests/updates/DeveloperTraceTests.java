package il.david.govmap.dark;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

public final class DeveloperTraceTests {
    public static void main(String[] ignored)throws Exception{
        String url="https://www.govmap.gov.il/assets/main-deadbeef123456.js";
        if(!DeveloperTrace.assetName(url).equals("main-deadbeef123456.js"))throw new AssertionError();
        for(String bad:new String[]{url+"?token=secret",url+"#31.123456","https://elsewhere.test/assets/main-deadbeef123456.js"})
            if(!DeveloperTrace.assetName(bad).equals("[resource URL omitted]"))throw new AssertionError();
        String redacted=DeveloperTrace.clean("failure https://example.test/?lat=31.123456 Authorization: Bearer top-secret");
        if(redacted.contains("31.123456")||redacted.contains("top-secret")||redacted.contains("example.test"))throw new AssertionError(redacted);
        DeveloperTrace.mark("Latest content visible");DeveloperTrace.mark("Latest load finished");DeveloperTrace.mark("Latest page start");
        if(DeveloperTrace.summary().contains("Latest load finished:"))throw new AssertionError("Old document timing retained");
        Path temp=Files.createTempDirectory("govmap-diagnostics-");
        try{
            byte[] bytes=new byte[20000];Arrays.fill(bytes,(byte)'x');ByteArrayOutputStream encoded=new ByteArrayOutputStream();
            try(GZIPOutputStream gzip=new GZIPOutputStream(encoded)){gzip.write(bytes);}
            byte[] wire=encoded.toByteArray();int[] fetched={0};
            PersistentAssetStore store=new PersistentAssetStore(temp.toFile());
            PersistentAssetStore.Fetcher fetch=address->{fetched[0]++;Map<String,String> h=new HashMap<>();h.put("Content-Type","application/javascript");h.put("Content-Encoding","gzip");h.put("Content-Length",Integer.toString(wire.length));return new PersistentAssetStore.Download(200,h,new ByteArrayInputStream(wire));};
            try(PersistentAssetStore.Entry e=store.get(url,fetch)){if(e.data.readAllBytes().length!=bytes.length)throw new AssertionError();}
            try(PersistentAssetStore.Entry e=store.get(url,fetch)){if(!e.fromPersistent||e.data.readAllBytes().length!=bytes.length)throw new AssertionError();}
            long deadline=System.nanoTime()+2_000_000_000L;
            while(!DeveloperTrace.summary().contains("Native finished body-transfer records: 1")&&System.nanoTime()<deadline)Thread.yield();
            String summary=DeveloperTrace.summary();
            if(fetched[0]!=1||!summary.contains("Permanent-store responses: 1")||!summary.contains("Observed network body bytes: "+wire.length)
                ||!summary.contains("Decoded download bytes: "+bytes.length)||!summary.contains("saved=true"))throw new AssertionError(summary);
            ExecutorService threads=Executors.newFixedThreadPool(4);
            try{List<Future<?>> jobs=new ArrayList<>();for(int i=0;i<4;i++)jobs.add(threads.submit(()->{for(int j=0;j<500;j++)DeveloperTrace.event("bounded event");}));for(Future<?> job:jobs)job.get();}finally{threads.shutdownNow();}
            if(DeveloperTrace.log().split("\n").length!=301)throw new AssertionError("Journal not bounded to 300 events");
            if(!DeveloperTrace.summary().contains("Observed network body bytes: "+wire.length))throw new AssertionError("Ring eviction changed transfer totals");
        }finally{try(java.util.stream.Stream<Path> files=Files.walk(temp)){for(Path p:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
        System.out.println("Developer diagnostics checks passed: real gzip transfer vs permanent reuse, bounded concurrent journal, redaction, page timing reset.");
    }
}
