package local.jile.v5;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.media.projection.MediaProjectionManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Build;
import android.os.Process;
import android.graphics.drawable.RippleDrawable;
import android.widget.FrameLayout;
import android.text.TextUtils;
import android.view.View;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int INK = 0xffe9f5ff;
    private static final int MUTED = 0xffaac4d9;
    private static final int CARD = 0xffeaf0fa;
    private static final int PARENT_INK = 0xff1c2b42;
    private static final int PARENT_MUTED = 0xff536881;
    private static final int PARENT_BG = 0xfff4f6f9;
    private static final int BLUE = 0xff245eea;
    private static final int ACCENT = 0xff88eaf2;
    private static final ComponentName STOCK_HOME =
            new ComponentName("com.android.launcher3", "com.android.launcher3.Launcher");

    private final Handler handler = new Handler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private ParentPin pin;
    private KioskPolicy kiosk;
    private FamilyApps familyApps;
    private boolean inForeground;
    private static final int REQUEST_SCREEN = 41;
    private static final int REQUEST_WALLPAPER = 42;
    private static final int REQUEST_AUTHOR_SITE = 43;
    private static final int REQUEST_RESTORE_SCREEN = 44;
    private static final String RESTORE_SCREEN = "restore_remembered_screen_capture";
    private LocalParentPanel localParentPanel;
    private android.webkit.ValueCallback<android.net.Uri[]> wallpaperPicker;
    private int parentAuxRequest;
    private LinearLayout root;
    private LinearLayout page;
    private TextView clock;
    private TextView date;
    private ImageView wifi;
    private Boolean wifiConnected;
    private FamilyExtras extras;
    private ImageButton screenMode;
    private android.animation.ObjectAnimator wallpaperMotion;
    private LinearLayout musicControls;
    private FamilyApps.Entry musicApp;
    private TextView musicTitle;
    private int quoteOffset;
    private int desktopPage;
    private static final String DESKTOP_ORDER = "desktop_entry_order";
    private ImageButton musicToggle;
    private ImageButton musicNext;
    private boolean parent;
    private AlertDialog pinDialog;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (clock != null) updateClock();
            refreshMusicControls();
            handler.postDelayed(this, 3000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            quoteOffset = state.getInt("quoteOffset");
            desktopPage = state.getInt("desktopPage");
        }
        preferences = getSharedPreferences("family", MODE_PRIVATE);
        pin = new ParentPin(preferences);
        familyApps = new FamilyApps(this);
        extras = new FamilyExtras(this);
        kiosk = new KioskPolicy(this);
        preferences.registerOnSharedPreferenceChangeListener(appChanges);
        getWindow().setStatusBarColor(0xff0e233c);
        getWindow().setNavigationBarColor(0xff0e233c);
        showChild();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("quoteOffset", quoteOffset);
        state.putInt("desktopPage", desktopPage);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        inForeground = true;
        HomeKeyService.foreground(getPackageName(), MainActivity.class.getName());
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        if (!parent && pinDialog == null) showChild();
        enableFamilyMode();
        handler.post(() -> restoreScreenWhenReady(8));
    }

    @Override protected void onPause() {
        inForeground = false;
        stopWallpaperMotion();
        handler.removeCallbacks(ticker);
        if (pinDialog != null) pinDialog.dismiss();
        if (parent && parentAuxRequest == 0) showChild();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopWallpaperMotion();
        closeLocalParentPanel();
        handler.removeCallbacksAndMessages(null);
        worker.shutdown();
        preferences.unregisterOnSharedPreferenceChangeListener(appChanges);
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (pinDialog != null) pinDialog.dismiss();
        showChild();
        enableFamilyMode();
    }

    @Override public void onBackPressed() {
        if (parent) showChild();
    }

    private void setDesktopNavigationHidden(boolean hidden) {
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        int current = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(hidden ? current | flags : current & ~flags);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setDesktopNavigationHidden(!parent && pinDialog == null);
    }

    private void showChild() {
        parent = false;
        setDesktopNavigationHidden(true);
        parentAuxRequest = 0;
        makePage();
        boolean landscape = getResources().getConfiguration().screenWidthDp
                > getResources().getConfiguration().screenHeightDp;
        Set<String> allowed = familyApps.approved();
        java.util.List<FamilyApps.Entry> installed = familyApps.installed();
        String playerPackage = extras.player();
        musicApp = null;
        for (FamilyApps.Entry app : installed) {
            if (allowed.contains(app.id()) && playerPackage.equals(app.component.getPackageName())) {
                musicApp = app;
                break;
            }
        }
        org.json.JSONArray sites = extras.sites();
        int height = getResources().getConfiguration().screenHeightDp;
        boolean compact = !landscape && height < 710;
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView greeting = text(extras.greeting(), landscape ? 19 : 23, INK);
        greeting.setMaxLines(1);
        greeting.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(greeting, new LinearLayout.LayoutParams(0, -2, 1));
        wifi = new ImageView(this);
        wifi.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        wifi.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        clock = text("", landscape ? 43 : compact ? 60 : 82, INK);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        clock.setGravity(Gravity.CENTER);
        date = text("", landscape ? 14 : 16, MUTED);
        date.setGravity(Gravity.CENTER);
        if (landscape) {
            LinearLayout clockGroup = new LinearLayout(this);
            clockGroup.setOrientation(LinearLayout.VERTICAL);
            clockGroup.addView(clock);
            clockGroup.addView(date);
            header.addView(clockGroup);
            View spacer = new View(this);
            header.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1));
            header.addView(wifi, new LinearLayout.LayoutParams(dp(28), dp(28)));
            root.addView(header);
        } else {
            header.addView(wifi, new LinearLayout.LayoutParams(dp(28), dp(28)));
            root.addView(header);
            add(clock, compact ? 12 : 29);
            add(date, 3);
        }
        updateClock();

        LinkedHashMap<String, DesktopPager.Entry> entries = new LinkedHashMap<>();
        int tileHeight = landscape ? Math.max(54, Math.min(132, (height - 205) / 2))
                : Math.max(54, Math.min(122, (height - (compact ? 235 : 280)) / 3));
        int iconSize = Math.min(landscape ? 62 : 58, tileHeight - 34);
        for (FamilyApps.Entry app : installed) {
            if (!allowed.contains(app.id())) continue;
            Drawable icon = app.info.loadIcon(getPackageManager());
            String id = "app:" + app.id();
            entries.put(id, new DesktopPager.Entry(id,
                    desktopTile(app.label, icon, iconSize, () -> launchApproved(app.component))));
        }
        for (int i = 0; i < sites.length(); i++) {
            org.json.JSONObject site = sites.optJSONObject(i);
            if (site == null) continue;
            String url = site.optString("url"), label = site.optString("label");
            int iconId = extras.iconResource(site.optString("icon"));
            Drawable icon = getDrawable(iconId == 0 ? R.drawable.site_globe : iconId);
            icon.setTint(0xffa0d6ff);
            String id = "site:" + url;
            entries.put(id, new DesktopPager.Entry(id, desktopTile(label, icon, iconSize,
                    () -> startActivity(new Intent(this, ApprovedSiteActivity.class).putExtra("url", url)))));
        }
        if (entries.isEmpty()) {
            add(text("还没有开放的应用或网址。", 16, MUTED), 17);
        } else {
            java.util.ArrayList<DesktopPager.Entry> ordered = new java.util.ArrayList<>();
            org.json.JSONArray stored = desktopOrder();
            for (int i = 0; i < stored.length(); i++) {
                DesktopPager.Entry entry = entries.remove(stored.optString(i));
                if (entry != null) ordered.add(entry);
            }
            ordered.addAll(entries.values());
            DesktopPager pager = new DesktopPager(this, landscape, tileHeight, ordered, desktopPage,
                    new DesktopPager.Listener() {
                        @Override public void pageChanged(int current) { desktopPage = current; }
                        @Override public void orderChanged(java.util.List<String> ids) {
                            preferences.edit().putString(DESKTOP_ORDER,
                                    new org.json.JSONArray(ids).toString()).apply();
                        }
                    });
            LinearLayout.LayoutParams pagerParams = new LinearLayout.LayoutParams(-1, 0, 1);
            pagerParams.topMargin = dp(landscape || compact ? 10 : 20);
            root.addView(pager, pagerParams);
        }

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(22), dp(landscape ? 2 : 7), dp(22), dp(landscape ? 3 : 8));
        LinearLayout footerRow = new LinearLayout(this);
        footerRow.setGravity(Gravity.CENTER_VERTICAL);
        View quoteCard = desktopQuote(landscape);
        if (landscape) footerRow.addView(quoteCard, new LinearLayout.LayoutParams(0, -2, 1));
        else footer.addView(quoteCard);
        musicTitle = text("", 14, INK);
        musicTitle.setSingleLine(true);
        musicTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        musicTitle.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        musicTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        musicTitle.setMarqueeRepeatLimit(-1);
        musicTitle.setSelected(true);
        musicTitle.setPadding(0, dp(2), 0, dp(2));
        footer.addView(musicTitle, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        musicControls = new LinearLayout(this);
        musicControls.setGravity(Gravity.CENTER_VERTICAL);
        musicToggle = iconButton(R.drawable.music_play, "播放 " + playerName(), ACCENT, () -> controlMusic(false));
        musicNext = iconButton(R.drawable.music_next, playerName() + " 下一首", ACCENT, () -> controlMusic(true));
        musicControls.addView(musicToggle);
        musicControls.addView(musicNext);
        actions.addView(musicControls, landscape ? new LinearLayout.LayoutParams(-2, -2)
                : new LinearLayout.LayoutParams(0, -2, 1));
        refreshMusicControls();
        screenMode = iconButton(R.drawable.screen_sleep, "30 秒自动息屏", 0xffd4e6ff, this::toggleScreenMode);
        actions.addView(screenMode);
        updateScreenMode();
        actions.addView(iconButton(R.drawable.device_restart, "重启", 0xffd4e6ff, this::confirmReboot));
        actions.addView(iconButton(R.drawable.parent_shield, "家长模式", ACCENT, this::promptPin));
        if (landscape) {
            footerRow.addView(actions, new LinearLayout.LayoutParams(-2, -2));
            footer.addView(footerRow);
        } else footer.addView(actions);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER);
        ImageView brandIcon = new ImageView(this);
        brandIcon.setImageResource(R.drawable.ic_brand);
        brandIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        brandIcon.setAlpha(0.55f);
        LinearLayout.LayoutParams brandIconParams = new LinearLayout.LayoutParams(dp(17), dp(17));
        brandIconParams.setMarginEnd(dp(5));
        brand.addView(brandIcon, brandIconParams);
        brand.addView(text("鸡肋OS", 11, MUTED));
        footer.addView(brand);
        page.addView(footer);
    }

    private View desktopQuote(boolean landscape) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(3), dp(landscape ? 12 : 0), dp(3));
        card.setBackground(pressable(0x00142b48, 0x00142b48));
        TextView quote = text("", 15, INK);
        TextView source = text("", 12, MUTED);
        card.addView(quote);
        card.addView(source);
        Runnable showQuote = () -> {
            long now = System.currentTimeMillis();
            String sentence = DailyContent.quote(now, quoteOffset);
            String attribution = DailyContent.source(now, quoteOffset);
            quote.setText(sentence);
            source.setText("—— " + attribution);
            card.setContentDescription(sentence + " " + attribution + "。点击换一句");
        };
        card.setFocusable(true);
        card.setOnClickListener(view -> {
            quoteOffset = DailyContent.nextQuote(quoteOffset);
            showQuote.run();
        });
        showQuote.run();
        return card;
    }

    private MediaController musicController() {
        if (musicApp == null) return null;
        MediaSessionManager manager = getSystemService(MediaSessionManager.class);
        if (manager == null) return null;
        try {
            ComponentName listener = new ComponentName(this, MusicSessionAccess.class);
            String packageName = musicApp.component.getPackageName();
            for (MediaController controller : manager.getActiveSessions(listener)) {
                if (packageName.equals(controller.getPackageName())) return controller;
            }
        } catch (SecurityException ignored) {
            // Controls remain visible but disabled without media session access.
        }
        return null;
    }

    private String playerName() {
        return musicApp == null ? "播放器" : musicApp.label;
    }

    private static boolean isMusicPlaying(PlaybackState state) {
        int value = state.getState();
        return value == PlaybackState.STATE_PLAYING || value == PlaybackState.STATE_BUFFERING
                || value == PlaybackState.STATE_CONNECTING;
    }

    private void refreshMusicControls() {
        if (musicControls == null) return;
        MediaController controller = musicController();
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        boolean available = state != null && (state.getState() == PlaybackState.STATE_PAUSED || isMusicPlaying(state));
        MediaMetadata metadata = available ? controller.getMetadata() : null;
        CharSequence title = metadata == null ? "" : metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (metadata != null && TextUtils.isEmpty(title)) {
            title = metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        }
        title = title == null ? "" : title.toString().trim();
        if (!TextUtils.equals(musicTitle.getText(), title)) musicTitle.setText(title);
        musicTitle.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
        boolean playing = available && isMusicPlaying(state);
        long actions = available ? state.getActions() : 0;
        long toggleActions = PlaybackState.ACTION_PLAY_PAUSE
                | (playing ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY);
        String label = ((actions & toggleActions) == 0 ? "打开 " : playing ? "暂停 " : "播放 ") + playerName();
        musicToggle.setImageResource(playing ? R.drawable.music_pause : R.drawable.music_play);
        musicToggle.setContentDescription(label);
        musicToggle.setTooltipText(label);
        musicToggle.setEnabled(musicApp != null);
        musicToggle.setAlpha(musicToggle.isEnabled() ? 1f : 0.45f);
        musicNext.setEnabled((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
        musicNext.setAlpha(musicNext.isEnabled() ? 1f : 0.45f);
    }

    private void controlMusic(boolean next) {
        MediaController controller = musicController();
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        boolean available = state != null && (state.getState() == PlaybackState.STATE_PAUSED || isMusicPlaying(state));
        long actions = available ? state.getActions() : 0;
        if (next) {
            if ((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) controller.getTransportControls().skipToNext();
        } else if ((actions & PlaybackState.ACTION_PAUSE) != 0 && isMusicPlaying(state)) {
            controller.getTransportControls().pause();
        } else if ((actions & PlaybackState.ACTION_PLAY) != 0 && !isMusicPlaying(state)) {
            controller.getTransportControls().play();
        } else if ((actions & PlaybackState.ACTION_PLAY_PAUSE) != 0) {
            controller.dispatchMediaButtonEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
            controller.dispatchMediaButtonEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        } else if (musicApp != null) {
            launchApproved(musicApp.component);
        }
        handler.postDelayed(this::refreshMusicControls, 350);
    }

    private org.json.JSONArray desktopOrder() {
        try { return new org.json.JSONArray(preferences.getString(DESKTOP_ORDER, "[]")); }
        catch (org.json.JSONException invalid) { return new org.json.JSONArray(); }
    }

    private View desktopTile(String name, Drawable artwork, int iconSize, Runnable open) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(5), dp(6), dp(5), dp(6));
        tile.setBackground(pressable(0xbf142b48, 0x507394ba));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(artwork);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)));
        TextView label = text(name, 14, INK);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(0, dp(6), 0, 0);
        tile.addView(label, new LinearLayout.LayoutParams(-1, -2));
        tile.setContentDescription(name);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> open.run());
        return tile;
    }

    private void toggleScreenMode() {
        boolean keepOn = !kiosk.keepsScreenOn();
        try {
            kiosk.setKeepScreenOn(keepOn);
            updateScreenMode();
            toast(keepOn ? "已开启永不息屏" : "已恢复 30 秒自动息屏");
        } catch (RuntimeException error) {
            toast("无法切换息屏模式，请检查设备管理权限");
        }
    }

    private void updateScreenMode() {
        boolean keepOn = kiosk.keepsScreenOn();
        if (keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (screenMode == null) return;
        screenMode.setImageResource(keepOn ? R.drawable.screen_keep_on : R.drawable.screen_sleep);
        screenMode.setImageTintList(ColorStateList.valueOf(keepOn ? ACCENT : 0xffd4e6ff));
        String mode = keepOn ? "永不息屏，点击恢复 30 秒自动息屏" : "30 秒自动息屏，点击开启永不息屏";
        screenMode.setContentDescription(mode);
        screenMode.setTooltipText(mode);
    }

    private void confirmReboot() {
        if (!kiosk.available()) {
            toast("重启需要设备管理权限");
            return;
        }
        new AlertDialog.Builder(this).setTitle("重启设备")
                .setMessage("设备会重新启动并回到儿童桌面。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认重启", (dialog, button) -> {
                    try { kiosk.reboot(); }
                    catch (RuntimeException error) { toast("暂时无法重启设备"); }
                }).show();
    }

    private void updateClock() {
        long now = System.currentTimeMillis();
        clock.setText(DailyContent.clock(now));
        date.setText(DailyContent.date(now));
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
        boolean connected = network != null && network.isConnected()
                && network.getType() == ConnectivityManager.TYPE_WIFI;
        if (wifiConnected == null || wifiConnected != connected) {
            wifiConnected = connected;
            wifi.setImageResource(connected ? R.drawable.wifi_status : R.drawable.wifi_off_status);
            wifi.setImageTintList(ColorStateList.valueOf(connected ? ACCENT : MUTED));
            String status = connected ? "Wi-Fi 已连接" : "Wi-Fi 未连接";
            wifi.setContentDescription(status);
            wifi.setTooltipText(status);
        }
    }

    private void promptPin() {
        if (pinDialog != null) return;
        boolean first = !pin.configured();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), dp(6));
        EditText setupCode = pinField("初始设置码 123456");
        if (first) form.addView(setupCode);
        EditText input = pinField(first ? "设置新的六位数字 PIN" : "家长 PIN");
        form.addView(input);
        EditText confirm = pinField("再次输入 PIN");
        if (first) form.addView(confirm);
        TextView error = text("", 14, 0xffa22b25);
        error.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(first ? "创建家长 PIN" : "进入家长模式")
                .setView(form).setNegativeButton("取消", null)
                .setPositiveButton(first ? "创建并进入" : "进入", null).create();
        pinDialog = dialog;
        dialog.setOnDismissListener(d -> pinDialog = null);
        dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        submit.setOnClickListener(v -> {
            String entered = input.getText().toString();
            if (first && !"123456".contentEquals(setupCode.getText())) {
                error.setText("请输入初始设置码 123456。");
                return;
            }
            if (first && "123456".equals(entered)) {
                error.setText("请设置不同于初始设置码的新 PIN。");
                return;
            }
            if (!ParentPin.valid(entered) || (first && !entered.equals(confirm.getText().toString()))) {
                error.setText(first ? "请输入两次相同的六位数字。" : "请输入六位数字。");
                return;
            }
            long wait = preferences.getLong("pin_retry_after", 0) - SystemClock.elapsedRealtime();
            if (wait > 0 && wait <= 30000) {
                error.setText("尝试次数过多，请等待 " + ((wait + 999) / 1000) + " 秒。");
                return;
            }
            submit.setEnabled(false);
            error.setText("");
            worker.execute(() -> {
                boolean accepted = false;
                String failure = "PIN 不正确。";
                try {
                    accepted = first ? pin.create(entered) : pin.verify(entered);
                    if (accepted) preferences.edit().remove("pin_failures").remove("pin_retry_after").apply();
                    else if (!first) {
                        int count = preferences.getInt("pin_failures", 0) + 1;
                        SharedPreferences.Editor edit = preferences.edit().putInt("pin_failures", count % 5);
                        if (count >= 5) edit.putLong("pin_retry_after", SystemClock.elapsedRealtime() + 30000);
                        edit.apply();
                    } else failure = "PIN 保存失败，请重试。";
                } catch (Exception exception) {
                    failure = "无法验证 PIN，请重试。";
                }
                final boolean verified = accepted;
                final String message = failure;
                runOnUiThread(() -> {
                    if (pinDialog != dialog || !dialog.isShowing()) return;
                    if (!verified) {
                        error.setText(message);
                        input.setText("");
                        submit.setEnabled(true);
                        return;
                    }
                    InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
                    dialog.dismiss();
                    enableFamilyMode();
                    showParent();
                });
            });
        });
    }

    private enum ParentSection {
        APPS("应用审批"), SITES("开放网址"), USAGE("使用记录"), REMOTE("远程管理"),
        DESKTOP("桌面设置"), SETTINGS("家长设置"), ABOUT("产品说明");

        final String label;
        ParentSection(String label) { this.label = label; }
    }

    private ParentSection parentSection = ParentSection.REMOTE;

    private void showParent() {
        showParent(ParentSection.REMOTE);
    }

    private void showParent(ParentSection section) {
        parent = true;
        setDesktopNavigationHidden(false);
        parentSection = section;
        makePage();
        switch (section) {
            case SITES: showLocalParentPanel("websites"); return;
            case USAGE: showLocalParentPanel("usage"); return;
            case DESKTOP: showLocalParentPanel("desktop"); return;
            case SETTINGS: showLocalParentPanel("settings"); return;
            case ABOUT: showLocalParentPanel("about"); return;
            default: break;
        }
        add(text(section.label, 26, PARENT_INK), 0);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xffffffff);
        card.setStroke(dp(1), 0xffdae1ec);
        card.setCornerRadius(dp(14));
        content.setBackground(card);
        add(content, 16);
        root = content;
        switch (section) {
            case APPS: showAppApproval(); break;
            case REMOTE: showRemoteManagement(); break;
            default: break;
        }
    }

    private View parentSidebar() {
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(10), dp(16), dp(10), dp(12));
        sidebar.setBackgroundColor(0xff101f34);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_brand);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(7), 0, 0, 0);
        titles.addView(text("鸡肋OS", 16, 0xffffffff));
        titles.addView(text("家长端 · V5", 11, 0xffa8bdd9));
        brand.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        sidebar.addView(brand);

        ScrollView menu = new ScrollView(this);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(0, dp(15), 0, dp(4));
        for (ParentSection section : ParentSection.values()) {
            items.addView(parentNavButton(section.label, section == parentSection, () -> showParent(section)));
        }
        menu.addView(items);
        sidebar.addView(menu, new LinearLayout.LayoutParams(-1, 0, 1));
        sidebar.addView(parentNavButton("返回儿童端", false, this::showChild));
        sidebar.addView(parentNavButton("返回原系统", false, this::openStockHome));
        if (!kiosk.available()) sidebar.addView(parentNavButton("设置默认桌面", false, this::chooseHome));
        return sidebar;
    }

    private Button parentNavButton(String label, boolean selected, Runnable action) {
        Button item = button(label, action);
        item.setTextColor(selected ? 0xffffffff : 0xffd5e2f4);
        item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        item.setTextSize(14);
        item.setStateListAnimator(null);
        item.setElevation(0);
        item.setMinHeight(dp(42));
        item.setMinimumHeight(dp(42));
        item.setBackground(pressable(selected ? 0xff294d7b : 0xff101f34, 0xff101f34));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(2);
        item.setLayoutParams(params);
        return item;
    }

    private void showRemoteManagement() {
        String ip = FamilyService.wifiAddress(this);
        String address = ip.isEmpty() ? "连上家庭 Wi-Fi 后显示访问地址" : "http://" + ip + ":8766/";
        add(text(address, 17, BLUE), 6);
        if (ip.isEmpty()) {
            Button connect = button("连接家庭 Wi-Fi", this::openWifiSettings);
            connect.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.wifi_status, 0, 0, 0);
            connect.setCompoundDrawableTintList(ColorStateList.valueOf(BLUE));
            connect.setCompoundDrawablePadding(dp(8));
            add(connect, 4);
        }
        add(text(kiosk.locked() ? "儿童模式已锁定" : "儿童模式未锁定", 15, PARENT_MUTED), 5);
        add(button("授权屏幕查看", this::requestScreen), 8);
        if (FamilyService.running != null && FamilyService.running.screen.ready()) {
            add(button("停止屏幕授权", () -> {
                preferences.edit().putBoolean(RESTORE_SCREEN, false).apply();
                if (FamilyService.running != null) FamilyService.running.screen.close();
                showParent();
            }), 4);
        }
    }

    private void showAppApproval() {
        add(text("勾选孩子桌面可打开的应用，然后保存。", 15, PARENT_MUTED), 5);

        Set<String> saved = familyApps.approved();
        LinkedHashMap<String, CheckBox> toggles = new LinkedHashMap<>();
        for (FamilyApps.Entry app : familyApps.installed()) {
            String id = app.id();
            LinearLayout row = appRow(app);
            CheckBox check = new CheckBox(this);
            check.setButtonTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{BLUE, PARENT_MUTED}));
            check.setChecked(saved.contains(id));
            check.setContentDescription(app.label);
            row.addView(check);
            row.setOnClickListener(v -> check.toggle());
            toggles.put(id, check);
            add(row, 6);
        }
        Button save = button("保存应用白名单", () -> {
            if (!parent) return;
            Set<String> selection = new HashSet<>();
            toggles.forEach((id, check) -> { if (check.isChecked()) selection.add(id); });
            if (familyApps.save(selection)) {
                toast("白名单已保存");
            } else toast("保存失败，请重试");
        });
        save.setTextColor(0xffffffff);
        save.setBackground(pressable(BLUE, BLUE));
        add(save, 12);
    }

    private void showLocalParentPanel(String section) {
        page.removeAllViews();
        LocalParentPanel panel = new LocalParentPanel(this, section, () -> {
            if (parent) {
                toast("PIN 已修改，请重新登录");
                showChild();
            }
        }, this::openAuthorSite);
        localParentPanel = panel;
        panel.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public boolean onShowFileChooser(android.webkit.WebView view,
                    android.webkit.ValueCallback<android.net.Uri[]> callback, FileChooserParams params) {
                if (!parent || localParentPanel != panel || parentSection != ParentSection.DESKTOP
                        || parentAuxRequest != 0) return false;
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/*").setPackage("com.android.documentsui");
                try {
                    wallpaperPicker = callback;
                    parentAuxRequest = REQUEST_WALLPAPER;
                    startActivityForResult(picker, REQUEST_WALLPAPER);
                    return true;
                } catch (RuntimeException unavailable) {
                    wallpaperPicker = null;
                    parentAuxRequest = 0;
                    toast("无法打开系统照片选择器");
                    return false;
                }
            }
        });
        page.addView(panel, new LinearLayout.LayoutParams(-1, -1));
    }

    private void closeLocalParentPanel() {
        if (wallpaperPicker != null) {
            wallpaperPicker.onReceiveValue(null);
            wallpaperPicker = null;
        }
        if (localParentPanel == null) return;
        ViewGroup container = (ViewGroup) localParentPanel.getParent();
        if (container != null) container.removeView(localParentPanel);
        localParentPanel.closePanel();
        localParentPanel = null;
    }

    private void openAuthorSite() {
        if (!parent) return;
        parentAuxRequest = REQUEST_AUTHOR_SITE;
        try {
            Intent site = new Intent(this, ApprovedSiteActivity.class).putExtra("parentSite", true);
            startActivityForResult(site, REQUEST_AUTHOR_SITE);
        } catch (RuntimeException unavailable) {
            parentAuxRequest = 0;
            toast("暂时无法打开网站");
        }
    }

    private void openStockHome() {
        if (!parent) return;
        Intent home = homeIntent().setComponent(STOCK_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (getPackageManager().resolveActivity(home, 0) == null) {
            toast("找不到原系统桌面");
            return;
        }
        try {
            if (kiosk.locked()) stopLockTask();
            startActivity(home);
            showChild();
        } catch (RuntimeException exception) {
            enableFamilyMode();
            toast("原系统桌面暂时无法打开");
        }
    }

    private void chooseHome() {
        if (!parent) return;
        ComponentName alias = new ComponentName(this, getPackageName() + ".ChildHome");
        getPackageManager().setComponentEnabledSetting(alias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (RuntimeException exception) {
            toast("请在原系统的默认应用设置中选择桌面");
        }
    }

    private Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener appChanges = (store, key) -> {
        if (("approved_components".equals(key) || FamilyExtras.SITES.equals(key)
                || FamilyExtras.GREETING.equals(key) || FamilyExtras.APPEARANCE.equals(key))
                && inForeground && !parent && pinDialog == null) showChild();
    };

    private void enableFamilyMode() {
        if (!pin.configured()) return;
        if (FamilyService.running == null) startForegroundService(new Intent(this, FamilyService.class));
        try {
            if (kiosk.available() && !kiosk.locked()) {
                kiosk.configure(familyApps.approved());
                kiosk.enter(this);
            }
        } catch (RuntimeException exception) {
            toast("儿童模式暂未锁定");
        }
    }

    private void openWifiSettings() {
        if (!parent) return;
        try {
            if (kiosk.locked()) stopLockTask();
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            showChild();
        } catch (RuntimeException exception) {
            enableFamilyMode();
            toast("无法打开 Wi-Fi 设置");
        }
    }


    private void requestScreen() {
        if (!parent) return;
        FamilyService service = FamilyService.running;
        if (service == null) {
            toast("家长服务尚未就绪");
            return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        if (manager == null) return;
        try {
            if (kiosk.locked()) stopLockTask();
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN);
        } catch (RuntimeException exception) {
            enableFamilyMode();
            toast("无法打开屏幕授权");
        }
    }

    private void restoreScreenWhenReady(int retries) {
        if (!inForeground || parent || pinDialog != null || !kiosk.locked()
                || !preferences.getBoolean(RESTORE_SCREEN, true)) return;
        FamilyService service = FamilyService.running;
        if (service == null) {
            if (retries > 0) handler.postDelayed(() -> restoreScreenWhenReady(retries - 1), 500);
            return;
        }
        if (service.screenRestoreAttempted || service.screen.ready()) return;
        service.screenRestoreAttempted = true;
        if (!hasRememberedScreenPermission()) return;
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        if (manager == null) return;
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_RESTORE_SCREEN);
        } catch (RuntimeException ignored) {
            // Keep the kiosk locked and leave manual authorization available.
        }
    }

    private boolean hasRememberedScreenPermission() {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1) return false;
        AppOpsManager manager = getSystemService(AppOpsManager.class);
        if (manager == null) return false;
        try {
            // Android 8.1 exposes PROJECT_MEDIA only through the numeric AppOps API.
            int operation = AppOpsManager.class.getField("OP_PROJECT_MEDIA").getInt(null);
            Object mode = AppOpsManager.class.getMethod("checkOpNoThrow", int.class, int.class, String.class)
                    .invoke(manager, operation, Process.myUid(), getPackageName());
            return Integer.valueOf(AppOpsManager.MODE_ALLOWED).equals(mode);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_WALLPAPER) {
            if (parentAuxRequest != REQUEST_WALLPAPER) return;
            parentAuxRequest = 0;
            android.webkit.ValueCallback<android.net.Uri[]> callback = wallpaperPicker;
            wallpaperPicker = null;
            if (callback != null) {
                android.net.Uri selected = data == null ? null : data.getData();
                callback.onReceiveValue(parent && result == RESULT_OK && selected != null
                        && "content".equals(selected.getScheme()) ? new android.net.Uri[]{selected} : null);
            }
            return;
        }
        if (request == REQUEST_AUTHOR_SITE) {
            if (parentAuxRequest == REQUEST_AUTHOR_SITE) parentAuxRequest = 0;
            return;
        }
        if (request != REQUEST_SCREEN && request != REQUEST_RESTORE_SCREEN) return;
        FamilyService service = FamilyService.running;
        boolean manual = request == REQUEST_SCREEN;
        if (result == RESULT_OK && data != null && service != null
                && (manual || preferences.getBoolean(RESTORE_SCREEN, true))) {
            try {
                service.screen.authorize(result, data);
                if (manual) {
                    preferences.edit().putBoolean(RESTORE_SCREEN, true).apply();
                    toast("屏幕已授权，可从家长网页按需查看");
                }
            } catch (RuntimeException exception) {
                if (manual) toast("屏幕授权未生效，请重试");
            }
        }
        enableFamilyMode();
    }

    private void launchApproved(ComponentName component) {
        if (!familyApps.approved().contains(component.flattenToString())) return;
        if (kiosk.available() && !kiosk.locked()) {
            toast("请先启动儿童锁定");
            return;
        }
        Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component);
        try {
            startActivity(launch);
            HomeKeyService.foreground(component.getPackageName(), component.getClassName());
        } catch (RuntimeException exception) {
            toast("应用暂不可用，请让家长检查");
        }
    }

    private void stopWallpaperMotion() {
        if (wallpaperMotion != null) {
            wallpaperMotion.cancel();
            wallpaperMotion = null;
        }
    }

    private void makePage() {
        closeLocalParentPanel();
        stopWallpaperMotion();
        clock = date = null;
        screenMode = null;
        musicControls = null;
        musicTitle = null;
        wifi = null;
        wifiConnected = null;
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        if (parent) page.setBackgroundColor(PARENT_BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        boolean portraitParent = parent && getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        int sidePadding = dp(portraitParent ? 14 : 22);
        root.setPadding(sidePadding, dp(16), sidePadding, dp(22));
        if (parent) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.addView(root);
            page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout shell = new LinearLayout(this);
            int width = getResources().getConfiguration().screenWidthDp;
            int sidebarWidth = portraitParent ? Math.max(118, Math.min(144, width / 4))
                    : Math.max(118, Math.min(216, width / 3));
            shell.addView(parentSidebar(), new LinearLayout.LayoutParams(dp(sidebarWidth), -1));
            shell.addView(page, new LinearLayout.LayoutParams(0, -1, 1));
            setContentView(shell);
        } else {
            page.addView(root, new LinearLayout.LayoutParams(-1, 0, 1));
            FrameLayout desktop = new FrameLayout(this);
            desktop.setBackgroundColor(0xff061326);
            ImageView wallpaper = new ImageView(this);
            org.json.JSONObject appearance = extras.appearance();
            String choice = appearance.optString("wallpaper", "original");
            Bitmap photo = "custom".equals(choice)
                    ? extras.customWallpaper(appearance.optString("customWallpaper")) : null;
            if (photo != null) wallpaper.setImageBitmap(photo);
            else {
                switch (choice) {
                    case "aurora": wallpaper.setImageResource(R.drawable.wallpaper_aurora); break;
                    case "cosmos": wallpaper.setImageResource(R.drawable.wallpaper_cosmos); break;
                    case "dune": wallpaper.setImageResource(R.drawable.wallpaper_dune); break;
                    default: wallpaper.setImageResource(R.drawable.desktop_wallpaper);
                }
            }
            wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
            wallpaper.setColorFilter(0xa5061326);
            desktop.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
            desktop.addView(page, new FrameLayout.LayoutParams(-1, -1));
            setContentView(desktop);
            if (inForeground && appearance.optBoolean("motion") && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                wallpaper.setScaleX(1.08f);
                wallpaper.setScaleY(1.08f);
                wallpaperMotion = android.animation.ObjectAnimator.ofFloat(wallpaper, View.TRANSLATION_X, -dp(8), dp(8));
                wallpaperMotion.setDuration(16000);
                wallpaperMotion.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                wallpaperMotion.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                wallpaperMotion.start();
            }
        }
    }

    private LinearLayout appRow(FamilyApps.Entry app) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(6), dp(10), dp(6));
        row.setMinimumHeight(dp(52));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(9));
        row.setBackground(bg);
        ImageView image = new ImageView(this);
        image.setImageDrawable(app.info.loadIcon(getPackageManager()));
        row.addView(image, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView label = text(app.label, 17, PARENT_INK);
        label.setPadding(dp(11), 0, dp(6), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void add(android.view.View view, int gap) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2);
        layout.topMargin = dp(gap);
        root.addView(view, layout);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextColor(parent ? 0xff254f8a : INK);
        view.setTextSize(14);
        view.setMinHeight(dp(44));
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackground(pressable(parent ? CARD : 0xad142b48, parent ? 0xffd5e1ef : 0x607394ba));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(dp(3), dp(4), dp(3), dp(4));
        view.setLayoutParams(params);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private ImageButton iconButton(int icon, String label, int tint, Runnable action) {
        ImageButton view = new ImageButton(this);
        view.setImageResource(icon);
        view.setImageTintList(ColorStateList.valueOf(tint));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setContentDescription(label);
        view.setTooltipText(label);
        view.setFocusable(true);
        view.setBackground(pressable(0xad142b48, 0x607394ba));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(49), dp(49));
        params.setMargins(dp(5), dp(4), dp(1), dp(4));
        view.setLayoutParams(params);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private RippleDrawable pressable(int fill, int border) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setStroke(dp(1), border);
        background.setCornerRadius(dp(12));
        return new RippleDrawable(ColorStateList.valueOf(0x3388b8fa), background, null);
    }

    private EditText pinField(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        input.setSaveEnabled(false);
        input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        return input;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

}
