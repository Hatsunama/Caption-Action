package android.content

import java.io.File

/** Only the filesystem boundary. The production recorder and maintained tests are compiled intact. */
open class Context {
    open val filesDir: File get() = error("Not configured")
    open fun getExternalFilesDir(type: String?): File? = error("Not configured")
}
open class ContextWrapper(base: Context?) : Context()
