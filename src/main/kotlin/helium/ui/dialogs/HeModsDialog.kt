package helium.ui.dialogs

import arc.Core
import arc.func.Cons
import arc.func.Func
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.TextureRegion
import arc.math.Interp
import arc.math.Mathf
import arc.math.geom.Rect
import arc.scene.Element
import arc.scene.Group
import arc.scene.actions.Actions
import arc.scene.style.Drawable
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.*
import arc.scene.ui.layout.Cell
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.struct.ObjectMap
import arc.struct.OrderedMap
import arc.struct.Seq
import arc.util.*
import arc.util.serialization.Jval
import helium.He
import helium.addEventBlocker
import helium.invoke
import helium.set
import helium.ui.ButtonEntry
import helium.ui.HeAssets
import helium.ui.UIUtils
import helium.ui.UIUtils.closeBut
import helium.ui.UIUtils.line
import helium.ui.elements.HeCollapser
import helium.util.*
import mindustry.Vars
import mindustry.Vars.modGuideURL
import mindustry.core.Version
import mindustry.ctype.UnlockableContent
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.graphics.Pal
import mindustry.io.JsonIO
import mindustry.mod.Mods
import mindustry.ui.Bar
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog
import universecore.ui.elements.markdown.Markdown
import universecore.ui.elements.markdown.MarkdownStyles
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min

val switchBut: Button.ButtonStyle = Button.ButtonStyle().also {
  it.up = Styles.none
  it.over = HeAssets.grayUIAlpha
  it.down = HeAssets.grayUI
  it.checked = HeAssets.grayUI
}

private var exec: ExecutorService = Threads.unboundedExecutor("HTTP", 1)
private var modList: OrderedMap<Name, ModListing>? = null

private class CullTable: Table{
  constructor(background: Drawable?): super(background)
  constructor(build: Cons<Table>): super(build)
  constructor(background: Drawable?, build: Cons<Table>): super(background, build)

  override fun drawChildren() {
    cullingArea?.also { widgetAreaBounds ->
      children.forEach { widget ->
        if (widget is Group) {
          val set = widget.cullingArea?: Rect()
          set.set(widgetAreaBounds)
          set.x -= widget.x
          set.y -= widget.y
          widget.setCullingArea(set)
        }
      }
    }
    super.drawChildren()
  }
}

private fun Table.cullTable(background: Drawable? = null, build: Cons<Table>? = null) =
  add(CullTable(background).also { t -> build?.also { it.get(t) } })

internal fun <T: Element> Cell<T>.addTip(tipText: String): Cell<T> {
  tooltip{ t -> t.table(HeAssets.padGrayUIAlpha) { tip ->
    tip.add(tipText, Styles.outlineLabel)
  }}

  return this
}

class HeModsDialog: BaseDialog(Core.bundle["mods"]) {
  private val browser = HeModsBrowser()

  private val modTabs = ObjectMap<Mods.LoadedMod, Table>()
  private val updateChecked = ObjectMap<Mods.LoadedMod, UpdateEntry>()

  private val shouldRelaunch get() = Vars.mods.requiresReload()
  private lateinit var tipTable: Table
  private lateinit var disabled: Table
  private lateinit var enabled: Table

  private var searchStr = ""

  init {
    shown(::rebuild)
    resized(::rebuild)

    hidden {
      if (shouldRelaunch) {
        UIUtils.showTip(
          Core.bundle["dialog.mods.shouldRelaunch"],
          Core.bundle["dialog.mods.relaunch"]
        ){
          Core.app.exit()
        }
      }
    }
  }

  fun rebuild(){
    cont.clearChildren()

    cont.table { main ->
      if (Core.graphics.isPortrait){
        main.stack(
          Table(HeAssets.grayUIAlpha) { list ->
            list.top().margin(6f)

            var coll: HeCollapser? = null
            list.button(Core.bundle["dialog.mods.menu"], Icon.menuSmall, Styles.flatt){
              coll?.toggle()
            }.growX().height(38f).margin(8f).update { b ->
              b.find<Image> { it is Image }.setDrawable(if (coll?.collapse?:true) Icon.menuSmall else Icon.upOpen)
            }
            list.row()
            list.add(HeCollapser(collX = false, collY = true, collapsed = true){ coll ->
              coll.pane(Styles.smallPane) { pane ->
                pane.defaults().growX().fillY().pad(4f)
                pane.add(Core.bundle["dialog.mods.importMod"]).color(Color.gray)
                pane.row()
                pane.line(Color.gray, true, 4f).padTop(6f).padBottom(6f)
                pane.row()
                pane.button(Core.bundle["mods.browser"], Icon.planet, Styles.grayt, 46f){
                  browser.show()
                }.margin(4f)
                pane.row()
                pane.button(Core.bundle["mod.import.file"], Icon.file, Styles.grayt, 46f){
                  importFile()
                }.margin(4f)
                pane.row()
                pane.button(Core.bundle["mod.import.github"], Icon.download, Styles.grayt, 46f){
                  importGithub()
                }.margin(4f)
                pane.row()

                pane.add(Core.bundle["dialog.mods.otherHandle"]).color(Color.gray)
                pane.row()
                pane.line(Color.gray, true, 4f).padTop(6f).padBottom(6f)
                pane.row()
                pane.button(Core.bundle["dialog.mods.exportPack"], Icon.list, Styles.grayt, 46f)
                { He.modPackerDialog.show() }.margin(4f)
                pane.row()
                pane.button(Core.bundle["mods.openfolder"], Icon.save, Styles.grayt, 46f)
                { openFolder() }.margin(4f)
                pane.row()
                pane.button(Core.bundle["mods.guide"], Icon.link, Styles.grayt, 46f)
                { Core.app.openURI(modGuideURL) }.margin(4f)
              }.growX().fillY().maxHeight(400f)
            }.setDuration(0.3f, Interp.pow3Out).also { coll = it }).growX().fillY()
            list.row()
            list.line(Pal.darkerGray, true, 4f)
            list.row()
            list.pane(Styles.smallPane) { pane ->
              pane.table { en ->
                en.add(Core.bundle["dialog.mods.enabled"]).color(Pal.accent).left().growX().labelAlign(Align.left)
                en.row()
                en.line(Pal.accent, true, 4f).padTop(6f).padBottom(6f)
                en.row()
                en.top().table { enabled ->
                  this.enabled = enabled
                }.growX().fillY().top()
              }.margin(6f).growX().fillY()
              pane.row()
              pane.table { di ->
                di.add(Core.bundle["dialog.mods.disabled"]).color(Pal.accent).left().growX().labelAlign(Align.left)
                di.row()
                di.line(Pal.accent, true, 4f).padTop(6f).padBottom(6f)
                di.row()
                di.top().table { disabled ->
                  this.disabled = disabled
                }.growX().fillY().top()
              }.margin(6f).growX().fillY()
            }.growX().fillY().scrollX(false).scrollY(true).get().setForceScroll(true, true)
          },
          Table{ tip ->
            tip.bottom().table(HeAssets.grayUIAlpha){ t ->
              tipTable = t
              t.visible = false
            }.fillY().growX().margin(8f)
          }
        ).grow()
        main.row()
        main.line(Pal.gray, true, 4f).pad(-8f).padTop(6f).padBottom(6f)
        main.row()
        main.table{ but ->
          but.button(Core.bundle["back"], Icon.leftOpen, Styles.grayt, 46f)
          { hide() }.height(58f).pad(6f).growX()
          but.button(Core.bundle["dialog.mods.refresh"], Icon.refresh, Styles.grayt, 46f)
          { refresh() }.height(58f).pad(6f).growX()
        }.growX().fillY()
      }
      else {
        main.table { search ->
          search.image(Icon.zoom).size(64f).scaling(Scaling.fit)
          search.field(""){
            searchStr = it.lowercase()
            rebuildMods()
          }.growX()
        }.growX().fillY().padLeft(16f).padRight(16f)
        main.row()

        main.stack(
          Table{ mods ->
            mods.table { left ->
              left.table(HeAssets.grayUIAlpha){ list ->
                list.add(Core.bundle["dialog.mods.enabled"]).color(Pal.accent)
                list.row()
                list.line(Pal.accent, true, 4f).padTop(6f).padBottom(6f)
                list.row()
                list.top().pane(Styles.smallPane) { enabled ->
                  this.enabled = enabled
                }.width(Core.graphics.width/2.8f/Scl.scl()).fillY().top().get().setForceScroll(false, true)
              }.fillX().growY()
            }.fillX().growY()
            mods.line(Color.gray, false, 4f).padLeft(6f).padRight(6f)
            mods.table { right ->
              right.table(HeAssets.grayUIAlpha){ list ->
                list.add(Core.bundle["dialog.mods.disabled"]).color(Pal.accent)
                list.row()
                list.line(Pal.accent, true, 4f).padTop(6f).padBottom(6f)
                list.row()
                list.top().pane(Styles.smallPane) { disabled ->
                  this.disabled = disabled
                }.width(Core.graphics.width/2.8f/Scl.scl()).fillY().top().get().setForceScroll(false, true)
              }.fillX().growY()
            }.fillX().growY()
          },
          Table{ tip ->
            tip.bottom().table(HeAssets.grayUIAlpha){ t ->
              tipTable = t
              t.visible = false
            }.fillY().growX().margin(8f)
          }
        ).grow()

        main.row()
        main.line(Pal.gray, true, 4f).pad(-8f).padTop(6f).padBottom(6f)
        main.row()
        main.table { buttons ->
          buttons.table { top ->
            top.defaults().growX().height(54f).pad(4f)
            top.button(Core.bundle["mods.browser"], Icon.planet, Styles.flatBordert, 46f){
              browser.show()
            }.margin(8f)
            top.button(Core.bundle["mod.import.file"], Icon.file, Styles.flatBordert, 46f){
              importFile()
            }.margin(8f)
            top.button(Core.bundle["mod.import.github"], Icon.download, Styles.flatBordert, 46f){
              importGithub()
            }.margin(8f)
          }.growX().fillY().padBottom(6f)
          buttons.row()
          buttons.table { bot ->
            bot.defaults().growX().height(62f).pad(4f)
            bot.button(Core.bundle["back"], Icon.leftOpen, Styles.grayt, 46f)
            { hide() }
            bot.button(Core.bundle["dialog.mods.refresh"], Icon.refresh, Styles.grayt, 46f)
            { refresh() }
            bot.button(Core.bundle["dialog.mods.exportPack"], Icon.list, Styles.grayt, 46f)
            { He.modPackerDialog.show() }
            bot.button(Core.bundle["mods.openfolder"], Icon.save, Styles.grayt, 46f)
            { openFolder() }
            bot.button(Core.bundle["mods.guide"], Icon.link, Styles.grayt, 46f)
            { Core.app.openURI(modGuideURL) }
          }.growX().fillY()
        }.growX().fillY().colspan(3)
      }

      main.update {
        if (!tipTable.visible && shouldRelaunch) {
          tipTable.visible = true
          tipTable.color.a = 0f
          tipTable.clearChildren()
          tipTable.add(Core.bundle["dialog.mods.shouldRelaunch"]).color(Color.crimson)
          tipTable.actions(Actions.alpha(1f, 0.3f, Interp.pow3Out))
        }
      }
    }.also {
      if (Core.graphics.isPortrait) it.grow()
      else it.fillX().growY()
    }

    rebuildMods()
  }

  private fun refresh() {
    modList = null
    modTabs.clear()
    rebuildMods()
  }

  fun rebuildMods(){
    enabled.clearChildren()
    disabled.clearChildren()

    Vars.mods.list()
      .filter {
        searchStr.isBlank()
        || it.name.lowercase().contains(searchStr)
        || it.meta.displayName.lowercase().contains(searchStr)
      }
      .forEach { mod ->
        ModStat.apply {
          val stat = checkModStat(mod)

          val addToTarget = if (mod.enabled() && stat.isValid()) enabled else disabled
          val modTab = buildModTab(mod)

          addToTarget.add(modTab).growX().fillY().pad(4f)
          addToTarget.row()
        }
      }
  }

  private fun buildModTab(mod: Mods.LoadedMod): Table {
    modTabs[mod]?.also { return it }

    val res = Table()
    var stat = ModStat.checkModStat(mod)
    var updateEntry: UpdateEntry? = null
    var coll: HeCollapser? = null
    var setupContent = { i: Int -> }

    modTabs[mod] = res

    res.button({ top ->
      top.table(Tex.buttonSelect) { icon ->
        icon.image(mod.iconTexture?.let { TextureRegionDrawable(TextureRegion(it)) }?:Tex.nomap)
          .scaling(Scaling.fit).size(80f)
      }.pad(10f).margin(4f).size(88f)
      top.stack(
        Table{ info ->
          info.left().top().margin(12f).marginLeft(6f).defaults().left()
          info.add(mod.meta.displayName).color(Pal.accent).grow().padRight(160f).wrap()
          info.row()
          info.add(mod.meta.version, 0.8f).color(Color.lightGray).grow().padRight(50f).wrap()
          info.row()
          info.add(mod.meta.shortDescription()).grow().padRight(50f).wrap()
        },
        Table{ over ->
          over.right()

          over.table { status ->
            status.top().defaults().size(26f).pad(4f)

            var updateTip: Label? = null
            val checkUpdate = status.image(HeAssets.loading).color(Pal.accent)
              .tooltip{ t -> t.table(HeAssets.padGrayUIAlpha) { tip ->
                updateTip = tip.add(Core.bundle["dialog.mods.checkUpdating"], Styles.outlineLabel).get()
              }}.get()

            buildModAttrIcons(status, stat)

            checkModUpdate(mod, {
              checkUpdate.drawable = HeAssets.networkError
              checkUpdate.setColor(Color.red)
              updateTip!!.setText(Core.bundle["dialog.mods.checkUpdateFailed"])
            }){ res ->
              ModStat.apply {
                if (res.latestMod != null && res.updateValid) stat = stat or UP_TO_DATE
                if (res.latestMod == null) stat = stat or LOCAL_FILE

                if (stat.isUpToDate()) {
                  checkUpdate.drawable = Icon.upSmall
                  checkUpdate.setColor(HeAssets.lightBlue)

                  updateEntry = res
                  updateTip!!.setText(Core.bundle.format("dialog.mods.updateValid", res.latestMod!!.version))
                }

                if (stat.isLocalFile()) {
                  status.image(Icon.fileSmall).scaling(Scaling.fit)
                    .color(Color.white)
                    .addTip(Core.bundle["dialog.mods.localFile"])
                }

                if (stat.isValid()) {
                  if (!stat.isUpToDate()) {
                    if (stat.isEnabled()) {
                      checkUpdate.drawable = Icon.okSmall
                      checkUpdate.setColor(Pal.heal)

                      updateTip!!.setText(Core.bundle["dialog.mods.isLatest"])
                    }
                    else checkUpdate.visible = false
                  }
                }
                else {
                  checkUpdate.visible = false

                  if (stat.isLibMissing()) status.image(Icon.layersSmall).scaling(Scaling.fit).color(Color.crimson)
                    .addTip(Core.bundle["dialog.mods.libMissing"])
                  else if (stat.isLibIncomplete()) status.image(Icon.warningSmall).scaling(Scaling.fit)
                    .color(Color.crimson)
                    .addTip(Core.bundle["dialog.mods.libIncomplete"])
                  else if (stat.isLibCircleDepending()) status.image(Icon.refresh).scaling(Scaling.fit)
                    .color(Color.crimson)
                    .addTip(Core.bundle["dialog.mods.libCircleDepending"])

                  if (stat.isError()) status.image(Icon.cancelSmall).scaling(Scaling.fit).color(Color.crimson)
                    .addTip(Core.bundle["dialog.mods.error"])
                  if (stat.isBlackListed()) status.image(Icon.infoCircle).scaling(Scaling.fit).color(Color.crimson)
                    .addTip(Core.bundle["dialog.mods.blackListed"])
                }
              }
            }
          }.fill().pad(4f)

          over.table { side ->
            side.line(Color.darkGray, false, 3f)
            side.table { buttons ->
              buttons.defaults().size(48f)

              ModStat.apply {
                buttons.button(Icon.rightOpen, Styles.clearNonei, 32f) {
                  Vars.mods.setEnabled(mod, !mod.enabled())
                  rebuildMods()
                }.update { m -> m.style.imageUp = if (mod.enabled()) Icon.rightOpen else Icon.leftOpen }
                  .disabled { !mod.enabled() && !stat.isValid() }
              }

              buttons.row()
              buttons.button(Icon.exportSmall, Styles.clearNonei, 48f) { exportLink(mod) }
              buttons.row()

              buttons.addEventBlocker()
            }.fill()
          }.fill()
        }
      ).grow()
    }, Styles.grayt) {
      coll!!.toggle()
      if (!coll!!.collapse){
        setupContent(0)
      }
    }.growX().fillY()

    res.row()
    coll = res.add(HeCollapser(collX = false, collY = true, collapsed = true, Styles.grayPanel){ col ->
      col.stack(
        Table{ details ->
          ModStat.apply {
            details.left().defaults().growX().pad(4f).padLeft(12f).padRight(12f)

            details.add(Core.bundle.format("dialog.mods.author", mod.meta.author))
              .growX().padRight(50f).wrap().color(Pal.accent).labelAlign(Align.left)
            details.row()
            details.table { link ->
              link.left().image(Icon.githubSmall).scaling(Scaling.fit).size(24f).color(Color.lightGray)
              val linkButton = link.button("...", Styles.nonet) {}
                .padLeft(4f).padRight(50f).wrapLabel(true)
                .growX().left().align(Align.left).height(30f).disabled(true).get()

              linkButton.label.setAlignment(Align.left)
              linkButton.label.setFontScale(0.9f)
              getModList(
                errHandler = {
                  linkButton.isDisabled = true
                  linkButton.setText(Core.bundle["dialog.mods.checkFailed"])
                }
              ) { modList ->
                val modInfo = modList[Name(mod)]

                if (modInfo == null) {
                  linkButton.isDisabled = true
                  linkButton.setText(Core.bundle["dialog.mods.noGithubRepo"])
                }
                else {
                  val url = "https://github.com/${modInfo.repo}"
                  linkButton.isDisabled = false
                  linkButton.setText(url)
                  linkButton.clicked { Core.app.openURI(url) }
                }
              }
            }
            details.row()
            details.table { status ->
              status.left().defaults().left()

              status.collapser(
                { t ->
                  t.left().defaults().left()
                  buildStatus(t, Icon.upSmall, Core.bundle["dialog.mods.updateValidS"], HeAssets.lightBlue)
                }, false
              ) { stat.isUpToDate() }.fill().colspan(2)
              status.row()

              status.collapser(
                { t ->
                  t.left().defaults().left()
                  buildStatus(t, Icon.fileSmall, Core.bundle["dialog.mods.localFile"], Color.white)
                }, false
              ) { stat.isLocalFile() }.fill().colspan(2)
              status.row()

              if (stat.isValid()) {
                buildStatus(status, Icon.okSmall, Core.bundle["dialog.mods.modStatCorrect"], Pal.heal)
              }
              else {
                buildStatus(status, Icon.cancelSmall, Core.bundle["dialog.mods.modStatError"], Color.crimson)
              }

              buildModAttrList(status, stat)

              if (stat.isLibMissing()) {
                buildStatus(status, Icon.layersSmall, Core.bundle["dialog.mods.libMissing"], Color.crimson)
              }
              else if (stat.isLibIncomplete()) {
                buildStatus(status, Icon.warningSmall, Core.bundle["dialog.mods.libIncomplete"], Color.crimson)
              }
              else if (stat.isLibCircleDepending()) {
                buildStatus(status, Icon.rotateSmall, Core.bundle["dialog.mods.libCircleDepending"], Color.crimson)
              }

              if (stat.isError()) {
                buildStatus(status, Icon.cancelSmall, Core.bundle["dialog.mods.error"], Color.crimson)
              }
              if (stat.isBlackListed()) {
                buildStatus(status, Icon.infoCircleSmall, Core.bundle["dialog.mods.blackListed"], Color.crimson)
              }
            }
            details.row()
            details.line(Color.gray, true, 4f).pad(6f).padLeft(-6f).padRight(-6f)
            details.row()

            val contents = if (stat.isEnabled()) Vars.content.contentMap.map { it.toList() }
              .flatten()
              .filterIsInstance<UnlockableContent>()
              .filter { c -> c.minfo.mod === mod && !c.isHidden }
            else listOf()

            var current = -1
            details.table { switch ->
              switch.left().defaults().center()
              switch.button({ it.add(Core.bundle["dialog.mods.description"], 0.85f) }, switchBut) { setupContent(0) }
                .margin(12f).checked { current == 0 }.disabled { t -> t.isChecked }
              switch.button({ it.add(Core.bundle["dialog.mods.rawText"], 0.85f) }, switchBut) { setupContent(1) }
                .margin(12f).checked { current == 1 }.disabled { t -> t.isChecked }
              if (contents.any()) {
                switch.button({ it.add(Core.bundle["dialog.mods.contents"], 0.85f) }, switchBut) { setupContent(2) }
                  .margin(12f).checked { current == 2 }.disabled { t -> t.isChecked }
              }
            }.grow().padBottom(0f)
            details.row()
            details.table(HeAssets.grayUI) { desc ->
              desc.defaults().grow()
              setupContent = a@{ i ->
                if (i == current) return@a

                desc.clearChildren()
                current = i

                when (i) {
                  0 -> desc.add(Markdown(mod.meta.description ?: "", MarkdownStyles.defaultMD))
                  1 -> desc.add(mod.meta.description ?: "").wrap()
                  2 -> {
                    Core.app.post {
                      val n = (desc.width/Scl.scl(50f)).toInt()
                      contents.forEachIndexed { i, c ->
                        if (i > 0 && i%n == 0) desc.row()

                        desc.button(TextureRegionDrawable(c.uiIcon), Styles.flati, Vars.iconMed) {
                          Vars.ui.content.show(c)
                        }.size(50f).with { im ->
                          val click = im.clickListener
                          im.update {
                            im.image.color.lerp(
                              if (!click.isOver) Color.lightGray else Color.white,
                              0.4f*Time.delta
                            )
                          }
                        }.tooltip(c.localizedName)
                      }
                    }
                  }
                }
              }
            }.grow().margin(12f).padTop(0f)
          }
        },
        Table{ lay ->
          ModStat.apply {
            lay.top().right().table { l ->
              l.line(Color.darkGray, false, 3f)
              l.table { buttons ->
                buttons.collapser(
                  {
                    it.button(Icon.upSmall, Styles.clearNonei, 48f) {
                      val latest = updateEntry?.latestMod
                      if (latest != null) {
                        downloadMod(latest)
                      }
                      else UIUtils.showError(Core.bundle["dialog.mods.noDownloadLink"])
                    }.size(48f).visible { stat.isUpToDate() }
                  }, false
                ) { stat.isUpToDate() }.fill()
                buttons.row()
                buttons.button(Icon.trashSmall, Styles.clearNonei, 48f) { deleteMod(mod) }.size(48f)
              }.fill()
            }.fill()
          }
        }
      ).grow()
    }.also { it.setDuration(0.3f, Interp.pow3Out) }).growX().fillY().colspan(2).get()

    return res
  }

  private fun openFolder() {
    val path = Vars.modDirectory.absolutePath()

    if (Core.app.isMobile) {
      UIUtils.showPane(
        Core.bundle["dialog.mods.openFolderFailed"],
        closeBut,
        ButtonEntry(Core.bundle["misc.copy"], Icon.copy) {
          Core.app.clipboardText = path
          Vars.ui.showInfoFade(Core.bundle["infos.copied"])
        }
      ){ t ->
        t.add(Core.bundle["dialog.mods.cantOpenOnAndroid"]).growX().pad(6f).left()
          .labelAlign(Align.left).color(Color.lightGray)
        t.row()
        t.table(HeAssets.darkGrayUIAlpha) { l ->
          l.image(Icon.folder).scaling(Scaling.fit).pad(6f).size(36f)
          l.add(path).pad(6f)
        }.margin(12f)
      }
    }
    else Core.app.openFolder(path)
  }

  private fun importGithub() {
    var tipLabel: Label? = null

    UIUtils.showInput(
      Core.bundle["mod.import.github"],
      Core.bundle["dialog.mods.inputGithubLink"],
      buildContent = { cont ->
        cont.add(HeCollapser(collX = false, collY = true, collapsed = true){
          tipLabel = it.add("").growX().labelAlign(Align.left).pad(8f).get()
        }.setDuration(0.3f, Interp.pow3Out).setCollapsed { tipLabel?.text?.isBlank()?:true }).fillY().growX()
      }
    ){ dialog, txt ->
      tipLabel?.setText(Core.bundle["dialog.mods.parsing"])
      tipLabel?.setColor(Pal.accent)
      val link = if (txt.startsWith("https://")) txt.substring(8) else txt
      if (link.startsWith("github.com/")){
        val repo = link.substring(11)
        Http.get(
          Vars.ghApi + "/repos/" + repo + "/releases/latest",
          { res ->
            if (res.status != Http.HttpStatus.OK) throw Exception("not found")
            val jval = Jval.read(res.getResultAsString())
            val tagLink = "https://raw.githubusercontent.com/${repo}/${jval.getString("tag_name")}"

            val modJ = tryList(
              "$tagLink/mod.json",
              "$tagLink/mod.hjson",
              "$tagLink/assets/mod.json",
              "$tagLink/assets/mod.hjson",
            )

            if (modJ == null) throw Exception("not found")

            var repoMeta: Jval? = null
            Http.get(Vars.ghApi + "/repos/" + repo)
              .error {
                tipLabel?.setText(Core.bundle["dialog.mods.parseFailed"])
                tipLabel?.setColor(Color.crimson)
              }
              .block { repoMeta = Jval.read(it.getResultAsString()) }

            repoMeta!!
            val lang = repoMeta.getString("language", "")

            val modInfo = ModListing().also {
              it.repo = repo
              it.internalName = modJ.getString("name")
              it.name = modJ.getString("displayName")
              it.subtitle = modJ.getString("subtitle")
              it.author = modJ.getString("author")
              it.version = modJ.getString("version")
              it.hidden = modJ.getBool("hidden", false)
              it.lastUpdated = repoMeta.getString("pushed_at")
              it.stars = repoMeta.getInt("stargazers_count", 0)
              it.description = modJ.getString("description")
              it.minGameVersion = modJ.getString("minGameVersion")
              it.hasScripts = lang == "JavaScript"
              it.hasJava = modJ.getBool("java", false)
                           || lang == "Java"
                           || lang == "Kotlin"
                           || lang == "Groovy"
                           || lang == "Scala"
            }

            Core.app.post {
              dialog!!.hide()
              downloadMod(modInfo)
            }
          }
        ){
          Core.app.post {
            if (it is IllegalArgumentException) tipLabel?.setText(Core.bundle["dialog.mods.parseFailed"])
            else tipLabel?.setText(Core.bundle["dialog.mods.checkFailed"])
            tipLabel?.setColor(Color.crimson)
          }
        }
      }
      else {
        tipLabel?.setText(Core.bundle["dialog.mods.parseFailed"])
        tipLabel?.setColor(Color.crimson)
      }
    }
  }

  private fun tryList(vararg queries: String): Jval? {
    var result: Jval? = null
    for (str in queries) {
      Http.get(str)
        .timeout(10000)
        .block { out -> result = Jval.read(out!!.getResultAsString()) }
      if (result != null) return result
    }
    return null
  }

  private fun importFile() {
    Vars.platform.showMultiFileChooser({ file ->
      try {
        Vars.mods.importMod(file)
        modTabs.clear()
        rebuildMods()
      } catch (e: java.lang.Exception) {
        Log.err(e)
        UIUtils.showException(
          e, if (e.message != null && e.message!!.lowercase().contains("writable dex")) "@error.moddex" else ""
        )
      }
    }, "zip", "jar")
  }

  private fun deleteMod(mod: Mods.LoadedMod) {
    if (Name(mod) == Name("EBwilson", "he")) {
      UIUtils.showConfirm(Core.bundle["dialog.mods.deleteMod"], Core.bundle["dialog.mods.confirmDeleteHe"]) {
        Vars.mods.removeMod(mod)
        Core.app.exit()
      }
    }
    else {
      UIUtils.showConfirm(Core.bundle["dialog.mods.deleteMod"], Core.bundle["mod.remove.confirm"]) {
        Vars.mods.removeMod(mod)
        rebuildMods()
      }
    }
  }

  private fun exportLink(mod: Mods.LoadedMod) {
    getModList(
      errHandler = { e ->
        Log.err(e)
        UIUtils.showException(e, Core.bundle["dialog.mods.checkFailed"])
      }
    ) { list ->
      val info = list.get(Name(mod))

      if (info != null) {
        val link = "https://github.com/${info.repo}"

        UIUtils.showPane(
          Core.bundle["dialog.mods.exportLink"],
          closeBut,
          ButtonEntry(Core.bundle["misc.open"], Icon.link) {
            Core.app.openURI(link)
          },
          ButtonEntry(Core.bundle["misc.copy"], Icon.copy) {
            Core.app.clipboardText = link
            Vars.ui.showInfoFade(Core.bundle["infos.copied"])
          }
        ){ t ->
          t.add(Core.bundle["dialog.mods.githubLink"]).growX().pad(6f).left()
            .labelAlign(Align.left).color(Color.lightGray)
          t.row()
          t.table(HeAssets.darkGrayUIAlpha) { l ->
            l.image(Icon.github).scaling(Scaling.fit).pad(6f).size(36f)
            l.add(link).pad(6f)
          }.margin(12f)
        }
      }
      else {
        UIUtils.showTip(
          Core.bundle["dialog.mods.noLink"],
          '' + Core.bundle["dialog.mods.noGithubRepo"]
        )
      }
    }
  }

  private fun downloadMod(modInfo: ModListing, callback: Runnable? = null) {
    var progress = 0f
    var downloading = false
    var complete = false
    var task: Future<*>? = null

    val loaded = Vars.mods.getMod(modInfo.internalName)
    val isUpdate = loaded != null && loaded.meta.version != modInfo.version

    UIUtils.showPane(
      Core.bundle[if (isUpdate) "dialog.mods.updateMod" else "dialog.mods.downloadMod"],
      ButtonEntry(
        Core.bundle["cancel"],
        Icon.cancel
      ) {
        task?.cancel(true)
        it.hide()
      },
      ButtonEntry(
        { Core.bundle[if (complete) "misc.complete" else "misc.download"] },
        { if (complete) Icon.ok else Icon.download },
        disabled = { downloading && !complete }
      ) {
        if (complete) {
          it.hide()
          return@ButtonEntry
        }

        downloading = true

        task = exec.submit {
          Http.get(Vars.ghApi + "/repos/" + modInfo.repo + "/releases/latest")
            .error { e ->
              downloading = false
              if (e is InterruptedException) return@error
              Log.err(e)
              Core.app.post {
                UIUtils.showException(e, Core.bundle["dialog.mods.checkFailed"])
              }
            }
            .block { result ->
              val json = Jval.read(result.resultAsString)
              val assets = json.get("assets").asArray()

              val dexedAsset = assets.find { j ->
                j.getString("name").startsWith("dexed")
                && j.getString("name").endsWith(".jar")
              }
              val jarAssets = dexedAsset ?: assets.find { j ->
                j.getString("name").endsWith(".jar")
              }
              val asset = jarAssets ?: assets.find { j ->
                j.getString("name").endsWith(".zip")
              }

              val suffix = if (dexedAsset == null && jarAssets == null) ".zip" else ".jar"

              val url = if (asset != null) {
                asset.getString("browser_download_url")
              }
              else {
                json.getString("zipball_url")
              }

              val fi = Vars.modDirectory.child("tmp").child(modInfo.internalName + suffix)
              Downloader.downloadToFile(
                url, fi, true,
                { p -> progress = p },
                { e ->
                  if (e is InterruptedException) return@downloadToFile
                  Log.err(e)
                  Core.app.post {
                    UIUtils.showException(e, Core.bundle["dialog.mods.downloadFailed"])
                  }
                }
              ) {
                Core.app.post {
                  try {
                    if (isUpdate) {
                      loaded.also { m -> Vars.mods.removeMod(m) }
                    }
                    Vars.mods.importMod(fi)
                    fi.delete()
                    complete = true
                    rebuildMods()
                    callback?.run()
                  } catch (e: Exception) {
                    Log.err(e)
                    UIUtils.showException(e, Core.bundle["dialog.mods.downloadFailed"])
                  }
                }
              }
            }
        }
      }
    ){ t ->
      val iconLink = "https://raw.githubusercontent.com/EB-wilson/HeMindustryMods/master/icons/" + modInfo.repo.replace("/", "_")
      val image = Downloader.downloadImg(iconLink, Core.atlas.find("nomap"))

      t.table(HeAssets.darkGrayUIAlpha){ cont ->
        cont.table(Tex.buttonSelect) { icon ->
          icon.image(image).scaling(Scaling.fit).size(80f)
        }.pad(10f).margin(4f).size(88f)
        cont.stack(
          Table { info ->
            info.left().top().defaults().left().pad(3f)
            info.add(modInfo.name).color(Pal.accent)
            info.row()
            if (loaded != null) {
              if (loaded.meta.version != modInfo.version) {
                info.add("[lightgray]${loaded.meta.version}  >>>  [accent]${modInfo.version}")
              }
              else {
                info.add("[lightgray]${loaded.meta.version}  >>>  ${modInfo.version}" + Core.bundle["dialog.mods.reinstall"])
              }
            }
            else info.add(modInfo.version)
            info.row()
            info.table{ b ->
              b.add(Bar(
                {
                  if (complete) Core.bundle["dialog.mods.downloadComplete"]
                  else Core.bundle.format(
                    "dialog.mods.downloading",
                    if (progress < 0) (-progress).toStoreSize()
                    else "${Mathf.round(progress*100)}%"
                  )
                },
                { Pal.accent },
                { if (progress < 0) 1f else progress }
              )).growX().pad(6f).height(22f).visible { downloading }
            }.grow()
          },
          Table { info ->
            info.top().right().defaults().right().top()
            info.table { status ->
              status.top().right().defaults().size(26f).pad(4f)
              val stat = modInfo.checkStatus()

              buildModAttrIcons(status, stat)
            }.fill()
            info.row()
            info.table { stars ->
              stars.bottom().right()
              buildStars(stars, modInfo)
            }
          }
        ).pad(12f).padLeft(4f).growX().fillY().minWidth(420f)
      }.margin(6f).growX().fillY()
    }
  }

  private fun buildStars(stars: Table, modInfo: ModListing) {
    stars.add(object : Element() {
      override fun draw() {
        validate()
        Draw.color(Color.darkGray)
        Icon.starSmall.draw(
          x - width*0.2f, y - height*0.2f,
          0f, 0f, width, height,
          1.4f, 1.4f, 0f
        )
        Draw.color(Color.white)
        Icon.starSmall.draw(x, y, width, height)
      }
    }).size(60f).pad(-16f)
    stars.add(modInfo.stars.toString(), Styles.outlineLabel, 0.85f)
      .bottom().padBottom(4f).padLeft(-2f)
  }

  private fun checkModUpdate(
    mod: Mods.LoadedMod,
    errorHandler: Cons<Throwable>,
    callback: Cons<UpdateEntry>
  ) {
    val res = updateChecked.get(mod)
    if (res != null) callback(res)
    else {
      getModList(
        errHandler = { e ->
          Log.err(e)
          errorHandler(e)
        }
      ) { list ->
        val modInfo = list[Name(mod)]

        if (modInfo == null) callback(UpdateEntry(mod, false, null))
        else {
          if (modInfo.version != mod.meta.version) callback(UpdateEntry(mod, true, modInfo))
          else callback(UpdateEntry(mod, false, modInfo))
        }
      }
    }
  }

  private data class UpdateEntry(
    val mod: Mods.LoadedMod,
    val updateValid: Boolean,
    val latestMod: ModListing?,
  )

  inner class HeModsBrowser: BaseDialog(Core.bundle["mods.browser"]) {
    private lateinit var rebuildList: () -> Unit

    private val browserTabs = ObjectMap<ModListing, Table>()
    private val favoritesMods = Seq<ModListing>()

    private var search = ""
    private var orderDate = false
    private var reverse = false
    private var hideInvalid = true

    init {
      shown(::rebuild)
      resized(::rebuild)
    }

    fun rebuild(){
      cont.clearChildren()
      cont.table { main ->
        main.top()
        main.table { top ->
          top.image(Icon.zoom).size(64f).scaling(Scaling.fit)
          top.field(""){
            search = it.lowercase()
            rebuildList()
          }.growX()
          top.button(Icon.list, Styles.emptyi, 32f) {
            orderDate = !orderDate
            rebuildList()
          }.update { b -> b.style.imageUp = (if (orderDate) Icon.list else Icon.star) }
            .size(48f).get()
            .addListener(Tooltip { tip ->
              tip!!.label { if (orderDate) "@mods.browser.sortdate" else "@mods.browser.sortstars" }.left()
            })
          top.button(Icon.list, Styles.emptyi, 32f) {
            reverse = !reverse
            rebuildList()
          }.update { b -> b.style.imageUp = (if (reverse) Icon.upOpen else Icon.downOpen) }
            .size(48f).get()
            .addListener(Tooltip { tip ->
              tip!!.label { if (reverse) "@misc.reverse" else "@misc.sequence" }.left()
            })
          top.check(Core.bundle["dialog.mods.hideInvalid"], hideInvalid) {
            hideInvalid = it
            rebuildList()
          }
        }.growX().padLeft(40f).padRight(40f)
        main.row()
        main.line(Pal.accent, true, 4f).padTop(4f)
        main.row()
        main.add(ScrollPane( CullTable{ list ->
          list.top().defaults().fill()

          val n = max((Core.graphics.width/Scl.scl(540f)).toInt(), 1)

          rebuildList = {
            var favCols: Array<Table>? = null
            var normCols: Array<Table>? = null

            favoritesMods.clear()

            list.clearChildren()
            list.add(" " + Core.bundle["dialog.mods.favorites"]).color(Pal.accent).padLeft(26f)
            list.row()
            list.line(Pal.accent, true, 4f).pad(6f).padLeft(20f).padRight(20f)
            list.row()
            list.cullTable { fav ->
              fav.add(HeCollapser(collX = false, collY = true, background = HeAssets.grayUIAlpha){ col ->
                col.table { t -> t.add(Core.bundle["dialog.mods.noFavorites"]) }.fill().margin(24f)
              }.setCollapsed { favoritesMods.any() }).fill().colspan(n)

              fav.row()

              fav.defaults().width(min(540f, (Core.graphics.width - 80f)/Scl.scl())).fillY().pad(6f)
              favCols = Array(n) {
                fav.cullTable(HeAssets.grayUIAlpha){ it.top().defaults().growX().fillY() }.get()
              }
            }
            list.row()
            list.add(" " + Core.bundle["dialog.mods.mods"]).color(Pal.accent).padLeft(26f)
            list.row()
            list.line(Pal.accent, true, 4f).pad(6f).padLeft(20f).padRight(20f)
            list.row()
            list.cullTable { norm ->
              norm.top().defaults().width(min(540f, (Core.graphics.width - 80f)/Scl.scl())).fillY().pad(6f)
              normCols = Array(n) {
                norm.cullTable(HeAssets.grayUIAlpha){ it.top().defaults().growX().fillY() }.get()
              }
            }
            getModList(
              errHandler = { e ->
                Log.err(e)
                list.clearChildren()
                list.image(HeAssets.networkError).size(48f).pad(6f).color(Color.red)
                list.add(Core.bundle["dialog.mods.checkFailed"], Styles.outlineLabel)
              }
            ) { ls ->
              var favI = 0
              var normI = 0

              ls.values()
                .filter {
                  search.isBlank()
                  || it.name.lowercase().contains(search)
                  || it.internalName.lowercase().contains(search)
                }
                .filter { !hideInvalid || ModStat.run { it.checkStatus().isValid() } }
                .let { l ->
                  if (reverse) {
                    if (orderDate) l.reversed()
                    else l.sortedBy { it.stars }
                  }
                  else {
                    if (orderDate) l
                    else l.sortedBy { -it.stars }
                  }
                }
                .forEach { m ->
                  val key = "mod.favorites.${m.internalName}"
                  val col = if (He.global.getBool(key, false)) {
                    favoritesMods.add(m)
                    favCols!![favI++%n]
                  }
                  else normCols!![normI++%n]
                  val tab = buildModTab(m)

                  col.add(tab).growX().fillY().pad(4f).row()
                }
            }
          }

          rebuildList()
        }, Styles.smallPane)).growY().fillX()
        main.row()
        main.line(Color.gray, true, 4f).padTop(6f).padBottom(6f)
        main.row()
        main.table { bot ->
          bot.defaults().width(242f).height(62f).pad(6f)
          bot.button(Core.bundle["back"], Icon.leftOpen, Styles.grayt, 46f)
          { hide() }
          bot.button(Core.bundle["dialog.mods.refresh"], Icon.refresh, Styles.grayt, 46f) {
            modList = null
            browserTabs.clear()

            rebuildList()
          }
          if (Core.graphics.isPortrait) bot.row()
          bot.button(Core.bundle["dialog.mods.importFav"], Icon.download, Styles.grayt, 46f) {
            importFavorites()
          }
          bot.button(Core.bundle["dialog.mods.exportFav"], Icon.export, Styles.grayt, 46f) {
            exportFavorites()
          }
        }.growX().fillY()
      }.grow()
    }

    private fun importFavorites() {
      UIUtils.showInput(
        Core.bundle["dialog.mods.importFav"],
        Core.bundle["dialog.mods.inputFavText"],
        true
      ){ d, t ->
        val repos = t.split(";").map { it.trim() }.toSet()
        getModList { list ->
          list.values()
            .filter { repos.contains(it.repo) }
            .forEach { m ->
              val key = "mod.favorites.${m.internalName}"
              He.global.put(key, true)
            }

          rebuildList()
          d.hide()
        }
      }
    }

    private fun exportFavorites() {
      if (favoritesMods.isEmpty){
        UIUtils.showTip(
          null,
          Core.bundle["dialog.mods.noFavorites"]
        )
        return
      }

      val mods = StringBuilder()
      favoritesMods.forEach {  m ->
        mods.append(m.repo).append(";\n")
      }

      UIUtils.showPane(
        Core.bundle["dialog.mods.exportFav"],
        closeBut,
        ButtonEntry(Core.bundle["misc.copy"], Icon.copy) {
          Vars.ui.showInfoFade(Core.bundle["infos.copied"])
          Core.app.clipboardText = mods.toString()
        },
        ButtonEntry(Core.bundle["misc.save"], Icon.file) {
          Vars.platform.showFileChooser(false, "zip") { f ->
            f.writer(false).write(mods.toString())
          }
        }
      ){ t ->
        t.add(Core.bundle["dialog.mods.favoritesText"]).growX().pad(6f).left()
          .labelAlign(Align.left).color(Color.lightGray)
        t.row()
        t.table(HeAssets.darkGrayUIAlpha) { l ->
          l.left().top().add(mods, Label.LabelStyle(MarkdownStyles.defaultMD.codeFont, Color.white))
            .pad(6f).wrap()
        }.margin(12f).minWidth(420f).growX()
      }
    }

    private fun buildModTab(mod: ModListing): Table {
      browserTabs[mod]?.also { return it }

      val res = Table()
      val stat = mod.checkStatus()
      var coll: HeCollapser? = null
      var setupContent = { i: Int -> }
      val key = "mod.favorites.${mod.internalName}"

      browserTabs[mod] = res

      val iconLink = "https://raw.githubusercontent.com/Anuken/MindustryMods/master/icons/" + mod.repo.replace("/", "_")
      val image = Downloader.downloadLazyDrawable(iconLink, Core.atlas.find("nomap"))
      val loaded = Vars.mods.getMod(mod.internalName)

      res.button(
        { top ->
          top.table(Tex.buttonSelect) { icon ->
            icon.stack(
              Image(image).setScaling(Scaling.fit),
              Table { stars ->
                stars.bottom().left()
                buildStars(stars, mod)
              }
            ).size(80f)
          }.pad(10f).margin(4f).size(88f)
          top.stack(
            Table { info ->
              info.left().top().margin(12f).marginLeft(6f).defaults().left()
              info.add(mod.name).color(Pal.accent).growX().labelAlign(Align.left).padRight(160f).wrap()
              info.row()
              info.add(mod.version, 0.8f).color(Color.lightGray).growX().padRight(50f).wrap()
              info.row()
              info.add(mod.shortDescription()).growY().growX().padRight(50f).wrap()
            },
            Table { over ->
              over.top().right()

              over.table { status ->
                status.top().defaults().size(26f).pad(4f)

                loaded?.also { loaded ->
                  if (loaded.meta.version != mod.version){
                    status.image(Icon.starSmall).scaling(Scaling.fit).color(HeAssets.lightBlue)
                      .addTip(Core.bundle["dialog.mods.newVersion"])
                  }
                  else {
                    status.image(Icon.okSmall).scaling(Scaling.fit).color(Pal.heal)
                      .addTip(Core.bundle["dialog.mods.installed"])
                  }
                }

                buildModAttrIcons(status, stat)
              }.fill().pad(4f)

              over.table { side ->
                side.line(Color.darkGray, false, 3f)
                side.table { buttons ->
                  buttons.defaults().size(48f)
                  buttons.button(Icon.star, Styles.clearNonei, 24f) {
                    He.global.put(key, !He.global.getBool(key, false))
                    rebuildList()
                  }.update { b ->
                    b.image.setScale(0.9f)
                    b.style.imageUpColor = if (He.global.getBool(key, false)) Pal.accent else Color.white
                  }

                  buttons.row()
                  buttons.button(Icon.downloadSmall, Styles.clearNonei, 48f) {
                    downloadMod(mod){
                      browserTabs.clear()
                      rebuildList()
                    }
                  }
                  buttons.row()

                  buttons.addEventBlocker()
                }.fill()
              }.fill()
            }
          ).grow()
        }, Styles.grayt) {
        coll!!.toggle()
        if (!coll!!.collapse){
          setupContent(0)
        }
      }.growX().fillY()

      res.row()
      coll = res.add(HeCollapser(collX = false, collY = true, collapsed = true, Styles.grayPanel){ col ->
        col.table { details ->
          details.left().defaults().growX().pad(4f).padLeft(12f).padRight(12f)

          details.add(Core.bundle.format("dialog.mods.author", mod.author))
            .growX().padRight(50f).wrap().color(Pal.accent).labelAlign(Align.left)
          details.row()
          details.table { link ->
            link.left().image(Icon.githubSmall).scaling(Scaling.fit).size(24f).color(Color.lightGray)
            val linkButton = link.button("...", Styles.nonet) {}
              .padLeft(4f).wrapLabel(true)
              .growX().left().align(Align.left).height(30f).disabled(true).get()

            linkButton.label.setAlignment(Align.left)
            linkButton.label.setFontScale(0.9f)

            val url = "https://github.com/${mod.repo}"
            linkButton.isDisabled = false
            linkButton.setText(url)
            linkButton.clicked { Core.app.openURI(url) }
          }
          details.row()
          details.table { status ->
            status.left().defaults().left()

            loaded?.also { loaded ->
              if (loaded.meta.version != mod.version){
                buildStatus(status, Icon.starSmall, Core.bundle["dialog.mods.newVersion"], HeAssets.lightBlue)
              }
              else {
                buildStatus(status, Icon.okSmall, Core.bundle["dialog.mods.installed"], Pal.heal)
              }
            }

            buildModAttrList(status, stat)
          }
          details.row()
          details.line(Color.gray, true, 4f).pad(6f).padLeft(-6f).padRight(-6f)
          details.row()

          var current = -1
          details.table { switch ->
            switch.left().defaults().center()
            switch.button({ it.add(Core.bundle["dialog.mods.description"], 0.85f) }, switchBut) { setupContent(0) }
              .margin(12f).checked { current == 0 }.disabled { t -> t.isChecked }
            switch.button({ it.add(Core.bundle["dialog.mods.rawText"], 0.85f) }, switchBut) { setupContent(1) }
              .margin(12f).checked { current == 1 }.disabled { t -> t.isChecked }
          }.grow().padBottom(0f)
          details.row()
          details.table(HeAssets.grayUI) { desc ->
            desc.defaults().grow()
            setupContent = a@{ i ->
              if (i == current) return@a

              desc.clearChildren()
              current = i

              when (i) {
                0 -> desc.add(Markdown(mod.description, MarkdownStyles.defaultMD))
                1 -> desc.add(mod.description).wrap()
              }
            }
          }.grow().margin(12f).padTop(0f)
        }.grow()
      }.also { it.setDuration(0.3f, Interp.pow3Out) }).growX().fillY().colspan(2).get()

      return res
    }
  }
}

private fun buildModAttrIcons(status: Table, stat: Int) {
  ModStat.apply {
    if (stat.isJAR()) status.image(HeAssets.java).scaling(Scaling.fit).color(Pal.reactorPurple)
      .addTip(Core.bundle["dialog.mods.jarMod"])
    if (stat.isJS()) status.image(HeAssets.javascript).scaling(Scaling.fit).color(Pal.accent)
      .addTip(Core.bundle["dialog.mods.jsMod"])
    if (!stat.isClientOnly()) status.image(Icon.hostSmall).scaling(Scaling.fit).color(Pal.techBlue)
      .addTip(Core.bundle["dialog.mods.hostMod"])

    if (stat.isDeprecated()) status.image(Icon.warningSmall).scaling(Scaling.fit).color(Color.crimson)
      .addTip(
        Core.bundle.format(
          "dialog.mods.deprecated",
          if (stat.isJAR()) Vars.minJavaModGameVersion else Vars.minModGameVersion
        )
      )
    else if (stat.isUnsupported()) status.image(Icon.warningSmall).scaling(Scaling.fit).color(Color.crimson)
      .addTip(Core.bundle["dialog.mods.unsupported"])
  }
}

fun buildStatus(status: Table, icon: Drawable, information: String, color: Color) {
  status.image(icon).scaling(Scaling.fit).color(color).size(26f).pad(4f)
  status.add(information, 0.85f).color(color)
  status.row()
}

private fun buildModAttrList(status: Table, stat: Int) {
  ModStat.apply {
    if (stat.isJAR()) {
      buildStatus(status, HeAssets.java, Core.bundle["dialog.mods.jarMod"], Pal.reactorPurple)
    }
    if (stat.isJS()) {
      buildStatus(status, HeAssets.javascript, Core.bundle["dialog.mods.jsMod"], Pal.accent)
    }
    if (!stat.isClientOnly()) {
      buildStatus(status, Icon.hostSmall, Core.bundle["dialog.mods.hostMod"], Pal.techBlue)
    }

    if (stat.isDeprecated()) {
      buildStatus(
        status, Icon.warningSmall, Core.bundle.format(
          "dialog.mods.deprecated",
          if (stat.isJAR()) Vars.minJavaModGameVersion else Vars.minModGameVersion
        ), Color.crimson
      )
    }
    else if (stat.isUnsupported()) {
      buildStatus(status, Icon.warningSmall, Core.bundle["dialog.mods.unsupported"], Color.crimson)
    }
  }
}

class ModListing {
  var repo: String = "???"
  var name: String = "???"
  var internalName: String = "???"
  var subtitle: String? = null
  var author: String = "???"
  var version: String = "???"
  var hidden: Boolean = false
  var lastUpdated: String = "???"
  var description: String? = null
  var minGameVersion: String? = null
  var hasScripts: Boolean = false
  var hasJava: Boolean = false
  var stars: Int = 0

  fun checkStatus(): Int {
    var res = 0

    if (hasJava) res = res or JAR_MOD
    if (hasScripts) res = res or JS_MOD
    if (hidden) res = res or CLIENT_ONLY

    if (getMinMajor() < (if (hasJava) Vars.minJavaModGameVersion else Vars.minModGameVersion)) res = res or DEPRECATED
    if (!Version.isAtLeast(minGameVersion)) res = res or UNSUPPORTED

    return res
  }

  fun shortDescription(): String {
    return Strings.truncate(
      if (subtitle == null) (if (description == null || description!!.length > Vars.maxModSubtitleLength) "" else description) else subtitle,
      Vars.maxModSubtitleLength,
      "..."
    )
  }

  private fun getMinMajor(): Int {
    val ver: String = (if (minGameVersion == null) "0" else minGameVersion)!!
    val dot = ver.indexOf(".")
    return if (dot != -1) Strings.parseInt(ver.substring(0, dot), 0)
           else Strings.parseInt(ver, 0)
  }

  override fun toString(): String {
    return "ModListing{" +
       "repo='" + repo + '\'' +
       ", name='" + name + '\'' +
       ", internalName='" + internalName + '\'' +
       ", author='" + author + '\'' +
       ", version='" + version + '\'' +
       ", lastUpdated='" + lastUpdated + '\'' +
       ", description='" + description + '\'' +
       ", minGameVersion='" + minGameVersion + '\'' +
       ", hasScripts=" + hasScripts +
       ", hasJava=" + hasJava +
       ", stars=" + stars +
       '}'
  }
}

data class Name(
  val author: String,
  val name: String,
){
  private val hash = author.hashCode()*31 xor 31 + name.hashCode() xor 31

  constructor(loaded: Mods.LoadedMod): this(loaded.meta.author?:"*", loaded.name)
  constructor(loaded: ModListing): this(loaded.author, loaded.internalName)

  override fun hashCode(): Int {
    return hash
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Name) return false

    if (author != "*" && author != other.author) return false
    if (name != other.name) return false

    return true
  }

  override fun toString() = "$author-$name"
}

object Lock
@Suppress("UNCHECKED_CAST")
fun getModList(
  index: Int = 0,
  refresh: Boolean = false,
  errHandler: Cons<Throwable>? = null,
  listener: Cons<OrderedMap<Name, ModListing>>,
) {
  if (index >= He.modJsonURLs.size) return
  if (refresh) modList = null

  if (modList != null) {
    listener.get(modList)
    return
  }

  exec.submit {
    synchronized(Lock) {
      if (modList != null) {
        Core.app.post {
          listener.get(modList)
        }
        return@synchronized
      }

      val req = Http.get(He.modJsonURLs[index])
      req.error { err ->
        if (index < He.modJsonURLs.size - 1) {
          getModList(index + 1, false, errHandler, listener)
        }
        else {
          Core.app.post {
            errHandler?.get(err)
          }
        }
      }
      req.block { response ->
        val strResult = response.resultAsString
        try {
          modList = OrderedMap()
          val list = JsonIO.json.fromJson(Seq::class.java, ModListing::class.java, strResult) as Seq<ModListing>
          val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
          val parser = Func { text: String ->
            try {
              return@Func d.parse(text)
            } catch (_: Exception) {
              return@Func Date()
            }
          }

          list.sortComparing { m -> parser.get(m!!.lastUpdated) }.reverse()
          list.forEach { modList!![Name(it.author, it.internalName)] = it }

          Core.app.post {
            listener.get(modList)
          }
        } catch (e: Exception) {
          Core.app.post {
            errHandler?.get(e)
          }
        }
      }
    }
  }
}