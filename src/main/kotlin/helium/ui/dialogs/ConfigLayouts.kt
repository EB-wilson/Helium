package helium.ui.dialogs

import arc.Core
import arc.func.*
import arc.graphics.Color
import arc.input.KeyCode
import arc.math.Mathf
import arc.scene.event.HandCursorListener
import arc.scene.event.InputEvent
import arc.scene.event.InputListener
import arc.scene.event.Touchable
import arc.scene.ui.*
import arc.scene.ui.layout.Cell
import arc.scene.ui.layout.Table
import arc.util.Strings
import arc.util.Time
import helium.invoke
import helium.ui.HeAssets
import helium.ui.UIUtils.line
import helium.ui.dialogs.ModConfigDialog.ConfigLayout
import helium.util.binds.CombinedKeys
import mindustry.graphics.Pal
import mindustry.ui.Styles
import kotlin.reflect.KMutableProperty0

class ConfigSepLine(
  name: String,
  private var string: String,
  private var lineColor: Color = Pal.accent,
  private var lineColorBack: Color = Pal.accentBack
): ConfigLayout(name) {
  override fun build(table: Table) {
    table.stack(
      Table { t ->
        t.image().color(lineColor).pad(0f).grow()
        t.row()
        t.line(lineColorBack, true, 4f)
      },
      Table { t: Table ->
        t.left().add(string, Styles.outlineLabel).fill().left().padLeft(5f)
      }
    ).grow().pad(-5f).padBottom(4f).padTop(4f)
    table.row()
  }
}

abstract class ConfigEntry(name: String) : ConfigLayout(name) {
  protected var str: Prov<String>? = null
  protected var tip: Prov<String>? = null
  protected var disabled = Boolp { false }

  init {
    if (Core.bundle.has("settings.tip.$name")) {
      tip = Prov { Core.bundle["settings.tip.$name"] }
    }
  }

  override fun build(table: Table) {
    table.left().add(Core.bundle["settings.item.$name"]).left().padLeft(4f)
    table.table { t: Table ->
      t.clip = false
      t.right().defaults().right().padRight(0f)
      if (str != null) t.add("").update { l -> l.setText(str!!.get()) }

      buildCfg(t)
    }.growX().height(60f).padRight(4f).right()

    if (tip != null) {
      table.addListener(Tooltip { ta ->
        ta.table(HeAssets.darkGrayUIAlpha).margin(6f).get().add(
          tip!!.get()
        ).update { l: Label -> l.setText(tip!!.get()) }
      }.apply { allowMobile = true })
    }
  }

  abstract fun buildCfg(table: Table)
}

class ConfigButton(
  name: String,
  private var button: Prov<Button>
): ConfigEntry(name) {
  override fun buildCfg(table: Table) {
    table.add(button.get()).width(180f).growY().pad(4f).get().setDisabled(disabled)
  }
}

class ConfigTable(
  name: String,
  private var table: Cons<Table>,
  private var handler: Cons<Cell<Table>>
): ConfigEntry(name) {
  override fun buildCfg(table: Table) {
    handler[table.table { t: Table ->
      t.clip = false
      this.table[t]
    }]
  }
}

class ConfigKeyBind(
  name: String,
  private var getBindKey: Prov<KeyCode>,
  private var bindingKey: Cons<KeyCode>,
): ConfigEntry(name) {
  val def = getBindKey.get()!!

  constructor(name: String, baindField: KMutableProperty0<KeyCode>): this(
    name,
    { baindField.get() },
    { baindField.set(it) }
  )

  override fun buildCfg(table: Table) {
    createKeybindTable(table, getBindKey, bindingKey) { bindingKey.get(def) }
  }

  private fun createKeybindTable(
    table: Table,
    getBindKey: Prov<KeyCode>,
    hotKeyMethod: Cons<KeyCode>,
    resetMethod: Runnable
  ) {
    table.label{ getBindKey().toString() }.color(Pal.accent).left().minWidth(160f).padRight(20f)

    table.button("@settings.rebind", Styles.defaultt) { openDialog(false){ hotKeyMethod(it.first()) } }.width(130f)
    table.button("@settings.resetKey", Styles.defaultt) { resetMethod.run() }.width(130f).pad(2f).padLeft(4f)
    table.row()
  }
}

private fun openDialog(isCombine: Boolean, callBack: Cons<Array<KeyCode>>) {
  val rebindDialog = Dialog()
  val res = linkedSetOf<KeyCode>()
  var show = ""

  rebindDialog.cont.table{
    it.add(Core.bundle["misc.pressAnyKeys".takeIf { isCombine }?:"keybind.press"])
      .color(Pal.accent)
    if (!isCombine) return@table

    it.row()
    it.label{ show.ifBlank { Core.bundle["misc.requireInput"] } }
      .padTop(8f)
  }

  rebindDialog.titleTable.cells.first().pad(4f)

  rebindDialog.addListener(object : InputListener() {
    override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: KeyCode): Boolean {
      if (Core.app.isAndroid) {
        rebindDialog.hide()
        return false
      }

      keySet(button)
      return true
    }

    override fun keyDown(event: InputEvent, keycode: KeyCode): Boolean {
      if (keycode == KeyCode.escape) {
        rebindDialog.hide()
        return false
      }

      keySet(keycode)
      return true
    }

    private fun keySet(button: KeyCode) {
      if (!isCombine) {
        callBack(arrayOf(button))
        rebindDialog.hide()
        return
      }

      if (button != KeyCode.enter) {
        res.add(button)
        show = CombinedKeys.toString(res)
      }
      else {
        callBack(res.toTypedArray())
        rebindDialog.hide()
      }
    }

    override fun keyUp(event: InputEvent?, keycode: KeyCode?): Boolean {
      res.remove(keycode)
      show = CombinedKeys.toString(res)

      return true
    }
  })

  rebindDialog.show()
  Time.runTask(1f) { Core.scene.setScrollFocus(rebindDialog) }
}

class ConfigCheck(
  name: String,
  private var click: Boolc,
  private var checked: Boolp
): ConfigEntry(name) {
  constructor(name: String, field: KMutableProperty0<Boolean>): this(name, { field.set(it) }, { field.get() })

  override fun buildCfg(table: Table) {
    table.check("", checked.get(), click)
      .update { c -> c.isChecked = checked.get() }
      .get().also {
        it.setDisabled(disabled)
        table.touchable = Touchable.enabled
        table.addListener(it.clickListener)
        table.addListener(HandCursorListener())
        it.removeListener(it.clickListener)
      }
  }
}

class ConfigSlider : ConfigEntry {
  private var slided: Floatc
  private var curr: Floatp
  private var show: Func<Float, String>
  private var min: Float
  private var max: Float
  private var step: Float

  constructor(
    name: String,
    slided: Floatc,
    curr: Floatp,
    min: Float, max: Float, step: Float
  ) : super(name) {
    var s = step
    this.slided = slided
    this.curr = curr
    this.min = min
    this.max = max
    this.step = s

    val fix: Int
    s %= 1f
    var i = 0
    while (true) {
      if (Mathf.zero(s)) {
        fix = i
        break
      }
      s *= 10f
      s %= 1f
      i++
    }

    this.show = Func { f -> Strings.autoFixed(f, fix) }
  }

  constructor(
    name: String,
    field: KMutableProperty0<Float>,
    min: Float, max: Float, step: Float
  ) : this(name, { field.set(it) }, { field.get() }, min, max, step)

  constructor(
    name: String,
    field: KMutableProperty0<Int>,
    min: Int, max: Int, step: Int
  ) : this(name, { field.set(it.toInt()) }, { field.get().toFloat() }, min.toFloat(), max.toFloat(), step.toFloat())

  constructor(
    name: String,
    field: KMutableProperty0<Int>,
    min: Int, max: Int, step: Int,
    show: Func<Int, String>,
  ) : this(
    name, { field.set(it.toInt()) }, { field.get().toFloat() },
    min.toFloat(), max.toFloat(), step.toFloat(),
    { f -> show(f.toInt()) }
  )

  constructor(
    name: String,
    slided: Floatc,
    curr: Floatp,
    min: Float, max: Float, step: Float,
    show: Func<Float, String>,
  ) : super(name) {
    this.show = show
    this.slided = slided
    this.curr = curr
    this.min = min
    this.max = max
    this.step = step
  }

  constructor(
    name: String,
    field: KMutableProperty0<Float>,
    min: Float, max: Float, step: Float,
    show: Func<Float, String>,
  ) : this(name, { field.set(it) }, { field.get() }, min, max, step, show)

  override fun buildCfg(table: Table) {
    if (str == null) {
      table.add("").update { l: Label ->
        l.setText(show[curr.get()])
      }.padRight(0f)
    }
    table.slider(min, max, step, curr.get(), slided).width(360f).padLeft(4f).update { s: Slider ->
      s.setValue(curr.get())
      s.isDisabled = disabled.get()
    }
  }
}