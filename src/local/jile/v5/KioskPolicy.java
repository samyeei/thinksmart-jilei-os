package local.jile.v5;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Set;

final class KioskPolicy {
    private final Context context;
    private final DevicePolicyManager manager;
    private final ComponentName admin;
    private final ComponentName home;

    KioskPolicy(Context context) {
        this.context = context;
        manager = context.getSystemService(DevicePolicyManager.class);
        admin = new ComponentName(context, KioskAdmin.class);
        home = new ComponentName(context, context.getPackageName() + ".ChildHome");
    }

    boolean available() {
        return manager != null && manager.isDeviceOwnerApp(context.getPackageName());
    }

    boolean locked() {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        return am != null && am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_LOCKED;
    }

    void configure(Set<String> approved) {
        if (!available()) return;
        Set<String> packages = new HashSet<>();
        packages.add(context.getPackageName());
        for (String id : approved) {
            ComponentName component = ComponentName.unflattenFromString(id);
            if (component != null && allowedCandidate(component)) packages.add(component.getPackageName());
        }
        manager.setLockTaskPackages(admin, packages.toArray(new String[0]));
        context.getPackageManager().setComponentEnabledSetting(home,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        manager.addPersistentPreferredActivity(admin, filter, home);
    }

    boolean enter(Activity activity) {
        if (!available()) return false;
        if (locked()) return true;
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isKeyguardLocked()) return false;
        if (!manager.isLockTaskPermitted(context.getPackageName())) return false;
        activity.startLockTask();
        return locked();
    }

    boolean keepsScreenOn() {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0;
    }

    void setKeepScreenOn(boolean enabled) {
        if (!available()) throw new IllegalStateException("Device owner is not active");
        int sources = BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB
                | BatteryManager.BATTERY_PLUGGED_WIRELESS;
        manager.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                Integer.toString(enabled ? sources : 0));
        if (keepsScreenOn() != enabled) throw new IllegalStateException("Screen mode did not change");
    }

    void reboot() {
        if (!available()) throw new IllegalStateException("Device owner is not active");
        manager.reboot(admin);
    }

    boolean allowedCandidate(ComponentName component) {
        String name = component.getPackageName();
        return !name.equals("com.android.settings")
                && !name.equals("com.android.launcher3")
                && !name.equals("com.android.systemui")
                && !name.equals("com.android.packageinstaller");
    }
}
