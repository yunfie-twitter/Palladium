package com.yunfie.palleria.sampleplugin;

import android.util.Log;
import com.yunfie.illustia.lsposed.api.PalleriaPlugin;
import com.yunfie.illustia.lsposed.api.PluginContext;
import com.yunfie.illustia.lsposed.api.PluginSetting;
import java.util.Arrays;
import java.util.List;

/** Minimal, no-hook sample that verifies plugin class loading and lifecycle callbacks. */
public final class SamplePlugin implements PalleriaPlugin {
    private PluginContext context;

    public SamplePlugin() {}

    @Override
    public void onLoad(PluginContext context) {
        this.context = context;
        context.log(Log.INFO, "Sample plugin loaded. Palleria=" + context.getPalleriaVersionName()
                + " Palladium=" + context.getPalladiumVersionName()
                + " SDK=" + context.getSdkVersion());
        context.getPreferences().edit().putBoolean("sample_loaded", true).apply();
        context.subscribeEvent("sample.ping", (name, payload) ->
                context.log(Log.INFO, "Received event " + name));
        context.publishEvent("sample.ping", new android.os.Bundle());
    }

    @Override
    public List<PluginSetting> getSettings() {
        return Arrays.asList(
                PluginSetting.toggle("sample_enabled", "Sample switch", "Example boolean setting", true),
                PluginSetting.text("sample_message", "Message", "Example text setting", "Hello from plugin")
        );
    }

    @Override
    public void onSettingChanged(String key, Object value) {
        if (context != null) context.log(Log.INFO, "Setting changed: " + key + "=" + value);
    }

    @Override
    public void onActivityResumed(android.app.Activity activity) {
        // Lifecycle callbacks run only for activities in Palleria's process.
    }

    @Override
    public void onUnload() {
        if (context != null) context.log(Log.INFO, "Sample plugin unloaded");
        context = null;
    }
}
