package helium.graphics

import arc.Core
import arc.Events
import arc.graphics.Color
import arc.graphics.GL30
import arc.graphics.Gl
import arc.graphics.g2d.Draw
import arc.graphics.gl.FrameBuffer
import arc.graphics.gl.Shader
import arc.util.serialization.Jval
import mindustry.game.EventType

object ScreenSampler {
  private var worldBuffer: FrameBuffer? = null
  private var uiBuffer: FrameBuffer? = null

  private var currBuffer: FrameBuffer? = null
  private var activity = false

  fun resetMark() {
    Core.settings.remove("sampler.setup")
  }

  fun setup() {
    if (activity) throw RuntimeException("forbid setup sampler twice")

    var e = Jval.read(Core.settings.getString("sampler.setup", "{enabled: false}"))

    if (!e.getBool("enabled", false)) {
      e = Jval.newObject()
      e.put("enabled", true)
      e.put("className", ScreenSampler::class.java.name)
      e.put("worldBuffer", "worldBuffer")
      e.put("uiBuffer", "uiBuffer")

      worldBuffer = FrameBuffer()
      uiBuffer = FrameBuffer()

      Core.settings.put("sampler.setup", e.toString())

      Events.run(EventType.Trigger.preDraw) { beginWorld() }
      Events.run(EventType.Trigger.postDraw) { endWorld() }

      Events.run(EventType.Trigger.uiDrawBegin) { beginUI() }
      Events.run(EventType.Trigger.uiDrawEnd) { endUI() }
    }
    else {
      val className = e.getString("className")
      val worldBufferName = e.getString("worldBuffer")
      val uiBufferName = e.getString("uiBuffer")
      val clazz = Class.forName(className)
      val worldBufferField = clazz.getDeclaredField(worldBufferName)
      val uiBufferField = clazz.getDeclaredField(uiBufferName)

      worldBufferField.isAccessible = true
      uiBufferField.isAccessible = true
      worldBuffer = worldBufferField[null] as FrameBuffer
      uiBuffer = uiBufferField[null] as FrameBuffer

      Events.run(EventType.Trigger.preDraw) { currBuffer = worldBuffer }
      Events.run(EventType.Trigger.postDraw) { currBuffer = null }
      Events.run(EventType.Trigger.uiDrawBegin) { currBuffer = uiBuffer }
      Events.run(EventType.Trigger.uiDrawEnd) { currBuffer = null }
    }

    activity = true
  }

  private fun beginWorld() {
    currBuffer = worldBuffer
    worldBuffer!!.resize(Core.graphics.width, Core.graphics.height)
    worldBuffer!!.begin(Color.clear)
  }

  private fun endWorld() {
    currBuffer = null
    worldBuffer!!.end()
  }

  private fun beginUI() {
    currBuffer = uiBuffer
    uiBuffer!!.resize(Core.graphics.width, Core.graphics.height)
    uiBuffer!!.begin(Color.clear)
    blitBuffer(worldBuffer!!, uiBuffer)
  }

  private fun endUI() {
    currBuffer = null
    uiBuffer!!.end()
    blitBuffer(uiBuffer!!, null)
  }

  /**将当前的屏幕纹理使用传入的着色器绘制到屏幕上
   * @param unit 屏幕采样纹理绑定的纹理单元
   */
  fun blit(shader: Shader, unit: Int = 0) {
    checkNotNull(currBuffer) { "currently no buffer bound" }

    currBuffer!!.texture.bind(unit)
    Draw.blit(shader)
  }

  private fun blitBuffer(from: FrameBuffer, to: FrameBuffer?) {
    if (Core.gl30 == null) {
      from.blit(HeShaders.baseScreen)
    }
    else {
      Gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.framebufferHandle)
      Gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to?.framebufferHandle ?: 0)
      Core.gl30.glBlitFramebuffer(
        0, 0, from.width, from.height,
        0, 0,
        to?.width ?: Core.graphics.width,
        to?.height ?: Core.graphics.height,
        Gl.colorBufferBit, Gl.nearest
      )
    }
  }

  /**将当前屏幕纹理转存到一个[帧缓冲区][FrameBuffer]，这将成为一份拷贝，可用于暂存屏幕内容
   *
   * @param target 用于转存屏幕纹理的目标缓冲区
   * @param clear 在转存之前是否清空帧缓冲区
   */
  fun getToBuffer(target: FrameBuffer, clear: Boolean) {
    checkNotNull(currBuffer) { "currently no buffer bound" }

    if (clear) target.begin(Color.clear)
    else target.begin()

    Gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, currBuffer!!.framebufferHandle)
    Gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.framebufferHandle)
    Core.gl30.glBlitFramebuffer(
      0, 0, currBuffer!!.width, currBuffer!!.height,
      0, 0, target.width, target.height,
      Gl.colorBufferBit, Gl.nearest
    )

    target.end()
  }
}
