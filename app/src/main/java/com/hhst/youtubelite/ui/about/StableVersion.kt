package com.hhst.youtubelite.ui.about

/** Stable releases compare numeric components; development builds keep their own channel. */
object StableVersion {
    fun isDevelopment(version: String) = version.contains(Regex("(?i)(dev|alpha|beta|rc|snapshot)"))
    fun isNewer(current: String, latest: String): Boolean {
        // Four components: upstream a.b.c plus this fork's .n revision (e.g. 3.0.3.1).
        fun numbers(value: String) = Regex("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").find(value)?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: 0 }
        val old = numbers(current) ?: return false
        val next = numbers(latest) ?: return false
        return next.zip(old).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
    }
}
