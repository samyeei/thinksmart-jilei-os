package local.jile.v5;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse;

final class SiteImageChooser {
    static final int PICK_IMAGE = 61;
    static final int TAKE_PHOTO = 62;
    static final int CAMERA_PERMISSION = 63;
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private final Activity activity;
    private final Predicate<String> allowed;
    private final File directory;
    private final List<File> submitted = new ArrayList<>();
    private FilePrompt prompt;
    private GeckoResult<PromptResponse> pending;
    private File capture;
    private Uri captureUri;
    private boolean closed;

    SiteImageChooser(Activity activity, Predicate<String> allowed) {
        this.activity = activity;
        this.allowed = allowed;
        directory = new File(activity.getCacheDir(), "site-photos");
        File[] oldFiles = directory.listFiles();
        if (oldFiles != null) {
            for (File file : oldFiles) {
                if (System.currentTimeMillis() - file.lastModified() > 3600000) file.delete();
            }
        }
    }

    GeckoResult<PromptResponse> show(FilePrompt request) {
        boolean pick = allowed.test("photos");
        boolean camera = allowed.test("camera");
        if (pending != null || (!pick && !camera) || request.type == FilePrompt.Type.FOLDER
                || !acceptsImages(request.mimeTypes)) return GeckoResult.fromValue(request.dismiss());
        prompt = request;
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        pending = result;
        List<String> choices = new ArrayList<>();
        if (pick) choices.add("选择本机图片");
        if (camera) choices.add("拍照");
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("添加图片")
                .setItems(choices.toArray(new String[0]), (view, index) -> {
                    if ("拍照".equals(choices.get(index))) requestCamera();
                    else pick();
                })
                .setNegativeButton("取消", (view, which) -> finish(null)).create();
        dialog.setOnCancelListener(view -> finish(null));
        dialog.show();
        return result;
    }

    private static boolean acceptsImages(String[] types) {
        if (types == null || types.length == 0) return true;
        for (String type : types) {
            String value = type.toLowerCase(Locale.ROOT);
            if (value.startsWith("image/") || value.equals("*/*")
                    || value.matches("\\.(jpg|jpeg|png|webp|gif|bmp)")) return true;
        }
        return false;
    }

    private void pick() {
        if (!allowed.test("photos")) { finish(null); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                .setPackage("com.android.documentsui")
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launch(intent, PICK_IMAGE);
    }

    private void requestCamera() {
        if (!allowed.test("camera")) { finish(null); return; }
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        camera();
    }

    void cameraPermissionResult(int[] grants) {
        if (pending == null) return;
        if (grants.length == 1 && grants[0] == PackageManager.PERMISSION_GRANTED) camera();
        else finish(null);
    }

    private void camera() {
        if (!allowed.test("camera")) { finish(null); return; }
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        ResolveInfo system = activity.getPackageManager().resolveActivity(intent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY);
        if (system == null) { unavailable(); return; }
        try {
            directory.mkdirs();
            capture = File.createTempFile("camera-", ".jpg", directory);
            captureUri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".sitephotos", capture);
            intent.setClassName(system.activityInfo.packageName, system.activityInfo.name);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, captureUri);
            intent.setClipData(ClipData.newRawUri("拍摄图片", captureUri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            launch(intent, TAKE_PHOTO);
        } catch (IOException | RuntimeException error) {
            unavailable();
        }
    }

    private void launch(Intent intent, int request) {
        try { activity.startActivityForResult(intent, request); }
        catch (RuntimeException error) { unavailable(); }
    }

    private void unavailable() {
        Toast.makeText(activity, "系统相机或图片选择器暂不可用", Toast.LENGTH_SHORT).show();
        finish(null);
    }

    void result(int request, int result, Intent data) {
        if (pending == null) return;
        boolean camera = request == TAKE_PHOTO;
        String permission = camera ? "camera" : "photos";
        if (result != Activity.RESULT_OK || !allowed.test(permission)) { finish(null); return; }
        if (camera) {
            File file = capture;
            if (!validImage(file)) { finish(null); return; }
            capture = null;
            finish(file);
            return;
        }
        Uri uri = data == null ? null : data.getData();
        if (uri == null || !"content".equals(uri.getScheme())) { finish(null); return; }
        GeckoResult<PromptResponse> requestTicket = pending;
        new Thread(() -> {
            File file = copy(uri);
            activity.runOnUiThread(() -> {
                if (closed || requestTicket != pending) {
                    if (file != null) file.delete();
                    return;
                }
                if (file != null && !allowed.test(permission)) { file.delete(); finish(null); }
                else finish(file);
            });
        }, "site-image-copy").start();
    }

    private File copy(Uri uri) {
        File file = null;
        try {
            directory.mkdirs();
            file = File.createTempFile("image-", ".tmp", directory);
            try (InputStream input = activity.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(file)) {
                if (input == null) throw new IOException();
                byte[] buffer = new byte[16384];
                long size = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > MAX_BYTES) throw new IOException();
                    output.write(buffer, 0, count);
                }
            }
            BitmapFactory.Options bounds = imageBounds(file);
            if (bounds == null) throw new IOException();
            String extension = bounds.outMimeType.substring("image/".length()).replace("jpeg", "jpg");
            File image = new File(directory, file.getName().replace(".tmp", "." + extension));
            if (file.renameTo(image)) return image;
        } catch (IOException | RuntimeException ignored) {}
        if (file != null) file.delete();
        return null;
    }

    private static BitmapFactory.Options imageBounds(File file) {
        if (file == null || file.length() == 0 || file.length() > MAX_BYTES) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        return bounds.outWidth > 0 && bounds.outHeight > 0
                && bounds.outWidth <= 20000 && bounds.outHeight <= 20000
                && bounds.outMimeType != null && bounds.outMimeType.matches("image/(jpeg|png|gif|webp|bmp)")
                ? bounds : null;
    }

    private static boolean validImage(File file) { return imageBounds(file) != null; }

    void cancel() { finish(null); }

    void close() {
        closed = true;
        cancel();
        for (File file : submitted) file.delete();
    }

    private void finish(File file) {
        if (captureUri != null) {
            activity.revokeUriPermission(captureUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            captureUri = null;
        }
        if (capture != null) { capture.delete(); capture = null; }
        GeckoResult<PromptResponse> result = pending;
        pending = null;
        FilePrompt request = prompt;
        prompt = null;
        if (result == null || request.isComplete()) {
            if (file != null) file.delete();
            return;
        }
        if (file == null) result.complete(request.dismiss());
        else {
            submitted.add(file);
            result.complete(request.confirm(activity, Uri.fromFile(file)));
        }
    }
}
