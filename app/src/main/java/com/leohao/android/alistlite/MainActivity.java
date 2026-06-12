package com.leohao.android.alistlite;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.leohao.android.alistlite.model.Alist;
import com.leohao.android.alistlite.service.AlistService;
import com.leohao.android.alistlite.service.AlistTileService;
import com.leohao.android.alistlite.ui.alist.AListFragment;
import com.leohao.android.alistlite.ui.filebrowser.FileBrowserFragment;
import com.leohao.android.alistlite.util.Constants;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AListLite+ 主界面
 *
 * 作为 Fragment 容器 + 底部导航，托管 AList 网页浏览和文件浏览两个独立模块。
 */
public class MainActivity extends AppCompatActivity {

    private static MainActivity instance;
    private static final String TAG = "MainActivity";

    private ScheduledExecutorService broadcastScheduler = null;
    private Alist alistServer;
    /** AList 服务地址（AlistService 写入） */
    public String serverAddress = Constants.URL_ABOUT_BLANK;

    private AListFragment alistFragment;
    private FileBrowserFragment fileBrowserFragment;
    private int currentNavId = R.id.nav_alist;

    private BottomNavigationView bottomNavigation;

    public static MainActivity getInstance() {
        return instance;
    }

    public Alist getAlistServer() {
        return alistServer;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        setContentView(R.layout.activity_main);

        alistServer = Alist.getInstance();

        alistFragment = new AListFragment();
        fileBrowserFragment = new FileBrowserFragment();

        bottomNavigation = findViewById(R.id.bottom_navigation);
        bottomNavigation.setOnItemSelectedListener(this::onNavItemSelected);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, alistFragment, "alist")
                .add(R.id.fragment_container, fileBrowserFragment, "files")
                .hide(fileBrowserFragment)
                .commit();
        } else {
            alistFragment = (AListFragment) getSupportFragmentManager().findFragmentByTag("alist");
            fileBrowserFragment = (FileBrowserFragment) getSupportFragmentManager().findFragmentByTag("files");
        }

        checkPermissions();
        initBroadcastScheduler();
    }

    private boolean onNavItemSelected(@NonNull android.view.MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == currentNavId) return true;

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

        if (itemId == R.id.nav_alist) {
            if (alistFragment != null) transaction.show(alistFragment);
            if (fileBrowserFragment != null) transaction.hide(fileBrowserFragment);
            currentNavId = R.id.nav_alist;
        } else if (itemId == R.id.nav_files) {
            if (fileBrowserFragment != null) transaction.show(fileBrowserFragment);
            if (alistFragment != null) transaction.hide(alistFragment);
            currentNavId = R.id.nav_files;
        } else {
            return false;
        }

        transaction.commitNow();
        return true;
    }

    // ====== 服务管理（供 AlistService 调用） ======

    public void onServiceStarted(String address) {
        this.serverAddress = address;
        if (alistFragment != null && alistFragment.isAdded()) {
            alistFragment.onServiceStarted(address);
        }
    }

    public void onServiceStopped() {
        if (alistFragment != null && alistFragment.isAdded()) {
            alistFragment.onServiceStopped();
        }
    }

    public void readyToStartService() {
        Intent intent = new Intent(this, AlistService.class).setAction(AlistService.ACTION_STARTUP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    public void readyToShutdownService() {
        Intent intent = new Intent(this, AlistService.class).setAction(AlistService.ACTION_SHUTDOWN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    // ====== 权限检查 ======

    private void checkPermissions() {
        XXPermissions.with(this)
            .permission(Permission.POST_NOTIFICATIONS)
            .permission(Permission.MANAGE_EXTERNAL_STORAGE)
            .permission(Permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .request(new OnPermissionCallback() {
                @Override
                public void onGranted(@NonNull List<String> permissions, boolean allGranted) {
                    if (!allGranted) {
                        showToast("部分权限未授予，软件可能无法正常运行");
                    }
                }

                @Override
                public void onDenied(@NonNull List<String> permissions, boolean doNotAskAgain) {
                    if (doNotAskAgain) {
                        showToast("请手动授予相关权限");
                    }
                }
            });
    }

    // ====== 广播定时器 ======

    private void initBroadcastScheduler() {
        broadcastScheduler = Executors.newSingleThreadScheduledExecutor();
        broadcastScheduler.scheduleAtFixedRate(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                TileService.requestListeningState(this, new ComponentName(this, AlistTileService.class));
                String actionName = (alistServer != null && alistServer.hasRunning())
                    ? AlistTileService.ACTION_TILE_ON : AlistTileService.ACTION_TILE_OFF;
                Intent tileServiceIntent = new Intent(this, AlistTileService.class).setAction(actionName);
                LocalBroadcastManager.getInstance(this).sendBroadcast(tileServiceIntent);
            }
        }, 2, 1, TimeUnit.SECONDS);
    }

    // ====== 返回键 ======

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (currentNavId == R.id.nav_files) {
                // 文件浏览器的返回交给 Fragment 内部的 BackStack 处理
                // 由于 Fragment 不直接接收 onKeyDown，我们直接 moveTaskToBack
                // 文件浏览器内部的目录导航由它自己的 toolbar 处理
                moveTaskToBack(true);
                return true;
            }
            if (alistFragment != null && alistFragment.isVisible()) {
                if (alistFragment.handleBack()) {
                    return true;
                }
            }
            moveTaskToBack(true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ====== PopupMenu 回调（委托给 AListFragment） ======

    public void showQrCode(View view) {
        if (alistFragment != null && alistFragment.isAdded()) alistFragment.showQrCode(view);
    }

    public void setAdminPassword(View view) {
        if (alistFragment != null && alistFragment.isAdded()) alistFragment.setAdminPassword(view);
    }

    public void startPermissionCheckActivity(View view) {
        startActivity(new Intent(this, PermissionActivity.class));
    }

    public void manageConfigData(View view) {
        if (alistFragment != null && alistFragment.isAdded()) alistFragment.manageConfigData();
    }

    public void showServiceLogs(View view) {
        if (alistFragment != null && alistFragment.isAdded()) alistFragment.showServiceLogs();
    }

    public void checkUpdates(View view) {
        if (alistFragment != null && alistFragment.isAdded()) alistFragment.checkUpdates(view);
    }

    public void showAliTvTokenGetPage(View view) {
        if (alistFragment != null && alistFragment.isAdded()) alistFragment.showAliTvTokenGetPage();
    }

    public void showSystemInfo(View view) {
        if (alistFragment != null && alistFragment.isAdded()) alistFragment.showSystemInfo();
    }

    // ====== Toast ======

    void showToast(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private void showToast(String msg, int duration) {
        Toast.makeText(getApplicationContext(), msg, duration).show();
    }

    // ====== 生命周期 ======

    @Override
    public void finish() {
        readyToShutdownService();
        super.finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (broadcastScheduler != null) {
            broadcastScheduler.shutdownNow();
        }
    }
}
