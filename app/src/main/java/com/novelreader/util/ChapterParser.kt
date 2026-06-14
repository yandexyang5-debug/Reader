package com.novelreader.util

import com.novelreader.data.model.Chapter

object ChapterParser {
    // 章节标题正则模式
    private val chapterPatterns = listOf(
        // 中文标准格式：第X章/回/节/卷/折 + 标题
        "^第[一二三四五六七八九十百千万零壹贰叁肆伍陆柒捌玖拾佰仟\\d]+章\\s*.+".toRegex(),
        "^第[一二三四五六七八九十百千万零壹贰叁肆伍陆柒捌玖拾佰仟\\d]+回\\s*.+".toRegex(),
        "^第[一二三四五六七八九十百千万零壹贰叁肆伍陆柒捌玖拾佰仟\\d]+节\\s*.+".toRegex(),
        "^第[一二三四五六七八九十百千万零壹贰叁肆伍陆柒捌玖拾佰仟\\d]+卷\\s*.+".toRegex(),
        "^第[一二三四五六七八九十百千万零壹贰叁肆伍陆柒捌玖拾佰仟\\d]+折\\s*.+".toRegex(),
        "^卷[一二三四五六七八九十百千万零壹贰叁肆伍陆柒捌玖拾佰仟\\d]+\\s*.*".toRegex(),
        // 带括号/方括号的格式：【第一章】、[第一章]、（第一章）
        "[【\\[（(]第[一二三四五六七八九十百千万零壹贰叁肆伍陆柒捌玖拾佰仟\\d]+[章回节卷折][】\\]）)]".toRegex(),
        "[【\\[（(](楔子|正文|序章|序言|番外|尾声|后记|引子)[】\\]）)]".toRegex(),
        // 英文章节格式：Chapter 1、CHAPTER 1、chapter 12
        "(?i)^chapter\\s+\\d+.*".toRegex(),
        // 纯数字章节：001、01、1.、1、1、1）等（至少2位或带标点，避免误匹配普通数字）
        "^\\d{2,}[\\.、\\s：:）)].*".toRegex(),
        "^\\d{1,}[\\.、]\\s*\\S.*".toRegex(),
        // 独占一行的特殊标记
        "^楔子$".toRegex(),
        "^正文$".toRegex(),
        "^序$".toRegex(),
        "^序章$".toRegex(),
        "^序言$".toRegex(),
        "^番外$".toRegex(),
        "^尾声$".toRegex(),
        "^后记$".toRegex(),
        "^引子$".toRegex()
    )

    // 每个回退分割块的目标字数
    private const val FALLBACK_CHARS_PER_CHAPTER = 8000

    /**
     * 解析文本中的章节
     * @param text 完整的小说文本
     * @param bookId 书籍ID
     * @return 章节列表
     */
    fun parseChapters(text: String, bookId: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val lines = text.split("\n")

        var currentOffset = 0

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (isChapterTitle(trimmed)) {
                val chapter = Chapter(
                    bookId = bookId,
                    index = chapters.size,
                    title = trimmed,
                    startOffset = currentOffset,
                    endOffset = 0, // 稍后计算
                    charCount = 0   // 稍后计算
                )
                chapters.add(chapter)
            }
            currentOffset += line.toByteArray(Charsets.UTF_8).size + 1 // UTF-8字节数 + 换行符
        }

        // 正则匹配 0 章时，回退到按固定字数分割
        if (chapters.isEmpty()) {
            return fallbackSplit(text, bookId)
        }

        // 计算每个章节的结束偏移和字符数
        calculateChapterBounds(chapters, text)

        return chapters
    }

    /**
     * 判断是否是章节标题
     */
    private fun isChapterTitle(text: String): Boolean {
        if (text.isEmpty()) return false

        for (pattern in chapterPatterns) {
            if (pattern.containsMatchIn(text)) {
                return true
            }
        }

        return false
    }

    /**
     * 计算每个章节的结束偏移和字符数
     */
    private fun calculateChapterBounds(chapters: MutableList<Chapter>, text: String) {
        val totalByteSize = text.toByteArray(Charsets.UTF_8).size
        for (i in chapters.indices) {
            val chapter = chapters[i]
            val nextStartOffset = if (i < chapters.size - 1) {
                chapters[i + 1].startOffset
            } else {
                totalByteSize
            }

            chapters[i] = chapter.copy(
                endOffset = nextStartOffset,
                charCount = nextStartOffset - chapter.startOffset
            )
        }
    }

    /**
     * 兜底策略：按固定字数分割，确保始终有目录
     */
    private fun fallbackSplit(text: String, bookId: String): List<Chapter> {
        val totalBytes = text.toByteArray(Charsets.UTF_8).size
        val lines = text.split("\n")
        val chapters = mutableListOf<Chapter>()
        var currentOffset = 0
        var chapterStartOffset = 0
        var chapterCharCount = 0
        var chapterIndex = 0

        for (line in lines) {
            val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
            if (chapterCharCount >= FALLBACK_CHARS_PER_CHAPTER && chapterCharCount > 0) {
                chapters.add(
                    Chapter(
                        bookId = bookId,
                        index = chapterIndex,
                        title = "第${chapterIndex + 1}节",
                        startOffset = chapterStartOffset,
                        endOffset = currentOffset,
                        charCount = chapterCharCount
                    )
                )
                chapterIndex++
                chapterStartOffset = currentOffset
                chapterCharCount = 0
            }
            chapterCharCount += line.length + 1
            currentOffset += lineBytes
        }

        // 最后一段
        if (chapterCharCount > 0) {
            chapters.add(
                Chapter(
                    bookId = bookId,
                    index = chapterIndex,
                    title = "第${chapterIndex + 1}节",
                    startOffset = chapterStartOffset,
                    endOffset = totalBytes,
                    charCount = chapterCharCount
                )
            )
        }

        return chapters
    }

    /**
     * 将UTF-8字节偏移转换为字符串中的字符索引
     */
    private fun byteOffsetToCharIndex(text: String, byteOffset: Int): Int {
        var byteCount = 0
        for (i in text.indices) {
            if (byteCount >= byteOffset) return i
            byteCount += text[i].toString().toByteArray(Charsets.UTF_8).size
        }
        return text.length
    }

    /**
     * 从全文字符串中按章节的字节偏移提取章节内容
     * 用于全文搜索等场景，其中 fullContent 已作为字符串加载
     */
    fun chapterContentFromBytes(fullContent: String, chapter: Chapter): String {
        val startIndex = byteOffsetToCharIndex(fullContent, chapter.startOffset)
        val endIndex = byteOffsetToCharIndex(fullContent, chapter.endOffset)
        return if (startIndex < endIndex) {
            fullContent.substring(startIndex, endIndex)
        } else {
            ""
        }
    }
}
