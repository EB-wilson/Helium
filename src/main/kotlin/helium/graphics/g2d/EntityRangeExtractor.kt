package helium.graphics.g2d

import arc.Core
import arc.files.Fi
import arc.graphics.Color
import arc.graphics.Texture
import arc.graphics.gl.FrameBuffer
import arc.graphics.gl.Shader
import arc.util.Time
import helium.Helium.Companion.getInternalFile

class EntityRangeExtractor(val low: Boolean) {
  companion object {
    private val internalShaderDir: Fi = getInternalFile("shaders")
  }

  private val buffer = FrameBuffer().also { it.texture.setFilter(Texture.TextureFilter.nearest) }
  init {
    setupShader()
  }

  private var capturing = false

  var stroke = 2f
  var alpha = 0.075f
  var boundColor = Color(1f, 1f, 1f, 1f)

  private lateinit var extractShader: Shader

  private fun setupShader() {
    extractShader = Shader(
      Core.files.internal("shaders/screenspace.vert"),
      internalShaderDir.child(if (low) "entity_range_low.frag" else "entity_range.frag")
    )
    buffer.resize(Core.graphics.width, Core.graphics.height)
  }

  fun capture(){
    if (capturing) throw IllegalStateException("capturing already running")

    buffer.resize(Core.graphics.width, Core.graphics.height)
    buffer.begin(Color.clear)
    capturing = true
  }

  fun render(){
    if (!capturing) throw IllegalStateException("capturing not started")

    capturing = false

    buffer.end()
    extractShader.also{
      it.bind()
      it.apply()
      it.setUniformf("u_time", Time.time)
      it.setUniformf("u_resolution", Core.camera.width, Core.camera.height)
      it.setUniformf(
        "u_campos",
        Core.camera.position.x - Core.camera.width/2,
        Core.camera.position.y - Core.camera.height/2
      )
      it.setUniformf("u_stroke", stroke)
      it.setUniformf("u_alpha", alpha)
      it.setUniformf("u_color", boundColor)
      it.setUniformi("u_texture", 0)
      buffer.blit(it)
    }
  }
}