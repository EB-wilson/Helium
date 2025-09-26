import helium.util.IndexedSerial
import helium.util.SerialObject

data class Obj(
  val n: Int
): SerialObject {
  override var indexes = intArrayOf(0)

  override fun toString(): String {
    return "(n:${n}; index:${indexes[0]})"
  }
}

val serial = IndexedSerial<Obj>()

fun main() {
  val list = mutableListOf<Obj>()

  for (n in 0..10) {
    val obj = Obj(n)
    list.add(obj)
    serial.add(obj)
  }

  println(serial)

  serial.remove(list[4])
  serial.remove(list[5])
  serial.remove(list[6])

  println(serial)
}
