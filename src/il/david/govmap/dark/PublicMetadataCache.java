package il.david.govmap.dark;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import org.json.*;

/** Short, anonymous-only cache for the public layer catalog. Misses never block WebView. */
final class PublicMetadataCache {
    static final long MAX_FRESH_MS=60_000, DEFAULT_FRESH_MS=30_000;
    static final int MAX_BODY=2*1024*1024;
    interface Fetcher { PersistentAssetStore.Download fetch(String url,Map<String,String> validators)throws IOException; }
    interface Clock { long now(); }
    static final class Cached {
        final byte[] body;final Map<String,String> headers;final long storedAt,freshUntil;final String etag;
        Cached(byte[] body,Map<String,String> headers,long storedAt,long freshUntil){this.body=body;this.headers=headers;this.storedAt=storedAt;this.freshUntil=freshUntil;etag=headers.getOrDefault("etag","");}
    }
    private final File directory;private final Executor worker;private final Clock clock;
    private final Map<String,Cached> memory=new HashMap<>();
    private final Map<String,Long> retryAfter=new HashMap<>();private final Set<String> refreshing=new HashSet<>();
    PublicMetadataCache(File directory){this(directory,background(),System::currentTimeMillis);}
    PublicMetadataCache(File directory,Executor worker,Clock clock){this.directory=directory;this.worker=worker;this.clock=clock;}
    private static Executor background(){ThreadPoolExecutor pool=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new LinkedBlockingQueue<>(),r->{Thread t=new Thread(r,"govmap-public-metadata");t.setDaemon(true);return t;});pool.allowCoreThreadTimeOut(true);return pool;}
    static boolean eligible(String url){
        try{URI u=new URI(url);return "https".equalsIgnoreCase(u.getScheme())&&(u.getPort()==-1||u.getPort()==443)&&u.getUserInfo()==null&&u.getRawFragment()==null
            &&("www.govmap.gov.il".equalsIgnoreCase(u.getHost())||"govmap.gov.il".equalsIgnoreCase(u.getHost()))
            &&"/api/layers-catalog/catalog".equals(u.getRawPath())&&u.getRawQuery()!=null&&u.getRawQuery().matches("lang=(he|en|ar|ru)");}catch(Exception e){return false;}
    }
    Cached get(String url,Map<String,String> requestHeaders,Fetcher fetcher){
        if(!eligible(url))return null;
        URI address=URI.create(url);url="https://"+address.getHost().toLowerCase(Locale.ROOT)+address.getRawPath()+"?"+address.getRawQuery();
        Map<String,String> request=lower(requestHeaders);
        if(request.containsKey("authorization")||request.containsKey("range")||!request.getOrDefault("cookie","").isEmpty()||request.getOrDefault("cache-control","").toLowerCase(Locale.ROOT).contains("no-store"))return null;
        Cached cached;long now=clock.now();String key=AssetUtil.hash(url);
        synchronized(this){cached=memory.get(key);if(cached==null){cached=read(key,url);if(cached!=null)memory.put(key,cached);}}
        String cc=request.getOrDefault("cache-control","").toLowerCase(Locale.ROOT);
        boolean forced=cc.contains("no-cache")||cc.matches(".*max-age\\s*=\\s*0(?:[^0-9].*|$)")||"no-cache".equalsIgnoreCase(request.get("pragma"));
        boolean fresh=cached!=null&&now>=cached.storedAt&&now<cached.freshUntil;
        if(!fresh||forced||now-cached.storedAt>=(cached.freshUntil-cached.storedAt)/2)refresh(key,url,cached,fetcher,now);
        return fresh&&!forced?cached:null;
    }
    private void refresh(String key,String url,Cached old,Fetcher fetcher,long now){
        synchronized(this){if(refreshing.contains(key)||now<retryAfter.getOrDefault(key,0L))return;refreshing.add(key);}
        try{worker.execute(()->{
            try{
                Map<String,String> validators=new HashMap<>();if(old!=null&&!old.etag.isEmpty()&&old.etag.length()<1024&&!old.etag.contains("\r")&&!old.etag.contains("\n"))validators.put("If-None-Match",old.etag);
                try(PersistentAssetStore.Download response=fetcher.fetch(url,validators)){
                    Map<String,String> headers=lower(response.headers);byte[] body;
                    if(response.status==304&&old!=null){Map<String,String> merged=new HashMap<>(old.headers);merged.remove("age");merged.remove("date");merged.putAll(headers);headers=merged;body=old.body;}
                    else if(response.status==200){if(freshLifetime(headers,clock.now())<=0){invalidate(key,60_000);return;}body=readBody(response,headers);}
                    else {invalidate(key,response.status>=400&&response.status<500?600_000:30_000);return;}
                    long savedAt=clock.now(),lifetime=freshLifetime(headers,savedAt);
                    if(lifetime<=0){invalidate(key,60_000);return;}
                    Map<String,String> safe=new LinkedHashMap<>();for(String name:new String[]{"content-type","cache-control","date","age","etag","expires","last-modified","access-control-allow-origin","cross-origin-resource-policy","x-content-type-options"})if(headers.containsKey(name))safe.put(name,headers.get(name));
                    Cached result=new Cached(body,safe,savedAt,savedAt+lifetime);write(key,url,result);
                    synchronized(this){memory.put(key,result);retryAfter.remove(key);}
                }
            }catch(Exception e){synchronized(this){retryAfter.put(key,clock.now()+30_000);}}
            finally{synchronized(this){refreshing.remove(key);}}
        });}catch(RuntimeException e){synchronized(this){refreshing.remove(key);}}
    }
    static long freshLifetime(Map<String,String> headers,long now){
        String type=headers.getOrDefault("content-type","").toLowerCase(Locale.ROOT);
        if(!(type.startsWith("application/json")||type.split(";",2)[0].endsWith("+json"))||headers.containsKey("set-cookie"))return 0;
        String vary=headers.getOrDefault("vary","").trim().toLowerCase(Locale.ROOT);if(!vary.isEmpty()&&!"accept-encoding".equals(vary))return 0;
        Map<String,String> directives=new HashMap<>();
        for(String raw:headers.getOrDefault("cache-control","").split(",")){String[] p=raw.trim().toLowerCase(Locale.ROOT).split("=",2);String name=p[0].trim();if(name.isEmpty())continue;if(directives.containsKey(name))return 0;directives.put(name,p.length==1?"":p[1].trim().replace("\"",""));}
        for(String forbidden:new String[]{"no-store","private","no-cache","must-understand"})if(directives.containsKey(forbidden))return 0;
        long lifetime=DEFAULT_FRESH_MS,date=date(headers.get("date"));
        if(directives.containsKey("max-age")){long seconds=seconds(directives.get("max-age"));if(seconds<0)return 0;lifetime=seconds*1000;}
        else if(headers.containsKey("expires")){long expires=date(headers.get("expires"));if(expires<0)return 0;lifetime=expires-(date<0?now:date);}
        long age=seconds(headers.getOrDefault("age","0"));if(age<0)return 0;
        long currentAge=Math.max(age*1000,date<0?0:Math.max(0,now-date));
        return Math.max(0,Math.min(MAX_FRESH_MS,lifetime-currentAge));
    }
    private static byte[] readBody(PersistentAssetStore.Download response,Map<String,String> headers)throws IOException{
        long length=number(headers.get("content-length"));if(length>MAX_BODY)throw new IOException("Catalog size limit");
        String encoding=headers.getOrDefault("content-encoding","identity").toLowerCase(Locale.ROOT);if(!Arrays.asList("","identity","gzip").contains(encoding))throw new IOException("Unsupported catalog encoding");
        InputStream input="gzip".equals(encoding)?new GZIPInputStream(response.data):response.data;ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int n;
        while((n=input.read(buffer))!=-1){if(out.size()+n>MAX_BODY)throw new IOException("Catalog size limit");out.write(buffer,0,n);}
        byte[] body=out.toByteArray();if(!"gzip".equals(encoding)&&length>=0&&length!=body.length)throw new EOFException();
        try{JSONTokener tokener=new JSONTokener(new String(body,StandardCharsets.UTF_8));Object json=tokener.nextValue();if((!(json instanceof JSONObject)&&!(json instanceof JSONArray))||tokener.nextClean()!=0)throw new IOException("Catalog JSON expected");}catch(JSONException e){throw new IOException("Invalid catalog JSON",e);}
        return body;
    }
    private void write(String key,String url,Cached value)throws IOException{
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Metadata cache directory unavailable");
        JSONObject h=new JSONObject();for(Map.Entry<String,String> e:value.headers.entrySet())AssetUtil.put(h,e.getKey(),e.getValue());
        JSONObject data=AssetUtil.obj("url",url,"storedAt",value.storedAt,"freshUntil",value.freshUntil,"headers",h,"body",Base64.getEncoder().encodeToString(value.body),"sha256",AssetUtil.hash(value.body));
        File target=new File(directory,key+".json"),temp=new File(directory,key+".part");
        try{try(FileOutputStream out=new FileOutputStream(temp)){out.write(data.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{temp.delete();}
    }
    private Cached read(String key,String url){
        File file=new File(directory,key+".json");if(!file.isFile()||file.length()>3L*1024*1024)return null;
        try{JSONObject data=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));if(!url.equals(data.optString("url")))return null;
            long start=data.getLong("storedAt"),end=data.getLong("freshUntil");if(end<start||end-start>MAX_FRESH_MS)return null;
            byte[] body=Base64.getDecoder().decode(data.getString("body"));if(body.length>MAX_BODY||!AssetUtil.hash(body).equals(data.optString("sha256")))return null;
            JSONObject h=data.getJSONObject("headers");Map<String,String> headers=new HashMap<>();Iterator<String> keys=h.keys();while(keys.hasNext()){String name=keys.next();headers.put(name,h.optString(name));}return new Cached(body,headers,start,end);
        }catch(Exception e){return null;}
    }
    private void invalidate(String key,long delay){synchronized(this){memory.remove(key);retryAfter.put(key,clock.now()+delay);}new File(directory,key+".json").delete();}
    private static Map<String,String> lower(Map<String,String> headers){Map<String,String> result=new HashMap<>();for(Map.Entry<String,String> e:headers.entrySet())if(e.getKey()!=null)result.put(e.getKey().toLowerCase(Locale.ROOT),e.getValue());return result;}
    private static long number(String value){try{return Long.parseLong(value);}catch(Exception e){return -1;}}
    private static long seconds(String value){long result=number(value);return result<0||result>Long.MAX_VALUE/1000?-1:result;}
    private static long date(String value){try{return ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();}catch(Exception e){return -1;}}
}
