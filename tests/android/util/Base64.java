package android.util;
/** Test-only bridge for the JVM document tests; never packaged in the APK. */
public final class Base64 {
    public static final int DEFAULT = 0, NO_WRAP = 2;
    public static String encodeToString(byte[] value, int flags) { return java.util.Base64.getEncoder().encodeToString(value); }
    public static byte[] decode(String value, int flags) { return java.util.Base64.getMimeDecoder().decode(value); }
}
