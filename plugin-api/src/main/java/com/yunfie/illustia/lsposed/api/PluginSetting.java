package com.yunfie.illustia.lsposed.api;

import java.util.Objects;

/** A small declarative setting shown on the plugin's Palladium settings page. */
public final class PluginSetting {
    public enum Type { BOOLEAN, STRING }

    private final String key;
    private final String title;
    private final String summary;
    private final Type type;
    private final Object defaultValue;

    private PluginSetting(String key, String title, String summary, Type type, Object defaultValue) {
        if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("Setting key is empty");
        if (title == null || title.trim().isEmpty()) throw new IllegalArgumentException("Setting title is empty");
        this.key = key;
        this.title = title;
        this.summary = summary == null ? "" : summary;
        this.type = type;
        this.defaultValue = defaultValue;
    }

    public static PluginSetting toggle(String key, String title, String summary, boolean defaultValue) {
        return new PluginSetting(key, title, summary, Type.BOOLEAN, defaultValue);
    }

    public static PluginSetting text(String key, String title, String summary, String defaultValue) {
        return new PluginSetting(key, title, summary, Type.STRING, Objects.requireNonNull(defaultValue));
    }

    public String getKey() { return key; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public Type getType() { return type; }
    public Object getDefaultValue() { return defaultValue; }
}
