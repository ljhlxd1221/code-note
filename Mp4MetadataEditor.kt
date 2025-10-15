import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * Mp4MetadataEditor (增强版)
 *
 * 特性：
 * - 支持 32-bit 和 64-bit (extended size, size == 1) box header
 * - 在替换/插入 ilst 时会复用原有 udta/meta 下的 hdlr/keys/其它子盒，避免破坏原结构
 * - 可设置常见 iTunes 风格 tag：©nam, ©ART, ©cmt, desc 或任意四字符箱名
 *
 * 限制（重要）：
 * - 假设源文件总体结构合理（moov 存在且可解析）
 * - 对极端/非标准 meta 结构可能需要额外兼容
 * - 仍然推荐写入到临时文件，然后原子替换
 *
 * 用法见类末尾注释
 */
class Mp4MetadataEditor(private val srcFile: File) {

    private val tags: MutableMap<String, String> = mutableMapOf()

    fun setTitle(title: String) = apply { tags["\u00A9nam"] = title } // ©nam
    fun setArtist(artist: String) = apply { tags["\u00A9ART"] = artist } // ©ART
    fun setComment(comment: String) = apply { tags["\u00A9cmt"] = comment } // ©cmt
    fun setDescription(description: String) = apply { tags["desc"] = description } // desc
    fun putTag(boxType: String, value: String) = apply { tags[boxType] = value }

    @Throws(IOException::class)
    fun save(outFile: File) {
        RandomAccessFile(srcFile, "r").use { raf ->
            RandomAccessFile(outFile, "rw").use { out ->
                raf.seek(0)
                while (raf.filePointer < raf.length()) {
                    val header = readBoxHeader(raf) ?: break
                    val boxStart = header.start
                    val boxSize = header.size
                    val headerSize = header.headerSize
                    val boxType = header.type

                    // Sanity check
                    if (boxSize <= 0L || boxSize > Long.MAX_VALUE - 1024) {
                        // 遇到不可处理的 size，直接复制剩余并退出
                        copyRemaining(raf, out)
                        break
                    }

                    if (boxType == "moov") {
                        // 读取完整 moov payload (不含外层 header)
                        val payloadSize = boxSize - headerSize
                        if (payloadSize > Int.MAX_VALUE) {
                            // 极端大 moov，不支持
                            copyBytes(raf, out, boxStart, boxSize)
                            continue
                        }
                        val moovBytes = ByteArray(payloadSize.toInt())
                        raf.readFully(moovBytes)
                        val newMoov = patchMoovWithIList(moovBytes, tags)
                        // 写入新 moov（含 header）
                        val newBoxSize = headerSize + newMoov.size
                        // 写 size (32bit if fits, otherwise write as 1 + 64-bit largeSize)
                        writeBoxHeader(out, newBoxSize, "moov")
                        out.write(newMoov)
                    } else {
                        // 直接复制原 box（含 header）
                        copyBytes(raf, out, boxStart, boxSize)
                    }
                }
                out.fd.sync()
            }
        }
    }

    // ---------- Helpers ----------

    private data class BoxHeader(val size: Long, val type: String, val start: Long, val headerSize: Int)

    /**
     * 读取当前文件指针处的 box header。
     * 返回 header，并将文件指针移动到 header 之后（payload 开始）。
     * 支持 32-bit size、64-bit extended size (size == 1)。
     */
    private fun readBoxHeader(raf: RandomAccessFile): BoxHeader? {
        val start = raf.filePointer
        val remaining = raf.length() - start
        if (remaining < 8) return null
        val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
        val typeBytes = ByteArray(4)
        raf.readFully(typeBytes)
        val type = String(typeBytes, Charsets.US_ASCII)
        return if (size32 == 1L) {
            // extended size (64-bit)
            if (raf.filePointer + 8 > raf.length()) {
                // invalid, fallback
                BoxHeader(size32, type, start, 8)
            } else {
                val large = raf.readLong()
                BoxHeader(large, type, start, 16)
            }
        } else {
            BoxHeader(size32, type, start, 8)
        }
    }

    // 将一个 box 从源文件的 boxStart 开始，拷贝 boxSize 个字节到目标（包含 header）
    private fun copyBytes(src: RandomAccessFile, dst: RandomAccessFile, boxStart: Long, boxSize: Long) {
        val buffer = ByteArray(8192)
        var remaining = boxSize
        src.seek(boxStart)
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = src.read(buffer, 0, toRead)
            if (read <= 0) break
            dst.write(buffer, 0, read)
            remaining -= read
        }
        // 保证源文件指针移动到 boxStart+boxSize
        src.seek(boxStart + boxSize)
    }

    private fun copyRemaining(src: RandomAccessFile, dst: RandomAccessFile) {
        val buffer = ByteArray(8192)
        var read: Int
        src.seek(src.filePointer)
        while (true) {
            read = src.read(buffer)
            if (read <= 0) break
            dst.write(buffer, 0, read)
        }
    }

    // 写 box header（自动选择 32/64 位）
    private fun writeBoxHeader(dst: RandomAccessFile, totalSize: Long, type: String) {
        if (totalSize <= 0xFFFFFFFFL) {
            dst.writeInt((totalSize and 0xFFFFFFFFL).toInt())
            dst.write(type.toByteArray(Charsets.US_ASCII))
        } else {
            // 32-bit size set to 1, then write 64-bit large size
            dst.writeInt(1)
            dst.write(type.toByteArray(Charsets.US_ASCII))
            dst.writeLong(totalSize)
        }
    }

    // 将 moov payload (无外层 header) 中的 udta/meta/ilst 进行替换/插入
    private fun patchMoovWithIList(moovBytes: ByteArray, tags: Map<String, String>): ByteArray {
        // 解析 moov 的 children（返回完整子盒 bytes，包含各自 header）
        val children = parseChildrenBoxes(moovBytes)
        val newChildren = mutableListOf<ByteArray>()
        var udtaReplaced = false

        for (child in children) {
            val type = readTypeFromBox(child)
            if (type == "udta") {
                // 解析 udta 内容，尝试替换或合并 meta
                val newUdta = mergeUdtaWithTags(child, tags)
                newChildren.add(newUdta)
                udtaReplaced = true
            } else {
                newChildren.add(child)
            }
        }

        if (!udtaReplaced) {
            // 构造新 udta 并追加
            newChildren.add(buildUdtaBox(tags))
        }

        val baos = ByteArrayOutputStream()
        for (ch in newChildren) baos.write(ch)
        return baos.toByteArray()
    }

    // 解析 containerBytes（内存）里的子box，支持 32/64 header
    // 返回 list of full box bytes (含 header)
    private fun parseChildrenBoxes(containerBytes: ByteArray): List<ByteArray> {
        val res = mutableListOf<ByteArray>()
        var pos = 0
        val len = containerBytes.size
        while (pos + 8 <= len) {
            val size32 = uint32(containerBytes, pos)
            val type = String(containerBytes, pos + 4, 4, Charsets.US_ASCII)
            if (size32 == 0L) {
                // extends to end of container
                val remain = containerBytes.copyOfRange(pos, len)
                res.add(remain)
                break
            }
            if (size32 == 1L) {
                // need 64-bit size
                if (pos + 16 > len) {
                    val remain = containerBytes.copyOfRange(pos, len)
                    res.add(remain)
                    break
                }
                val largeSize = uint64(containerBytes, pos + 8)
                if (pos + largeSize > len) {
                    val remain = containerBytes.copyOfRange(pos, len)
                    res.add(remain)
                    break
                }
                val boxBytes = containerBytes.copyOfRange(pos, (pos + largeSize).toInt())
                res.add(boxBytes)
                pos += largeSize.toInt()
            } else {
                val boxSize = size32.toInt()
                if (pos + boxSize > len) {
                    val remain = containerBytes.copyOfRange(pos, len)
                    res.add(remain)
                    break
                }
                val boxBytes = containerBytes.copyOfRange(pos, pos + boxSize)
                res.add(boxBytes)
                pos += boxSize
            }
        }
        return res
    }

    private fun uint32(buf: ByteArray, off: Int): Long {
        val b0 = buf[off].toLong() and 0xFF
        val b1 = buf[off + 1].toLong() and 0xFF
        val b2 = buf[off + 2].toLong() and 0xFF
        val b3 = buf[off + 3].toLong() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun uint64(buf: ByteArray, off: Int): Long {
        var res = 0L
        for (i in 0 until 8) {
            res = (res shl 8) or (buf[off + i].toLong() and 0xFF)
        }
        return res
    }

    private fun readTypeFromBox(boxBytes: ByteArray): String {
        if (boxBytes.size < 8) return ""
        return String(boxBytes, 4, 4, Charsets.US_ASCII)
    }

    // 合并原 udta (完整 box bytes) 与新的 tags：保留原 meta 中的 hdlr/keys/其它子盒，替换或插入 ilst
    private fun mergeUdtaWithTags(originalUdtaBox: ByteArray, tags: Map<String, String>): ByteArray {
        // originalUdtaBox 包含 header（8 或 16 bytes）+ payload
        val type = readTypeFromBox(originalUdtaBox)
        if (type != "udta") return originalUdtaBox // 安全检查

        // 找到 udta 的 payload offset:
        val headerSize = if (isLargeHeader(originalUdtaBox)) 16 else 8
        val payload = originalUdtaBox.copyOfRange(headerSize, originalUdtaBox.size)

        // 在 payload 中查找 meta box
        val children = parseChildrenBoxes(payload)
        var foundMeta: ByteArray? = null
        val otherChildren = mutableListOf<ByteArray>()
        for (c in children) {
            if (readTypeFromBox(c) == "meta") {
                foundMeta = c
            } else {
                otherChildren.add(c)
            }
        }

        val newMetaBox = if (foundMeta != null) {
            // 合并已有 meta：保留 version(4bytes)，保留除了 ilst 的子盒，插入新的 ilst
            mergeMetaBox(foundMeta, tags)
        } else {
            // 构造全新 meta (standard: 4 bytes version+flags + ilst)
            val ilst = buildIListBox(tags)
            val metaPayload = ByteArrayOutputStream()
            metaPayload.write(intToBytes(0)) // version + flags
            metaPayload.write(ilst)
            buildBox("meta", metaPayload.toByteArray())
        }

        // 构造新的 udta payload：保留 otherChildren + newMetaBox
        val baos = ByteArrayOutputStream()
        // write other children (preserve original order: put meta at same relative place? here append meta to end)
        for (c in otherChildren) baos.write(c)
        baos.write(newMetaBox)
        val newUdta = buildBox("udta", baos.toByteArray())
        return newUdta
    }

    // 判断 box header 是否是 large header(16)
    private fun isLargeHeader(boxBytes: ByteArray): Boolean {
        if (boxBytes.size < 8) return false
        val s = uint32(boxBytes, 0)
        return s == 1L
    }

    // 合并已有 meta box: 保留 version(4 bytes)，保留除 ilst 外子盒，插入新的 ilst
    private fun mergeMetaBox(originalMetaBox: ByteArray, tags: Map<String, String>): ByteArray {
        // originalMetaBox 包含 header + payload
        val headerSize = if (isLargeHeader(originalMetaBox)) 16 else 8
        val payload = originalMetaBox.copyOfRange(headerSize, originalMetaBox.size)

        // payload 的前 4 字节为 version+flags
        if (payload.size < 4) {
            // 奇怪的 meta，直接返回新建 meta
            val ilst = buildIListBox(tags)
            val metaPayload = ByteArrayOutputStream()
            metaPayload.write(intToBytes(0))
            metaPayload.write(ilst)
            return buildBox("meta", metaPayload.toByteArray())
        }

        val versionBytes = payload.copyOfRange(0, 4)
        val childBytes = payload.copyOfRange(4, payload.size)

        // 解析 childBoxes of meta.payload (excluding version)
        val children = parseChildrenBoxes(childBytes)
        val preserved = mutableListOf<ByteArray>()
        for (c in children) {
            val t = readTypeFromBox(c)
            if (t == "ilst") {
                // skip existing ilst (we will replace)
                continue
            } else {
                preserved.add(c)
            }
        }

        // build new ilst
        val ilst = buildIListBox(tags)

        // construct new meta payload: version + preserved children + ilst
        val baos = ByteArrayOutputStream()
        baos.write(versionBytes)
        for (p in preserved) baos.write(p)
        baos.write(ilst)

        return buildBox("meta", baos.toByteArray())
    }

    // 构造 udta (当原 udta 不存在)
    private fun buildUdtaBox(tags: Map<String, String>): ByteArray {
        val ilst = buildIListBox(tags)
        val metaPayload = ByteArrayOutputStream()
        metaPayload.write(intToBytes(0)) // version + flags
        metaPayload.write(ilst)
        val metaBox = buildBox("meta", metaPayload.toByteArray())
        return buildBox("udta", metaBox)
    }

    // 构造 ilst（每个 tag 一个子 box）
    private fun buildIListBox(tags: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        for ((k, v) in tags) {
            val item = buildMetaItemBox(k, v)
            baos.write(item)
        }
        return buildBox("ilst", baos.toByteArray())
    }

    // 构造单个 metadata item：type(4) -> data box (含 4 bytes data header + data bytes)
    private fun buildMetaItemBox(type: String, value: String): ByteArray {
        val dataBytes = value.toByteArray(Charsets.UTF_8)
        val dataPayload = ByteArrayOutputStream()
        // 一般 iTunes data box 前 4 bytes 表示 locale/type. 0x00000001 常表示 UTF-8/UTF-16 text depending impl.
        dataPayload.write(intToBytes(1))
        dataPayload.write(dataBytes)
        val dataBox = buildBox("data", dataPayload.toByteArray())
        return buildBox(type, dataBox)
    }

    // 通用构造 box（header + payload），注意 size 采用 32-bit（如果 payload 大于 0xFFFFFFFF 则不会正确 —— 极端情况）
    private fun buildBox(type: String, payload: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val size = 8 + payload.size
        baos.write(intToBytes(size))
        baos.write(type.toByteArray(Charsets.US_ASCII))
        baos.write(payload)
        return baos.toByteArray()
    }

    private fun intToBytes(v: Int): ByteArray {
        return byteArrayOf(
            ((v ushr 24) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte()
        )
    }

    // ---------- Companion usage note ----------
    companion object {
        /**
         * 使用示例：
         *
         * val editor = Mp4MetadataEditor(File("/sdcard/input.mp4"))
         *   .setTitle("Trip 2025")
         *   .setArtist("Li")
         *   .setComment("phone shot")
         *
         * editor.save(File("/sdcard/output_with_meta.mp4"))
         *
         * 建议：先写到临时文件，再用 atomic move 替换原文件，避免出错导致源文件损坏。
         */
    }
}
