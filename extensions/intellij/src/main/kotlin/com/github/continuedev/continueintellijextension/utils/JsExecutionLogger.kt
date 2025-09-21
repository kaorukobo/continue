package com.github.continuedev.continueintellijextension.utils

import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * スレッドセーフなJavaScript実行ログユーティリティクラス
 * ContinueBrowserのメソッド呼び出しを非同期でログ出力する
 */
object JsExecutionLogger {
    private const val LOG_FILE_PATH = "/tmp/continue-execjs.log"
    private const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024 // 10MB
    private const val MAX_ARG_LENGTH = 1000 // 引数の最大文字数

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "JsExecutionLogger").apply { isDaemon = true }
    }
    private val isShutdown = AtomicBoolean(false)

    init {
        // シャットダウン時のクリーンアップ
        Runtime.getRuntime().addShutdownHook(Thread {
            shutdown()
        })
    }

    /**
     * メソッド呼び出しをログに記録
     * @param methodName 呼び出されたメソッド名
     * @param args メソッドの引数
     */
    fun logMethodCall(methodName: String, vararg args: Any?) {
        if (isShutdown.get()) return

        executor.submit {
            try {
                val caller = getCallerInfo()
                val timestamp = LocalDateTime.now().format(dateFormatter)
                val argsJson = formatArgs(*args)

                val logEntry = mapOf(
                    "timestamp" to timestamp,
                    "method" to "com.github.continuedev.continueintellijextension.browser.ContinueBrowser#$methodName",
                    "caller" to caller,
                    "args" to argsJson
                )

                val logLine = gson.toJson(logEntry)
                writeToLogFile(logLine)
            } catch (e: Exception) {
                // ログ出力でエラーが発生しても、元の処理に影響しないようにする
                System.err.println("JsExecutionLogger error: ${e.message}")
            }
        }
    }

    /**
     * 呼び出し元の情報を取得
     */
    private fun getCallerInfo(): String {
        val stackTrace = Thread.currentThread().stackTrace
        // 0: getStackTrace, 1: getCallerInfo, 2: logMethodCall, 3: actual caller
        for (i in 3 until stackTrace.size) {
            val element = stackTrace[i]
            if (!element.className.contains("JsExecutionLogger") &&
                !element.className.contains("kotlin.") &&
                !element.className.contains("java.")) {
                return "${element.className}#${element.methodName}:${element.lineNumber}"
            }
        }
        return "Unknown"
    }

    /**
     * 引数を適切にフォーマット
     */
    private fun formatArgs(vararg args: Any?): List<String> {
        return args.map { arg ->
            when (arg) {
                null -> "null"
                is String -> {
                    if (arg.length > MAX_ARG_LENGTH) {
                        "${arg.substring(0, MAX_ARG_LENGTH)}...[truncated]"
                    } else {
                        arg
                    }
                }
                else -> {
                    val jsonStr = try {
                        gson.toJson(arg)
                    } catch (e: Exception) {
                        arg.toString()
                    }
                    if (jsonStr.length > MAX_ARG_LENGTH) {
                        "${jsonStr.substring(0, MAX_ARG_LENGTH)}...[truncated]"
                    } else {
                        jsonStr
                    }
                }
            }
        }
    }

    /**
     * ログファイルに書き込み（ローテーション機能付き）
     */
    private fun writeToLogFile(logLine: String) {
        val logFile = File(LOG_FILE_PATH)

        // ディレクトリが存在しない場合は作成
        logFile.parentFile?.mkdirs()

        // ファイルサイズチェックとローテーション
        if (logFile.exists() && logFile.length() > MAX_LOG_FILE_SIZE) {
            rotateLogFile(logFile)
        }

        try {
            FileWriter(logFile, true).use { writer ->
                writer.appendLine(logLine)
                writer.flush()
            }
        } catch (e: IOException) {
            System.err.println("Failed to write to log file: ${e.message}")
        }
    }

    /**
     * ログファイルをローテーション
     */
    private fun rotateLogFile(logFile: File) {
        try {
            val backupFile = File("${logFile.path}.old")
            if (backupFile.exists()) {
                backupFile.delete()
            }
            logFile.renameTo(backupFile)
        } catch (e: Exception) {
            System.err.println("Failed to rotate log file: ${e.message}")
        }
    }

    /**
     * ログシステムをシャットダウン
     */
    fun shutdown() {
        if (!isShutdown.compareAndSet(false, true)) {
            return
        }

        try {
            executor.shutdown()
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}