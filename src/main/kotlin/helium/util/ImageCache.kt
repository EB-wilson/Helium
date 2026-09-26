package helium.util

import arc.Core
import arc.func.Cons
import arc.graphics.g2d.TextureRegion
import arc.scene.style.Drawable
import arc.scene.style.TextureRegionDrawable
import arc.struct.ObjectMap

class ImageCache {
  private val lock = Any()
  private val cache = ObjectMap<String, Drawable>()

  val size: Int get() = synchronized(lock) { cache.size }

  fun resolve(
    url: String,
    def: TextureRegion = Core.atlas.find("nomap")
  ): Drawable = synchronized(lock) {
    cache.get(url) ?: Downloader.downloadLazyDrawable(
      url,
      def,
      errHandler = { evict(url) }
    ).also { cache.put(url, it) }
  }

  fun invalidate(url: String) {
    synchronized(lock) { cache.remove(url) }
  }

  fun clear() {
    synchronized(lock) { cache.clear() }
  }

  private fun evict(url: String) {
    synchronized(lock) {
      cache.remove(url)
    }
  }
}
