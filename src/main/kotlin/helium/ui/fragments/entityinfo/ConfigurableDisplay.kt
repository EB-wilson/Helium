package helium.ui.fragments.entityinfo

import arc.Core
import arc.func.Boolp
import arc.math.Interp
import arc.scene.style.Drawable
import arc.scene.ui.Tooltip
import arc.scene.ui.layout.Table
import arc.util.Align
import arc.util.Scaling
import helium.ui.HeAssets
import helium.ui.elements.HeCollapser
import helium.util.enterSt
import helium.util.exitSt
import mindustry.ui.Styles
import kotlin.reflect.KMutableProperty0

interface ConfigurableDisplay {
  fun getConfigures(): List<ConfigPair>
}

open class ConfigPair(
  val name: String,
  val icon: Drawable,
  val checked: Boolp? = null,
  val callback: Runnable
){
  constructor(
    name: String,
    icon: Drawable,
    bind: KMutableProperty0<Boolean>
  ) : this(
    name,
    icon,
    { bind.get() },
    { bind.set(!bind.get()) }
  )

  open fun localized() = Core.bundle["config.$name"]?:"error"
  open fun build(table: Table) {
    table.image(icon).size(38f).scaling(Scaling.fit)
    table.row()
    table.add(HeCollapser(collX = false, collY = true, collapsed = true){
      it.add(localized(), Styles.outlineLabel, 0.8f)
    }.also { col ->
      col.setDuration(0.35f, Interp.pow2Out)
      table.parent.enterSt { col.setCollapsed(false) }
      table.parent.exitSt { col.setCollapsed(true) }
    })
  }
}
