package helium.ui.fragments.entityinfo

import arc.func.Cons
import arc.math.geom.QuadTree
import arc.math.geom.Rect
import arc.scene.Element
import arc.scene.ui.layout.Table
import arc.struct.IntMap
import arc.struct.Seq
import mindustry.Vars
import mindustry.async.PhysicsProcess
import mindustry.entities.EntityGroup
import mindustry.entities.EntityIndexer
import mindustry.game.Team
import mindustry.gen.*
import mindustry.gen.Unit
import universe.util.reflect.Reflection.accessField
import java.lang.reflect.Field

abstract class DisplayProvider<E, T: EntityInfoDisplay<E>>{
  abstract val typeID: Int
  abstract fun targetGroup(): Iterable<TargetGroup<*>>
  abstract fun valid(entity: Posc): Boolean
  abstract fun enabled(): Boolean
  abstract fun provide(entity: E, id: Int): T
  open val hoveringOnly: Boolean get() = false

  abstract fun buildConfig(table: Table)
}

@Suppress("UNCHECKED_CAST")
abstract class EntityInfoDisplay<E>(
  val entity: E,
  val id: Int
){
  var team: Team = Team.derelict
  var index: Int = -1

  abstract val typeID: Int

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

abstract class WorldDrawOnlyDisplay<E>(
  entity: E,
  id: Int
): EntityInfoDisplay<E>(entity, id) {
  override val layoutSide: Side get() = Side.CENTER
  override val prefWidth: Float get() = 0f
  override val prefHeight: Float get() = 0f
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
}

open class TargetGroup<T: Entityc>(private val target: Field) {
  @Suppress("UNCHECKED_CAST")
  private val origin = target.get(null) as EntityGroup<Entityc>

  companion object {
    private val EntityGroup<Entityc>.array: Seq<Entityc> by accessField("array")
    private val EntityGroup<Entityc>.indexer: EntityIndexer? by accessField("indexer")
    private var EntityGroup<Entityc>.map: IntMap<Entityc>? by accessField("map")
    private var EntityGroup<Entityc>.tree: QuadTree<QuadTree.QuadTreeObject>? by accessField("tree")
    private var PhysicsProcess.group: EntityGroup<Unit> by accessField("group")

    val all = TargetGroup<Entityc>(Groups::class.java.getField("all"))

    val build = TargetGroup<Building>(Groups::class.java.getField("build"))
    val bullet = TargetGroup<Bullet>(Groups::class.java.getField("bullet"))
    val draw = TargetGroup<Drawc>(Groups::class.java.getField("draw"))
    val fire = TargetGroup<Fire>(Groups::class.java.getField("fire"))
    val label = TargetGroup<WorldLabel>(Groups::class.java.getField("label"))
    val player = TargetGroup<Player>(Groups::class.java.getField("player"))
    val powerGraph = TargetGroup<PowerGraphUpdaterc>(Groups::class.java.getField("powerGraph"))
    val puddle = TargetGroup<Puddle>(Groups::class.java.getField("puddle"))
    val sync = TargetGroup<Syncc>(Groups::class.java.getField("sync"))
    val unit = object: TargetGroup<Unit>(Groups::class.java.getField("unit")){
      override fun reset() {
        super.reset()
        Vars.asyncCore.processes
          .filterIsInstance<PhysicsProcess>()
          .first().group = Groups.unit
      }
      override fun apply(put: Cons<Unit>, remove: Cons<Unit>, clear: Runnable) {
        super.apply(put, remove, clear)
        Vars.asyncCore.processes
          .filterIsInstance<PhysicsProcess>()
          .first().group = Groups.unit
      }
    }
    val weather = TargetGroup<WeatherState>(Groups::class.java.getField("weather"))
  }

  @Suppress("UNCHECKED_CAST")
  open fun reset(){
    val curr = target.get(null) as EntityGroup<Entityc>
    if (curr == origin) return
    origin.array.clear()
    origin.array.addAll(curr.array)

    target.set(null, origin)
  }

  @Suppress("UNCHECKED_CAST")
  fun get() = target.get(null) as EntityGroup<T>

  @Suppress("UNCHECKED_CAST")
  open fun apply(put: Cons<T>, remove: Cons<T>, clear: Runnable){
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
        val rm = array.items[position] === type
        super.removeIndex(type, position)
        if (rm) remove.get(type as T)
      }

      override fun removeByID(id: Int) {
        val t = map?.get(id) as? T
        super.removeByID(id)
        if (t != null) remove.get(t)
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
  }
}
