package com.yunfie.illustia.lsposed.api;

import android.app.Activity;
import android.os.Bundle;
import java.util.Collections;
import java.util.List;

/** Entry point implemented by a Palleria plugin APK. */
public interface PalleriaPlugin {
    /** Called once when this enabled plugin is loaded into Palleria's process. */
    void onLoad(PluginContext context) throws Exception;

    /** Called when the plugin is disabled or removed while Palleria is running. */
    default void onUnload() throws Exception {}

    /** Declarative settings rendered by Palladium. Defaults to no settings. */
    default List<PluginSetting> getSettings() { return Collections.emptyList(); }

    /** Called after a setting has been saved. The plugin can read its updated preferences. */
    default void onSettingChanged(String key, Object value) throws Exception {}

    default void onActivityCreated(Activity activity, Bundle state) throws Exception {}
    default void onActivityStarted(Activity activity) throws Exception {}
    default void onActivityResumed(Activity activity) throws Exception {}
    default void onActivityPaused(Activity activity) throws Exception {}
    default void onActivityStopped(Activity activity) throws Exception {}
    default void onActivitySaveInstanceState(Activity activity, Bundle state) throws Exception {}
    default void onActivityDestroyed(Activity activity) throws Exception {}
}
