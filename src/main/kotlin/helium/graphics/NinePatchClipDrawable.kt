package helium.graphics

import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.NinePatch
import arc.math.Mathf
import arc.scene.style.NinePatchDrawable
import universe.util.reflect.Reflection.accessField
import universe.util.reflect.Reflection.accessInt

@Suppress("LeakingThis")
open class NinePatchClipDrawable: BaseClipDrawable {
  companion object {
    private val tmpColor = Color()

    private val NinePatch.idxField by accessInt("idx")
    private val NinePatch.botLeftField by accessInt("bottomLeft")
    private val NinePatch.botCenterField by accessInt("bottomCenter")
    private val NinePatch.botRightField by accessInt("bottomRight")
    private val NinePatch.midLeftField by accessInt("middleLeft")
    private val NinePatch.midCenterField by accessInt("middleCenter")
    private val NinePatch.midRightField by accessInt("middleRight")
    private val NinePatch.topLeftField by accessInt("topLeft")
    private val NinePatch.topCenterField by accessInt("topCenter")
    private val NinePatch.topRightField by accessInt("topRight")
    private val NinePatch.verticesField: FloatArray by accessField("vertices")
  }

  protected lateinit var ninePatch: NinePatch
  protected lateinit var originVert: FloatArray
  protected var idx = 0
  protected var bottomLeft = -1
  protected var bottomCenter = -1
  protected var bottomRight = -1
  protected var middleLeft = -1
  protected var middleCenter = -1
  protected var middleRight = -1
  protected var topLeft = -1
  protected var topCenter = -1
  protected var topRight = -1

  private val vertices = FloatArray(9*4*6)

  /** Creates an uninitialized NinePatchDrawable. The ninepatch must be [setPatch] before use.  */
  constructor() {
  }

  constructor(patch: NinePatch) {
    setPatch(patch)
  }

  constructor(drawable: NinePatchClipDrawable): super(drawable) {
    setPatch(drawable.ninePatch)
  }

  constructor(drawable: NinePatchDrawable): this(drawable.patch!!) {
    minWidth = drawable.minWidth
    minHeight = drawable.minHeight
    leftWidth = drawable.leftWidth
    rightWidth = drawable.rightWidth
    topHeight = drawable.topHeight
    bottomHeight = drawable.bottomHeight
  }

  override fun draw(
    x: Float, y: Float, width: Float, height: Float,
    clipLeft: Float, clipRight: Float, clipTop: Float, clipBottom: Float,
  ) {
    val vertices = this.vertices
    val patch = this.ninePatch

    if (clipLeft > 0f || clipRight > 0f || clipTop > 0f || clipBottom > 0f) {
      prepareVertices(
        vertices, originVert, patch,
        x, y, width, height, 1f, 1f,
        clipLeft, clipRight, clipTop, clipBottom
      )
    }
    else {
      prepareVertices(
        vertices, originVert, patch,
        x, y, width, height, 1f, 1f
      )
    }

    Draw.vert(patch.texture, vertices, 0, idx)
  }

  override fun draw(
    x: Float, y: Float, originX: Float, originY: Float,
    width: Float, height: Float, scaleX: Float, scaleY: Float, rotation: Float,
    clipLeft: Float, clipRight: Float, clipTop: Float, clipBottom: Float,
  ) {
    val vertices = this.vertices
    val patch = this.ninePatch
    val n = this.idx

    if (clipLeft > 0f || clipRight > 0f || clipTop > 0f || clipBottom > 0) {
      prepareVertices(
        vertices, originVert, patch,
        x, y, width, height, scaleX, scaleY,
        clipLeft, clipRight, clipTop, clipBottom
      )
    }
    else {
      prepareVertices(
        vertices, originVert, patch,
        x, y, width, height, scaleX, scaleY
      )
    }

    val worldOriginX = x + originX
    val worldOriginY = y + originY
    if (rotation != 0f) {
      var i = 0
      while (i < n) {
        val vx = (vertices[i] - worldOriginX)*scaleX
        val vy = (vertices[i + 1] - worldOriginY)*scaleY
        val cos = Mathf.cosDeg(rotation)
        val sin = Mathf.sinDeg(rotation)
        vertices[i] = cos*vx - sin*vy + worldOriginX
        vertices[i + 1] = sin*vx + cos*vy + worldOriginY
        i += 6
      }
    }
    else if (scaleX != 1f || scaleY != 1f) {
      var i = 0
      while (i < n) {
        vertices[i] = (vertices[i] - worldOriginX)*scaleX + worldOriginX
        vertices[i + 1] = (vertices[i + 1] - worldOriginY)*scaleY + worldOriginY
        i += 6
      }
    }
    Draw.vert(patch.texture, vertices, 0, n)
  }

  open fun setPatch(patch: NinePatch) {
    this.ninePatch = patch
    minWidth = patch.totalWidth
    minHeight = patch.totalHeight
    leftWidth = patch.padLeft
    rightWidth = patch.padRight
    topHeight = patch.padTop
    bottomHeight = patch.padBottom

    patch.apply {
      idx = idxField

      bottomLeft = botLeftField
      bottomCenter = botCenterField
      bottomRight = botRightField
      middleLeft = midLeftField
      middleCenter = midCenterField
      middleRight = midRightField
      topLeft = topLeftField
      topCenter = topCenterField
      topRight = topRightField

      originVert = verticesField
    }
  }

  /** Creates a new drawable that renders the same as this drawable tinted the specified color.  */
  fun tint(tint: Color?): NinePatchClipDrawable {
    val drawable = NinePatchClipDrawable(this)
    drawable.ninePatch = NinePatch(drawable.ninePatch, tint)
    return drawable
  }

  @Suppress("DuplicatedCode")
  protected open fun prepareVertices(
    vertices: FloatArray, originVert: FloatArray, patch: NinePatch,
    x: Float, y: Float, width: Float, height: Float, scaleX: Float, scaleY: Float,
  ) {
    vertices.fill(0f)

    val centerColumnX = x + leftWidth
    val rightColumnX = x + width - rightWidth
    val middleRowY = y + bottomHeight
    val topRowY = y + height - topHeight
    val c: Float = tmpColor.set(patch.color).mul(Draw.getColor()).toFloatBits()

    val bl = bottomLeft
    val bc = bottomCenter
    val br = bottomRight
    val ml = middleLeft
    val mc = middleCenter
    val mr = middleRight
    val tl = topLeft
    val tc = topCenter
    val tr = topRight

    if (bl != -1) set(
      vertices, bl,
      x, y, centerColumnX - x, middleRowY - y,
      originVert[bl + 3], originVert[bl + 4],
      originVert[bl + 15], originVert[bl + 16],
      c
    )
    if (bc != -1) set(
      vertices, bc,
      centerColumnX, y, rightColumnX - centerColumnX, middleRowY - y,
      originVert[bc + 3], originVert[bc + 4],
      originVert[bc + 15], originVert[bc + 16],
      c
    )
    if (br != -1) set(
      vertices, br,
      rightColumnX, y, x + width - rightColumnX, middleRowY - y,
      originVert[br + 3], originVert[br + 4],
      originVert[br + 15], originVert[br + 16],
      c
    )
    if (ml != -1) set(
      vertices, ml,
      x, middleRowY, centerColumnX - x, topRowY - middleRowY,
      originVert[ml + 3], originVert[ml + 4],
      originVert[ml + 15], originVert[ml + 16],
      c
    )
    if (mc != -1) set(
      vertices, mc,
      centerColumnX, middleRowY, rightColumnX - centerColumnX, topRowY - middleRowY,
      originVert[mc + 3], originVert[mc + 4],
      originVert[mc + 15], originVert[mc + 16],
      c
    )
    if (mr != -1) set(
      vertices, mr,
      rightColumnX, middleRowY, x + width - rightColumnX, topRowY - middleRowY,
      originVert[mr + 3], originVert[mr + 4],
      originVert[mr + 15], originVert[mr + 16],
      c
    )
    if (tl != -1) set(
      vertices, tl,
      x, topRowY, centerColumnX - x, y + height - topRowY,
      originVert[tl + 3], originVert[tl + 4],
      originVert[tl + 15], originVert[tl + 16],
      c
    )
    if (tc != -1) set(
      vertices, tc,
      centerColumnX, topRowY, rightColumnX - centerColumnX, y + height - topRowY,
      originVert[tc + 3], originVert[tc + 4],
      originVert[tc + 15], originVert[tc + 16],
      c
    )
    if (tr != -1) set(
      vertices, tr,
      rightColumnX, topRowY, x + width - rightColumnX, y + height - topRowY,
      originVert[tr + 3], originVert[tr + 4],
      originVert[tr + 15], originVert[tr + 16],
      c
    )
  }

  @Suppress("DuplicatedCode")
  protected open fun prepareVertices(
    vertices: FloatArray, originVert: FloatArray, patch: NinePatch,
    x: Float, y: Float, width: Float, height: Float, scaleX: Float, scaleY: Float,
    cLeft: Float, cRight: Float, cTop: Float, cBottom: Float,
  ) {
    vertices.fill(0f)

    if (cLeft > width - cRight || cTop > height - cBottom) return

    val bl = bottomLeft
    val bc = bottomCenter
    val br = bottomRight
    val ml = middleLeft
    val mc = middleCenter
    val mr = middleRight
    val tl = topLeft
    val tc = topCenter
    val tr = topRight

    val centerColumnX = x + this.leftWidth
    val rightColumnX = x + width - this.rightWidth
    val middleRowY = y + this.bottomHeight
    val topRowY = y + height - this.topHeight

    val bottomHeight = this.bottomHeight
    val topHeight = this.topHeight
    val leftWidth = this.leftWidth
    val rightWidth = this.rightWidth
    val centerWidth = width - leftWidth - rightWidth
    val centerHeight = height - bottomHeight - topHeight

    val clipToX = width - cRight
    val clipYoY = height - cTop

    val fromX = x + cLeft
    val fromY = y + cBottom
    val toX = x + clipToX
    val toY = y + clipYoY

    val c = tmpColor.set(patch.color).mul(Draw.getColor()).toFloatBits()

    if (bl != -1 && fromX < centerColumnX && fromY < middleRowY) {
      val u1 = originVert[bl + 3]
      val v1 = originVert[bl + 4]
      val u2 = originVert[bl + 15]
      val v2 = originVert[bl + 16]

      val rateXL = cLeft/leftWidth
      val rateYB = cBottom/bottomHeight
      val rateXR = Mathf.clamp(clipToX/leftWidth)
      val rateYT = Mathf.clamp(clipYoY/bottomHeight)

      set(
        vertices, bl,
        fromX, fromY,
        leftWidth*(rateXR - rateXL), bottomHeight*(rateYT - rateYB),
        u1 + (u2 - u1)*rateXL, v1 + (v2 - v1)*rateYB, u1 + (u2 - u1)*rateXR, v1 + (v2 - v1)*rateYT,
        c
      )
    }
    if (bc != -1 && fromX < rightColumnX && toX > centerColumnX && fromY < middleRowY) {
      val u1 = originVert[bc + 3]
      val v1 = originVert[bc + 4]
      val u2 = originVert[bc + 15]
      val v2 = originVert[bc + 16]

      val rateXL = Mathf.clamp((cLeft - leftWidth)/centerWidth)
      val rateYB = cBottom/bottomHeight
      val rateXR = Mathf.clamp((clipToX - leftWidth)/centerWidth)
      val rateYT = Mathf.clamp(clipYoY/bottomHeight)

      set(
        vertices, bc,
        centerColumnX + centerWidth*rateXL, fromY,
        centerWidth*(rateXR - rateXL), bottomHeight*(rateYT - rateYB),
        u1 + (u2 - u1)*rateXL, v1 + (v2 - v1)*rateYB, u1 + (u2 - u1)*rateXR, v1 + (v2 - v1)*rateYT,
        c
      )
    }
    if (br != -1 && toX > rightColumnX && fromY < middleRowY) {
      val u1 = originVert[br + 3]
      val v1 = originVert[br + 4]
      val u2 = originVert[br + 15]
      val v2 = originVert[br + 16]

      val rateXL = Mathf.clamp((cLeft - leftWidth - centerWidth)/rightWidth)
      val rateYB = cBottom/bottomHeight
      val rateXR = Mathf.clamp((clipToX - leftWidth - centerWidth)/rightWidth)
      val rateYT = Mathf.clamp(clipYoY/bottomHeight)

      set(
        vertices, br,
        rightColumnX + rightWidth*rateXL, fromY,
        rightWidth*(rateXR - rateXL), bottomHeight*(rateYT - rateYB),
        u1 + (u2 - u1)*rateXL, v1 + (v2 - v1)*rateYB, u1 + (u2 - u1)*rateXR, v1 + (v2 - v1)*rateYT,
        c
      )
    }

    if (ml != -1 && fromX < centerColumnX && fromY < topRowY && toY > middleRowY) {
      val u1 = originVert[ml + 3]
      val v1 = originVert[ml + 4]
      val u2 = originVert[ml + 15]
      val v2 = originVert[ml + 16]

      val rateXL = cLeft/leftWidth
      val rateYB = Mathf.clamp((cBottom - bottomHeight)/centerHeight)
      val rateXR = Mathf.clamp(clipToX/leftWidth)
      val rateYT = Mathf.clamp((clipYoY - bottomHeight)/centerHeight)

      set(
        vertices, ml,
        fromX, middleRowY + centerHeight*rateYB,
        leftWidth*(rateXR - rateXL), centerHeight*(rateYT - rateYB),
        u1 + (u2 - u1)*rateXL, v1 + (v2 - v1)*rateYB, u1 + (u2 - u1)*rateXR, v1 + (v2 - v1)*rateYT,
        c
      )
    }
    if (mc != -1 && fromX < rightColumnX && toX > centerColumnX && fromY < topRowY && toY > middleRowY) {
      val u1 = originVert[mc + 3]
      val v1 = originVert[mc + 4]
      val u2 = originVert[mc + 15]
      val v2 = originVert[mc + 16]

      val rateXL = Mathf.clamp((cLeft - leftWidth)/centerWidth)
      val rateYB = Mathf.clamp((cBottom - bottomHeight)/centerHeight)
      val rateXR = Mathf.clamp((clipToX - leftWidth)/centerWidth)
      val rateYT = Mathf.clamp((clipYoY - bottomHeight)/centerHeight)

      set(
        vertices, mc,
        centerColumnX + centerWidth*rateXL, middleRowY + centerHeight*rateYB,
        centerWidth*(rateXR - rateXL), centerHeight*(rateYT - rateYB),
        u1 + (u2 - u1)*rateXL, v1 + (v2 - v1)*rateYB, u1 + (u2 - u1)*rateXR, v1 + (v2 - v1)*rateYT,
        c
      )
    }
    if (mr != -1 && toX > rightColumnX && fromY < topRowY && toY > middleRowY) {
      val u1 = originVert[mr + 3]
      val v1 = originVert[mr + 4]
      val u2 = originVert[mr + 15]
      val v2 = originVert[mr + 16]

      val rateXL = Mathf.clamp((cLeft - leftWidth - centerWidth)/rightWidth)
      val rateYB = Mathf.clamp((cBottom - bottomHeight)/centerHeight)
      val rateXR = Mathf.clamp((clipToX - leftWidth - centerWidth)/rightWidth)
      val rateYT = Mathf.clamp((clipYoY - bottomHeight)/centerHeight)

      set(
        vertices, mr,
        rightColumnX + rightWidth*rateXL, middleRowY + centerHeight*rateYB,
        rightWidth*(rateXR - rateXL), centerHeight*(rateYT - rateYB),
        u1 + (u2 - u1)*rateXL, v1 + (v2 - v1)*rateYB, u1 + (u2 - u1)*rateXR, v1 + (v2 - v1)*rateYT,
        c
      )
    }

    if (tl != -1 && fromX < centerColumnX && toY > topRowY) {
      val u1 = originVert[tl + 3]
      val v1 = originVert[tl + 4]
      val u2 = originVert[tl + 15]
      val v2 = originVert[tl + 16]

      val rateXL = cLeft/leftWidth
      val rateYB = Mathf.clamp((cBottom - bottomHeight - centerHeight)/topHeight)
      val rateXR = Mathf.clamp(clipToX/leftWidth)
      val rateYT = Mathf.clamp((clipYoY - bottomHeight - centerHeight)/topHeight)

      set(
        vertices, tl,
        fromX, middleRowY + centerHeight + topHeight*rateYB,
        leftWidth*(rateXR - rateXL), topHeight*(rateYT - rateYB),
        u1 + (u2 - u1)*rateXL, v1 + (v2 - v1)*rateYB, u1 + (u2 - u1)*rateXR, v1 + (v2 - v1)*rateYT,
        c
      )
    }
    if (tc != -1 && fromX < rightColumnX && toX > centerColumnX && toY > topRowY) {
      val u1 = originVert[tc + 3]
      val v1 = originVert[tc + 4]
      val u2 = originVert[tc + 15]
      val v2 = originVert[tc + 16]

      val rateXL = Mathf.clamp((cLeft - leftWidth)/centerWidth)
      val rateYB = Mathf.clamp((cBottom - bottomHeight - centerHeight)/topHeight)
      val rateXR = Mathf.clamp((clipToX - leftWidth)/centerWidth)
      val rateYT = Mathf.clamp((clipYoY - bottomHeight - centerHeight)/topHeight)

      set(
        vertices, tc,
        centerColumnX + centerWidth*rateXL, middleRowY + centerHeight + topHeight*rateYB,
        centerWidth*(rateXR - rateXL), topHeight*(rateYT - rateYB),
        u1 + (u2 - u1)*rateXL, v1 + (v2 - v1)*rateYB, u1 + (u2 - u1)*rateXR, v1 + (v2 - v1)*rateYT,
        c
      )
    }
    if (tr != -1 && toX > rightColumnX && toY > topRowY) {
      val u1 = originVert[tr + 3]
      val v1 = originVert[tr + 4]
      val u2 = originVert[tr + 15]
      val v2 = originVert[tr + 16]

      val rateXL = Mathf.clamp((cLeft - leftWidth - centerWidth)/rightWidth)
      val rateYB = Mathf.clamp((cBottom - bottomHeight - centerHeight)/topHeight)
      val rateXR = Mathf.clamp((clipToX - leftWidth - centerWidth)/rightWidth)
      val rateYT = Mathf.clamp((clipYoY - bottomHeight - centerHeight)/topHeight)

      set(
        vertices, tr,
        rightColumnX + rightWidth*rateXL, middleRowY + centerHeight + topHeight*rateYB,
        rightWidth*(rateXR - rateXL), topHeight*(rateYT - rateYB),
        u1 + (u2 - u1)*rateXL, v1 + (v2 - v1)*rateYB, u1 + (u2 - u1)*rateXR, v1 + (v2 - v1)*rateYT,
        c
      )
    }
  }

  protected fun set(
    vertices: FloatArray, idx: Int,
    x: Float, y: Float, width: Float, height: Float,
    u1: Float, v1: Float, u2: Float, v2: Float,
    color: Float,
  ){
    val fx2 = x + width
    val fy2 = y + height
    val mixColor = Color.clearFloatBits
    vertices[idx + 0] = x
    vertices[idx + 1] = y
    vertices[idx + 2] = color
    vertices[idx + 3] = u1
    vertices[idx + 4] = v1
    vertices[idx + 5] = mixColor

    vertices[idx + 6] = x
    vertices[idx + 7] = fy2
    vertices[idx + 8] = color
    vertices[idx + 9] = u1
    vertices[idx + 10] = v2
    vertices[idx + 11] = mixColor

    vertices[idx + 12] = fx2
    vertices[idx + 13] = fy2
    vertices[idx + 14] = color
    vertices[idx + 15] = u2
    vertices[idx + 16] = v2
    vertices[idx + 17] = mixColor

    vertices[idx + 18] = fx2
    vertices[idx + 19] = y
    vertices[idx + 20] = color
    vertices[idx + 21] = u2
    vertices[idx + 22] = v1
    vertices[idx + 23] = mixColor
  }
}