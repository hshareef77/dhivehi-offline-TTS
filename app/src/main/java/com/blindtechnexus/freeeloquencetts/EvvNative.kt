package com.blindtechnexus.freeeloquencetts

/**
 * ABI of the user-supplied Free Eloquence JNI bridge.
 * Names/package deliberately preserved: native symbols are
 * Java_com_blindtechnexus_freeeloquencetts_EvvNative_*.
 *
 * Native libevvjni.so is included only in explicit native builds.
 */
object EvvNative {
    val loaded: Boolean
    val loadFailure: String?
    init {
        var ok = false
        var error: String? = null
        try {
            System.loadLibrary("evvjni")
            ok = true
        } catch (e: UnsatisfiedLinkError) {
            error = e.message
        } catch (e: Throwable) {
            error = e.message
        }
        loaded = ok
        loadFailure = error
    }
    external fun create(language: Int): Long
    external fun destroy(handle: Long)
    external fun speak(handle: Long, text: ByteArray): Boolean
    /** >0 bytes, 0 utterance end, -1 stopped, -2 timeout. */
    external fun read(handle: Long, dst: ByteArray, timeoutMs: Int): Int
    external fun stop(handle: Long)
    external fun setParam(handle: Long, param: Int, value: Int): Int
    external fun setVoiceParam(handle: Long, voice: Int, param: Int, value: Int): Int
    external fun copyVoice(handle: Long, from: Int, to: Int): Int
    external fun version(): String
}
