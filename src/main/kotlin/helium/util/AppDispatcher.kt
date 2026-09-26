package helium.util

import arc.Core
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

private object PostDispatcherImpl : CoroutineDispatcher() {
  override fun dispatch(context: CoroutineContext, block: Runnable) {
    Core.app.post(block)
  }

  override fun toString(): String = "Dispatchers.App"
}

/**主线程调度器，该调度器将协程分配到主线程的[arc.Application.post]中进行工作，通常用于进行那些需要与主线程同步的操作。*/
val Dispatchers.Post: CoroutineDispatcher get() = PostDispatcherImpl
