package helium.ui.elements.roulette

import arc.func.Boolp
import arc.func.Cons
import arc.scene.Element
import arc.scene.event.*
import arc.scene.ui.layout.Table
import arc.scene.utils.Disableable
import arc.util.pooling.Pools
import helium.graphics.StripDrawable

open class StripButton(
  buttonStyle: StripButtonStyle,
  private val elem: Element = Element(),
): StripWrap(elem), Disableable {
  constructor(buttonStyle: StripButtonStyle, builder: Cons<Table>): this(buttonStyle, Table()){
    builder.get(elem as Table)
  }

  var clickListener: ClickListener
    private set

  var style: StripButtonStyle = buttonStyle
    set(value) {
      field = value

      if (isDisable && value.disabled != null) background = value.disabled
      else if (isPressed && value.down != null) background = value.down
      else if (isChecked && value.checked != null) background =
        if (value.checkedOver != null && isOver) value.checkedOver else value.checked
      else if (isOver && value.over != null) {
        background = value.over
      }
      else if (value.up != null) background = value.up
    }

  private var disableProv: Boolp? = null

  var isDisable = false
  var isChecked = false
  val isPressed get() = clickListener.isVisualPressed
  val isOver get() = clickListener.isOver

  init {
    this.touchable = Touchable.enabled
    addListener(object : ClickListener() {
      override fun clicked(event: InputEvent?, x: Float, y: Float) {
        if (isDisable) return
        setChecked(!isChecked, true)
      }
    }.also { clickListener = it })
    addListener(HandCursorListener())
  }

  open fun setDisabled(disabled: Boolp) {
    this.disableProv = disabled
  }

  open fun setChecked(isChecked: Boolean, fireEvent: Boolean) {
    if (this.isChecked == isChecked) return
    this.isChecked = isChecked

    if (fireEvent) {
      val changeEvent = Pools.obtain(ChangeListener.ChangeEvent::class.java) { ChangeListener.ChangeEvent() }
      if (fire(changeEvent)) this.isChecked = !isChecked
      Pools.free(changeEvent)
    }
  }

  override fun act(delta: Float) {
    disableProv?.also { isDisable = it.get() }
    super.act(delta)
  }

  override fun draw() {
    validate()

    if (isDisable && style.disabled != null) background = style.disabled
    else if (isPressed && style.down != null) background = style.down
    else if (isChecked && style.checked != null) background =
      if (style.checkedOver != null && isOver) style.checkedOver else style.checked
    else if (isOver && style.over != null) {
      background = style.over
    }
    else if (style.up != null) background = style.up

    super.draw()
  }

  override fun isDisabled() = isDisable

  override fun setDisabled(isDisabled: Boolean) {
    this.isDisable = isDisabled
  }
}

class StripButtonStyle(
  val up: StripDrawable? = null,
  val down: StripDrawable? = null,
  val over: StripDrawable? = null,
  val checked: StripDrawable? = null,
  val checkedOver: StripDrawable? = null,
  val disabled: StripDrawable? = null,
){
  constructor(other: StripButtonStyle): this(
    up = other.up,
    down = other.down,
    over = other.over,
    checked = other.checked,
    checkedOver = other.checkedOver,
    disabled = other.disabled
  )

  fun copyWith(
    up: StripDrawable? = null,
    down: StripDrawable? = null,
    over: StripDrawable? = null,
    checked: StripDrawable? = null,
    checkedOver: StripDrawable? = null,
    disabled: StripDrawable? = null,
  ) = StripButtonStyle(
    up = up?:this.up,
    down = down?:this.down,
    over = over?:this.over,
    checked = checked?:this.checked,
    checkedOver = checkedOver?:this.checkedOver,
    disabled = disabled?:this.disabled,
  )
}
