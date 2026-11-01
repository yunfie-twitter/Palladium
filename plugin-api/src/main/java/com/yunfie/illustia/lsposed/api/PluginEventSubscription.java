package com.yunfie.illustia.lsposed.api;

/** Handle for removing a plugin event subscription before plugin unload. */
@FunctionalInterface
public interface PluginEventSubscription extends AutoCloseable {
    @Override
    void close();
}
