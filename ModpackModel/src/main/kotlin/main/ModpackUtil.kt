package main

import arc.Core
import arc.files.Fi
import arc.files.ZipFi
import arc.func.Cons2
import arc.util.serialization.Jval
import main.PackModel.Type.*
import mindustry.Vars
import mindustry.ui.FileChooser
import java.io.IOException
import java.text.DateFormat
import java.util.*
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


object ModpackUtil {
  private val thisLoadedMod = Vars.mods.getMod(Installer::class.java)

  fun installModpack(model: PackModel) {
    val time = Date()
    val backup = Vars.dataDirectory.child("backup-${
      DateFormat.getDateInstance(DateFormat.DEFAULT).format(time)
    }.zip")

    if (model.type != Distribute){
      makeBackup(model.rawBackup, time, backup)
    }

    when(model.type){
      Distribute -> distribute(model)
      Shadow -> shadow(model)
      Override -> override(model)
    }

    if (model.type != Distribute) {
      if (model.rawBackup) {
        FileChooser.save("zip").submit { f: Fi ->
          backup.moveTo(f)
        }
      }
      else {
        Vars.mods.setEnabled(Vars.mods.importMod(backup), false)
        backup.delete()
      }
    }

    Vars.mods.setEnabled(thisLoadedMod, false)
  }

  private fun override(model: PackModel) {
    Vars.dataDirectory.list().forEach { f ->
      if (f.name() == "mods"){
        val modFile = thisLoadedMod.file
        f.list().forEach { c ->
          if (c == modFile) return@forEach
          if (c.isDirectory) c.deleteDirectory()
          else c.delete()
        }
      }
      else if (!f.name().contains("backup") && f.name() != "cache"){
        if (f.isDirectory) f.deleteDirectory()
        else f.delete()
      }
    }

    model.mods.forEach{ mod ->
      mod.fi.copyTo(Vars.modDirectory)
    }

    model.fileEntries.forEach { ent ->
      ent.fi.copyTo(Vars.dataDirectory.child(ent.to).child(ent.fi.name()))
    }

    Core.settings.clear()
    Core.settings.load()
  }

  private fun shadow(model: PackModel) {
    Vars.mods.list().forEach { mod ->
      Vars.mods.setEnabled(mod, false)
    }

    model.mods.forEach{ mod ->
      val loaded = Vars.mods.getMod(mod.name)
      if (loaded == null || model.force || mod.version != loaded.meta.version){
        loaded?.let { Vars.mods.removeMod(it) }
        Vars.mods.importMod(mod.fi)
      }

      loaded?.let { Vars.mods.setEnabled(it, true) }
    }

    model.fileEntries.forEach { f ->
      val to = Vars.dataDirectory.child(f.to).child(f.fi.name())
      if (!to.exists() || !model.skipRepeat){
        f.fi.copyTo(to)
      }
    }
  }

  private fun distribute(model: PackModel) {
    model.mods.forEach{ mod ->
      val loaded = Vars.mods.getMod(mod.name)
      if (loaded == null || model.force || mod.version != loaded.meta.version){
        loaded?.let { Vars.mods.removeMod(it) }
        Vars.mods.importMod(mod.fi)
      }
    }

    model.fileEntries.forEach { f ->
      val to = Vars.dataDirectory.child(f.to).child(f.fi.name())
      if (!to.exists() || !model.skipRepeat){
        f.fi.copyTo(to)
      }
    }
  }

  private fun each(path: String, fi: Fi, cons: Cons2<String, Fi>){
    fi.list()?.forEach {
      if (it.isDirectory) each("$path/${it.name()}", it, cons)
      else cons.get(path, it)
    }
  }

  private fun genBackupMeta(time: Date): String{
    val res: Jval = Jval.newObject()
    res.add("isBackup", Jval.valueOf(true))

    res.add("name", "backup-${time.time}")
    res.add("displayName", "${Core.bundle["misc.backup"]}-${
      DateFormat.getDateInstance(DateFormat.DEFAULT).format(time)
    }")
    res.add("description", Core.bundle["infos.backup.description"])
    res.add("subtitle", Core.bundle["infos.backup.subTitle"])

    res.add("version", "${time.time}")
    res.add("author", "backup")

    res.add("hidden", Jval.valueOf(true))
    res.add("minGameVersion", thisLoadedMod.meta.minGameVersion)

    res.add("main", "main.Recover")

    return res.toString(Jval.Jformat.formatted)
  }

  fun makeBackup(raw: Boolean, time: Date, file: Fi){
    if (raw){
      val output = ZipOutputStream(file.write())

      output.use { write ->
        each("", Vars.dataDirectory){ p, fi ->
          val pa = if (p.startsWith("/")) p.substring(1) else p
          if (fi == file) return@each

          write.putNextEntry(ZipEntry(pa))
          write.write(fi.readBytes())
          write.closeEntry()
        }
      }
    }
    else {
      val modFile = thisLoadedMod.file
      val output = JarOutputStream(file.write())
      val input = JarInputStream(modFile.read())

      output.use { write ->
        input.use { read ->
          while (true) {
            val ent = read.nextJarEntry?:break
            if (
              ent.name == "mod.json"
              || ent.name == "icon.png"
              || ent.name.startsWith("assets/")
              || ent.name.startsWith("mods/")
            ) continue
            write.putNextEntry(JarEntry(ent))
            write.write(read.readBytes())
            write.closeEntry()
            read.closeEntry()
          }
        }

        write.putNextEntry(JarEntry("mod.json"))
        write.write(genBackupMeta(time).toByteArray())
        write.closeEntry()

        each("", Vars.dataDirectory){ p, fi ->
          val pa = if (p.startsWith("/")) p.substring(1) else p
          if (fi == file || pa.startsWith("cache")) return@each

          write.putNextEntry(JarEntry("backfiles/${if (pa.isBlank()) "" else "$pa/"}${fi.name()}"))
          write.write(fi.readBytes())
          write.closeEntry()
        }
      }
    }
  }

  @Throws(IOException::class)
  fun readModpackFile(model: PackModel, fi: Fi){
    val metaFile = fi.child("mod.json")
    if (!metaFile.exists()) throw IOException("Not a mod file")

    val meta = Jval.read(metaFile.reader())
    model.name = meta.getString("name")
    model.displayName = meta.getString("displayName")
    model.description = meta.getString("description")
    model.version = meta.getString("version")
    model.author = meta.getString("author")

    model.icon = fi.child("icon.png").let { if (it.exists()) it else null }
    model.type = PackModel.Type.valueOf(meta.getString("packType"))

    model.skipRepeat = meta.getBool("skipRepeat", false)
    model.rawBackup = meta.getBool("rawBackup", false)
    model.force = meta.getBool("force", false)
    model.uncheck = meta.getBool("uncheck", false)

    val mods = fi.child("mods")
    val tmpMod = Vars.tmpDirectory.child("tmpMod.jar")
    try {
      meta.get("mods").asArray().forEach { m ->
        val fiName = m.getString("file")
        val f = mods.child(fiName)
        f.copyTo(tmpMod)
        val open = ZipFi(tmpMod)
        if (!f.exists()) throw IOException("Mod pack error! Missing listed mod.")

        val metaFi = open.child("mod.json").let { if (it.exists()) it else open.child("mod.hjson") }
        if (!metaFi.exists()) throw IOException("Mod pack error! Listing illegal file.")

        val modEnt = PackModel.ModEntry(
          fi = f,
          name = m.getString("name"),
          version = m.getString("version"),
          author = m.getString("author")
        )
        model.mods.add(modEnt)

        tmpMod.delete()
      }
    } finally {
      tmpMod.delete()
    }

    each("", fi.child("assets")){ p, f ->
      model.fileEntries.add(PackModel.FileEntry(
        f,
        p.replaceFirst("assets", "modpack").substring(1))
      )
    }
  }
}
