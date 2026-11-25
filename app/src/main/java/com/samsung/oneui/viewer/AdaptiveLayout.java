package com.samsung.oneui.viewer;

/** Window-width policy, independent of Android so boundary cases can be checked on the JVM. */
public final class AdaptiveLayout {
    private AdaptiveLayout() {}
    public static boolean tablet(float width, String preference) {
        return !"mobile".equals(preference) && ("tablet".equals(preference) ? width >= 600 : width >= 840);
    }
    public static boolean dockBoth(float width) { return width >= 1150; }
    public static int drawerWidth(float width) { return Math.max(200, Math.min(320, (int)width - 48)); }
    public static boolean book(float width, float left, float right, float height) {
        return height > 0 && left >= 200 && width - right >= 320;
    }
}
