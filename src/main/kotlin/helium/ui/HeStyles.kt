package helium.ui

import arc.Core
import arc.Events
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.scene.style.Drawable
import arc.scene.style.NinePatchDrawable
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.Dialog.DialogStyle
import arc.scene.ui.layout.Scl
import helium.Helium
import helium.graphics.*
import helium.ui.HeAssets.transparent
import helium.ui.elements.roulette.StripButtonStyle
import helium.ui.fragments.entityinfo.displays.HealthBarStyle
import mindustry.game.EventType
import mindustry.graphics.Pal
import mindustry.ui.Fonts
import mindustry.ui.Styles

object HeStyles {
  lateinit var BLUR_BACK: Drawable

  lateinit var blurStrip: StripDrawable

  lateinit var none: StripDrawable
  lateinit var black: StripDrawable
  lateinit var black9: StripDrawable
  lateinit var black8: StripDrawable
  lateinit var black7: StripDrawable
  lateinit var black6: StripDrawable
  lateinit var black5: StripDrawable
  lateinit var boundBlack: StripDrawable
  lateinit var boundBlack9: StripDrawable
  lateinit var boundBlack8: StripDrawable
  lateinit var boundBlack7: StripDrawable
  lateinit var boundBlack6: StripDrawable
  lateinit var boundBlack5: StripDrawable
  lateinit var grayPanel: StripDrawable
  lateinit var flatOver: StripDrawable
  lateinit var edgeFlatOver: StripDrawable
  lateinit var flatDown: StripDrawable
  lateinit var clearEdge: StripDrawable
  lateinit var accent: StripDrawable

  lateinit var transparentBack: DialogStyle

  lateinit var test: HealthBarStyle

  lateinit var clearS: StripButtonStyle
  lateinit var toggleClearS: StripButtonStyle
  lateinit var boundClearS: StripButtonStyle
  lateinit var flatS: StripButtonStyle
  lateinit var grayS: StripButtonStyle

  var uiBlur: Blur = Blur()

  private var drawingCounter = 0
  private var lastDialogs = 0

  fun load() {
    Events.run(EventType.Trigger.uiDrawBegin) { drawingCounter = 0 }
    Events.run(EventType.Trigger.uiDrawEnd) { lastDialogs = drawingCounter }

    BLUR_BACK = object : TextureRegionDrawable(Core.atlas.white()) {
      override fun draw(x: Float, y: Float, width: Float, height: Float) {
        drawingCounter++
        if (drawingCounter == lastDialogs) uiBlur.drawBlur {
          Draw.alpha(Draw.getColorAlpha())
          Draw.rect(region, x + width/2f, y + height/2f, width, height)
        }

        Styles.black5.draw(x, y, width, height)
      }

      override fun draw(
        x: Float, y: Float, originX: Float, originY: Float,
        width: Float, height: Float,
        scaleX: Float, scaleY: Float,
        rotation: Float,
      ) {
        drawingCounter++
        if (drawingCounter == lastDialogs) uiBlur.drawBlur {
          Draw.alpha(Draw.getColorAlpha())
          Draw.rect(
            region,
            x + width/2f, y + height/2f,
            width*scaleX, height*scaleY,
            originX, originY,
            rotation
          )
        }

        Styles.black5.draw(x, y, originX, originY, width, height, scaleX, scaleY, rotation)
      }
    }

    blurStrip = object: FillStripDrawable(Color.white){
      override fun draw(
        originX: Float,
        originY: Float,
        angle: Float,
        distance: Float,
        angleDelta: Float,
        stripWidth: Float,
      ) {
        drawingCounter++
        if (drawingCounter == lastDialogs) uiBlur.drawBlur {
          Draw.alpha(Draw.getColorAlpha())
          DrawUtils.circleStrip(
            originX, originY,
            distance, distance + stripWidth,
            angleDelta, angle,
          )
          Draw.color()
        }
      }
    }

    none = FillStripDrawable(Color.clear)
    black = FillStripDrawable(Color.black)
    black9 = FillStripDrawable(Color.black.cpy().a(0.9f))
    black8 = FillStripDrawable(Color.black.cpy().a(0.8f))
    black7 = FillStripDrawable(Color.black.cpy().a(0.6f))
    black6 = FillStripDrawable(Color.black.cpy().a(0.5f))
    black5 = FillStripDrawable(Color.black.cpy().a(0.3f))
    boundBlack = EdgeLineStripDrawable(Scl.scl(3f), Pal.darkestGray, Color.black)
    boundBlack9 = EdgeLineStripDrawable(Scl.scl(3f), Pal.darkestGray, Color.black.cpy().a(0.9f))
    boundBlack8 = EdgeLineStripDrawable(Scl.scl(3f), Pal.darkestGray, Color.black.cpy().a(0.8f))
    boundBlack7 = EdgeLineStripDrawable(Scl.scl(3f), Pal.darkestGray, Color.black.cpy().a(0.6f))
    boundBlack6 = EdgeLineStripDrawable(Scl.scl(3f), Pal.darkestGray, Color.black.cpy().a(0.5f))
    boundBlack5 = EdgeLineStripDrawable(Scl.scl(3f), Pal.darkestGray, Color.black.cpy().a(0.3f))
    grayPanel = FillStripDrawable(Pal.darkestGray)
    flatOver = FillStripDrawable(Color.valueOf("454545").a(0.6f))
    flatDown = EdgeLineStripDrawable(Scl.scl(3f), Pal.accent)
    edgeFlatOver = EdgeLineStripDrawable(Scl.scl(3f), Pal.darkestGray, Color.valueOf("454545"))
    clearEdge = EdgeLineStripDrawable(Scl.scl(3f), Pal.darkestGray)
    accent = FillStripDrawable(Pal.accent)

    transparentBack = object : DialogStyle() {
      init {
        stageBackground = transparent
        titleFont = Fonts.outline
        background = transparent
        titleFontColor = Pal.accent
      }
    }

    test = HealthBarStyle(
      background = ScaledNinePatchClipDrawable(Helium.getDrawable("background") as NinePatchDrawable),
      backgroundColor = Color.white.cpy().a(0.6f),
      healthBar = ScaledNinePatchClipDrawable(Helium.getDrawable("healthbar") as NinePatchDrawable),
      healthPadLeft = 6,
      healthPadRight = 6,
      shieldBar = ScaledNinePatchClipDrawable(Helium.getDrawable("shieldbar") as NinePatchDrawable),
      shieldPadLeft = 5,
      shieldPadRight = 5,
      font = Fonts.outline,
      texOffX = 15,
      texOffY = 7,
      shieldsOffX = 15,
      shieldsOffY = 7
    )

    flatS = StripButtonStyle(
      over = flatOver,
      down = flatOver,
      up = black,
    )
    grayS = StripButtonStyle(
      over = flatOver,
      down = flatOver,
      up = grayPanel,
    )
    clearS = StripButtonStyle(
      down = flatDown,
      up = none,
      over = flatOver,
    )
    toggleClearS = StripButtonStyle(
      down = flatDown,
      up = none,
      over = flatOver,
      checked = flatDown,
      checkedOver = EdgeLineStripDrawable(Scl.scl(3f), Pal.accent, Color.valueOf("454545").a(0.6f)),
    )
    boundClearS = StripButtonStyle(
      down = flatDown,
      up = clearEdge,
      over = edgeFlatOver,
    )
  }
}
