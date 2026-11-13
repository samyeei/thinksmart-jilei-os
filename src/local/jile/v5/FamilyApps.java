package local.jile.v5;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class FamilyApps {
    private final Context context;
    private final SharedPreferences preferences;

    FamilyApps(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("family", Context.MODE_PRIVATE);
    }

    Set<String> approved() {
        return new HashSet<>(preferences.getStringSet("approved_components", new HashSet<>()));
    }

    boolean save(Set<String> ids) {
        synchronized (preferences) {
            Set<String> available = new HashSet<>();
            for (Entry app : installed()) available.add(app.id());
            if (!available.containsAll(ids)) return false;
            Set<String> before = approved();
            KioskPolicy kiosk = new KioskPolicy(context);
            try {
                kiosk.configure(ids);
                if (preferences.edit().putStringSet("approved_components", new HashSet<>(ids)).commit()) return true;
            } catch (RuntimeException ignored) {
                // Restore the previous policy before returning an error.
            }
            try { kiosk.configure(before); } catch (RuntimeException ignored) {}
            return false;
        }
    }

    boolean change(String id, boolean approved) {
        synchronized (preferences) {
            Set<String> ids = approved();
            if (approved) ids.add(id);
            else ids.remove(id);
            // Prune uninstalled apps before saving a remote edit.
            Set<String> available = new HashSet<>();
            for (Entry app : installed()) available.add(app.id());
            if (!available.contains(id)) return false;
            ids.retainAll(available);
            return save(ids);
        }
    }

    List<Entry> installed() {
        PackageManager manager = context.getPackageManager();
        Set<String> homes = new HashSet<>();
        for (ResolveInfo info : manager.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            homes.add(info.activityInfo.packageName);
        }
        Set<ComponentName> seen = new HashSet<>();
        List<Entry> result = new ArrayList<>();
        KioskPolicy kiosk = new KioskPolicy(context);
        Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : manager.queryIntentActivities(launch, 0)) {
            ComponentName component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            if (homes.contains(component.getPackageName()) || context.getPackageName().equals(component.getPackageName())
                    || !info.activityInfo.exported || !seen.add(component) || !kiosk.allowedCandidate(component)) continue;
            result.add(new Entry(component, info.loadLabel(manager).toString(), info));
        }
        Collator collator = Collator.getInstance(Locale.CHINA);
        result.sort((a, b) -> collator.compare(a.label, b.label));
        return result;
    }

    List<Entry> approvedPlayers() {
        Set<String> allowed = approved();
        Set<String> packages = new HashSet<>();
        List<Entry> result = new ArrayList<>();
        for (Entry app : installed()) {
            if (allowed.contains(app.id()) && packages.add(app.component.getPackageName())) result.add(app);
        }
        return result;
    }

    static final class Entry {
        final ComponentName component;
        final String label;
        final ResolveInfo info;

        Entry(ComponentName component, String label, ResolveInfo info) {
            this.component = component;
            this.label = label;
            this.info = info;
        }

        String id() { return component.flattenToString(); }
    }
}
