package il.david.govmap.dark;

import java.text.SimpleDateFormat;
import java.util.*;

final class DeveloperFacts {
    private DeveloperFacts() {}
    static long fixAgeMs(long fixElapsedNanos,long nowElapsedNanos,long fixWallMs,long nowWallMs){
        if(fixElapsedNanos>0)return nowElapsedNanos>=fixElapsedNanos?(nowElapsedNanos-fixElapsedNanos)/1000000:-1;
        return fixWallMs>0&&nowWallMs>=fixWallMs?nowWallMs-fixWallMs:-1;
    }
    static String time(long millis){return millis<=0?"not recorded":new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.ROOT).format(new Date(millis));}
    static String bytes(long bytes){return String.format(Locale.ROOT,"%,d bytes (%.2f MiB)",Math.max(0,bytes),Math.max(0,bytes)/1048576d);}
}
