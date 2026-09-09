package dev.eigger.hassble.service

import android.content.Context
import android.os.Build
import android.os.Process
import dev.eigger.hassble.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 앱은 실행 로그를 메모리에만 들고 있어서(LiveEventLogger) 프로세스가 죽으면 같이 사라진다.
 * 크래시 원인을 알려면 PC에 물려 logcat/dropbox를 떠야 했다.
 *
 * 그래서 마지막 크래시의 스택을 파일 하나로 남기고, 다음 기동 때 읽어서 로그 탭에 올린다.
 * 로그 탭의 저장·공유·복사 버튼으로 그대로 내보낼 수 있다.
 */
object CrashReporter {
    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"
    private const val MAX_CHARS = 64 * 1024

    /**
     * 기본 UncaughtExceptionHandler를 감싼다. 기록 후에는 원래 핸들러로 넘겨
     * 시스템 크래시 처리(dropbox 기록 포함)를 그대로 태운다.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                // 기본 핸들러가 없으면 프로세스가 멈춘 채로 남을 수 있어 직접 내린다.
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /** 저장된 리포트를 읽고 파일은 지운다. 없으면 null. */
    fun consumeLast(context: Context): String? {
        val file = file(context.applicationContext)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        return text?.takeIf { it.isNotBlank() }
    }

    private fun file(context: Context): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, FILE)

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val text = buildString {
            appendLine("time: $stamp")
            appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(
                "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("thread: ${thread.name}")
            appendLine()
            append(error.stackTraceToString())
        }
        file(context).writeText(text.take(MAX_CHARS))
    }
}
