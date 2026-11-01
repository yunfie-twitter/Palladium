package com.yunfie.illustia.lsposed;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/** Private APK catalog shared by the manager UI and its exported, caller-checked provider. */
public final class PluginStore {
    static final String META_ENTRY = "com.yunfie.illustia.lsposed.plugin.ENTRY";
    static final String META_API = "com.yunfie.illustia.lsposed.plugin.API";
    static final String META_NAME = "com.yunfie.illustia.lsposed.plugin.NAME";
    static final String META_DESCRIPTION = "com.yunfie.illustia.lsposed.plugin.DESCRIPTION";
    static final String API_VERSION = "1";
    static final String PREFS = "plugin_settings";

    private static final String APK_SUFFIX = ".apk";
    private static final String META_SUFFIX = ".properties";

    private PluginStore() {}

    static File directory(Context context) {
        return new File(context.getFilesDir(), "plugins");
    }

    public static synchronized PluginInfo importApk(Context context, InputStream source) throws Exception {
        File directory = directory(context);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create plugin directory");
        File incoming = File.createTempFile("incoming-", APK_SUFFIX, directory);
        try {
            copy(source, incoming);
            PackageInfo packageInfo = packageInfo(context, incoming);
            if (packageInfo == null || packageInfo.applicationInfo == null) {
                throw new IOException(context.getString(R.string.error_not_plugin_apk));
            }
            Bundle manifest = packageInfo.applicationInfo.metaData;
            String packageName = packageInfo.packageName;
            String entryClass = manifest == null ? null : manifest.getString(META_ENTRY);
            Object apiValue = manifest == null ? null : manifest.get(META_API);
            String apiVersion = apiValue == null ? null : String.valueOf(apiValue);
            if (!isPackageName(packageName) || entryClass == null || entryClass.trim().isEmpty()) {
                throw new IOException(context.getString(R.string.error_invalid_plugin_manifest));
            }
            if (!API_VERSION.equals(apiVersion)) {
                throw new IOException(context.getString(R.string.error_plugin_api));
            }

            String name = value(manifest, META_NAME, packageName);
            String description = value(manifest, META_DESCRIPTION, "");
            String version = packageInfo.versionName == null ? "" : packageInfo.versionName;
            long versionCode = Build.VERSION.SDK_INT >= 28
                    ? packageInfo.getLongVersionCode() : packageInfo.versionCode;
            String hash = sha256(incoming);
            File apk = apkFile(context, packageName);
            File metadata = metadataFile(context, packageName);
            File stagedApk = new File(directory, packageName + ".apk.new");
            File stagedMetadata = new File(directory, packageName + ".properties.new");
            if (!incoming.renameTo(stagedApk)) {
                try (FileInputStream input = new FileInputStream(incoming)) { copy(input, stagedApk); }
            }
            if (entryClass.startsWith(".")) entryClass = packageName + entryClass;
            else if (!entryClass.contains(".")) entryClass = packageName + "." + entryClass;

            Properties properties = new Properties();
            properties.setProperty("package", packageName);
            properties.setProperty("entry", entryClass.trim());
            properties.setProperty("name", name);
            properties.setProperty("description", description);
            properties.setProperty("version", version);
            properties.setProperty("versionCode", Long.toString(versionCode));
            properties.setProperty("sha256", hash);
            try (FileOutputStream output = new FileOutputStream(stagedMetadata)) {
                properties.store(output, "Palleria plugin metadata");
                output.getFD().sync();
            }
            replace(stagedApk, apk);
            replace(stagedMetadata, metadata);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(enabledKey(packageName), false).putString(statusKey(packageName), "").apply();
            return read(context, metadata);
        } finally {
            if (incoming.exists()) incoming.delete();
        }
    }

    public static synchronized List<PluginInfo> list(Context context) {
        File directory = directory(context);
        File[] files = directory.listFiles((dir, name) -> name.endsWith(META_SUFFIX));
        if (files == null) return Collections.emptyList();
        ArrayList<PluginInfo> result = new ArrayList<>();
        for (File file : files) {
            try {
                PluginInfo info = read(context, file);
                if (apkFile(context, info.packageName).isFile()) result.add(info);
            } catch (Exception ignored) {
                // A damaged record is isolated; valid plugins remain manageable.
            }
        }
        result.sort(Comparator.comparing(info -> info.name.toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    static synchronized PluginInfo get(Context context, String packageName) throws IOException {
        requirePackageName(packageName);
        File metadata = metadataFile(context, packageName);
        if (!metadata.isFile() || !apkFile(context, packageName).isFile()) throw new IOException("Plugin not found");
        return read(context, metadata);
    }

    public static synchronized boolean remove(Context context, String packageName) {
        if (!isPackageName(packageName)) return false;
        boolean removed = apkFile(context, packageName).delete();
        removed |= metadataFile(context, packageName).delete();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(enabledKey(packageName)).remove(statusKey(packageName)).apply();
        return removed;
    }

    public static void setEnabled(Context context, String packageName, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(enabledKey(packageName), enabled).apply();
    }

    public static void setStatus(Context context, String packageName, String status) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(statusKey(packageName), status).apply();
    }

    public static File apkFile(Context context, String packageName) {
        return new File(directory(context), packageName + APK_SUFFIX);
    }

    private static File metadataFile(Context context, String packageName) {
        return new File(directory(context), packageName + META_SUFFIX);
    }

    static String enabledKey(String packageName) { return "enabled." + packageName; }
    static String statusKey(String packageName) { return "status." + packageName; }

    private static PluginInfo read(Context context, File metadata) throws IOException {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(metadata)) { properties.load(input); }
        String packageName = properties.getProperty("package", "");
        requirePackageName(packageName);
        PluginInfo info = new PluginInfo();
        info.packageName = packageName;
        info.entryClass = properties.getProperty("entry", "");
        info.name = properties.getProperty("name", packageName);
        info.description = properties.getProperty("description", "");
        info.version = properties.getProperty("version", "");
        info.sha256 = properties.getProperty("sha256", "");
        info.enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(enabledKey(packageName), false);
        info.status = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(statusKey(packageName), "");
        return info;
    }

    private static PackageInfo packageInfo(Context context, File apk) {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA));
        }
        //noinspection deprecation
        return context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_META_DATA);
    }

    private static String value(Bundle bundle, String key, String fallback) {
        String value = bundle == null ? null : bundle.getString(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static void copy(InputStream input, File output) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(output)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) stream.write(buffer, 0, count);
            stream.getFD().sync();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
    }

    private static void replace(File staged, File destination) throws IOException {
        if (destination.exists() && !destination.delete()) throw new IOException("Cannot replace plugin file");
        if (!staged.renameTo(destination)) throw new IOException("Cannot finalize plugin file");
    }

    static boolean isPackageName(String packageName) {
        return packageName != null && packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    }

    private static void requirePackageName(String packageName) throws IOException {
        if (!isPackageName(packageName)) throw new IOException("Invalid plugin package name");
    }

    public static final class PluginInfo {
        public String packageName;
        public String entryClass;
        public String name;
        public String description;
        public String version;
        public String sha256;
        public boolean enabled;
        public String status;

        Bundle toBundle() {
            Bundle bundle = new Bundle();
            bundle.putString("package", packageName);
            bundle.putString("entry", entryClass);
            bundle.putString("name", name);
            bundle.putString("description", description);
            bundle.putString("version", version);
            bundle.putString("sha256", sha256);
            bundle.putBoolean("enabled", enabled);
            bundle.putString("status", status);
            return bundle;
        }
    }
}
