package android.util
object Log {
    @JvmStatic fun e(tag: String, message: String): Int = 0
    @JvmStatic fun e(tag: String, message: String, error: Throwable): Int = 0
}
