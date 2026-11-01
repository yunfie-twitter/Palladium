package com.yunfie.illustia.lsposed.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import io.github.libxposed.api.XposedInterface;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Executable;

/** Runtime services available to a plugin running inside the Palleria process. */
public final class PluginContext implements AutoCloseable {
    public static final int API_VERSION = 1;
    public static final String CAP_METHOD_HOOKS = "method_hooks";
    public static final String CAP_PLUGIN_SETTINGS = "plugin_settings";
    public static final String CAP_ACTIVITY_LIFECYCLE = "activity_lifecycle";
    public static final String CAP_PLUGIN_EVENTS = "plugin_events";
    public static final String CAP_PLUGIN_PREFERENCES = "plugin_preferences";
    public static final String CAP_PLUGIN_FILES = "plugin_files";

    private static final Object EVENT_LOCK = new Object();
    private static final Map<String, List<EventRegistration>> EVENT_LISTENERS = new HashMap<>();
    private static final java.util.Set<String> CAPABILITIES = Collections.unmodifiableSet(
            new java.util.HashSet<>(Arrays.asList(
                    CAP_METHOD_HOOKS, CAP_PLUGIN_SETTINGS, CAP_ACTIVITY_LIFECYCLE,
                    CAP_PLUGIN_EVENTS, CAP_PLUGIN_PREFERENCES, CAP_PLUGIN_FILES)));

    private final Context hostContext;
    private final ClassLoader hostClassLoader;
    private final XposedInterface xposed;
    private final String pluginId;
    private final String palladiumVersionName;
    private final long palladiumVersionCode;
    private final List<XposedInterface.HookHandle> hooks = new ArrayList<>();
    private final List<PluginEventSubscription> subscriptions = new ArrayList<>();
    private boolean closed;

    public PluginContext(Context hostContext, ClassLoader hostClassLoader,
            XposedInterface xposed, String pluginId) {
        this(hostContext, hostClassLoader, xposed, pluginId, "unknown", -1);
    }

    public PluginContext(Context hostContext, ClassLoader hostClassLoader,
            XposedInterface xposed, String pluginId, String palladiumVersionName, long palladiumVersionCode) {
        this.hostContext = hostContext;
        this.hostClassLoader = hostClassLoader;
        this.xposed = xposed;
        this.pluginId = pluginId;
        this.palladiumVersionName = palladiumVersionName;
        this.palladiumVersionCode = palladiumVersionCode;
    }

    /** Palleria's application context. */
    public Context getHostContext() { return hostContext; }

    /** Class loader containing Palleria's classes. */
    public ClassLoader getHostClassLoader() { return hostClassLoader; }

    /** libxposed interface for method hooks, deoptimization, and framework logging. */
    public XposedInterface getXposed() { return xposed; }

    /** Package name of this plugin APK. */
    public String getPluginId() { return pluginId; }

    public int getSdkVersion() { return API_VERSION; }
    public String getPalladiumVersionName() { return palladiumVersionName; }
    public long getPalladiumVersionCode() { return palladiumVersionCode; }
    public String getPalleriaVersionName() { return hostPackageInfo().versionName; }
    public long getPalleriaVersionCode() {
        PackageInfo info = hostPackageInfo();
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }
    public java.util.Set<String> getCapabilities() { return CAPABILITIES; }
    public boolean hasCapability(String capability) { return CAPABILITIES.contains(capability); }

    private PackageInfo hostPackageInfo() {
        try {
            return hostContext.getPackageManager().getPackageInfo(hostContext.getPackageName(), 0);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read Palleria package version", failure);
        }
    }

    /** Preferences stored in Palladium's private data and namespaced to this plugin. */
    public SharedPreferences getPreferences() {
        return hostContext.getSharedPreferences("plugin." + pluginId, Context.MODE_PRIVATE);
    }

    /** Private files owned by this plugin. */
    public File getFilesDirectory() {
        File directory = new File(hostContext.getFilesDir(), "plugins/" + pluginId);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create plugin files directory");
        }
        return directory;
    }

    /** Return a file below the plugin's private directory, rejecting path traversal. */
    public File getFile(String relativePath) throws IOException {
        if (relativePath == null || relativePath.trim().isEmpty()) throw new IllegalArgumentException("File path is empty");
        File root = getFilesDirectory().getCanonicalFile();
        File file = new File(root, relativePath).getCanonicalFile();
        if (!file.toPath().startsWith(root.toPath()) || file.equals(root)) {
            throw new SecurityException("File path must stay inside the plugin directory");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create plugin file directory");
        }
        return file;
    }

    public FileInputStream openFileInput(String relativePath) throws IOException {
        return new FileInputStream(getFile(relativePath));
    }

    public FileOutputStream openFileOutput(String relativePath, boolean append) throws IOException {
        return new FileOutputStream(getFile(relativePath), append);
    }

    /** Resolve a class from Palleria's class loader. */
    public Class<?> findClass(String className) throws ClassNotFoundException {
        return hostClassLoader.loadClass(className);
    }

    /** Resolve and make a declared Palleria method accessible. */
    public Method findMethod(String className, String methodName, Class<?>... parameterTypes)
            throws ReflectiveOperationException {
        Method method = findClass(className).getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    /** Convenience method that tags log messages with the plugin package. */
    public void log(int priority, String message) {
        xposed.log(priority, "PalleriaPlugin/" + pluginId, message);
    }

    /** Log a failure with its stack trace. */
    public void log(int priority, String message, Throwable failure) {
        xposed.log(priority, "PalleriaPlugin/" + pluginId, message, failure);
    }

    /** Listen for a named event. The subscription is removed automatically on unload. */
    public synchronized PluginEventSubscription subscribeEvent(String name, PluginEventListener listener) {
        if (closed) throw new IllegalStateException("Plugin is already unloaded");
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Event name is empty");
        EventRegistration registration = new EventRegistration(this, name, listener);
        synchronized (EVENT_LOCK) {
            EVENT_LISTENERS.computeIfAbsent(name, ignored -> new ArrayList<>()).add(registration);
        }
        subscriptions.add(registration);
        return registration;
    }

    /** Publish a process-local event. Listeners run synchronously on the caller's thread. */
    public void publishEvent(String name, Bundle payload) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Event name is empty");
        List<EventRegistration> listeners;
        synchronized (EVENT_LOCK) {
            listeners = new ArrayList<>(EVENT_LISTENERS.getOrDefault(name, Collections.emptyList()));
        }
        for (EventRegistration registration : listeners) {
            if (registration.closed) continue;
            try {
                registration.listener.onEvent(name, payload == null ? new Bundle() : new Bundle(payload));
            } catch (Throwable failure) {
                registration.owner.log(android.util.Log.ERROR, "Event listener failed: " + name, failure);
            }
        }
    }

    /** Register a hook that Palladium automatically removes when this plugin unloads. */
    public synchronized XposedInterface.HookHandle hook(Executable target,
            XposedInterface.Hooker callback, int priority) {
        if (closed) throw new IllegalStateException("Plugin is already unloaded");
        XposedInterface.HookHandle handle = xposed.hook(target)
                .setPriority(priority)
                .intercept(callback);
        hooks.add(handle);
        return handle;
    }

    /** Remove a managed hook early and unregister it from automatic cleanup. */
    public synchronized void unhook(XposedInterface.HookHandle handle) {
        if (hooks.remove(handle)) handle.unhook();
    }

    /** Called by the loader after the plugin's onUnload callback. */
    @Override
    public void close() {
        List<PluginEventSubscription> eventSubscriptions;
        List<XposedInterface.HookHandle> registeredHooks;
        synchronized (this) {
            if (closed) return;
            closed = true;
            eventSubscriptions = new ArrayList<>(subscriptions);
            registeredHooks = new ArrayList<>(hooks);
            subscriptions.clear();
            hooks.clear();
        }
        for (PluginEventSubscription subscription : eventSubscriptions) subscription.close();
        for (int index = registeredHooks.size() - 1; index >= 0; index--) {
            try {
                registeredHooks.get(index).unhook();
            } catch (Throwable failure) {
                log(android.util.Log.WARN, "Could not remove a plugin hook", failure);
            }
        }
    }

    private static final class EventRegistration implements PluginEventSubscription {
        final PluginContext owner;
        final String name;
        final PluginEventListener listener;
        volatile boolean closed;

        EventRegistration(PluginContext owner, String name, PluginEventListener listener) {
            this.owner = owner;
            this.name = name;
            this.listener = listener;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            synchronized (EVENT_LOCK) {
                List<EventRegistration> registrations = EVENT_LISTENERS.get(name);
                if (registrations != null) {
                    registrations.remove(this);
                    if (registrations.isEmpty()) EVENT_LISTENERS.remove(name);
                }
            }
            synchronized (owner) { owner.subscriptions.remove(this); }
        }
    }
}
