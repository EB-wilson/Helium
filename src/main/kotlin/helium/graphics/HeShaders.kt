package helium.graphics

import arc.Core
import arc.files.Fi
import arc.graphics.gl.Shader
import helium.Helium.Companion.getInternalFile

object HeShaders {
  lateinit var baseScreen: Shader

  private val internalShaderDir: Fi = getInternalFile("shaders")

  fun load() {
    baseScreen = Shader(
      Core.files.internal("shaders/screenspace.vert"),
      internalShaderDir.child("dist_base.frag")
    )
  }
}
