package helium.util

import arc.Core
import arc.files.Fi
import arc.func.Cons
import arc.graphics.Pixmap
import arc.graphics.Texture
import arc.graphics.g2d.TextureRegion
import arc.scene.style.Drawable
import arc.scene.style.TextureRegionDrawable
import arc.struct.OrderedMap
import arc.util.Http
import arc.util.Log
import arc.util.io.Streams.OptimizedByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.math.max

object Downloader {
  const val MAX_RETRY: Int = 5

  var debugInfo = false

  private const val RETRY_DELAY_MS: Long = 250L
  private const val BUFFER_SIZE: Int = 8192

  private val scope: CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO + CoroutineName("helium-downloader")
  )

  private val urlReplacers = OrderedMap<String, String>()

  /**
   * 镜像表的不可变快照。
   *
   * Arc 的 `ObjectMap` 迭代器是**共享对象**：多个下载协程同时在 `for (entry in map)` 上迭代时，
   * 第二个会抛 `ArcRuntimeException: #iterator() cannot be used nested.`，把该次下载直接打死
   * （实测日志里每个图标请求都在报这个）。所以读侧只走快照，写侧重建快照。
   */
  @Volatile
  private var mirrors: List<Pair<String, String>> = emptyList()

  private fun rebuildMirrors() {
    synchronized(urlReplacers) {
      mirrors = urlReplacers.entries().map { it.key to it.value }
    }
  }

  fun setMirror(source: String, to: String) {
    urlReplacers.put(source, to)
    rebuildMirrors()
  }

  fun removeMirror(source: String) {
    urlReplacers.remove(source)
    rebuildMirrors()
  }

  fun clearMirrors() {
    urlReplacers.clear()
    rebuildMirrors()
  }

  private fun mirrored(url: String): String {
    var result = url

    for ((from, to) in mirrors) {
      if (from.isNotEmpty() && result.startsWith(from)) result = result.replaceFirst(from.toRegex(), to)
    }

    return result
  }

  private suspend fun <T> request(url: String, maxRetry: Int, handler: (Http.HttpResponse) -> T): T {
    val realUrl = mirrored(url)
    var last: Throwable? = null

    for (attempt in 0..maxRetry) {
      try {
        return getRequest(realUrl, handler)
      } catch (e: CancellationException) {
        throw e
      } catch (e: InterruptedException) {
        throw e
      } catch (e: Throwable) {
        last = e
        if (attempt < maxRetry) delay(RETRY_DELAY_MS * (attempt + 1))
      }
    }

    throw last ?: IllegalStateException("download failed: $realUrl")
  }

  private suspend fun <T> getRequest(url: String, handler: (Http.HttpResponse) -> T): T = withContext(Dispatchers.IO) {
    var result: T? = null
    var failure: Throwable? = null

    Http.get(url).error { failure = it }.block { result = handler(it) }

    failure?.let { throw it }

    result ?: throw IllegalStateException("request returned no response: $url")
  }

  private fun copyBody(res: Http.HttpResponse, out: OutputStream, progressBack: Cons<Float>?, job: Job?) {
    val input = res.resultAsStream ?: return
    val total = res.contentLength
    val buffer = ByteArray(BUFFER_SIZE)

    var curr = 0L
    while (true) {
      job?.ensureActive()
      if (Thread.currentThread().isInterrupted) throw InterruptedException()

      val read = input.read(buffer)
      if (read == -1) break

      out.write(buffer, 0, read)
      curr += read
      progressBack?.get(curr.toFloat()/total)
    }
  }

  private suspend fun <T> onAppThread(action: () -> T) = suspendCancellableCoroutine { cont ->
    Core.app.post {
      cont.resumeWith(runCatching(action))
    }
  }

  private fun launchDownload(
    errHandler: Cons<Throwable>?,
    block: suspend CoroutineScope.() -> Unit
  ): Job = scope.launch {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      if (debugInfo) Log.err(e)
      errHandler?.get(e)
    }
  }

  suspend fun downloadToStream(
    url: String,
    stream: OutputStream,
    progressBack: Cons<Float>? = null,
    maxRetry: Int = MAX_RETRY,
  ) {
    val job = currentCoroutineContext()[Job]

    stream.use { out ->
      request(url, maxRetry) { res ->
        copyBody(res, out, progressBack, job)
      }
    }
  }

  suspend fun downloadToFile(
    url: String,
    file: Fi,
    progressBack: Cons<Float>? = null,
    maxRetry: Int = MAX_RETRY,
  ): Unit = downloadToStream(url, file.write(), progressBack, maxRetry)

  suspend fun downloadImg(
    url: String,
    errDef: TextureRegion,
    progressBack: Cons<Float>? = null,
    maxRetry: Int = MAX_RETRY,
    completed: Cons<TextureRegion>? = null,
  ): TextureRegion {
    val result = TextureRegion(errDef)

    downloadInto(url, result, progressBack, maxRetry, completed)

    return result
  }

  private suspend fun downloadInto(
    url: String,
    result: TextureRegion,
    progressBack: Cons<Float>?,
    maxRetry: Int,
    completed: Cons<TextureRegion>?,
  ) {
    val job = currentCoroutineContext()[Job]

    val bytes = request(url, maxRetry) { res ->
      val out = OptimizedByteArrayOutputStream(max(0, res.contentLength).toInt())

      out.use { copyBody(res, it, progressBack, job) }

      out.toByteArray()
    }

    val pix = Pixmap(bytes)

    onAppThread {
      try {
        val tex = Texture(pix)
        tex.setFilter(Texture.TextureFilter.linear)
        result.set(tex)

        completed?.get(result)
      } finally {
        pix.dispose()
      }
    }
  }

  fun launchDownloadToStream(
    url: String,
    stream: OutputStream,
    progressBack: Cons<Float>? = null,
    errHandler: Cons<Throwable>? = null,
    completed: Runnable? = null,
  ): Job = launchDownload(errHandler) {
    downloadToStream(url, stream, progressBack)
    completed?.run()
  }

  fun launchDownloadToFile(
    url: String,
    file: Fi,
    progressBack: Cons<Float>? = null,
    errHandler: Cons<Throwable>? = null,
    completed: Runnable? = null,
  ): Job = launchDownload(errHandler) {
    downloadToFile(url, file, progressBack)
    completed?.run()
  }

  fun launchDownloadImg(
    url: String,
    errDef: TextureRegion,
    progressBack: Cons<Float>? = null,
    errHandler: Cons<Throwable>? = null,
    completed: Cons<TextureRegion>? = null,
  ): TextureRegion {
    val result = TextureRegion(errDef)

    startImgDownload(url, result, progressBack, errHandler, completed)

    return result
  }

  private fun startImgDownload(
    url: String,
    result: TextureRegion,
    progressBack: Cons<Float>?,
    errHandler: Cons<Throwable>?,
    completed: Cons<TextureRegion>?,
  ): Job = launchDownload(errHandler) {
    downloadInto(url, result, progressBack, MAX_RETRY, completed)
  }

  fun downloadLazyImg(
    url: String,
    errDef: TextureRegion,
    progressBack: Cons<Float>? = null,
    errHandler: Cons<Throwable>? = null,
    completed: Cons<TextureRegion>? = null,
  ): LazyRegionProv {
    val result = TextureRegion(errDef)

    return LazyRegionProv(result) {
      startImgDownload(url, result, progressBack, errHandler, completed)
    }
  }

  fun downloadLazyDrawable(
    url: String,
    errDef: TextureRegion,
    progressBack: Cons<Float>? = null,
    errHandler: Cons<Throwable>? = null,
    completed: Cons<TextureRegion>? = null,
  ): Drawable {
    val prov = downloadLazyImg(url, errDef, progressBack, errHandler, completed)

    return object: TextureRegionDrawable(prov.region){
      override fun draw(x: Float, y: Float, width: Float, height: Float) {
        prov.init()
        super.draw(x, y, width, height)
      }
    }
  }
}

data class LazyRegionProv(
  val region: TextureRegion,
  val downloader: () -> Job,
) {
  private var job: Job? = null

  val started: Boolean get() = job != null

  fun init() {
    if (job != null) return

    job = downloader()
  }

  fun cancel() {
    job?.cancel()
  }
}
