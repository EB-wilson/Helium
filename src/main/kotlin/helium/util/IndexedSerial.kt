package helium.util

import kotlin.reflect.KClass

/**
 * 零分配的索引序列表。
 *
 * - 遍历：直接读 [array] + [size]（不要用 `iterator()`，它每次会分配一个 Itr，`forEach` 同理）
 * - 增 / 删 / 查：O(1)，无装箱、无 lambda、无临时对象；仅在缓存失效时退化为 O(n) 扫描并自愈
 * - 扩容：1.5 倍增长（旧实现是每次 `copyOf(size + 1)`，即每次 add 都分配一次并整体拷贝）
 *
 * 位置信息由容器写进 [SerialObject.indexes]，同时用 [SerialObject.tags] 记录一个"代际戳"：
 * 只有 `tags[order] == 本容器 tag` **且** `array[index] === 元素` 时缓存才被采信，否则线性扫描并修复缓存。
 * 于是缓存陈旧只会变慢，不会算错：既不会静默丢元素，也不会越界或误删。
 * 换句话说，**将来任何新增代码路径漏掉失效处理都不会污染正确性**，这正是旧实现最致命的隐患。
 */
class IndexedSerial<T: SerialObject>(
  private val elementType: KClass<*> = SerialObject::class,
  private val defaultCapacity: Int = 16,
  private val indexOrder: Int = 0,
): Collection<T> {
  @Suppress("UNCHECKED_CAST")
  var array = java.lang.reflect.Array.newInstance(
    elementType.java,
    defaultCapacity
  ) as Array<T?>
    private set

  override var size: Int = 0
    private set

  /**本容器的代际戳：clear/forceClear 后自增，使全部旧缓存立即失效*/
  private var tag: Int = nextTag()

  /**代际戳只读出口，供调试/测试校验缓存有效性（tag == 0 表示"已失效"）*/
  val currentTag: Int get() = tag

  /**1.5 倍扩容，避免"每个元素一次分配 + O(n²) 拷贝"*/
  private fun checkGrow(targetSize: Int) {
    if (targetSize <= array.size) return
    var capacity = array.size
    while (capacity < targetSize) capacity = if (capacity < 8) 8 else capacity + (capacity shr 1)
    array = array.copyOf(capacity)
  }

  override fun isEmpty() = size == 0

  // ------------------------------------------------------------------
  // 索引写入：只允许本节的函数改动 SerialObject.indexes / tags
  // ------------------------------------------------------------------

  private fun invalidate(obj: T) {
    obj.indexes[indexOrder] = -1
    obj.tags[indexOrder] = 0
  }

  /**交换删除：把尾元素填到 index。O(1)，零分配*/
  private fun swapRemove(index: Int): T {
    val removed = array[index]!!
    size--
    if (index != size) {
      val tail = array[size]!!
      array[size] = null            //断开引用，否则被删元素会被 array 一直持有
      array[index] = tail
      tail.indexes[indexOrder] = index
      tail.tags[indexOrder] = tag
    }
    else {
      array[index] = null
    }
    invalidate(removed)
    return removed
  }

  /**保序删除：index 之后的元素整体前移。O(n)，零分配*/
  private fun orderedRemove(index: Int): T {
    val removed = array[index]!!
    var i = index + 1
    while (i < size) {
      val e = array[i]!!
      array[i - 1] = e
      e.indexes[indexOrder] = i - 1
      e.tags[indexOrder] = tag
      i++
    }
    size--
    array[size] = null
    invalidate(removed)
    return removed
  }

  /**
   * 定位元素：快路径 O(1)（校验缓存与代际戳），缓存不可信时 O(n) 扫描并修复缓存。
   * 全程零分配。
   */
  private fun indexOf(obj: T): Int {
    val index = obj.indexes[indexOrder]
    if (index < 0) return -1                                              //初值或已删除：明确不在列表里
    if (obj.tags[indexOrder] == tag && index < size && array[index] === obj) return index

    var i = 0
    while (i < size) {
      if (array[i] === obj) {
        obj.indexes[indexOrder] = i
        obj.tags[indexOrder] = tag
        return i
      }
      i++
    }

    invalidate(obj)
    return -1
  }

  // ------------------------------------------------------------------
  // 查询
  // ------------------------------------------------------------------

  /**注意：本容器是按引用（===）判定的槽位容器，不参与 equals 语义*/
  override fun contains(element: T) = indexOf(element) >= 0

  override fun containsAll(elements: Collection<T>): Boolean {
    elements.forEach { e ->
      if (!contains(e)) return false
    }

    return true
  }

  operator fun get(index: Int): T {
    checkIndex(index)
    return array[index]!!
  }

  // ------------------------------------------------------------------
  // 增 / 删
  // ------------------------------------------------------------------

  fun add(obj: T): Boolean {
    if (indexOf(obj) >= 0) return false     //不再拿裸缓存当"是否已在列表里"的判据

    checkGrow(size + 1)
    array[size] = obj
    obj.indexes[indexOrder] = size
    obj.tags[indexOrder] = tag
    size++

    return true
  }

  fun remove(obj: T): Boolean {
    val index = indexOf(obj)
    if (index < 0) return false

    swapRemove(index)
    return true
  }

  fun removeIndex(index: Int): T? {
    checkIndex(index)
    return swapRemove(index)
  }

  fun removeOrdered(obj: T): T? {
    val index = indexOf(obj)
    if (index < 0) return null

    return orderedRemove(index)
  }

  fun removeIndexOrdered(index: Int): T? {
    checkIndex(index)
    return orderedRemove(index)
  }

  /**把 obj 放到 index，原来的 index 元素挪到末尾*/
  fun insert(index: Int, obj: T) {
    checkIndex(index)
    if (array[index] === obj) return

    remove(obj)                             //已在别处时先摘掉，避免产生重复项
    if (index >= size) {                    //摘除把 index 处掏空了：退化为追加
      checkGrow(size + 1)
      array[size] = obj
      obj.indexes[indexOrder] = size
      obj.tags[indexOrder] = tag
      size++
      return
    }

    checkGrow(size + 1)
    val occupant = array[index]!!
    array[size] = occupant
    occupant.indexes[indexOrder] = size
    occupant.tags[indexOrder] = tag
    array[index] = obj
    obj.indexes[indexOrder] = index
    obj.tags[indexOrder] = tag
    size++
  }

  /**插入并保持其余元素的相对顺序*/
  fun insertOrdered(index: Int, obj: T) {
    checkIndex(index)
    if (array[index] === obj) return

    remove(obj)
    checkGrow(size + 1)

    var i = size
    while (i > index) {
      val e = array[i - 1]!!
      array[i] = e
      e.indexes[indexOrder] = i
      e.tags[indexOrder] = tag
      i--
    }

    array[index] = obj
    obj.indexes[indexOrder] = index
    obj.tags[indexOrder] = tag
    size++
  }

  /**替换 index 处元素；obj 必须不在本列表中，否则抛异常（宁可炸掉也不要静默产生重复项）*/
  operator fun set(index: Int, obj: T) {
    checkIndex(index)
    val old = array[index]!!
    if (old === obj) return

    val duplicate = indexOf(obj)
    require(duplicate < 0) { "obj is already in this IndexedSerial at $duplicate" }

    invalidate(old)
    array[index] = obj
    obj.indexes[indexOrder] = index
    obj.tags[indexOrder] = tag
  }

  // ------------------------------------------------------------------
  // 清空 / 维护
  // ------------------------------------------------------------------

  /**
   * O(n) 但零分配：显式作废每个元素的索引（这样之后复用同一批对象时 add 仍是 O(1)），
   * 并自增代际戳作为双保险（即使将来有路径漏掉作废，旧缓存也会被 tag 挡下）。
   */
  fun clear() {
    var i = 0
    while (i < size) {
      val e = array[i]!!
      e.indexes[indexOrder] = -1
      e.tags[indexOrder] = 0
      array[i] = null                       //断开引用
      i++
    }
    size = 0
    tag = nextTag()
  }

  @Suppress("UNCHECKED_CAST")
  fun forceClear(){
    clear()
    array = java.lang.reflect.Array.newInstance(
      elementType.java,
      defaultCapacity
    ) as Array<T?>
  }

  /**把容量收到与 size 一致。注意：这会分配一次，且之后不应再依赖旧的缓存索引（本类已不依赖）*/
  fun trimSerial(){
    array = array.copyOf(size)
  }

  /**仅用于测试/调试：O(n) 校验内部索引不变量，正常运行路径不要调用*/
  fun checkInvariant(): Boolean {
    var i = 0
    while (i < size) {
      val e = array[i] ?: return false
      if (e.indexes[indexOrder] != i || e.tags[indexOrder] != tag) return false
      i++
    }
    return true
  }

  private fun checkIndex(index: Int) {
    if (index !in 0..<size)
      throw IndexOutOfBoundsException("Index $index is out of bounds. length: ${array.size}")
  }

  override fun toString(): String {
    val builder = StringBuilder()
    builder.append("IndexedSerial{")
    var i = 0
    while (i < size) {
      builder.append(array[i].toString())
      i++
    }
    builder.append("}")

    return builder.toString()
  }

  override fun iterator(): MutableIterator<T> = Itr()

  /**
   * 每次 `iterator()` 都会分配一个 Itr；热路径请直接用 `array` + `size` 遍历。
   * 注意 [remove] 是交换删除、不保序：删除后游标会退回被删位置，以便继续访问被换进来的尾元素。
   */
  private inner class Itr(): MutableIterator<T>{
    private var cursor = 0
    private var last = -1

    override fun hasNext() = cursor < size

    override fun next(): T {
      if (cursor >= size) throw NoSuchElementException()
      last = cursor
      return array[cursor++]!!
    }

    override fun remove() {
      if (last < 0) throw IllegalStateException("next() has not been called, or remove() was already called")
      swapRemove(last)
      cursor = last
      last = -1
    }
  }

  companion object {
    private var lastTag = 0

    /**容器实例的代际戳，0 保留给"已失效"*/
    private fun nextTag(): Int {
      lastTag++
      if (lastTag == 0) lastTag = 1
      return lastTag
    }
  }
}

interface SerialObject {
  /**索引槽位（每个 IndexedSerial 占一个槽）：-1 表示不在该列表中。由容器维护，切勿手动修改*/
  var indexes: IntArray
  /**代际戳槽位，与 [indexes] 一一对应。由容器维护，切勿手动修改*/
  var tags: IntArray
}
