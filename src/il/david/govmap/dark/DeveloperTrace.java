package il.david.govmap.dark;

import java.net.URI;
import java.util.*;

/** Bounded process-local diagnostics. No coordinates, headers, queries or console bodies. */
final class DeveloperTrace {
    private static final long START = System.nanoTime();
    private static long logStart=START,runSequence;
    private static Run run;
    private static final class Run {
        long id,start,firstPage=-1,visible=-1,finished=-1,end,initialHits,initialBytes,initialWire,initialDownloads,finalHits,finalBytes,finalWire,finalDownloads;
        String outcome="running";
    }
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static final LinkedHashMap<String, Asset> ASSETS = new LinkedHashMap<>(16, .75f, true);
    private static final LinkedHashMap<String, Long> MARKS = new LinkedHashMap<>();
    private static long hits, hitBytes, streams, downloads, wireBytes, decodedBytes;
    private static final class Asset {
        String name; long hits, streams, downloads, wire, decoded, ms, expected, started; boolean stored, complete, downloading;
        Asset(String name) { this.name = name; }
    }
    static synchronized void event(String event) {
        while (EVENTS.size() >= 300) EVENTS.removeFirst();
        EVENTS.addLast(String.format(Locale.ROOT, "+%.3fs %s", (System.nanoTime()-logStart)/1e9, clean(event)));
    }
    static synchronized void mark(String name) {
        if("Latest page start".equals(name)){MARKS.remove("Latest content visible");MARKS.remove("Latest load finished");}
        if(run!=null&&run.end==0){long elapsed=(System.nanoTime()-run.start)/1000000;
            if("Latest page start".equals(name)&&run.firstPage<0)run.firstPage=elapsed;
            if("Latest content visible".equals(name)&&run.firstPage>=0&&run.visible<0)run.visible=elapsed;
            if("Latest load finished".equals(name)&&run.firstPage>=0&&run.finished<0)run.finished=elapsed;
        }
        MARKS.put(name, (System.nanoTime()-START)/1000000); event(name);
    }
    static String clean(String input) {
        if (input == null) return "";
        String s = (input.length()>2048?input.substring(0,2048):input).replaceAll("(?i)(https?|content|file)://\\S+", "[URI omitted]")
            .replaceAll("(?i)(authorization|cookie|token|password)\\s*[:=].*", "$1=[omitted]")
            .replaceAll("[\\r\\n\\t]", " ");
        return s.length() > 240 ? s.substring(0, 240) : s;
    }
    static String assetName(String address) {
        try {
            URI uri = new URI(address); String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                || !("govmap.gov.il".equalsIgnoreCase(uri.getHost()) || "www.govmap.gov.il".equalsIgnoreCase(uri.getHost()))
                || path==null || !path.matches("/assets/[A-Za-z0-9_/-]+-[A-Za-z0-9_-]{8,}\\.(js|css|woff2?|ttf|otf|png|jpe?g|webp|svg|ico)")) return "[resource URL omitted]";
            String name = path.substring(path.lastIndexOf('/')+1); return name.length()>140 ? name.substring(0,140) : name;
        } catch (Exception e) { return "[resource URL omitted]"; }
    }
    private static Asset asset(String url) {
        String name=assetName(url); Asset a=ASSETS.get(name);
        if(a==null){if(ASSETS.size()>=256)ASSETS.remove(ASSETS.keySet().iterator().next());a=new Asset(name);ASSETS.put(name,a);}return a;
    }
    static synchronized void served(String url, boolean persistent, long bytes) {
        Asset a=asset(url);
        if(persistent){hits++;hitBytes+=Math.max(0,bytes);a.hits++;}else{streams++;a.streams++;}
    }
    static synchronized void downloaded(String url,long wire,long decoded,long durationMs,boolean complete,boolean stored) {
        Asset a=asset(url); downloads++;wireBytes+=Math.max(0,wire);decodedBytes+=Math.max(0,decoded);
        a.downloads++;a.wire+=Math.max(0,wire);a.decoded+=Math.max(0,decoded);a.ms+=Math.max(0,durationMs);a.complete=complete;a.stored=stored;a.downloading=false;
        event("asset "+a.name+" wire="+wire+" decoded="+decoded+" complete="+complete+" saved="+stored);
    }
    static synchronized void started(String url,long expected,long start){Asset a=asset(url);a.downloading=true;a.expected=expected;a.started=start;}
    static synchronized String summary() {
        StringBuilder s=new StringBuilder("Native timings (ms since first Activity diagnostics event)\n");
        for(Map.Entry<String,Long> e:MARKS.entrySet())s.append(e.getKey()).append(": ").append(e.getValue()).append(" ms\n");
        s.append("\nPublic static assets — entire process session\nPermanent-store responses: ").append(hits).append("; decoded file bytes offered: ").append(hitBytes)
            .append("\nStreamed responses (shared downloads counted per reader): ").append(streams)
            .append("\nNative finished body-transfer records: ").append(downloads).append("\nObserved network body bytes: ").append(wireBytes)
            .append("\nDecoded download bytes: ").append(decodedBytes).append("\nLargest native downloads (wire body bytes; headers excluded):\n");
        ArrayList<Asset> rows=new ArrayList<>(ASSETS.values());rows.sort((a,b)->Long.compare(b.wire,a.wire));
        for(int i=0;i<Math.min(15,rows.size());i++){Asset a=rows.get(i);s.append(a.name).append(" | wire=").append(a.wire).append(" decoded=").append(a.decoded)
            .append(" hits=").append(a.hits).append(" fetch+store_ms=").append(a.ms).append(" complete=").append(a.complete).append(" saved=").append(a.stored).append('\n');}
        s.append("In-progress native downloads (excluded from completed byte totals):\n");
        for(Asset a:rows)if(a.downloading)s.append(a.name).append(" expected_wire_bytes=").append(a.expected).append(" (-1=unknown) elapsed_ms=").append(Math.max(0,(System.nanoTime()-a.started)/1000000)).append('\n');
        return s.append('\n').append(startupSummary()).toString();
    }
    static synchronized String log() { return "App events (last 300; time since current log session; no system logcat)\n"+String.join("\n",EVENTS)+"\n"; }
    static synchronized void freshLog(){EVENTS.clear();logStart=System.nanoTime();event("Fresh app log session started; process metrics retained");}
    static synchronized String recentErrors(){ArrayList<String> errors=new ArrayList<>();for(String event:EVENTS){String lower=event.toLowerCase(Locale.ROOT);if(lower.contains("error")||lower.contains("failed")||lower.contains("installer:"))errors.add(event);}return errors.isEmpty()?"none recorded\n":String.join("\n",errors.subList(Math.max(0,errors.size()-8),errors.size()))+"\n";}
    static synchronized String cacheSummary(){return "Process permanent-store responses: "+hits+"\nDecoded file bytes offered from permanent storage: "+hitBytes
        +"\nNative completed-transfer wire body bytes: "+wireBytes+"\nNative completed-transfer decoded bytes: "+decodedBytes+"\n";}
    static synchronized long beginStartup(){Run next=new Run();next.id=++runSequence;next.start=System.nanoTime();next.initialHits=hits;next.initialBytes=hitBytes;next.initialWire=wireBytes;next.initialDownloads=downloads;run=next;event("Startup/reload test started");return next.id;}
    static synchronized void endStartup(long id,String outcome){if(run==null||run.id!=id||run.end!=0)return;run.end=System.nanoTime();run.finalHits=hits;run.finalBytes=hitBytes;run.finalWire=wireBytes;run.finalDownloads=downloads;run.outcome=clean(outcome);event("Startup/reload test "+run.outcome);}
    static synchronized String startupSummary(){
        if(run==null)return "Startup/reload test: not run\n";
        long h=run.end==0?hits:run.finalHits,b=run.end==0?hitBytes:run.finalBytes,w=run.end==0?wireBytes:run.finalWire,d=run.end==0?downloads:run.finalDownloads;
        return "Latest startup/reload test: "+run.outcome+"\nElapsed: "+((run.end==0?System.nanoTime():run.end)-run.start)/1000000+" ms"
            +"\nPage start: "+timing(run.firstPage)+"\nFirst content visible: "+timing(run.visible)+"\nLoad-finished callback: "+timing(run.finished)
            +"\nPermanent-store response delta: "+Math.max(0,h-run.initialHits)+"; file bytes offered: "+Math.max(0,b-run.initialBytes)
            +"\nNative completed-transfer delta: "+Math.max(0,d-run.initialDownloads)+"; wire body bytes: "+Math.max(0,w-run.initialWire)
            +"\nDeltas cover completions during test, including any requests already in flight. No cache was cleared.\n";
    }
    private static String timing(long millis){return millis<0?"not observed":millis+" ms after test start";}
}
