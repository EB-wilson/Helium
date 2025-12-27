package helium.util

import arc.input.KeyBind
import arc.input.KeyCode

object HeKeyBindings {
  val switchFastPageHotKey = KeyBind.add("switchFastPageHotKey", KeyCode.tab, "helium")!!
  val placementFoldHotKey = KeyBind.add("placementFoldHotKey", KeyCode.q, "helium")!!
}