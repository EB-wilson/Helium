package helium.graphics

import arc.Core
import arc.files.Fi
import arc.graphics.gl.Shader
import helium.Helium.Companion.getInternalFile
import helium.graphics.g2d.EntityRangeExtractor

object HeShaders {
  lateinit var entityRangeRenderer: EntityRangeExtractor
  lateinit var lowEntityRangeRenderer: EntityRangeExtractor

  lateinit var baseScreen: Shader

  private val internalShaderDir: Fi = getInternalFile("shaders")

  fun load() {
    entityRangeRenderer = EntityRangeExtractor(false)
    lowEntityRangeRenderer = EntityRangeExtractor(true)

    baseScreen = Shader(
      Core.files.internal("shaders/screenspace.vert"),
      internalShaderDir.child("dist_base.frag")
    )
  }
}
