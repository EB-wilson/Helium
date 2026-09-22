package helium.util

import arc.files.Fi
import arc.util.Log
import helium.He
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecureStore {
  private const val MAGIC = "HS1"
  private const val IV_LENGTH = 12
  private const val TAG_BITS = 128
  private const val KEY_LENGTH = 32
  private const val KEY_FILE = "local.key"

  private val random = SecureRandom()

  @Volatile
  private var key: SecretKeySpec? = null

  private val fingerprint: String by lazy { computeFingerprint() }

  private val dir: Fi get() = He.dataDirectory.child("secret")

  fun write(id: String, payload: ByteArray) {
    synchronized(this) {
      try {
        dir.mkdirs()
        val file = dir.child(id)
        file.writeBytes(encrypt(payload, contentKey()))
        restrictPermissions(file)
      }
      catch (error: Throwable) {
        Log.err("failed to write local secret '$id'", error)
      }
    }
  }

  fun readBytes(id: String): ByteArray? {
    synchronized(this) {
      val file = dir.child(id)
      if (!file.exists()) return null

      return try {
        decrypt(file.readBytes(), contentKey())
      }
      catch (_: Throwable) {
        Log.warn("local secret '$id' can not be decrypted (different machine key or corrupted); discarding it")
        erase(id)
        null
      }
    }
  }

  fun erase(id: String) {
    synchronized(this) {
      try {
        dir.child(id).delete()
      }
      catch (error: Throwable) {
        Log.err("failed to erase local secret '$id'", error)
      }
    }
  }

  private fun contentKey(): SecretKeySpec {
    key?.also { return it }

    synchronized(this) {
      key?.also { return it }

      return deriveKey(baseSecret(), fingerprint).also { key = it }
    }
  }

  private fun deriveKey(base: ByteArray, fingerprint: String): SecretKeySpec {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(base, "HmacSHA256"))

    return SecretKeySpec(mac.doFinal(fingerprint.toByteArray(Charsets.UTF_8)), "AES")
  }

  private fun baseSecret(): ByteArray {
    val file = dir.child(KEY_FILE)

    try {
      if (file.exists()) {
        val raw = Base64.getDecoder().decode(file.readString().trim())
        if (raw.size == KEY_LENGTH) return raw

        Log.warn("local secret key has an unexpected length, regenerating")
      }
    }
    catch (error: Throwable) {
      Log.err("failed to read local secret key, regenerating", error)
    }

    val raw = ByteArray(KEY_LENGTH).also { random.nextBytes(it) }
    dir.mkdirs()
    file.writeString(Base64.getEncoder().encodeToString(raw))
    restrictPermissions(file)

    return raw
  }

  private fun computeFingerprint(): String {
    val system = try {
      "${System.getProperty("os.name")}/${System.getProperty("os.arch")}"
    }
    catch (_: Throwable) {
      "unknown"
    }

    val host = hostName()
    val volume = volumeSerial()

    val parts = ArrayList<String>(3)
    parts.add("os=$system")
    host?.also { parts.add("host=$it") }
    volume?.also { parts.add("vol=$it") }

    Log.info(
      "local secret store fingerprint: system=@, hostname=@, volume id=@",
      system,
      if (host == null) "<unavailable>" else "ok",
      if (volume == null) "<unavailable>" else "ok"
    )

    return parts.joinToString("|")
  }

  private fun hostName(): String? {
    try {
      System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }?.also { return it }
      System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }?.also { return it }
    }
    catch (_: Throwable) {

    }

    return try {
      val runtimeName = java.lang.management.ManagementFactory.getRuntimeMXBean().name
      val at = runtimeName.indexOf('@')
      if (at >= 0 && at < runtimeName.length - 1) runtimeName.substring(at + 1) else null
    }
    catch (_: Throwable) {
      null
    }
  }

  private fun volumeSerial(): String? {
    try {
      var path: Path? = He.dataDirectory.file().toPath()
      while (path != null && !Files.exists(path)) path = path.parent

      val target = path ?: return null

      return Files.getFileStore(target).getAttribute("volume:vsn")?.toString()
    }
    catch (_: Throwable) {
      return null
    }
  }

  private fun encrypt(plain: ByteArray, key: SecretKeySpec): ByteArray {
    val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

    val body = cipher.doFinal(plain)
    val magic = MAGIC.toByteArray(Charsets.UTF_8)

    return ByteArray(magic.size + IV_LENGTH + body.size).also { out ->
      magic.copyInto(out, 0)
      iv.copyInto(out, magic.size)
      body.copyInto(out, magic.size + IV_LENGTH)
    }
  }

  private fun decrypt(encrypted: ByteArray, key: SecretKeySpec): ByteArray {
    val magic = MAGIC.toByteArray(Charsets.UTF_8)

    if (encrypted.size <= magic.size + IV_LENGTH) throw IllegalStateException("secret payload is too short")
    for (i in magic.indices) {
      if (encrypted[i] != magic[i]) throw IllegalStateException("unknown secret payload format")
    }

    val iv = encrypted.copyOfRange(magic.size, magic.size + IV_LENGTH)
    val body = encrypted.copyOfRange(magic.size + IV_LENGTH, encrypted.size)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

    return cipher.doFinal(body)
  }

  private fun restrictPermissions(file: Fi) {
    try {
      if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return

      Files.setPosixFilePermissions(file.file().toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
    }
    catch (_: Throwable) { }
  }
}
