package helium.util

import arc.Core
import arc.files.Fi
import arc.func.Cons
import arc.func.ConsT
import arc.graphics.Pixmap
import arc.graphics.Texture
import arc.graphics.g2d.TextureRegion
import arc.scene.style.Drawable
import arc.scene.style.TextureRegionDrawable
import arc.struct.OrderedMap
import arc.util.Http
import arc.util.Log
import arc.util.io.Streams.OptimizedByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max

object Downloader {
  private val urlReplacers = OrderedMap<String, String>()

  fun setMirror(source: String, to: String) {
    urlReplacers.put(source, to)
  }

  fun removeMirror(source: String) {
    urlReplacers.remove(source)
  }

  fun clearMirrors() {
    urlReplacers.clear()
  }

  private fun retryDown(
    url: String,
    sync: Boolean,
    resultHandler: ConsT<Http.HttpResponse, Exception>,
    maxRetry: Int,
    errHandler: Cons<Throwable>,
  ) {
    var url = url
    var counter = 0
    var get = {}

    for (entry in urlReplacers) {
      if (url.startsWith(entry.key!!)) {
        url = url.replaceFirst(entry.key!!.toRegex(), entry.value!!)
      }
    }

    val realUrl = url
    get = if (sync) {{
      Http.get(realUrl).error{ e ->
        if (counter++ > maxRetry || e is InterruptedException) errHandler.get(e)
        else get()
      }.block(resultHandler)
    }}
    else {{
      Http.get(realUrl, resultHandler){ e ->
        if (counter++ > maxRetry || e is InterruptedException) errHandler.get(e)
        else get()
      }
    }}
    get()
  }

  fun downloadToStream(
    url: String,
    stream: OutputStream,
    sync: Boolean = false,
    progressBack: Cons<Float>? = null,
    errHandler: Cons<Throwable>? = null,
    completed: Runnable? = null
  ) {
    retryDown(url, sync, { res ->
      stream.use {
        val input = res.resultAsStream
        val total = res.contentLength

        var curr = 0
        var b = input.read()
        while (b != -1) {
          if (Thread.interrupted())
            throw InterruptedException()

          curr++
          stream.write(b)
          progressBack?.get(curr.toFloat()/total)
          b = input.read()
        }

        completed?.run()
      }
    }, 5, { th -> errHandler?.get(th) })
  }

  fun downloadToFile(
    url: String,
    file: Fi,
    sync: Boolean = false,
    progressBack: Cons<Float>? = null,
    errHandler: Cons<Throwable>? = null,
    completed: Runnable? = null
  ) {
    downloadToStream(url, file.write(), sync, progressBack, errHandler, completed)
  }

  fun downloadImg(
    url: String,
    errDef: TextureRegion,
    sync: Boolean = false,
    progressBack: Cons<Float>? = null,
    errHandler: Cons<Throwable>? = null,
    completed: Cons<TextureRegion>? = null,
  ): TextureRegion {
    val result = TextureRegion(errDef)

    doDownloadImg(url, sync, progressBack, result, completed, errHandler)

    return result
  }

  fun downloadLazyImg(
    url: String,
    errDef: TextureRegion,
    sync: Boolean = false,
    progressBack: Cons<Float>? = null,
    errHandler: Cons<Throwable>? = null,
    completed: Cons<TextureRegion>? = null,
  ): LazyRegionProv {
    val result = TextureRegion(errDef)

    return LazyRegionProv(result) {
      doDownloadImg(url, sync, progressBack, result, completed, errHandler)
    }
  }

  fun downloadLazyDrawable(
    url: String,
    errDef: TextureRegion,
    sync: Boolean = false,
    progressBack: Cons<Float>? = null,
    errHandler: Cons<Throwable>? = null,
    completed: Cons<TextureRegion>? = null,
  ): Drawable {
    val prov = downloadLazyImg(url, errDef, sync, progressBack, errHandler, completed)
    return object: TextureRegionDrawable(prov.region){
      override fun draw(x: Float, y: Float, width: Float, height: Float) {
        prov.init()
        super.draw(x, y, width, height)
      }
    }
  }

  private fun doDownloadImg(
    url: String,
    sync: Boolean,
    progressBack: Cons<Float>?,
    result: TextureRegion,
    completed: Cons<TextureRegion>?,
    errHandler: Cons<Throwable>?,
  ) {
    retryDown(url, sync, { res ->
      val input = res.resultAsStream
      val total = res.contentLength
      val out = OptimizedByteArrayOutputStream(max(0, total).toInt())

      out.use { stream ->
        var curr = 0
        var b = input.read()
        while (b != -1) {
          curr++
          stream.write(b)
          progressBack?.get(curr.toFloat()/total)
          b = input.read()
        }
      }

      val pix = Pixmap(out.toByteArray())
      Core.app.post {
        try {
          val tex = Texture(pix)
          tex.setFilter(Texture.TextureFilter.linear)
          result.set(tex)
          pix.dispose()

          completed?.get(result)
        } catch (e: Exception) {
          Log.err(e)
        }
      }
    }, 5, { th -> errHandler?.get(th) })
  }
}

data class LazyRegionProv(
  val region: TextureRegion,
  val downloader: Runnable
){
  private var done = false

  fun init(){
    if (done) return
    downloader.run()
    done = true
  }
}
