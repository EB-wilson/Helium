package helium.ui.dialogs

import arc.Core
import arc.func.Cons
import arc.graphics.Color
import arc.graphics.g2d.TextureRegion
import arc.math.Interp
import arc.scene.actions.Actions
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.*
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.struct.ObjectMap
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
import helium.ui.dialogs.ModsDialogHelper.addTip
import helium.ui.dialogs.ModsDialogHelper.buildDescSelector
import helium.ui.dialogs.ModsDialogHelper.buildErrorIcons
import helium.ui.dialogs.ModsDialogHelper.buildLinkButton
import helium.ui.dialogs.ModsDialogHelper.buildModAttrIcons
import helium.ui.dialogs.ModsDialogHelper.buildModAttrList
import helium.ui.dialogs.ModsDialogHelper.buildStatus
import helium.ui.dialogs.ModsDialogHelper.getModList
import helium.ui.dialogs.ModsDialogHelper.setupContentsList
import helium.ui.dialogs.ModsDialogHelper.showDownloadModDialog
import helium.ui.elements.HeCollapser
import helium.util.*
import mindustry.Vars
import mindustry.Vars.modGuideURL
import mindustry.ctype.UnlockableContent
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.graphics.Pal
import mindustry.mod.Mods
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog
import universecore.ui.elements.markdown.Markdown
import universecore.ui.elements.markdown.MarkdownStyles

class HeModsDialog: BaseDialog(Core.bundle["mods"]) {
  val browser = HeModsBrowser()

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
            fun buildModsLayout(list: Table, reback: Cons<Table>) {
              list.line(Pal.accent, true, 4f).padTop(6f).padBottom(6f)
              list.row()
              list.top().pane(Styles.smallPane, reback)
                .width(Core.graphics.width/2.8f/Scl.scl())
                .fillY().top().get().setForceScroll(false, true)
            }

            mods.table { left ->
              left.table(HeAssets.grayUIAlpha){ list ->
                list.add(Core.bundle["dialog.mods.enabled"]).color(Pal.accent)
                list.row()
                buildModsLayout(list){
                  this.enabled = it
                }
              }.fillX().growY()
            }.fillX().growY()
            mods.line(Color.gray, false, 4f).padLeft(6f).padRight(6f)

            mods.table { right ->
              right.table(HeAssets.grayUIAlpha){ list ->
                list.add(Core.bundle["dialog.mods.disabled"]).color(Pal.accent)
                list.row()
                buildModsLayout(list){
                  this.disabled = it
                }
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
    ModsDialogHelper.resetModListCache()
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

                  buildErrorIcons(status, stat)
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
              buildLinkButton(link, Name(mod))
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
            buildDescSelector(details, { current }, { i -> setupContent(i) }, contents)
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
                  2 -> setupContentsList(desc, contents)//Core.app.post { setupContentsList(desc, contents) }
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
                        showDownloadModDialog(latest){
                          rebuildMods()
                        }
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
              showDownloadModDialog(modInfo){
                rebuildMods()
              }
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
    if (Name(mod) == Name("ebwilson", "helium")) {
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
}

