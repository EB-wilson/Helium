package helium.graphics

import arc.Core
import arc.graphics.Blending
import arc.graphics.Color
import arc.graphics.gl.FrameBuffer
import arc.graphics.gl.Shader
import arc.util.Log
import kotlin.math.abs

val DEf_A: FloatArray = floatArrayOf(
  0.008697324f, 0.035994977f, 0.10936101f,
  0.21296589f,  0.26596153f,  0.21296589f,
  0.10936101f,  0.035994977f, 0.008697324f,
)
val DEf_B: FloatArray = floatArrayOf(
  0.044408645f, 0.07799442f, 0.11599662f,
  0.16730806f,  0.1885769f,  0.16730806f,
  0.11599662f,  0.07799442f, 0.044408645f,
)
val DEf_C: FloatArray = floatArrayOf(
  0.0045418483f, 0.053999867f, 0.24198672f,
                 0.39894313f,
  0.24198672f,   0.053999867f, 0.0045418483f,
)
val DEf_D: FloatArray = floatArrayOf(
  0.02454185f, 0.06399987f, 0.2519867f,
               0.3189431f,
  0.2519867f,  0.06399987f, 0.02454185f,
)
val DEf_E: FloatArray = floatArrayOf(
  0.01961571f, 0.20542552f,
         0.55991757f,
  0.20542552f, 0.01961571f,
)
val DEf_F: FloatArray = floatArrayOf(
  0.07027027f, 0.31621623f,
         0.22702703f,
  0.31621623f, 0.07027027f,
)
val DEf_G: FloatArray = floatArrayOf(
  0.20798193f,
  0.68403614f,
  0.20798193f,
)
val DEf_H: FloatArray = floatArrayOf(
  0.25617367f,
  0.4876527f,
  0.25617367f,
)

private const val vertTemplate =
  """
  attribute vec4 a_position;
  attribute vec2 a_texCoord0;
  
  uniform vec2 u_dir;
  uniform vec2 u_size;
  
  varying vec2 v_texCoords;
  
  %varying%
  
  void main(){
    vec2 len = u_dir/u_size;
  
    v_texCoords = a_texCoord0;
    %assignVar%
    gl_Position = a_position;
  }
  """
private const val fragmentTemplate =
  """
  uniform lowp sampler2D u_texture0;
  uniform lowp sampler2D u_texture1;
  
  uniform lowp float u_def_alpha;
  
  varying vec2 v_texCoords;
  
  %varying%
  
  void main(){
    vec4 blur = texture2D(u_texture0, v_texCoords);
    vec3 color = texture2D(u_texture1, v_texCoords).rgb;
  
    if(blur.a > 0.0){
      vec3 blurColor =
          %convolution%
  
      gl_FragColor.rgb = mix(color, blurColor, blur.a);
      gl_FragColor.a = 1.0;
    }
    else{
      gl_FragColor.rgb = color;
      gl_FragColor.a = u_def_alpha;
    }
  }
  """

class Blur(vararg convolutions: Float = DEf_F) {
  var blurShader: Shader
  var buffer: FrameBuffer
  var pingpong: FrameBuffer

  var capturing: Boolean = false

  var blurScl: Int = 4
  var blurSpace: Float = 2.16f

  init {
    blurShader = genShader(*convolutions)

    buffer = FrameBuffer()
    pingpong = FrameBuffer()

    blurShader.bind()
    blurShader.setUniformi("u_texture0", 0)
    blurShader.setUniformi("u_texture1", 1)
  }

  private fun genShader(vararg convolutions: Float): Shader {
    require(convolutions.size%2 == 1) { "convolution numbers length must be odd number!" }

    val convLen = convolutions.size

    val varyings = StringBuilder()
    val assignVar = StringBuilder()
    val convolution = StringBuilder()

    var c = 0
    val half = convLen/2
    for (v in convolutions) {
      varyings.append("varying vec2 v_texCoords")
        .append(c)
        .append(";")
        .append("\n")

      assignVar.append("v_texCoords")
        .append(c)
        .append(" = ")
        .append("a_texCoord0")
      if (c - half != 0) {
        assignVar.append(if (c - half > 0) "+" else "-")
          .append(abs((c.toFloat() - half).toDouble()).toFloat())
          .append("*len")
      }
      assignVar.append(";")
        .append("\n")
        .append("  ")

      if (c > 0) convolution.append("        + ")
      convolution.append(v)
        .append("*texture2D(u_texture1, v_texCoords")
        .append(c)
        .append(")")
        .append(".rgb")
        .append("\n")

      c++
    }
    convolution.append(";")

    val vertexShader = vertTemplate
      .replace("%varying%", varyings.toString())
      .replace("%assignVar%", assignVar.toString())
    val fragmentShader = fragmentTemplate
      .replace("%varying%", varyings.toString())
      .replace("%convolution%", convolution.toString())

    return Shader(vertexShader, fragmentShader)
  }

  fun resize(width: Int, height: Int) {
    val w = width/blurScl
    val h = height/blurScl

    buffer.resize(w, h)
    pingpong.resize(w, h)

    blurShader.bind()
    blurShader.setUniformf("u_size", w.toFloat(), h.toFloat())
  }

  fun capture() {
    if (!capturing) {
      buffer.begin(Color.clear)

      capturing = true
    }
  }

  fun render() {
    if (!capturing) return
    capturing = false
    buffer.end()

    Blending.disabled.apply()

    blurShader.bind()
    blurShader.apply()
    blurShader.setUniformf("u_dir", blurSpace, 0f)
    blurShader.setUniformf("u_def_alpha", 1f)
    buffer.texture.bind(0)

    pingpong.begin()
    ScreenSampler.blit(blurShader, 1)
    pingpong.end()

    blurShader.setUniformf("u_dir", 0f, blurSpace)
    blurShader.setUniformf("u_def_alpha", 0f)
    pingpong.texture.bind(1)

    Blending.normal.apply()
    buffer.blit(blurShader)
  }

  fun directDraw(draw: Runnable) {
    resize(Core.graphics.width, Core.graphics.height)
    capture()
    draw.run()
    render()
  }
}
