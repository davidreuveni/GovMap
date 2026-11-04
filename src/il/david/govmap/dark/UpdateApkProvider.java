package il.david.govmap.dark;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Android installer gets a temporary read grant for one verified APK. */
public final class UpdateApkProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri, String mode) throws FileNotFoundException {
        try { return UpdateApkAccess.file(getContext().getNoBackupFilesDir(), uri.toString(), mode); }
        catch (SecurityException e) { throw e; }
        catch (Exception e) { throw new FileNotFoundException("Update APK unavailable"); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri, mode), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { if (!UpdateApkAccess.URI.equals(uri.toString())) throw new SecurityException("Unknown APK"); return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        File apk;
        try { apk = file(uri, "r"); } catch (FileNotFoundException e) { return null; }
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns); Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "GovMap-Dark.apk";
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = apk.length();
        }
        result.addRow(row); return result;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new SecurityException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new SecurityException("Read only"); }
}
