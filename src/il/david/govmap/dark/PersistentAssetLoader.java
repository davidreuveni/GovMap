package il.david.govmap.dark;

import android.content.Context;
import android.webkit.*;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

final class PersistentAssetLoader {
    private static PersistentAssetLoader instance;
    final PersistentAssetStore store;
    private final PublicMetadataCache metadata;
    private PersistentAssetLoader(Context context){
        store=new PersistentAssetStore(new File(context.getApplicationContext().getNoBackupFilesDir(),"persistent-assets"));
        metadata=new PublicMetadataCache(new File(context.getApplicationContext().getNoBackupFilesDir(),"public-layer-metadata"));
        ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request){return intercept(request);}
        });
    }
    static synchronized PersistentAssetLoader get(Context context){if(instance==null)instance=new PersistentAssetLoader(context);return instance;}
    WebResourceResponse intercept(WebResourceRequest request){
        String url=request.getUrl().toString();
        if(request.isForMainFrame()||!"GET".equals(request.getMethod()))return null;
        Map<String,String> supplied=request.getRequestHeaders();
        if(PublicMetadataCache.eligible(url)){
            Map<String,String> copy=new HashMap<>(supplied);
            PublicMetadataCache.Cached cached=metadata.get(url,copy,(address,validators)->download(address,copy,validators));
            if(cached==null)return null;
            Map<String,String> headers=new HashMap<>(cached.headers);headers.put("Cache-Control","no-store");
            headers.remove("cache-control");headers.remove("content-encoding");headers.put("Content-Length",Integer.toString(cached.body.length));
            return new WebResourceResponse("application/json","UTF-8",200,"OK",headers,new ByteArrayInputStream(cached.body));
        }
        if(!PersistentAssetStore.eligible(url))return null;
        for(String key:supplied.keySet())if("range".equalsIgnoreCase(key)||"authorization".equalsIgnoreCase(key))return null;
        try{
            PersistentAssetStore.Entry entry=store.get(url,asset->download(asset,supplied));if(entry==null)return null;
            WebResourceResponse response;
            try{response=new WebResourceResponse(entry.mime,entry.encoding,200,"OK",entry.headers,entry.data);}catch(RuntimeException error){entry.close();throw error;}
            return response;
        }catch(Exception e){return null;}
    }
    static PersistentAssetStore.Download download(String url,Map<String,String> requestHeaders)throws IOException{
        return download(url,requestHeaders,Collections.emptyMap());
    }
    static PersistentAssetStore.Download download(String url,Map<String,String> requestHeaders,Map<String,String> validators)throws IOException{
        if(!PersistentAssetStore.eligible(url)&&!PublicMetadataCache.eligible(url))throw new IOException("Not an eligible resource URL");
        HttpsURLConnection c=(HttpsURLConnection)new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setConnectTimeout(8000);c.setReadTimeout(12000);
        c.setRequestProperty("Accept-Encoding","gzip");
        // Public build artifacts are requested anonymously. Cookie/auth headers are never forwarded.
        for(Map.Entry<String,String> e:requestHeaders.entrySet())if(Arrays.asList("user-agent","accept","accept-language","origin").contains(e.getKey().toLowerCase(Locale.ROOT)))c.setRequestProperty(e.getKey(),e.getValue());
        for(Map.Entry<String,String> e:validators.entrySet())if("If-None-Match".equals(e.getKey()))c.setRequestProperty(e.getKey(),e.getValue());
        try{
            int status=c.getResponseCode();
            Map<String,String> headers=new LinkedHashMap<>();for(Map.Entry<String,List<String>> e:c.getHeaderFields().entrySet())if(e.getKey()!=null&&!e.getValue().isEmpty())headers.put(e.getKey(),String.join(", ",e.getValue()));
            InputStream data=status==200?c.getInputStream():new ByteArrayInputStream(new byte[0]);
            return new PersistentAssetStore.Download(status,headers,data){@Override public void close()throws IOException{try{super.close();}finally{c.disconnect();}}};
        }catch(IOException|RuntimeException e){c.disconnect();throw e;}
    }
}
