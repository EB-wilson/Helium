package helium

import arc.Core
import arc.Events
import arc.Settings
import arc.files.Fi
import arc.func.*
import arc.scene.Element
import arc.scene.event.SceneEvent
import arc.scene.ui.Dialog
import arc.scene.ui.layout.Table
import arc.struct.IntMap
import arc.struct.ObjectFloatMap
import arc.struct.ObjectIntMap
import arc.struct.ObjectMap
import arc.util.Log
import arc.util.Strings
import arc.util.Time
import helium.graphics.HeShaders
import helium.graphics.ShieldRenderer
import helium.ui.HeAssets
import helium.ui.HeStyles
import helium.ui.dialogs.*
import helium.ui.dialogs.modpacker.ModPackerDialog
import helium.ui.fragments.entityinfo.EntityInfoFrag
import helium.ui.fragments.entityinfo.displays.*
import helium.ui.fragments.placement.HePlacementFrag
import helium.ui.fragments.placement.HoveringInfoFrag
import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Icon
import mindustry.graphics.Pal
import mindustry.ui.Fonts
import mindustry.ui.Styles
import mindustry.ui.dialogs.SettingsMenuDialog

object He {
  private val settingsMenu = SettingsMenuDialog::class.java.getDeclaredField("menu")
    .also { it.isAccessible = true }

  /**本模组的文件位置 */
  private val mod = Vars.mods.getMod(Helium::class.java)

  /**此模组的压缩包对象 */
  val modFile: Fi = mod.root

  /**此mod内部名称 */
  const val INTERNAL_NAME: String = "he"
  const val MOD_NAME: String = "Helium"

  val modJsonURLs = arrayOf(
    "https://raw.githubusercontent.com/EB-wilson/HeMindustryMods/master/mods.json"
  )

  val defaultMirrors = arrayOf(
    ""
  )

  /**模组内配置文件存放位置 */
  val internalConfigDir: Fi = modFile.child("config")
  /**模组文件夹位置 */
  val modDirectory: Fi = Core.settings.dataDirectory.child("mods")
  /**模组配置文件夹 */
  val configDirectory: Fi = modDirectory.child("config").child(INTERNAL_NAME)
  /**模组数据文件目录*/
  val dataDirectory: Fi = modDirectory.child("data").child(INTERNAL_NAME)
  /**模组持久全局变量存储文件 */
  val globalVars: Fi = dataDirectory.child("global_vars.bin")
  /**模组持久全局变量备份文件 */
  val globalVarsBackup: Fi = dataDirectory.child("global_vars.bin.bak")

  /**整合包模板文件*/
  val modpackModel = Helium.getInternalFile("model/ModpackModel.jar")

  lateinit var config: HeConfig
  lateinit var global: Settings

  lateinit var heShield: ShieldRenderer

  lateinit var placement: HePlacementFrag
  lateinit var hoveringInfo: HoveringInfoFrag
  lateinit var entityInfo: EntityInfoFrag
  lateinit var unitHealthBarDisplay: UnitHealthDisplayProv
  lateinit var buildHealthBarDisplay: BuildHealthDisplayProv
  lateinit var statusDisplay: StatusDisplayProvider
  lateinit var detailsDisplay: DetailsDisplayProvider
  lateinit var entityRangeDisplay: EntityRangeDisplayProvider

  lateinit var configDialog: ModConfigDialog
  lateinit var heModsDialog: HeModsDialog
  lateinit var modPackerDialog: ModPackerDialog

  private fun update() {
    global.autosave()

    HeStyles.uiBlur.blurScl = config.blurScl
    HeStyles.uiBlur.blurSpace = config.blurSpace
    Styles.defaultDialog.stageBackground = if (config.enableBlur) HeStyles.BLUR_BACK else Styles.black9
  }

  private fun drawWorld() {
    EntityRangeDisplay.resetMark()
    entityInfo.drawWorld()
  }

  fun init() {
    config = HeConfig(
      configDirectory,
      internalConfigDir.child("mod_config.hjson")
    )
    config.load()

    global = genGlobal()
    global.load()

    heShield = ShieldRenderer()
    //heShield.setup()

    //GithubAPI.init()

    HeAssets.load()
    HeShaders.load()
    HeStyles.load()

    placement = HePlacementFrag()
    setupTools(placement)
    placement.cleanGlobal()

    hoveringInfo = HoveringInfoFrag()

    entityInfo = EntityInfoFrag()
    setupDisplays(entityInfo)

    configDialog = ModConfigDialog()
    setupSettings(configDialog)

    heModsDialog = HeModsDialog()
    setupHeModsDialog(heModsDialog)

    modPackerDialog = ModPackerDialog()

    setupGlobalListeners()

    //add config entry
    Vars.ui.settings.shown {
      val table = settingsMenu[Vars.ui.settings] as Table
      table.button(
        Core.bundle["settings.helium"],
        HeAssets.heIcon,
        Styles.flatt,
        32f
      ) { configDialog.show() }.marginLeft(8f).row();
    }

    Events.on(EventType.ClientLoadEvent::class.java) {
      Time.run(1f) { postHandle() }
    }
  }

  private fun postHandle() {
    placement.build(Vars.ui.hudGroup)
    hoveringInfo.build(Vars.ui.hudGroup)
    entityInfo.build(Vars.ui.hudGroup)
  }

  private fun setupHeModsDialog(heModsDialog: HeModsDialog) {
    if (config.enableBetterModsDialog) {
      Vars.ui.mods.also {
        it.style = Dialog.DialogStyle().also { s ->
          s.background = Styles.none
          s.titleFont = Fonts.def
        }
        it.clear()
        it.shown {
          Core.app.post {
            it.hide(null)
            heModsDialog.show()
          }
        }
      }
    }
  }

  private fun genGlobal() = object : Settings() {
    override fun getSettingsFile(): Fi {
      return globalVars
    }

    override fun getBackupFolder(): Fi {
      return He.dataDirectory.child("global_backups")
    }

    override fun getBackupSettingsFile(): Fi {
      return globalVarsBackup
    }

    @Synchronized
    override fun load() {
      try {
        loadValues()
      } catch (error: Throwable) {
        Log.err("Error in load: " + Strings.getStackTrace(error))
        if (errorHandler != null) {
          if (!hasErrored) errorHandler.get(error)
        }
        else {
          throw error
        }
        hasErrored = true
      }
      loaded = true
    }

    @Synchronized
    override fun forceSave() {
      if (!loaded) return
      try {
        saveValues()
      } catch (error: Throwable) {
        Log.err("Error in forceSave to " + settingsFile + ":\n" + Strings.getStackTrace(error))
        if (errorHandler != null) {
          if (!hasErrored) errorHandler.get(error)
        }
        else {
          throw error
        }
        hasErrored = true
      }
      modified = false
    }
  }.also {
    it.setAutosave(true)
    it.setDataDirectory(dataDirectory)
  }

  private fun setupTools(frag: HePlacementFrag) {
    frag.addTool(
      "controlEntityInfoShow",
      Icon.effect,
      { entityInfo.controlling || Core.input.keyDown(config.entityInfoHotKey) },
      { Core.bundle["tools.tip.ctrlEntityInfo"] }
    ){ entityInfo.controlling = !entityInfo.controlling }
    frag.addTool(
      "entityInfoSwitches",
      Icon.settings,
      tip = { Core.bundle["tools.tip.configEntityInfo"] },
    ){ entityInfo.toggleSwitchConfig() }
  }

  private fun setupGlobalListeners() {
    Events.run(EventType.Trigger.update) { update() }
    Events.run(EventType.Trigger.draw) { drawWorld() }

    //Events.on(EventType.ResetEvent::class.java) { entityInfo.reset() }
  }

  private fun setupDisplays(infos: EntityInfoFrag) {
    infos.addDisplay(UnitHealthDisplayProv().also { unitHealthBarDisplay = it })
    infos.addDisplay(BuildHealthDisplayProv().also { buildHealthBarDisplay = it })
    infos.addDisplay(StatusDisplayProvider().also { statusDisplay = it })

    infos.addDisplay(DetailsDisplayProvider().also { detailsDisplay = it })
    infos.addDisplay(EntityRangeDisplayProvider().also { entityRangeDisplay = it })

    unitHealthBarDisplay.style = HeStyles.test
    buildHealthBarDisplay.style = HeStyles.test
  }

  private fun setupSettings(conf: ModConfigDialog) {
    conf.addConfig(
      "basic", Icon.settings,
      ConfigSepLine(
        "backBlur",
        Core.bundle["settings.basic.backBlur"],
        Pal.accent,
        Pal.accentBack
      ),
      ConfigCheck(
        "enableBlur",
        config::enableBlur
      ),
      ConfigSlider(
        "blurScl",
        config::blurScl,
        1, 8, 1
      ),
      ConfigSlider(
        "blurSpace",
        config::blurSpace,
        0.5f, 8f, 0.25f
      ),

      ConfigSepLine(
        "entityInfo",
        Core.bundle["settings.basic.entityInfo"],
        Pal.accent,
        Pal.accentBack
      ),
      ConfigCheck(
        "useFixedHoveringInfoPane",
        config::useFixedHoveringInfoPane
      ),
      ConfigSlider(
        "entityInfoScale",
        config::entityInfoScale,
        0.5f, 2f, 0.1f
      ),
      ConfigSlider(
        "entityInfoAlpha",
        config::entityInfoAlpha,
        0.4f, 1f, 0.05f
      ),
      ConfigCheck(
        "enableEntityInfoDisplay",
        config::enableEntityInfoDisplay
      ),
      ConfigCheck(
        "enableHealthBarDisplay",
        config::enableHealthBarDisplay
      ),
      ConfigCheck(
        "enableUnitStatusDisplay",
        config::enableUnitStatusDisplay
      ),
      ConfigCheck(
        "enableRangeDisplay",
        config::enableRangeDisplay
      ),
      ConfigSlider(
        "rangeRenderLevel",
        config::rangeRenderLevel,
        0, 2, 1
      ){ return@ConfigSlider when(it){
        0 -> Core.bundle["range.animate.full"]
        1 -> Core.bundle["range.animate.simplified"]
        else -> Core.bundle["range.animate.prof"]
      } },
      ConfigCheck(
        "showAttackAngle",
        config::showAttackAngle
      ),
      ConfigCheck(
        "showAttackRange",
        config::showAttackRange
      ),
      ConfigCheck(
        "showHealRange",
        config::showHealRange
      ),
      ConfigCheck(
        "showOverdriveRange",
        config::showOverdriveRange
      ),

      ConfigSepLine(
        "placement",
        Core.bundle["settings.basic.placement"],
        Pal.accent,
        Pal.accentBack
      ),
      ConfigCheck(
        "enableBetterPlacement",
        config::enableBetterPlacement
      ),
      ConfigSlider(
        "blockColumns",
        config::blockColumns,
        4, 8, 1
      ),

      ConfigSepLine(
        "modsDialog",
        Core.bundle["settings.basic.modsDialog"],
        Pal.accent,
        Pal.accentBack
      ),
      ConfigCheck(
        "enableBetterModsDialog",
        config::enableBetterModsDialog
      ),
    )
    conf.addConfig(
      "debug", HeAssets.program,
      ConfigSepLine(
        "logging",
        Core.bundle["settings.debug.logging"],
      ),
      ConfigCheck(
        "loadingInfo",
        config::loadInfo
      )
    )
  }
}

fun Element.addEventBlocker(
  capture: Boolean = false,
  isCancel: Boolean = false,
  filter: Boolf<SceneEvent> = Boolf{ true }
){
  (this::addCaptureListener.takeIf{ capture }?: this::addListener){ event ->
    if (event != null && filter.get(event)) {
      if (isCancel) event.cancel()
      else event.stop()
    }
    false
  }
}

operator fun <K> ObjectIntMap<K>.set(key: K, value: Int) = put(key, value)
operator fun <K> ObjectFloatMap<K>.set(key: K, value: Float) = put(key, value)

operator fun <V> IntMap<V>.set(key: Int, value: V): V = put(key, value)

operator fun <K, V> ObjectMap<K, V>.set(key: K, value: V): V = put(key, value)

operator fun <P> Cons<P>.invoke(p: P) = get(p)
operator fun <P1, P2> Cons2<P1, P2>.invoke(p1: P1, p2: P2) = get(p1, p2)
operator fun <P1, P2, P3> Cons3<P1, P2, P3>.invoke(p1: P1, p2: P2, p3: P3) = get(p1, p2, p3)
operator fun <P1, P2, P3, P4> Cons4<P1, P2, P3, P4>.invoke(p1: P1, p2: P2, p3: P3, p4: P4) = get(p1, p2, p3, p4)
operator fun <P, R> Func<P, R>.invoke(p: P): R = get(p)
operator fun <P1, P2, R> Func2<P1, P2, R>.invoke(p1: P1, p2: P2): R = get(p1, p2)
operator fun <P1, P2, P3, R> Func3<P1, P2, P3, R>.invoke(p1: P1, p2: P2, p3: P3): R = get(p1, p2, p3)
operator fun <R> Prov<R>.invoke(): R = get()
operator fun <P, T: Throwable> ConsT<P, T>.invoke(p: P) = get(p)
