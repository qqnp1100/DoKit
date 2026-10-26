package com.didichuxing.doraemonkit.kit.filemanager

import com.didichuxing.doraemonkit.util.FileUtils
import com.didichuxing.doraemonkit.util.PathUtils

/**
 * ================================================
 * 作    者：jint（金台）
 * 版    本：1.0
 * 创建日期：2020/6/29-17:10
 * 描    述：
 * 修订历史：
 * ================================================
 */
object FileManagerUtil {
    /**
     * 根目录匹配字段
     */
    const val ROOT_PATH_STR = "/root/"

    private val internalAppRootPath by lazy { PathUtils.getInternalAppDataPath() }
    private val internalAppRootReplacePath by lazy { FileUtils.getFileName(PathUtils.getInternalAppDataPath()) }
    public val externalStorageRootPath by lazy { PathUtils.getExternalStoragePath() }
    private val externalStorageRootReplacePath by lazy { "external" }


    /**
     * 输出相对路径
     */
    fun relativeRootPath(path: String?): String {
        path?.let {
            val canonical = java.io.File(it).canonicalPath
            val aliases = mapOf(
                java.io.File(internalAppRootPath).canonicalPath to "$ROOT_PATH_STR$internalAppRootReplacePath",
                java.io.File(externalStorageRootPath).canonicalPath to "$ROOT_PATH_STR$externalStorageRootReplacePath"
            )
            aliases.entries.firstOrNull {
                canonical == it.key || canonical.startsWith(it.key + java.io.File.separator)
            }?.let { alias ->
                return alias.value + canonical.removePrefix(alias.key)
            }
            return it
        }

        return ""
    }

    /**
     * 合并绝对路径
     */
    fun securePath(path: String): String = DoKitFileScope.resolve(path,
        arrayOf(internalAppRootPath, externalStorageRootPath))

    fun childPath(dir: String, name: String?): String {
        require(!name.isNullOrBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')) { "Invalid file name" }
        return securePath(java.io.File(dir, name!!).path)
    }

    fun absoluteRootPath(path: String?): String {
        if (path.isNullOrBlank()) return ""
        if (path == ROOT_PATH_STR) return ROOT_PATH_STR
        val aliases = mapOf("$ROOT_PATH_STR$internalAppRootReplacePath" to internalAppRootPath,
            "$ROOT_PATH_STR$externalStorageRootReplacePath" to externalStorageRootPath)
        val match = aliases.entries.firstOrNull { path == it.key || path.startsWith(it.key + "/") }
        val mapped = if (match == null) path else match.value + path.removePrefix(match.key)
        val resolved = securePath(mapped)
        return if (resolved == externalStorageRootPath) resolved else resolved + java.io.File.separator
    }
}
