package local.jile.v5;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public final class OtaReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        OtaUpdate ota = new OtaUpdate(context);
        if (OtaUpdate.RESULT.equals(intent.getAction())) {
            ota.result(intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
                    intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE));
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            ota.cancelLegacyAutomaticUpdates();
            if (!ota.replaced()) return;
            context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClassName(context, context.getPackageName() + ".ChildHome")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
