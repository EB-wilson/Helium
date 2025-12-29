package helium.graphics;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.GL30;
import arc.graphics.Gl;
import arc.graphics.g2d.Draw;
import arc.graphics.gl.FrameBuffer;
import arc.graphics.gl.GLFrameBuffer;
import arc.graphics.gl.Shader;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.graphics.Layer;
import mindustry.graphics.Pixelator;

import java.lang.reflect.Field;

public class ScreenSamplerJ {
  private static final Field lastBoundFramebufferField;
  private static final Field bufferField;

  private static FrameBuffer pixelatorBuffer;

  private static FrameBuffer worldBuffer = null;
  private static FrameBuffer uiBuffer = null;

  private static FrameBuffer currBuffer = null;
  private static boolean activity = false;

  static {
    try {
      lastBoundFramebufferField = GLFrameBuffer.class.getDeclaredField("lastBoundFramebuffer");
      lastBoundFramebufferField.setAccessible(true);

      bufferField = Pixelator.class.getDeclaredField("buffer");
      bufferField.setAccessible(true);
    } catch (NoSuchFieldException e) {
      throw new RuntimeException("Failed to initialize reflection fields", e);
    }
  }

  private static void ensurePixelatorBufferInitialized() {
    if (pixelatorBuffer == null) {
      try {
        Pixelator pixelator = Vars.renderer.pixelator;
        pixelatorBuffer = (FrameBuffer) bufferField.get(pixelator);
      } catch (IllegalAccessException e) {
        throw new RuntimeException("Failed to access pixelator buffer", e);
      }
    }
  }

  /** 设置采样阶段标记，请在你的mod主类型的构造函数中调用此方法 */
  public static void resetMark() {
    Core.settings.remove("sampler.setup");
  }

  /** 初始化采样阶段标记，请在你的mod主类型中的初始化阶段调用此方法 */
  public static void setup() {
    if (activity) throw new RuntimeException("forbid setup sampler twice");

    Jval e = Jval.read(Core.settings.getString("sampler.setup", "{enabled: false}"));

    if (!e.getBool("enabled", false)) {
      e = Jval.newObject();
      e.put("enabled", true);
      e.put("className", ScreenSampler.class.getName());
      e.put("worldBuffer", "worldBuffer");
      e.put("uiBuffer", "uiBuffer");

      worldBuffer = new FrameBuffer();
      uiBuffer = new FrameBuffer();

      Core.settings.put("sampler.setup", e.toString());

      Events.run(EventType.Trigger.draw, () -> {
        Draw.draw(Layer.min - 0.001f, ScreenSamplerJ::beginWorld);
        Draw.draw(Layer.end + 0.001f, ScreenSamplerJ::endWorld);
      });

      Events.run(EventType.Trigger.uiDrawBegin, ScreenSamplerJ::beginUI);
      Events.run(EventType.Trigger.uiDrawEnd, ScreenSamplerJ::endUI);
    } else {
      String className = e.getString("className");
      String worldBufferName = e.getString("worldBuffer");
      String uiBufferName = e.getString("uiBuffer");

      try {
        Class<?> clazz = Class.forName(className);
        Field worldBufferField = clazz.getDeclaredField(worldBufferName);
        Field uiBufferField = clazz.getDeclaredField(uiBufferName);

        worldBufferField.setAccessible(true);
        uiBufferField.setAccessible(true);
        worldBuffer = (FrameBuffer) worldBufferField.get(null);
        uiBuffer = (FrameBuffer) uiBufferField.get(null);

        Events.run(EventType.Trigger.preDraw, () -> currBuffer = worldBuffer);
        Events.run(EventType.Trigger.postDraw, () -> currBuffer = null);
        Events.run(EventType.Trigger.uiDrawBegin, () -> currBuffer = uiBuffer);
        Events.run(EventType.Trigger.uiDrawEnd, () -> currBuffer = null);
      } catch (Exception ex) {
        throw new RuntimeException("Failed to setup buffers from reflection", ex);
      }
    }

    activity = true;
  }

  private static void beginWorld() {
    if (Vars.renderer.pixelate) {
      ensurePixelatorBufferInitialized();
      currBuffer = pixelatorBuffer;
    } else {
      currBuffer = worldBuffer;

      if (worldBuffer.isBound()) return;

      worldBuffer.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
      worldBuffer.begin(Color.clear);
    }
  }

  private static void endWorld() {
    if (!Vars.renderer.pixelate) {
      worldBuffer.end();
      blitBuffer(worldBuffer, null);
    }
  }

  private static void beginUI() {
    currBuffer = uiBuffer;

    if (uiBuffer.isBound()) return;

    uiBuffer.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
    uiBuffer.begin(Color.clear);

    ensurePixelatorBufferInitialized();
    if (Vars.renderer.pixelate) blitBuffer(pixelatorBuffer, uiBuffer);
    else blitBuffer(worldBuffer, uiBuffer);
  }

  private static void endUI() {
    currBuffer = null;
    uiBuffer.end();
    blitBuffer(uiBuffer, null);
  }

  /** 将当前的屏幕纹理使用传入的着色器绘制到屏幕上
   * @param unit 屏幕采样纹理绑定的纹理单元
   */
  public static void blit(Shader shader, int unit) {
    if (currBuffer == null) {
      throw new IllegalStateException("currently no buffer bound");
    }

    currBuffer.getTexture().bind(unit);
    Draw.blit(shader);
  }

  /** 重载方法，使用默认纹理单元0 */
  public static void blit(Shader shader) {
    blit(shader, 0);
  }

  private static void blitBuffer(FrameBuffer from, FrameBuffer to) {
    if (Core.gl30 == null) {
      from.blit(HeShaders.baseScreen);
    } else {
      GLFrameBuffer<?> target = to != null ? to : getLastBoundFramebuffer(from);
      Gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.getFramebufferHandle());
      Gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target != null ? target.getFramebufferHandle() : 0);
      Core.gl30.glBlitFramebuffer(
          0, 0, from.getWidth(), from.getHeight(),
          0, 0,
          target != null ? target.getWidth() : Core.graphics.getWidth(),
          target != null ? target.getHeight() : Core.graphics.getHeight(),
          Gl.colorBufferBit, Gl.nearest
      );
    }
  }

  // 反射访问 lastBoundFramebuffer 字段
  private static GLFrameBuffer<?> getLastBoundFramebuffer(GLFrameBuffer<?> buffer) {
    try {
      return (GLFrameBuffer<?>) lastBoundFramebufferField.get(buffer);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Failed to access lastBoundFramebuffer field", e);
    }
  }

  /** 将当前屏幕纹理转存到一个[帧缓冲区][FrameBuffer]，这将成为一份拷贝，可用于暂存屏幕内容
   *
   * @param target 用于转存屏幕纹理的目标缓冲区
   * @param clear 在转存之前是否清空帧缓冲区
   */
  public static void getToBuffer(FrameBuffer target, boolean clear) {
    if (currBuffer == null) {
      throw new IllegalStateException("currently no buffer bound");
    }

    if (clear) target.begin(Color.clear);
    else target.begin();

    Gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, currBuffer.getFramebufferHandle());
    Gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.getFramebufferHandle());
    Core.gl30.glBlitFramebuffer(
        0, 0, currBuffer.getWidth(), currBuffer.getHeight(),
        0, 0, target.getWidth(), target.getHeight(),
        Gl.colorBufferBit, Gl.nearest
    );

    target.end();
  }
}