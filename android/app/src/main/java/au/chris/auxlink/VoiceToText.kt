package au.chris.auxlink

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast

/**
 * Voice → text for notification replies. Asks for RECORD_AUDIO the same way
 * Setup does (prompt once, then app settings if denied permanently).
 */
object VoiceToText {
    private val asked = mutableSetOf<String>()
    private var pending: (() -> Unit)? = null
    private var recognizer: SpeechRecognizer? = null

    fun micAllowed(act: Activity): Boolean =
        act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Ask for mic if needed, then run [then]. */
    fun ensureMic(act: Activity, then: () -> Unit) {
        if (micAllowed(act)) {
            then()
            return
        }
        val perm = Manifest.permission.RECORD_AUDIO
        pending = then
        if (act.shouldShowRequestPermissionRationale(perm) || perm !in asked) {
            asked += perm
            act.requestPermissions(arrayOf(perm), REQ_MIC)
        } else {
            act.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${act.packageName}")
                )
            )
        }
    }

    /** Call from Activity.onRequestPermissionsResult. */
    fun onPermissionResult(
        act: Activity,
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode != REQ_MIC) return
        val ok = permissions.indices.any {
            permissions[it] == Manifest.permission.RECORD_AUDIO &&
                grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED
        }
        val cb = pending
        pending = null
        if (ok) cb?.invoke()
        else Toast.makeText(act, "Microphone permission is needed for voice reply.", Toast.LENGTH_SHORT).show()
    }

    /**
     * Start listening; [onText] gets the best transcript.
     * Stops any previous session. Call [stop] from onDestroy.
     */
    fun listen(act: Activity, onText: (String) -> Unit, onState: (listening: Boolean) -> Unit = {}) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            Toast.makeText(act, "Voice input isn't available on this device.", Toast.LENGTH_SHORT).show()
            return
        }
        ensureMic(act) {
            val sr = SpeechRecognizer.createSpeechRecognizer(act)
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { onState(true) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { onState(false) }
                override fun onError(error: Int) {
                    onState(false)
                    stop()
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission is needed for voice reply."
                        else -> "Voice input failed."
                    }
                    Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
                }
                override fun onResults(results: Bundle?) {
                    onState(false)
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    stop()
                    if (text.isNotEmpty()) onText(text)
                    else Toast.makeText(act, "Didn't catch that — try again.", Toast.LENGTH_SHORT).show()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (text.isNotEmpty()) onText(text)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your reply")
            }
            try {
                onState(true)
                sr.startListening(intent)
            } catch (e: Exception) {
                onState(false)
                stop()
                Toast.makeText(act, "Voice input failed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stop() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    const val REQ_MIC = 7101
}
