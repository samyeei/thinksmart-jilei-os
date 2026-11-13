package local.jile.v5;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.view.Gravity;
import android.view.Surface;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageButton;

/** Supplies a contrast-safe Home key on Android 8.1 while the child is inside an approved app. */
public final class HomeKeyService extends AccessibilityService {
    private static final String CHATGPT = "com.openai.chatgpt";
    private static HomeKeyService running;
    private WindowManager windows;
    private KioskPolicy kiosk;
    private FamilyApps apps;
    private SharedPreferences preferences;
    private ImageButton home;
    private ImageButton keyboard;
    private boolean keyboardHidden;
    private String foregroundPackage;
    private String foregroundClass;

    private final SharedPreferences.OnSharedPreferenceChangeListener approvalsChanged =
            (prefs, key) -> {
                if ("approved_components".equals(key)) updateButton();
            };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        windows = getSystemService(WindowManager.class);
        kiosk = new KioskPolicy(this);
        apps = new FamilyApps(this);
        preferences = getSharedPreferences("family", MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(approvalsChanged);
    }

    static void foreground(String name, String windowClass) {
        if (running != null) running.setForeground(name, windowClass);
    }

    private void setForeground(String name, String windowClass) {
        foregroundPackage = name;
        foregroundClass = windowClass;
        updateButton();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (keyboard != null) updateIconColors();
            return;
        }
        CharSequence name = event.getPackageName();
        CharSequence windowClass = event.getClassName();
        if (name == null || windowClass == null || getPackageName().contentEquals(name)
                || event.getContentChangeTypes() != 0) return;
        String candidate = name.toString();
        try {
            // Ignore keyboard, toast and other non-activity windows.
            getPackageManager().getActivityInfo(new ComponentName(candidate, windowClass.toString()), 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException notAnActivity) {
            return;
        }
        setForeground(candidate, windowClass.toString());
    }

    private boolean approvedForeground() {
        if (getPackageName().equals(foregroundPackage)) {
            return ApprovedSiteActivity.class.getName().equals(foregroundClass);
        }
        for (String id : apps.approved()) {
            ComponentName app = ComponentName.unflattenFromString(id);
            if (app != null && app.getPackageName().equals(foregroundPackage)) return true;
        }
        return false;
    }

    private void updateButton() {
        if (windows == null || !kiosk.locked() || !approvedForeground()) {
            hideButton();
            return;
        }
        if (CHATGPT.equals(foregroundPackage)) showKeyboardControl();
        else removeKeyboardControl();
        if (home != null) return;
        ImageButton button = new ImageButton(this);
        button.setContentDescription("Home，返回儿童桌面");
        button.setImageResource(R.drawable.kiosk_home);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        button.setOnClickListener(view -> performGlobalAction(GLOBAL_ACTION_HOME));
        try {
            windows.addView(button, layout(false));
            home = button;
            updateIconColors();
        } catch (WindowManager.BadTokenException unavailable) {
            // The accessibility window token was revoked while disconnecting.
        }
    }

    private void showKeyboardControl() {
        if (keyboard != null) return;
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.kiosk_keyboard);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(0, 0, 0, 0);
        SoftKeyboardController controller = getSoftKeyboardController();
        button.setContentDescription(controller.getShowMode() == SHOW_MODE_HIDDEN
                ? "开启键盘" : "收起键盘");
        button.setOnClickListener(view -> {
            int mode = controller.getShowMode() == SHOW_MODE_HIDDEN
                    ? SHOW_MODE_AUTO : SHOW_MODE_HIDDEN;
            if (!controller.setShowMode(mode)) return;
            keyboardHidden = mode == SHOW_MODE_HIDDEN;
            button.setContentDescription(keyboardHidden ? "开启键盘" : "收起键盘");
        });
        try {
            windows.addView(button, layout(true));
            keyboard = button;
            updateIconColors();
        } catch (WindowManager.BadTokenException unavailable) {
            // The accessibility window token was revoked while disconnecting.
        }
    }

    private void updateIconColors() {
        int color = 0xff767676;
        if (keyboard != null) {
            // V5's IME takes over the bottom navigation bar in portrait.
            boolean bottomBar = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_PORTRAIT;
            boolean keyboardVisible = false;
            if (bottomBar) {
                for (AccessibilityWindowInfo window : getWindows()) {
                    keyboardVisible |= window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD;
                    window.recycle();
                }
            }
            color = keyboardVisible ? Color.WHITE : 0xff253a58;
        }
        if (home != null) home.setColorFilter(color);
        if (keyboard != null) keyboard.setColorFilter(color);
    }

    private void removeKeyboardControl() {
        if (keyboardHidden) {
            getSoftKeyboardController().setShowMode(SHOW_MODE_AUTO);
            keyboardHidden = false;
        }
        if (keyboard != null) {
            windows.removeView(keyboard);
            keyboard = null;
        }
        updateIconColors();
    }

    private WindowManager.LayoutParams layout(boolean keyboardControl) {
        int size = Math.round(48 * getResources().getDisplayMetrics().density);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.setTitle(keyboardControl ? "JileKeyboardControl" : "JileHomeKey");
        // Anchor to the edges opposite the navigation bar. Its appearance can
        // change the available frame during an app transition.
        Point screen = new Point();
        windows.getDefaultDisplay().getRealSize(screen);
        int rotation = windows.getDefaultDisplay().getRotation();
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            params.gravity = Gravity.TOP
                    | (rotation == Surface.ROTATION_270 ? Gravity.RIGHT : Gravity.LEFT);
            params.x = screen.x - size;
            params.y = (screen.y - size) / 2;
        } else {
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = screen.y - size;
        }
        if (keyboardControl) {
            int offset = Math.round(126 * getResources().getDisplayMetrics().density);
            if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                params.y += offset;
            } else {
                params.x = offset;
            }
        }
        return params;
    }

    private void hideButton() {
        removeKeyboardControl();
        if (home != null) {
            windows.removeView(home);
            home = null;
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (home != null) windows.updateViewLayout(home, layout(false));
        if (keyboard != null) windows.updateViewLayout(keyboard, layout(true));
        updateIconColors();
    }

    @Override public void onInterrupt() { hideButton(); }

    @Override public void onDestroy() {
        if (running == this) running = null;
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(approvalsChanged);
        hideButton();
        super.onDestroy();
    }
}
