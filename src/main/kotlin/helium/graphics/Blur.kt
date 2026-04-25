package helium.graphics

import arc.Core
import arc.graphics.Blending
import arc.graphics.Color
import arc.graphics.Gl
import arc.graphics.Pixmap
import arc.graphics.g2d.Draw
import arc.graphics.gl.FrameBuffer
import arc.graphics.gl.Shader
import helium.Helium
import universe.graphic.ScreenSampler

class Blur {
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
  private val stencil: FrameBuffer = FrameBuffer(
    Pixmap.Format.rgba8888,
    2, 2,
    false,
    false
  )
  private val pingpong1: FrameBuffer = FrameBuffer(
    Pixmap.Format.rgba8888,
    2, 2,
    false,
    true
  )
  private val pingpong2: FrameBuffer = FrameBuffer(
    Pixmap.Format.rgba8888,
    2, 2,
    false,
    true
  )

  var blurSpace: Float = 1.5f
  var blurScl: Int = 2
  var blurLevel: Int = 2

  fun drawBlur(
    block: () -> Unit,
  ) {
    stencil.resize(Core.graphics.width / blurScl, Core.graphics.height / blurScl)
    pingpong1.resize(Core.graphics.width / blurScl, Core.graphics.height / blurScl)
    pingpong2.resize(Core.graphics.width / blurScl, Core.graphics.height / blurScl)

    stencil.begin(Color.clear)
    block()
    stencil.end()

    Gl.enable(Gl.stencilTest)
    Gl.stencilMask(0xFF)
    Gl.stencilFunc(Gl.always, 1, 0xFF)
    Gl.stencilOp(Gl.keep, Gl.keep, Gl.replace)

    pingpong1.begin()
    Gl.clear(Gl.stencilBufferBit or Gl.colorBufferBit)
    block()
    pingpong1.end()

    pingpong2.begin()
    Gl.clear(Gl.stencilBufferBit or Gl.colorBufferBit)
    block()
    pingpong2.end()

    Gl.disable(Gl.stencilTest)

    render()
  }

  private fun render() {
    Blending.disabled.apply()

    ScreenSampler.toBuffer(pingpong1)
    ScreenSampler.toBuffer(pingpong2)

    blurShader.bind()
    blurShader.apply()
    blurShader.setUniformi("u_stencil", 0)
    blurShader.setUniformi("u_sample", 1)
    blurShader.setUniformf("u_screenSize", Core.graphics.width.toFloat(), Core.graphics.height.toFloat())

    Gl.enable(Gl.stencilTest)
    Gl.stencilMask(0x00)
    Gl.stencilFunc(Gl.equal, 1, 0xFF)
    Gl.stencilOp(Gl.keep, Gl.keep, Gl.keep)
    (0 until blurLevel).forEach { n ->
      pingpong2.begin()
      blurShader.bind()
      blurShader.setUniformf("u_blurDirection", blurSpace, 0f)
      stencil.texture.bind(0)
      pingpong1.texture.bind(1)
      Draw.blit(blurShader)
      pingpong2.end()

      pingpong1.begin()
      blurShader.bind()
      blurShader.setUniformf("u_blurDirection", 0f, blurSpace)
      stencil.texture.bind(0)
      pingpong2.texture.bind(1)
      Draw.blit(blurShader)
      pingpong1.end()
    }
    Gl.disable(Gl.stencilTest)

    pingpong1.blit(baseShader)

    Blending.normal.apply()
  }
}
