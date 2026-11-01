package com.yunfie.illustia.lsposed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Locale;

/** LSPosed entry point for the Palleria process. */
public final class PalleriaModule extends XposedModule {
    private static final String TAG = "PalleriaPlugins";
    private static final String PALLERIA_PACKAGE = "com.yunfie.illustia";
    private static final String MODULE_PACKAGE = "com.yunfie.illustia.lsposed";
    private static boolean pluginRowInserted;
    private static volatile Context hostContext;
    private static volatile ClassLoader targetClassLoader;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Log.i(TAG, "onModuleLoaded process=" + param.getProcessName());
        log(Log.INFO, TAG, "onModuleLoaded process=" + param.getProcessName());
        if (!PALLERIA_PACKAGE.equals(param.getProcessName())) {
            Log.i(TAG, "Ignoring non-main Palleria process: " + param.getProcessName());
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        Log.i(TAG, "onPackageLoaded package=" + param.getPackageName());
        log(Log.INFO, TAG, "onPackageLoaded package=" + param.getPackageName());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        Log.i(TAG, "onPackageReady package=" + param.getPackageName());
        log(Log.INFO, TAG, "onPackageReady package=" + param.getPackageName());
        if (!PALLERIA_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        try {
            targetClassLoader = param.getClassLoader();
            try {
                hookApplicationAttach();
            } catch (Throwable throwable) {
                log(Log.WARN, "PalleriaPlugins", "Application context hook unavailable; using Activity fallback.", throwable);
            }
            try {
                hookActivityResume();
            } catch (Throwable throwable) {
                log(Log.WARN, "PalleriaPlugins", "Activity context fallback unavailable.", throwable);
            }
            try {
                hookActivityResult();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Could not install plugin-manager result hook.", throwable);
            }
            hookSettingsCategoryList(param.getClassLoader());
        } catch (Throwable throwable) {
            Log.e(TAG, "Could not install the Palleria settings category hook.", throwable);
            log(Log.ERROR, TAG, "Could not install the Palleria settings category hook.", throwable);
        }
    }

    /** Hooks Palleria's R8 category model, rather than invoking a composable outside its list. */
    private void hookSettingsCategoryList(ClassLoader appClassLoader) throws Exception {
        Class<?> categoryContent = appClassLoader.loadClass("do2");
        Method compose = null;
        for (Method method : categoryContent.getDeclaredMethods()) {
            if (method.getName().equals("e") && method.getParameterCount() == 3) {
                compose = method;
                break;
            }
        }
        if (compose == null) {
            throw new IllegalStateException("Could not find Palleria's settings category content method.");
        }
        compose.setAccessible(true);
        hook(compose).intercept(chain -> {
            Object receiver = chain.getThisObject();
            try {
                Field variantField = categoryContent.getDeclaredField("f");
                variantField.setAccessible(true);
                Object variant = variantField.get(receiver);
                Field callbackListField = categoryContent.getDeclaredField("h");
                callbackListField.setAccessible(true);
                Object rawList = callbackListField.get(receiver);
                Log.d(TAG, "Settings category callback variant=" + variant
                        + " size=" + (rawList instanceof java.util.List<?> ? ((java.util.List<?>) rawList).size() : -1));
                if (variant instanceof Number && ((Number) variant).intValue() == 1) {
                    injectSettingsCategory(appClassLoader, receiver, categoryContent);
                }
            } catch (Throwable throwable) {
                Log.e(TAG, "Could not add plugin settings to Palleria's category list.", throwable);
                log(Log.ERROR, TAG, "Could not add plugin settings to Palleria's category list.", throwable);
            }
            return chain.proceed();
        });
        Log.i(TAG, "Installed settings category-list hook: do2.e");
        log(Log.INFO, TAG, "Installed settings category-list hook: do2.e");
    }

    private void injectSettingsCategory(
            ClassLoader appClassLoader, Object receiver, Class<?> contentClass) throws Exception {
        Field listField = contentClass.getDeclaredField("h");
        listField.setAccessible(true);
        Object rawList = listField.get(receiver);
        if (!(rawList instanceof java.util.List<?>)) return;

        Context context = hostContext;
        if (context == null) {
            Log.w(TAG, "Cannot inject settings category: host context is not available.");
            return;
        }
        boolean isJapanese = isJapanese(context.getResources().getConfiguration());
        int updateTitleId = context.getResources().getIdentifier(
                "update_settings_title", "string", PALLERIA_PACKAGE);
        if (updateTitleId == 0) throw new IllegalStateException("Palleria update settings title resource is missing");
        String updateTitle = context.getString(updateTitleId);
        Log.i(TAG, "Looking for update category title=" + updateTitle + " entries=" + rawList.getClass().getName()
                + "/" + ((java.util.List<?>) rawList).size());
        String pluginTitle = isJapanese ? "プラグイン設定" : "Plugin settings";
        String pluginSummary = isJapanese ? "インストール済みプラグインを管理" : "Manage installed plugins";

        java.util.List<?> categories = (java.util.List<?>) rawList;
        Class<?> categoryClass = appClassLoader.loadClass("d96");
        Field titleField = categoryClass.getDeclaredField("a");
        Field iconField = categoryClass.getDeclaredField("c");
        titleField.setAccessible(true);
        iconField.setAccessible(true);

        int updateIndex = -1;
        Object updateCategory = null;
        for (int index = 0; index < categories.size(); index++) {
            Object category = categories.get(index);
            if (!categoryClass.isInstance(category)) continue;
            String title = (String) titleField.get(category);
            if (pluginTitle.equals(title)) return;
            if (updateTitle.equals(title)) {
                updateIndex = index;
                updateCategory = category;
            }
        }
        if (updateIndex < 0 || updateCategory == null) return;

        Constructor<?> constructor = null;
        for (Constructor<?> candidate : categoryClass.getDeclaredConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length == 4
                    && parameters[0] == String.class
                    && parameters[1] == String.class
                    && parameters[2].isInstance(iconField.get(updateCategory))
                    && parameters[3].isInterface()) {
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) {
            throw new IllegalStateException("Could not find the R8 settings category constructor.");
        }
        constructor.setAccessible(true);
        Object clickCallback = createClickCallback(appClassLoader, constructor.getParameterTypes()[3]);
        Object pluginCategory = constructor.newInstance(
                pluginTitle, pluginSummary, iconField.get(updateCategory), clickCallback);

        ArrayList<Object> updatedCategories = new ArrayList<>(categories);
        updatedCategories.add(updateIndex + 1, pluginCategory);
        listField.set(receiver, updatedCategories);
        if (!pluginRowInserted) {
            pluginRowInserted = true;
            Log.i(TAG, "Added plugin settings category after the update category.");
            log(Log.INFO, TAG, "Added plugin settings category after the update category.");
        }
    }

    private void hookApplicationAttach() throws NoSuchMethodException {
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        attach.setAccessible(true);
        hook(attach).intercept(chain -> {
            Object result = chain.proceed();
            Object context = chain.getArg(0);
            if (context instanceof Context && PALLERIA_PACKAGE.equals(((Context) context).getPackageName())) {
                hostContext = (Context) context;
                Log.i(TAG, "Captured Palleria application context.");
                log(Log.INFO, TAG, "Captured Palleria application context.");
                PluginRuntime.start(hostContext, targetClassLoader, this);
            }
            return result;
        });
    }

    private void hookActivityResume() throws NoSuchMethodException {
        Method onResume = Activity.class.getDeclaredMethod("onResume");
        hook(onResume).intercept(chain -> {
            Object result = chain.proceed();
            Object receiver = chain.getThisObject();
            if (receiver instanceof Activity && PALLERIA_PACKAGE.equals(((Activity) receiver).getPackageName())) {
                hostContext = (Activity) receiver;
                Log.i(TAG, "Captured Palleria Activity context.");
                log(Log.INFO, TAG, "Captured Palleria Activity context.");
                PluginRuntime.start(hostContext, targetClassLoader, this);
            }
            return result;
        });
    }

    private void hookActivityResult() throws NoSuchMethodException {
        Method result = null;
        // Android 15+ dispatches through the ComponentCaller overload. Hook the
        // overload the framework actually invokes so manager results are not lost.
        if (Build.VERSION.SDK_INT >= 35) {
            for (Method candidate : Activity.class.getDeclaredMethods()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (candidate.getName().equals("onActivityResult") && parameters.length == 4
                        && parameters[0] == int.class && parameters[1] == int.class
                        && parameters[2] == Intent.class
                        && parameters[3].getName().equals("android.app.ComponentCaller")) {
                    result = candidate;
                    break;
                }
            }
        }
        if (result == null) {
            result = Activity.class.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class);
        }
        result.setAccessible(true);
        hook(result).intercept(chain -> {
            Object value = chain.proceed();
            if (chain.getThisObject() instanceof Activity
                    && chain.getArg(0) instanceof Integer
                    && ((Integer) chain.getArg(0)) == PluginSettingsActivity.REQUEST_CODE
                    && chain.getArg(1) instanceof Integer
                    && ((Integer) chain.getArg(1)) == Activity.RESULT_OK
                    && chain.getArg(2) instanceof Intent) {
                try {
                    PluginRuntime.syncFromManagerResult((Activity) chain.getThisObject(), (Intent) chain.getArg(2));
                } catch (Throwable failure) {
                    Log.e(TAG, "Could not sync the plugin catalog from Palladium.", failure);
                    log(Log.ERROR, TAG, "Could not sync the plugin catalog from Palladium.", failure);
                }
            }
            return value;
        });
    }

    private static boolean isJapanese(Configuration configuration) {
        Locale locale = configuration.getLocales().isEmpty() ? Locale.getDefault() : configuration.getLocales().get(0);
        return "ja".equals(locale.getLanguage());
    }

    private static Object createClickCallback(ClassLoader appClassLoader, Class<?> callbackType) {
        if (!callbackType.isInterface()) {
            throw new IllegalStateException("Compose click callback is not an interface: " + callbackType);
        }
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("toString")) return "PalleriaPluginSettingsClick";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            Context context = hostContext;
            if (context != null) {
                Intent intent = new Intent().setClassName(MODULE_PACKAGE,
                        "com.yunfie.illustia.lsposed.PluginSettingsActivity");
                if (context instanceof Activity) {
                    intent.putExtra(PluginSettingsActivity.EXTRA_RUNTIME_STATUS, PluginRuntime.statusSnapshot());
                    intent.putExtra(PluginSettingsActivity.EXTRA_PLUGIN_SETTINGS, PluginRuntime.settingsSnapshot());
                    ((Activity) context).startActivityForResult(intent, PluginSettingsActivity.REQUEST_CODE);
                } else {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == void.class) return null;
            if (!returnType.isPrimitive()) {
                try {
                    return returnType.getField("INSTANCE").get(null);
                } catch (ReflectiveOperationException ignored) {
                    return null;
                }
            }
            if (returnType == boolean.class) return false;
            if (returnType == char.class) return '\0';
            if (returnType == byte.class) return (byte) 0;
            if (returnType == short.class) return (short) 0;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0f;
            if (returnType == double.class) return 0d;
            return null;
        };
        return Proxy.newProxyInstance(appClassLoader, new Class<?>[]{callbackType}, handler);
    }
}
