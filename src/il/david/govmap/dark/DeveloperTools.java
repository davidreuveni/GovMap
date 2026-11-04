package il.david.govmap.dark;

import android.app.*;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.location.Location;
import android.location.LocationManager;
import android.webkit.WebView;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Long-press tools. Browser timing is sampled only on demand; export is user-selected. */
final class DeveloperTools {
    private static final int SAVE_BASE = 0x2000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"GovMap-developer-export");t.setDaemon(true);return t;});
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"GovMap-developer-network");t.setDaemon(true);return t;});
    private final MainActivity activity;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean busy;
    private boolean networkBusy,startupWaiting,startupSawStart;
    private long startupId;
    DeveloperTools(MainActivity activity){this.activity=activity;context=activity.getApplicationContext();}
    void menu(){
        new AlertDialog.Builder(activity.developerDialogContext()).setTitle("כלי מפתח")
            .setItems(new String[]{"עדכוני אפליקציה","דוח מהירות","שמור לוג","מצב מיקום","סקירת אחסון וקאש","בדיקת טעינה מחדש","בדיקת חיבור","העתק פרטי אבחון","התחל לוג נקי","מצב עדכון מפורט"},(dialog,index)->main.post(()->{
                switch(index){
                    case 0:AppUpdates.get(activity).controls(activity);break;
                    case 1:capture(false);break;
                    case 2:capture(true);break;
                    case 3:location();break;
                    case 4:background("אחסון וקאש",this::cacheInfo,this::cache);break;
                    case 5:startup();break;
                    case 6:connections();break;
                    case 7:copyDebug();break;
                    case 8:DeveloperTrace.freshLog();toast("לוג חדש התחיל");break;
                    case 9:updateDetails(false);break;
                }
            })).setNegativeButton("סגור",null).show();
    }
    private boolean alive(){return !activity.isFinishing()&&!activity.isDestroyed();}
    private boolean foreground(){return alive()&&activity.developerForeground();}
    private void capture(boolean save){
        if(busy){Toast.makeText(activity,"הדוח כבר נבנה",Toast.LENGTH_SHORT).show();return;}
        busy=true;DeveloperTrace.event(save?"developer save log":"developer speed report");
        WebView web=activity.developerWebView();
        String script;
        try(InputStream in=context.getAssets().open("speed-report.js")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
            script=new String(out.toByteArray(),StandardCharsets.UTF_8);
        }catch(IOException e){script="JSON.stringify({available:false})";}
        final java.util.concurrent.atomic.AtomicBoolean delivered=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<String> done=value->{
            if(!delivered.compareAndSet(false,true))return;
            IO.execute(()->{
                String built;
                try{built=build(value);}catch(Exception error){built="Developer report unavailable: "+error.getClass().getSimpleName()+"\n"+DeveloperTrace.summary();}
                final String report=built;String export=report+"\n\n"+DeveloperTrace.log();
                main.post(()->{busy=false;if(!foreground())return;if(save)save(export);else show(report,export);});
            });
        };
        // A renderer failure must not leave the developer menu stuck forever.
        main.postDelayed(()->done.accept(null),4000);
        if(web==null||web.getUrl()==null||!MainActivity.allowed(Uri.parse(web.getUrl()))){done.accept(null);return;}
        try{web.evaluateJavascript(script,done::accept);}catch(RuntimeException e){done.accept(null);}
    }
    private String build(String browser){
        StringBuilder out=new StringBuilder("GovMap speed report\n");
        try{PackageInfo p=context.getPackageManager().getPackageInfo(context.getPackageName(),0);out.append("App: ").append(p.versionName).append(" (code ").append(p.versionCode).append(")\n");}catch(Exception ignored){}
        out.append("Android API: ").append(Build.VERSION.SDK_INT).append('\n');
        try{PackageInfo engine=WebView.getCurrentWebViewPackage();if(engine!=null)out.append("WebView: ").append(engine.versionName).append('\n');}catch(RuntimeException ignored){}
        out.append("Captured: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.ROOT).format(new Date())).append("\n\n");
        out.append(DeveloperTrace.summary()).append('\n');
        File permanent=new File(context.getNoBackupFilesDir(),"persistent-assets");long count=0,size=0;
        File[] files=permanent.listFiles((d,n)->n.matches("[0-9a-f]{64}\\.body"));
        if(files!=null)for(File f:files)if(f.isFile()){count++;size+=f.length();}
        out.append("Permanent public asset store: ").append(count).append(" body files, ").append(size).append(" decoded bytes on disk\n")
            .append("Browser HTTP cache size is not exposed by this report.\n\n");
        try{
            if(browser==null||browser.length()>131072)throw new IOException();
            Object raw=new JSONTokener(browser).nextValue();JSONObject data=raw instanceof String?new JSONObject((String)raw):raw instanceof JSONObject?(JSONObject)raw:null;
            if(data==null||!data.optBoolean("available"))throw new IOException();
            browserSummary(out,data);
        }catch(Exception ignored){out.append("Browser timing unavailable for this document. Native diagnostics remain available.\n");}
        out.append("\nReading this report\n")
            .append("- First content visible / load finished are WebView callbacks, not proof that every map tile is ready.\n")
            .append("- Native totals cover public static assets during this process. Fetch+store includes network and disk commit.\n")
            .append("- Browser transfer bytes can overlap native responses. Do not add the two totals.\n")
            .append("- Zero browser transfer bytes do not prove a cache hit: interception and cross-origin timing restrictions also hide sizes.\n")
            .append("- Only public versioned asset filenames are listed. Other URLs, queries, coordinates, headers and console bodies are omitted.\n");
        out.append('\n').append(AppUpdates.get(context).developerDetails());
        return out.append('\n').toString();
    }
    private static double number(JSONObject o,String key){double n=o.optDouble(key,0);return Double.isFinite(n)&&n>=0?Math.min(n,1e12):0;}
    private static void browserSummary(StringBuilder out,JSONObject d){
        out.append("WebView current document\nRetained resource entries: ").append((long)number(d,"retainedResourceEntries"))
            .append("; sampled: ").append((long)number(d,"sampledResourceEntries"))
            .append("\nPage age: ").append(Math.round(number(d,"pageAgeMs"))).append(" ms\nBrowser-reported transfer bytes: ").append((long)number(d,"browserReportedTransferBytes"))
            .append("\nEntries with unreported transfer bytes: ").append((long)number(d,"unknownTransferEntries"))
            .append("\nExplicit browser-reported cache entries: ").append((long)number(d,"explicitCacheEntries")).append('\n');
        JSONObject nav=d.optJSONObject("navigation");if(nav!=null){
            out.append("First response since navigation start: ").append(Math.round(number(nav,"ttfbMs"))).append(" ms\nDOM ready: ")
                .append(nav.isNull("domReadyMs")?"pending":Math.round(number(nav,"domReadyMs"))+" ms")
                .append("\nLoad event: ").append(nav.isNull("loadEventMs")?"pending":Math.round(number(nav,"loadEventMs"))+" ms").append('\n');
        }
        JSONArray paints=d.optJSONArray("paints");if(paints!=null)for(int i=0;i<Math.min(8,paints.length());i++){
            JSONObject p=paints.optJSONObject(i);if(p!=null)out.append("first-contentful-paint".equals(p.optString("name"))?"First contentful paint":"Paint")
                .append(": ").append(Math.round(number(p,"startMs"))).append(" ms\n");
        }
        for(String list:new String[]{"slowest","largest"}){
            out.append('\n').append(list.equals("slowest")?"Slowest retained browser resources":"Largest reported browser transfers").append('\n');
            JSONArray rows=d.optJSONArray(list);if(rows==null)continue;
            for(int i=0;i<Math.min(15,rows.length());i++){
                JSONObject r=rows.optJSONObject(i);if(r==null)continue;String name=r.optString("name");
                if(name.length()>140||!name.matches("[A-Za-z0-9_-]+-[A-Za-z0-9_-]{8,}\\.(js|css|woff2?|ttf|otf|png|jpe?g|webp|svg|ico)"))name="[resource URL omitted]";
                String source="browser-reported cache".equals(r.optString("source"))?"explicit cache":number(r,"transferBytes")>0?"reported transfer":"unreported (cache/intercept/restricted)";
                out.append(name).append(" | ").append(Math.round(number(r,"durationMs"))).append(" ms | transfer=").append((long)number(r,"transferBytes"))
                    .append(" encoded=").append((long)number(r,"encodedBytes")).append(" decoded=").append((long)number(r,"decodedBytes")).append(" | ").append(source).append('\n');
            }
        }
    }
    private ScrollView textView(String report){
        ScrollView scroll=new ScrollView(activity.developerDialogContext());TextView text=new TextView(activity.developerDialogContext());
        text.setText(report);text.setTextSize(12);text.setTypeface(Typeface.MONOSPACE);text.setTextIsSelectable(true);
        text.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);text.setPadding(24,16,24,16);
        text.setTextColor(activity.developerDark()?Color.rgb(235,240,250):Color.rgb(25,35,50));scroll.addView(text);
        if(activity.developerOled())scroll.setBackgroundColor(Color.BLACK);
        return scroll;
    }
    private void show(String report,String export){
        ScrollView scroll=textView(report);
        new AlertDialog.Builder(activity.developerDialogContext()).setTitle("דוח מהירות").setView(scroll)
            .setPositiveButton("שמור דוח ולוג",(dialog,which)->save(export)).setNegativeButton("סגור",null).show();
    }
    private void details(String title,String report,Runnable refresh){
        if(!foreground())return;
        AlertDialog.Builder builder=new AlertDialog.Builder(activity.developerDialogContext()).setTitle(title).setView(textView(report))
            .setNegativeButton("סגור",null).setNeutralButton("העתק",(dialog,which)->copy(report));
        if(refresh!=null)builder.setPositiveButton("רענן",(dialog,which)->refresh.run());builder.show();
    }
    private void background(String title,java.util.function.Supplier<String> task,Runnable refresh){
        IO.execute(()->{String result;try{result=task.get();}catch(Exception e){result="Diagnostic unavailable: "+e.getClass().getSimpleName();}
            final String body=result;main.post(()->details(title,body,refresh));});
    }
    private void cache(){background("אחסון וקאש",this::cacheInfo,this::cache);}
    private String cacheInfo(){
        File root=context.getNoBackupFilesDir(),permanent=new File(root,"persistent-assets");
        return "Storage overview (metadata only; no files cleared)\n\nPermanent static-asset bodies: "+DeveloperStorage.bodies(permanent).text()
            +"\nPermanent asset directory incl. metadata/in-flight files: "+DeveloperStorage.scan(permanent).text()
            +"\nAsset-store budget: 128 MiB / 256 complete files\nPublic metadata directory: "+DeveloperStorage.scan(new File(root,"public-layer-metadata")).text()
            +"\nStaged app-update directory: "+DeveloperStorage.scan(new File(root,"app-updates")).text()
            +"\nAndroid app cache directory (includes temporary exports): "+DeveloperStorage.scan(context.getCacheDir()).text()
            +"\n\n"+DeveloperTrace.cacheSummary()
            +"\nBrowser-only HTTP cache bytes are not exposed by Android's WebView API here. Internal WebView data outside the app cache directory is not included."
            +"\nPermanent assets survive Clear cache. Clear app data or uninstall removes them. Native transfer totals cover public static assets, not all browser traffic.\n";
    }
    private void location(){String nativeState=activity.developerLocationState();background("מצב מיקום",()->locationInfo(nativeState),this::location);}
    private String locationInfo(String nativeState){
        boolean fine=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        boolean coarse=context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        StringBuilder out=new StringBuilder("Passive location diagnostics\nPrecise permission: ").append(fine).append("\nApproximate permission: ").append(coarse).append('\n').append(nativeState);
        LocationManager manager=context.getSystemService(LocationManager.class);if(manager==null)return out.append("Location service unavailable\n").toString();
        try{boolean enabled=Build.VERSION.SDK_INT>=28?manager.isLocationEnabled():android.provider.Settings.Secure.getInt(context.getContentResolver(),android.provider.Settings.Secure.LOCATION_MODE,0)!=0;out.append("Android Location enabled: ").append(enabled).append('\n');}catch(RuntimeException e){out.append("Android Location state unavailable\n");}
        Location best=null;long bestAge=-1;String bestProvider="none";
        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER,LocationManager.PASSIVE_PROVIDER}){
            out.append(provider).append(": ");try{out.append(manager.isProviderEnabled(provider)?"enabled":"disabled");}catch(RuntimeException e){out.append("unavailable");}out.append('\n');
            if(!fine&&!coarse)continue;
            try{Location fix=manager.getLastKnownLocation(provider);if(fix==null)continue;
                long age=DeveloperFacts.fixAgeMs(fix.getElapsedRealtimeNanos(),SystemClock.elapsedRealtimeNanos(),fix.getTime(),System.currentTimeMillis());
                if(best==null||(age>=0&&(bestAge<0||age<bestAge))){best=fix;bestAge=age;bestProvider=provider;}
            }catch(SecurityException|IllegalArgumentException ignored){}
        }
        out.append("\nMost recent readable cached fix: ").append(bestProvider).append("\nFix age: ").append(bestAge<0?"unknown":bestAge+" ms");
        out.append("\nHorizontal accuracy: ").append(best!=null&&best.hasAccuracy()&&Float.isFinite(best.getAccuracy())&&best.getAccuracy()>=0?String.format(Locale.ROOT,"%.1f m",best.getAccuracy()):"unknown");
        return out.append("\n\nNo coordinate values are displayed, copied or logged. This check does not request a new fix or start a location listener. Refresh samples the existing fix again. The separate WebView location-watch state is not inspected here.\n").toString();
    }
    private void startup(){
        if(startupWaiting){toast("בדיקת טעינה כבר פעילה");return;}
        startupId=DeveloperTrace.beginStartup();final long id=startupId;startupWaiting=true;startupSawStart=false;
        if(!activity.developerReload()){startupWaiting=false;DeveloperTrace.endStartup(id,"renderer unavailable");details("בדיקת טעינה",DeveloperTrace.startupSummary(),this::startup);return;}
        toast("טוען מפה מחדש. הדוח ייפתח בסיום");
        main.postDelayed(()->{if(startupWaiting&&startupId==id){startupWaiting=false;DeveloperTrace.endStartup(id,"45-second timeout; partial result");if(foreground())capture(false);}},45000);
    }
    void pageStarted(){if(startupWaiting)startupSawStart=true;}
    void pageFinished(String url){
        WebView web=activity.developerWebView();if(!startupWaiting||!startupSawStart||web==null||url==null||!url.equals(web.getUrl()))return;
        startupWaiting=false;final long id=startupId;DeveloperTrace.endStartup(id,"load-finished callback received");
        main.postDelayed(()->{if(startupId==id&&foreground())capture(false);},200);
    }
    private void network(String title,java.util.function.Supplier<String> task,Runnable refresh){
        if(networkBusy){toast("בדיקת חיבור כבר פועלת");return;}networkBusy=true;toast("בודק חיבור…");
        NET.execute(()->{String result;try{result=task.get();}catch(Exception e){result="Connection check unavailable: "+e.getClass().getSimpleName();}
            final String body=result;main.post(()->{networkBusy=false;details(title,body,refresh);});});
    }
    private void connections(){network("בדיקת חיבור",()->{
        DeveloperNetwork.Result map=DeveloperNetwork.check(false),feed=DeveloperNetwork.check(true);
        if(feed.release!=null)AppUpdates.get(context).recordMetadata(feed.release);
        logProbe(map);logProbe(feed);
        return map.text()+"\n\n"+feed.text()+"\n\nThese are anonymous endpoint checks without WebView cookies or authentication. A working browser page can still have its anonymous probe refused. HTTP 403 is an access refusal, not a DNS/TLS failure. This does not check every map layer/tile. No APK is downloaded or installed.\n";
    },this::connections);}
    private void updateDetails(boolean refresh){
        if(!refresh){background("מצב עדכון מפורט",()->AppUpdates.get(context).developerDetails(),()->updateDetails(true));return;}
        network("מצב עדכון מפורט",()->{DeveloperNetwork.Result feed=DeveloperNetwork.check(true);
            if(feed.release!=null)AppUpdates.get(context).recordMetadata(feed.release);
            logProbe(feed);return feed.text()+"\n\n"+AppUpdates.get(context).developerDetails();
        },()->updateDetails(true));
    }
    private void logProbe(DeveloperNetwork.Result result){DeveloperTrace.event((result.error.isEmpty()?"connection ok: ":"connection failed: ")+result.text());}
    private void copyDebug(){
        IO.execute(()->{String text;try{text=basicInfo()+"\n"+AppUpdates.get(context).developerDetails()+"\nRecent error events\n"+DeveloperTrace.recentErrors();}
            catch(Exception e){text="GovMap diagnostic unavailable: "+e.getClass().getSimpleName();}
            final String snippet=text;main.post(()->copy(snippet));});
    }
    private String basicInfo(){
        StringBuilder out=new StringBuilder("GovMap debug information\n");
        try{PackageInfo p=context.getPackageManager().getPackageInfo(context.getPackageName(),0);out.append("App: ").append(p.versionName).append(" / ").append(p.versionCode).append('\n');}catch(Exception ignored){}
        out.append("Android API: ").append(Build.VERSION.SDK_INT).append('\n');
        try{PackageInfo p=WebView.getCurrentWebViewPackage();if(p!=null)out.append("WebView: ").append(p.versionName).append('\n');}catch(RuntimeException ignored){}
        return out.toString();
    }
    private void copy(String text){
        if(!foreground())return;try{ClipboardManager clipboard=context.getSystemService(ClipboardManager.class);if(clipboard==null)throw new IllegalStateException();
            ClipData clip=ClipData.newPlainText("GovMap diagnostics",text);
            if(Build.VERSION.SDK_INT>=33){PersistableBundle extras=new PersistableBundle();extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true);clip.getDescription().setExtras(extras);}
            clipboard.setPrimaryClip(clip);DeveloperTrace.event("developer copied diagnostic text");if(Build.VERSION.SDK_INT<33)toast("פרטי האבחון הועתקו");
        }catch(RuntimeException e){toast("ההעתקה נכשלה");}
    }
    private void save(String text){
        if(!alive())return;
        IO.execute(()->{
            File staged=null;
            try{
                File directory=new File(context.getCacheDir(),"developer-export");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException();
                File[] old=directory.listFiles();if(old!=null)for(File f:old)if(f.getName().matches("govmap-[0-9a-f-]{36}\\.txt")&&System.currentTimeMillis()-f.lastModified()>3600000)f.delete();
                old=directory.listFiles();if(old!=null&&old.length>=20)throw new IOException("Too many pending exports");
                File file=new File(directory,"govmap-"+UUID.randomUUID()+".txt");staged=file;if(!file.createNewFile())throw new IOException();
                byte[] data=text.getBytes(StandardCharsets.UTF_8);if(data.length>262144)throw new IOException("Report exceeds export limit");
                try(FileOutputStream out=new FileOutputStream(file)){out.write(data);out.getFD().sync();}
                android.content.SharedPreferences prefs=context.getSharedPreferences("developer-export",Context.MODE_PRIVATE);
                final int index=(prefs.getInt("next",0)+1)%0x2000;final int request=SAVE_BASE+index;
                if(!prefs.edit().putInt("next",index).putString("file_"+request,file.getName()).commit())throw new IOException();
                main.post(()->{
                    if(!foreground()){discard(request,file);return;}
                    try{
                        Intent pick=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                            .putExtra(Intent.EXTRA_TITLE,"GovMap-log-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+".txt");
                        activity.startActivityForResult(pick,request);
                    }catch(RuntimeException e){discard(request,file);toast("לא ניתן לפתוח בחירת קובץ לשמירה");}
                });
            }catch(Exception e){if(staged!=null)staged.delete();toast("שמירת הלוג נכשלה. נסה שוב");}
        });
    }
    private void discard(int request,File file){IO.execute(()->{context.getSharedPreferences("developer-export",Context.MODE_PRIVATE).edit().remove("file_"+request).commit();file.delete();});}
    boolean activityResult(int request,int result,Intent data){
        if(request<SAVE_BASE||request>=0x4000)return false;
        final Uri destination=result==Activity.RESULT_OK&&data!=null?data.getData():null;
        IO.execute(()->{
            android.content.SharedPreferences prefs=context.getSharedPreferences("developer-export",Context.MODE_PRIVATE);
            String key="file_"+request;String name=prefs.getString(key,"");prefs.edit().remove(key).commit();
            if(!name.matches("govmap-[0-9a-f-]{36}\\.txt"))return;
            File file=new File(new File(context.getCacheDir(),"developer-export"),name);
            try{
                if(destination==null)return;
                if(!"content".equals(destination.getScheme())||!file.isFile()||file.length()>262144)throw new IOException();
                try(InputStream in=new FileInputStream(file);OutputStream out=context.getContentResolver().openOutputStream(destination,"wt")){
                    if(out==null)throw new IOException();byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
                }
                DeveloperTrace.event("developer log saved to user-selected document");toast("הלוג נשמר");
            }catch(Exception e){toast("שמירת הלוג נכשלה. נסה שוב");}
            finally{file.delete();}
        });return true;
    }
    private void toast(String text){main.post(()->{if(alive())Toast.makeText(activity,text,Toast.LENGTH_LONG).show();});}
}
