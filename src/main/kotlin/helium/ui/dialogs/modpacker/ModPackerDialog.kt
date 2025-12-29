package helium.ui.dialogs.modpacker

import arc.Core
import arc.files.Fi
import arc.graphics.Color
import arc.graphics.Texture
import arc.graphics.g2d.TextureRegion
import arc.math.Interp
import arc.math.Mathf
import arc.scene.event.HandCursorListener
import arc.scene.event.Touchable
import arc.scene.style.BaseDrawable
import arc.scene.style.Drawable
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.Button
import arc.scene.ui.ScrollPane
import arc.scene.ui.TextButton
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.util.Align
import arc.util.Scaling
import helium.addEventBlocker
import helium.ui.HeAssets
import helium.ui.UIUtils
import helium.ui.UIUtils.line
import helium.ui.dialogs.ModsDialogHelper.buildDescSelector
import helium.ui.dialogs.ModsDialogHelper.buildErrorIcons
import helium.ui.dialogs.ModsDialogHelper.buildLinkButton
import helium.ui.dialogs.ModsDialogHelper.buildModAttrIcons
import helium.ui.dialogs.ModsDialogHelper.buildModAttrList
import helium.ui.dialogs.ModsDialogHelper.setupContentsList
import helium.ui.dialogs.Name
import helium.ui.elements.HeCollapser
import helium.util.ModStat
import helium.util.ModStat.isValid
import helium.util.accessField
import mindustry.Vars
import mindustry.content.Planets
import mindustry.ctype.UnlockableContent
import mindustry.game.Saves
import mindustry.game.Schematics
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.graphics.Pal
import mindustry.type.Planet
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog
import mindustry.ui.dialogs.CampaignRulesDialog
import mindustry.ui.dialogs.LoadDialog
import mindustry.ui.dialogs.PlanetDialog
import universecore.ui.elements.markdown.Markdown
import universecore.ui.elements.markdown.MarkdownStyles
import kotlin.math.max

class ModPackerDialog: BaseDialog(Core.bundle["dialog.modPacker.title"]) {
  private var search = ""
  private var showAll = false

  private var model = PackModel()

  private lateinit var selectedTab: Table
  private lateinit var unselectedTab: Table

  private lateinit var optionsTab: Table
  private lateinit var fileEntriesTab: Table
  private var rebuildFiles = {}

  private var optionShow = false
  private var filesShow = false

  private val selectCampaignDialog = object: PlanetDialog(){
    val PlanetDialog.campaignRules: CampaignRulesDialog by accessField("campaignRules")

    fun selectable(planet: Planet): Boolean {
      return (planet.alwaysUnlocked && planet.isLandable) || planet.sectors.contains { obj -> obj.hasBase() } || debugSelect
    }

    init {
      shown {
        fill { t ->
          t.top().left()
          val pane = ScrollPane(null, Styles.smallPane)
          t.add(pane).colspan(2).row()
          t.button("@campaign.difficulty", Icon.bookSmall) {
            campaignRules.show(state.planet)
          }.margin(12f).size(208f, 40f).padTop(12f)
            .visible { state.planet.allowCampaignRules && mode != Mode.planetLaunch }.row()
          t.add().height(64f) //padding for close button
          val starsTable = Table(Styles.black)
          pane.setWidget(starsTable)
          pane.setScrollingDisabled(true, false)

          var starCount = 0
          for (star in Vars.content.planets()) {
            if (star.solarSystem !== star || !Vars.content.planets()
                .contains { p: Planet? -> p!!.solarSystem === star && selectable(p) }
            ) continue

            starCount++
            if (starCount > 1) starsTable.add(star.localizedName).padLeft(10f).padBottom(10f).padTop(10f).left()
              .width(190f).row()
            val planetTable = Table()
            planetTable.margin(4f) //less padding
            starsTable.add<Table?>(planetTable).left().row()
            for (planet in Vars.content.planets()) {
              if (planet.solarSystem === star && selectable(planet)) {
                val planetButton: Button = planetTable.button(
                  planet.localizedName,
                  Icon.icons.get(planet.icon + "Small", Icon.icons.get(planet.icon, Icon.commandRallySmall)),
                  Styles.flatTogglet
                ) {
                  viewPlanet(planet, false)
                }.width(200f).height(40f).update { bb -> bb!!.setChecked(state.planet === planet) }
                  .with { w -> w!!.marginLeft(10f) }.get()
                planetButton.getChildren().get(1).setColor(planet.iconColor)
                planetButton.setColor(planet.iconColor)
                planetTable.background(Tex.pane).row()
              }
            }
          }
        }
      }
    }
  }
  val selectSchematicDialog = SelectSchematicDialog()

  init {
    shown{
      model = PackModel()
      initModel(model)
      Vars.tmpDirectory.deleteDirectory()
      rebuild()
    }
    resized(::rebuild)
    hidden {
      Vars.tmpDirectory.deleteDirectory()
    }
  }

  private fun initModel(model: PackModel) {
    Vars.mods.list().forEach { mod -> model.mods.add(PackModel.ModEntry(mod)) }
  }

  fun rebuild() {
    cont.clearChildren()

    if (Core.graphics.isPortrait){
      cont.table { main ->
        main.top()
        main.table { top ->
          top.button(Icon.list, Styles.clearNonei, 32f){
            optionShow = true
            filesShow = false
          }.margin(6f)
          top.image(Icon.zoom).size(64f).scaling(Scaling.fit)
          top.field("") {
            search = it.lowercase()
            rebuildList()
          }.growX()
          top.button(Icon.download, Styles.clearNonei, 32f) {
            openModpack()
          }.margin(6f)
          top.button(Icon.file, Styles.clearNonei, 32f){
            filesShow = true
            optionShow = false
          }.margin(6f)
        }.growX().padLeft(20f).padRight(20f)
        main.row()
        main.table(HeAssets.grayUIAlpha) { cent ->
          cent.top().pane(Styles.smallPane) { mods ->
            mods.table { left ->
              left.table { list ->
                list.add(Core.bundle["dialog.modPacker.unselect"]).color(Pal.accent)
                list.row()
                list.line(Pal.accent, true, 4f).padTop(6f).padBottom(6f)
                list.row()
                list.top().table { unselect ->
                  unselectedTab = unselect
                }.growX().fillY().top().get()
              }.growX().fillY()
            }.growX().fillY()
            mods.row()
            mods.table { right ->
              right.table { list ->
                list.add(Core.bundle["dialog.modPacker.select"]).color(Pal.accent)
                list.row()
                list.line(Pal.accent, true, 4f).padTop(6f).padBottom(6f)
                list.row()
                list.top().table { select ->
                  selectedTab = select
                }.growX().fillY().top().get()
              }.growX().fillY()
            }.growX().fillY()
          }.growX().fillY().get().setForceScroll(false, true)
        }.grow().margin(6f)
        main.row()
        main.table { buttons ->
          buttons.defaults().width(210f).height(62f).pad(4f)
          buttons.button(Core.bundle["back"], Icon.leftOpen, Styles.grayt, 46f) { hide() }
          buttons.button(Core.bundle["dialog.modPacker.export"], Icon.export, Styles.grayt, 46f) {
            exportModpack()
          }
        }.fill()
      }.grow()
    }
    else {
      cont.table { main ->
        main.top()
        main.table { top ->
          if (Core.app.isMobile) {
            top.button(Icon.list, Styles.clearNonei, 32f) {
              optionShow = true
              filesShow = false
            }.margin(6f)
          }
          top.image(Icon.zoom).size(64f).scaling(Scaling.fit)
          top.field("") {
            search = it.lowercase()
            rebuildList()
          }.growX()
          top.check(Core.bundle["dialog.modPacker.showAll"], showAll) {
            showAll = it
            rebuildList()
          }
          if (Core.app.isMobile) {
            top.button(Icon.file, Styles.clearNonei, 32f){
              filesShow = true
              optionShow = false
            }.margin(6f)
          }
        }.growX().padLeft(40f).padRight(40f)
        main.row()
        main.table { mods ->
          mods.table { left ->
            left.table(HeAssets.grayUIAlpha) { list ->
              list.add(Core.bundle["dialog.modPacker.unselect"]).color(Pal.accent)
              list.row()
              list.line(Pal.accent, true, 4f).padTop(6f).padBottom(6f)
              list.row()
              list.top().pane(Styles.smallPane) { unselect ->
                unselectedTab = unselect
              }.width(Core.graphics.width/2.8f/Scl.scl()).fillY().top().get().setForceScroll(false, true)
            }.fillX().growY()
          }.fillX().growY()
          mods.line(Color.gray, false, 4f).padLeft(6f).padRight(6f)
          mods.table { right ->
            right.table(HeAssets.grayUIAlpha) { list ->
              list.add(Core.bundle["dialog.modPacker.select"]).color(Pal.accent)
              list.row()
              list.line(Pal.accent, true, 4f).padTop(6f).padBottom(6f)
              list.row()
              list.top().pane(Styles.smallPane) { select ->
                selectedTab = select
              }.width(Core.graphics.width/2.8f/Scl.scl()).fillY().top().get().setForceScroll(false, true)
            }.fillX().growY()
          }.fillX().growY()
        }.grow()
        main.row()
        main.table { buttons ->
          buttons.defaults().width(210f).height(62f).pad(4f)
          buttons.button(Core.bundle["back"], Icon.leftOpen, Styles.grayt, 46f) {
            UIUtils.showConfirm(Core.bundle["back"], Core.bundle["dialog.modPacker.discard"]){
              hide()
            }
          }
          buttons.button(Core.bundle["dialog.modPacker.editFIle"], Icon.file, Styles.grayt, 46f) {
            openModpack()
          }
          buttons.button(Core.bundle["dialog.modPacker.export"], Icon.export, Styles.grayt, 46f) {
            exportModpack()
          }
        }.fill()
      }.fillX().growY()
    }

    cont.fill { over ->
      val tex = if (Core.graphics.isPortrait) HeAssets.grayUI else HeAssets.grayUIAlpha

      over.addChild(Table(tex){ options ->
        options.touchable = Touchable.enabled
        options.top().left().defaults().growX().pad(6f)
        optionsTab = options
        buildOptions(options)
      })

      over.addChild(Table(tex){ fileEntries ->
        fileEntries.touchable = Touchable.enabled
        fileEntries.top().left().defaults().growX().pad(6f)
        fileEntriesTab = fileEntries
        buildFileEntries(fileEntries)
      })

      Core.app.post {
        val scenW = cont.width
        val scenH = cont.height

        if (Core.graphics.isPortrait){
          optionsTab.pack()
          optionsTab.width = max(scenW, optionsTab.width)
          optionsTab.height = scenH
          optionsTab.x = -optionsTab.width
          optionsTab.update {
            val toX = if (optionShow) 0f else -optionsTab.width
            optionsTab.x = Mathf.lerpDelta(optionsTab.x, toX, 0.2f)
          }

          fileEntriesTab.pack()
          fileEntriesTab.width = scenW
          fileEntriesTab.height = scenH
          fileEntriesTab.x = scenW
          fileEntriesTab.update {
            val toX = if (filesShow) over.width - fileEntriesTab.width else over.width
            fileEntriesTab.x = Mathf.lerpDelta(fileEntriesTab.x, toX, 0.2f)
          }
        }
        else {
          optionsTab.pack()
          optionsTab.width = max(scenW/4f, optionsTab.width)
          optionsTab.height = scenH
          optionsTab.x = -optionsTab.width
          optionsTab.update {
            val toX = if (optionShow) 0f else -optionsTab.width
            optionsTab.x = Mathf.lerpDelta(optionsTab.x, toX, 0.2f)
          }

          fileEntriesTab.pack()
          fileEntriesTab.width = scenW/4f
          fileEntriesTab.height = scenH
          fileEntriesTab.x = scenW
          fileEntriesTab.update {
            val toX = if (filesShow) over.width - fileEntriesTab.width else over.width
            fileEntriesTab.x = Mathf.lerpDelta(fileEntriesTab.x, toX, 0.2f)
          }

          if (!Core.app.isMobile) over.update {
            val mouse = Core.input.mouse()

            over.stageToLocalCoordinates(mouse)
            val x = mouse.x
            val y = mouse.y

            if (y < 0 || y > over.height) {
              optionShow = false
              filesShow = false
              return@update
            }

            if (!optionShow && x < 80){
              optionShow = true
            }
            else if (optionShow && x > optionsTab.width){
              optionShow = false
            }

            if (!filesShow && x > over.width - 80f) {
              filesShow = true
            }
            else if (filesShow && x < over.width - optionsTab.width) {
              filesShow = false
            }
          }
        }
      }
    }

    rebuildList()
  }

  private fun exportModpack() {
    val stat = ModpackUtil.checkModel(model)

    ModpackStat.apply {
      if (stat.isCorrect()) {
        Vars.platform.showFileChooser(false, Core.bundle["dialog.modPacker.options"], "zip") { file ->
          ModpackUtil.genFile(model, file)
          UIUtils.showTip(Core.bundle["misc.complete"], Core.bundle["dialog.modPacker.completed"])
        }
      }
      else {
        UIUtils.showTip(Core.bundle["dialog.modPacker.failed"], when{
          stat.metainfoError() -> Core.bundle["dialog.modPacker.metaError"]
          stat.filesError() -> Core.bundle["dialog.modPacker.filesError"]
          stat.modsError() -> Core.bundle["dialog.modPacker.modsError"]
          stat.modDependenciesError() -> Core.bundle["dialog.modPacker.modsDependenciesError"]
          else -> throw RuntimeException("?")
        })
      }
    }
  }

  private fun openModpack() {
    Vars.platform.showMultiFileChooser(
      { file ->
        val new = PackModel()
        initModel(new)

        try {
          ModpackUtil.readModpackFile(new, file)
        } catch (e: Exception){
          UIUtils.showException(e)
        }

        model = new
        rebuild()
      }, "zip", "jar")
  }

  private fun buildOptions(options: Table) {
    options.table(Tex.whiteui){
      it.left()
      it.add(Core.bundle["dialog.modPacker.options"], Styles.outlineLabel).pad(12f).left()
      it.add().growX()
      it.button(Icon.cancel, Styles.clearNonei, 32f){
        optionShow = false
      }.margin(6f)
    }.color(Pal.gray).pad(0f)
    options.row()
    options.line(Pal.darkerGray, true, 4f).pad(0f).padBottom(4f)
    options.row()
    options.pane(Styles.smallPane) { list ->
      list.left().defaults().pad(6f).left().growX()
      list.add(Core.bundle["dialog.modPacker.packInfo"]).color(Pal.accent)
      list.row()
      list.line(Pal.accent, true, 3f).padTop(0f)

      list.row()
      list.table(HeAssets.darkGrayUIAlpha){ tab ->
        tab.left().defaults().pad(6f).left()
        tab.add(Core.bundle["dialog.modPacker.packIcon"])
        tab.row()
        tab.left().table(Tex.whiteui){
          var fi: Fi? = null

          it.image(Tex.nomap).size(200f).update { i ->
            if (model.icon != null && fi != model.icon) {
              fi = model.icon
              i.setDrawable(TextureRegionDrawable(TextureRegion(Texture(fi))))
            }
          }
        }.color(Pal.darkerGray).margin(4f).left().get().also { img ->
          img.addListener(HandCursorListener())
          img.clicked {
            Vars.platform.showMultiFileChooser(
              { file ->
                try {
                  val tst = Texture(file)
                  if (tst.width != tst.height) throw RuntimeException("width must equal height")

                  model.icon = file
                } catch (e: Throwable) {
                  UIUtils.showException(e, Core.bundle["dialog.modPacker.imageError"])
                }
              }, ".png", ".jpg")
          }
        }
        tab.row()
        tab.add(Core.bundle["dialog.modPacker.iconRequire"]).padBottom(6f).color(Color.lightGray)
      }

      list.row()
      list.table(HeAssets.darkGrayUIAlpha){ tab ->
        tab.left().defaults().pad(6f).left()
        tab.add(Core.bundle["dialog.modPacker.packName"]).padBottom(0f)
        tab.row()
        tab.field(model.displayName){ model.displayName = it }.padTop(0f).growX()
          .update { it.setColor(if (it.text.isBlank()) Color.red else Color.white) }
        tab.row()
        tab.add(Core.bundle["dialog.modPacker.internalName"]).padBottom(0f)
        tab.row()
        tab.field(model.name){ model.name = it }.padTop(0f).growX()
      }

      list.row()
      list.table(HeAssets.darkGrayUIAlpha){ tab ->
        tab.left().defaults().pad(6f).left()
        tab.add(Core.bundle["dialog.modPacker.description"]).padBottom(0f)
        tab.row()
        tab.area(model.description){ model.description = it }.padTop(0f).growX().height(120f)
      }

      list.row()
      list.table{
        it.defaults().growX().pad(6f)
        it.table(HeAssets.darkGrayUIAlpha){ tab ->
          tab.left().defaults().pad(6f).left()
          tab.add(Core.bundle["dialog.modPacker.author"]).padBottom(0f)
          tab.row()
          tab.field(model.author){ a -> model.author = a }.padTop(0f).growX()
            .update { f -> f.setColor(if (f.text.isBlank()) Color.red else Color.white) }
        }

        it.table(HeAssets.darkGrayUIAlpha) { tab ->
          tab.left().defaults().pad(6f).left()
          tab.add(Core.bundle["dialog.modPacker.version"]).padBottom(0f)
          tab.row()
          tab.field(model.version){ v -> model.version = v }.padTop(0f).growX()
            .update { f -> f.setColor(if (f.text.isBlank()) Color.red else Color.white) }
        }
      }.pad(0f).growX().fillY()

      list.row()
      list.add(Core.bundle["dialog.modPacker.packConfig"]).color(Pal.accent)
      list.row()
      list.line(Pal.accent, true, 3f).padTop(0f)

      list.row()
      list.table(HeAssets.darkGrayUIAlpha) { tab ->
        var hoveringType: PackModel.Type? = null

        tab.left().defaults().pad(6f).left()
        tab.add(Core.bundle["dialog.modPacker.type"]).padBottom(0f)
        tab.row()
        tab.table{ types ->
          types.defaults().pad(4f).growX().fillY()

          PackModel.Type.entries.forEach { type ->
            types.button(type.localized, type.icon, Styles.flatTogglet){
              model.type = type
            }.margin(12f).update {
              it.isChecked = model.type == type
            }.get().let {
              it.hovered { hoveringType = type }
              it.exited { hoveringType = null }
            }
          }
        }.growX().fillY()
        tab.row()
        tab.add("").update { desc ->
          val type = hoveringType ?: model.type
          desc.setText(type.description)
        }.wrap().pad(12f).growX().color(Color.lightGray)
      }

      list.row()
      list.table(HeAssets.darkGrayUIAlpha) { tab ->
        tab.left().defaults().pad(6f).left().growX()
        tab.check(Core.bundle["dialog.modPacker.skipRepeat"], model.skipRepeat)
        { model.skipRepeat = it }.get().left()
        tab.row()
        tab.check(Core.bundle["dialog.modPacker.rawBackup"], model.rawBackup)
        { model.rawBackup = it }.get().left()
        tab.row()
        tab.check(Core.bundle["dialog.modPacker.force"], model.force)
        { model.force = it }.get().left()
        tab.row()
        tab.check(Core.bundle["dialog.modPacker.uncheck"], model.uncheck)
        { model.uncheck = it }.get().left()
      }
    }.growX().fillY()
  }

  private fun buildFileEntries(file: Table) {
    file.table(Tex.whiteui){
      it.left()
      it.add(Core.bundle["dialog.modPacker.fileEntries"], Styles.outlineLabel).pad(12f).left()
      it.add().growX()
      it.button(Icon.cancel, Styles.clearNonei, 32f){
        filesShow = false
      }.margin(6f)
    }.color(Pal.gray).pad(0f)
    file.row()
    file.line(Pal.darkerGray, true, 3f).pad(0f).padBottom(4f)
    file.row()
    file.table { t ->
      t.top().pane(Styles.smallPane){ list ->
        rebuildFiles = {
          list.clearChildren()

          model.fileEntries.forEach { entry ->
            val tab = buildFileTab(entry)
            list.add(tab).growX().padTop(4f).padBottom(4f)
            list.row()
          }
        }
        rebuildFiles()
      }.growX().fillY()
    }.grow()

    val coll = object: Table({ col ->
      col.left().defaults().left().growX().padLeft(6f).padRight(6f)
      col.button(Core.bundle["dialog.modPacker.map"], Icon.mapSmall, Styles.flatt){
        selectMap()
      }.margin(8f)
      col.row()
      col.button(Core.bundle["dialog.modPacker.campaign"], Icon.planetSmall, Styles.flatt){
        selectCampaign()
      }.margin(8f)
      col.row()
      col.button(Core.bundle["dialog.modPacker.schematic"], Icon.bookSmall, Styles.flatt){
        selectSchematic()
      }.margin(8f)
      col.row()
      col.button(Core.bundle["dialog.modPacker.settings"], Icon.settingsSmall, Styles.flatt){
        selectSettings()
      }.margin(8f)
      col.row()
      col.button(Core.bundle["dialog.modPacker.customFile"], Icon.addSmall, Styles.flatt){
        selectCustomFile()
      }.margin(8f)
    }){
      override fun validate() {
        if (parent != null && width != parent.width) {
          y = parent.height
          width = parent.width
          invalidate()
        }
        super.validate()
      }
    }
    coll.visible = false
    file.row()
    file.button(Core.bundle["dialog.modPacker.addFile"], object: BaseDrawable(Icon.addSmall){
      override fun draw(x: Float, y: Float, width: Float, height: Float) {
        (if (!coll.visible) Icon.addSmall else Icon.cancelSmall).draw(x, y, width, height)
      }
    }, Styles.flatt){
      coll.visible = !coll.visible
    }.margin(8f).padTop(0f).update {
      it.setText(if (!coll.visible) Core.bundle["dialog.modPacker.addFile"] else Core.bundle["misc.cancel"])
    }.get().let { t ->
      coll.pack()
      t.addChild(coll)
    }
  }

  private fun buildFileTab(entry: PackModel.FileEntry): Table {
    return Table(HeAssets.darkGrayUIAlpha){ tab ->
      val ext = entry.fi.extension()

      val img = when(ext){
        "png", "jpg" -> TextureRegionDrawable(TextureRegion(Texture(entry.fi)))
        "msav" -> Icon.map
        "msch" -> TextureRegionDrawable(TextureRegion(Vars.schematics.getPreview(Schematics.read(entry.fi))))
        "bin" -> Icon.settings
        else -> Icon.file
      }

      tab.image(img).scaling(Scaling.fit).size(64f).pad(6f)
      tab.table { info ->
        info.left()
        info.table{ t ->
          t.add(entry.fi.name()).pad(6f).left().growX()
          t.button(Icon.cancel, Styles.clearNonei, 24f){
            model.fileEntries.remove(entry)
            rebuildFiles()
          }.margin(4f)
        }.growX()
        info.row()
        info.table(Tex.whiteui){ ent ->
          ent.labelWrap(entry.fi.path()).color(Color.lightGray).growX()
          ent.row()
          ent.table{ t ->
            t.image(Icon.rightSmall).size(24f).scaling(Scaling.fit)
            t.add("modpack/")
            t.field(entry.to?:"") {
              entry.to = it
            }.growX().update { it.setColor(if (entry.to == null) Color.red else Color.white) }
          }.growX()
        }.margin(6f).color(Color.black).growX().fillY()
      }.growX().fillY()
    }.margin(6f)
  }

  private fun selectMap() {
    object: LoadDialog(Core.bundle["dialog.modPacker.map"]) {
      override fun modifyButton(button: TextButton, slot: Saves.SaveSlot) {
        button.clicked {
          if (!button.childrenPressed()) {
            if (!model.fileEntries.contains { it.fi == slot.file }) {
              model.fileEntries.add(
                PackModel.FileEntry(
                  slot.file,
                  "saves"
                )
              )
              rebuildFiles()
            }
            hide()
          }
        }
      }
    }.show()
  }

  private fun selectCampaign() {
    selectCampaignDialog.showSelect(Planets.serpulo.sectors.first()) { s ->
      if (!model.fileEntries.contains { it.fi == s.save.file }) {
        model.fileEntries.add(
          PackModel.FileEntry(
            s.save.file,
            "saves"
          )
        )
        rebuildFiles()
      }
    }
  }

  private fun selectSchematic() {
    selectSchematicDialog.show { s ->
      val f = s.file
      if (!model.fileEntries.contains { it.fi == f }) {
        model.fileEntries.add(
          PackModel.FileEntry(
            f,
            "schematics"
          )
        )
        rebuildFiles()
      }
    }
  }

  private fun selectSettings() {
    val f = Core.settings.settingsFile
    if (!model.fileEntries.contains { it.fi == f }) {
      model.fileEntries.add(
        PackModel.FileEntry(
          f,
          ""
        )
      )
      rebuildFiles()
    }
  }

  private fun selectCustomFile() {
    Vars.platform.showFileChooser(true, "*") { f ->
      if (!model.fileEntries.contains { it.fi == f }) {
        model.fileEntries.add(
          PackModel.FileEntry(f)
        )
        rebuildFiles()
      }
    }
  }

  private fun rebuildList() {
    selectedTab.clearChildren()
    unselectedTab.clearChildren()

    model.mods.forEach { modEnt ->
      val tab = buildModTab(modEnt)

      if (modEnt.enabled) {
        selectedTab.add(tab).fillY().growX().pad(4f)
        selectedTab.row()
      }
      else {
        unselectedTab.add(tab).fillY().growX().pad(4f)
        unselectedTab.row()
      }
    }
  }

  private fun buildModTab(mod: PackModel.ModEntry): Table {
    val res = Table()
    val stat = mod.stat
    var coll: HeCollapser? = null
    var setupContent = { i: Int -> }

    res.button({ top ->
      top.table(Tex.buttonSelect) { icon ->
        icon.image(mod.iconTexture?.let { TextureRegionDrawable(TextureRegion(it)) } ?: Tex.nomap)
          .scaling(Scaling.fit).size(80f)
      }.pad(10f).margin(4f).size(88f)
      top.stack(
        Table{ info ->
          info.left().top().margin(12f).marginLeft(6f).defaults().left()
          info.add(mod.displayName).color(Pal.accent).grow().padRight(160f).wrap()
          info.row()
          info.add(mod.version, 0.8f).color(Color.lightGray).grow().padRight(50f).wrap()
          info.row()
          info.add(mod.shortDesc).grow().padRight(50f).wrap()
        },
        Table{ over ->
          over.right()

          over.table { status ->
            status.top().defaults().size(26f).pad(4f)

            buildModAttrIcons(status, stat)

            buildErrorIcons(status, stat)
          }.fill().pad(4f)

          over.table { side ->
            side.line(Color.darkGray, false, 3f)
            side.table { buttons ->
              buttons.defaults().width(45f).growY()
              buttons.button(Icon.rightOpenSmall, Styles.clearNonei, 48f) {
                mod.enabled = !mod.enabled
                rebuildList()
              }.fillX().growY()
                .update { it.style.imageUp = if (mod.enabled) Icon.leftOpen else Icon.rightOpen }

              buttons.addEventBlocker()
            }.fillX().growY()
          }.fillX().growY().marginTop(6f).marginBottom(6f)
        }
      ).grow()
    }, Styles.grayt) {
      coll!!.toggle()
      if (!coll!!.collapse) {
        setupContent(0)
      }
    }.growX().fillY()

    res.row()
    coll = res.add(HeCollapser(collX = false, collY = true, collapsed = true, Styles.grayPanel) { col ->
      col.table { details ->
        details.left().defaults().growX().pad(4f).padLeft(12f).padRight(12f)

        details.add(Core.bundle.format("dialog.mods.author", mod.author))
          .growX().padRight(50f).wrap().color(Pal.accent).labelAlign(Align.left)
        details.row()
        details.table { link ->
          buildLinkButton(link, Name(mod.author, mod.name))
        }
        details.row()
        details.table{ status ->
          status.left().defaults().left()

          if (stat.isValid()) {
            buildStatus(status, Icon.okSmall, Core.bundle["dialog.mods.modStatCorrect"], Pal.heal)
          }
          else {
            buildStatus(
              status,
              Icon.cancelSmall,
              Core.bundle["dialog.mods.modStatError"],
              Color.crimson
            )
          }

          buildModAttrList(status, stat)

          ModStat.apply {
            if (stat.isLibMissing()) {
              buildStatus(
                status,
                Icon.layersSmall,
                Core.bundle["dialog.mods.libMissing"],
                Color.crimson
              )
            }
            else if (stat.isLibIncomplete()) {
              buildStatus(
                status,
                Icon.warningSmall,
                Core.bundle["dialog.mods.libIncomplete"],
                Color.crimson
              )
            }
            else if (stat.isLibCircleDepending()) {
              buildStatus(
                status,
                Icon.rotateSmall,
                Core.bundle["dialog.mods.libCircleDepending"],
                Color.crimson
              )
            }

            if (stat.isError()) {
              buildStatus(status, Icon.cancelSmall, Core.bundle["dialog.mods.error"], Color.crimson)
            }
            if (stat.isBlackListed()) {
              buildStatus(
                status,
                Icon.infoCircleSmall,
                Core.bundle["dialog.mods.blackListed"],
                Color.crimson
              )
            }
          }
        }
        details.row()
        details.line(Color.gray, true, 4f).pad(6f).padLeft(-6f).padRight(-6f)
        details.row()

        val contents = Vars.content.contentMap.map { it.toList() }
          .flatten()
          .filterIsInstance<UnlockableContent>()
          .filter { c -> mod.name == c.minfo.mod?.name && !c.isHidden }

        var current = -1
        buildDescSelector(details, { current }, setupContent, contents)
        details.row()
        details.table(HeAssets.grayUI) { desc ->
          desc.defaults().grow()
          setupContent = a@{ i ->
            if (i == current) return@a

            desc.clearChildren()
            current = i

            when (i) {
              0 -> desc.add(Markdown(mod.description ?: "", MarkdownStyles.defaultMD))
              1 -> desc.add(mod.description ?: "").wrap()
              2 -> setupContentsList(desc, contents)
            }
          }
        }.grow().margin(12f).padTop(0f)
      }.grow()
    }.also { it.setDuration(0.3f, Interp.pow3Out) }).growX().fillY().colspan(2).get()

    return res
  }

  fun buildStatus(status: Table, icon: Drawable, information: String, color: Color) {
    status.image(icon).scaling(Scaling.fit).color(color).size(26f).pad(4f)
    status.add(information, 0.85f).color(color)
    status.row()
  }
}