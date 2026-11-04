package il.david.govmap.dark;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Optional real HTTPS integration check. Run separately from offline regression tests. */
public final class LiveAssetTest {
    static String digest(PersistentAssetStore.Entry e)throws Exception{try(PersistentAssetStore.Entry entry=e){MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=entry.data.read(b))!=-1)d.update(b,0,n);return Base64.getEncoder().encodeToString(d.digest());}}
    public static void main(String[] args)throws Exception{
        String url=args.length>0?args[0]:PersistentAssetTests.URL;File directory=Files.createTempDirectory("govmap-real-asset").toFile();AtomicInteger calls=new AtomicInteger();
        Map<String,String> headers=new HashMap<>();headers.put("User-Agent","Mozilla/5.0 (Linux; Android 14; 22101316G; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/155.0.0.0 Mobile Safari/537.36");
        PersistentAssetStore.Fetcher fetcher=asset->{calls.incrementAndGet();return PersistentAssetLoader.download(asset,headers);};
        PersistentAssetStore.Entry first=new PersistentAssetStore(directory).get(url,fetcher);long bytes=first.bytes;String original=digest(first);
        PersistentAssetStore.Entry second=new PersistentAssetStore(directory).get(url,fetcher);
        if(!second.fromPersistent||!original.equals(digest(second))||calls.get()!=1)throw new AssertionError("Real asset was not reused intact without another network call");
        System.out.println("PASS real GovMap HTTPS: body="+bytes+" bytes; fetches="+calls.get()+"; reopened store checksum matches.");
    }
}
