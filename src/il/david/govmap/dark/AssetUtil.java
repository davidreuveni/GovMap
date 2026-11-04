package il.david.govmap.dark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.*;

/** File-key and metadata helpers for the persistent asset store. */
final class AssetUtil {
    static JSONObject obj(Object... pairs){
        JSONObject object=new JSONObject();
        for(int i=0;i+1<pairs.length;i+=2)put(object,String.valueOf(pairs[i]),pairs[i+1]);
        return object;
    }
    static void put(JSONObject object,String key,Object value){
        try{object.put(key,value==null?JSONObject.NULL:value);}catch(JSONException e){throw new IllegalArgumentException(e);}
    }
    static String hash(String value){
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }
    static String hash(byte[] value){
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder text=new StringBuilder();for(byte b:digest)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
