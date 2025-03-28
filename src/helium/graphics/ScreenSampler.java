package helium.graphics;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.GL30;
import arc.graphics.Gl;
import arc.graphics.g2d.Draw;
import arc.graphics.gl.FrameBuffer;
import arc.graphics.gl.Shader;
import mindustry.game.EventType;

import static helium.graphics.HeShaders.baseScreen;

public class ScreenSampler {
  private static final FrameBuffer worldBuffer = new FrameBuffer(), uiBuffer = new FrameBuffer();

  private static FrameBuffer currBuffer;
  private static boolean activity;
  public static void setup() {
    if (activity) throw new RuntimeException("forbid setup sampler twice");

    Events.run(EventType.Trigger.preDraw, ScreenSampler::beginWorld);
    Events.run(EventType.Trigger.postDraw, ScreenSampler::endWorld);

    Events.run(EventType.Trigger.uiDrawBegin, ScreenSampler::beginUI);
    Events.run(EventType.Trigger.uiDrawEnd, ScreenSampler::endUI);
    activity = true;
  }

  private static void beginWorld(){
    currBuffer = worldBuffer;
    worldBuffer.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
    worldBuffer.begin(Color.clear);
  }
  private static void endWorld(){
    currBuffer = null;
    worldBuffer.end();
  }

  private static void beginUI(){
    currBuffer = uiBuffer;
    uiBuffer.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
    uiBuffer.begin(Color.clear);

    blitBuffer(worldBuffer, uiBuffer);
  }

  private static void endUI(){
    currBuffer = null;
    uiBuffer.end();
    blitBuffer(uiBuffer, null);
  }

  private static void blitBuffer(FrameBuffer from, FrameBuffer to){
    if (Core.gl30 == null) {
      from.blit(baseScreen);
    }
    else {
      Gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.getFramebufferHandle());
      Gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to == null? 0: to.getFramebufferHandle());
      Core.gl30.glBlitFramebuffer(
          0, 0, from.getWidth(), from.getHeight(),
          0, 0,
          to == null? Core.graphics.getWidth(): to.getWidth(),
          to == null? Core.graphics.getHeight(): to.getHeight(),
          Gl.colorBufferBit, Gl.nearest
      );
    }
  }

  /**将当前的屏幕纹理使用传入的着色器绘制到屏幕上*/
  public static void blit(Shader shader) {
    blit(shader, 0);
  }

  /**将当前的屏幕纹理使用传入的着色器绘制到屏幕上
   * @param unit 屏幕采样纹理绑定的纹理单元*/
  public static void blit(Shader shader, int unit) {
    if (currBuffer == null) throw new IllegalStateException("currently no buffer bound");

    currBuffer.getTexture().bind(unit);
    Draw.blit(shader);
  }

  /**将当前屏幕纹理转存到一个{@linkplain FrameBuffer 帧缓冲区}，这将成为一份拷贝，可用于暂存屏幕内容
   *
   * @param target 用于转存屏幕纹理的目标缓冲区
   * @param clear 在转存之前是否清空帧缓冲区*/
  public static void getToBuffer(FrameBuffer target, boolean clear){
    if (currBuffer == null) throw new IllegalStateException("currently no buffer bound");

    if (clear){
      target.begin(Color.clear);
      target.end();
    }

    Gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, currBuffer.getFramebufferHandle());
    Gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.getFramebufferHandle());
    Core.gl30.glBlitFramebuffer(
        0, 0, currBuffer.getWidth(), currBuffer.getHeight(),
        0, 0, target.getWidth(), target.getHeight(),
        Gl.colorBufferBit, Gl.nearest
    );
  }
}
