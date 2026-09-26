package helium.ui.dialogs.mods

import arc.Core
import arc.files.Fi
import arc.func.Cons
import arc.func.Func
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.math.Mathf
import arc.scene.Element
import arc.scene.style.Drawable
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.Button
import arc.scene.ui.layout.Cell
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.struct.OrderedMap
import arc.struct.Seq
import arc.util.Align
import arc.util.Http
import arc.util.Log
import arc.util.Scaling
import arc.util.Strings
import arc.util.Threads
import arc.util.Time
import arc.util.serialization.Jval
import helium.He
import helium.set
import helium.ui.ButtonEntry
import helium.ui.HeAssets
import helium.ui.UIUtils
import helium.util.CLIENT_ONLY
import helium.util.DEPRECATED
import helium.util.Downloader
import helium.util.JAR_MOD
import helium.util.JS_MOD
import helium.util.ModStat
import helium.util.UNSUPPORTED
import helium.util.VersionCompareHelper.tryCompareVersion
import helium.util.toStoreSize
import mindustry.Vars
import mindustry.core.Version
import mindustry.ctype.UnlockableContent
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.graphics.Pal
import mindustry.io.JsonIO
import mindustry.mod.Mods
import mindustry.ui.Bar
import mindustry.ui.Styles
import java.lang.NumberFormatException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.jvm.Throws
import kotlinx.coroutines.Job

object ModsDialogHelper {
  private val exec: ExecutorService = Threads.unboundedExecutor("HTTP", 1)

  /** 模组索引缓存：跨线程读写，必须 volatile（主线程读、HTTP 线程写） */
  @Volatile
  var modList: OrderedMap<Name, ModListing>? = null
    private set

  val switchBut: Button.ButtonStyle = Button.ButtonStyle().also {
    it.up = Styles.none
    it.over = HeAssets.grayUIAlpha
    it.down = HeAssets.grayUI
    it.checked = HeAssets.grayUI
  }

  fun buildDescSelector(
    details: Table,
    get: () -> Int,
    set: (Int) -> Unit,
    contents: List<UnlockableContent>,
  ) {
    details.table { switch ->
      switch.left().defaults().center()
      switch.button({ it.add(Core.bundle["dialog.mods.description"], 0.85f) }, switchBut) { set(0) }
        .margin(12f).checked { get() == 0 }.disabled { t -> t.isChecked }
      switch.button({ it.add(Core.bundle["dialog.mods.rawText"], 0.85f) }, switchBut) { set(1) }
        .margin(12f).checked { get() == 1 }.disabled { t -> t.isChecked }
      if (contents.any()) {
        switch.button({ it.add(Core.bundle["dialog.mods.contents"], 0.85f) }, switchBut) { set(2) }
          .margin(12f).checked { get() == 2 }.disabled { t -> t.isChecked }
      }
    }.grow().padBottom(0f)
  }

  fun buildErrorIcons(status: Table, stat: Int) {
    ModStat.apply {
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

  fun buildLinkButton(link: Table, modName: Name) {
    link.left().image(Icon.githubSmall).scaling(Scaling.fit).size(24f).color(Color.lightGray)
    val linkButton = link.button("...", Styles.nonet) {}
      .padLeft(4f).padRight(50f).wrap(true)
      .growX().left().align(Align.left).height(30f).disabled(true).get()

    linkButton.label.setAlignment(Align.left)
    linkButton.label.setFontScale(0.9f)

    getModList(
      errHandler = {
        linkButton.isDisabled = true
        linkButton.setText(Core.bundle["dialog.mods.checkFailed"])
      }
    ) { modList ->

      val modInfo = modList[modName]

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

  fun buildModAttrIcons(status: Table, stat: Int) {
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

  fun buildModBasicStatus(status: Table, stat: Int) {
    ModStat.apply {
      if (stat.isValid()) {
        buildStatus(status, Icon.okSmall, Core.bundle["dialog.mods.modStatCorrect"], Pal.heal)
      }
      else {
        buildStatus(status, Icon.cancelSmall, Core.bundle["dialog.mods.modStatError"], Color.crimson)
      }
    }
  }

  fun buildModAttrList(status: Table, stat: Int) {
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
    }
  }

  fun buildModErrList(status: Table, stat: Int) {
    ModStat.apply {
      if (stat.isDeprecated()) {
        buildStatus(
          status,
          Icon.warningSmall,
          Core.bundle.format(
            "dialog.mods.deprecated",
            if (stat.isJAR()) Vars.minJavaModGameVersion else Vars.minModGameVersion
          ),
          Color.crimson
        )
      }
      else if (stat.isUnsupported()) {
        buildStatus(status, Icon.warningSmall, Core.bundle["dialog.mods.unsupported"], Color.crimson)
      }

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
  }

  fun buildStars(stars: Table, modInfo: ModListing) {
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

  fun buildStatus(status: Table, icon: Drawable, information: String, color: Color) {
    status.image(icon).scaling(Scaling.fit).color(color).size(26f).pad(4f)
    status.add(information, 0.85f).color(color)
    status.row()
  }

  fun <T : Element> Cell<T>.addTip(tipText: String): Cell<T> {
    tooltip { t ->
      t.table(HeAssets.padGrayUIAlpha) { tip ->
        tip.add(tipText, Styles.outlineLabel)
      }
    }

    return this
  }

  fun resetModListCache(){
    modList = null
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

            val parsed = OrderedMap<Name, ModListing>()
            list.forEach { parsed[Name(it)] = it }

            modList = parsed

            Core.app.post {
              listener.get(parsed)
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

  fun setupContentsList(
    desc: Table,
    contents: List<UnlockableContent>,
  ) {
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

  /**
   * 单个 mod 的下载弹窗：内容就是一条 [ModDownloadBar]，由弹窗底部的"下载"按钮触发。
   */
  fun showDownloadModDialog(modInfo: ModListing, callback: Runnable) {
    val bar = ModDownloadBar(modInfo, exec)
    val title = Core.bundle[if (bar.isUpdate) "dialog.mods.updateMod" else "dialog.mods.downloadMod"]

    val dialog = UIUtils.showPane(
      title,
      ButtonEntry(
        Core.bundle["cancel"],
        Icon.cancel
      ) {
        bar.cancel()
        it.hide()
      },
      ButtonEntry(
        Core.bundle["misc.download"],
        Icon.download,
        disabled = { bar.downloading || bar.complete }
      ) {
        bar.start()
      }
    ) { t -> bar.buildContent(t, withButton = false) }

    bar.onInstalled = {
      // 已经回到主线程：关掉下载弹窗，换成完成提示
      dialog.hide()
      UIUtils.showPane(
        title,
        ButtonEntry(Core.bundle["confirm"], Icon.ok) { d ->
          callback.run()
          d.hide()
        }
      ) { t -> bar.buildContent(t, withButton = false) }
    }
  }

  /**
   * 批量下载弹窗：整个收藏夹的 mod 以可滚动列表呈现，每行一条 [ModDownloadBar]（自带下载按钮）。
   *
   * 不可用的 mod（[ModStat.isValid] 为假）直接过滤掉，不进入列表 —— 这类 mod 只能回到主布局里
   * 勾选"显示不可用 mod"后单独强制安装。
   *
   * 「全部下载」会并发启动所有缺失 / 有更新的条目；已安装且版本一致的会被跳过（仍显示在列表里）。
   */
  fun showDownloadAllDialog(mods: List<ModListing>, callback: Runnable) {
    val installable = mods.filter { ModStat.run { it.checkStatus().isValid() } }

    if (installable.isEmpty()) {
      UIUtils.showTip(null, Core.bundle["dialog.mods.installAllEmpty"])
      return
    }

    val bars = installable.map { mod -> ModDownloadBar(mod, exec).also { it.onInstalled = { callback.run() } } }

    UIUtils.showPane(
      Core.bundle["dialog.mods.installAll"],
      ButtonEntry(
        Core.bundle["cancel"],
        Icon.cancel
      ) {
        bars.forEach { it.cancel() }
        it.hide()
      },
      ButtonEntry(
        Core.bundle["dialog.mods.downloadAll"],
        Icon.download
      ) {
        // 并发下载：跳过已安装且版本一致的、正在下载的和已经完成的
        bars.forEach { bar ->
          if (!bar.isCurrent && !bar.downloading && !bar.complete) bar.start()
        }
      }
    ) { t ->
      bars.forEach { bar ->
        bar.buildContent(t)
        t.row()
      }
    }
  }
}

class ModListing {
  var repo: String = "???"
  var name: String = "???"
  var internalName: String = "???"
  var subtitle: String? = null
  var author: String? = null
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
    return if (dot != -1) Strings.parseInt(ver.take(dot), 0)
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

class Name(
  author: String,
  name: String,
){
  val author = author.lowercase()
  val name = name.lowercase()

  private val hash = author.hashCode()*31 xor 31 + name.hashCode() xor 31

  constructor(loaded: Mods.LoadedMod): this(loaded.meta.author?:"*", loaded.name)
  constructor(loaded: ModListing): this(loaded.author?:"*", loaded.internalName)

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

/**
 * 单个 mod 的下载栏。
 *
 * 单个下载弹窗把它作为唯一内容；批量下载弹窗把它作为可滚动列表里的一行（每行自带下载按钮）。
 *
 * 网络请求跑在线程池 / 下载协程里，进度由 IO 线程回写（状态字段都是 volatile）；
 * 而 importMod、removeMod、弹窗等涉及游戏状态与场景图的操作一律 post 回主线程。
 */
class ModDownloadBar(
  val modInfo: ModListing,
  private val exec: ExecutorService,
) {
  /** 安装完成后的回调，主线程执行 */
  var onInstalled: () -> Unit = {}

  @Volatile
  var progress = 0f
    private set

  @Volatile
  var complete = false
    private set

  @Volatile
  var failed = false
    private set

  @Volatile
  var downloading = false
    private set

  private var task: Future<*>? = null
  private var download: Job? = null

  private val loaded: Mods.LoadedMod? get() = Vars.mods.getMod(modInfo.internalName)

  /** @return 本地已安装且版本与索引一致；批量下载时跳过 */
  val isCurrent: Boolean get() = loaded?.meta?.version == modInfo.version

  /** @return 本地已安装的是旧版本，本次下载是更新而不是重装 */
  val isUpdate: Boolean get() = loaded?.let {
    it.meta.version != modInfo.version && tryCompareVersion(it.meta.version, modInfo.version) < 0
  } == true

  /** 开始下载；正在下载或已完成时忽略 */
  fun start() {
    if (downloading || complete) return

    downloading = true
    failed = false
    progress = 0f

    task = exec.submit {
      Http.get(Vars.ghApi + "/repos/" + modInfo.repo + "/releases/latest")
        .error { e -> fail(e, "dialog.mods.checkFailed") }
        .block { result ->
          try {
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

            val file = Vars.modDirectory.child("tmp").child(modInfo.internalName + suffix)
            download = Downloader.launchDownloadToFile(
              url, file,
              { p -> progress = p },
              { e -> fail(e, "dialog.mods.downloadFailed") }
            ) {
              // 这里是下载协程（IO 线程）：安装必须回主线程
              Core.app.post { install(file) }
            }
          }
          catch (e: Exception) {
            fail(e, "dialog.mods.checkFailed")
          }
        }
    }
  }

  /** 取消进行中的下载 */
  fun cancel() {
    task?.cancel(true)
    download?.cancel()

    task = null
    download = null
    downloading = false
  }

  /** 构建下载栏：图标、名称与版本、进度条，以及（可选的）本行自己的下载按钮 */
  fun buildContent(content: Table, withButton: Boolean = true) {
    val repoStr = modInfo.repo.replace("/", "_")
    val iconLink = "https://raw.githubusercontent.com/EB-wilson/HeMindustryMods/master/icons/$repoStr"
    val icon = Downloader.downloadLazyDrawable(iconLink, Core.atlas.find("nomap"))

    content.table(HeAssets.darkGrayUIAlpha) { cont ->
      cont.table(Tex.buttonSelect) { t ->
        t.image(icon).scaling(Scaling.fit).size(80f)
      }.pad(10f).margin(4f).size(88f)

      cont.stack(
        Table { info -> buildInfo(info) },
        Table { info -> buildStatus(info) },
      ).pad(12f).padLeft(4f).growX().fillY().minWidth(420f)

      if (withButton) {
        cont.table { buttons ->
          buttons.defaults().size(48f).pad(4f)

          buttons.button(Icon.downloadSmall, Styles.clearNonei, 48f) { start() }
            .disabled { downloading || complete }
            .update { b ->
              b.image.setScale(0.9f)
              b.style.imageUpColor = when {
                complete -> Pal.heal
                failed -> Color.crimson
                else -> Color.white
              }
            }
        }.pad(8f)
      }
    }.margin(6f).growX().fillY()
  }

  private fun buildInfo(info: Table) {
    info.left().top().defaults().left().pad(3f)
    info.add(modInfo.name).color(Pal.accent)
    info.row()

    val installed = loaded
    when {
      installed == null -> info.add(modInfo.version)

      installed.meta.version == modInfo.version ->
        info.add("[lightgray]${installed.meta.version}  " + Core.bundle["dialog.mods.installed"])

      isUpdate ->
        info.add("[lightgray]${installed.meta.version}  >>>  [accent]${modInfo.version}")

      else ->
        info.add("[lightgray]${installed.meta.version}  >>>  ${modInfo.version}" + Core.bundle["dialog.mods.reinstall"])
    }

    info.row()
    info.table { bar ->
      bar.add(
        Bar(
          {
            when {
              failed -> Core.bundle["dialog.mods.downloadFailed"]
              complete -> Core.bundle["dialog.mods.downloadComplete"]
              else -> Core.bundle.format(
                "dialog.mods.downloading",
                if (progress < 0) (-progress).toStoreSize()
                else "${Mathf.round(progress*100)}%"
              )
            }
          },
          { if (failed) Color.crimson else Pal.accent },
          { if (progress < 0) 1f else progress }
        )
      ).growX().pad(6f).height(22f).visible { downloading || complete || failed }
    }.grow()
  }

  private fun buildStatus(info: Table) {
    info.top().right().defaults().right().top()
    info.table { status ->
      status.top().right().defaults().size(26f).pad(4f)
      ModsDialogHelper.buildModAttrIcons(status, modInfo.checkStatus())
    }.fill()
    info.row()
    info.table { stars ->
      stars.bottom().right()
      ModsDialogHelper.buildStars(stars, modInfo)
    }
  }

  private fun fail(error: Throwable, messageKey: String) {
    downloading = false
    failed = true

    if (error is InterruptedException) return

    Log.err(error)
    Core.app.post { UIUtils.showException(error, Core.bundle[messageKey]) }
  }

  /** 安装（主线程执行）：更新时先移除已安装的旧版本 */
  private fun install(file: Fi) {
    try {
      if (isUpdate) loaded?.also { Vars.mods.removeMod(it) }

      Vars.mods.importMod(file)
      file.delete()

      complete = true
      downloading = false
      onInstalled()
    }
    catch (e: Exception) {
      Log.err(e)
      UIUtils.showException(e, Core.bundle["dialog.mods.downloadFailed"])
    }
  }
}