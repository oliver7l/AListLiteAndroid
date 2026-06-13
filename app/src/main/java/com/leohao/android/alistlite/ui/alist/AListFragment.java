package com.leohao.android.alistlite.ui.alist;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import cn.hutool.extra.qrcode.QrCodeUtil;
import cn.hutool.http.Method;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.google.zxing.common.BitMatrix;
import com.kyleduo.switchbutton.SwitchButton;
import com.leohao.android.alistlite.MainActivity;
import com.leohao.android.alistlite.R;
import com.leohao.android.alistlite.interfaces.DownloadBlobFileJsInterface;
import com.leohao.android.alistlite.model.Alist;
import com.leohao.android.alistlite.util.AppUtil;
import com.leohao.android.alistlite.util.ClipBoardHelper;
import com.leohao.android.alistlite.util.Constants;
import com.leohao.android.alistlite.util.MyHttpUtil;
import com.leohao.android.alistlite.util.SharedDataHelper;
import com.leohao.android.alistlite.window.PopupMenuWindow;
import com.yuyh.jsonviewer.library.JsonRecyclerView;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class AListFragment extends Fragment {

    private static final String TAG = "AListFragment";

    private MainActivity mainActivity;
    public WebView webView;
    public TextView runningInfoTextView;
    public SwitchButton serviceSwitch;
    public TextView appInfoTextView;
    private PopupMenuWindow popupMenuWindow;
    private Alist alistServer;
    private final ClipBoardHelper clipBoardHelper = ClipBoardHelper.getInstance();

    // 文件上传回调
    private ValueCallback<Uri[]> mFilePathCallback;
    private ValueCallback<Uri> mUploadMessage;
    private static final int FILE_CHOOSER_REQUEST_CODE = 100;

    public AListFragment() {
        super(R.layout.fragment_alist);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainActivity = (MainActivity) requireActivity();
        alistServer = Alist.getInstance();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initWidgets(view);
        // 焦点设置（稍延迟，等视图稳定）
        view.postDelayed(() -> initFocusSettings(), 500);
        // 权限检查只在 Activity 中做一次，Fragment 不再重复
    }

    private void initWidgets(View view) {
        Toolbar toolbar = view.findViewById(R.id.toolbar);
        mainActivity.setSupportActionBar(toolbar);
        Objects.requireNonNull(mainActivity.getSupportActionBar()).setDisplayShowTitleEnabled(false);

        serviceSwitch = view.findViewById(R.id.switchButton);
        appInfoTextView = view.findViewById(R.id.tv_app_info);
        runningInfoTextView = view.findViewById(R.id.tv_alist_status);
        webView = view.findViewById(R.id.webview_alist);

        // 初始化弹出菜单
        popupMenuWindow = new PopupMenuWindow(requireContext());
        popupMenuWindow.setOnDismissListener(() -> backgroundAlpha(1.0f));

        // 初始化 WebView
        initWebview(view);

        // 获取版本号
        String currentAppVersion = getCurrentAppVersion();
        String currentAlistVersion = getCurrentAlistVersion();

        // 服务开关监听
        serviceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) {
                mainActivity.readyToShutdownService();
                return;
            }
            try {
                mainActivity.readyToStartService();
            } catch (Exception e) {
                Log.d(TAG, e.getLocalizedMessage());
            }
        });

        // 默认开启服务（只触发一次，避免重复）
        if (!serviceSwitch.isChecked()) {
            serviceSwitch.setChecked(true);
        }

        // 刷新按钮
        view.findViewById(R.id.btn_refresh).setOnClickListener(v -> refreshWebPage());
        // WebView 前进后退
        view.findViewById(R.id.btn_webViewGoBack).setOnClickListener(v -> webViewGoBack());
        view.findViewById(R.id.btn_webViewGoForward).setOnClickListener(v -> webViewGoForward());
        // 弹出菜单
        view.findViewById(R.id.btn_showPopupMenu).setOnClickListener(v -> showPopupMenu(v));
        // App 标题点击跳首页
        appInfoTextView.setOnClickListener(v -> jumpToHomepage());
        // 状态栏点击复制地址
        runningInfoTextView.setOnClickListener(v -> copyAddressToClipboard());
    }

    private void initFocusSettings() {
        appInfoTextView.postDelayed(() -> appInfoTextView.requestFocus(), 1000);
        // TV 端焦点边框
        List<View> views = AppUtil.getAllViews(requireActivity());
        views.addAll(AppUtil.getAllChildViews(popupMenuWindow.getContentView()));
        for (View view : views) {
            view.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.setBackgroundResource(R.drawable.background_border);
                } else {
                    v.setBackground(null);
                }
            });
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebview(View rootView) {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.removeJavascriptInterface("searchBoxJavaBredge_");
        webView.addJavascriptInterface(new DownloadBlobFileJsInterface(requireContext()), "Android");

        webView.setWebChromeClient(new WebChromeClient() {
            private View mCustomView;
            private CustomViewCallback mCustomViewCallback;
            final FrameLayout videoContainer = rootView.findViewById(R.id.video_container);

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    mFilePathCallback = null;
                    showToast("无法打开文件选择器");
                    return false;
                }
                return true;
            }

            public void openFileChooser(ValueCallback<Uri> uploadMsg) {
                mUploadMessage = uploadMsg;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                startActivity(Intent.createChooser(intent, "选择文件"));
            }

            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType) {
                mUploadMessage = uploadMsg;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType(acceptType);
                startActivity(Intent.createChooser(intent, "选择文件"));
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                super.onShowCustomView(view, callback);
                if (mCustomView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                mCustomView = view;
                videoContainer.addView(mCustomView);
                mCustomViewCallback = callback;
                webView.setVisibility(View.GONE);
                Objects.requireNonNull(mainActivity.getSupportActionBar()).hide();
                requireActivity().getWindow().getDecorView()
                    .setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }

            @Override
            public void onHideCustomView() {
                webView.setVisibility(View.VISIBLE);
                if (mCustomView == null) return;
                mCustomView.setVisibility(View.GONE);
                videoContainer.removeView(mCustomView);
                mCustomViewCallback.onCustomViewHidden();
                mCustomView = null;
                Objects.requireNonNull(mainActivity.getSupportActionBar()).show();
                requireActivity().getWindow().getDecorView().setSystemUiVisibility(0);
                requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                super.onHideCustomView();
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url.contains("5244") || url.contains(mainActivity.serverAddress.replace("http://", "").replace("https://", ""))) {
                    view.evaluateJavascript("window.__vite_is_modern_browser=true;", null);
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return super.shouldOverrideUrlLoading(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return super.shouldOverrideUrlLoading(view, url);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (error.getPrimaryError() == SslError.SSL_UNTRUSTED) {
                        handler.proceed();
                    } else {
                        super.onReceivedSslError(view, handler, error);
                    }
                } else {
                    super.onReceivedSslError(view, handler, error);
                }
            }
        });
    }

    // ====== 工具方法 ======

    private String getCurrentAppVersion() {
        String versionName = "unknown";
        try {
            versionName = requireContext().getPackageManager()
                .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "getCurrentVersion: ", e);
        }
        return versionName;
    }

    private String getCurrentAlistVersion() {
        return Constants.OPENLIST_VERSION;
    }

    void showToast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    // ====== 页面操作 ======

    public void jumpToHomepage() {
        if (alistServer.hasRunning()) {
            webView.loadUrl(mainActivity.serverAddress);
        } else {
            showToast("AList 服务未启动");
        }
    }

    public void refreshWebPage() {
        webView.reload();
    }

    public void webViewGoBack() {
        if (webView.canGoBack()) {
            webView.goBack();
        }
    }

    public void webViewGoForward() {
        if (webView.canGoForward()) {
            webView.goForward();
        }
    }

    /** 处理 Fragment 内的返回键，返回 true 表示已消费 */
    public boolean handleBack() {
        if (webView.canGoBack() && alistServer != null && alistServer.hasRunning()) {
            webView.goBack();
            return true;
        }
        return false;
    }

    /** AList 服务已启动（由 MainActivity 转发） */
    public void onServiceStarted(String address) {
        serviceSwitch.setCheckedNoEvent(true);
        webView.loadUrl(address);
        runningInfoTextView.setVisibility(View.GONE);
    }

    /** AList 服务已停止（由 MainActivity 转发） */
    public void onServiceStopped() {
        serviceSwitch.setCheckedNoEvent(false);
        webView.reload();
        runningInfoTextView.setVisibility(View.VISIBLE);
        runningInfoTextView.setText(R.string.alist_service_not_running);
    }

    // ====== 弹出菜单 ======

    private void showPopupMenu(View view) {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        popupMenuWindow.showAsDropDown(view, 0, 50);
        backgroundAlpha(0.6f);
    }

    private void backgroundAlpha(float bgAlpha) {
        WindowManager.LayoutParams lp = requireActivity().getWindow().getAttributes();
        lp.alpha = bgAlpha;
        requireActivity().getWindow().setAttributes(lp);
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }

    // ====== 对话框方法（从 MainActivity 原样迁移） ======

    public void setAdminPassword(View view) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("设置管理员密码");
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 10);

        final EditText usernameInput = new EditText(requireContext());
        usernameInput.setHint("管理员账号（留空则不变）");
        usernameInput.setText(alistServer.getAdminUser());
        layout.addView(usernameInput);

        final EditText pwdInput = new EditText(requireContext());
        pwdInput.setHint("新密码");
        pwdInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        layout.addView(pwdInput);

        builder.setView(layout);
        builder.setView(layout);
        builder.setPositiveButton("确定", (dialog, which) -> {
            String pwd = pwdInput.getText().toString().trim();
            if (!TextUtils.isEmpty(pwd)) {
                try {
                    String username = usernameInput.getText().toString().trim();
                    if (!TextUtils.isEmpty(username)) {
                        // 用户名通过 Alist API 设置
                        try {
                            SharedDataHelper.getInstance().putSharedData(Constants.ANDROID_SHARED_DATA_KEY_WEBDAV_USERNAME, username);
                        } catch (Exception ignored) {}
                    }
                    alistServer.setAdminPassword(pwd);
                    SharedDataHelper.getInstance().putSharedData(Constants.ANDROID_SHARED_DATA_KEY_WEBDAV_PASSWORD, pwd);
                    try {
                        SharedDataHelper.getInstance().putSharedData(Constants.ANDROID_SHARED_DATA_KEY_WEBDAV_USERNAME, alistServer.getAdminUser());
                    } catch (Exception ignored) {}
                    showToast(String.format("管理员密码已更新：%s | %s", alistServer.getAdminUser(), pwd));
                } catch (Exception e) {
                    showToast("管理员密码设置失败");
                    Log.e(TAG, "setAdminPassword: ", e);
                }
            } else {
                showToast("管理员密码不能为空");
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    public void showQrCode(View view) {
        if (alistServer == null || !alistServer.hasRunning()) {
            showToast("请先启动服务");
            return;
        }
        String url = mainActivity.serverAddress;
        ImageView imageView = new ImageView(requireContext());
        imageView.setImageBitmap(bitMatrixToBitmap(QrCodeUtil.encode(url, 500, 500)));
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        imageView.setOnClickListener(v -> openExternalUrl(url));

        FrameLayout layout = new FrameLayout(requireContext());
        int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5, getResources().getDisplayMetrics());
        layout.setPadding(padding, padding, padding, padding);
        layout.addView(imageView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        AlertDialog.Builder dialog = new AlertDialog.Builder(requireContext());
        AlertDialog alertDialog = dialog.create();
        alertDialog.setTitle("远程访问");
        alertDialog.setView(layout);
        alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, "复制地址", (d, w) -> {
            clipBoardHelper.copyText(url);
            showToast("已复制");
        });
        alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "关闭", (d, w) -> {});
        alertDialog.show();
    }

    private Bitmap bitMatrixToBitmap(BitMatrix bitMatrix) {
        int width = bitMatrix.getWidth();
        int height = bitMatrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    public void copyAddressToClipboard() {
        if (alistServer != null && alistServer.hasRunning()) {
            clipBoardHelper.copyText(mainActivity.serverAddress);
            showToast("AList 服务地址已复制");
        }
    }

    // ====== 管理功能（从 MainActivity 迁移） ======

    public void manageConfigData() {
        AlertDialog configDataDialog = new AlertDialog.Builder(requireContext()).create();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View dialogView = inflater.inflate(R.layout.config_view, null);
        JsonRecyclerView jsonView = dialogView.findViewById(R.id.json_view_config);
        ImageButton editButton = dialogView.findViewById(R.id.btn_edit_config);
        EditText jsonEditText = dialogView.findViewById(R.id.edit_text_config);
        jsonView.setTextSize(14);

        String dataPath = requireContext().getExternalFilesDir("data").getAbsolutePath();
        String configPath = String.format("%s%s%s", dataPath, File.separator, Constants.ALIST_CONFIG_FILENAME);
        String configJsonData;
        File configFile = new File(configPath);
        try {
            configJsonData = FileUtils.readFileToString(configFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            configJsonData = Constants.ERROR_MSG_CONFIG_DATA_READ.replace("MSG", e.getLocalizedMessage());
            editButton.setVisibility(View.INVISIBLE);
        }
        jsonView.bindJson(configJsonData);
        configDataDialog.setView(dialogView);
        configDataDialog.show();
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        if (width < height) {
            configDataDialog.getWindow().setLayout(width - 50, height * 2 / 5);
        } else {
            configDataDialog.getWindow().setLayout(width * 5 / 6, height - 200);
        }
        String finalConfigJsonData = configJsonData;
        AtomicBoolean isEditing = new AtomicBoolean(false);
        editButton.setOnClickListener(v -> {
            if (isEditing.get()) {
                boolean isJsonLegal = true;
                try {
                    JSONUtil.parseObj(jsonEditText.getText());
                } catch (Exception ignored) {
                    isJsonLegal = false;
                }
                if (!isJsonLegal) {
                    showToast("配置文件不是合法的JSON文件");
                    return;
                }
                try {
                    FileUtils.write(configFile, jsonEditText.getText());
                    showToast("重启服务以应用新配置");
                } catch (IOException e) {
                    showToast(Constants.ERROR_MSG_CONFIG_DATA_WRITE);
                }
                isEditing.set(false);
                jsonView.setVisibility(View.VISIBLE);
                jsonEditText.setVisibility(View.INVISIBLE);
                editButton.setImageResource(R.drawable.edit);
            } else {
                showToast("错误配置可能导致服务无法启动，请谨慎修改！");
                isEditing.set(true);
                jsonEditText.setText(finalConfigJsonData);
                jsonView.setVisibility(View.INVISIBLE);
                jsonEditText.setVisibility(View.VISIBLE);
                editButton.setImageResource(R.drawable.save);
            }
        });
    }

    public void showServiceLogs() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View dialogView = inflater.inflate(R.layout.service_logs_view, null);
        TextView textView = dialogView.findViewById(R.id.tv_service_logs);
        ScrollView scrollView = dialogView.findViewById(R.id.tv_logs_scroll_view);
        textView.setText(Alist.ALIST_LOGS);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        new Thread(() -> {
            while (true) {
                requireActivity().runOnUiThread(() -> {
                    textView.setText(Alist.ALIST_LOGS);
                    if (!Alist.ALIST_LOGS.toString().equals(textView.getText().toString())) {
                        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                    }
                });
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Log.i(TAG, "showServiceLogs: " + e.getLocalizedMessage());
                }
            }
        }).start();
        dialog.setView(dialogView);
        dialog.show();
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        if (width < height) {
            dialog.getWindow().setLayout(width - 50, height * 2 / 5);
        } else {
            dialog.getWindow().setLayout(width * 5 / 6, height - 200);
        }
    }

    public void checkUpdates(View view) {
        String currentAppVersion = getCurrentAppVersion();
        new Thread(() -> {
            try {
                String releaseInfo = null;
                try {
                    releaseInfo = MyHttpUtil.request(Constants.URL_RELEASE_LATEST, Method.GET);
                } catch (Throwable t) {
                    Looper.prepare();
                    showToast("无法获取更新: " + t.getLocalizedMessage());
                    Looper.loop();
                    Log.e(TAG, "checkUpdates: " + t.getLocalizedMessage());
                }
                JSONObject release = JSONUtil.parseObj(releaseInfo);
                if (!release.containsKey("tag_name")) {
                    Looper.prepare();
                    showToast("未发现新版本信息");
                    Looper.loop();
                    return;
                }
                String abiName = AppUtil.getAbiName();
                if (!Constants.SUPPORTED_DOWNLOAD_ABI_NAMES.contains(abiName)) {
                    abiName = Constants.UNIVERSAL_ABI_NAME;
                }
                String latestVersion = release.getStr("tag_name").substring(1);
                String latestOnOpenListVersion = release.getStr("name").substring(15);
                String updateJournal = String.format("\uD83D\uDD25 新版本基于 OpenList %s 构建\r\n\r\n%s",
                    latestOnOpenListVersion, release.getStr("body"));
                String downloadLinkGitHub = (String) release.getByPath("assets[0].browser_download_url");
                String downloadLinkFast = String.format("%s/%s",
                    Constants.QUICK_DOWNLOAD_ADDRESS_GH_PROXY_PREFIX, downloadLinkGitHub);
                if (latestVersion.compareTo(currentAppVersion) > 0) {
                    Looper.prepare();
                    String dialogTitle = String.format("\uD83C\uDF89 AListLite %s 已发布", latestVersion);
                    AlertDialog.Builder dialog = new AlertDialog.Builder(requireContext());
                    dialog.setTitle(dialogTitle);
                    dialog.setMessage(updateJournal);
                    dialog.setCancelable(true);
                    dialog.setPositiveButton("镜像加速下载", (dialog1, which) -> openExternalUrl(downloadLinkFast));
                    dialog.setNeutralButton("GitHub官网下载", (dialog2, which) -> openExternalUrl(downloadLinkGitHub));
                    dialog.setNegativeButton("取消", null);
                    dialog.show();
                    Looper.loop();
                } else {
                    if (view != null) {
                        Looper.prepare();
                        showToast(String.format("当前已是最新版本（v%s）", currentAppVersion));
                        Looper.loop();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "checkUpdates: " + e.getLocalizedMessage());
            }
        }).start();
    }

    public void showSystemInfo() {
        webView.loadUrl(Constants.URL_LOCAL_ABOUT_ALIST_LITE);
    }

    public void showAliTvTokenGetPage() {
        webView.loadUrl("http://127.0.0.1:4015");
    }

    private void openExternalUrl(String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            startActivity(intent);
        } catch (Exception e) {
            showToast("无法打开此外部链接");
        }
    }

    // ====== Activity 结果回调 ======
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (mFilePathCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK) {
                    if (data != null) {
                        String dataString = data.getDataString();
                        if (dataString != null) {
                            results = new Uri[]{Uri.parse(dataString)};
                        }
                    }
                }
                mFilePathCallback.onReceiveValue(results);
                mFilePathCallback = null;
            } else if (mUploadMessage != null) {
                Uri result = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    result = data.getData();
                }
                mUploadMessage.onReceiveValue(result);
                mUploadMessage = null;
            }
        }
    }
}
