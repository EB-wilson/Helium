package helium.ui.fragments.entityinfo

import arc.Core
import arc.Events
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.graphics.g2d.Lines
import arc.math.Interp
import arc.math.Mathf
import arc.math.geom.Geometry
import arc.math.geom.QuadTree
import arc.math.geom.Rect
import arc.math.geom.Vec2
import arc.scene.Group
import arc.scene.event.Touchable
import arc.scene.ui.Label
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Stack
import arc.scene.ui.layout.Table
import arc.struct.Bits
import arc.struct.IntMap
import arc.struct.ObjectSet
import arc.struct.Seq
import arc.util.Scaling
import arc.util.Time
import arc.util.Tmp
import arc.util.pooling.Pool
import arc.util.pooling.Pools
import helium.He
import helium.He.config
import helium.addEventBlocker
import helium.graphics.EdgeLineStripDrawable
import helium.graphics.FillStripDrawable
import helium.set
import helium.ui.HeAssets
import helium.ui.HeStyles
import helium.ui.elements.HeCollapser
import helium.ui.elements.roulette.StripButton
import helium.ui.elements.roulette.StripButtonStyle
import helium.ui.elements.roulette.StripWrap
import helium.ui.fragments.entityinfo.Side.*
import helium.util.IndexedSerial
import helium.util.SerialObject
import helium.util.accessField
import helium.util.enterSt
import helium.util.exitSt
import helium.util.poolObtain
import mindustry.Vars
import mindustry.entities.Units
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.*
import mindustry.gen.Unit
import mindustry.graphics.Drawf
import mindustry.graphics.Layer
import mindustry.graphics.Pal
import mindustry.input.Binding
import mindustry.ui.Fonts
import mindustry.ui.Styles
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class EntityInfoFrag {
  companion object {
    private val tmp = Vec2()
    private val outerStyle = HeStyles.clearS.copyWith(
      over = FillStripDrawable (
        Pal.accent.cpy().a(0.7f),
        Pal.accent.cpy().a(0f)
      ),
      down = EdgeLineStripDrawable (
        Scl.scl(2f),
        Pal.accent,
        Pal.accent.cpy().a(0.7f),
        Pal.accent.cpy().a(0f)
      ),
      checked = FillStripDrawable(
        Pal.accent.cpy(),
        Pal.accent.cpy().a(0f)
      )
    )

    private val Bits.bits: LongArray by accessField("bits")
  }

  var controlling = false
  private var shouldSetup = false

  private val disabledTeams = IntMap<Bits>()
  private val providers = Seq<DisplayProvider<*, *>>()
  private val hoveringProviders = Seq<DisplayProvider<*, *>>()

  private val entityEntries = IntMap<EntityEntry>()
  //Indexed array, elements are repeatable, for all entries and hovering only entries
  private val entriesList = IndexedSerial<EntityEntry>(
    elementType = EntityEntry::class,
    indexOrder = 0
  )

  private val hoveringEntries = IntMap<EntityEntry>()
  //foreach optimize
  private val hoveringList = IndexedSerial<EntityEntry>(
    elementType = EntityEntry::class,
    indexOrder = 1
  )
  private val hovering = ObjectSet<EntityEntry>()
  private val holding = ObjectSet<EntityEntry>()

  private var selecting = false
  private var clearTimer = 0f
  private val selectStart = Vec2()
  private val selectionRect = Rect()

  private val worldViewport = Rect()
  private val screenViewport = Rect()

  private lateinit var infoFill: Group
  private var configPane: Table? = null

  init {
    Events.on(EventType.ResizeEvent::class.java) {
      configPane?.also { buildConfig(it) }
    }
  }

  /**for hot-update*/
  fun displaySetupUpdated(){
    clearEntry()
    shouldSetup = true
  }

  @Suppress("UNCHECKED_CAST")
  fun addDisplay(display: DisplayProvider<*, *>) {
    if (display.hoveringOnly) hoveringProviders.add(display)
    else providers.add(display)
    disabledTeams.put(display.typeID, Bits(256))
    displaySetupUpdated()
  }

  @Suppress("UNCHECKED_CAST")
  fun addDisplayBefore(display: DisplayProvider<*, *>, target: DisplayProvider<*, *>){
    val list = if (display.hoveringOnly) hoveringProviders else providers

    val index = list.indexOf(target)
    if (index == -1) list.add(display)
    else list.insert(index, display)
    disabledTeams.put(display.typeID, Bits(256))
    displaySetupUpdated()
  }

  @Suppress("UNCHECKED_CAST")
  fun addDisplayAfter(display: DisplayProvider<*, *>, target: DisplayProvider<*, *>){
    val list = if (display.hoveringOnly) hoveringProviders else providers

    val index = list.indexOf(target)
    if (index == -1 || index == list.size - 1) list.add(display)
    else list.insert(index + 1, display)
    disabledTeams.put(display.typeID, Bits(256))
    displaySetupUpdated()
  }

  @Suppress("UNCHECKED_CAST")
  fun replaceDisplay(display: DisplayProvider<*, *>, target: DisplayProvider<*, *>) {
    val list = if (display.hoveringOnly) hoveringProviders else providers
    val index = list.indexOf(target)
    if (index != -1) list[index] = display
    displaySetupUpdated()
  }

  @Suppress("UNCHECKED_CAST")
  fun removeDisplay(display: DisplayProvider<*, *>) {
    if (display.hoveringOnly) hoveringProviders.remove(display)
    else providers.remove(display)
    disabledTeams.remove(display.typeID)
    displaySetupUpdated()
  }

  fun setupDisplay(){
    val targets = ObjectSet<TargetGroup<*>>()
    providers.forEach {
      val groups = it.targetGroup()
      if (groups.contains(TargetGroup.all) ) {
        TargetGroup.all.apply(::addEntry, ::removeEntry, ::clearEntry)
        return
      }
      else groups.forEach { group -> targets.add(group) }
    }

    targets.forEach {
      it.apply(::addEntry, ::removeEntry, ::clearEntry)
    }
  }

  fun toggleSwitchConfig() {
    if (configPane!!.visible) {
      config.saveAsync()
      configPane!!.visible = false
    }
    else {
      buildConfig(configPane!!)
      configPane!!.visible = true
    }
  }

  private fun buildTeamStyle(team: Team): StripButtonStyle {
    val overColor = team.color.cpy().lerp(Color.white, 0.4f)
    return StripButtonStyle(
      up = FillStripDrawable(team.color.cpy().a(0.7f)),
      over = EdgeLineStripDrawable(
        Scl.scl(3f),
        Color.lightGray,
        overColor
      ),
      down = EdgeLineStripDrawable(
        Scl.scl(3f),
        Color.white,
        overColor
      ),
      checked = EdgeLineStripDrawable(
        Scl.scl(3f),
        Pal.accent.cpy().lerp(Color.white, 0.4f),
        team.color
      ),
      checkedOver = EdgeLineStripDrawable(
        Scl.scl(3f),
        Pal.accent.cpy().lerp(Color.white, 0.4f),
        overColor
      ),
      disabled = FillStripDrawable(
        team.color.cpy().a(0.7f)
      )
    )
  }

  private fun buildConfig(table: Table) {
    table.clearChildren()

    var current: DisplayProvider<*, *>? = null
    val teams = Vars.state.teams.active
    val stack = Stack()
    stack.addEventBlocker()

    val radius = min(min(Core.graphics.width, Core.graphics.height)/2f - Scl.scl(30f), Scl.scl(460f))
    val r1 = radius*0.3f
    val r2 = radius*0.44f
    val r3 = radius*0.76f
    val r4 = radius
    val off = Scl.scl(3f)

    val toolTip = { table: Table, str: String ->
      table.row()
      table.add(HeCollapser(collX = false, collY = true, collapsed = true){
        it.add(str, Styles.outlineLabel, 0.75f)
      }.also { col ->
        col.setDuration(0.35f, Interp.pow2Out)
        table.parent.enterSt { col.setCollapsed(false) }
        table.parent.exitSt { col.setCollapsed(true) }
      })
    }

    stack.add(StripWrap(background = HeStyles.blurStrip).also {
      it.setCBounds(
        0f, 0f,
        360f, r3
      )
    })
    stack.add(StripWrap(background = HeStyles.black5).also {
      it.setCBounds(
        0f, 0f,
        360f, r3
      )
    })

    stack.add(StripWrap(background = HeAssets.whiteEdge).also {
      it.setColor(Pal.darkestGray)
      it.setCBounds(
        90f, 0f,
        0f, r1 - off
      )
    })
    stack.add(StripWrap(background = HeAssets.whiteEdge).also {
      it.setColor(Pal.darkestGray)
      it.setCBounds(
        210f, 0f,
        0f, r1 - off
      )
    })
    stack.add(StripWrap(background = HeAssets.whiteEdge).also {
      it.setColor(Pal.darkestGray)
      it.setCBounds(
        330f, 0f,
        0f, r1 - off
      )
    })

    stack.add(StripWrap(background = HeStyles.boundBlack).also {
      it.setColor(Pal.darkestGray)
      it.setCBounds(
        0f, r1 - off/2f,
        360f, 0f
      )
    })

    stack.add(StripWrap(
      Label(Core.bundle["infos.enabledTeams"], Styles.outlineLabel).also { it.setFontScale(0.85f) },
      HeStyles.boundBlack5
    ).also {
      it.setCBounds(
        60f, r1,
        60f, r2 - r1
      )
    })
    val teamButtonDelta = 300f/teams.size
    teams.forEachIndexed { i, team ->
      stack.add(StripButton(
        buildTeamStyle(team.team),
        Label(team.team.emoji, Styles.outlineLabel).also { it.setFontScale(2f) }
      ).also {
        it.setCBounds(
          120f + teamButtonDelta*i, r1,
          teamButtonDelta, r2 - r1
        )
        it.setDisabled { current == null }
        it.update { it.isChecked = current?.let { dis -> !disabledTeams[dis.typeID].get(team.team.id) }?: false }
        it.clicked { disabledTeams[current!!.typeID].flip(team.team.id) }
      })
    }

    stack.add(StripWrap(background = HeStyles.boundBlack).also {
      it.setCBounds(
        0f, r2 + off/2f,
        360f, 0f
      )
    })

    val displayButtonDelta = 360f/providers.size
    providers.forEachIndexed{ i, dis ->
      if (dis is ConfigurableDisplay){
        stack.add(StripWrap(background = HeStyles.black5).also {
          it.setCBounds(
            90f + displayButtonDelta*i, r3 + off,
            displayButtonDelta, r4 - r3 - off
          )
        })
        dis.getConfigures().also { list ->
          val configButtonDelta = displayButtonDelta/list.size
          stack.add(StripWrap(background = HeAssets.whiteEdge).also {
            it.setColor(Pal.darkestGray)
            it.setCBounds(
              90f + displayButtonDelta*(i + 1), r3 + off,
              0f, r4 - r3 - off
            )
          })
          list.forEachIndexed { ci, conf ->
            stack.add(StripWrap(background = HeAssets.whiteEdge).also {
              it.setColor(Pal.darkestGray)
              it.setCBounds(
                90f + displayButtonDelta*i + configButtonDelta*ci, r3 + off,
                0f, r4 - r3 - off
              )
            })
          }
          stack.add(StripWrap(background = HeAssets.whiteEdge).also {
            it.setColor(Pal.darkestGray)
            it.setCBounds(
              90f + displayButtonDelta*i, r3 + off/2f,
              displayButtonDelta, 0f
            )
          })
        }
      }
      stack.add(StripWrap(background = HeAssets.whiteEdge).also {
        it.setColor(Pal.darkestGray)
        it.setCBounds(
          90f + displayButtonDelta*i, r2 + off,
          0f, r3 - r2 - off
        )
      })
    }

    stack.add(StripButton(HeStyles.clearS){
      it.image(Icon.none).size(42f).scaling(Scaling.fit)
      toolTip(it, Core.bundle["infos.disableAll"])
    }.also {
      it.setCBounds(
        90f, 0f,
        120f, r1 - off
      )
      it.clicked { disabledTeams.values().forEach { bits -> Arrays.fill(bits.bits, -0x1L) } }
    })
    stack.add(StripButton(HeStyles.clearS){
      it.image(Icon.filters).size(42f).scaling(Scaling.fit)
      toolTip(it, Core.bundle["infos.allSetting"])
    }.also {
      it.setCBounds(
        210f, 0f,
        120f, r1 - off
      )
      it.clicked { He.configDialog.show("entityInfo") }
    })
    stack.add(StripButton(HeStyles.clearS){
      it.image(Icon.effect).size(42f).scaling(Scaling.fit)
      toolTip(it, Core.bundle["infos.enableAll"])
    }.also {
      it.setCBounds(
        330f, 0f,
        120f, r1 - off
      )
      it.clicked {
        disabledTeams.values().forEach { bits -> Arrays.fill(bits.bits, 0) }
      }
    })

    providers.forEachIndexed { i, dis ->
      stack.add(StripButton(outerStyle) { dis.buildConfig(it) }.also {
        it.setCBounds(
          90f + displayButtonDelta*i, r2 + off,
          displayButtonDelta, r3 - r2 - off
        )
        it.update { it.isChecked = current == dis }
        it.clicked { current = dis }
      })

      if (dis is ConfigurableDisplay){
        dis.getConfigures().also { list ->
          val configButtonDelta = displayButtonDelta/list.size
          list.forEachIndexed { ci, conf ->
            stack.add(StripButton(HeStyles.toggleClearS){ conf.build(it) }.also {
              it.setCBounds(
                90f + displayButtonDelta*i + configButtonDelta*ci, r3 + off,
                configButtonDelta, r4 - r3 - off
              )
              conf.checked?.also { ch -> it.update { it.isChecked = ch.get() } }
              it.clicked { conf.callback.run() }
            })
          }
        }
      }
    }

    table.add(stack).size(0f)
  }

  fun build(parent: Group){
    setupDisplay()

    infoFill = object: Group(){
      override fun draw() {
        if (!config.enableEntityInfoDisplay) return
        drawHUDLay()
        super.draw()
      }

      override fun act(delta: Float) {
        if (!config.enableEntityInfoDisplay){
          if (entityEntries.any()) clearEntry()
          shouldSetup = true
          return
        }
        else if (shouldSetup) {
          clearEntry()
          setupEntry()
          shouldSetup = false
        }

        super.act(delta)

        updateShowing()
        update(delta*60)
      }
    }

    parent.addChildAt(0, infoFill)
    infoFill.fillParent = true
    infoFill.visible { Vars.state.isGame }

    parent.fill { config ->
      configPane = config
      config.touchable = Touchable.enabled
      config.clicked {
        toggleSwitchConfig()
      }
      config.visible = false
    }
  }

  @Suppress("UNCHECKED_CAST")
  fun addEntry(entity: Entityc, hovering: Boolean = false): EntityEntry? {
    if (entity !is Teamc) return null

    //val entry = poolObtain{ EntityEntry(entity, hovering) }
    val entry = EntityEntry(entity, hovering)

    (if (hovering) hoveringProviders else providers).forEach { prov ->
      if (prov.enabled() && prov.valid(entity)) {
        prov as DisplayProvider<Teamc, *>
        val display = prov.provide(entity, entity.id())
        Core.app.post { display.team = entity.team() } // post handle

        if (display is InputEventChecker) {
          display as InputEventChecker
          display.element = display.buildListener().also { elem -> infoFill.addChild(elem) }
        }

        entry.displays.add(display)
      }
    }

    if (entry.displays.any()) {
      if (hovering) {
        hoveringEntries[entity.id()] = entry
        hoveringList.add(entry)
      }
      else entityEntries[entity.id()] = entry

      entriesList.add(entry)

      return entry
    }

    return null
  }
  fun removeEntry(entity: Entityc, hovering: Boolean = false) {
    val ent = (if (hovering) hoveringEntries else entityEntries).remove(entity.id())
    if (ent == null) return

    if (hovering) hoveringList.remove(ent)
    entriesList.remove(ent)

    ent.displays.forEach { display ->
      if (display is InputEventChecker) display.element.remove()
    }
  }

  fun setupEntry(){
    clearEntry()

    val targets = ObjectSet<TargetGroup<*>>()
    providers.forEach {
      val groups = it.targetGroup()
      if (groups.contains(TargetGroup.all) ) {
        Groups.all.forEach(::addEntry)
        return
      }
      else groups.forEach { group -> targets.add(group) }
    }

    targets.forEach {
      it.get().forEach(::addEntry)
    }
  }
  fun clearEntry() {
    entriesList.forEach { Pools.free(it) }
    entriesList.clear()
    entityEntries.clear()
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

    if (controlling || Core.input.keyDown(config.entityInfoHotKey)) {
      hovering.forEach { e ->
        val rad = e.size*1.44f + Mathf.absin(4f, 2f)
        val ent = e.entity
        val origX = ent.x
        val origY = ent.y

        Draw.z(Layer.overlayUI)
        Drawf.poly(
          origX, origY, 4,
          rad, 0f,
          if (e.holding) Color.crimson else Pal.accent
        )
      }
    }

    entriesList.forEach { e ->
      if (!e.inFog && e.holding) {
        val rad = e.size*1.44f
        val ent = e.entity
        val origX = ent.x
        val origY = ent.y

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

      val a = if (e.isHovering) e.alpha*alpha else alpha
      e.displays.forEach { display ->
        if (!display.worldRender) return@forEach

        val disabledTeam = disabledTeams[display.typeID]
        if (disabledTeam.get(display.team.id)) return@forEach

        val ent = entityEntries[display.id].entity
        if (!display.shouldDisplay() || !display.checkWorldClip(ent, worldViewport)) return@forEach
        display.drawWorld(a)
      }
    }
  }

  private fun updateShowing() {
    val mouse = Core.input.mouseWorld()
    val hotkeyDown = controlling || Core.input.keyDown(config.entityInfoHotKey)
    if (hotkeyDown) {
      if (!selecting && Core.input.keyDown(Binding.select)) {
        selecting = true
        clearTimer = Time.globalTime
        selectStart.set(mouse)
        selectionRect.set(mouse.x, mouse.y, 0f, 0f)
      }
      else if (selecting) {
        val beginX = min(selectStart.x, mouse.x)
        val beginY = min(selectStart.y, mouse.y)
        val width = abs(selectStart.x - mouse.x)
        val height = abs(selectStart.y - mouse.y)
        selectionRect.set(beginX, beginY, width, height)

        if (selectionRect.width > 4 || selectionRect.height > 4) clearTimer = 0f
      }
      else {
        selectionRect.set(mouse.x, mouse.y, 0f, 0f)
      }
    }
    else selectionRect.set(mouse.x, mouse.y, 0f, 0f)

    entriesList.forEach { it.player = null }
    Groups.player.forEach { player ->
      player.unit()?.also {
        entityEntries[it.id]?.player = player
        hoveringEntries[it.id]?.player = player
      }
    }

    hovering.forEach { it.mouseHovering = false }
    hovering.clear(32)
    val team = Vars.player.team()
    fun execEntity(ent: Teamc){
      if (ent.inFogTo(team)) return
      val id = ent.id()
      var entry = hoveringEntries[id]
      if (entry == null){
        entry = addEntry(ent, true)
      }
      entry.showing = true

      hovering.add(entry.also { it.mouseHovering = true })
      entityEntries[id]?.also { e -> hovering.add(e.also { it.mouseHovering = true }) }
    }

    val x1 = (selectionRect.x/Vars.tilesize).roundToInt()
    val y1 = (selectionRect.y/Vars.tilesize).roundToInt()
    val x2 = ((selectionRect.x + selectionRect.width)/Vars.tilesize).roundToInt()
    val y2 = ((selectionRect.y + selectionRect.height)/Vars.tilesize).roundToInt()
    (x1..x2).forEach { x ->
      (y1..y2).forEach n@{ y ->
        val build = Vars.world.build(x, y)
        build?.also {
          execEntity(it)
        }
      }
    }

    if (selectionRect.width <= 4 || selectionRect.height <= 4) {
      val v1 = selectionRect.getCenter(Tmp.v1)
      Units.nearby(v1.x - 120f, v1.y - 120f, 240f, 240f) { unit ->
        if (checkHovering(unit)) execEntity(unit)
      }
    }
    else Units.nearby(selectionRect) { unit -> execEntity(unit) }

    if (selecting && !(hotkeyDown && Core.input.keyDown(Binding.select))){
      if (hovering.isEmpty && Time.globalTime - clearTimer < 15f) {
        holding.forEach { it.holding = false }
        holding.clear(32)
      }
      else {
        var anyAdded = hovering.size > holding.size
        hovering.forEach { hov ->
          anyAdded = holding.add(hov.also { it.holding = true }) or anyAdded
        }

        if (!anyAdded) hovering.forEach {
          holding.remove(it.also { e -> e.holding = false })
        }
      }

      selecting = false
      selectStart.setZero()
    }

    val list = hoveringList.array
    var i = 0
    while (i < hoveringList.size){
      val e = list[i]!!
      if (e.showing || e.holding) {
        e.showing = false
        e.alpha = Mathf.approachDelta(e.alpha, 1f, 0.05f)
      }
      else e.alpha = Mathf.approachDelta(e.alpha, 0f, 0.05f)

      if (e.alpha <= 0){
        removeEntry(e.entity, true)
        i--
      }
      i++
    }
  }

  private fun checkHovering(entity: Posc): Boolean {
    val rect = selectionRect

    return when(entity){
      is QuadTree.QuadTreeObject -> {
        val box = Tmp.r1.also { entity.hitbox(it) }
        rect.overlaps(box)
      }
      else -> {
        rect.overlaps(
          entity.x - 5, entity.y - 5,
          10f, 10f
        )
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun update(delta: Float) {
    val alpha = config.entityInfoAlpha
    val playerTeam = Vars.player.team()
    entriesList.forEach { ent ->
      val inFog = ent.entity.inFogTo(playerTeam)
      ent.inFog = inFog
      if (inFog) return@forEach
      val a = if (ent.isHovering) ent.alpha*alpha else alpha
      ent.displays.forEach { dis ->
        dis.update(delta, a, ent.mouseHovering, ent.holding)
      }
    }
  }

  private fun drawHUDLay() {
    val scale = config.entityInfoScale
    val alpha = config.entityInfoAlpha

    Draw.sort(true)

    entriesList.forEach { e ->
      if (e.inFog) return@forEach
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

      val origin = e.entity.let { Core.camera.project(it.x, it.y) }
      val origX = origin.x
      val origY = origin.y

      val a = if (e.isHovering) e.alpha*alpha else alpha
      e.displays.forEach f@{ display ->
        if (!display.screenRender) return@f
        val disabledTeam = disabledTeams[display.typeID]
        if (disabledTeam.get(display.team.id)) return@f

        display.apply {
          if (!display.shouldDisplay()) return@f
          when (display.layoutSide) {
            CENTER -> {
              centerWith = max(display.prefWidth, centerWith)
              centerHeight = max(display.prefHeight, centerHeight)
            }

            RIGHT -> rightHeight = max(display.prefHeight, rightHeight)
            TOP -> topWidth = max(display.prefWidth, topWidth)
            LEFT -> leftHeight = max(display.prefHeight, leftHeight)
            BOTTOM -> bottomWidth = max(display.prefWidth, bottomWidth)
          }
        }
      }

      val orig = Core.camera.project(0f, 0f)
      val sizeOrig = orig.x
      val sizeOff = Core.camera.project(e.size, 0f).x - sizeOrig

      e.displays.forEach f@{ display ->
        val disabledTeam = disabledTeams[display.typeID]
        if (disabledTeam.get(display.team.id)) return@f

        val dir = if(display.layoutSide == CENTER) Tmp.p1.set(0, 0) else Geometry.d4(display.layoutSide.dir)
        val offset = Tmp.v1.set(sizeOff*dir.x, sizeOff*dir.y)
        val ox = origX + offset.x
        val oy = origY + offset.y

        display.apply {
          if (!display.shouldDisplay()) return@f

          val maxSizeMul = maxSizeMultiple
          val minSizeMul = minSizeMultiple

          when (layoutSide) {
            CENTER -> {
              val disW = centerWith*scale
              val disH = centerHeight*scale
              if (display.checkScreenClip(screenViewport, ox - disW/2, oy - disH/2, disW, disH))
                display.draw(a, scale, ox - disW/2, oy - disH/2, disW, disH)
            }
            RIGHT -> {
              val w = display.realWidth(rightHeight)
              val h = display.realHeight(rightHeight)
              val scl = if (maxSizeMul < 0 || minSizeMul < 0) scale
              else min(max(maxSizeMul*sizeOff/h, minSizeMul*sizeOff/h*scale), scale)
              val disW = w*scl
              val disH = h*scl
              if (display.checkScreenClip(screenViewport, ox + offsetRight, oy - disH/2, disW, disH))
                display.draw(a, scl, ox + offsetRight, oy - disH/2, disW, disH)

              offsetRight += disW
            }
            TOP -> {
              val off = e.player?.run {
                if (this.unit() != null && this.name() != null && !this.unit().inFogTo(Vars.player.team())) {
                  if (!isLocal)
                    Core.camera.project(tmp.set(0f, lineHeight())).y - orig.y
                  else if (Core.settings.getBool("playerchat") && (textFadeTime() > 0.0f && lastText() != null || typing()))
                    Core.camera.project(tmp.set(0f, lineHeight()*2)).y - orig.y
                  else 0f
                }
                else 0f
              }?: 0f

              val w = display.realWidth(topWidth)
              val h = display.realHeight(topWidth)
              val scl = if (maxSizeMul < 0 || minSizeMul < 0) scale
              else min(max(maxSizeMul*sizeOff/w, minSizeMul*sizeOff/w*scale), scale)
              val disW = w*scl
              val disH = h*scl
              if (display.checkScreenClip(screenViewport, ox - disW/2, oy + offsetTop + off, disW, disH))
                display.draw(a, scl, ox - disW/2, oy + offsetTop + off, disW, disH)

              offsetTop += disH
            }
            LEFT -> {
              val w = display.realWidth(leftHeight)
              val h = display.realHeight(leftHeight)
              val scl = if (maxSizeMul < 0 || minSizeMul < 0) scale
              else min(max(maxSizeMul*sizeOff/h, minSizeMul*sizeOff/h*scale), scale)
              val disW = w*scl
              val disH = h*scl
              if (display.checkScreenClip(screenViewport, ox - disW - offsetLeft, oy - disH/2, disW, disH))
                display.draw(a, scl, ox - disW - offsetLeft, oy - disH/2, disW, disH)

              offsetLeft += disW
            }
            BOTTOM -> {
              val w = display.realWidth(bottomWidth)
              val h = display.realHeight(bottomWidth)
              val scl = if (maxSizeMul < 0 || minSizeMul < 0) scale
              else min(max(maxSizeMul*sizeOff/w, minSizeMul*sizeOff/w*scale), scale)
              val disW = w*scl
              val disH = h*scl
              if (display.checkScreenClip(screenViewport, ox - disW/2, oy - disH - offsetBottom, disW, disH))
                display.draw(a, scl, ox - disW/2, oy - disH - offsetBottom, disW, disH)

              offsetBottom += disH
            }
          }
        }
      }
    }
    Draw.flush()
    Draw.sort(false)
  }

  private fun lineHeight() = Fonts.outline.capHeight*(0.25f/Scl.scl(1.0f)) + 3.0f
}

class EntityEntry(
  val entity: Teamc,
  val isHovering: Boolean
): SerialObject {
  override var indexes = intArrayOf(-1, -1)
  var player: Playerc? = null

  var mouseHovering = false
  var holding = false
  var inFog = false

  var alpha = 0f
  var showing = false

  var displays = Seq<EntityInfoDisplay<*>>()

  val size: Float
    get() = entity.let { when (it) {
      is Hitboxc -> it.hitSize()/1.44f
      is Building -> it.block.size*Vars.tilesize/2f
      else -> 10f
    } }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is EntityEntry) return false
    return entity === other.entity && isHovering == other.isHovering
  }

  override fun hashCode(): Int {
    return entity.let { if (isHovering) it.id().inv() else it.id() }
  }
}

