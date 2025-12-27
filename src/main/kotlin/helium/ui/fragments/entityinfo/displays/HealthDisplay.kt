package helium.ui.fragments.entityinfo.displays

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Font
import arc.graphics.g2d.FontCache
import arc.graphics.g2d.GlyphLayout
import arc.math.Interp
import arc.math.Mathf
import arc.scene.ui.Tooltip
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.util.Align
import arc.util.Scaling
import arc.util.Tmp.c1
import helium.He
import helium.graphics.ClipDrawable
import helium.ui.HeAssets
import helium.ui.elements.HeCollapser
import helium.ui.fragments.entityinfo.DisplayProvider
import helium.ui.fragments.entityinfo.EntityInfoDisplay
import helium.ui.fragments.entityinfo.Side
import helium.ui.fragments.entityinfo.TargetGroup
import helium.util.enterSt
import helium.util.exitSt
import helium.util.runInst
import mindustry.Vars
import mindustry.core.UI
import mindustry.gen.*
import mindustry.gen.Unit
import mindustry.graphics.Pal
import mindustry.ui.Fonts
import mindustry.ui.Styles
import kotlin.math.max

class UnitHealthDisplayProv: BaseHealthDisplayProv(){
  override val typeID: Int get() = 982347561
  override fun targetGroup() = listOf(TargetGroup.unit)
  override fun valid(entity: Posc) = entity is Unit

  override fun provide(
    entity: Healthc,
    id: Int
  ) = object: BaseHealthDisplay(style, entity, id){
    override val typeID: Int get() = 982347561
  }.apply {
    shieldEnt = entity as? Shieldc
    insectHealth = entity.health()
    insectShield = shieldEnt?.shield()?: 0f
  }
}

class BuildHealthDisplayProv: BaseHealthDisplayProv(){
  override val hoveringOnly: Boolean get() = true
  override val typeID: Int get() = 238907312
  override fun targetGroup() = listOf(TargetGroup.build)
  override fun valid(entity: Posc) = entity is Building

  override fun provide(
    entity: Healthc,
    id: Int
  ) = object: BaseHealthDisplay(style, entity, id){
    override val typeID: Int get() = 238907312
  }.apply {
    shieldEnt = entity as? Shieldc
    insectHealth = entity.health()
    insectShield = shieldEnt?.shield()?: 0f
  }
}

abstract class BaseHealthDisplayProv: DisplayProvider<Healthc, BaseHealthDisplay>(){
  lateinit var style: HealthBarStyle
  override fun enabled() = He.config.enableHealthBarDisplay
  override fun buildConfig(table: Table) {
    table.image(Icon.add).size(80f).scaling(Scaling.fit)
    table.row()
    table.add(HeCollapser(collX = false, collY = true, collapsed = true){
      it.add(Core.bundle["infos.healthDisplay"], Styles.outlineLabel)
    }.also { col ->
      col.setDuration(0.35f, Interp.pow2Out)
      table.parent.enterSt { col.setCollapsed(false) }
      table.parent.exitSt { col.setCollapsed(true) }
    })
  }
}

abstract class BaseHealthDisplay(
  val style: HealthBarStyle,
  entity: Healthc,
  id: Int
): EntityInfoDisplay<Healthc>(entity, id){
  var detailWidth = 0f
  var shieldWidth = 0f

  var hovering = false

  var lastHealth = 0f
  var lastShield = 0f
  var lastAlpha = 1f
  var lastScale = 1f
  var lastN = 0
  val detailBuff = StringBuilder()
  val shieldBuff = StringBuilder()

  var detailCache = FontCache(Fonts.outline)
  var shieldCache = FontCache(Fonts.outline)

  var insectHealth = 0f
  var insectShield = 0f

  var shieldEnt: Shieldc? = null

  override val layoutSide: Side = Side.TOP

  override fun realWidth(prefSize: Float) = prefSize
  override fun realHeight(prefSize: Float) = prefHeight

  override val prefWidth: Float get() = entity.let {
    val minWidth = max(
      style.background.minWidth,
      style.texOffX + detailWidth + Scl.scl(20f) + shieldWidth + style.shieldPadRight
    )

    when(it) {
      is Hitboxc -> max(it.hitSize()*8f, minWidth)
      is Building -> max(it.block.size*Vars.tilesize*8f, minWidth)
      else -> minWidth
    }
  }
  override val prefHeight: Float get() = style.height

  override fun update(delta: Float, alpha: Float, isHovering: Boolean, isHolding: Boolean) {
    hovering = isHovering || isHolding
    insectHealth = Mathf.lerp(insectHealth, entity.health(), delta*0.05f)
    shieldEnt?.also {
      insectShield = Mathf.lerp(insectShield, it.shield(), delta*0.05f)
    }
  }

  override fun draw(alpha: Float, scale: Float, origX: Float, origY: Float, drawWidth: Float, drawHeight: Float) {
    val drawW = drawWidth/scale
    val drawH = drawHeight/scale

    val progressHealth = Mathf.clamp(entity.health()/entity.maxHealth())
    val insProgHealth = Mathf.clamp(insectHealth/entity.maxHealth())

    val healthBarWidth = (drawW - style.healthPadLeft - style.healthPadRight)
    val shieldBarWidth = (drawW - style.shieldPadLeft - style.shieldPadRight)

    val teamC = entity.runInst(Teamc::class) { it.team().color }?: Color.white

    Draw.z(0f)
    Draw.color(c1.set(style.backgroundColor).mulA(alpha))
    style.background.draw(
      origX, origY, 0f, 0f,
      drawW, drawH, scale, scale, 0f,
      0f, 0f, 0f, 0f
    )

    Draw.color(Pal.lightishGray, alpha)
    style.healthBar.draw(
      origX, origY, 0f, 0f,
      drawW, drawH, scale, scale, 0f,
      style.healthPadLeft.toFloat(),
      (style.healthPadRight + healthBarWidth*(1 - insProgHealth)),
      0f, 0f
    )

    Draw.color(teamC, alpha)
    style.healthBar.draw(
      origX, origY, 0f, 0f,
      drawW, drawH, scale, scale, 0f,
      style.healthPadLeft.toFloat(),
      style.healthPadRight + healthBarWidth*(1 - progressHealth),
      0f, 0f
    )

    style.shieldBar?.also {
      val n = max(Mathf.ceil(insectShield/entity.maxHealth()), 0)
      val insProgShield = (insectShield%entity.maxHealth())/entity.maxHealth()
      val r = if (Vars.state.isPaused) 0f else Mathf.absin(8f, 0.5f)

      if (n > 1) {
        Draw.color(shieldColor(teamC, n - 1), alpha*(1f - r))
        it.draw(
          origX, origY, 0f, 0f,
          drawW, drawH, scale, scale, 0f,
          style.shieldPadLeft + shieldBarWidth*insProgShield,
          style.shieldPadRight.toFloat(),
          0f, 0f
        )
      }

      Draw.color(shieldColor(teamC, n), alpha*(1f - r))
      it.draw(
        origX, origY, 0f, 0f,
        drawW, drawH, scale, scale, 0f,
        style.shieldPadLeft.toFloat(),
        style.shieldPadRight + shieldBarWidth*(1 - insProgShield),
        0f, 0f
      )
    }

    style.foreground?.also {
      Draw.color(c1.set(style.foregroundColor).mulA(alpha))
      it.draw(
        origX, origY, 0f, 0f,
        drawW, drawH, scale, scale, 0f,
        0f, 0f, 0f, 0f
      )
    }

    if (!hovering && He.config.hideHealthText) return

    //The texture of the text is decoupled from the UI, should be drawn in groups to optimize performance
    Draw.z(10f)
    val rate = drawHeight/style.font.capHeight
    val alp = if (hovering) 1f else alpha*(Mathf.clamp((rate - 0.75f)/0.5f))
    if(alp > 0.001f){
      updateText(drawWidth, scale, alp)
      if (style.shieldBar != null) {
        shieldCache.setPosition(origX, origY)
        shieldCache.draw()
      }
      detailCache.setPosition(origX, origY)
      detailCache.draw()
    }
  }

  private fun shieldColor(teamC: Color, n: Int): Color = when(Mathf.mod(n, 3)) {
    0 -> if (teamC.diff(Pal.reactorPurple) < 0.1f) Pal.heal else Pal.reactorPurple
    1 -> if (teamC.diff(Pal.accent) < 0.1f) HeAssets.lightBlue else Pal.accent
    2 -> if (teamC.diff(Pal.heal) < 0.1f) Pal.lancerLaser else Pal.heal
    else -> throw RuntimeException("wtf?")
  }

  private fun updateText(drawWidth: Float, scale: Float, alpha: Float){
    if (!hovering && He.config.hideHealthText) return

    if (detailCache.font != style.font) detailCache = FontCache(style.font)
    if (shieldCache.font != style.font) shieldCache = FontCache(style.font)

    val health = entity.health()
    val shield = shieldEnt?.shield()?: 0f
    val scaleModified = !Mathf.equal(lastScale, scale, 0.001f)

    if (scaleModified || health != lastHealth || shield != lastShield){
      if (health != lastHealth || shield != lastShield) {
        detailBuff.apply {
          clear()
          append("\uE813 ")
          append(Mathf.round(health))
          append("/")
          append(Mathf.round(entity.maxHealth()))
          if (shield > 0) {
            append(" - ")
            append("\uE84D ")
            append(UI.formatAmount(Mathf.round(shield).toLong()))
          }
        }
        detailWidth = GlyphLayout.obtain().let {
          it.setText(style.font, detailBuff)
          val res = it.width
          it.free()
          res*style.fontScl
        }
      }

      detailCache.clear()
      detailCache.color = c1.set(Color.white).a(alpha)
      style.font.apply {
        val pscale = getData().scaleX
        val pint = usesIntegerPositions()
        getData().setScale(scale*style.fontScl)
        setUseIntegerPositions(false)
        detailCache.setText(
          detailBuff,
          style.texOffX*Scl.scl(scale),
          style.texOffY*Scl.scl(scale) + style.font.capHeight,
          0f,
          Align.topLeft,
          false
        )
        getData().setScale(pscale)
        setUseIntegerPositions(pint)
      }

      lastHealth = health
      lastShield = shield
    }

    val n = Mathf.ceil((shieldEnt?.shield()?:0f)/entity.maxHealth())

    if (scaleModified || lastN != n) {
      if (lastN != n) {
        shieldBuff.clear()
        if (n > 1) shieldBuff.append("x").append(n)

        shieldWidth = GlyphLayout.obtain().let {
          it.setText(style.font, shieldBuff)
          val res = it.width
          it.free()
          res*style.shieldFontScl
        }
      }

      val fontColor =
        if (n >= 10000) Color.crimson
        else if (n >= 1000) Color.red
        else if (n >= 100) Pal.accent
        else if (n >= 10) Color.white
        else Color.lightGray
      shieldCache.clear()
      shieldCache.color = c1.set(fontColor).a(alpha)
      style.font.apply {
        val pscale = getData().scaleX
        val pint = usesIntegerPositions()
        getData().setScale(scale*style.shieldFontScl)
        setUseIntegerPositions(false)
        shieldCache.setText(
          shieldBuff,
          drawWidth - style.shieldsOffX*Scl.scl(scale),
          style.shieldsOffY*Scl.scl(scale) + style.font.capHeight,
          0f,
          Align.topRight,
          false
        )
        getData().setScale(pscale)
        setUseIntegerPositions(pint)
      }

      lastN = n
    }

    if (!Mathf.equal(lastAlpha, alpha, 0.001f)){
      detailCache.setAlphas(alpha)
      shieldCache.setAlphas(alpha)
    }

    lastScale = scale
    lastAlpha = alpha
  }
}

class HealthBarStyle(
  val background: ClipDrawable,
  val backgroundColor: Color = Color.white,
  val foreground: ClipDrawable? = null,
  val foregroundColor: Color = Color.white,
  val healthBar: ClipDrawable,
  val healthPadLeft: Int,
  val healthPadRight: Int,
  val shieldBar: ClipDrawable? = null,
  val shieldPadLeft: Int = 0,
  val shieldPadRight: Int = 0,
  val font: Font = Fonts.outline,
  val fontScl: Float = 0.8f,
  val texOffX: Int,
  val texOffY: Int,
  val shieldFontScl: Float = 0.7f,
  val shieldsOffX: Int,
  val shieldsOffY: Int,
) {
  val height: Float = background.minHeight
}
