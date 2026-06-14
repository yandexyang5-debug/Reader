package com.novelreader.util

import android.content.Context
import android.net.Uri
import com.novelreader.data.model.Book
import com.novelreader.data.model.Chapter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object FileImporter {
    /**
     * 从URI导入TXT文件
     */
    fun importFromUri(context: Context, uri: Uri): Pair<Book, String>? {
        return try {
            // 获取文件名
            val fileName = getFileName(context, uri) ?: "未知书籍"

            // 复制文件到App内部存储
            val internalFile = copyToInternal(context, uri, fileName)
                ?: return null

            // 读取文件内容（自动检测编码）
            val content = readTextWithEncoding(internalFile)

            // 如果不是UTF-8编码，转换为UTF-8保存
            val bytes = internalFile.readBytes()
            val encoding = detectEncoding(bytes)
            if (encoding != Charsets.UTF_8) {
                // 转换为UTF-8并覆盖保存
                internalFile.writeText(content, Charsets.UTF_8)
            }

            // 提取书名和作者
            val (title, author) = extractMetadata(content, fileName)

            // 创建Book对象
            val book = Book(
                title = title,
                author = author,
                filePath = internalFile.absolutePath,
                fileSize = internalFile.length(),
                chapterCount = 0,
                totalCharCount = content.length
            )

            Pair(book, content)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 自动检测编码并读取文本
     */
    private fun readTextWithEncoding(file: File): String {
        val bytes = file.readBytes()

        // 检测BOM（字节顺序标记）
        val encoding = detectEncoding(bytes)

        return String(bytes, encoding)
    }

    /**
     * 检测文件编码
     */
    private fun detectEncoding(bytes: ByteArray): Charset {
        // UTF-8 BOM: EF BB BF
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return Charsets.UTF_8
        }

        // UTF-16 LE BOM: FF FE
        if (bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte()
        ) {
            return Charsets.UTF_16LE
        }

        // UTF-16 BE BOM: FE FF
        if (bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte()
        ) {
            return Charsets.UTF_16BE
        }

        // 尝试UTF-8解码，检查是否有效
        if (isValidUtf8(bytes)) {
            return Charsets.UTF_8
        }

        // 默认使用GBK（中文系统常见编码）
        return Charset.forName("GBK")
    }

    /**
     * 验证字节是否为有效UTF-8编码
     * 先做GBK字节模式快速排除，再用CharsetDecoder严格验证
     */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0x80..0xFF) {
                // 高字节后必须紧跟UTF-8续接字节(0x80-0xBF)
                // GBK尾字节范围0x40-0xFE，其中0xC0-0xFE不是UTF-8续接字节
                if (i + 1 < bytes.size) {
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    if (b2 < 0x80 || b2 > 0xBF) {
                        // 高字节后跟非续接字节 → 不是UTF-8（很可能是GBK）
                        return false
                    }
                }
                // 跳过当前多字节序列
                val len = when {
                    b in 0xC0..0xDF -> 2
                    b in 0xE0..0xEF -> 3
                    b in 0xF0..0xF7 -> 4
                    else -> return false // 0x80-0xBF 单独出现不是合法UTF-8起始字节
                }
                i += len
            } else {
                i++
            }
        }
        // 快速检查通过后，用CharsetDecoder做严格验证
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取文件名
     */
    private fun getFileName(context: Context, uri: Uri): String? {
        var fileName: String? = null

        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        fileName = it.getString(nameIndex)
                    }
                }
            }
        }

        if (fileName == null) {
            fileName = uri.path?.split("/")?.lastOrNull()
        }

        // 移除.txt扩展名
        return fileName?.removeSuffix(".txt")
    }

    /**
     * 复制文件到内部存储
     */
    private fun copyToInternal(context: Context, uri: Uri, fileName: String): File? {
        return try {
            val internalDir = File(context.filesDir, "novels")
            if (!internalDir.exists()) {
                internalDir.mkdirs()
            }

            val internalFile = File(internalDir, "$fileName.txt")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(internalFile).use { output ->
                    input.copyTo(output)
                }
            }

            internalFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 从内容中提取元数据
     */
    private fun extractMetadata(content: String, fileName: String): Pair<String, String> {
        var title = fileName
        var author = ""

        // 尝试从前几行提取信息
        val lines = content.split("\n").take(20)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("书名") || trimmed.startsWith("《")) {
                title = trimmed
                    .removePrefix("书名")
                    .removePrefix("：")
                    .removePrefix(":")
                    .trim()
                    .removeSurrounding("《", "》")
            } else if (trimmed.startsWith("作者") || trimmed.startsWith("著")) {
                author = trimmed
                    .removePrefix("作者")
                    .removePrefix("著")
                    .removePrefix("：")
                    .removePrefix(":")
                    .trim()
            }
        }

        return Pair(title, author)
    }

    /**
     * 解析章节
     */
    fun parseChapters(content: String, bookId: String): List<Chapter> {
        return ChapterParser.parseChapters(content, bookId)
    }

    /**
     * 删除书籍文件
     */
    fun deleteBookFile(context: Context, filePath: String) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
