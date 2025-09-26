package helium.util

import kotlin.reflect.KClass

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

  private fun checkGrow(targetSize: Int) {
    if (targetSize <= array.size) return
    array = array.copyOf(targetSize)
  }

  override fun isEmpty() = size == 0

  override fun contains(element: T): Boolean {
    val index = element.indexes[indexOrder]
    return !(index !in 0..<size || array[index] != element)
  }

  override fun iterator(): Iterator<T> = Itr()

  override fun containsAll(elements: Collection<T>): Boolean {
    elements.forEach { e ->
      if (!contains(e)) return false
    }

    return true
  }

  fun trimSerial(){
    array = array.copyOf(size)
  }

  fun add(obj: T) {
    checkGrow(size + 1)
    obj.indexes[indexOrder] = size
    array[size] = obj
    size++
  }

  fun insert(index: Int, obj: T) {
    checkIndex(index)
    checkGrow(size + 1)

    val inserted = array[index]!!
    array[size] = inserted
    inserted.indexes[indexOrder] = size
    array[index] = obj
    obj.indexes[indexOrder] = index
    size++
  }

  fun insertOrdered(index: Int, obj: T) {
    checkIndex(index)
    checkGrow(size + 1)

    for (i in index..<size) {
      array[i]?.indexes[indexOrder]++
    }
    System.arraycopy(array, index, array, index + 1, size - index)
    array[index] = obj
    obj.indexes[indexOrder] = index
    size++
  }

  fun remove(obj: T): T? {
    val index = obj.indexes[indexOrder]
    val removed = array[index]?:return null
    if (isEmpty() || index !in 0..<size || removed != obj) return null

    size--
    val tail = array[size]!!
    array[size] = null
    array[index] = tail
    tail.indexes[indexOrder] = index

    return removed
  }

  fun removeOrdered(obj: T): T? {
    val index = obj.indexes[indexOrder]
    val removed = array[index]?:return null
    if (isEmpty() || index !in 0..<size || removed != obj) return null

    for (i in index + 1..<size) {
      array[i]?.indexes[indexOrder]--
    }
    size--
    System.arraycopy(array, index + 1, array, index, size - index)
    array[size] = null

    return removed
  }

  fun removeIndex(index: Int): T? {
    checkIndex(index)
    val removed = array[index]?:return null

    size--
    val tail = array[size]!!
    array[size] = null
    array[index] = tail
    tail.indexes[indexOrder] = index

    return removed
  }

  fun removeIndexOrdered(index: Int): T? {
    checkIndex(index)
    val removed = array[index]?:return null

    for (i in index + 1..<size) {
      array[i]?.indexes[indexOrder]--
    }
    size--
    System.arraycopy(array, index + 1, array, index, size - index)
    array[size] = null

    return removed
  }

  operator fun get(index: Int): T {
    checkIndex(index)
    return array[index]!!
  }

  operator fun set(index: Int, obj: T) {
    checkIndex(index)
    array[index] = obj
    obj.indexes[indexOrder] = index
  }

  private fun checkIndex(index: Int) {
    if (index !in 0..<size)
      throw IndexOutOfBoundsException("Index $index is out of bounds. length: ${array.size}")
  }

  private inner class Itr(): MutableIterator<T>{
    var index = 0

    override fun next(): T {
      val res = array[index]
      index++
      return res!!
    }
    override fun hasNext() = index < size
    override fun remove() {
      removeIndex(index)
      index--
    }
  }

  override fun toString(): String {
    val builder = StringBuilder()
    builder.append("IndexedSerial{")
    forEach { obj -> builder.append(obj.toString()) }
    builder.append("}")

    return builder.toString()
  }

  fun clear() {
    size = 0
  }

  @Suppress("UNCHECKED_CAST")
  fun forceClear(){
    array = java.lang.reflect.Array.newInstance(
      elementType.java,
      defaultCapacity
    ) as Array<T?>
    size = 0
  }
}

interface SerialObject {
  /**Automatically update, DO NOT MODIFIER THIS MANUALLY!!!*/
  var indexes: IntArray
}