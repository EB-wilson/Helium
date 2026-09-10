package helium.graphics

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.graphics.g2d.Lines
import arc.graphics.g2d.TextureRegion
import arc.graphics.gl.FrameBuffer
import arc.graphics.gl.Shader
import arc.math.Mathf
import arc.math.geom.Vec2
import kotlin.math.abs
import kotlin.math.min

@Suppress("DuplicatedCode")
object DrawUtils {
  private val circleOffset24 = prepareCircleOffset(24)
  private val circleOffset36 = prepareCircleOffset(36)
  private val circleOffset60 = prepareCircleOffset(60)

  private val circleVertices24 = prepareCircleVertices(24)
  private val circleVertices36 = prepareCircleVertices(36)
  private val circleVertices60 = prepareCircleVertices(60)

  private fun getVerts(level: Int) = when(level) {
    1 -> circleOffset24 to circleVertices24
    2 -> circleOffset36 to circleVertices36
    3 -> circleOffset60 to circleVertices60
    else -> error("Level $level not supported")
  }

  private fun prepareCircleOffset(sides: Int): FloatArray {
    val vertices = FloatArray(sides * 4)
    val step = 360f/sides

    for (i in 0 until sides) {
      val angle = i*step
      val angle1 = (i + 1)*step
      vertices[i * 4] = Mathf.cosDeg(angle)
      vertices[i * 4 + 1] = Mathf.sinDeg(angle)
      vertices[i * 4 + 2] = Mathf.cosDeg(angle1)
      vertices[i * 4 + 3] = Mathf.sinDeg(angle1)
    }
    return vertices
  }

  private fun prepareCircleVertices(sides: Int): FloatArray{
    val vertices = FloatArray(sides * 24)
    val region: TextureRegion = Core.atlas.white()
    val mcolor = Color.clearFloatBits
    val u = region.u
    val v = region.v

    for (i in 0 until sides) {
      val off = i * 24
      vertices[off + 3] = u
      vertices[off + 4] = v
      vertices[off + 5] = mcolor
      vertices[off + 9] = u
      vertices[off + 10] = v
      vertices[off + 11] = mcolor
      vertices[off + 15] = u
      vertices[off + 16] = v
      vertices[off + 17] = mcolor
      vertices[off + 21] = u
      vertices[off + 22] = v
      vertices[off + 23] = mcolor
    }

    return vertices
  }

  private var drawTasks: Array<DrawTask?> = arrayOfNulls(16)
  private var taskBuffer: Array<FrameBuffer?> = arrayOfNulls(16)

  private val v1 = Vec2()
  private val v2 = Vec2()
  private val v3 = Vec2()
  private val v4 = Vec2()
  private val v5 = Vec2()

  fun fillCircle(x: Float, y: Float, radius: Float, level: Int = 2){
    val color = Draw.getColorPacked()
    val (offset, vertices) = getVerts(level)
    val sides = offset.size/4

    for (i in 0 until sides) {
      val idx = i*24
      val offX1 = offset[i * 4]
      val offY1 = offset[i * 4 + 1]
      val offX2 = offset[i * 4 + 2]
      val offY2 = offset[i * 4 + 3]
      val dxi1 = x + offX1 * radius
      val dyi1 = y + offY1 * radius
      val dxi2 = x + offX2 * radius
      val dyi2 = y + offY2 * radius

      vertices[idx + 0] = dxi1
      vertices[idx + 1] = dyi1
      vertices[idx + 2] = color

      vertices[idx + 6] = x
      vertices[idx + 7] = y
      vertices[idx + 8] = color

      vertices[idx + 12] = x
      vertices[idx + 13] = y
      vertices[idx + 14] = color

      vertices[idx + 18] = dxi2
      vertices[idx + 19] = dyi2
      vertices[idx + 20] = color
    }

    Draw.vert(Core.atlas.white().texture, vertices, 0, vertices.size);
  }

  fun innerCircle(x: Float, y: Float, innerRadius: Float, radius: Float, innerColor: Color, color: Color, level: Int = 2){
    val c1 = innerColor.toFloatBits()
    val c2 = color.toFloatBits()
    val (offset, vertices) = getVerts(level)
    val sides = offset.size/4

    for (i in 0 until sides) {
      val idx = i*24
      val offX1 = offset[i * 4]
      val offY1 = offset[i * 4 + 1]
      val offX2 = offset[i * 4 + 2]
      val offY2 = offset[i * 4 + 3]
      val dxi1 = x + offX1 * innerRadius
      val dyi1 = y + offY1 * innerRadius
      val dxo1 = x + offX1 * radius
      val dyo1 = y + offY1 * radius
      val dxi2 = x + offX2 * innerRadius
      val dyi2 = y + offY2 * innerRadius
      val dxo2 = x + offX2 * radius
      val dyo2 = y + offY2 * radius

      vertices[idx + 0] = dxi1
      vertices[idx + 1] = dyi1
      vertices[idx + 2] = c1

      vertices[idx + 6] = dxo1
      vertices[idx + 7] = dyo1
      vertices[idx + 8] = c2

      vertices[idx + 12] = dxo2
      vertices[idx + 13] = dyo2
      vertices[idx + 14] = c2

      vertices[idx + 18] = dxi2
      vertices[idx + 19] = dyi2
      vertices[idx + 20] = c1
    }

    Draw.vert(Core.atlas.white().texture, vertices, 0, vertices.size);
  }

  fun innerPoly(x: Float, y: Float, sides: Int, radius: Float, rotation: Float, innerColor: Color, color: Color) {
    val c1 = innerColor.toFloatBits()
    val c2 = color.toFloatBits()

    val step = 360f/sides

    for (i in 0 until sides) {
      val angle1 = i*step + rotation
      val angle2 = (i + 1)*step + rotation
      val off1 = Mathf.cosDeg(angle1)
      val off2 = Mathf.sinDeg(angle2)
      val dx1 = x + off1 * radius
      val dy1 = y + off1 * radius
      val dx2 = x + off2 * radius
      val dy2 = y + off2 * radius

      Fill.quad(
        x, y, c1,
        x, y, c1,
        dx1, dy1, c2,
        dx2, dy2, c2,
      )
    }
  }

  fun lineCircle(x: Float, y: Float, radius: Float, level: Int = 2){
    val stroke = Lines.getStroke()
    val color = Draw.getColorPacked()
    val (offset, vertices) = getVerts(level)
    val sides = offset.size/4

    for (i in 0 until sides) {
      val idx = i*24
      val offX1 = offset[i * 4]
      val offY1 = offset[i * 4 + 1]
      val offX2 = offset[i * 4 + 2]
      val offY2 = offset[i * 4 + 3]
      val dxi1 = x + offX1 * (radius + stroke)
      val dyi1 = y + offY1 * (radius + stroke)
      val dxo1 = x + offX1 * (radius - stroke)
      val dyo1 = y + offY1 * (radius - stroke)
      val dxi2 = x + offX2 * (radius + stroke)
      val dyi2 = y + offY2 * (radius + stroke)
      val dxo2 = x + offX2 * (radius - stroke)
      val dyo2 = y + offY2 * (radius - stroke)

      vertices[idx + 0] = dxi1
      vertices[idx + 1] = dyi1
      vertices[idx + 2] = color

      vertices[idx + 6] = dxo1
      vertices[idx + 7] = dyo1
      vertices[idx + 8] = color

      vertices[idx + 12] = dxo2
      vertices[idx + 13] = dyo2
      vertices[idx + 14] = color

      vertices[idx + 18] = dxi2
      vertices[idx + 19] = dyi2
      vertices[idx + 20] = color
    }

    Draw.vert(Core.atlas.white().texture, vertices, 0, vertices.size);
  }

  fun arc(x: Float, y: Float, radius: Float, innerAngel: Float, rotate: Float, scaleFactor: Float = 0.8f) {
    val sides = 40 + (radius*scaleFactor).toInt()

    val step = 360f/sides
    val sing = if (innerAngel > 0) 1 else -1
    val inner = min(abs(innerAngel), 360f)

    val vec = v1

    val n = (inner/step).toInt()
    val rem = inner - n*step
    Lines.beginLine()

    vec.set(radius, 0f).setAngle(rotate)
    Lines.linePoint(x + vec.x, y + vec.y)

    for (i in 0 until n) {
      vec.set(radius, 0f).setAngle((i + 1)*step*sing + rotate)
      Lines.linePoint(x + vec.x, y + vec.y)
    }

    if (rem > 0.1f) {
      vec.set(radius, 0f).setAngle(inner*sing + rotate)
      Lines.linePoint(x + vec.x, y + vec.y)
    }

    Lines.endLine(inner >= 360f - 0.01f)
  }

  fun dashCircle(
    x: Float, y: Float, radius: Float,
    dashes: Int = 8,
    totalDashDeg: Float = 180f,
    rotate: Float = 0f,
  ) {
    if (Mathf.equal(totalDashDeg, 0f)) return

    val totalTransDeg = 360f - totalDashDeg
    val dashDeg = totalDashDeg / dashes
    val transDeg = totalTransDeg / dashes
    val step = dashDeg + transDeg
    val sides = (360/dashDeg).toInt()*2

    for (i in 0 until dashes) {
      Lines.arc(
        x, y, radius,
        dashDeg/360f, rotate + i*step,
        sides
      )
    }
  }

  fun dashPoly(
    x: Float, y: Float, edges: Int,
    radius: Float, rotation: Float,
    dashOffset: Float, dashes: Int,
  ) {

  }

  fun flowDashLine(
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    dashOffset: Float, dashes: Int
  ) {
    val len = Mathf.dst(x1, y1, x2, y2)
    val step = len/dashes
    val half = step/2f

    val offsetMod = dashOffset%step

    for (i in 0 until dashes) {
      val from = i*step + offsetMod
      val to = from + half
    }
  }

  fun circleFan(
    x: Float, y: Float, radius: Float,
    angle: Float, rotate: Float = 0f, sides: Int = 72,
  ){
    val step = 360f/sides
    val s = (angle/360*sides).toInt()

    val rem = angle - s*step

    for (i in 0 until s) {
      val offX1 = Mathf.cosDeg(rotate + i*step)
      val offY1 = Mathf.sinDeg(rotate + i*step)
      val offX2 = Mathf.cosDeg(rotate + (i + 1)*step)
      val offY2 = Mathf.sinDeg(rotate + (i + 1)*step)

      v1.set(offX1, offY1).scl(radius).add(x, y)
      v2.set(offX2, offY2).scl(radius).add(x, y)

      Fill.quad(
        x, y,
        v1.x, v1.y,
        v2.x, v2.y,
        x, y
      )
    }

    if (rem > 0) {
      val offX1 = Mathf.cosDeg(rotate + s*step)
      val offY1 = Mathf.sinDeg(rotate + s*step)
      val offX2 = Mathf.cosDeg(rotate + angle)
      val offY2 = Mathf.sinDeg(rotate + angle)

      v1.set(offX1, offY1).scl(radius).add(x, y)
      v2.set(offX2, offY2).scl(radius).add(x, y)

      Fill.quad(
        x, y,
        v1.x, v1.y,
        v2.x, v2.y,
        x, y
      )
    }
  }

  fun circleStrip(
    x: Float, y: Float, innerRadius: Float, radius: Float,
    angle: Float, rotate: Float = 0f,
    innerColor: Color = Draw.getColor(),
    outerColor: Color = innerColor,
    sides: Int = 72,
  ){
    val step = 360f/sides
    val s = (angle/360*sides).toInt()
    val innerC = innerColor.toFloatBits()
    val outerC = outerColor.toFloatBits()

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

      Fill.quad(
        inner1.x, inner1.y, innerC,
        inner2.x, inner2.y, innerC,
        out2.x, out2.y, outerC,
        out1.x, out1.y, outerC,
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

      Fill.quad(
        inner1.x, inner1.y, innerC,
        inner2.x, inner2.y, innerC,
        out2.x, out2.y, outerC,
        out1.x, out1.y, outerC,
      )
    }
  }

  fun circleFrame(
    x: Float, y: Float, innerRadius: Float, radius: Float,
    angle: Float, rotate: Float = 0f, sides: Int = 72,
    padCap: Boolean = false,
  ){
    val offX1 = Mathf.cosDeg(rotate)
    val offY1 = Mathf.sinDeg(rotate)
    val offX2 = Mathf.cosDeg(rotate + angle)
    val offY2 = Mathf.sinDeg(rotate + angle)

    var inner1: Vec2
    var inner2: Vec2
    var out1: Vec2
    var out2: Vec2

    if (padCap) {
      val off = Lines.getStroke()/2f
      v5.set(off, off).rotate(rotate)
      inner1 = v1.set(offX1, offY1).scl(innerRadius).add(v5).add(x, y)
      v5.set(-off, off).rotate(rotate)
      out1 = v3.set(offX1, offY1).scl(radius).add(v5).add(x, y)
      v5.set(off, -off).rotate(rotate + angle)
      inner2 = v2.set(offX2, offY2).scl(innerRadius).add(v5).add(x, y)
      v5.set(-off, -off).rotate(rotate + angle)
      out2 = v4.set(offX2, offY2).scl(radius).add(v5).add(x, y)

      Lines.arc(
        x, y, innerRadius + off, angle/360f, rotate, sides
      )
      Lines.arc(
        x, y, radius - off, angle/360f, rotate, sides
      )
    }
    else {
      inner1 = v1.set(offX1, offY1).scl(innerRadius).add(x, y)
      inner2 = v2.set(offX2, offY2).scl(innerRadius).add(x, y)
      out1 = v3.set(offX1, offY1).scl(radius).add(x, y)
      out2 = v4.set(offX2, offY2).scl(radius).add(x, y)

      Lines.arc(
        x, y, innerRadius, angle/360f, rotate, sides
      )
      Lines.arc(
        x, y, radius, angle/360f, rotate, sides
      )
    }

    Lines.line(
      inner1.x, inner1.y,
      out1.x, out1.y,
    )
    Lines.line(
      inner2.x, inner2.y,
      out2.x, out2.y,
    )
  }

  fun drawLinesRadio(
    centerX: Float, centerY: Float,
    innerRadius: Float, radius: Float,
    lines: Int, rotate: Float = 0f,
    totalDeg: Float = 360f, cap: Boolean = false,
  ){
    val angleStep = totalDeg/lines

    for (i in 0 until if (cap) lines + 1 else lines) {
      val angle = i * angleStep + rotate

      val cos = Mathf.cosDeg(angle)
      val sin = Mathf.sinDeg(angle)
      val innerOffX = innerRadius * cos
      val innerOffY = innerRadius * sin
      val outerOffX = radius * cos
      val outerOffY = radius * sin

      Lines.line(
        centerX + innerOffX,
        centerY + innerOffY,
        centerX + outerOffX,
        centerY + outerOffY,
      )
    }
  }

  /**发布缓存的任务并在首次发布时的z轴时进行绘制，传递的一些参数只在初始化时起了效果，之后都被选择性的无视了
   *
   * @param taskId 任务的标识ID，用于区分任务缓存
   * @param target 传递给绘制任务的数据目标，这是为了优化lambda的内存而添加的，避免产生大量闭包的lambda实例造成不必要的内存占用
   * @param drawFirst **选择性的参数，若任务已初始化，这个参数无效**，用于声明这个任务组在执行前要进行的操作
   * @param drawLast **选择性的参数，若任务已初始化，这个参数无效**，用于声明这个任务组在完成主绘制后要执行的操作
   * @param draw 添加到任务缓存的绘制任务，即此次绘制的操作
   */
  @Suppress("UNCHECKED_CAST")
  fun <T: Any, D: Any> drawTask(
    taskId: Int,
    target: T? = null,
    defTarget: D? = null,
    drawFirst: DrawAcceptor<D>? = null,
    drawLast: DrawAcceptor<D>? = null,
    draw: DrawAcceptor<T>,
  ) {
    while (taskId >= drawTasks.size) {
      drawTasks = drawTasks.copyOf(drawTasks.size*2)
    }

    var task = drawTasks[taskId]
    if (task == null) {
      task = DrawTask()
      drawTasks[taskId] = task
    }
    if (!task.init) {
      task.defaultFirstTask = drawFirst as DrawAcceptor<Any>
      task.defaultLastTask = drawLast as DrawAcceptor<Any>
      task.defaultTarget = defTarget
      task.init = true
      Draw.draw(Draw.z()) { task.flush() }
    }
    task.addTask(target, draw)
  }

  /**发布缓存的任务并在首次发布时的z轴时进行绘制，传递的一些参数只在初始化时起了效果，之后都被选择性的无视了
   *
   * @param taskID 任务的标识id，用于区分任务缓存
   * @param target 递给绘制任务的数据目标，这是为了优化lambda的内存而添加的，避免产生大量闭包的lambda实例造成不必要的内存占用
   * @param shader **选择性的参数，若任务已初始化，这个参数无效**，在这组任务绘制时使用的着色器
   * @param draw 添加到任务缓存的绘制任务，即此次绘制的操作
   */
  fun <T: Any, S: Shader> drawTask(taskID: Int, target: T, shader: S, draw: DrawAcceptor<T>) {
    while (taskID >= taskBuffer.size) {
      taskBuffer = taskBuffer.copyOf(taskBuffer.size*2)
    }

    var buffer = taskBuffer[taskID]
    if (buffer == null) {
      buffer = FrameBuffer()
      taskBuffer[taskID] = buffer
    }
    drawTask(taskID, target, shader, { _ ->
      buffer.resize(Core.graphics.width, Core.graphics.height)
      buffer.begin(Color.clear)
    }, { e ->
      buffer.end()
      buffer.blit(e)
    }, draw)
  }
}

private val none = Any()
class DrawTask {
  var defaultFirstTask: DrawAcceptor<Any>? = null
  var defaultLastTask: DrawAcceptor<Any>? = null
  var defaultTarget: Any? = null

  var tasks: Array<DrawAcceptor<Any>?> = arrayOfNulls(16)
  var dataTarget: Array<Any?> = arrayOfNulls(16)
  var taskCounter: Int = 0
  var init: Boolean = false

  @Suppress("UNCHECKED_CAST")
  fun <T: Any> addTask(dataAcceptor: T?, task: DrawAcceptor<T>) {
    if (tasks.size <= taskCounter) {
      tasks = tasks.copyOf(tasks.size + 1)
      dataTarget = dataTarget.copyOf(tasks.size)
    }

    tasks[taskCounter] = task as DrawAcceptor<Any>
    dataTarget[taskCounter++] = dataAcceptor
  }

  fun flush() {
    defaultFirstTask?.draw(defaultTarget?:none)
    for (i in 0..<taskCounter) {
      tasks[i]?.draw(dataTarget[i]?:none)
    }
    defaultLastTask?.draw(defaultTarget?:none)

    taskCounter = 0
    init = false
  }
}

fun interface DrawAcceptor<T> {
  fun draw(accept: T)
}