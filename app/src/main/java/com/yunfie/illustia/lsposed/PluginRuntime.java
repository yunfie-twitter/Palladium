package com.yunfie.illustia.lsposed;

import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import dalvik.system.DexClassLoader;
import com.yunfie.illustia.lsposed.api.PalleriaPlugin;
import com.yunfie.illustia.lsposed.api.PluginContext;
import io.github.libxposed.api.XposedInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads enabled plugin APKs into the Palleria process and reconciles manager changes. */
final class PluginRuntime {
    private static final String TAG = "PalleriaPlugins";
    private static final String STATUS_LOADED = "loaded";
    private static final String STATUS_DISABLED = "disabled";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PalleriaPluginLoader");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, Loaded> loaded = new HashMap<>();
    private static final Map<String, String> statuses = new HashMap<>();
    private static Context hostContext;
    private static ClassLoader hostClassLoader;
    private static XposedInterface xposed;
    private static boolean lifecycleCallbacksRegistered;

    private PluginRuntime() {}

    static synchronized void start(Context context, ClassLoader appClassLoader, XposedInterface xposedInterface) {
        if (context == null) return;
        hostContext = context.getApplicationContext();
        hostClassLoader = appClassLoader;
        xposed = xposedInterface;
        registerActivityCallbacks(hostContext);
        scheduleReload();
    }

    private static void scheduleReload() { executor.execute(PluginRuntime::reload); }

    private static synchronized void reload() {
        Context context = hostContext;
        XposedInterface framework = xposed;
        ClassLoader appClassLoader = hostClassLoader;
        if (context == null || framework == null || appClassLoader == null) return;
        try {
            ArrayList<Bundle> records = getPluginRecords(context);
            Set<String> desired = new HashSet<>();
            Map<String, Bundle> enabled = new HashMap<>();
            for (Bundle record : records) {
                String packageName = record.getString("package", "");
                if (packageName.isEmpty()) continue;
                if (record.getBoolean("enabled", false)) {
                    desired.add(packageName);
                    enabled.put(packageName, record);
                }
            }

            for (String packageName : new HashSet<>(loaded.keySet())) {
                Loaded current = loaded.get(packageName);
                Bundle next = enabled.get(packageName);
                if (next == null || !current.sha256.equals(next.getString("sha256", ""))) {
                    unload(context, packageName, current);
                }
            }

            for (String packageName : desired) {
                if (loaded.containsKey(packageName)) continue;
                Bundle record = enabled.get(packageName);
                try {
                    load(context, appClassLoader, framework, record);
                } catch (Throwable failure) {
                    String message = failure.getMessage();
                    statuses.put(packageName, "error:" + (message == null
                            ? failure.getClass().getSimpleName() : message));
                    Log.e(TAG, "Plugin failed to load: " + packageName, failure);
                    framework.log(Log.ERROR, TAG, "Plugin failed to load: " + packageName, failure);
                }
            }
        } catch (Throwable failure) {
            Log.e(TAG, "Could not read the plugin catalog.", failure);
            framework.log(Log.ERROR, TAG, "Could not read the plugin catalog.", failure);
        }
    }

    static Bundle statusSnapshot() {
        synchronized (statuses) {
            Bundle result = new Bundle();
            for (Map.Entry<String, String> entry : statuses.entrySet()) result.putString(entry.getKey(), entry.getValue());
            return result;
        }
    }

    static synchronized Bundle settingsSnapshot() {
        Bundle catalog = new Bundle();
        for (Map.Entry<String, Loaded> entry : loaded.entrySet()) {
            String pluginId = entry.getKey();
            Loaded instance = entry.getValue();
            try {
                ArrayList<Bundle> definitions = new ArrayList<>();
                Bundle values = new Bundle();
                for (com.yunfie.illustia.lsposed.api.PluginSetting setting : instance.plugin.getSettings()) {
                    Bundle definition = new Bundle();
                    definition.putString("key", setting.getKey());
                    definition.putString("title", setting.getTitle());
                    definition.putString("summary", setting.getSummary());
                    definition.putString("type", setting.getType().name());
                    Object defaultValue = setting.getDefaultValue();
                    if (defaultValue instanceof Boolean) {
                        definition.putBoolean("default", (Boolean) defaultValue);
                        values.putBoolean(setting.getKey(), instance.pluginContext.getPreferences()
                                .getBoolean(setting.getKey(), (Boolean) defaultValue));
                    } else {
                        definition.putString("default", String.valueOf(defaultValue));
                        values.putString(setting.getKey(), instance.pluginContext.getPreferences()
                                .getString(setting.getKey(), String.valueOf(defaultValue)));
                    }
                    definitions.add(definition);
                }
                if (!definitions.isEmpty()) {
                    Bundle pluginSettings = new Bundle();
                    pluginSettings.putParcelableArrayList("definitions", definitions);
                    pluginSettings.putBundle("values", values);
                    catalog.putBundle(pluginId, pluginSettings);
                }
            } catch (Throwable failure) {
                Log.e(TAG, "Could not read plugin settings: " + pluginId, failure);
            }
        }
        return catalog;
    }

    static void syncFromManagerResult(Context context, Intent data) throws Exception {
        if (data == null) return;
        ArrayList<Bundle> records;
        if (Build.VERSION.SDK_INT >= 33) {
            records = data.getParcelableArrayListExtra(PluginSettingsActivity.EXTRA_PLUGINS, Bundle.class);
        } else {
            //noinspection deprecation
            records = data.getParcelableArrayListExtra(PluginSettingsActivity.EXTRA_PLUGINS);
        }
        if (records == null) return;
        File directory = new File(context.getFilesDir(), "palleria-plugin-catalog");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create plugin catalog");
        Set<String> present = new HashSet<>();
        for (Bundle record : records) {
            String packageName = record.getString("package", "");
            if (!PluginStore.isPackageName(packageName)) continue;
            present.add(packageName);
            ParcelFileDescriptor descriptor;
            if (Build.VERSION.SDK_INT >= 33) descriptor = record.getParcelable("apk", ParcelFileDescriptor.class);
            else {
                //noinspection deprecation
                descriptor = record.getParcelable("apk");
            }
            if (descriptor == null) throw new IllegalStateException("Missing APK descriptor for " + packageName);
            File apk = new File(directory, packageName + ".apk");
            File staged = new File(directory, packageName + ".apk.new");
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                 FileOutputStream output = new FileOutputStream(staged)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            }
            if (apk.exists() && !apk.delete()) throw new IllegalStateException("Cannot replace cached plugin APK");
            if (!staged.renameTo(apk)) throw new IllegalStateException("Cannot finalize cached plugin APK");
            Properties metadata = new Properties();
            metadata.setProperty("package", packageName);
            metadata.setProperty("entry", record.getString("entry", ""));
            metadata.setProperty("name", record.getString("name", packageName));
            metadata.setProperty("description", record.getString("description", ""));
            metadata.setProperty("version", record.getString("version", ""));
            metadata.setProperty("sha256", record.getString("sha256", ""));
            metadata.setProperty("enabled", Boolean.toString(record.getBoolean("enabled", false)));
            try (FileOutputStream output = new FileOutputStream(new File(directory, packageName + ".properties"))) {
                metadata.store(output, "Palleria plugin cache");
                output.getFD().sync();
            }
        }
        File[] metadataFiles = directory.listFiles((dir, name) -> name.endsWith(".properties"));
        if (metadataFiles != null) for (File metadata : metadataFiles) {
            String packageName = metadata.getName().substring(0, metadata.getName().length() - ".properties".length());
            if (!present.contains(packageName)) {
                metadata.delete();
                new File(directory, packageName + ".apk").delete();
            }
        }
        Bundle settingValues = data.getBundleExtra(PluginSettingsActivity.EXTRA_SETTING_VALUES);
        if (settingValues != null) {
            for (String pluginId : settingValues.keySet()) {
                if (!PluginStore.isPackageName(pluginId)) continue;
                Bundle values = settingValues.getBundle(pluginId);
                if (values == null) continue;
                android.content.SharedPreferences.Editor preferences = context
                        .getSharedPreferences("plugin." + pluginId, Context.MODE_PRIVATE).edit();
                for (String key : values.keySet()) {
                    Object value = values.get(key);
                    if (value instanceof Boolean) preferences.putBoolean(key, (Boolean) value);
                    else if (value instanceof String) preferences.putString(key, (String) value);
                }
                preferences.apply();
                notifySettingsChanged(pluginId, values);
            }
        }
        scheduleReload();
    }

    private static void notifySettingsChanged(String pluginId, Bundle values) {
        executor.execute(() -> {
            synchronized (PluginRuntime.class) {
                Loaded instance = loaded.get(pluginId);
                if (instance == null) return;
                for (String key : values.keySet()) {
                    try {
                        instance.plugin.onSettingChanged(key, values.get(key));
                    } catch (Throwable failure) {
                        Log.e(TAG, "Plugin setting callback failed: " + pluginId + "/" + key, failure);
                        synchronized (statuses) { statuses.put(pluginId, "error:setting " + failure.getMessage()); }
                    }
                }
            }
        });
    }

    private static synchronized void registerActivityCallbacks(Context context) {
        if (lifecycleCallbacksRegistered || !(context instanceof Application)) return;
        Application application = (Application) context;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                dispatchActivity(activity, plugin -> plugin.onActivityCreated(activity, state));
            }
            @Override public void onActivityStarted(Activity activity) {
                dispatchActivity(activity, plugin -> plugin.onActivityStarted(activity));
            }
            @Override public void onActivityResumed(Activity activity) {
                dispatchActivity(activity, plugin -> plugin.onActivityResumed(activity));
            }
            @Override public void onActivityPaused(Activity activity) {
                dispatchActivity(activity, plugin -> plugin.onActivityPaused(activity));
            }
            @Override public void onActivityStopped(Activity activity) {
                dispatchActivity(activity, plugin -> plugin.onActivityStopped(activity));
            }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
                dispatchActivity(activity, plugin -> plugin.onActivitySaveInstanceState(activity, state));
            }
            @Override public void onActivityDestroyed(Activity activity) {
                dispatchActivity(activity, plugin -> plugin.onActivityDestroyed(activity));
            }
        });
        lifecycleCallbacksRegistered = true;
    }

    private static void dispatchActivity(Activity activity, ActivityCall call) {
        if (activity == null || !"com.yunfie.illustia".equals(activity.getPackageName())) return;
        ArrayList<Loaded> plugins;
        synchronized (PluginRuntime.class) { plugins = new ArrayList<>(loaded.values()); }
        for (Loaded loadedPlugin : plugins) {
            try {
                call.invoke(loadedPlugin.plugin);
            } catch (Throwable failure) {
                Log.e(TAG, "Plugin activity lifecycle callback failed", failure);
            }
        }
    }

    private interface ActivityCall { void invoke(PalleriaPlugin plugin) throws Exception; }

    private static ArrayList<Bundle> getPluginRecords(Context context) throws Exception {
        ArrayList<Bundle> records = new ArrayList<>();
        File directory = new File(context.getFilesDir(), "palleria-plugin-catalog");
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".properties"));
        if (files == null) return records;
        for (File file : files) {
            Properties properties = new Properties();
            try (FileInputStream input = new FileInputStream(file)) { properties.load(input); }
            String packageName = properties.getProperty("package", "");
            if (!PluginStore.isPackageName(packageName)
                    || !new File(directory, packageName + ".apk").isFile()) continue;
            Bundle record = new Bundle();
            record.putString("package", packageName);
            record.putString("entry", properties.getProperty("entry", ""));
            record.putString("name", properties.getProperty("name", packageName));
            record.putString("description", properties.getProperty("description", ""));
            record.putString("version", properties.getProperty("version", ""));
            record.putString("sha256", properties.getProperty("sha256", ""));
            record.putBoolean("enabled", Boolean.parseBoolean(properties.getProperty("enabled", "false")));
            records.add(record);
        }
        return records;
    }

    private static void load(Context context, ClassLoader appClassLoader,
            XposedInterface framework, Bundle record) throws Exception {
        String packageName = record.getString("package", "");
        String entryClass = record.getString("entry", "");
        String expectedHash = record.getString("sha256", "");
        if (!PluginStore.isPackageName(packageName) || entryClass.isEmpty()) {
            throw new IllegalArgumentException("Invalid plugin catalog entry");
        }

        File optimized = new File(context.getCodeCacheDir(), "palleria-plugins/optimized");
        File dexDirectory = new File(context.getCodeCacheDir(), "palleria-plugins/apks");
        if (!optimized.isDirectory() && !optimized.mkdirs()) throw new IllegalStateException("Cannot create optimized directory");
        if (!dexDirectory.isDirectory() && !dexDirectory.mkdirs()) throw new IllegalStateException("Cannot create plugin cache");
        File apk = new File(dexDirectory, packageName + ".apk");
        copyPluginApk(context, packageName, apk);
        if (!apk.setReadOnly()) throw new IllegalStateException("Cannot mark plugin APK read-only");

        ClassLoader apiParent = PalleriaPlugin.class.getClassLoader();
        ClassLoader pluginParent = new HostAwareClassLoader(apiParent, appClassLoader);
        DexClassLoader pluginLoader = new DexClassLoader(
                apk.getAbsolutePath(), optimized.getAbsolutePath(), null, pluginParent);
        Class<?> entry = Class.forName(entryClass, true, pluginLoader);
        if (!PalleriaPlugin.class.isAssignableFrom(entry)) {
            throw new IllegalArgumentException("Entry class must implement PalleriaPlugin");
        }
        PalleriaPlugin plugin = (PalleriaPlugin) entry.getDeclaredConstructor().newInstance();
        PluginContext pluginContext = new PluginContext(context, appClassLoader, framework, packageName,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        try {
            plugin.onLoad(pluginContext);
        } catch (Exception | Error failure) {
            pluginContext.close();
            throw failure;
        }
        loaded.put(packageName, new Loaded(plugin, pluginContext, pluginLoader, expectedHash));
        synchronized (statuses) { statuses.put(packageName, STATUS_LOADED); }
        Log.i(TAG, "Loaded plugin " + packageName + " entry=" + entryClass);
        framework.log(Log.INFO, TAG, "Loaded plugin " + packageName + " entry=" + entryClass);
    }

    private static void copyPluginApk(Context context, String packageName, File destination) throws Exception {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".new");
        if (destination.exists() && !destination.canWrite()) destination.setWritable(true, true);
        File cachedApk = new File(new File(context.getFilesDir(), "palleria-plugin-catalog"), packageName + ".apk");
        try (InputStream input = new FileInputStream(cachedApk)) {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            }
        }
        if (destination.exists() && !destination.delete()) throw new IllegalStateException("Cannot replace cached plugin APK");
        if (!temporary.renameTo(destination)) throw new IllegalStateException("Cannot finalize cached plugin APK");
    }

    private static void unload(Context context, String packageName, Loaded current) {
        loaded.remove(packageName);
        try {
            current.plugin.onUnload();
            synchronized (statuses) { statuses.put(packageName, STATUS_DISABLED); }
            Log.i(TAG, "Unloaded plugin " + packageName);
        } catch (Throwable failure) {
            synchronized (statuses) { statuses.put(packageName, "error:unload " + failure.getMessage()); }
            Log.e(TAG, "Plugin failed to unload: " + packageName, failure);
        } finally {
            current.pluginContext.close();
        }
    }

    /** Resolves Palleria types from its class loader and the stable plugin API from the module. */
    private static final class HostAwareClassLoader extends ClassLoader {
        private final ClassLoader host;

        HostAwareClassLoader(ClassLoader apiLoader, ClassLoader hostClassLoader) {
            super(apiLoader);
            host = hostClassLoader;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loadedClass = findLoadedClass(name);
            if (loadedClass == null) {
                if (name.startsWith("com.yunfie.illustia.lsposed.api.")) {
                    loadedClass = getParent().loadClass(name);
                } else {
                    try { loadedClass = host.loadClass(name); }
                    catch (ClassNotFoundException missingFromHost) { loadedClass = getParent().loadClass(name); }
                }
            }
            if (resolve && loadedClass.getClassLoader() == this) resolveClass(loadedClass);
            return loadedClass;
        }
    }

    private static final class Loaded {
        final PalleriaPlugin plugin;
        final PluginContext pluginContext;
        @SuppressWarnings("unused") final ClassLoader classLoader;
        final String sha256;
        Loaded(PalleriaPlugin plugin, PluginContext pluginContext, ClassLoader classLoader, String sha256) {
            this.plugin = plugin;
            this.pluginContext = pluginContext;
            this.classLoader = classLoader;
            this.sha256 = sha256;
        }
    }
}
