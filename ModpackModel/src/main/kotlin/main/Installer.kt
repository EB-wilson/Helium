package main

import arc.Core
import arc.Events
import arc.graphics.Color
import arc.graphics.Texture
import arc.graphics.g2d.TextureRegion
import arc.scene.event.Touchable
import arc.scene.style.TextureRegionDrawable
import arc.util.Align
import arc.util.Log
import arc.util.Time
import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.graphics.Pal
import mindustry.mod.Mod
import mindustry.ui.BorderImage
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog

class Installer: Mod() {
  val model: PackModel = PackModel()

  private var rebuild = {}

  override fun init() {
    val file = Vars.mods.getMod(Installer::class.java).root
    ModpackUtil.readModpackFile(model, file)
    val icon = model.icon?.let { TextureRegionDrawable(TextureRegion(Texture(it))) }

    Events.on(EventType.ClientLoadEvent::class.java) { e ->
      BaseDialog(Core.bundle["dialog.installPack.title"]).apply {
        rebuild = {
          cont.clearChildren()

          cont.table(Tex.pane) { main ->
            main.add(Core.bundle.get("dialog.installPack.helperInfo")).pad(5f)
            main.row()
            main.top().pane(Styles.noBarPane) { pane ->
              pane.table(Tex.underlineOver) { infos ->
                val image = BorderImage(icon?:Tex.nomap)
                image.border(Pal.accent)

                infos.add(image).size(360f).left()
                if (Core.graphics.isPortrait) infos.row()
                infos.top().table { bar ->
                  bar.defaults().padTop(5f).padLeft(2f).padBottom(2f).padRight(12f).top().left().growX()
                  bar.add(Core.bundle.get("dialog.installPack.name")).color(Color.gray)
                  bar.row()
                  bar.add(model.displayName)
                  bar.row()
                  bar.add(Core.bundle.get("dialog.installPack.author")).color(Color.gray)
                  bar.row()
                  bar.add(model.author)
                  bar.row()
                  bar.add(Core.bundle.get("dialog.installPack.version")).color(Color.gray)
                  bar.row()
                  bar.add(model.version)
                  bar.row()
                  bar.add(Core.bundle.get("dialog.installPack.description")).color(Color.gray)
                  bar.row()
                  bar.add(model.description).wrap(true).grow().top()
                    .minWidth(280f).labelAlign(Align.topLeft)
                  bar.row()
                  bar.add(Core.bundle.get("dialog.installPack.contains")).color(Color.gray)
                  bar.row()
                  bar.add(Core.bundle.format(
                    "dialog.installPack.content",
                    model.mods.size,
                    model.fileEntries.size
                  ))
                }.padLeft(6f).grow().top()
              }.growX()
              pane.row()
              pane.table { opt ->
                opt.defaults().pad(6f).growX()
                opt.table(Tex.whiteui) { tab ->
                  tab.left().defaults().pad(6f).left()
                  tab.add(Core.bundle["dialog.installPack.type"]).padBottom(0f)
                  tab.row()
                  tab.table{ types ->
                    types.defaults().pad(4f).growX().fillY()

                    PackModel.Type.entries.forEach { type ->
                      val i = when(type){
                        PackModel.Type.Distribute -> Icon.boxSmall
                        PackModel.Type.Shadow -> Icon.bookSmall
                        PackModel.Type.Override -> Icon.starSmall
                      }

                      types.button(type.localized, i, Styles.flatTogglet){}
                        .margin(12f).update {
                          it.isChecked = model.type == type
                        }.touchable(Touchable.disabled)
                    }
                  }.growX().fillY()
                  tab.row()
                  tab.add("").update { desc ->
                    desc.setText(model.type.description)
                  }.wrap(true).pad(12f).growX().color(Color.lightGray)
                }.color(Pal.darkestGray.cpy().a(0.7f))
                opt.row()
                opt.table(Tex.whiteui) { tab ->
                  tab.left().defaults().pad(6f).left().growX()
                  tab.check(Core.bundle["dialog.installPack.skipRepeat"], model.skipRepeat){}
                    .touchable(Touchable.disabled).get().left()
                  tab.row()
                  tab.check(Core.bundle["dialog.installPack.rawBackup"], model.rawBackup){}
                    .touchable(Touchable.disabled).get().left()
                  tab.row()
                  tab.check(Core.bundle["dialog.installPack.force"], model.force){}
                    .touchable(Touchable.disabled).get().left()
                  tab.row()
                  tab.check(Core.bundle["dialog.installPack.uncheck"], model.uncheck){}
                    .touchable(Touchable.disabled).get().left()
                }.color(Pal.darkestGray.cpy().a(0.7f))
              }.growX()
            }.top().padTop(20f).fill()
          }.fill()
          cont.row()
          cont.table { buttons ->
            buttons.defaults().size(210f, 64f).pad(4f)
            buttons.button("@back", Icon.left) { hide() }
            buttons.button(Core.bundle["misc.install"], Icon.download) {
              hide()
              val dialog = BaseDialog(Core.bundle["dialog.installing.title"])
              dialog.cont.add("")
                .width(500f).wrap(true).pad(4f)
                .update { l ->
                  l.setText(Core.bundle["dialog.installing.text"] + ".".repeat((Time.globalTime%30/10).toInt() + 1))
                }
                .get().setAlignment(Align.center, Align.center)
              dialog.buttons.defaults().size(200f, 54f).pad(2f)
              dialog.setFillParent(false)
              dialog.show()

              Thread{
                try {
                  ModpackUtil.installModpack(model)
                  dialog.hide()
                  Core.app.post {
                    Vars.ui.showOkText(Core.bundle["misc.installComplete"], Core.bundle["dialog.installPack.complete"]){
                      Vars.tmpDirectory.deleteDirectory()
                      Core.app.exit()
                    }
                  }
                } catch (e: Throwable) {
                  Log.err(e)
                  Core.app.post {
                    Vars.ui.showException(Core.bundle["misc.installFailed"], e)
                  }
                }
              }.start()
            }
          }.fill()
        }

        shown(rebuild)
        resized(rebuild)
      }.show()
    }
  }
}