package com.samet.windowstik;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public final class UpdateResult extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent result) {
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                try {
                    context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (android.content.ActivityNotFoundException ignored) { }
            }
        }
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_FAILURE_ABORTED)
            Toast.makeText(context, "Güncelleme kurulamadı.", Toast.LENGTH_LONG).show();
    }
}
