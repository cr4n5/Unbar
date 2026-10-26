package de.robv.android.xposed;

public final class XposedHelpers {
    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static Object getObjectField(Object object, String fieldName) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static Object callMethod(Object object, String methodName, Object... args) {
        throw new UnsupportedOperationException("compile-time stub");
    }
}
