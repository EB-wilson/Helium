package helium.ui.fragments.entityinfo.displays

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.math.Interp
import arc.math.Mathf
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.struct.Seq
import arc.util.Align
import arc.util.Scaling
import arc.util.Tmp
import helium.He
import helium.ui.elements.HeCollapser
import helium.ui.fragments.entityinfo.DisplayProvider
import helium.ui.fragments.entityinfo.EntityInfoDisplay
import helium.ui.fragments.entityinfo.Side
import helium.ui.fragments.entityinfo.TargetGroup
import helium.util.enterSt
import helium.util.exitSt
import mindustry.Vars
import mindustry.gen.Icon
import mindustry.gen.Posc
import mindustry.gen.Statusc
import mindustry.graphics.Pal
import mindustry.type.StatusEffect
import mindustry.ui.Fonts
import mindustry.ui.Styles
import kotlin.math.max

private val iconSize = Scl.scl(26f)
private val iconPadding = Scl.scl(4f)

class StatusDisplayProvider: DisplayProvider<Statusc, StatusDisplay>(){
  override val typeID: Int get() = 1237687141

  override fun targetGroup() = listOf(TargetGroup.unit)
  override fun valid(entity: Posc) = entity is Statusc
  override fun enabled() = He.config.enableUnitStatusDisplay
  override fun provide(
    entity: Statusc,
    id: Int
  ) = StatusDisplay(entity, id).apply {
    singleWidth = iconSize
  }

  override fun buildConfig(table: Table) {
    table.image(Icon.layers).size(80f).scaling(Scaling.fit)
    table.row()
    table.add(HeCollapser(collX = false, collY = true, collapsed = true){
      it.add(Core.bundle["infos.statusDisplay"], Styles.outlineLabel)
    }.also { col ->
      col.setDuration(0.35f, Interp.pow2Out)
      table.parent.enterSt { col.setCollapsed(false) }
      table.parent.exitSt { col.setCollapsed(true) }
    })
  }
}

class StatusDisplay(
  entity: Statusc,
  id: Int
): EntityInfoDisplay<Statusc>(entity, id) {
  override val typeID: Int get() = 1237687141

  var singleWidth = 1f
  val statusList = Seq<StatusEffect>()

  override val layoutSide: Side = Side.TOP

  override val prefHeight: Float
    get() = iconSize
  override val prefWidth: Float
    get() = iconSize

  override fun shouldDisplay(): Boolean {
    return statusList.any()
  }

  override fun realHeight(prefSize: Float): Float{
    val n = Mathf.ceil(prefSize/(singleWidth + iconPadding))
    return n*iconSize
  }
  override fun realWidth(prefSize: Float) = prefSize

  override fun draw(alpha: Float, scale: Float, origX: Float, origY: Float, drawWidth: Float, drawHeight: Float) {
    var offX = 0f
    var offY = 0f

    val singW = singleWidth*scale

    var newSingleW = 0f

    var rh = iconSize
    statusList.forEach { eff ->
      if (offX + singW >= drawWidth) {
        offX = iconPadding
        offY += iconSize*scale
        rh += (iconSize + iconPadding)*scale
      }
      newSingleW = max(drawStatus(eff, alpha, scale, origX + offX, origY + offY), newSingleW)
      offX += singW + iconPadding*scale
    }

    singleWidth = newSingleW
  }

  private fun drawStatus(
    status: StatusEffect,
    alpha: Float, scale: Float,
    origX: Float, origY: Float,
  ): Float {
    val icon = status.uiIcon
    val iconHeight = iconSize*scale
    val iconWidth = iconSize*(icon.width.toFloat()/icon.height.toFloat())*scale

    var dw = iconWidth/scale

    Draw.color(Color.white, alpha)
    Draw.rect(icon, origX + iconWidth/2, origY + iconHeight/2, iconWidth, iconHeight)

    if (status.permanent) return dw
    val time = entity.getDuration(status)
    if (time > 0) {
      val timeWidth = Fonts.outline.draw(
        formatTime(time),
        origX, origY + Fonts.outline.lineHeight*scale*0.7f,
        Tmp.c1.set(Pal.accent).a(alpha),
        scale*0.7f,
        false,
        Align.bottomLeft
      ).width/scale

      dw = max(dw, timeWidth)
    }

    return dw
  }

  private fun formatTime(ticks: Float): String {
    val seconds = ticks/60f
    if (seconds > 60){
      val minutes = seconds / 60
      if (minutes > 60){
        val hours = minutes / 60
        return "${Mathf.round(hours)}h"
      } else {
        return "${Mathf.round(minutes)}m"
      }
    }
    else {
      return "${Mathf.round(seconds)}s"
    }
  }

  override fun update(delta: Float, alpha: Float, isHovering: Boolean, isHolding: Boolean) {
    val list = statusList
    list.clear()
    Vars.content.statusEffects().forEach { eff ->
      if (!Core.atlas.isFound(eff.uiIcon)) return@forEach
      if (entity.hasEffect(eff)) {
        list.add(eff)
      }
    }
  }
}
