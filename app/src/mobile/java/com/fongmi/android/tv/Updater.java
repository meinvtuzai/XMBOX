package com.fongmi.android.tv;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.databinding.DialogUpdateBinding;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UpdateInstaller;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Github;
import com.github.catvod.utils.Logger;
import com.github.catvod.utils.Path;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

public class Updater implements Download.Callback {

    private DialogUpdateBinding binding;
    private Download download;
    private AlertDialog dialog;
    private boolean dev;
    private boolean forceCheck; // 是否为手动检查
    private boolean autoShow; // 是否自动显示更新对话框（用于自动检查）
    private String latestVersion; // 存储检测到的最新版本
    private String releaseApkUrl; // 从 GitHub Release 获取的 APK 下载链接（jsDelivr CDN）
    private String fallbackApkUrl; // 备用下载链接（GitHub原始URL）
    
    // 静态变量：记录上次检查时间（用于时间间隔限制）
    private static long lastCheckTime = 0;
    private static final long CHECK_INTERVAL = 60 * 60 * 1000; // 1小时（毫秒）

    private File getFile() {
        // Android 10+ 无法直接访问外部存储的Download目录
        // 使用应用的cache目录，FileProvider可以正常访问
        return Path.cache("XMBOX-update.apk");
    }

    private String getApk() {
        // 使用从 GitHub Release 获取的 APK URL（jsDelivr CDN）
        if (releaseApkUrl != null && !releaseApkUrl.isEmpty()) {
            Logger.d("APK download URL from Release (jsDelivr): " + releaseApkUrl);
            return releaseApkUrl;
        }
        // 如果没有获取到URL，返回空（不应该发生）
        Logger.e("Updater: 未找到APK下载链接");
        return "";
    }

    public static Updater create() {
        return new Updater();
    }

    public Updater() {
        this.forceCheck = false;
        this.autoShow = false;
        // download对象将在需要时创建
    }

    public Updater force() {
        Notify.show(R.string.update_check);
        Setting.putUpdate(true);
        this.forceCheck = true; // 标记为手动检查
        return this;
    }
    
    /**
     * 设置自动检查模式（应用启动时自动检查）
     */
    public Updater auto() {
        this.forceCheck = false;
        this.autoShow = true; // 自动显示更新对话框
        return this;
    }

    public Updater release() {
        this.dev = false;
        return this;
    }

    public Updater dev() {
        this.dev = true;
        return this;
    }

    private Updater check() {
        dismiss();
        return this;
    }

    public void start(Activity activity) {
        // 如果是自动检查，检查时间间隔
        if (autoShow && !forceCheck) {
            long currentTime = System.currentTimeMillis();
            long timeSinceLastCheck = currentTime - lastCheckTime;
            // 1小时内只检查一次
            if (lastCheckTime > 0 && timeSinceLastCheck < CHECK_INTERVAL) {
                Logger.d("Updater: 距离上次检查仅 " + (timeSinceLastCheck / 1000 / 60) + " 分钟，跳过本次检查");
                return;
            }
        }
        App.execute(() -> doInBackground(activity));
    }

    private boolean need(int code, String name) {
        return Setting.getUpdate() && (dev ? !name.equals(BuildConfig.VERSION_NAME) && code >= BuildConfig.VERSION_CODE : code > BuildConfig.VERSION_CODE);
    }

    private void doInBackground(Activity activity) {
        Logger.d("Updater: Starting update check...");
        lastCheckTime = System.currentTimeMillis(); // 更新检查时间
        // 直接使用 GitHub Releases API 检查更新
        checkViaGitHubAPI(activity);
    }
    
    private void checkViaGitHubAPI(Activity activity) {
        try {
            String releasesUrl = "https://api.github.com/repos/Tosencen/XMBOX/releases/latest";
            Logger.d("Updater: Trying GitHub Releases API: " + releasesUrl);
            
            // 检查是否有GitHub Token
            String githubToken = BuildConfig.GITHUB_TOKEN;
            String response = null;
            int retryCount = 0;
            int maxRetries = 2; // 最多重试2次
            
            while (response == null && retryCount <= maxRetries) {
                try {
                    if (githubToken != null && !githubToken.isEmpty()) {
                        // 使用token进行认证请求（5000次/小时）
                        java.util.Map<String, String> headers = new java.util.HashMap<>();
                        headers.put("Authorization", "Bearer " + githubToken);
                        headers.put("Accept", "application/vnd.github.v3+json");
                        headers.put("User-Agent", "XMBOX-Android");
                        Logger.d("Updater: Using GitHub Token for authenticated request (attempt " + (retryCount + 1) + ")");
                        response = OkHttp.string(releasesUrl, headers);
                    } else {
                        // 使用未认证请求（60次/小时）
                        Logger.d("Updater: Using unauthenticated request (attempt " + (retryCount + 1) + ")");
                        java.util.Map<String, String> headers = new java.util.HashMap<>();
                        headers.put("User-Agent", "XMBOX-Android");
                        response = OkHttp.string(releasesUrl, headers);
                    }
                } catch (Exception e) {
                    Logger.e("Updater: Request failed (attempt " + (retryCount + 1) + "): " + e.getMessage());
                    retryCount++;
                    if (retryCount <= maxRetries) {
                        // 等待一段时间后重试
                        Thread.sleep(1000 * retryCount); // 递增等待时间
                    }
                }
            }
            
            // 检查响应是否为空（可能是网络错误、VPN问题等）
            if (response == null || response.isEmpty()) {
                Logger.e("Updater: 网络请求失败，响应为空。可能是网络连接问题或GitHub访问受限");
                if (forceCheck) {
                    // 手动检查时，显示错误提示
                    App.post(() -> {
                        Notify.show("检查更新失败：无法连接到GitHub，请检查网络或稍后重试");
                        showVersionInfo(activity, BuildConfig.VERSION_NAME, "");
                    });
                } else {
                    Logger.w("Updater: 自动检查失败，网络不可用");
                }
                return;
            }
            
            // 检查API限流
            if (response.contains("rate limit exceeded") || response.contains("API rate limit exceeded")) {
                Logger.e("Updater: GitHub API rate limit exceeded");
                if (forceCheck) {
                    App.post(() -> {
                        Notify.show("检查更新失败：GitHub API请求次数已达上限，请稍后重试");
                        showVersionInfo(activity, BuildConfig.VERSION_NAME, "");
                    });
                }
                return;
            }
            
            // 检查404错误
            if (response.contains("Not Found") || response.contains("404")) {
                Logger.e("Updater: Release not found (404)");
                if (forceCheck) {
                    App.post(() -> {
                        Notify.show("检查更新失败：未找到发布版本");
                        showVersionInfo(activity, BuildConfig.VERSION_NAME, "");
                    });
                }
                return;
            }
            
            JSONObject release = new JSONObject(response);
            String tagName = release.optString("tag_name");
            String body = release.optString("body");
            String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            
            Logger.d("Updater: GitHub API Remote version: " + version);
            
            // 从 assets 中查找 APK
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                String mode = BuildConfig.FLAVOR_mode;
                String abi = BuildConfig.FLAVOR_abi;
                
                // 尝试多种文件名格式
                String[] possibleNames = {
                    mode + "-" + abi + "-v" + version + ".apk",  // mobile-arm64_v8a-v3.1.0.apk
                    mode + "-" + abi + "-release.apk",           // mobile-arm64_v8a-release.apk
                    mode + "-" + abi + ".apk",                   // mobile-arm64_v8a.apk
                    mode + "-" + abi + "-" + version + ".apk"    // mobile-arm64_v8a-3.1.0.apk
                };
                
                boolean found = false;
                for (int i = 0; i < assets.length() && !found; i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String assetName = asset.optString("name");
                    
                    // 检查是否匹配任何可能的文件名格式
                    for (String targetName : possibleNames) {
                        if (targetName.equals(assetName)) {
                            String githubUrl = asset.optString("browser_download_url");
                            // jsDelivr无法访问GitHub Release文件，直接使用GitHub Release URL
                            // 如果GitHub访问慢，可以配置代理或使用其他CDN
                            this.releaseApkUrl = githubUrl;
                            this.fallbackApkUrl = githubUrl;
                            Logger.d("Updater: 找到匹配的APK: " + assetName);
                            Logger.d("Updater: APK URL (GitHub Release): " + this.releaseApkUrl);
                            found = true;
                            break;
                        }
                    }
                }
                
                // 如果精确匹配失败，尝试模糊匹配（包含mode和abi的APK文件）
                if (!found) {
                    Logger.w("Updater: 未找到精确匹配的APK，尝试模糊匹配...");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String assetName = asset.optString("name");
                        // 检查文件名是否包含mode和abi，且是APK文件
                        if (assetName.endsWith(".apk") && 
                            assetName.contains(mode) && 
                            assetName.contains(abi.replace("_", "-"))) {
                            String githubUrl = asset.optString("browser_download_url");
                            // jsDelivr无法访问GitHub Release文件，直接使用GitHub Release URL
                            this.releaseApkUrl = githubUrl;
                            this.fallbackApkUrl = githubUrl;
                            Logger.d("Updater: 找到模糊匹配的APK: " + assetName);
                            Logger.d("Updater: APK URL (GitHub Release): " + this.releaseApkUrl);
                            found = true;
                            break;
                        }
                    }
                }
                
                if (!found) {
                    Logger.e("Updater: 在Release中未找到匹配的APK文件");
                    Logger.e("Updater: 期望的格式: " + mode + "-" + abi + "-v" + version + ".apk");
                    Logger.e("Updater: 可用的assets:");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String assetName = asset.optString("name");
                        if (assetName.endsWith(".apk")) {
                            Logger.e("Updater:   - " + assetName);
                        }
                    }
                }
            } else {
                Logger.e("Updater: Release中没有assets数组");
            }
            
            if (needUpdate(version)) {
                this.latestVersion = version;
                // 有新版本时，自动显示或手动显示更新对话框
                App.post(() -> show(activity, version, body));
            } else {
                // 没有新版本
                if (forceCheck) {
                    // 手动检查时，显示版本信息弹窗
                    App.post(() -> showVersionInfo(activity, version, body));
                } else if (autoShow) {
                    // 自动检查时，不显示任何内容（静默检查）
                    Logger.d("Updater: 自动检查完成，当前已是最新版本");
                }
            }
        } catch (InterruptedException e) {
            Logger.e("Updater: Thread interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Logger.e("Updater: GitHub API check failed: " + e.getMessage());
            e.printStackTrace();
            if (forceCheck) {
                // 手动检查时，显示错误提示
                String errorMsg = e.getMessage();
                if (errorMsg != null && (errorMsg.contains("network") || errorMsg.contains("timeout") || 
                    errorMsg.contains("connect") || errorMsg.contains("Unable to resolve host"))) {
                    App.post(() -> {
                        Notify.show("检查更新失败：网络连接异常，请检查网络设置");
                        showVersionInfo(activity, BuildConfig.VERSION_NAME, "");
                    });
                } else {
                    App.post(() -> {
                        Notify.show("检查更新失败：" + (errorMsg != null ? errorMsg : "未知错误"));
                        showVersionInfo(activity, BuildConfig.VERSION_NAME, "");
                    });
                }
            } else {
                Logger.w("Updater: 自动检查失败: " + e.getMessage());
            }
        }
    }
    
    private boolean needUpdate(String remoteVersion) {
        if (!Setting.getUpdate()) return false;
        
        try {
            // 简单的版本号比较，假设版本格式为 x.y.z
            String[] remoteParts = remoteVersion.split("\\.");
            String[] localParts = BuildConfig.VERSION_NAME.split("\\.");
            
            // 确保两个版本号都有足够的段
            int maxLength = Math.max(remoteParts.length, localParts.length);
            
            for (int i = 0; i < maxLength; i++) {
                int remotePart = i < remoteParts.length ? Integer.parseInt(remoteParts[i]) : 0;
                int localPart = i < localParts.length ? Integer.parseInt(localParts[i]) : 0;
                
                if (remotePart > localPart) {
                    return true;
                } else if (remotePart < localPart) {
                    return false;
                }
            }
            return false; // 版本相同
        } catch (Exception e) {
            Logger.e("Updater: Version comparison error: " + e.getMessage());
            return false;
        }
    }

    private void show(Activity activity, String version, String desc) {
        binding = DialogUpdateBinding.inflate(LayoutInflater.from(activity));
        check().create(activity, ResUtil.getString(R.string.update_version, version)).show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(this::confirm);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(this::cancel);
        binding.desc.setText(desc);
    }

    /**
     * 显示版本信息弹窗（无更新时）
     */
    private void showVersionInfo(Activity activity, String remoteVersion, String desc) {
        binding = DialogUpdateBinding.inflate(LayoutInflater.from(activity));
        // 先设置内容，只显示当前版本号，不使用远程信息
        binding.desc.setText(BuildConfig.VERSION_NAME);
        check().create(activity, "最新版本").show();
        // 隐藏确认按钮，只显示取消按钮（改为"确定"）
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setVisibility(View.GONE);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setText("确定");
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
            if (dialog != null) dialog.dismiss();
        });
    }

    private AlertDialog create(Activity activity, String title) {
        return dialog = new MaterialAlertDialogBuilder(activity).setTitle(title).setView(binding.getRoot()).setPositiveButton(R.string.update_confirm, null).setNegativeButton(R.string.dialog_negative, null).setCancelable(false).create();
    }

    private void cancel(View view) {
        Setting.putUpdate(false);
        if (download != null) {
            download.cancel();
        }
        dialog.dismiss();
    }

    private void confirm(View view) {
        // 开始下载更新（使用jsDelivr CDN，失败时回退到GitHub）
        String downloadUrl = getApk();
        String fallbackUrl = this.fallbackApkUrl;
        
        // 检查URL是否为空
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            Logger.e("Updater: 下载URL为空，无法下载");
            Notify.show("无法获取下载链接，请稍后重试或手动下载");
            return;
        }
        
        Logger.d("Updater: 开始下载，URL: " + downloadUrl);
        
        // 创建带回退URL的下载对象
        this.download = Download.create(downloadUrl, getFile(), fallbackUrl, this);
        this.download.start();
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void progress(int progress) {
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(String.format(Locale.getDefault(), "%1$d%%", progress));
    }

    @Override
    public void error(String msg) {
        Notify.show(msg);
        dismiss();
    }

    @Override
    public void success(File file) {
        // 使用UpdateInstaller处理安装，包括权限检查和请求
        UpdateInstaller installer = UpdateInstaller.get();
        
        // 检查安装权限
        if (!installer.hasInstallPermission()) {
            // 没有权限，请求权限并保存待安装的文件
            Logger.d("Updater: 没有安装权限，请求权限");
            installer.requestInstallPermission();
            // 保存待安装的文件，将在权限授予后自动安装
            installer.install(file, true); // checkPermission=true会保存文件
            Notify.show("请授予安装权限以完成更新");
            dismiss();
            return;
        }
        
        // 有权限，直接安装
        boolean success = installer.install(file, false);
        if (success) {
            Logger.d("Updater: 已启动安装程序");
            dismiss();
        } else {
            Logger.e("Updater: 启动安装程序失败");
            Notify.show("无法启动安装程序，请检查文件是否完整");
            dismiss();
        }
    }
}