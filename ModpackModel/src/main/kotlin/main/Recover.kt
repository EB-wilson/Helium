package main

import arc.Core
import arc.Events
import arc.util.Align
import arc.util.Log
import arc.util.Time
import mindustry.Vars
import mindustry.game.EventType
import mindustry.mod.Mod
import mindustry.ui.dialogs.BaseDialog

class Recover: Mod() {
  override fun init() {
    Events.on(EventType.ClientLoadEvent::class.java) { event ->
      Vars.ui.showConfirm(Core.bundle["dialog.recovering.title"], Core.bundle["dialog.recovering.confirm"]){
        val dialog = BaseDialog(Core.bundle["dialog.recovering.title"])
        dialog.cont.add("")
          .width(500f).wrap(true).pad(4f)
          .update { l ->
            l.setText(Core.bundle["dialog.recovering.text"] + ".".repeat((Time.globalTime%30/10).toInt() + 1))
          }
          .get().setAlignment(Align.center, Align.center)
        dialog.buttons.defaults().size(200f, 54f).pad(2f)
        dialog.setFillParent(false)
        dialog.show()

        Thread{
          try {
            recoverBackup()

            dialog.hide()
            Core.app.post {
              Vars.ui.showOkText(Core.bundle["misc.recoverCompleted"], Core.bundle["dialog.recovering.complete"]) {
                Core.app.exit()
              }
            }
          } catch (e: Throwable) {
            Log.err(e)
            Core.app.post {
              Vars.ui.showException(Core.bundle["misc.recoverFailed"], e)
            }
          }
        }.start()
      }
    }
  }

  private fun recoverBackup() {
    val thisLoadedMod = Vars.mods.getMod(Recover::class.java)

    Vars.dataDirectory.list().forEach { f ->
      if (f.name() == "mods"){
        val modFile = thisLoadedMod.file
        f.list().forEach { c ->
          if (c == modFile) return@forEach
          if (c.isDirectory) c.deleteDirectory()
          else c.delete()
        }
      }
      else {
        if (f.isDirectory) f.deleteDirectory()
        else f.delete()
      }
    }

    thisLoadedMod.root.child("backfiles").list().forEach { f ->
      if (f.isDirectory) f.copyTo(Vars.dataDirectory)
      else f.copyTo(Vars.dataDirectory.child(f.name()))
    }

    Core.settings.clear()
    Core.settings.load()
    Vars.mods.setEnabled(thisLoadedMod, false)
  }
}