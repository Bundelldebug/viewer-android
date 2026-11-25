package com.samsung.oneui.viewer;

import android.app.Activity;
import android.graphics.Rect;
import java.lang.reflect.*;
import java.util.List;

/** Optional OEM Window Extensions listener. Ordinary phones still use window-size adaptation. */
final class FoldObserver {
    interface Listener { void onFold(Rect bounds); }
    private Object component, callback;
    private Method remove;
    void start(Activity activity, Listener listener) {
        try {
            Class<?> provider = Class.forName("androidx.window.extensions.WindowExtensionsProvider");
            Object extensions = provider.getMethod("getWindowExtensions").invoke(null);
            component = Class.forName("androidx.window.extensions.WindowExtensions").getMethod("getWindowLayoutComponent").invoke(extensions);
            Class<?> api = Class.forName("androidx.window.extensions.layout.WindowLayoutComponent");
            for (Method method : api.getMethods()) {
                if (!method.getName().equals("addWindowLayoutInfoListener") || method.getParameterTypes().length != 2) continue;
                Class<?> owner = method.getParameterTypes()[0], consumer = method.getParameterTypes()[1];
                if (!owner.isInstance(activity) || !consumer.isInterface()) continue;
                callback = Proxy.newProxyInstance(consumer.getClassLoader(), new Class<?>[]{consumer}, (proxy, call, args) -> {
                    if (call.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (call.getName().equals("equals")) return proxy == args[0];
                    if (call.getName().equals("toString")) return "ViewerFoldListener";
                    if (call.getName().equals("accept") && args != null && args.length == 1) {
                        Rect hinge = null;
                        List<?> features = (List<?>) args[0].getClass().getMethod("getDisplayFeatures").invoke(args[0]);
                        Class<?> foldType = Class.forName("androidx.window.extensions.layout.FoldingFeature");
                        for (Object feature : features) {
                            if (!foldType.isInstance(feature)) continue;
                            int type = (Integer) foldType.getMethod("getType").invoke(feature), state = (Integer) foldType.getMethod("getState").invoke(feature);
                            if (type == 2 || state == 2) { hinge = new Rect((Rect) foldType.getMethod("getBounds").invoke(feature)); break; }
                        }
                        final Rect result = hinge; activity.runOnUiThread(() -> listener.onFold(result));
                    }
                    return null;
                });
                remove = api.getMethod("removeWindowLayoutInfoListener", consumer);
                method.invoke(component, activity, callback); return;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) { component = callback = null; }
    }
    void stop() {
        if (component != null && callback != null && remove != null) try { remove.invoke(component, callback); } catch (ReflectiveOperationException ignored) {}
        component = callback = null;
    }
}
