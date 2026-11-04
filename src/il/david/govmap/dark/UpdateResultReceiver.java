package il.david.govmap.dark;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class UpdateResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { AppUpdates.get(context).installationResult(intent); }
}
