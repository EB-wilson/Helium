package helium.graphics

import arc.Core
import arc.graphics.*
import arc.graphics.g2d.Draw
import arc.graphics.gl.FrameBuffer
import arc.graphics.gl.Shader
import arc.math.Angles
import arc.math.Mat
import arc.math.Mathf
import arc.math.geom.Vec2
import helium.Helium
import universe.graphic.ScreenSampler

class Blur {
  companion object {
    private const val VERTEX_SIZE = 3
    private const val SHAPE_SIZE = VERTEX_SIZE*4
    private val tmpVert1: FloatArray = FloatArray(SHAPE_SIZE)
    private val tmpVert2: FloatArray = FloatArray(SHAPE_SIZE)

    private val v1 = Vec2()
    private val v2 = Vec2()
    private val v3 = Vec2()
    private val v4 = Vec2()
  }

  private val baseShader: Shader = Shader(
    Helium.getInternalFile("shaders").child("gauss_blur.vert"),
    Helium.getInternalFile("shaders").child("blur_base.frag"),
  ).also { shader ->
    shader.bind()
    shader.setUniformi("u_screen", 0)
  }
  private val blurShader: Shader = Shader(
    Helium.getInternalFile("shaders").child("gauss_blur.vert"),
    Helium.getInternalFile("shaders").child("gauss_blur.frag"),
  ).also { shader ->
    shader.bind()
    shader.setUniformi("u_screen", 0)
  }
  private val pingpong1: FrameBuffer = FrameBuffer()
  private val pingpong2: FrameBuffer = FrameBuffer()
  private val mesh = Mesh(
    false, 4000, 6000,
    VertexAttribute.position,
    VertexAttribute(1, Gl.floatV, true, "a_strength")
  ).also { mesh ->
    val len = 6000
    val indices = ShortArray(6000)

    var j: Short = 0
    var i = 0
    while (i < len) {
      indices[i] = j
      indices[i + 1] = (j + 1).toShort()
      indices[i + 2] = (j + 2).toShort()
      indices[i + 3] = (j + 2).toShort()
      indices[i + 4] = (j + 3).toShort()
      indices[i + 5] = j
      i += 6
      j = (j + 4).toShort()
    }

    mesh.setIndices(indices)
    mesh.verticesBuffer.position(0)
    mesh.verticesBuffer.limit(mesh.verticesBuffer.capacity())
    //mark indices as dirty once for GL30
    mesh.indicesBuffer
  }

  private val transformMatrix: Mat = Mat()
  private val projectionMatrix: Mat = Mat()
  private val combinedMatrix = Mat()

  private val drawInst = DrawBlur()

  private val buffer = mesh.verticesBuffer
  private var idx = 0

  var blurSpace: Float = 1.5f
  var blurScl: Int = 2
  var blurLevel: Int = 2

  fun resize(width: Int, height: Int) {
    val w = width/blurScl
    val h = height/blurScl

    pingpong1.resize(w, h)
    pingpong2.resize(w, h)

    blurShader.bind()
    blurShader.setUniformf("u_screenSize", w.toFloat(), h.toFloat())
    baseShader.bind()
    baseShader.setUniformf("u_screenSize", width.toFloat(), height.toFloat())
  }

  fun drawBlur(
    proj: Mat = Draw.proj(),
    trans: Mat = Draw.trans(),
    block: DrawBlur.() -> Unit,
  ) {
    resize(Core.graphics.width, Core.graphics.height)

    discard()
    setProjection(proj)
    setTransform(trans)
    drawInst.block()
    render()
  }

  fun pushShape(vertices: FloatArray, offset: Int = 0, count: Int = vertices.size) {
    val verticesLength = buffer.capacity()
    if (offset + count >= verticesLength - idx)
      throw IllegalStateException("A blur batch in once render can only have 1000 shapes (4000 vertices)")

    buffer.put(vertices, offset, count)
    idx += count
  }

  fun pushShape(
    strength: Float,
    x: Float,
    y: Float,
    originX: Float,
    originY: Float,
    width: Float,
    height: Float,
    rotation: Float,
  ){
    val vertices = tmpVert1
    val idx = idx

    if (!Mathf.zero(rotation)) {
      val worldOriginX = x + originX
      val worldOriginY = y + originY
      val fx = -originX
      val fy = -originY
      val fx2 = width - originX
      val fy2 = height - originY

      val cos = Mathf.cosDeg(rotation)
      val sin = Mathf.sinDeg(rotation)

      val x1 = cos*fx - sin*fy + worldOriginX
      val y1 = sin*fx + cos*fy + worldOriginY
      val x2 = cos*fx - sin*fy2 + worldOriginX
      val y2 = sin*fx + cos*fy2 + worldOriginY
      val x3 = cos*fx2 - sin*fy2 + worldOriginX
      val y3 = sin*fx2 + cos*fy2 + worldOriginY
      val x4 = x1 + (x3 - x2)
      val y4 = y3 - (y2 - y1)

      vertices[idx] = x1
      vertices[idx + 1] = y1
      vertices[idx + 2] = strength

      vertices[idx + 3] = x2
      vertices[idx + 4] = y2
      vertices[idx + 5] = strength

      vertices[idx + 6] = x3
      vertices[idx + 7] = y3
      vertices[idx + 8] = strength

      vertices[idx + 9] = x4
      vertices[idx + 10] = y4
      vertices[idx + 11] = strength
    }
    else {
      val fx2 = x + width
      val fy2 = y + height

      vertices[idx] = x
      vertices[idx + 1] = y
      vertices[idx + 2] = strength

      vertices[idx + 3] = x
      vertices[idx + 4] = fy2
      vertices[idx + 5] = strength

      vertices[idx + 6] = fx2
      vertices[idx + 7] = fy2
      vertices[idx + 8] = strength

      vertices[idx + 9] = fx2
      vertices[idx + 10] = y
      vertices[idx + 11] = strength
    }

    buffer.put(vertices, 0, SHAPE_SIZE)
    this.idx += SHAPE_SIZE
  }

  fun getProjection(): Mat {
    return projectionMatrix
  }

  fun getTransform(): Mat {
    return transformMatrix
  }

  fun setProjection(projection: Mat) {
    projectionMatrix.set(projection)
  }

  fun setTransform(transform: Mat) {
    transformMatrix.set(transform)
  }

  private fun commitVertices(back: Texture, shader: Shader){
    back.bind()
    val count = idx/SHAPE_SIZE*6
    mesh.render(shader, Gl.triangles, 0, count)
  }

  fun discard(){
    idx = 0
    buffer.position(0)
  }

  fun render() {
    Gl.depthMask(false)
    Blending.disabled.apply()

    ScreenSampler.toBuffer(pingpong1)
    ScreenSampler.toBuffer(pingpong2)

    buffer.position(0)
    buffer.limit(idx)

    blurShader.bind()
    blurShader.apply()
    combinedMatrix.set(projectionMatrix).mul(transformMatrix)
    blurShader.setUniformMatrix4("u_projTrans", combinedMatrix)

    for (n in 0 until blurLevel) {
      pingpong2.begin()
      blurShader.bind()
      blurShader.setUniformf("u_blurDirection", blurSpace, 0f)
      commitVertices(pingpong1.texture, blurShader)
      pingpong2.end()

      pingpong1.begin()
      blurShader.bind()
      blurShader.setUniformf("u_blurDirection", 0f, blurSpace)
      commitVertices(pingpong2.texture, blurShader)
      pingpong1.end()
    }

    baseShader.bind()
    baseShader.apply()
    baseShader.setUniformMatrix4("u_projTrans", combinedMatrix)
    commitVertices(pingpong1.texture, baseShader)

    buffer.limit(buffer.capacity())
    buffer.position(0)
    idx = 0

    Blending.normal.apply()
  }

  inner class DrawBlur {
    fun rect(strength: Float, x: Float, y: Float, width: Float, height: Float) {
      pushShape(strength, x + width/2f, y + height/2f, 0f, 0f, width, height, 0f)
    }
    fun rect(
      strength: Float,
      x: Float, y: Float, originX: Float, originY: Float,
      width: Float, height: Float,
      scaleX: Float, scaleY: Float,
      rotation: Float,
    ) {
      pushShape(strength, x + width/2f, y + height/2f, width*scaleX, height*scaleY, originX, originY, rotation)
    }
    fun shape(
      x1: Float, y1: Float, s1: Float,
      x2: Float, y2: Float, s2: Float,
      x3: Float, y3: Float, s3: Float,
      x4: Float, y4: Float, s4: Float,
    ){
      val vertices = tmpVert2
      vertices[0] = x1
      vertices[1] = y1
      vertices[1] = s1
      vertices[2] = x2
      vertices[3] = y2
      vertices[3] = s2
      vertices[4] = x3
      vertices[5] = y3
      vertices[5] = s3
      vertices[6] = x4
      vertices[7] = y4
      vertices[7] = s4
      pushShape(vertices)
    }
    fun poly(strength: Float, x: Float, y: Float, sides: Int, radius: Float, rotation: Float) {
      when (sides) {
        3 -> {
          shape(
            x + Angles.trnsx(rotation, radius),
            y + Angles.trnsy(rotation, radius),
            strength,
            x + Angles.trnsx(120f + rotation, radius),
            y + Angles.trnsy(120f + rotation, radius),
            strength,
            x + Angles.trnsx(240f + rotation, radius),
            y + Angles.trnsy(240f + rotation, radius),
            strength,
            x + Angles.trnsx(rotation, radius),
            y + Angles.trnsy(rotation, radius),
            strength,
          )
        }
        4 -> {
          shape(
            x + Angles.trnsx(rotation, radius),
            y + Angles.trnsy(rotation, radius),
            strength,
            x + Angles.trnsx(90f + rotation, radius),
            y + Angles.trnsy(90f + rotation, radius),
            strength,
            x + Angles.trnsx(180f + rotation, radius),
            y + Angles.trnsy(180f + rotation, radius),
            strength,
            x + Angles.trnsx(270f + rotation, radius),
            y + Angles.trnsy(270f + rotation, radius),
            strength,
          )
        }
        else -> {
          val space = 360f/sides

          run {
            var i = 0
            while (i < sides - 1) {
              val px = Angles.trnsx(space*i + rotation, radius)
              val py = Angles.trnsy(space*i + rotation, radius)
              val px2 = Angles.trnsx(space*(i + 1) + rotation, radius)
              val py2 = Angles.trnsy(space*(i + 1) + rotation, radius)
              val px3 = Angles.trnsx(space*(i + 2) + rotation, radius)
              val py3 = Angles.trnsy(space*(i + 2) + rotation, radius)
              shape(
                x, y, strength,
                x + px, y + py, strength,
                x + px2, y + py2, strength,
                x + px3, y + py3, strength
              )
              i += 2
            }
          }

          val mod = sides%2

          if (mod == 0) return

          val i = sides - 1

          val px = Angles.trnsx(space*i + rotation, radius)
          val py = Angles.trnsy(space*i + rotation, radius)
          val px2 = Angles.trnsx(space*(i + 1) + rotation, radius)
          val py2 = Angles.trnsy(space*(i + 1) + rotation, radius)
          shape(
            x, y, strength,
            x + px, y + py, strength,
            x + px2, y + py2, strength,
            x, y, strength,
          )
        }
      }
    }

    fun circle(strength: Float, x: Float, y: Float, radius: Float, sides: Int = 11 + (radius*0.4f).toInt()) {
      poly(strength, x, y, sides, radius, 0f)
    }

    fun circleStrip(
      strength: Float,
      x: Float, y: Float, innerRadius: Float, radius: Float,
      angle: Float, rotate: Float = 0f, sides: Int = 72,
    ) {
      val step = 360f/sides
      val s = (angle/360*sides).toInt()

      val rem = angle - s*step

      for (i in 0 until s) {
        val offX1 = Mathf.cosDeg(rotate + i*step)
        val offY1 = Mathf.sinDeg(rotate + i*step)
        val offX2 = Mathf.cosDeg(rotate + (i + 1)*step)
        val offY2 = Mathf.sinDeg(rotate + (i + 1)*step)

        val inner1 = v1.set(offX1, offY1).scl(innerRadius).add(x, y)
        val inner2 = v2.set(offX2, offY2).scl(innerRadius).add(x, y)
        val out1 = v3.set(offX1, offY1).scl(radius).add(x, y)
        val out2 = v4.set(offX2, offY2).scl(radius).add(x, y)

        shape(
          inner1.x, inner1.y, strength,
          inner2.x, inner2.y, strength,
          out2.x, out2.y, strength,
          out1.x, out1.y, strength,
        )
      }

      if (rem > 0) {
        val offX1 = Mathf.cosDeg(rotate + s*step)
        val offY1 = Mathf.sinDeg(rotate + s*step)
        val offX2 = Mathf.cosDeg(rotate + angle)
        val offY2 = Mathf.sinDeg(rotate + angle)

        val inner1 = v1.set(offX1, offY1).scl(innerRadius).add(x, y)
        val inner2 = v2.set(offX2, offY2).scl(innerRadius).add(x, y)
        val out1 = v3.set(offX1, offY1).scl(radius).add(x, y)
        val out2 = v4.set(offX2, offY2).scl(radius).add(x, y)

        shape(
          inner1.x, inner1.y, strength,
          inner2.x, inner2.y, strength,
          out2.x, out2.y, strength,
          out1.x, out1.y, strength,
        )
      }
    }
  }
}
