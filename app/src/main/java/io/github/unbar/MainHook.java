package io.github.unbar;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.WindowManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Blocks the top transient status bar for user-selected foreground applications. */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "Unbar";
    private static final String ANDROID_PACKAGE = "android";
    private static final String DEFAULT_TARGET_PACKAGE = "com.tencent.tmgp.sgame";

    private static final String ACTION_SET_CONFIG = "io.github.unbar.ACTION_SET_CONFIG";
    private static final String CONFIG_PERMISSION = "io.github.unbar.permission.CONFIGURE";
    private static final String EXTRA_PACKAGES = "packages";
    private static final String EXTRA_LANDSCAPE_ONLY = "landscape_only";
    private static final String SETTING_PACKAGES = "unbar_target_packages";
    private static final String SETTING_LANDSCAPE_ONLY = "unbar_landscape_only";

    private static final int TYPE_STATUS_BAR = 2000;
    private static final int INSET_TYPE_STATUS_BARS = 1;
    private static final long LOG_THROTTLE_MS = 5000L;

    private static final AtomicBoolean receiverRegistered = new AtomicBoolean(false);
    private static volatile long lastBlockLog;
    private static volatile long lastDiagnosticLog;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!ANDROID_PACKAGE.equals(lpparam.packageName)
                || !ANDROID_PACKAGE.equals(lpparam.processName)) {
            return;
        }

        try {
            hookDisplayPolicy(lpparam.classLoader);
            hookInsetsPolicyFallback(lpparam.classLoader);
            hookConfigReceiverRegistration(lpparam.classLoader);
        } catch (Throwable throwable) {
            log("initialization failed");
            XposedBridge.log(throwable);
        }
    }

    private static void hookDisplayPolicy(ClassLoader classLoader) {
        Class<?> displayPolicy = XposedHelpers.findClass(
                "com.android.server.wm.DisplayPolicy", classLoader);
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                displayPolicy, "requestTransientBars", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        boolean targetFocused = isTargetFocused(param.thisObject);
                        boolean orientationMatches = orientationMatches(param.thisObject);
                        boolean statusBarTarget = isStatusBarTarget(param.thisObject, param.args);
                        logDiagnosticThrottled("DisplayPolicy.requestTransientBars",
                                param.thisObject, param.args, targetFocused, orientationMatches,
                                statusBarTarget);

                        if (targetFocused && orientationMatches && statusBarTarget) {
                            param.setResult(null);
                            logBlockedThrottled("DisplayPolicy");
                        }
                    }
                });
        log("active: hooked " + (hooks == null ? 0 : hooks.size())
                + " requestTransientBars method(s)");
    }

    private static void hookInsetsPolicyFallback(ClassLoader classLoader) {
        try {
            Class<?> insetsPolicy = XposedHelpers.findClass(
                    "com.android.server.wm.InsetsPolicy", classLoader);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    insetsPolicy, "showTransient", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean targetFocused = isTargetFocused(param.thisObject);
                            boolean orientationMatches = orientationMatches(param.thisObject);
                            boolean containsStatusBars = containsStatusBarInsets(param.args);
                            logDiagnosticThrottled("InsetsPolicy.showTransient",
                                    param.thisObject, param.args, targetFocused,
                                    orientationMatches, containsStatusBars);

                            if (!targetFocused || !orientationMatches || !containsStatusBars) {
                                return;
                            }

                            if (param.args[0] instanceof Integer) {
                                int filteredTypes = ((Integer) param.args[0])
                                        & ~INSET_TYPE_STATUS_BARS;
                                if (filteredTypes == 0) {
                                    param.setResult(null);
                                } else {
                                    param.args[0] = filteredTypes;
                                }
                                logBlockedThrottled("InsetsPolicy");
                            } else if (param.args[0] instanceof int[]) {
                                filterLegacyInsetTypes(param, (int[]) param.args[0]);
                                logBlockedThrottled("InsetsPolicy-int-array");
                            }
                        }
                    });
            log("fallback active: hooked " + (hooks == null ? 0 : hooks.size())
                    + " InsetsPolicy.showTransient method(s)");
        } catch (Throwable throwable) {
            log("InsetsPolicy fallback initialization failed");
            XposedBridge.log(throwable);
        }
    }

    private static void filterLegacyInsetTypes(XC_MethodHook.MethodHookParam param, int[] source) {
        int kept = 0;
        for (int type : source) {
            if (type != 0) {
                kept++;
            }
        }
        if (kept == 0) {
            param.setResult(null);
        } else if (kept != source.length) {
            int[] filtered = new int[kept];
            int index = 0;
            for (int type : source) {
                if (type != 0) {
                    filtered[index++] = type;
                }
            }
            param.args[0] = filtered;
        }
    }

    private static void hookConfigReceiverRegistration(ClassLoader classLoader) {
        try {
            Class<?> activityManagerService = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerService", classLoader);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    activityManagerService, "systemReady", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object context = getField(param.thisObject, "mContext");
                            if (context instanceof Context) {
                                registerConfigReceiver((Context) context);
                            }
                        }
                    });
            log("config bridge pending: hooked " + (hooks == null ? 0 : hooks.size())
                    + " ActivityManagerService.systemReady method(s)");
        } catch (Throwable throwable) {
            log("config bridge initialization failed");
            XposedBridge.log(throwable);
        }
    }

    private static void registerConfigReceiver(Context context) {
        if (!receiverRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(ACTION_SET_CONFIG);
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (!ACTION_SET_CONFIG.equals(intent.getAction())) {
                        return;
                    }
                    String[] packages = intent.getStringArrayExtra(EXTRA_PACKAGES);
                    boolean landscapeOnly = intent.getBooleanExtra(EXTRA_LANDSCAPE_ONLY, true);
                    if (packages == null) {
                        packages = new String[0];
                    }
                    Arrays.sort(packages);
                    String joined = TextUtils.join(",", packages);
                    Settings.Global.putString(receiverContext.getContentResolver(),
                            SETTING_PACKAGES, joined);
                    Settings.Global.putInt(receiverContext.getContentResolver(),
                            SETTING_LANDSCAPE_ONLY, landscapeOnly ? 1 : 0);
                    log("configuration updated: packages=" + joined
                            + ", landscapeOnly=" + landscapeOnly);
                }
            }, filter, CONFIG_PERMISSION, null, Context.RECEIVER_EXPORTED);
            log("config bridge active");
        } catch (Throwable throwable) {
            receiverRegistered.set(false);
            log("config bridge registration failed");
            XposedBridge.log(throwable);
        }
    }

    private static boolean isTargetFocused(Object policy) {
        Set<String> targets = getTargetPackages(policy);
        Object displayContent = getDisplayContent(policy);
        Object[] windowCandidates = new Object[] {
                getField(policy, "mFocusedWindow"),
                getField(policy, "mFocusedWin"),
                getField(displayContent, "mCurrentFocus")
        };
        for (Object window : windowCandidates) {
            if (targets.contains(getWindowPackage(window))) {
                return true;
            }
        }

        Object[] activityCandidates = new Object[] {
                getField(displayContent, "mFocusedApp"),
                call(call(displayContent, "getFocusedRootTask"), "topRunningActivity")
        };
        for (Object activity : activityCandidates) {
            if (targets.contains(getActivityPackage(activity))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> getTargetPackages(Object policy) {
        Context context = getPolicyContext(policy);
        if (context == null) {
            return Collections.singleton(DEFAULT_TARGET_PACKAGE);
        }
        String value = Settings.Global.getString(context.getContentResolver(), SETTING_PACKAGES);
        if (value == null) {
            return Collections.singleton(DEFAULT_TARGET_PACKAGE);
        }
        if (value.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> packages = new HashSet<>();
        for (String item : value.split(",")) {
            String packageName = item.trim();
            if (!packageName.isEmpty()) {
                packages.add(packageName);
            }
        }
        return packages;
    }

    private static boolean orientationMatches(Object policy) {
        Context context = getPolicyContext(policy);
        boolean landscapeOnly = context == null || Settings.Global.getInt(
                context.getContentResolver(), SETTING_LANDSCAPE_ONLY, 1) != 0;
        return !landscapeOnly || isLandscape(policy);
    }

    private static Context getPolicyContext(Object policy) {
        Object context = getField(policy, "mContext");
        if (!(context instanceof Context)) {
            context = getField(getField(policy, "mPolicy"), "mContext");
        }
        return context instanceof Context ? (Context) context : null;
    }

    private static String getWindowPackage(Object windowState) {
        if (windowState == null) {
            return null;
        }
        Object owner = call(windowState, "getOwningPackage");
        if (owner instanceof String) {
            return (String) owner;
        }
        Object attrs = call(windowState, "getAttrs");
        Object packageName = getField(attrs, "packageName");
        return packageName instanceof String ? (String) packageName : null;
    }

    private static String getActivityPackage(Object activityRecord) {
        if (activityRecord == null) {
            return null;
        }
        Object packageName = getField(activityRecord, "packageName");
        if (packageName instanceof String) {
            return (String) packageName;
        }
        Object component = getField(activityRecord, "mActivityComponent");
        if (component instanceof ComponentName) {
            return ((ComponentName) component).getPackageName();
        }
        return null;
    }

    private static Object getDisplayContent(Object policy) {
        Object displayContent = getField(policy, "mDisplayContent");
        if (displayContent != null) {
            return displayContent;
        }
        return getField(getField(policy, "mPolicy"), "mDisplayContent");
    }

    private static boolean isLandscape(Object policy) {
        Object displayContent = getDisplayContent(policy);
        Object configuration = call(displayContent, "getConfiguration");
        if (configuration instanceof Configuration) {
            int orientation = ((Configuration) configuration).orientation;
            if (orientation != Configuration.ORIENTATION_UNDEFINED) {
                return orientation == Configuration.ORIENTATION_LANDSCAPE;
            }
        }
        Context context = getPolicyContext(policy);
        if (context != null) {
            int orientation = context.getResources().getConfiguration().orientation;
            if (orientation != Configuration.ORIENTATION_UNDEFINED) {
                return orientation == Configuration.ORIENTATION_LANDSCAPE;
            }
        }
        return true;
    }

    private static boolean containsStatusBarInsets(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return false;
        }
        if (args[0] instanceof Integer) {
            return (((Integer) args[0]) & INSET_TYPE_STATUS_BARS) != 0;
        }
        if (args[0] instanceof int[]) {
            for (int type : (int[]) args[0]) {
                if (type == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isStatusBarTarget(Object displayPolicy, Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return false;
        }
        Object swipeTarget = args[0];
        Object statusBar = getField(displayPolicy, "mStatusBar");
        if (statusBar == null) {
            statusBar = call(displayPolicy, "getStatusBar");
        }
        if (statusBar != null && swipeTarget == statusBar) {
            return true;
        }
        Object attrs = call(swipeTarget, "getAttrs");
        if (attrs instanceof WindowManager.LayoutParams) {
            return ((WindowManager.LayoutParams) attrs).type == TYPE_STATUS_BAR;
        }
        Object type = getField(attrs, "type");
        return type instanceof Integer && ((Integer) type) == TYPE_STATUS_BAR;
    }

    private static Object getField(Object object, String name) {
        if (object == null) {
            return null;
        }
        try {
            return XposedHelpers.getObjectField(object, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object call(Object object, String name, Object... args) {
        if (object == null) {
            return null;
        }
        try {
            return XposedHelpers.callMethod(object, name, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void logBlockedThrottled(String route) {
        long now = SystemClock.uptimeMillis();
        if (now - lastBlockLog >= LOG_THROTTLE_MS) {
            lastBlockLog = now;
            log("blocked via " + route);
        }
    }

    private static void logDiagnosticThrottled(String route, Object policy, Object[] args,
            boolean targetFocused, boolean orientationMatches, boolean statusCandidate) {
        long now = SystemClock.uptimeMillis();
        if (now - lastDiagnosticLog < 1000L) {
            return;
        }
        lastDiagnosticLog = now;
        Object displayContent = getDisplayContent(policy);
        Object directFocus = getField(policy, "mFocusedWindow");
        if (directFocus == null) {
            directFocus = getField(policy, "mFocusedWin");
        }
        String argType = args == null || args.length == 0 || args[0] == null
                ? "null" : args[0].getClass().getName();
        log("diagnostic route=" + route
                + " direct=" + getWindowPackage(directFocus)
                + " current=" + getWindowPackage(getField(displayContent, "mCurrentFocus"))
                + " app=" + getActivityPackage(getField(displayContent, "mFocusedApp"))
                + " target=" + targetFocused
                + " orientation=" + orientationMatches
                + " status=" + statusCandidate
                + " arg0=" + argType);
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
