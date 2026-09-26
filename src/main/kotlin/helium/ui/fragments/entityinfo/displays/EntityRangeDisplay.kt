package helium.ui.fragments.entityinfo.displays

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.graphics.g2d.Lines
import arc.math.Interp
import arc.math.Mathf
import arc.math.geom.Rect
import arc.scene.ui.layout.Table
import arc.struct.Bits
import arc.util.Scaling
import arc.util.Time
import arc.util.Tmp
import helium.He
import helium.graphics.DrawUtils
import helium.graphics.HeShaders
import helium.ui.elements.HeCollapser
import helium.ui.fragments.entityinfo.*
import helium.util.enterSt
import helium.util.exitSt
import mindustry.game.Team
import mindustry.gen.Building
import mindustry.gen.Icon
import mindustry.gen.Posc
import mindustry.gen.Unitc
import mindustry.graphics.Layer
import mindustry.graphics.Pal
import mindustry.logic.Ranged
import mindustry.ui.Styles
import mindustry.world.blocks.defense.ForceProjector.ForceBuild
import mindustry.world.blocks.defense.MendProjector.MendBuild
import mindustry.world.blocks.defense.OverdriveProjector
import mindustry.world.blocks.defense.OverdriveProjector.OverdriveBuild
import mindustry.world.blocks.defense.turrets.BaseTurret.BaseTurretBuild
import mindustry.world.blocks.units.RepairTower
import mindustry.world.blocks.units.RepairTurret
import mindustry.world.meta.BlockStatus

class EntityRangeDisplayProvider: DisplayProvider<Ranged, EntityRangeDisplay>(), ConfigurableDisplay{
  override val typeID: Int get() = 893475812

  override fun targetGroup() = listOf(
    TargetGroup.build,
    TargetGroup.unit
  )
  override fun valid(entity: Posc): Boolean = entity is Ranged && entity !is ForceBuild
  override fun enabled() = He.config.let {
    it.enableRangeDisplay && (it.showAttackRange || it.showHealRange || it.showOverdriveRange)
  }

  override fun create() = EntityRangeDisplay()

  override fun initialize(display: EntityRangeDisplay, entity: Ranged, id: Int) {
    super.initialize(display, entity, id)
    display.timeOffset = Mathf.random(240f)
    display.phaseOffset = Mathf.random(360f)
    display.phaseScl = Mathf.random(0.9f, 1.1f)

    if (entity is Building) display.building = entity

    when(entity) {
      is Unitc -> display.isUnit = true
      is BaseTurretBuild -> display.isTurret = true
      is RepairTurret.RepairPointBuild -> display.isRepair = true
      is RepairTower.RepairTowerBuild -> display.isRepair = true
      is MendBuild -> display.isRepair = true
      is OverdriveBuild -> display.isOverdrive = true
    }

    //team() 要等实体构造完成才可靠，所以延后一帧；池化后必须校验这期间实例没被回收、也没被复用给别的实体
    Core.app.post {
      if (display.pooled || display.entity !== entity) return@post

      display.layerID = when{
        display.isUnit || display.isTurret -> {
          display.color.set(entity.team().color)
          display.alpha = 0.1f
          entity.team().id
        }
        display.isRepair -> {
          display.color.set(Pal.heal)
          display.alpha = 0.075f
          260
        }
        display.isOverdrive -> {
          display.color.set(0.731f, 0.522f, 0.425f, 1f)
          display.alpha = 0.075f
          261
        }
        else -> 300
      }
      display.color.a(0.6f)
      display.layerOffset = display.layerID*0.01f
    }
  }

  override fun buildConfig(table: Table) {
    table.image(Icon.diagonal).size(80f).scaling(Scaling.fit)
    table.row()
    table.add(HeCollapser(collX = false, collY = true, collapsed = true){
      it.add(Core.bundle["infos.entityRange"], Styles.outlineLabel)
    }.also { col ->
      col.setDuration(0.35f, Interp.pow2Out)
      table.parent.enterSt { col.setCollapsed(false) }
      table.parent.exitSt { col.setCollapsed(true) }
    })
  }
  override fun getConfigures() = listOf(
    ConfigPair(
      "showAttackRange",
      Icon.turret,
      He.config::showAttackRange
    ),
    ConfigPair(
      "showHealRange",
      Icon.defense,
      He.config::showHealRange
    ),
    ConfigPair(
      "showOverdriveRange",
      Icon.upOpen,
      He.config::showOverdriveRange
    )
  )
}

class EntityRangeDisplay: WorldDrawOnlyDisplay<Ranged>() {
  override val typeID: Int get() = 893475812
  var building: Building? = null
  var vis = 0f
  var range = 0f
  var edges = -1

  var isUnit = false
  var isTurret = false
  var isRepair = false
  var isOverdrive = false

  var timeOffset = 0f
  var phaseOffset = 0f
  var phaseScl = 0f

  val color = Color(1f, 1f, 1f, 1f)
  var alpha = 0f

  var layerID = 0
  var layerOffset = 0f

  /**归还对象池时把全部实体相关状态清干净，否则复用时会沿用上一个实体的类型/颜色/进度*/
  override fun recycle() {
    super.recycle()
    building = null
    vis = 0f
    range = 0f
    edges = -1
    isUnit = false
    isTurret = false
    isRepair = false
    isOverdrive = false
    timeOffset = 0f
    phaseOffset = 0f
    phaseScl = 0f
    color.set(1f, 1f, 1f, 1f)
    alpha = 0f
    layerID = 0
    layerOffset = 0f
    n = 30
    to = 0f
  }

  companion object {
    private var coneDrawing = false

    private val teamBits = Bits(Team.all.size)
    private var dashes = 0f

    var renderer = when(He.config.rangeRenderLevel){
      0 -> HeShaders.entityRangeRenderer
      1 -> HeShaders.lowEntityRangeRenderer
      else -> null
    }

    fun resetMark(){
      teamBits.clear()
      coneDrawing = false
      dashes = 0f
    }
  }

  override fun shouldDisplay() = vis > 0 && He.config.let {
    ((isUnit || isTurret) && it.showAttackRange)
    || (isRepair && it.showHealRange)
    || (isOverdrive && it.showOverdriveRange)
  }

  override fun checkWorldClip(entity: Posc, worldViewport: Rect) = (range*2).let { clipSize ->
    worldViewport.overlaps(
      entity.x - clipSize/2, entity.y - clipSize/2,
      clipSize, clipSize
    )
  }

  override fun draw(alpha: Float) {
    val a = (alpha/He.config.entityInfoAlpha*vis).let { if (it >= 0.999f) 1f else Interp.pow3Out.apply(it) }
    val radius = range*a
    val layer = Layer.light - 3 + layerOffset

    renderer?.also { renderer ->
      if (!teamBits.get(layerID)){
        teamBits.set(layerID)
        Draw.drawRange(layer, 0.0045f, {
          renderer.capture()
        }) {
          renderer.alpha = this.alpha*He.config.entityInfoAlpha
          renderer.boundColor = color
          renderer.render()
        }
      }

      Draw.z(layer + 0.001f)
      Draw.color(color)
      DrawUtils.fillCircle(entity.x, entity.y, radius - 1f)

      if (He.config.rangeRenderLevel == 0) {
        Draw.z(layer + 0.002f)
        val r = (Time.time*phaseScl + timeOffset)%240/240f
        val inner = Interp.pow3.apply(r)
        val outer = Interp.pow3Out.apply(r)

        if (edges == -1) {
          DrawUtils.innerCircle(
            entity.x, entity.y,
            inner*radius, outer*radius,
            Tmp.c1.set(Color.white).a(0f), Color.white, 1
          )
        }
        else {
          Draw.color()
          DrawUtils.innerPoly(
            entity.x, entity.y,
            edges, inner*radius, 0f,
            Tmp.c1.set(Color.white).a(0f), Color.white
          )
        }
      }

      Draw.z(layer + 0.003f)
      Lines.stroke(1f, Color.black)
      if (edges == -1) {
        DrawUtils.lineCircle(entity.x, entity.y, radius)
      }
      else {
        Lines.poly(
          entity.x, entity.y,
          edges, radius, 0f
        )
      }
    }?:run {
      val pos = Core.camera.position
      val dst = pos.dst(entity.x, entity.y)
      val rate = 1f - Mathf.maxZero((dst - radius)/radius)

      if (rate < 0.01f) return@run
      Draw.z(layer)
      Lines.stroke(1f)
      Draw.color(color, color.a*rate)

      if (edges == -1) {
        DrawUtils.dashCircle(
          entity.x, entity.y, radius,
          8 + (radius/12).toInt(),
          rotate = Time.time/radius*12 + timeOffset
        )
      }
      else {
        DrawUtils.dashPoly(
          entity.x, entity.y,
          edges, radius,
          0.5f,
          Time.time*2.2f + timeOffset,
          8 + (radius/12).toInt()
        )
      }
    }
  }

  var n = 30
  var to = 0f
  override fun update(delta: Float, alpha: Float, isHovering: Boolean, isHolding: Boolean) {
    if (n++ >= 30) {
      range = entity.getRange()
      edges = entity.getEdges()
      to = building?.let {
        if (it.status() !== BlockStatus.noInput) 1f else 0f
      }?:1f

      n = 0
    }
    if (!Mathf.equal(vis, to)) vis = Mathf.approach(vis, to, delta*0.04f)
  }

  private fun Ranged.getRange(): Float = when(this) {
    // why?
    is OverdriveBuild -> range()*phaseHeat*(block as OverdriveProjector).phaseRangeBoost
    else -> range()
  }

  private fun Ranged.getEdges(): Int = when(this) {
    is RepairTower.RepairTowerBuild -> 4
    else -> -1
  }
}
