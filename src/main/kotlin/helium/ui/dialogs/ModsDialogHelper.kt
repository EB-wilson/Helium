package helium.ui.dialogs

import arc.Core
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

object ModsDialogHelper {
  private val exec: ExecutorService = Threads.unboundedExecutor("HTTP", 1)
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
            list.forEach { modList!![Name(it)] = it }

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

  fun showDownloadModDialog(modInfo: ModListing, callback: Runnable) {
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
                    callback.run()
                  } catch (e: Exception) {
                    Log.err(e)
                    UIUtils.showException(e, Core.bundle["dialog.mods.downloadFailed"])
                  }
                }
              }
            }
        }
      }
    ) { t ->
      val iconLink =
        "https://raw.githubusercontent.com/EB-wilson/HeMindustryMods/master/icons/" + modInfo.repo.replace("/", "_")
      val image = Downloader.downloadImg(iconLink, Core.atlas.find("nomap"))

      t.table(HeAssets.darkGrayUIAlpha) { cont ->
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
            info.table { b ->
              b.add(
                Bar(
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