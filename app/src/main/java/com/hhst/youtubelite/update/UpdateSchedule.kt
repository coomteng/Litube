package com.hhst.youtubelite.update

import com.tencent.mmkv.MMKV

/**
 * Throttle and "skip this version" memory for the automatic update check. MMKV-backed;
 * the pure decision logic is parameterised on time and store so it can be unit tested.
 */
object UpdateSchedule {
    const val KEY_LAST_CHECK = "update:last_check"
    const val KEY_SKIPPED_TAG = "update:skipped_tag"
    const val KEY_LAST_PROMPT = "update:last_prompt"
    const val INTERVAL_MS = 24L * 60 * 60 * 1000

    private val kv: MMKV get() = MMKV.defaultMMKV()

    /** True when no check ran in the last [INTERVAL_MS]. */
    fun isDue(now: Long = System.currentTimeMillis(), lastCheck: Long = kv.decodeLong(KEY_LAST_CHECK, 0L)): Boolean =
        isDue(now, lastCheck, INTERVAL_MS)

    fun isDue(now: Long, lastCheck: Long, interval: Long): Boolean = lastCheck <= 0L || now - lastCheck >= interval || lastCheck > now

    /** Records a completed check (manual or automatic). */
    @Suppress("UNUSED_PARAMETER")
    fun noteChecked(result: UpdateCheck, now: Long = System.currentTimeMillis()) {
        kv.encode(KEY_LAST_CHECK, now)
    }

    fun skippedTag(): String? = kv.decodeString(KEY_SKIPPED_TAG)
    fun skip(tag: String) = kv.encode(KEY_SKIPPED_TAG, tag)

    /** "Later" defers the dialog for one interval without re-checking. */
    fun notePrompted(now: Long = System.currentTimeMillis()) = kv.encode(KEY_LAST_PROMPT, now)

    /**
     * Whether to show the dialog for [update]: not the skipped tag (a newer tag prompts again),
     * and not deferred with "Later" within the interval.
     */
    fun shouldPrompt(update: UpdateInfo, now: Long = System.currentTimeMillis()): Boolean =
        shouldPrompt(update.tag, skippedTag(), now, kv.decodeLong(KEY_LAST_PROMPT, 0L), INTERVAL_MS)

    fun shouldPrompt(tag: String, skippedTag: String?, now: Long, lastPrompt: Long, interval: Long): Boolean =
        tag != skippedTag && isDue(now, lastPrompt, interval)
}
