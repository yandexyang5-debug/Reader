package com.novelreader.ui.reader

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.data.local.NovelDatabase
import com.novelreader.data.model.Book
import com.novelreader.data.model.Chapter
import com.novelreader.data.model.PageMode
import com.novelreader.data.model.ReadingSettings
import com.novelreader.data.repository.BookRepository
import com.novelreader.util.ChapterParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile

/**
 * 全文内容加载状态
 */
sealed class FullContentState {
    object Idle : FullContentState()
    object Loading : FullContentState()
    data class Ready(val content: String) : FullContentState()
    object Error : FullContentState()
}

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val database = NovelDatabase.getDatabase(application)
    private val repository = BookRepository(database.bookDao(), database.chapterDao())

    // SharedPreferences 用于保存阅读设置
    private val prefs: SharedPreferences = application.getSharedPreferences("reading_settings", Context.MODE_PRIVATE)

    private val _currentBook = MutableStateFlow<Book?>(null)
    val currentBook: StateFlow<Book?> = _currentBook.asStateFlow()

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    private val _currentChapterContent = MutableStateFlow("")
    val currentChapterContent: StateFlow<String> = _currentChapterContent.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ReadingSettings> = _settings.asStateFlow()

    private val _isMenuVisible = MutableStateFlow(false)
    val isMenuVisible: StateFlow<Boolean> = _isMenuVisible.asStateFlow()

    private val _isTOCVisible = MutableStateFlow(false)
    val isTOCVisible: StateFlow<Boolean> = _isTOCVisible.asStateFlow()

    private val _isSearchVisible = MutableStateFlow(false)
    val isSearchVisible: StateFlow<Boolean> = _isSearchVisible.asStateFlow()

    private val _isSettingsVisible = MutableStateFlow(false)
    val isSettingsVisible: StateFlow<Boolean> = _isSettingsVisible.asStateFlow()

    // 章节内阅读位置（页码或段落索引）
    private val _lastReadPosition = MutableStateFlow(0)
    val lastReadPosition: StateFlow<Int> = _lastReadPosition.asStateFlow()

    // 搜索跳转目标段落（-1表示不需要跳转）
    private val _scrollToParagraph = MutableStateFlow(-1)
    val scrollToParagraph: StateFlow<Int> = _scrollToParagraph.asStateFlow()

    private val _fullContentState = MutableStateFlow<FullContentState>(FullContentState.Idle)
    val fullContentState: StateFlow<FullContentState> = _fullContentState.asStateFlow()

    // === 全文搜索 ===
    data class SearchResult(
        val chapterTitle: String,
        val paragraphText: String,      // 段落全文（用于跳转定位）
        val paragraphIndex: Int,
        val excerpt: String = "",        // 摘录（匹配位置附近文字，用于列表显示）
        val matchPosition: Int = -1      // 匹配在段落中的起始位置
    )

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchProgress = MutableStateFlow(0)
    val searchProgress: StateFlow<Int> = _searchProgress.asStateFlow()

    private var searchJob: Job? = null

    // 预解析章节缓存：标题 + 原始段落 + lowercase 段落（避免搜索时重复 lowercase）
    private data class ParsedChapter(
        val title: String,
        val paragraphs: List<String>,
        val lowerParagraphs: List<String>
    )
    private var parsedChapters: List<ParsedChapter>? = null

    /**
     * 从SharedPreferences加载阅读设置
     */
    private fun loadSettings(): ReadingSettings {
        val pageModeOrdinal = prefs.getInt("pageMode", 0)
        val pageMode = PageMode.entries.getOrElse(pageModeOrdinal) { PageMode.VERTICAL_SCROLL }

        return ReadingSettings(
            fontSize = prefs.getFloat("fontSize", 18f),
            lineHeight = prefs.getFloat("lineHeight", 1.8f),
            letterSpacing = prefs.getFloat("letterSpacing", 0f),
            paragraphSpacing = prefs.getFloat("paragraphSpacing", 12f),
            backgroundColor = prefs.getLong("backgroundColor", 0xFFFFFFFF),
            textColor = prefs.getLong("textColor", 0xFF333333),
            isNightMode = prefs.getBoolean("isNightMode", false),
            pageMode = pageMode,
            flipAnimation = prefs.getBoolean("flipAnimation", true),
            flipSensitivity = prefs.getFloat("flipSensitivity", 0.33f)
        )
    }

    /**
     * 保存阅读设置到SharedPreferences
     */
    private fun saveSettings(settings: ReadingSettings) {
        prefs.edit().apply {
            putFloat("fontSize", settings.fontSize)
            putFloat("lineHeight", settings.lineHeight)
            putFloat("letterSpacing", settings.letterSpacing)
            putFloat("paragraphSpacing", settings.paragraphSpacing)
            putLong("backgroundColor", settings.backgroundColor)
            putLong("textColor", settings.textColor)
            putBoolean("isNightMode", settings.isNightMode)
            putInt("pageMode", settings.pageMode.ordinal)
            putBoolean("flipAnimation", settings.flipAnimation)
            putFloat("flipSensitivity", settings.flipSensitivity)
            apply()
        }
    }

    fun loadBook(bookId: String) {
        viewModelScope.launch {
            val book = repository.getBookById(bookId)
            _currentBook.value = book

            if (book != null) {
                // 加载章节列表
                repository.getChaptersByBookId(bookId).collect { chapterList ->
                    _chapters.value = chapterList

                    // 加载上次阅读位置
                    val lastIndex = book.lastChapterIndex
                    if (lastIndex in chapterList.indices) {
                        _currentChapterIndex.value = lastIndex
                        _lastReadPosition.value = book.lastParagraphIndex
                        loadChapterContent(chapterList[lastIndex])
                    }
                }
            }
        }
    }

    fun loadChapterContent(chapter: Chapter) {
        viewModelScope.launch {
            try {
                val book = _currentBook.value ?: return@launch
                val file = File(book.filePath)
                if (file.exists()) {
                    val content = withContext(Dispatchers.IO) {
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(chapter.startOffset.toLong())
                            val byteCount = chapter.endOffset - chapter.startOffset
                            val byteArray = ByteArray(byteCount)
                            raf.readFully(byteArray)
                            String(byteArray, Charsets.UTF_8)
                        }
                    }
                    _currentChapterContent.value = content
                    // 章节内容改变，全文缓存失效
                    _fullContentState.value = FullContentState.Idle
                    parsedChapters = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _currentChapterContent.value = "章节加载失败"
            }
        }
    }

    fun goToChapter(chapterIndex: Int) {
        val chapterList = _chapters.value
        if (chapterIndex in chapterList.indices) {
            _lastReadPosition.value = 0
            _currentChapterIndex.value = chapterIndex
            loadChapterContent(chapterList[chapterIndex])
            saveProgress()
        }
    }

    /**
     * 跳转到指定章节的指定段落（用于搜索结果跳转）
     */
    fun goToChapterAndParagraph(chapterIndex: Int, paragraphIndex: Int) {
        val chapterList = _chapters.value
        if (chapterIndex in chapterList.indices) {
            _scrollToParagraph.value = paragraphIndex
            _currentChapterIndex.value = chapterIndex
            loadChapterContent(chapterList[chapterIndex])
            saveProgress()
        }
    }

    /**
     * 通过章节标题跳转搜索结果
     */
    fun goToSearchResult(chapterTitle: String, paragraphIndex: Int) {
        val chapterList = _chapters.value
        val chapterIndex = chapterList.indexOfFirst { it.title == chapterTitle }
        if (chapterIndex >= 0) {
            goToChapterAndParagraph(chapterIndex, paragraphIndex)
        }
    }

    /**
     * 清除段落跳转标记（跳转完成后调用）
     */
    fun clearScrollToParagraph() {
        _scrollToParagraph.value = -1
    }

    fun goToNextChapter() {
        val nextIndex = _currentChapterIndex.value + 1
        if (nextIndex < _chapters.value.size) {
            goToChapter(nextIndex)
        }
    }

    fun goToPreviousChapter() {
        val prevIndex = _currentChapterIndex.value - 1
        if (prevIndex >= 0) {
            goToChapter(prevIndex)
        }
    }

    fun toggleMenu() {
        _isMenuVisible.value = !_isMenuVisible.value
    }

    fun hideMenu() {
        _isMenuVisible.value = false
    }

    fun showTOC() {
        _isTOCVisible.value = true
        _isMenuVisible.value = false
    }

    fun hideTOC() {
        _isTOCVisible.value = false
    }

    fun showSearch() {
        _isMenuVisible.value = false
        _isSearchVisible.value = true
        // 懒加载全文：仅在未加载时读取
        if (_fullContentState.value is FullContentState.Idle) {
            _fullContentState.value = FullContentState.Loading
            viewModelScope.launch {
                _fullContentState.value = try {
                    val book = _currentBook.value ?: return@launch
                    val file = File(book.filePath)
                    if (file.exists()) {
                        val content = withContext(Dispatchers.IO) {
                            file.readText(Charsets.UTF_8)
                        }
                        FullContentState.Ready(content)
                    } else {
                        FullContentState.Error
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    FullContentState.Error
                }
            }
        }
    }

    fun hideSearch() {
        _isSearchVisible.value = false
        searchJob?.cancel()
        _isSearching.value = false
        _searchResults.value = emptyList()
    }

    /**
     * 预解析章节内容并缓存（只执行一次）
     * 优化：
     * 1. 单次遍历计算所有章节的字符偏移（替代每章单独 byte→char 遍历）
     * 2. 预缓存 lowercase 段落（搜索时零转换开销）
     */
    private suspend fun ensureParsedChapters(): List<ParsedChapter> {
        parsedChapters?.let { return it }
        val chapters = _chapters.value
        val content = when (val state = _fullContentState.value) {
            is FullContentState.Ready -> state.content
            else -> return emptyList()
        }
        return withContext(Dispatchers.Default) {
            // 单次遍历：计算每个章节在字符串中的起止字符索引
            val charStarts = IntArray(chapters.size)
            val charEnds = IntArray(chapters.size)
            var bytePos = 0
            var charIdx = 0
            var chapterIdx = 0
            val sortedChapters = chapters.sortedBy { it.startOffset }
            // 记录每个章节的起始字符位置
            for ((i, ch) in sortedChapters.withIndex()) {
                while (bytePos < ch.startOffset && charIdx < content.length) {
                    bytePos += content[charIdx].toString().toByteArray(Charsets.UTF_8).size
                    charIdx++
                }
                charStarts[i] = charIdx
            }
            // 记录每个章节的结束字符位置
            bytePos = 0
            charIdx = 0
            for ((i, ch) in sortedChapters.withIndex()) {
                while (bytePos < ch.endOffset && charIdx < content.length) {
                    bytePos += content[charIdx].toString().toByteArray(Charsets.UTF_8).size
                    charIdx++
                }
                charEnds[i] = charIdx
            }
            // 提取章节文本并预解析段落 + lowercase
            sortedChapters.mapIndexed { i, chapter ->
                val start = charStarts[i]
                val end = minOf(charEnds[i], content.length)
                val text = if (start < end) content.substring(start, end) else ""
                val paragraphs = text.split("\n").filter { it.isNotBlank() }
                val lowerParagraphs = paragraphs.map { it.lowercase() }
                ParsedChapter(chapter.title, paragraphs, lowerParagraphs)
            }.also { parsedChapters = it }
        }
    }

    /**
     * 执行全文搜索（Dispatchers.Default，支持取消 + 进度反馈）
     * 优化：
     * 1. 使用预缓存的 lowercase 段落，零转换开销
     * 2. 每 50 个段落检查一次取消（减少 ensureActive 开销）
     * 3. 搜索模式与HTML版一致
     */
    fun performSearch(query: String, mode: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            _searchProgress.value = 0
            return
        }
        _isSearching.value = true
        _searchProgress.value = 0
        searchJob = viewModelScope.launch {
            try {
                val chapters = ensureParsedChapters()
                if (chapters.isEmpty()) {
                    _searchResults.value = emptyList()
                    return@launch
                }
                val keywordsLower = if (mode == "MULTI") {
                    query.split("\\s+".toRegex()).filter { it.isNotBlank() }.map { it.lowercase() }
                } else {
                    listOf(query.lowercase())
                }
                val queryLower = query.lowercase()
                val totalChapters = chapters.size
                var paraCount = 0

                val results = withContext(Dispatchers.Default) {
                    val acc = mutableListOf<SearchResult>()
                    for ((ci, chapter) in chapters.withIndex()) {
                        val paragraphs = chapter.paragraphs
                        val lowers = chapter.lowerParagraphs
                        for (idx in paragraphs.indices) {
                            // 每 50 个段落检查一次取消，减少开销
                            if (++paraCount % 50 == 0) ensureActive()
                            val paraLower = lowers[idx]
                            val matched = when (mode) {
                                "EXACT" -> paraLower.contains(queryLower)
                                "MULTI" -> keywordsLower.all { paraLower.contains(it) }
                                "FUZZY" -> fuzzyMatch(paraLower, queryLower)
                                else -> false
                            }
                            if (matched) {
                                val para = paragraphs[idx]
                                val pos = when (mode) {
                                    "EXACT", "MULTI" -> paraLower.indexOf(queryLower)
                                    "FUZZY" -> findFuzzyPosition(paraLower, queryLower)
                                    else -> -1
                                }
                                acc.add(SearchResult(chapter.title, para, idx, buildExcerpt(para, pos, query.length), pos))
                            }
                        }
                        // 每 5 个章节更新进度
                        if (ci % 5 == 0 || ci == totalChapters - 1) {
                            _searchProgress.value = (ci + 1) * 100 / totalChapters
                        }
                    }
                    acc
                }
                _searchResults.value = results
            } catch (_: Exception) {
                // 搜索被取消或异常，不更新结果
            } finally {
                _isSearching.value = false
                _searchProgress.value = 100
            }
        }
    }

    /** 模糊匹配：每个字符按顺序都能找到（大小写不敏感） */
    private fun fuzzyMatch(text: String, pattern: String): Boolean {
        var pi = 0
        for (c in text) {
            if (pi < pattern.length && c == pattern[pi]) pi++
        }
        return pi == pattern.length
    }

    /** 找到模糊匹配的起始位置 */
    private fun findFuzzyPosition(text: String, pattern: String): Int {
        var pi = 0
        for ((i, c) in text.withIndex()) {
            if (c == pattern[pi]) {
                if (pi == 0) return i
                pi++
            }
        }
        return -1
    }

    /** 生成匹配位置附近的摘录（前40字符 + 后60字符） */
    private fun buildExcerpt(text: String, pos: Int, keywordLen: Int): String {
        if (pos < 0) return text.take(100) + if (text.length > 100) "…" else ""
        val start = maxOf(0, pos - 40)
        val end = minOf(text.length, pos + keywordLen + 60)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end) + suffix
    }

    fun showSettings() {
        _isSettingsVisible.value = true
        _isMenuVisible.value = false
    }

    fun hideSettings() {
        _isSettingsVisible.value = false
    }

    fun updateSettings(newSettings: ReadingSettings) {
        val oldSettings = _settings.value
        _settings.value = newSettings
        // 只有设置真正改变时才保存
        if (oldSettings.fontSize != newSettings.fontSize ||
            oldSettings.lineHeight != newSettings.lineHeight ||
            oldSettings.letterSpacing != newSettings.letterSpacing ||
            oldSettings.paragraphSpacing != newSettings.paragraphSpacing ||
            oldSettings.backgroundColor != newSettings.backgroundColor ||
            oldSettings.textColor != newSettings.textColor ||
            oldSettings.isNightMode != newSettings.isNightMode ||
            oldSettings.pageMode != newSettings.pageMode
        ) {
            saveSettings(newSettings)
        }
    }

    /**
     * 更新章节内阅读位置（防抖，避免频繁写入）
     */
    private var lastSaveTime = 0L
    fun updateReadPosition(position: Int) {
        _lastReadPosition.value = position
        // 防抖：至少间隔1秒才保存一次
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSaveTime > 1000) {
            lastSaveTime = currentTime
            saveProgress()
        }
    }

    /**
     * 强制保存阅读位置（退出时调用）
     */
    fun forceSavePosition() {
        lastSaveTime = System.currentTimeMillis()
        saveProgress()
    }

    fun toggleNightMode() {
        val current = _settings.value
        val newSettings = current.copy(
            isNightMode = !current.isNightMode,
            backgroundColor = if (current.isNightMode) ReadingSettings.BG_WHITE else ReadingSettings.NIGHT_BG,
            textColor = if (current.isNightMode) 0xFF333333 else ReadingSettings.NIGHT_TEXT
        )
        _settings.value = newSettings
        saveSettings(newSettings)
    }

    private fun saveProgress() {
        viewModelScope.launch {
            val book = _currentBook.value ?: return@launch
            repository.updateBook(
                book.copy(
                    lastChapterIndex = _currentChapterIndex.value,
                    lastParagraphIndex = _lastReadPosition.value,
                    lastReadAt = System.currentTimeMillis()
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        forceSavePosition()
    }
}
