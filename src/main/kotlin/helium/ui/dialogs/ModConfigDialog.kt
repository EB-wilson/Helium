package helium.ui.dialogs

import arc.Core
import arc.func.Cons
import arc.graphics.Color
import arc.math.Mathf
import arc.scene.Element
import arc.scene.actions.Actions
import arc.scene.style.Drawable
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.ScrollPane
import arc.scene.ui.TextButton
import arc.scene.ui.TextButton.TextButtonStyle
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.struct.ObjectMap
import arc.struct.OrderedMap
import arc.struct.Seq
import arc.util.Align
import helium.He
import helium.Helium
import helium.ui.HeAssets
import helium.ui.HeStyles
import helium.ui.UIUtils.line
import mindustry.Vars
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.graphics.Pal
import mindustry.ui.Fonts
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog

class ModConfigDialog : BaseDialog("") {
  private var entries: OrderedMap<String, Seq<ConfigLayout>> = OrderedMap()
  private var icons: ObjectMap<String, Drawable> = ObjectMap()

  private var scrollPane: ScrollPane? = null
  private var settings: Table? = null
  private var hover: Table? = null

  private var currCat: String? = null

  private lateinit var catTable: Table
  private lateinit var relaunchTip: Table
  private var currIndex: Int = 0

  private var requireRelaunch: Boolean = false

  private val relaunchDialog = object : BaseDialog("") {
    init {
      style = HeStyles.transparentBack

      cont.table { t ->
        t.add(Core.bundle["infos.relaunchEnsure"])
          .padBottom(12f).center().labelAlign(Align.center).grow()
        t.row()
        t.table { bu ->
          bu.defaults().size(230f, 54f)
          bu.button(Core.bundle["misc.later"], Icon.left, Styles.flatt) { this.hide() }
            .margin(6f)
          bu.button(Core.bundle["misc.exitGame"], Icon.ok, Styles.flatt) { Core.app.exit() }
            .margin(6f)
        }.fill()
      }.fill().margin(16f)
    }
  }

  init {
    titleTable.clear()

    addCloseButton()
    buttons.button(
      Core.bundle["misc.recDefault"], Icon.redo
    ) {
      Vars.ui.showConfirm(
        Core.bundle["infos.confirmResetConfig"]
      ) { He.config.reset() }
    }

    hidden {
      He.config.save()
      if (requireRelaunch) {
        relaunchDialog.show()
      }
    }

    resized { this.rebuild() }
    shown { this.rebuild() }
  }

  fun show(itemName: String){
    show()
    Core.app.post {
      val elem: Element = settings?.find(itemName)?:return@post

      scrollPane!!.scrollY = elem.y
    }
  }

  private fun rebuild() {
    clearHover()

    cont.clearChildren()
    cont.table(Tex.pane) { main ->
      main.table { cats ->
        if (Scl.scl((entries.size*280).toFloat()) > Core.graphics.width*0.85f) {
          val rebuild = Runnable {
            currCat = entries.orderedKeys()[currIndex]
            catTable.clearActions()
            catTable.actions(
              Actions.alpha(0f, 0.3f),
              Actions.run {
                catTable.clearChildren()
                catTable.image(icons.get(currCat){ Helium.getDrawable("settings_$currCat") }).size(38f)
                catTable.add(Core.bundle["settings.category.$currCat"])
              },
              Actions.alpha(1f, 0.3f)
            )
          }

          cats.button(Icon.leftOpen, Styles.clearNonei) {
            currIndex = Mathf.mod(currIndex - 1, entries.size)
            rebuild.run()
            settings!!.clearActions()
            settings!!.actions(
              Actions.alpha(0f, 0.3f),
              Actions.run { this.rebuildSettings() },
              Actions.alpha(1f, 0.3f)
            )
          }.size(60f).padLeft(12f)
          cats.table(Tex.underline) { t -> catTable = t }
            .height(60f).growX().padLeft(4f).padRight(4f)
          cats.button(Icon.rightOpen, Styles.clearNonei) {
            currIndex = Mathf.mod(currIndex + 1, entries.size)
            rebuild.run()
            settings!!.clearActions()
            settings!!.actions(
              Actions.alpha(0f, 0.3f),
              Actions.run { this.rebuildSettings() },
              Actions.alpha(1f, 0.3f)
            )
          }.size(60f).padRight(12f)

          rebuild.run()
        }
        else {
          cats.defaults().height(60f).growX().padLeft(2f).padRight(2f)
          for (key in entries.keys()) {
            cats.button(
              Core.bundle["settings.category.$key"],
              icons[key, Core.atlas.drawable("settings_$key")],
              object : TextButtonStyle() {
                init {
                  font = Fonts.def
                  fontColor = Color.white
                  disabledFontColor = Color.lightGray
                  down = Styles.flatOver
                  checked = Styles.flatOver
                  up = Tex.underline
                  over = Tex.underlineOver
                  disabled = Tex.underlineDisabled
                }
              },
              38f
            ) {
              currCat = key
              settings!!.clearActions()
              settings!!.actions(
                Actions.alpha(0f, 0.3f),
                Actions.run { this.rebuildSettings() },
                Actions.alpha(1f, 0.3f)
              )
            }.update { b: TextButton -> b.isChecked = key == currCat }.margin(12f)
          }
        }
      }.growX().fillY()
      main.row()
      main.line(Color.gray, true, 4f).pad(-6f).padTop(4f).padBottom(4f)
      main.row()
      scrollPane = main.top().pane { pane ->
        pane.defaults().top().growX().height(50f)
        this.settings = pane

        hover = Table(Tex.pane)
        hover!!.visible = false
        pane.addChild(hover)
      }.growX().fillY().top().scrollX(false).get()
    }.grow().maxWidth(1200f).pad(4f).margin(12f)
    cont.row()
    relaunchTip = cont.table(HeAssets.grayUIAlpha) { t ->
      t.add(Core.bundle["infos.requireRelaunch"]).color(Color.red)
    }.fill().center().margin(10f).pad(4f).get()
    relaunchTip.color.a(0f)

    rebuildSettings()
  }

  fun rebuildSettings() {
    if (currCat == null) {
      currCat = entries.orderedKeys().first()
    }

    settings!!.clearChildren()
    cfgCount = 0
    for (entry in entries[currCat]) {
      cfgCount++
      settings!!.table((Tex.whiteui as TextureRegionDrawable).tint(Pal.darkestGray.cpy().a(0.5f*(cfgCount%2)))) { ent ->
        ent.clip = false
        ent.defaults().growY()
        entry.build(ent)
      }.name(entry.name)
      settings!!.row()
    }
  }

  fun requireRelaunch() {
    requireRelaunch = true
    relaunchTip.clearActions()
    relaunchTip.actions(Actions.alpha(1f, 0.5f))
  }

  fun addConfig(category: String, vararg config: ConfigLayout?) {
    entries[category, { Seq() }].addAll(*config)
    if (category == currCat) rebuildSettings()
  }

  fun addConfig(category: String, icon: Drawable, vararg config: ConfigLayout) {
    entries[category, { Seq() }].addAll(*config)
    icons.put(category, icon)
    if (category == currCat) rebuildSettings()
  }

  fun removeCfg(category: String, name: String) {
    entries[category, nilSeq].remove { e -> e.name == name }
    if (category == currCat) rebuildSettings()
  }

  fun removeCat(category: String?) {
    entries.remove(category)
    icons.remove(category)
  }

  fun clearHover() {
    if (hover == null) return
    hover!!.clear()
    hover!!.visible = false
  }

  fun setHover(build: Cons<Table?>) {
    if (hover == null) return

    clearHover()
    build[hover]
  }

  abstract class ConfigLayout(val name: String) {
    abstract fun build(table: Table)
  }

  companion object {
    private val nilSeq = Seq<ConfigLayout>()

    var cfgCount: Int = 0
  }
}
