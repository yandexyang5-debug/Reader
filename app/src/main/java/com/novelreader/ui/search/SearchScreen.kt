package com.novelreader.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelreader.ui.reader.ReaderViewModel
import com.novelreader.ui.theme.DarkBackground
import com.novelreader.ui.theme.DarkCardBackground
import kotlinx.coroutines.delay

enum class SearchMode(val label: String) {
    EXACT("精确"),
    FUZZY("模糊"),
    MULTI("多词")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf(SearchMode.EXACT) }

    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    // 防抖：query变化后延迟300ms再触发搜索
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(300)
        debouncedQuery = query
    }

    // 当防抖后的查询词或搜索模式变化时触发搜索
    LaunchedEffect(debouncedQuery, selectedMode) {
        viewModel.performSearch(debouncedQuery, selectedMode.name)
    }

    // 高亮关键词列表
    val keywords = debouncedQuery.split("\\s+".toRegex()).filter { it.isNotBlank() }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("全文搜索", color = MaterialTheme.colorScheme.onPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 搜索框
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("输入搜索关键词", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "清空",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        // 触发即时搜索
                        debouncedQuery = query
                    }
                ),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // 搜索模式选择
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchMode.entries.forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode },
                        label = { Text(mode.label, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = DarkCardBackground,
                            labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    )
                }
            }

            // 搜索结果统计和加载状态
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "正在搜索...",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                } else if (debouncedQuery.isNotBlank()) {
                    Text(
                        text = "找到 ${searchResults.size} 条结果",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // 搜索结果列表
            if (debouncedQuery.isNotBlank() && !isSearching) {
                if (searchResults.isEmpty()) {
                    // 空状态
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "未找到相关结果",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = searchResults,
                            key = { index, item -> "${item.chapterTitle}_${item.paragraphIndex}_$index" }
                        ) { _, result ->
                            SearchResultCard(
                                chapterTitle = result.chapterTitle,
                                paragraphText = result.paragraphText,
                                keywords = keywords,
                                onClick = {
                                    viewModel.goToSearchResult(result.chapterTitle, result.paragraphIndex)
                                    onBack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    chapterTitle: String,
    paragraphText: String,
    keywords: List<String>,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = DarkCardBackground
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 章节标题
            Text(
                text = chapterTitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 匹配段落（高亮关键词）
            Text(
                text = buildAnnotatedString {
                    if (keywords.isEmpty()) {
                        append(paragraphText)
                    } else {
                        var lastIndex = 0
                        val lowerText = paragraphText.lowercase()
                        val lowerKeywords = keywords.map { it.lowercase() }

                        while (lastIndex < paragraphText.length) {
                            var earliestIndex = Int.MAX_VALUE
                            var matchedKeyword = ""

                            for ((i, lowerKeyword) in lowerKeywords.withIndex()) {
                                val index = lowerText.indexOf(lowerKeyword, lastIndex)
                                if (index != -1 && index < earliestIndex) {
                                    earliestIndex = index
                                    matchedKeyword = keywords[i] // 用原始大小写
                                }
                            }

                            if (earliestIndex == Int.MAX_VALUE) {
                                // 没有更多匹配
                                append(paragraphText.substring(lastIndex))
                                break
                            }

                            // 匹配前的文本
                            if (earliestIndex > lastIndex) {
                                append(paragraphText.substring(lastIndex, earliestIndex))
                            }

                            // 高亮匹配的文本
                            withStyle(
                                SpanStyle(
                                    background = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                append(paragraphText.substring(earliestIndex, earliestIndex + matchedKeyword.length))
                            }

                            lastIndex = earliestIndex + matchedKeyword.length
                        }
                    }
                },
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        }
    }
}
