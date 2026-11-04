package il.david.govmap.dark;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class StreamingAssetTests {
    static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);}
    static final class Gate extends InputStream {
        final byte[] bytes;final int split;int position;final CountDownLatch release=new CountDownLatch(1);
        Gate(byte[] bytes,int split){this.bytes=bytes;this.split=split;}
        @Override public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
        @Override public int read(byte[] b,int off,int len)throws IOException{
            if(len==0)return 0;
            if(position>=split&&position<bytes.length)try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("Test gate timeout");}catch(InterruptedException e){throw new InterruptedIOException();}
            if(position==bytes.length)return -1;int n=Math.min(len,(position<split?split:bytes.length)-position);System.arraycopy(bytes,position,b,off,n);position+=n;return n;
        }
    }
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("govmap-stream-test").toFile();byte[] bytes=new byte[128000];new Random(2).nextBytes(bytes);
        Gate gate=new Gate(bytes,4096);AtomicInteger calls=new AtomicInteger();ExecutorService test=Executors.newSingleThreadExecutor();
        try{
            PersistentAssetStore store=new PersistentAssetStore(root);
            Future<PersistentAssetStore.Entry> start=test.submit(()->store.get(PersistentAssetTests.URL,url->{calls.incrementAndGet();return new PersistentAssetStore.Download(200,PersistentAssetTests.headers(bytes.length),gate);}));
            PersistentAssetStore.Entry first=start.get(1,TimeUnit.SECONDS);byte[] prefix=new byte[4096];
            int count=test.submit(()->first.data.read(prefix)).get(1,TimeUnit.SECONDS);
            check(count==4096&&Arrays.equals(prefix,Arrays.copyOf(bytes,4096)),"First bytes arrive before the download is released");
            PersistentAssetStore.Entry duplicate=store.get(PersistentAssetTests.URL,url->{throw new IOException("Duplicate network request");});
            byte[] secondPrefix=new byte[4096];check(test.submit(()->duplicate.data.read(secondPrefix)).get(1,TimeUnit.SECONDS)==4096,"Concurrent reader receives the live prefix");
            check(PersistentAssetTests.bodies(root)==0,"An unfinished stream is not installed as a cache hit");
            gate.release.countDown();byte[] rest=PersistentAssetTests.consume(first),secondRest=PersistentAssetTests.consume(duplicate);
            check(Arrays.equals(rest,Arrays.copyOfRange(bytes,4096,bytes.length))&&Arrays.equals(rest,secondRest),"Independent readers receive identical remaining content");
            check(calls.get()==1,"One producer serves concurrent readers");
            check(Arrays.equals(bytes,PersistentAssetTests.consume(new PersistentAssetStore(root).get(PersistentAssetTests.URL,url->{throw new IOException("Unexpected second download");}))),"Only a complete stream becomes a reusable file");
        }finally{gate.release.countDown();test.shutdownNow();}
        // Simulate a commit failure after the live body is already available.
        File failed=Files.createTempDirectory("govmap-commit-failure").toFile();Gate commitGate=new Gate(bytes,4096);
        try{
            PersistentAssetStore store=new PersistentAssetStore(failed);PersistentAssetStore.Entry entry=store.get(PersistentAssetTests.URL,url->new PersistentAssetStore.Download(200,PersistentAssetTests.headers(bytes.length),commitGate));
            File target=new File(failed,AssetUtil.hash(PersistentAssetTests.URL)+".json");target.mkdir();Files.write(new File(target,"block").toPath(),new byte[]{1});
            commitGate.release.countDown();check(Arrays.equals(bytes,PersistentAssetTests.consume(entry)),"Cache-commit failure does not discard a complete valid network response");
            check(PersistentAssetTests.bodies(failed)==0,"Failed commit leaves no reusable partial body");
        }finally{commitGate.release.countDown();}
        System.out.println("PASS: first-byte streaming before full download; concurrent independent readers; atomic complete-only reuse; valid response survives commit failure.");
    }
}
