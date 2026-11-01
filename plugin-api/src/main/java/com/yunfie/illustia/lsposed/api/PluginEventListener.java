package com.yunfie.illustia.lsposed.api;

import android.os.Bundle;

/** Receives an in-process plugin event on the thread that published it. */
@FunctionalInterface
public interface PluginEventListener {
    void onEvent(String name, Bundle payload);
}
