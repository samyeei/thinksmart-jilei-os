package local.jile.v5;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.json.JSONObject;

final class SiteLoginDialog {
    static void show(Activity activity, SiteLoginStore store, Supplier<String> currentOrigin,
            Consumer<Boolean> changed) {
        String origin = currentOrigin.get();
        if (origin == null) return;
        JSONObject existing = null;
        try { existing = store.load(origin); } catch (Exception ignored) {}
        JSONObject old = existing;
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding / 2, padding, padding / 2);
        TextView note = new TextView(activity);
        note.setText(origin + "\n账号密码加密保存在本机。填好后由你点击登录。");
        layout.addView(note);
        EditText user = field(activity, "用户名或邮箱", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        if (old != null) user.setText(old.optString("username"));
        layout.addView(user);
        EditText pass = field(activity, old == null ? "密码" : "新密码（留空保留原密码）",
                InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(pass);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("记住网站账号密码").setView(layout)
                .setPositiveButton("保存", null).setNegativeButton("取消", null)
                .setNeutralButton("清除已保存", null).create();
        dialog.setOnShowListener(event -> {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (!origin.equals(currentOrigin.get())) {
                    dialog.dismiss();
                    return;
                }
                String username = user.getText().toString().trim();
                String password = pass.getText().toString();
                if (password.isEmpty() && old != null && username.equals(old.optString("username"))) {
                    password = old.optString("password");
                }
                if (username.isEmpty() || password.isEmpty()) {
                    toast(activity, "请填写用户名和密码");
                    return;
                }
                try {
                    store.save(origin, username, password);
                    dialog.dismiss();
                    changed.accept(false);
                    toast(activity, "已记住此网站账号");
                } catch (Exception error) {
                    toast(activity, "保存失败，请重试");
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                try {
                    store.clear(origin);
                    dialog.dismiss();
                    if (origin.equals(currentOrigin.get())) changed.accept(true);
                    toast(activity, "已清除保存的账号密码");
                } catch (Exception error) {
                    toast(activity, "清除失败，请重试");
                }
            });
        });
        dialog.setOnDismissListener(event -> pass.getText().clear());
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }

    private static EditText field(Activity activity, String hint, int variation) {
        EditText view = new EditText(activity);
        view.setHint(hint);
        view.setSingleLine(true);
        view.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        view.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return view;
    }

    private static void toast(Activity activity, String text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }

    private SiteLoginDialog() {}
}
