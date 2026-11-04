package il.david.govmap.dark;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Two explicit public endpoint probes. No cookies, credentials, APK download or install. */
final class DeveloperNetwork {
    static final String GOVMAP="https://www.govmap.gov.il/";
    static final class Result {
        final String label,error;final int status;final long millis;final UpdateRelease release;
        Result(String label,int status,long millis,String error,UpdateRelease release){this.label=label;this.status=status;this.millis=millis;this.error=error;this.release=release;}
        String text(){return label+": "+(status>0?"HTTP "+status:"no HTTP response")+"; "+millis+" ms"+(error.isEmpty()?"":"; "+error)+(release==null?"":"; valid update feed "+release.version+" ("+release.code+")");}
    }
    static boolean trusted(URL url,boolean feed){
        if(feed)return UpdateRelease.trusted(url);
        String host=url.getHost();return "https".equals(url.getProtocol())&&url.getUserInfo()==null&&url.getRef()==null
            &&(url.getPort()==-1||url.getPort()==443)&&host!=null&&(host.equalsIgnoreCase("govmap.gov.il")||host.toLowerCase(java.util.Locale.ROOT).endsWith(".govmap.gov.il"));
    }
    static Result check(boolean feed){return check(feed,UpdateRelease.HTTPS);}
    static Result check(boolean feed,UpdateRelease.Connections open){
        String label=feed?"GitHub update feed":"GovMap HTTPS root";long start=System.nanoTime();int status=0;String phase="connection";
        try{
            URL url=new URL(feed?UpdateRelease.FEED:GOVMAP);
            for(int redirects=0;redirects<=5;redirects++){
                deadline(start);if(!trusted(url,feed))throw new IOException("Untrusted redirect");
                HttpURLConnection connection=open.open(url);
                try{
                    connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
                    connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("Cache-Control","no-cache");
                    connection.setRequestProperty("User-Agent","GovMap-Developer-Check");
                    status=connection.getResponseCode();deadline(start);
                    if(status==301||status==302||status==303||status==307||status==308){
                        String location=connection.getHeaderField("Location");if(location==null)throw new IOException("Missing redirect");
                        url=new URL(url,location);continue;
                    }
                    if(status!=200&&status!=206)return result(label,status,start,"server response received; unexpected status for this probe",null);
                    if(!feed)return result(label,status,start,"",null);
                    phase="metadata validation";
                    if(status!=200||connection.getContentLengthLong()>16384)throw new IOException("Invalid feed response");
                    String encoding=connection.getHeaderField("Content-Encoding");if(encoding!=null&&!"identity".equalsIgnoreCase(encoding))throw new IOException("Unexpected feed encoding");
                    ByteArrayOutputStream data=new ByteArrayOutputStream();
                    try(InputStream in=connection.getInputStream()){
                        byte[] bytes=new byte[2048];int count;
                        while((count=in.read(bytes))!=-1){deadline(start);if(data.size()+count>16384)throw new IOException("Feed too large");data.write(bytes,0,count);}
                    }
                    return result(label,status,start,"",new UpdateRelease(new String(data.toByteArray(),StandardCharsets.UTF_8)));
                }finally{connection.disconnect();}
            }
            throw new IOException("Too many redirects");
        }catch(Exception e){return result(label,status,start,phase+": "+e.getClass().getSimpleName(),null);}
    }
    private static Result result(String label,int status,long start,String error,UpdateRelease release){return new Result(label,status,Math.max(0,(System.nanoTime()-start)/1000000),error,release);}
    private static void deadline(long start)throws IOException{if(System.nanoTime()-start>30_000_000_000L||Thread.currentThread().isInterrupted())throw new IOException("Probe stopped");}
}
