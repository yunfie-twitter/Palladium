# Palleria Plugin Loader

独立したLSPosedモジュールとプラグインSDKです。Palleria本体のソースやAPKは変更しません。モジュールはPalleriaの設定一覧に「プラグイン設定」を追加し、選択したプラグインAPKをPalleriaプロセス内でロードします。

## 機能

- Compose製のプラグイン管理画面
- APKファイルのインポート、一覧、有効化、削除
- 追加したプラグインは初期状態で無効
- 有効なプラグインの起動と停止を実行時に反映
- プラグインごとのロードエラー表示
- Palleriaから設定画面を開いている間だけ、Activity Result経由でAPKをホスト側へ安全に受け渡し
- Palleria設定内への入口追加

プラグインコードはPalleriaと同じプロセスで実行されます。信頼できるプラグインだけを有効にしてください。フックの解除や保持するリソースの解放は、各プラグインが`onUnload`で行います。

## プラグインの作成

AndroidアプリAPKとして作成し、`:plugin-api`を`compileOnly`依存にします。プラグインAPKを端末へインストールする必要はありません。ManifestにAPIバージョンとエントリークラスを指定してください。

```groovy
dependencies {
    compileOnly project(":plugin-api")
    compileOnly "io.github.libxposed:api:101.0.1"
}
```

```xml
<application>
    <meta-data
        android:name="com.yunfie.illustia.lsposed.plugin.API"
        android:value="1" />
    <meta-data
        android:name="com.yunfie.illustia.lsposed.plugin.ENTRY"
        android:value="example.plugin.MyPlugin" />
    <meta-data
        android:name="com.yunfie.illustia.lsposed.plugin.NAME"
        android:value="Example plugin" />
    <meta-data
        android:name="com.yunfie.illustia.lsposed.plugin.DESCRIPTION"
        android:value="Example Palleria extension" />
</application>
```

Entry class must have a public no-argument constructor and implement `PalleriaPlugin`:

```java
public final class MyPlugin implements PalleriaPlugin {
    private PluginContext context;

    @Override
    public void onLoad(PluginContext context) throws Exception {
        this.context = context;
        context.getPreferences().edit().putBoolean("initialized", true).apply();
        context.hook(context.findMethod("com.yunfie.illustia.SomeClass", "someMethod"), chain -> {
            context.log(android.util.Log.INFO, "someMethod called");
            return chain.proceed();
        }, XposedInterface.PRIORITY_DEFAULT);
    }

    @Override
    public void onUnload() {
        context = null;
    }

    @Override
    public List<PluginSetting> getSettings() {
        return Arrays.asList(
            PluginSetting.toggle("enabled", "Enable feature", "", true),
            PluginSetting.text("message", "Message", "Shown by the feature", "Hello")
        );
    }

    @Override
    public void onActivityResumed(Activity activity) {
        context.log(android.util.Log.INFO, "Palleria activity resumed");
    }
}
```

`PluginContext` includes plugin-scoped preferences and files, host class/method lookup, tagged logging, managed hooks, event publish/subscribe, and version/capability queries. Hooks and event subscriptions are removed automatically after `onUnload`; use `context.unhook(handle)` or close a subscription to remove one early. Toggle and text settings declared by `getSettings()` appear under that plugin's settings button. Saved values are available through `getPreferences()` and `onSettingChanged` runs after edits are applied. Lifecycle callbacks run on Android's Activity lifecycle thread and only for Palleria activities. The SDK API version is `1`; version and feature checks are available through `getSdkVersion()`, `getPalleriaVersionName()`, `getPalladiumVersionName()`, and `hasCapability()`. A plugin with another or missing API version is rejected during import. The plugin package name uniquely identifies it in the catalog; importing another APK with the same package replaces the existing plugin.

## Recommended plugin APIs

Available in the current SDK:

1. **Hook lifecycle management** — register hooks through `PluginContext` and have them removed on disable or removal.
2. **Per-plugin preferences** — save simple settings under a plugin-specific private namespace.
3. **Host class/method lookup** — resolve Palleria classes and declared methods without repeating reflection boilerplate.
4. **Plugin settings UI** — declarative toggle and text settings under each plugin card.
5. **Host lifecycle callbacks** — Activity lifecycle events for Palleria screens.
6. **Plugin-to-plugin events** — in-process named events with `Bundle` payloads.
7. **Version and capability queries** — Palladium, Palleria, and SDK versions plus named capabilities.
8. **Scoped files** — private plugin directories with traversal-safe file helpers.

Events are synchronous and delivered on the publishing thread. Keep payloads small and avoid blocking lifecycle callbacks. All plugins execute in Palleria's process and share its privileges; install only plugins you trust.

## Build and install

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enable the module in LSPosed, select Palleria as its scope, then restart Palleria. Open Palleria settings → Plugin settings → Add APK. Turn on the imported plugin and close the manager to apply the change. The loader caches the received APKs in Palleria's private files and loads enabled plugins on startup.

## Requirements

- Android SDK 37 and Java 17
- LSPosed/Vector with libxposed API 101+
- Palleria package `com.yunfie.illustia`
- Android 8.0 (API 26) or later
