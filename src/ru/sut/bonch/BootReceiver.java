package ru.sut.bonch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AppStorage storage = new AppStorage(context);
        if (storage.isDaemonEnabled()) {
            Intent svc = new Intent(context, BonchBackgroundDaemon.class);
            svc.putExtra("action", "start");
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        }
    }
}
