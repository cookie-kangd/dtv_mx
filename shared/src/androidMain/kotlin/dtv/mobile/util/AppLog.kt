package dtv.mobile.util

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object AppLog {
  private val lock = Any()
  @Volatile private var file: File? = null

  private sealed interface Cmd
  private class Line(val text: String) : Cmd
  private class Flush(val latch: CountDownLatch) : Cmd

  private val queue = LinkedBlockingQueue<Cmd>()
  @Volatile private var started = false

  fun init(context: Context) {
    if (file != null) return
    synchronized(lock) {
      if (file != null) return
      val dir = File(context.filesDir, "dtv-logs").apply { mkdirs() }
      val name = "dtv-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.log"
      file = File(dir, name)
      startWriterLocked(dir)
      Log.i("DTV-LOG", "log file: ${file!!.absolutePath}")
      appendRaw("I", "DTV-LOG", "log file: ${file!!.absolutePath}", null)
    }
  }

  /**
   * 日志改为「内存队列 + 唯一后台写线程批量落盘」：
   * 任何线程（含主线程、弹幕 WS 接收线程）打日志时只做一次入队，
   * 不再同步开关文件写盘——热路径的磁盘 IO 抖动（卡顿+耗电来源）被彻底移走。
   * 写线程以低优先级运行，批量攒够一次 open/write/close，空闲时零开销阻塞等待。
   */
  private fun startWriterLocked(dir: File) {
    if (started) return
    started = true
    Thread {
      runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
      // 旧日志清理挪到后台线程，启动路径零磁盘 IO
      runCatching { cleanupOldLogs(dir, keep = 10) }
      while (true) {
        try {
          val first = queue.take()
          val batch = ArrayList<Cmd>(32)
          batch.add(first)
          queue.drainTo(batch, 128)
          val sb = StringBuilder()
          var pendingFlush: CountDownLatch? = null
          for (c in batch) {
            when (c) {
              is Line -> sb.append(c.text)
              is Flush -> pendingFlush = c.latch
            }
          }
          if (sb.isNotEmpty()) {
            val f = file ?: continue
            runCatching { FileWriter(f, true).use { it.write(sb.toString()) } }
          }
          // flush 放行必须放在写盘完成之后，保证 flushSync 返回时日志已落盘
          pendingFlush?.countDown()
        } catch (_: InterruptedException) {
          return@Thread
        }
      }
    }.apply { name = "dtv-log-writer" }.start()
  }

  /** 阻塞直到队列内已产生的日志全部落盘（崩溃记录读取日志尾部前调用）。 */
  fun flushSync() {
    if (!started) return
    val latch = CountDownLatch(1)
    queue.offer(Flush(latch))
    runCatching { latch.await(3, TimeUnit.SECONDS) }
  }

  fun currentLogFilePath(): String? = file?.absolutePath

  fun d(tag: String, message: String) {
    Log.d(tag, message)
    appendRaw("D", tag, message, null)
  }

  fun i(tag: String, message: String) {
    Log.i(tag, message)
    appendRaw("I", tag, message, null)
  }

  fun w(tag: String, message: String, t: Throwable? = null) {
    if (t != null) Log.w(tag, message, t) else Log.w(tag, message)
    appendRaw("W", tag, message, t)
  }

  fun e(tag: String, message: String, t: Throwable? = null) {
    if (t != null) Log.e(tag, message, t) else Log.e(tag, message)
    appendRaw("E", tag, message, t)
  }

  private fun cleanupOldLogs(dir: File, keep: Int) {
    val logs = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }.orEmpty()
      .sortedByDescending { it.lastModified() }
    logs.drop(keep).forEach { runCatching { it.delete() } }
  }

  private fun appendRaw(level: String, tag: String, message: String, t: Throwable?) {
    val f = file ?: return
    val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    val line = buildString {
      append(now)
      append(" [")
      append(level)
      append("] ")
      append(tag)
      append(": ")
      append(message)
      if (t != null) {
        append('\n')
        append(t.stackTraceStringCompat())
      }
      append('\n')
    }

    queue.offer(Line(line))
  }
}

private fun Throwable.stackTraceStringCompat(): String {
  val sw = StringWriter()
  PrintWriter(sw).use { pw -> this.printStackTrace(pw) }
  return sw.toString()
}
