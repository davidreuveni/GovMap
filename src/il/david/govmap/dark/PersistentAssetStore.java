package il.david.govmap.dark;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import org.json.*;

/** Private application data for public, versioned GovMap build assets. Never stores API data. */
final class PersistentAssetStore {
    static final long MAX_ASSET=32L*1024*1024, MAX_STORE=128L*1024*1024;
    private final File directory;
    private final long budget;
    private final ConcurrentHashMap<String,Object> locks=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,String> verified=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Flight> inFlight=new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor DOWNLOADS=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(32),r->{Thread t=new Thread(r,"govmap-asset-download");t.setDaemon(true);return t;},new ThreadPoolExecutor.CallerRunsPolicy());
    static { DOWNLOADS.allowCoreThreadTimeOut(true); }
    interface Fetcher { Download fetch(String url)throws IOException; }
    static class Download implements Closeable {
        final int status;final Map<String,String> headers;final InputStream data;
        Download(int status,Map<String,String> headers,InputStream data){this.status=status;this.headers=headers;this.data=data;}
        public void close()throws IOException{data.close();}
    }
    static final class Entry implements Closeable {
        final InputStream data;final String mime,encoding;final Map<String,String> headers;
        final boolean fromPersistent;final long bytes;
        Entry(File file,JSONObject meta,boolean hit)throws IOException{this(new FileInputStream(file),meta,hit,file.length());}
        Entry(InputStream input,JSONObject meta,boolean hit,long length){
            data=input;mime=meta.optString("mime");encoding=meta.isNull("encoding")?null:meta.optString("encoding");
            headers=new LinkedHashMap<>();JSONObject h=meta.optJSONObject("headers");
            if(h!=null){Iterator<String> keys=h.keys();while(keys.hasNext()){String key=keys.next();headers.put(key,h.optString(key));}}
            bytes=length;fromPersistent=hit;
            if(length>=0)headers.put("Content-Length",Long.toString(length));
        }
        public void close()throws IOException{data.close();}
    }
    PersistentAssetStore(File directory){this(directory,MAX_STORE);}
    PersistentAssetStore(File directory,long budget){this.directory=directory;this.budget=budget;}
    static boolean eligible(String url){
        try{
            URI u=new URI(url);String host=u.getHost(),path=u.getRawPath();
            return "https".equalsIgnoreCase(u.getScheme())&&(u.getPort()==-1||u.getPort()==443)&&u.getUserInfo()==null&&u.getRawQuery()==null&&u.getRawFragment()==null
                &&("www.govmap.gov.il".equalsIgnoreCase(host)||"govmap.gov.il".equalsIgnoreCase(host))
                &&path!=null&&path.matches("/assets/[A-Za-z0-9_/-]+-[A-Za-z0-9_-]{8,}\\.(js|css|woff2?|ttf|otf|png|jpe?g|webp|svg|ico)");
        }catch(Exception e){return false;}
    }
    Entry get(String url,Fetcher fetcher)throws IOException{
        if(!eligible(url))return null;
        String key=AssetUtil.hash(url);Object lock=locks.computeIfAbsent(key,k->new Object());
        synchronized(lock){
            Flight pending=inFlight.get(key);if(pending!=null){Entry entry=pending.entry();DeveloperTrace.served(url,false,entry.bytes);return entry;}
            if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Asset directory unavailable");
            File body=new File(directory,key+".body"),metaFile=new File(directory,key+".json");
            JSONObject saved=read(metaFile);
            if(valid(body,saved,url,key)){
                body.setLastModified(System.currentTimeMillis());verified.put(key,stamp(body));Entry entry=new Entry(body,saved,true);DeveloperTrace.served(url,true,entry.bytes);return entry;
            }
            body.delete();metaFile.delete();verified.remove(key);
            long started=System.nanoTime();Download download=fetcher.fetch(url);
            Flight created=null;
            try{
                Map<String,String> h=lower(download.headers);String cc=h.getOrDefault("cache-control","").toLowerCase(Locale.ROOT);
                if(download.status!=200||h.containsKey("set-cookie")||cc.contains("no-store")||cc.contains("private")||h.getOrDefault("vary","").toLowerCase(Locale.ROOT).matches(".*(cookie|authorization|\\*).*"))throw new IOException("Not a public reusable asset");
                String type=h.getOrDefault("content-type","");String mime=type.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
                if(!mimeMatches(url,mime))throw new IOException("Unexpected asset content type");
                String encoding=null;for(String item:type.split(";")){if(item.trim().toLowerCase(Locale.ROOT).startsWith("charset="))encoding=item.trim().substring(8).replace("\"","").trim();}
                long expected=number(h.get("content-length"));if(expected>MAX_ASSET||expected>budget)throw new IOException("Asset size limit");
                String compression=h.getOrDefault("content-encoding","identity").trim().toLowerCase(Locale.ROOT);
                if(!Arrays.asList("","identity","gzip").contains(compression))throw new IOException("Unsupported content encoding");
                JSONObject headers=new JSONObject();
                for(String name:new String[]{"content-type","cache-control","expires","etag","last-modified","access-control-allow-origin","cross-origin-resource-policy","content-security-policy","x-content-type-options"})if(h.containsKey(name))AssetUtil.put(headers,name,h.get(name));
                JSONObject meta=AssetUtil.obj("url",url,"mime",mime,"encoding",encoding,"headers",headers);
                Flight flight=new Flight(key,lock,body,metaFile,meta,download,expected,"gzip".equals(compression),started);
                created=flight;
                inFlight.put(key,flight);Entry entry=flight.entry();
                DeveloperTrace.started(url,expected,started);DOWNLOADS.execute(flight::produce);DeveloperTrace.served(url,false,entry.bytes);return entry;
            }catch(IOException|RuntimeException e){if(created!=null){inFlight.remove(key,created);try{created.output.close();}catch(IOException ignored){}created.temporary.delete();}try{download.close();}catch(IOException ignored){}throw e;}
        }
    }
    private final class Flight {
        final String key;final Object lock;final File body,metaFile,temporary,metaTemporary;final JSONObject meta;
        final Download download;final long expected,started;final boolean gzip;final FileOutputStream output;
        long published;boolean done;IOException failure;
        Flight(String key,Object lock,File body,File metaFile,JSONObject meta,Download download,long expected,boolean gzip,long started)throws IOException{
            this.key=key;this.lock=lock;this.body=body;this.metaFile=metaFile;this.meta=meta;this.download=download;this.expected=expected;this.gzip=gzip;this.started=started;
            temporary=new File(directory,key+"."+UUID.randomUUID()+".part");metaTemporary=new File(directory,temporary.getName()+".json");output=new FileOutputStream(temporary);
        }
        Entry entry()throws IOException{
            // Called under the URL lock, which also protects the final atomic rename.
            FileInputStream reader=new FileInputStream(temporary.exists()?temporary:body);
            return new Entry(new InputStream(){
                long position;boolean closed;
                @Override public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
                @Override public int read(byte[] bytes,int offset,int length)throws IOException{
                    if(bytes==null)throw new NullPointerException();if(offset<0||length<0||length>bytes.length-offset)throw new IndexOutOfBoundsException();
                    synchronized(Flight.this){
                        if(closed)throw new IOException("Stream closed");if(length==0)return 0;
                        while(position>=published&&!done&&!closed){try{Flight.this.wait();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}}
                        if(closed)throw new IOException("Stream closed");if(failure!=null)throw new IOException("Asset download incomplete",failure);
                        if(position>=published)return -1;
                        int n=reader.read(bytes,offset,(int)Math.min(length,published-position));if(n<0)throw new EOFException();position+=n;return n;
                    }
                }
                @Override public int available()throws IOException{synchronized(Flight.this){if(closed)throw new IOException("Stream closed");return (int)Math.min(Integer.MAX_VALUE,published-position);}}
                @Override public void close()throws IOException{synchronized(Flight.this){if(closed)return;closed=true;reader.close();Flight.this.notifyAll();}}
            },meta,false,gzip?-1:expected);
        }
        void produce(){
            boolean complete=false,durable=false,stored=false;long size=0;MessageDigest digest=sha();IOException error=null;
            CountingStream raw=new CountingStream(download.data);
            try{
                InputStream decoded=gzip?new GZIPInputStream(raw):raw;
                byte[] buffer=new byte[65536];int n;
                while((n=decoded.read(buffer))!=-1){
                    if(n==0)continue;size+=n;if(size>MAX_ASSET||size>budget)throw new IOException("Asset size limit");
                    output.write(buffer,0,n);digest.update(buffer,0,n);
                    synchronized(this){published=size;notifyAll();}
                }
                if(size==0||(expected>=0&&raw.count!=expected))throw new IOException("Incomplete asset response");
                complete=true;output.getFD().sync();durable=true;
            }catch(IOException e){if(!complete)error=e;}catch(RuntimeException e){error=new IOException("Asset transfer failed",e);}
            finally{try{output.close();}catch(IOException e){durable=false;}try{download.close();}catch(IOException ignored){}}
            if(complete&&durable){
                try{
                    synchronized(lock){
                    AssetUtil.put(meta,"bytes",size);AssetUtil.put(meta,"sha256",hex(digest.digest()));AssetUtil.put(meta,"downloadedAtEpochMs",System.currentTimeMillis());
                    try(FileOutputStream out=new FileOutputStream(metaTemporary)){out.write(meta.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
                        Files.move(temporary.toPath(),body.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                        Files.move(metaTemporary.toPath(),metaFile.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                        verified.put(key,stamp(body));stored=true;
                    }
                }catch(IOException|RuntimeException ignored){} // A cache-commit failure must not discard a valid network response.
            }
            synchronized(lock){
                if(!stored){body.delete();metaFile.delete();verified.remove(key);}temporary.delete();metaTemporary.delete();
                inFlight.remove(key,this);if(stored)try{prune(key);}catch(RuntimeException ignored){}
                synchronized(this){failure=error;done=true;notifyAll();}
            }
            DeveloperTrace.downloaded(meta.optString("url"),raw.count,size,(System.nanoTime()-started)/1000000,complete,stored);
        }
    }
    private boolean valid(File body,JSONObject meta,String url,String key){
        if(meta==null||!url.equals(meta.optString("url"))||!body.isFile()||body.length()!=meta.optLong("bytes",-1)||body.length()>MAX_ASSET||body.length()>budget)return false;
        String stamp=stamp(body);if(stamp.equals(verified.get(key)))return true;
        try(InputStream in=new FileInputStream(body)){
            MessageDigest digest=sha();byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);
            if(!hex(digest.digest()).equals(meta.optString("sha256")))return false;
            verified.put(key,stamp);return true;
        }catch(IOException e){return false;}
    }
    private synchronized void prune(String keep){
        File[] files=directory.listFiles((d,n)->n.length()==69&&n.endsWith(".body"));if(files==null)return;
        long size=0;for(File f:files)size+=f.length();Arrays.sort(files,Comparator.comparingLong(File::lastModified));int count=files.length;
        for(File f:files){if(size<=budget&&count<=256)break;String key=f.getName().substring(0,64);if(key.equals(keep)||inFlight.containsKey(key))continue;long bytes=f.length();if(f.delete()){size-=bytes;count--;new File(directory,key+".json").delete();verified.remove(key);}}
        File[] leftovers=directory.listFiles();if(leftovers!=null)for(File f:leftovers)if(f.getName().contains(".part")&&System.currentTimeMillis()-f.lastModified()>86400000L)f.delete();
    }
    private static JSONObject read(File f){if(!f.isFile()||f.length()>65536)return null;try{return new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}catch(Exception e){return null;}}
    private static String stamp(File f){return f.length()+":"+f.lastModified();}
    private static long number(String value){try{return Long.parseLong(value);}catch(Exception e){return -1;}}
    private static Map<String,String> lower(Map<String,String> input){Map<String,String> out=new HashMap<>();for(Map.Entry<String,String> e:input.entrySet())if(e.getKey()!=null)out.put(e.getKey().toLowerCase(Locale.ROOT),e.getValue());return out;}
    private static boolean mimeMatches(String url,String type){
        if(url.endsWith(".js"))return Arrays.asList("text/javascript","application/javascript","application/x-javascript","text/ecmascript","application/ecmascript").contains(type);
        if(url.endsWith(".css"))return "text/css".equals(type);
        if(url.matches(".*\\.(woff2?|ttf|otf)"))return type.startsWith("font/")||Arrays.asList("application/font-woff","application/font-sfnt","application/vnd.ms-opentype","application/octet-stream").contains(type);
        return type.startsWith("image/");
    }
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static final class CountingStream extends FilterInputStream {
        long count;CountingStream(InputStream in){super(in);}
        @Override public int read()throws IOException{int b=in.read();if(b>=0)count++;return b;}
        @Override public int read(byte[] b,int o,int n)throws IOException{int k=in.read(b,o,n);if(k>0)count+=k;return k;}
    }
}
