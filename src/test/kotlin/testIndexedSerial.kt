import helium.util.IndexedSerial
import helium.util.SerialObject
import helium.ui.fragments.entityinfo.EntityEntry
import helium.ui.fragments.entityinfo.displays.AttackAngleDisplayProvider
import arc.util.pooling.Pool
import mindustry.game.Team
import mindustry.gen.Teamc
import mindustry.logic.Ranged

/**
 * IndexedSerial 的不变量与历史故障回归测试。
 * 项目没有引入 JUnit（test 依赖只有 arc/mindustry core），所以用零依赖的 main() + expect()。
 * 运行：编译 test 源码后 `java -cp <classes:kotlin-stdlib> TestIndexedSerialKt`
 */
class Obj(val n: Int): SerialObject {
  //槽位数量 = 用到的 IndexedSerial 个数；-1 表示"不在该列表中"
  override var indexes = intArrayOf(-1, -1)
  override var tags = intArrayOf(0, 0)

  override fun equals(other: Any?) = other is Obj && other.n == n
  override fun hashCode() = n

  override fun toString() = "(n:$n; idx:${indexes[0]})"
}

private var failures = 0

private fun expect(cond: Boolean, msg: String) {
  if (cond) println("[ ok ] $msg")
  else {
    failures++
    println("[FAIL] $msg")
  }
}

private fun newList(order: Int) = IndexedSerial<Obj>(elementType = Obj::class, indexOrder = order)

fun main() {
  basic()
  tailReAdd()
  staleIndexSelfHeal()
  reuseAfterClear()
  shrinkCapacity()
  iteratorRemove()
  equalsIsNotIdentity()
  independentSlots()
  pooledEntryContract()
  pooledDisplayContract()

  if (failures > 0) throw AssertionError("$failures check(s) failed")
  println("== all checks passed ==")
}

private fun basic() {
  val list = newList(0)
  val objs = Array(11) { Obj(it) }
  for (o in objs) list.add(o)

  expect(list.size == 11, "add: size == 11")
  expect(list.checkInvariant(), "add: index invariant holds")
  expect(list[4].n == 4, "get(4) == 4")

  expect(list.remove(objs[4]), "remove: middle element")
  expect(list.remove(objs[10]), "remove: tail element")
  expect(list.size == 9, "remove: size == 9")
  expect(list.checkInvariant(), "remove: index invariant holds")
  expect(!list.contains(objs[4]), "remove: removed element is gone")
  expect(list[4] === objs[9], "remove: swap-remove moved the tail into the hole")
}

/** 旧实现：删尾元素后它的索引仍等于 size，`add` 拿这个陈旧索引当"已存在"判据 → 静默拒绝加入 */
private fun tailReAdd() {
  val list = newList(0)
  val a = Obj(1)
  val b = Obj(2)
  list.add(a)
  list.add(b)

  expect(list.remove(b), "tail re-add: remove tail first")
  expect(list.add(b), "tail re-add: adding the same instance again succeeds")
  expect(list.size == 2 && list[1] === b, "tail re-add: instance is back at index 1")
  expect(list.checkInvariant(), "tail re-add: index invariant holds")
}

/** 旧实现：缓存一旦被污染（越界/指向别处）就静默算错，甚至越界崩溃 */
private fun staleIndexSelfHeal() {
  val list = newList(0)
  val a = Obj(1)
  val b = Obj(2)
  val c = Obj(3)
  list.add(a)
  list.add(b)
  list.add(c)

  c.indexes[0] = 99
  expect(list.contains(c), "stale index: contains survives a bogus index")
  expect(c.indexes[0] == 2, "stale index: cache self-healed to 2")
  expect(list.checkInvariant(), "stale index: index invariant holds")

  b.tags[0] = 0
  expect(list.contains(b), "stale index: contains survives a wrong generation tag")
  expect(b.tags[0] == list.currentTag, "stale index: tag repaired")
}

/** 旧实现：clear() 不作废索引，之后复用同一批对象时会 O(n) 误判甚至丢元素 */
private fun reuseAfterClear() {
  val list = newList(0)
  val objs = Array(64) { Obj(it) }
  for (o in objs) list.add(o)

  list.clear()
  expect(list.size == 0, "clear: size == 0")

  for (o in objs) list.add(o)
  expect(list.size == 64, "clear: all instances can be re-added")
  expect(list.checkInvariant(), "clear: index invariant holds")
  expect(list.contains(objs[63]), "clear: last element is findable")
}

/** 旧实现：trimSerial 缩小容量后，陈旧索引会让 array[index] 越界抛 AIOOBE */
private fun shrinkCapacity() {
  val list = newList(0)
  val objs = Array(40) { Obj(it) }
  for (o in objs) list.add(o)

  val removed = objs[39]
  expect(list.remove(removed), "shrink: remove tail")
  list.trimSerial()

  expect(!list.contains(removed), "shrink: contains on a removed instance does not throw")
  expect(!list.remove(removed), "shrink: remove on a removed instance does not throw")

  removed.indexes[0] = 39
  removed.tags[0] = list.currentTag
  expect(!list.contains(removed), "shrink: out-of-capacity index does not throw")

  expect(list.remove(objs[0]), "shrink: valid elements are still removable")
  expect(list.checkInvariant(), "shrink: index invariant holds")
}

/** 旧实现 Itr：next() 已自增游标，remove() 却按当前游标删 → 删掉的是"下一个"元素，删末尾还会抛异常 */
private fun iteratorRemove() {
  val list = newList(0)
  val a = Obj(1)
  val b = Obj(2)
  val c = Obj(3)
  list.add(a)
  list.add(b)
  list.add(c)

  val it = list.iterator()
  expect(it.next() === a, "iterator: next() == a")
  it.remove()
  expect(!list.contains(a), "iterator: removed the element last returned by next()")
  expect(list.contains(b) && list.contains(c), "iterator: other elements untouched")
  expect(list.size == 2 && list.checkInvariant(), "iterator: index invariant holds")

  expect(it.next() === c, "iterator: continues with the swapped-in tail")
  expect(it.next() === b, "iterator: visits the remaining element")
  expect(!it.hasNext(), "iterator: exhausted")

  var threw = false
  try {
    it.remove()
    it.remove()
  }
  catch (e: IllegalStateException) {
    threw = true
  }
  expect(threw, "iterator: second remove() without next() throws IllegalStateException")
}

/** 容器是身份（===）容器：equals 相等不会让它误判成员关系 */
private fun equalsIsNotIdentity() {
  val list = newList(0)
  val a = Obj(7)
  list.add(a)

  val twin = Obj(7)
  expect(a == twin, "identity: twins are equals")
  expect(!list.contains(twin), "identity: equals-equal twin is not a member")
  expect(list.add(twin), "identity: equals-equal twin can be added")
  expect(list.size == 2, "identity: both instances present")
  expect(list.remove(twin) && list.contains(a), "identity: removing the twin keeps the original")
}

/** 一个对象可以同时存在于多个列表，各自占用一个槽位，互不干扰 */
private fun independentSlots() {
  val l0 = newList(0)
  val l1 = newList(1)
  val a = Obj(1)
  val b = Obj(2)
  l0.add(a)
  l0.add(b)
  l1.add(b)
  l1.add(a)

  expect(a.indexes[0] == 0 && a.indexes[1] == 1, "slots: independent positions")
  expect(l0.remove(a) && l1.contains(a), "slots: removing from one list leaves the other intact")
  expect(l0.size == 1 && l1.size == 2, "slots: independent sizes")
  expect(l0.checkInvariant() && l1.checkInvariant(), "slots: invariants hold")
}

/**
 * EntityEntry 池化契约。arc 的 Pool.obtain 只弹实例、不重置，所以：
 * 取出后必须 initialize，归还时必须经 reset -> recycle 断开实体引用。
 * 旧写法 `poolObtain { EntityEntry(entity, hovering) }` 缺的正是这两步（复用实例会带着上一个实体）。
 */
private fun pooledEntryContract() {
  val pool = object: Pool<EntityEntry>(2, 8) {
    override fun newObject() = EntityEntry()
    override fun reset(entry: EntityEntry) {
      entry.recycle()
    }
  }

  val first = teamc(1)
  val second = teamc(2)

  val e = pool.obtain()
  e.initialize(first, true)
  expect(e.entity === first && e.isHovering, "pool: initialize binds entity and kind")

  e.indexes[0] = 5
  e.tags[0] = 123
  e.holding = true
  pool.free(e)

  expect(e.pooled, "pool: free marks the entry as pooled")
  expect(!e.holding && e.displays.size == 0, "pool: free clears per-frame state")
  expect(e.indexes[0] == -1 && e.tags[0] == 0, "pool: free resets index slots")

  var threw = false
  try {
    e.entity
  }
  catch (ex: IllegalStateException) {
    threw = true
  }
  expect(threw, "pool: recycled entry no longer exposes the old entity")

  val again = pool.obtain()
  expect(again === e, "pool: the same instance is reused")
  again.initialize(second, false)
  expect(again.entity === second && !again.isHovering, "pool: re-initialize replaces the previous entity")
  expect(again.indexes[0] == -1 && !again.pooled, "pool: re-initialize resets slots and pooled flag")
}

/**
 * display 池化契约：provider 自带池 + create/initialize/recycle。
 * 这里用真实的 AttackAngleDisplayProvider 端到端跑一遍，而不是只测底层 Pool。
 */
private fun pooledDisplayContract() {
  val provider = AttackAngleDisplayProvider()

  val first = ranged(11)
  val d1 = provider.provide(first, 11)

  expect(d1.entity === first && d1.entityID == 11, "display: provide binds entity and id")
  expect(d1.team == Team.derelict && !d1.pooled, "display: provide resets team and pooled flag")

  d1.isUnit = true
  d1.isTurret = true
  d1.team = Team.crux
  d1.freeToPool()

  expect(d1.pooled, "display: freeToPool marks the display as pooled")

  var threw = false
  try {
    d1.entity
  }
  catch (ex: IllegalStateException) {
    threw = true
  }
  expect(threw, "display: recycled display no longer exposes the old entity")

  val second = ranged(22)
  val d2 = provider.provide(second, 22)

  expect(d2 === d1, "display: the same instance is reused")
  expect(d2.entity === second && d2.entityID == 22, "display: reuse rebinds entity and id")
  expect(!d2.isUnit && !d2.isTurret, "display: per-entity flags are reset on reuse")
  expect(d2.team == Team.derelict, "display: team is reset on reuse")

  d2.freeToPool()
  d2.freeToPool()
  expect(d2.pooled, "display: double freeToPool is a no-op")
}

/** 最小 Teamc 替身：只需 id() 可用（EntityEntry.hashCode 会用到） */
private fun teamc(id: Int): Teamc = java.lang.reflect.Proxy.newProxyInstance(
  Teamc::class.java.classLoader,
  arrayOf(Teamc::class.java)
) { _, method, _ ->
  when (method.name) {
    "id" -> id
    "hashCode" -> id
    "toString" -> "Teamc#$id"
    "equals" -> false
    else -> null
  }
} as Teamc

/** 最小 Ranged 替身：provider 只对它做类型判断，不会调用它的方法 */
private fun ranged(id: Int): Ranged = java.lang.reflect.Proxy.newProxyInstance(
  Ranged::class.java.classLoader,
  arrayOf(Ranged::class.java)
) { _, method, _ ->
  when (method.name) {
    "id" -> id
    "hashCode" -> id
    "toString" -> "Ranged#$id"
    "equals" -> false
    else -> null
  }
} as Ranged
