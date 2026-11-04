package il.david.govmap.dark;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.json.*;

public final class PersistentAssetTests {
    static final String URL="https://www.govmap.gov.il/assets/index-DyQH9mBN.js";
    static void check(boolean result,String reason){if(!result)throw new AssertionError(reason);}
    static byte[] consume(PersistentAssetStore.Entry entry)throws Exception{try(PersistentAssetStore.Entry e=entry;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[65536];int n;while((n=e.data.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    static Map<String,String> headers(int bytes){Map<String,String> h=new HashMap<>();h.put("Content-Type","application/javascript; charset=UTF-8");h.put("Content-Length",Integer.toString(bytes));return h;}
    static PersistentAssetStore.Download download(byte[] b){return new PersistentAssetStore.Download(200,headers(b.length),new ByteArrayInputStream(b));}
    static void rejected(File root,String label,PersistentAssetStore.Fetcher fetcher)throws Exception{
        PersistentAssetStore store=new PersistentAssetStore(new File(root,label));boolean threw=false;
        try{consume(store.get(URL,fetcher));}catch(IOException e){threw=true;}
        check(threw,label+" must not be retained");check(bodies(new File(root,label))==0,label+" cannot become a stored asset");
    }
    static int bodies(File directory){File[] files=directory.listFiles((d,n)->n.endsWith(".body"));return files==null?0:files.length;}
    static long bytes(File directory){File[] files=directory.listFiles((d,n)->n.endsWith(".body"));long size=0;if(files!=null)for(File f:files)size+=f.length();return size;}
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("govmap-persistent-test").toFile();File files=new File(root,"no_backup/persistent-assets");
        byte[] body=new byte[1_000_000];new Random(3).nextBytes(body);AtomicInteger calls=new AtomicInteger();
        PersistentAssetStore.Fetcher fetcher=url->{calls.incrementAndGet();return download(body);};
        PersistentAssetStore first=new PersistentAssetStore(files);PersistentAssetStore.Entry downloaded=first.get(URL,fetcher);
        check(!downloaded.fromPersistent,"First use is exactly one recorded download");check(Arrays.equals(body,consume(downloaded)),"First body bytes match");
        File ordinaryCache=new File(root,"cache");ordinaryCache.mkdir();File temp=new File(ordinaryCache,"temporary");Files.write(temp.toPath(),new byte[]{1});temp.delete();ordinaryCache.delete();
        PersistentAssetStore restarted=new PersistentAssetStore(files);PersistentAssetStore.Entry hit=restarted.get(URL,fetcher);
        check(hit.fromPersistent,"Reopened store survives normal cache deletion");check(Arrays.equals(body,consume(hit)),"Stored body matches after a new store instance");check(calls.get()==1,"Second use makes no network fetch");
        String changed=URL.replace("DyQH9mBN","NewHash99");consume(restarted.get(changed,fetcher));check(calls.get()==2,"A new version URL downloads new bytes");
        File saved=new File(files,AssetUtil.hash(URL)+".body");byte[] broken=body.clone();broken[0]^=1;Files.write(saved.toPath(),broken);
        consume(new PersistentAssetStore(files).get(URL,fetcher));check(calls.get()==3,"Corrupt stored data is verified and repaired");
        rejected(root,"truncated",url->{Map<String,String> h=headers(11);return new PersistentAssetStore.Download(200,h,new ByteArrayInputStream(new byte[10]));});
        rejected(root,"interrupted",url->new PersistentAssetStore.Download(200,headers(20),new InputStream(){int read;public int read()throws IOException{if(read++<10)return 1;throw new IOException("cut off");}}));
        for(String policy:new String[]{"private","no-store"})rejected(root,policy,url->{Map<String,String> h=headers(2);h.put("Cache-Control",policy);return new PersistentAssetStore.Download(200,h,new ByteArrayInputStream(new byte[2]));});
        rejected(root,"cookie",url->{Map<String,String> h=headers(2);h.put("Set-Cookie","private=value");return new PersistentAssetStore.Download(200,h,new ByteArrayInputStream(new byte[2]));});
        rejected(root,"vary-auth",url->{Map<String,String> h=headers(2);h.put("Vary","Authorization");return new PersistentAssetStore.Download(200,h,new ByteArrayInputStream(new byte[2]));});
        rejected(root,"html-login",url->{Map<String,String> h=headers(2);h.put("Content-Type","text/html");return new PersistentAssetStore.Download(200,h,new ByteArrayInputStream(new byte[2]));});
        rejected(root,"partial",url->new PersistentAssetStore.Download(206,headers(2),new ByteArrayInputStream(new byte[2])));
        rejected(root,"too-large",url->{Map<String,String> h=headers(2);h.put("Content-Length",Long.toString(PersistentAssetStore.MAX_ASSET+1));return new PersistentAssetStore.Download(200,h,new ByteArrayInputStream(new byte[2]));});
        check(!PersistentAssetStore.eligible("https://www.govmap.gov.il/api/layers-catalog/catalog")&&!PersistentAssetStore.eligible(URL+"?token=x")&&!PersistentAssetStore.eligible(URL.replace("https:","http:"))&&!PersistentAssetStore.eligible(URL.replace("www.govmap.gov.il","evil.example"))&&!PersistentAssetStore.eligible(URL.replace("index-DyQH9mBN","index")),"Only public versioned GovMap asset URLs qualify");
        byte[] text=new byte[100_000];Arrays.fill(text,(byte)'a');ByteArrayOutputStream gzip=new ByteArrayOutputStream();try(GZIPOutputStream g=new GZIPOutputStream(gzip)){g.write(text);}byte[] compressed=gzip.toByteArray();
        PersistentAssetStore zipped=new PersistentAssetStore(new File(root,"gzip"));
        PersistentAssetStore.Entry entry=zipped.get(URL,url->{Map<String,String> h=headers(compressed.length);h.put("Content-Encoding","gzip");h.put("Access-Control-Allow-Origin","*");return new PersistentAssetStore.Download(200,h,new ByteArrayInputStream(compressed));});
        check(entry.bytes==-1&&"UTF-8".equals(entry.encoding),"Gzip decoded length stays unknown until the response is complete");
        check("*".equals(entry.headers.get("access-control-allow-origin"))&&!entry.headers.containsKey("content-encoding")&&!entry.headers.containsKey("Content-Length"),"Decoded response preserves CORS without compressed encoding or length");check(Arrays.equals(text,consume(entry)),"Gzip download stored and served intact");
        try(PersistentAssetStore.Entry savedGzip=new PersistentAssetStore(new File(root,"gzip")).get(URL,url->{throw new IOException("No new download expected");})){check(savedGzip.bytes==text.length,"Completed gzip file has its actual decoded length");}
        PersistentAssetStore small=new PersistentAssetStore(new File(root,"bounded"),10);consume(small.get(URL,url->download(new byte[6])));consume(small.get(changed,url->download(new byte[6])));check(bytes(new File(root,"bounded"))<=10&&bodies(new File(root,"bounded"))==1,"Old versions are pruned at the size limit");
        PersistentAssetStore concurrent=new PersistentAssetStore(new File(root,"concurrent"));AtomicInteger network=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(6);List<Future<?>> futures=new ArrayList<>();
        for(int i=0;i<6;i++)futures.add(pool.submit(()->{try{consume(concurrent.get(URL,url->{network.incrementAndGet();return download(body);}));}catch(Exception e){throw new RuntimeException(e);}}));for(Future<?> f:futures)f.get(10,TimeUnit.SECONDS);pool.shutdown();check(network.get()==1,"Simultaneous WebView/Service Worker requests share one download");
        System.out.println("PASS: persistent reopen/cache-delete/version update; content integrity/atomic partial rejection; gzip lengths/charset/CORS; public-only policy; bounded versions; concurrent deduplication.");
    }
}
