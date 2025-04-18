package helium.ui.fragments.entityinfo

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.graphics.g2d.Lines
import arc.math.Mathf
import arc.math.geom.Geometry
import arc.math.geom.Rect
import arc.math.geom.Vec2
import arc.scene.Group
import arc.struct.ObjectSet
import arc.struct.OrderedSet
import arc.struct.Seq
import arc.util.Time
import arc.util.Tmp
import arc.util.pooling.Pool.Poolable
import arc.util.pooling.Pools
import helium.He.config
import helium.ui.fragments.entityinfo.Side.*
import helium.util.MutablePair
import helium.util.mto
import mindustry.Vars
import mindustry.gen.*
import mindustry.gen.Unit
import mindustry.graphics.Drawf
import mindustry.graphics.Layer
import mindustry.graphics.Pal
import mindustry.input.Binding
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class EntityInfoFrag {
  companion object {
    private val tempEntry = EntityEntry()
  }

  private val displays = Seq<EntityInfoDisplay<Model<*>>>()

  private val all = ObjectSet<EntityEntry>()
  private val displayQueue = Seq<EntityEntry>()

  private val hovering = OrderedSet<EntityEntry>()
  private val currHov = OrderedSet<EntityEntry>()

  private var selecting = false
  private val selectStart = Vec2()
  private val selectionRect = Rect()

  private val worldViewport = Rect()
  private val screenViewport = Rect()

  private lateinit var infoFill: Group

  /**for hot-update*/
  fun displaySetupUpdated(){
    all.forEach { Pools.free(it) }
    displayQueue.clear()
    all.clear()
  }

  @Suppress("UNCHECKED_CAST")
  fun addDisplay(display: EntityInfoDisplay<*>) {
    displays.add(display as EntityInfoDisplay<Model<*>>)
    displaySetupUpdated()
  }

  @Suppress("UNCHECKED_CAST")
  fun addDisplayBefore(display: EntityInfoDisplay<*>, target: EntityInfoDisplay<*>){
    val index = displays.indexOf(target)
    display as EntityInfoDisplay<Model<*>>
    if (index == -1) displays.add(display)
    else displays.insert(index, display)
    displaySetupUpdated()
  }

  @Suppress("UNCHECKED_CAST")
  fun addDisplayAfter(display: EntityInfoDisplay<*>, target: EntityInfoDisplay<*>){
    val index = displays.indexOf(target)
    display as EntityInfoDisplay<Model<*>>
    if (index == -1 || index == displays.size - 1) displays.add(display)
    else displays.insert(index + 1, display)
    displaySetupUpdated()
  }

  @Suppress("UNCHECKED_CAST")
  fun replaceDisplay(display: EntityInfoDisplay<*>, target: EntityInfoDisplay<*>) {
    val index = displays.indexOf(target)
    display as EntityInfoDisplay<Model<*>>
    if (index != -1) displays[index] = display
    displaySetupUpdated()
  }

  @Suppress("UNCHECKED_CAST")
  fun removeDisplay(display: EntityInfoDisplay<*>) {
    displays.remove(display as EntityInfoDisplay<Model<*>>)
    displaySetupUpdated()
  }

  fun build(parent: Group){
    infoFill = object: Group(){
      override fun draw() {
        if (!config.enableEntityInfoDisplay) return
        drawHUDLay()
        super.draw()
      }

      override fun act(delta: Float) {
        if (!config.enableEntityInfoDisplay){
          if (all.any()) reset()
          return
        }

        super.act(delta)

        updateShowing()

        this@EntityInfoFrag.also {
          update(delta*60)
        }
      }
    }

    parent.addChildAt(0, infoFill)
    infoFill.fillParent = true
  }

  fun reset(){
    all.forEach { Pools.free(it) }
    all.clear()
    displayQueue.clear()
  }

  fun drawWorld(){
    if (!config.enableEntityInfoDisplay) return

    val alpha = config.entityInfoAlpha

    Core.camera.bounds(worldViewport)

    worldViewport.also {
      val bl = it.getPosition(Tmp.v1)
      val tr = Tmp.v2.set(it.x + it.width, it.y + it.height)
      Core.camera.project(bl)
      Core.camera.project(tr)
      tr.sub(bl)
      screenViewport.set(bl.x, bl.y, tr.x, tr.y)
    }

    val rect = selectionRect
    if (selecting && rect.width > 0 && rect.height > 0) {
      Draw.z(Layer.overlayUI)
      Draw.color(Pal.accent, 0.3f)
      Fill.rect(rect)
    }

    if (Core.input.keyDown(config.entityInfoHotKey)) {
      currHov.forEach { e ->
        val rad = e.size*1.44f + Mathf.absin(4f, 2f)
        val origX = e.entity.x
        val origY = e.entity.y

        Draw.z(Layer.overlayUI)
        Drawf.poly(
          origX, origY, 4,
          rad, 0f,
          if (hovering.contains(e)) Color.crimson else Pal.accent
        )
      }
    }

    displayQueue.forEach { e ->
      if (hovering.contains(e)) {
        val rad = e.size*1.44f
        val ent = e.entity
        val origX = e.entity.x
        val origY = e.entity.y

        if (ent is Unit) {
          Draw.z(if (ent.isFlying) Layer.plans else Layer.groundUnit - 1f)
          Fill.lightInner(
            origX, origY, 4,
            rad*0.7f,
            rad,
            0f,
            Tmp.c3.set(Pal.accent).a(0f),
            Tmp.c2.set(Pal.accent).a(0.7f)
          )
        }

        Draw.z(Layer.overlayUI)
        Lines.stroke(1f, Pal.accent)
        Lines.poly(
          origX, origY, 4,
          rad, 0f
        )
      }

      e.display.forEach r@{ entry ->
        val display = entry.first
        val model = entry.second?:return@r

        if (!display.worldRender) return@r

        display.apply {
          if (
            (hoveringOnly && !model.checkHovering(checkIsHovering(e)))
            || !model.shouldDisplay()
            || !model.checkWorldClip(worldViewport)
          ) return@r
          model.drawWorld(alpha*e.alpha)
        }
      }
    }
  }

  private fun updateShowing() {
    val mouse = Core.input.mouseWorld()
    val hotkeyDown = Core.input.keyDown(config.entityInfoHotKey)
    if (hotkeyDown) {
      if (!selecting && Core.input.keyDown(Binding.select)) {
        selecting = true
        selectStart.set(mouse)
        selectionRect.set(mouse.x, mouse.y, 0f, 0f)
      }
      else if (selecting) {
        val beginX = min(selectStart.x, mouse.x)
        val beginY = min(selectStart.y, mouse.y)
        val width = abs(selectStart.x - mouse.x)
        val height = abs(selectStart.y - mouse.y)
        selectionRect.set(beginX, beginY, width, height)
      }
      else {
        selectionRect.set(mouse.x, mouse.y, 0f, 0f)
      }
    }

    currHov.clear()

    Groups.all.forEach { entity ->
      if (entity !is Posc) return@forEach

      val e = tempEntry
      e.entity = entity

      val ent = all.get(e)
      if (ent == null) {
        val entry = Pools.obtain(EntityEntry::class.java){ EntityEntry() }
        entry.entity = entity

        assignDisplayModels(entry)

        entry.isValid = !entry.display.isEmpty
        entry.showing = true
        all.add(entry)
        if (entry.isValid) {
          displayQueue.add(entry)

          if (checkHovering(entity)) {
            currHov.add(entry)
          }
        }
      }
      else {
        ent.showing = true

        if (ent.isValid && checkHovering(entity)) {
          currHov.add(ent)
        }
      }
    }

    if (selecting && !(hotkeyDown && Core.input.keyDown(Binding.select))){
      if (currHov.isEmpty) hovering.clear()
      else {
        var anyAdded = currHov.size > hovering.size
        currHov.forEach { hov ->
          anyAdded = hovering.add(hov) or anyAdded
        }

        if (!anyAdded) currHov.forEach { hovering.remove(it) }
      }

      selecting = false
      selectStart.setZero()
      selectionRect.set(0f, 0f, 0f, 0f)
    }

    val itr = all.iterator()
    while (itr.hasNext()) {
      val entry = itr.next()

      if (!entry.entity.isAdded) hovering.remove(entry)

      if (!entry.showing && !hovering.contains(entry))
        entry.alpha = Mathf.approach(entry.alpha, 0f, 0.025f*Time.delta)
      else entry.alpha = 1f

      entry.showing = false

      if (entry.alpha <= 0.001f) {
        itr.remove()
        displayQueue.remove(entry)
        Pools.free(entry)
      }
    }

    if (!displayQueue.isEmpty) displayQueue
      .sort { e -> if (hovering.contains(e)) -1f else e.entity.dst2(Core.input.mouseWorld()) }
  }

  @Suppress("UNCHECKED_CAST")
  private fun assignDisplayModels(entry: EntityEntry) {
    val entity = entry.entity

    displays.forEach { display ->
      if (display.valid(entity) && display.enabled()) {
        entry.display.add(
          display mto
          if (display.hoveringOnly) null
          else {
            val model = (display as EntityInfoDisplay<*>).obtainModel(entity)
            if (display is InputEventChecker<*>) {
              display as InputEventChecker<InputCheckerModel<*>>
              model as InputCheckerModel<*>
              model.element = display.run { model.buildListener().also { infoFill.addChild(it) } }
            }
            model
          }
        )
      }
    }
  }

  private fun checkHovering(entity: Posc): Boolean {
    val rect = selectionRect

    return when(entity){
      is Hitboxc -> rect.overlaps(Tmp.r1.also { entity.hitbox(it) })
      is Building -> {
        val block = entity.block
        val size = (block.size*Vars.tilesize).toFloat()
        return rect.overlaps(
          entity.x - size/2, entity.y - size/2,
          size, size
        )
      }
      else -> {
        return rect.overlaps(
          entity.x - 5, entity.y - 5,
          10f, 10f
        )
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun update(delta: Float) {
    for (i in displayQueue.size - 1 downTo 0) {
      val e = displayQueue[i]

      e.display.forEach r@{
        it.first.apply {
          if (!it.second.checkHovering(checkIsHovering(e)) && hoveringOnly) return@r
          if (it.second == null){
            val model = obtainModel(e.entity)
            if (this is InputEventChecker<*>){
              this as InputEventChecker<InputCheckerModel<*>>
              model as InputCheckerModel
              model.element = model.buildListener().also { elem -> infoFill.addChild(elem) }
            }
            it.second = model
          }
          it.second!!.update(delta)
        }
      }
    }
  }

  private fun drawHUDLay() {
    val scale = config.entityInfoScale
    val alpha = config.entityInfoAlpha
    val maxSizeMul = 6
    val minSizeMul = 2

    Draw.sort(true)
    for (i in displayQueue.size - 1 downTo 0) {
      val e = displayQueue[i]

      var offsetLeft = 0f
      var offsetRight = 0f
      var offsetTop = 0f
      var offsetBottom = 0f

      var topWidth = 0f
      var bottomWidth = 0f
      var leftHeight = 0f
      var rightHeight = 0f
      var centerWith = 0f
      var centerHeight = 0f

      val origin = Core.camera.project(e.entity.x, e.entity.y)
      val origX = origin.x
      val origY = origin.y

      val a = e.alpha*alpha
      e.display.forEach f@{ entry ->
        val display = entry.first
        if (!display.screenRender) return@f
        val model = entry.second?: return@f

        display.apply {
          if (
            (hoveringOnly && !model.checkHovering(checkIsHovering(e)))
            || !model.shouldDisplay()
          ) return@f
          when (display.layoutSide) {
            CENTER -> {
              centerWith = max(model.prefWidth, centerWith)
              centerHeight = max(model.prefHeight, centerHeight)
            }
            RIGHT -> rightHeight = max(model.prefHeight, rightHeight)
            TOP -> topWidth = max(model.prefWidth, topWidth)
            LEFT -> leftHeight = max(model.prefHeight, leftHeight)
            BOTTOM -> bottomWidth = max(model.prefWidth, bottomWidth)
          }
        }
      }

      val sizeOrig = Core.camera.project(0f, 0f).x
      val sizeOff = Core.camera.project(e.size, 0f).x - sizeOrig

      e.display.forEach f@{ entry ->
        val display = entry.first
        val model = entry.second?: return@f

        val dir = if(display.layoutSide == CENTER) Tmp.p1.set(0, 0) else Geometry.d4(display.layoutSide.dir)
        val offset = Tmp.v1.set(sizeOff*dir.x, sizeOff*dir.y)
        val ox = origX + offset.x
        val oy = origY + offset.y

        display.apply {
          if (
            (hoveringOnly && !model.checkHovering(checkIsHovering(e)))
            || !model.shouldDisplay()
          ) return@f
          when (layoutSide) {
            CENTER -> {
              val disW = centerWith*scale
              val disH = centerHeight*scale
              if (model.checkScreenClip(screenViewport, ox - disW/2, oy - disH/2, disW, disH))
                model.draw(a, scale, ox - disW/2, oy - disH/2, disW, disH)
            }
            RIGHT -> {
              val w = model.realWidth(rightHeight)
              val h = model.realHeight(rightHeight)
              val scl = min(max(maxSizeMul*sizeOff/h, minSizeMul*sizeOff/h*scale), scale)
              val disW = w*scl
              val disH = h*scl
              if (model.checkScreenClip(screenViewport, ox + offsetRight, oy - disH/2, disW, disH))
                model.draw(a, scl, ox + offsetRight, oy - disH/2, disW, disH)

              offsetRight += disW
            }
            TOP -> {
              val w = model.realWidth(topWidth)
              val h = model.realHeight(topWidth)
              val scl = min(max(maxSizeMul*sizeOff/w, minSizeMul*sizeOff/w*scale), scale)
              val disW = w*scl
              val disH = h*scl
              if (model.checkScreenClip(screenViewport, ox - disW/2, oy + offsetTop, disW, disH))
                model.draw(a, scl, ox - disW/2, oy + offsetTop, disW, disH)

              offsetTop += disH
            }
            LEFT -> {
              val w = model.realWidth(leftHeight)
              val h = model.realHeight(leftHeight)
              val scl = min(max(maxSizeMul*sizeOff/h, minSizeMul*sizeOff/h*scale), scale)
              val disW = w*scl
              val disH = h*scl
              if (model.checkScreenClip(screenViewport, ox - disW - offsetLeft, oy - disH/2, disW, disH))
                model.draw(a, scl, ox - disW - offsetLeft, oy - disH/2, disW, disH)

              offsetLeft += disW
            }
            BOTTOM -> {
              val w = model.realWidth(bottomWidth)
              val h = model.realHeight(bottomWidth)
              val scl = min(max(maxSizeMul*sizeOff/w, minSizeMul*sizeOff/w*scale), scale)
              val disW = w*scl
              val disH = h*scl
              if (model.checkScreenClip(screenViewport, ox - disW/2, oy - disH - offsetBottom, disW, disH))
                model.draw(a, scl, ox - disW/2, oy - disH - offsetBottom, disW, disH)

              offsetBottom += disH
            }
          }
        }
      }
    }
    Draw.flush()
    Draw.sort(false)
  }

  private fun checkIsHovering(e: EntityEntry) =
    hovering.contains(e) || (Core.input.keyDown(config.entityInfoHotKey) && currHov.contains(e))
}

class EntityEntry : Poolable {
  lateinit var entity: Posc
  var alpha = 0f
  var showing = false
  var isValid = false

  val display = Seq<MutablePair<EntityInfoDisplay<Model<*>>, Model<*>?>>()

  var size: Float = -1f
    get() = if (field < 0) entity.let {
      field = when (it) {
        is Hitboxc -> it.hitSize()/1.44f
        is Building -> it.block.size*Vars.tilesize/2f
        else -> 10f
      }
      field
    } else field

  override fun reset() {
    size = -1f
    display.forEach { e ->
      e.second?.also {
        if (it is InputCheckerModel) it.element.remove()
        Pools.free(it)
      }
    }
    display.clear()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is EntityEntry) return false
    return entity === other.entity
  }

  override fun hashCode(): Int {
    return entity.hashCode()
  }
}

