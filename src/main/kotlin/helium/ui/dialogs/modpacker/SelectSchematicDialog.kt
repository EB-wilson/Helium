package helium.ui.dialogs.modpacker

import arc.Core
import arc.func.Cons
import arc.graphics.Color
import arc.input.KeyCode
import arc.scene.event.EventListener
import arc.scene.event.ResizeListener
import arc.scene.event.VisibilityListener
import arc.scene.ui.Button
import arc.scene.ui.TextButton
import arc.scene.ui.TextField
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.struct.Seq
import arc.util.Align
import arc.util.Scaling
import mindustry.Vars
import mindustry.game.Schematic
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.input.Binding
import mindustry.ui.Styles
import mindustry.ui.dialogs.SchematicsDialog
import universe.util.reflect.Reflection.accessBoolean
import universe.util.reflect.Reflection.accessField
import universe.util.reflect.Reflection.accessMethod0
import java.util.*
import java.util.regex.Pattern
import kotlin.math.max

private const val tagh: Float = 42f
class SelectSchematicDialog: SchematicsDialog() {
  private val ignoreSymbols: Pattern = Pattern.compile("[`~!@#$%^&*()\\-_=+{}|;:'\",<.>/?]")

  private var SchematicsDialog.checkedTags: Boolean by accessBoolean("checkedTags")
  private var SchematicsDialog.search: String by accessField("search")
  private var SchematicsDialog.searchField: TextField by accessField("searchField")
  private var SchematicsDialog.rebuildPane: Runnable by accessField("rebuildPane")
  private var SchematicsDialog.rebuildTags: Runnable by accessField("rebuildTags")
  private var SchematicsDialog.firstSchematic: Schematic? by accessField("firstSchematic")

  private val SchematicsDialog.tags: Seq<String> by accessField("tags")
  private val SchematicsDialog.selectedTags: Seq<String> by accessField("selectedTags")

  private val checkTags: SchematicsDialog.() -> Unit = accessMethod0("checkTags")
  private val showAllTags: SchematicsDialog.() -> Unit = accessMethod0("showAllTags")

  private var handle = Cons{ s: Schematic -> }

  init {
    listeners.remove { e: EventListener -> e is VisibilityListener || e is ResizeListener }

    shown(::setup)
    resized(::setup)
  }

  fun show(callback: Cons<Schematic>){
    handle = callback
    show()
  }

  fun setup() {
    if (!checkedTags) {
      checkTags()
      checkedTags = true
    }

    search = ""

    cont.top()
    cont.clear()

    cont.table { s ->
      s.left()
      s.image(Icon.zoom)
      searchField = s.field(search) { res ->
        search = res
        rebuildPane.run()
      }.growX().get()
      searchField.setMessageText("@schematic.search")
      searchField.clicked(KeyCode.mouseRight) {
        if (!search.isEmpty()) {
          search = ""
          searchField.clearText()
          rebuildPane.run()
        }
      }
    }.fillX().padBottom(4f)

    cont.row()

    cont.table { inner ->
      inner.left()
      inner.add("@schematic.tags").padRight(4f)

      //tags (no scroll pane visible)
      inner.pane(Styles.noBarPane) { t ->
        rebuildTags = Runnable {
          t.clearChildren()
          t.left()

          t.defaults().pad(2f).height(tagh)
          for (tag in tags) {
            t.button(tag, Styles.togglet) {
              if (selectedTags.contains(tag)) {
                selectedTags.remove(tag)
              }
              else {
                selectedTags.add(tag)
              }
              rebuildPane.run()
            }.checked(selectedTags.contains(tag)).with { c: TextButton? -> c!!.getLabel().setWrap(false) }
          }
        }
        rebuildTags.run()
      }.fillX().height(tagh).scrollY(false)
      inner.button(Icon.pencilSmall) { showAllTags() }.size(tagh).pad(2f)
        .tooltip("@schematic.edittags")
    }.height(tagh).fillX()

    cont.row()

    cont.pane { t: Table? ->
      t!!.top()
      t.update {
        if (Core.input.keyTap(Binding.chat) && Core.scene.keyboardFocus === searchField && firstSchematic != null) {
          if (!Vars.state.rules.schematicsAllowed) {
            Vars.ui.showInfo("@schematic.disabled")
          }
          else {
            Vars.control.input.useSchematic(firstSchematic)
            hide()
          }
        }
      }

      rebuildPane = Runnable {
        val cols = max((Core.graphics.width/Scl.scl(230f)).toInt(), 1)
        t.clear()
        var i = 0
        val searchString = ignoreSymbols.matcher(search.lowercase(Locale.getDefault())).replaceAll("")

        firstSchematic = null

        for (s in Vars.schematics.all()) {
          //make sure *tags* fit
          if (selectedTags.any() && !s.labels.containsAll(selectedTags)) continue
          //make sure search fits
          if (!search.isEmpty() && !ignoreSymbols.matcher(s.name().lowercase(Locale.getDefault())).replaceAll("")
              .contains(searchString)
          ) continue
          if (firstSchematic == null) firstSchematic = s

          val sel = arrayOf<Button?>(null)
          sel[0] = t.button({ b ->
            b.top()
            b.margin(0f)
            b.table { buttons: Table? ->
              buttons!!.left()
              buttons.defaults().size(50f)

              val style = Styles.emptyi

              buttons.button(Icon.info, style) { showInfo(s) }.tooltip("@info.title")
              buttons.button(Icon.upload, style) { showExport(s) }.tooltip("@editor.export")
              buttons.button(Icon.pencil, style) { showEdit(s) }.tooltip("@schematic.edit")
              if (s.hasSteamID()) {
                buttons.button(Icon.link, style) { Vars.platform.viewListing(s) }
                  .tooltip("@view.workshop")
              }
              else {
                buttons.button(Icon.trash, style) {
                  if (s.mod != null) {
                    Vars.ui.showInfo(Core.bundle.format("mod.item.remove", s.mod.meta.displayName))
                  }
                  else {
                    Vars.ui.showConfirm("@confirm", "@schematic.delete.confirm") {
                      Vars.schematics.remove(s)
                      rebuildPane.run()
                    }
                  }
                }.tooltip("@save.delete")
              }
            }.growX().height(50f)
            b.row()
            b.stack(SchematicImage(s).setScaling(Scaling.fit), Table { n: Table? ->
              n!!.top()
              n.table(Styles.black3) { c: Table? ->
                val label =
                  c!!.add(s.name()).style(Styles.outlineLabel).color(Color.white).top().growX()
                    .maxWidth(200f - 8f)
                    .get()
                label.setEllipsis(true)
                label.setAlignment(Align.center)
              }.growX().margin(1f).pad(4f).maxWidth(Scl.scl(200f - 8f)).padBottom(0f)
            }).size(200f)
          }, {
            if (sel[0]!!.childrenPressed()) return@button

            handle.get(s)
            hide()
          }).pad(4f).style(Styles.flati).get()

          sel[0]!!.style.up = Tex.pane

          if (++i%cols == 0) {
            t.row()
          }
        }
        if (firstSchematic == null) {
          if (!searchString.isEmpty() || selectedTags.any()) {
            t.add("@none.found")
          }
          else {
            t.add("@none").color(Color.lightGray)
          }
        }
      }
      rebuildPane.run()
    }.grow().scrollX(false)
  }
}