package helium.ui.fragments.entityinfo.displays

import arc.math.Interp
import arc.math.Mathf
import arc.scene.Element
import arc.scene.ui.layout.Table
import helium.ui.HeAssets
import helium.ui.fragments.entityinfo.EntityInfoDisplay
import helium.ui.fragments.entityinfo.InputCheckerModel
import helium.ui.fragments.entityinfo.InputEventChecker
import helium.ui.fragments.entityinfo.Side
import mindustry.gen.Posc
import mindustry.ui.Displayable

class DetailsModel: InputCheckerModel<Displayable> {
  override lateinit var element: Element
  override lateinit var entity: Displayable

  var fadeOut = 0f
  var hovering = false

  override fun setup(ent: Displayable) {/*no action*/}
  override fun reset() {
    fadeOut = 0f
    hovering = false
  }
}

class DetailsDisplay: EntityInfoDisplay<DetailsModel>(::DetailsModel), InputEventChecker<DetailsModel> {
  override val layoutSide: Side get() = Side.BOTTOM
  override val hoveringOnly: Boolean get() = true

  override val DetailsModel.prefWidth: Float
    get() = element.prefWidth
  override val DetailsModel.prefHeight: Float
    get() = element.prefHeight

  override fun DetailsModel.buildListener(): Element {
    val tab = Table(HeAssets.padGrayUIAlpha).also {
      it.isTransform = true
      it.originX = 0f
      it.originY = 0f
    }
    entity.display(tab)
    tab.marginBottom(tab.background.bottomHeight)
    return tab
  }

  override fun valid(entity: Posc) = entity is Displayable
  override fun enabled() = true

  override fun DetailsModel?.checkHovering(isHovered: Boolean): Boolean {
    if (this != null) {
      if (isHovered) {
        hovering = true
        return true
      }
      hovering = false
      return fadeOut > 0f
    }
    return isHovered
  }

  override fun DetailsModel.realWidth(prefSize: Float) = element.prefWidth
  override fun DetailsModel.realHeight(prefSize: Float) = element.prefHeight

  override fun DetailsModel.draw(alpha: Float, scale: Float, origX: Float, origY: Float, drawWidth: Float, drawHeight: Float) {
    val drawW = drawWidth/scale
    val drawH = drawHeight/scale
    val r = Interp.pow4Out.apply(fadeOut)
    element.scaleX = scale*r
    element.scaleY = scale
    element.setBounds(origX + drawWidth/2*(1 - r), origY, drawW, drawH)
  }

  override fun DetailsModel.update(delta: Float) {
    fadeOut = Mathf.approach(fadeOut, if (hovering) 1f else 0f, delta*0.06f)
    if (fadeOut <= 0f) element.visible = false
    else element.visible = true
  }
}