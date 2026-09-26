package helium.ui.fragments.entityinfo

import arc.func.Cons
import arc.math.geom.QuadTree
import arc.math.geom.Rect
import arc.scene.Element
import arc.scene.ui.layout.Table
import arc.struct.IntMap
import arc.struct.Seq
import arc.util.pooling.Pool
import mindustry.Vars
import mindustry.async.PhysicsProcess
import mindustry.entities.EntityGroup
import mindustry.entities.EntityIndexer
import mindustry.game.Team
import mindustry.gen.*
import mindustry.gen.Unit
import universe.util.reflect.accessField
import java.lang.reflect.Field

abstract class DisplayProvider<E, T: EntityInfoDisplay<E>>{
  open val hoveringOnly: Boolean get() = false

  abstract val typeID: Int
  abstract fun targetGroup(): Iterable<TargetGroup<*>>
  abstract fun valid(entity: Posc): Boolean
  abstract fun enabled(): Boolean
  abstract fun create(): T

  open fun initialize(display: T, entity: E, id: Int) {
    display.initialize(entity, id, this)
  }

  @Suppress("UNCHECKED_CAST")
  internal fun recycleAny(display: EntityInfoDisplay<*>) {
    val target = display as T
    if (target.pooled) return
    pool.free(target)
  }

  private val pool = object: Pool<T>(16, 512) {
    override fun newObject() = create()

    override fun reset(display: T) {
      display.recycle()
    }
  }

  open fun provide(entity: E, id: Int): T {
    val display = pool.obtain()
    initialize(display, entity, id)
    return display
  }

  abstract fun buildConfig(table: Table)
}

abstract class EntityInfoDisplay<E>{
  private var backingEntity: E? = null

  var entity: E
    get() = backingEntity ?: throw IllegalStateException("EntityInfoDisplay has been recycled")
    set(value) { backingEntity = value }

  var entityID: Int = -1
  var team: Team = Team.derelict
  var index: Int = -1

  internal var owner: DisplayProvider<*, *>? = null
  var pooled = false

  abstract val typeID: Int

  open fun initialize(entity: E, id: Int, owner: DisplayProvider<*, *>? = null) {
    backingEntity = entity
    entityID = id
    this.owner = owner
    team = Team.derelict
    index = -1
    pooled = false
  }

  open fun recycle() {
    backingEntity = null
    entityID = -1
    team = Team.derelict
    index = -1
    pooled = true
  }

  fun freeToPool() {
    if (pooled) return
    val provider = owner
    if (provider == null) {
      recycle()
      return
    }
    provider.recycleAny(this)
  }

  abstract val layoutSide: Side
  open val screenRender: Boolean get() = true
  open val worldRender: Boolean get() = false

  open val maxSizeMultiple: Int get() = 6
  open val minSizeMultiple: Int get() = 2

  open fun checkWorldClip(entity: Posc, worldViewport: Rect) = entity.let {
    val clipSize = when(it){
      is Drawc -> it.clipSize()
      is Building -> it.block.clipSize
      else -> 10f
    }
    worldViewport.overlaps(it.x - clipSize/2, it.y - clipSize/2, clipSize, clipSize)
  }
  open fun checkScreenClip(screenViewport: Rect, origX: Float, origY: Float, drawWidth: Float, drawHeight: Float) =
    screenViewport.overlaps(
      origX, origY,
      drawWidth, drawHeight
    )
  open fun drawWorld(alpha: Float){}

  abstract val prefWidth: Float
  abstract val prefHeight: Float
  open fun shouldDisplay() = true
  abstract fun realWidth(prefSize: Float): Float
  abstract fun realHeight(prefSize: Float): Float
  abstract fun update(delta: Float, alpha: Float, isHovering: Boolean, isHolding: Boolean)
  abstract fun draw(alpha: Float, scale: Float, origX: Float, origY: Float, drawWidth: Float, drawHeight: Float)
}

enum class Side(val dir: Int){
  CENTER(-1),
  RIGHT(0),
  TOP(1),
  LEFT(2),
  BOTTOM(3)
}

abstract class WorldDrawOnlyDisplay<E>: EntityInfoDisplay<E>() {
  override val layoutSide: Side get() = Side.CENTER
  override val prefWidth: Float get() = 0f
  override val prefHeight: Float get() = 0f
  override val worldRender: Boolean get() = true
  override val screenRender: Boolean get() = false
  override fun realWidth(prefSize: Float) = 0f
  override fun realHeight(prefSize: Float) = 0f
  override fun draw(alpha: Float, scale: Float, origX: Float, origY: Float, drawWidth: Float, drawHeight: Float) {}
  override fun drawWorld(alpha: Float) {
    draw(alpha)
  }
  abstract fun draw(alpha: Float)
}

interface InputEventChecker{
  var element: Element
  fun buildListener(): Element
  fun detachElement()
}

open class TargetGroup<T: Entityc>(private val target: Field) {
  @Suppress("UNCHECKED_CAST")
  private val origin = target.get(null) as EntityGroup<Entityc>

  private var wrapped: EntityGroup<Entityc>? = null
  private var lastPut: Cons<T>? = null
  private var lastRemove: Cons<T>? = null
  private var lastClear: Runnable? = null

  companion object {
    private val EntityGroup<Entityc>.array: Seq<Entityc> by accessField("array")
    private val EntityGroup<Entityc>.indexer: EntityIndexer? by accessField("indexer")
    private var EntityGroup<Entityc>.map: IntMap<Entityc>? by accessField("map")
    private var EntityGroup<Entityc>.tree: QuadTree<QuadTree.QuadTreeObject>? by accessField("tree")

    val all = TargetGroup<Entityc>(Groups::class.java.getField("all"))

    val build = TargetGroup<Building>(Groups::class.java.getField("build"))
    val bullet = TargetGroup<Bullet>(Groups::class.java.getField("bullet"))
    val draw = TargetGroup<Drawc>(Groups::class.java.getField("draw"))
    val player = TargetGroup<Player>(Groups::class.java.getField("player"))
    val powerGraph = TargetGroup<PowerGraphUpdaterc>(Groups::class.java.getField("powerGraph"))
    val sync = TargetGroup<Syncc>(Groups::class.java.getField("sync"))
    val unit = TargetGroup<Unit>(Groups::class.java.getField("unit"))
    val weather = TargetGroup<WeatherState>(Groups::class.java.getField("weather"))
  }

  @Suppress("UNCHECKED_CAST")
  open fun reset(){
    val curr = target.get(null) as EntityGroup<Entityc>
    if (curr === wrapped) return
    origin.array.clear()
    origin.array.addAll(curr.array)
    origin.map = curr.map
    origin.tree = curr.tree

    target.set(null, origin)
    lastPut?.also { apply(it, lastRemove!!, lastClear!!) }
  }

  fun isHooked(): Boolean = wrapped != null && target.get(null) === wrapped

  fun ensureHooked(): Boolean {
    if (isHooked()) return false
    val put = lastPut ?: return false
    apply(put, lastRemove!!, lastClear!!)
    return true
  }

  @Suppress("UNCHECKED_CAST")
  fun get() = target.get(null) as EntityGroup<T>

  @Suppress("UNCHECKED_CAST")
  open fun apply(put: Cons<T>, remove: Cons<T>, clear: Runnable){
    lastPut = put
    lastRemove = remove
    lastClear = clear

    val old = target.get(null) as EntityGroup<Entityc>
    val type = old.array.items.javaClass.componentType as Class<Entityc>
    val new = object: EntityGroup<Entityc>(type, false, false, old.indexer){
      override fun add(type: Entityc?) {
        super.add(type)
        put.get(type as T)
      }

      override fun remove(type: Entityc?) {
        super.remove(type)
        remove.get(type as T)
      }
      override fun removeIndex(type: Entityc?, position: Int) {
        val exact = position >= 0 && position < array.size && array.items[position] === type

        if (!exact) {
          if (type != null) {
            super.remove(type)
            remove.get(type as T)
          }
          return
        }

        super.removeIndex(type, position)
        remove.get(type as T)
      }

      override fun removeByID(id: Int) {
        val t = map?.get(id) as? T ?: return
        t.remove()
      }

      override fun clear() {
        super.clear()
        clear.run()
      }
    }

    new.array.addAll(old.array)
    new.map = old.map
    new.tree = old.tree

    target.set(null, new)
    wrapped = new
  }
}
