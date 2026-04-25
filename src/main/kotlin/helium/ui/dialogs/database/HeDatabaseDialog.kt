package helium.ui.dialogs.database

import arc.Core
import helium.ui.HeAssets
import mindustry.Vars
import universe.ui.dialogs.AttachableDialog

class HeDatabaseDialog: AttachableDialog(
  Vars.ui.database,
  HeAssets.heIcon,
  "Helium",
  Core.bundle["mods"],
) {
}