package dtv.mobile.platform.huya.purelive.tars

import java.io.EOFException
import java.nio.charset.Charset

class TarsInputStream(private val data: ByteArray) {
  private var pos: Int = 0

  /**
   * 读取前统一校验剩余字节数。
   *
   * Tars 的字段长度全部来自网络，之前的 readString / readByteArray 直接
   * `data.copyOfRange(pos, pos + len)`：包被截断或长度字段异常时会抛
   * IndexOutOfBoundsException；LIST 分支的 `ByteArray(size)` 还会抛
   * NegativeArraySizeException 或直接 OOM。统一在这里拦掉，
   * 由上层 runCatching 兜住（与该文件既有风格一致）。
   */
  private fun requireRemaining(len: Int, what: String) {
    if (len < 0 || pos + len > data.size) {
      throw IllegalStateException("tars $what truncated: need=$len remaining=${data.size - pos}")
    }
  }
  private val utf8: Charset = Charsets.UTF_8

  private data class Head(val type: Int, val tag: Int, val headPos: Int)

  private fun readU8(): Int {
    if (pos >= data.size) throw EOFException("EOF")
    return data[pos++].toInt() and 0xFF
  }

  private fun readI8(): Byte = readU8().toByte()

  private fun readI16(): Short {
    val b1 = readU8()
    val b2 = readU8()
    return (((b1 shl 8) or b2).toShort())
  }

  private fun readI32(): Int {
    val b1 = readU8()
    val b2 = readU8()
    val b3 = readU8()
    val b4 = readU8()
    return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
  }

  private fun readI64(): Long {
    val hi = readI32().toLong() and 0xFFFFFFFFL
    val lo = readI32().toLong() and 0xFFFFFFFFL
    return (hi shl 32) or lo
  }

  private fun readHead(): Head {
    val headPos = pos
    val b = readU8()
    val type = b and 0x0F
    var tag = (b ushr 4) and 0x0F
    if (tag == 15) {
      tag = readU8()
    }
    return Head(type = type, tag = tag, headPos = headPos)
  }

  private fun skip(len: Int) {
    val newPos = pos + len
    if (newPos > data.size) throw EOFException("skip past EOF")
    pos = newPos
  }

  private fun skipField(type: Int) {
    when (type) {
      TarsTypes.BYTE -> skip(1)
      TarsTypes.SHORT -> skip(2)
      TarsTypes.INT -> skip(4)
      TarsTypes.LONG -> skip(8)
      TarsTypes.FLOAT -> skip(4)
      TarsTypes.DOUBLE -> skip(8)
      TarsTypes.STRING1 -> {
        val len = readU8()
        skip(len)
      }
      TarsTypes.STRING4 -> {
        val len = readI32()
        if (len < 0) throw IllegalStateException("negative string length: $len")
        skip(len)
      }
      TarsTypes.MAP -> {
        val size = readInt(tag = 0, required = true, defaultValue = 0)
        repeat(size * 2) { skipAny() }
      }
      TarsTypes.LIST -> {
        val size = readInt(tag = 0, required = true, defaultValue = 0)
        repeat(size) { skipAny() }
      }
      TarsTypes.STRUCT_BEGIN -> {
        while (true) {
          val h = readHead()
          if (h.type == TarsTypes.STRUCT_END) break
          skipField(h.type)
        }
      }
      TarsTypes.STRUCT_END -> Unit
      TarsTypes.ZERO_TAG -> Unit
      TarsTypes.SIMPLE_LIST -> {
        val h = readHead()
        if (h.type != TarsTypes.BYTE) throw IllegalStateException("simpleList element type must be BYTE")
        // dart_tars_protocol: size 使用 readInt(0, true)（带 head 的整型）
        val len = readInt(tag = 0, required = true, defaultValue = 0)
        if (len < 0) throw IllegalStateException("negative bytes length: $len")
        skip(len)
      }
      else -> throw IllegalStateException("unknown type: $type")
    }
  }

  private fun skipAny() {
    val h = readHead()
    skipField(h.type)
  }

  private fun skipToTag(targetTag: Int): Head? {
    if (targetTag < 0) return null
    while (pos < data.size) {
      val before = pos
      val h = readHead()
      if (h.type == TarsTypes.STRUCT_END) return null
      if (h.tag < targetTag) {
        skipField(h.type)
        continue
      }
      if (h.tag == targetTag) {
        return h
      }
      // h.tag > targetTag
      pos = before
      return null
    }
    return null
  }

  fun readInt(tag: Int, required: Boolean, defaultValue: Int): Int {
    val h = skipToTag(tag) ?: return if (required) throw IllegalStateException("tag $tag not found") else defaultValue
    return when (h.type) {
      TarsTypes.ZERO_TAG -> 0
      TarsTypes.BYTE -> readI8().toInt()
      TarsTypes.SHORT -> readI16().toInt()
      TarsTypes.INT -> readI32()
      TarsTypes.LONG -> readI64().toInt()
      else -> throw IllegalStateException("type ${h.type} cannot be read as int (tag=$tag)")
    }
  }

  fun readLong(tag: Int, required: Boolean, defaultValue: Long): Long {
    val h = skipToTag(tag) ?: return if (required) throw IllegalStateException("tag $tag not found") else defaultValue
    return when (h.type) {
      TarsTypes.ZERO_TAG -> 0L
      TarsTypes.BYTE -> readI8().toLong()
      TarsTypes.SHORT -> readI16().toLong()
      TarsTypes.INT -> readI32().toLong()
      TarsTypes.LONG -> readI64()
      else -> throw IllegalStateException("type ${h.type} cannot be read as long (tag=$tag)")
    }
  }

  fun readBool(tag: Int, required: Boolean, defaultValue: Boolean): Boolean {
    return readInt(tag, required, if (defaultValue) 1 else 0) != 0
  }

  fun readString(tag: Int, required: Boolean, defaultValue: String): String {
    val h = skipToTag(tag) ?: return if (required) throw IllegalStateException("tag $tag not found") else defaultValue
    return when (h.type) {
      TarsTypes.STRING1 -> {
        val len = readU8()
        requireRemaining(len, "string1")
        val bytes = data.copyOfRange(pos, pos + len)
        pos += len
        bytes.toString(utf8)
      }
      TarsTypes.STRING4 -> {
        val len = readI32()
        if (len < 0) throw IllegalStateException("negative string length: $len")
        requireRemaining(len, "string4")
        val bytes = data.copyOfRange(pos, pos + len)
        pos += len
        bytes.toString(utf8)
      }
      else -> throw IllegalStateException("type ${h.type} cannot be read as string (tag=$tag)")
    }
  }

  fun readByteArray(tag: Int, required: Boolean): ByteArray? {
    val h = skipToTag(tag) ?: return if (required) throw IllegalStateException("tag $tag not found") else null
    return when (h.type) {
      TarsTypes.SIMPLE_LIST -> {
        val elem = readHead()
        if (elem.type != TarsTypes.BYTE) throw IllegalStateException("simpleList element type must be BYTE")
        // dart_tars_protocol: size 使用 readInt(0, true)（带 head 的整型）
        val len = readInt(tag = 0, required = true, defaultValue = 0)
        if (len < 0) throw IllegalStateException("negative bytes length: $len")
        requireRemaining(len, "simpleList")
        val out = data.copyOfRange(pos, pos + len)
        pos += len
        out
      }
      TarsTypes.LIST -> {
        val size = readInt(tag = 0, required = true, defaultValue = 0)
        if (size < 0) throw IllegalStateException("negative list size: $size")
        // size 来自网络，先确认剩余字节够不够，再分配 —— 否则一个畸形包
        // 就能让 ByteArray(size) 抛 NegativeArraySizeException 或直接 OOM。
        requireRemaining(size, "list")
        val out = ByteArray(size)
        for (i in 0 until size) {
          val elemHead = readHead()
          if (elemHead.type != TarsTypes.BYTE) throw IllegalStateException("list element type must be BYTE")
          out[i] = readI8()
        }
        out
      }
      else -> throw IllegalStateException("type ${h.type} cannot be read as byte[] (tag=$tag)")
    }
  }

  fun <T : TarsStruct> readStruct(tag: Int, required: Boolean, factory: () -> T): T {
    if (tag >= 0) {
      val h = skipToTag(tag)
        ?: return if (required) throw IllegalStateException("tag $tag not found") else factory()
      if (h.type != TarsTypes.STRUCT_BEGIN) {
        return if (required) throw IllegalStateException("tag $tag is not a struct") else factory()
      }
    } else {
      val h = readHead()
      if (h.type != TarsTypes.STRUCT_BEGIN) throw IllegalStateException("not a struct begin")
    }

    val t = factory()
    t.readFrom(this)
    // consume until STRUCT_END
    while (true) {
      val h = readHead()
      if (h.type == TarsTypes.STRUCT_END) break
      skipField(h.type)
    }
    return t
  }
}

