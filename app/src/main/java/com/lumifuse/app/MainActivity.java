package com.lumifuse.app;

import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.*;
import org.opencv.android.OpenCVLoader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 保持 UI、照片读取与融合算法分离；文件选择器授权访问，不申请整个相册权限。 */
public final class MainActivity extends Activity {
    private static final int PICK_IMAGES = 42;
    private static final int[] EDGES = {1280, 2048, 3072};
    private final ArrayList<Uri> selected = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Spinner mode, size;
    private CheckBox align;
    private Button pick, run, save, compare;
    private TextView status;
    private ImageView preview;
    private ProgressBar progress;
    private Bitmap result, original;
    private boolean busy, showingOriginal;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            ArrayList<String> values = state.getStringArrayList("selected");
            if (values != null) for (String value : values) selected.add(Uri.parse(value));
        }
        buildUi();
        if (!OpenCVLoader.initLocal()) {
            status.setText("图像处理库初始化失败，请重新安装应用");
            run.setEnabled(false);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setBackgroundColor(Color.rgb(250, 248, 255));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(20), dp(12) + insets.getSystemWindowInsetTop(),
                    dp(20), dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = label("LumiFuse", 28);
        title.setTypeface(null, Typeface.BOLD); root.addView(title);
        root.addView(label("让包围照片，在手机里成为一张好照片", 13));
        mode = spinner(new String[]{"HDR · 不同曝光", "景深 · 不同焦点"}); root.addView(mode);
        pick = button("选择 2～7 张 JPG", root); pick.setOnClickListener(view -> pickImages());
        size = spinner(new String[]{"快速 · 长边 1280", "标准 · 长边 2048", "精细 · 长边 3072（内存占用较高）"});
        size.setSelection(1); root.addView(size);
        align = new CheckBox(this); align.setText("自动对齐并裁掉黑边"); align.setChecked(true); root.addView(align);
        run = button("开始合成", root); run.setOnClickListener(view -> process());
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true); progress.setVisibility(View.GONE); root.addView(progress);
        status = label(selected.isEmpty() ? "尚未选择照片 · 离线处理 · 不上传原片" : "已恢复 " + selected.size() + " 张照片，请重新合成", 13);
        root.addView(status);
        preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("照片预览");
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        compare = button("查看参考原图", actions); compare.setEnabled(false);
        compare.setOnClickListener(view -> {
            showingOriginal = !showingOriginal;
            preview.setImageBitmap(showingOriginal ? original : result);
            compare.setText(showingOriginal ? "查看合成结果" : "查看参考原图");
        });
        save = button("保存 JPG", actions); save.setEnabled(false);
        save.setOnClickListener(view -> saveResult()); root.addView(actions);
        setContentView(root);
    }

    private void pickImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/jpeg"); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_IMAGES);
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request != PICK_IMAGES || code != RESULT_OK || data == null) return;
        ArrayList<Uri> incoming = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (!incoming.contains(uri)) incoming.add(uri);
            }
        } else if (data.getData() != null) incoming.add(data.getData());
        if (incoming.size() < 2 || incoming.size() > 7) {
            status.setText("请选择 2～7 张不同的 JPG，当前选择未采用"); return;
        }
        for (Uri uri : incoming) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { /* 部分文件提供者仅支持当前会话授权。 */ }
        }
        clearResult(); selected.clear(); selected.addAll(incoming);
        status.setText("已选择 " + selected.size() + " 张 · HDR 用不同曝光，景深用不同焦点");
    }

    private void process() {
        if (busy) return;
        if (selected.size() < 2) { status.setText("请先选择 2～7 张 JPG"); return; }
        final List<Uri> inputs = new ArrayList<>(selected);
        final int edge = EDGES[size.getSelectedItemPosition()];
        final boolean hdr = mode.getSelectedItemPosition() == 0, useAlignment = align.isChecked();
        clearResult(); setBusy(true); status.setText("准备处理…");
        worker.execute(() -> {
            Bitmap fused = null, first = null;
            try {
                fused = Fusion.merge(getContentResolver(), inputs, edge, hdr, useAlignment,
                        message -> runOnUiThread(() -> { if (!isDestroyed()) status.setText(message); }));
                first = ImageLoader.load(getContentResolver(), inputs.get(0), 1280);
                final Bitmap output = fused, source = first;
                runOnUiThread(() -> {
                    if (isDestroyed()) { output.recycle(); source.recycle(); return; }
                    result = output; original = source; preview.setImageBitmap(result); setBusy(false);
                    status.setText("合成完成 · " + result.getWidth() + " × " + result.getHeight()
                            + " · " + inputs.size() + " 张\n" + (hdr ? "曝光融合 JPG，不是 HDR DNG" : "景深融合：遮挡边缘可能出现光晕"));
                });
            } catch (Exception | OutOfMemoryError error) {
                if (fused != null) fused.recycle(); if (first != null) first.recycle();
                reportFailure(error instanceof OutOfMemoryError ? "内存紧张，请选择快速档或减少照片数量" : error.getMessage());
            }
        });
    }

    private void saveResult() {
        if (busy || result == null) return;
        final Bitmap image = result;
        setBusy(true); status.setText("正在保存…");
        worker.execute(() -> {
            Uri uri = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "LumiFuse_" + System.currentTimeMillis() + ".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LumiFuse");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("相册创建文件失败");
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null || !image.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                        throw new IOException("JPG 写入失败");
                    }
                }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (getContentResolver().update(uri, values, null, null) != 1) throw new IOException("相册发布失败");
                runOnUiThread(() -> {
                    if (!isDestroyed()) { setBusy(false); status.setText("已保存到相册 · Pictures/LumiFuse · 原照片未修改"); }
                });
            } catch (Exception error) {
                // 仅清理本次生成的未发布空文件，绝不删除输入照片。
                if (uri != null) {
                    try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                }
                reportFailure("保存失败：" + error.getMessage());
            } finally {
                if (isDestroyed()) image.recycle();
            }
        });
    }

    private void reportFailure(String message) {
        runOnUiThread(() -> { if (!isDestroyed()) { setBusy(false); status.setText("处理未完成：" + message); } });
    }

    private void setBusy(boolean value) {
        busy = value; pick.setEnabled(!value); run.setEnabled(!value);
        mode.setEnabled(!value); size.setEnabled(!value); align.setEnabled(!value);
        save.setEnabled(!value && result != null); compare.setEnabled(!value && original != null);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private void clearResult() {
        preview.setImageDrawable(null);
        if (result != null) result.recycle(); if (original != null) original.recycle();
        result = null; original = null; showingOriginal = false;
        save.setEnabled(false); compare.setEnabled(false); compare.setText("查看参考原图");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        ArrayList<String> values = new ArrayList<>();
        for (Uri uri : selected) values.add(uri.toString());
        state.putStringArrayList("selected", values); super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        // 保存线程持有 result 时由该线程释放，避免压缩过程中的位图回收。
        if (!busy && result != null) result.recycle();
        if (original != null) original.recycle();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size);
        view.setTextColor(Color.rgb(50, 42, 65)); view.setPadding(0, dp(4), 0, dp(4)); return view;
    }
    private Spinner spinner(String[] options) {
        Spinner view = new Spinner(this);
        view.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options)); return view;
    }
    private Button button(String text, LinearLayout parent) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false);
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) parent.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
        else parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
}
