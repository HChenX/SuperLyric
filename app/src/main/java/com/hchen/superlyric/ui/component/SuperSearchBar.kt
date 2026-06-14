package com.hchen.superlyric.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.zIndex
import com.hchen.superlyric.R
import com.hchen.superlyric.ui.effect.BlurredBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

// Search Status Class
@Stable
class SearchStatus(val label: String) {
    var searchText by mutableStateOf("")
    var current by mutableStateOf(Status.COLLAPSED)

    var offsetY by mutableStateOf(0.dp)
    var resultStatus by mutableStateOf(ResultStatus.DEFAULT)

    fun isExpand() = current == Status.EXPANDED
    fun isCollapsed() = current == Status.COLLAPSED
    fun isExpandStage() = current == Status.EXPANDING || current == Status.EXPANDED
    fun isCollapseStage() = current == Status.COLLAPSING || current == Status.COLLAPSED

    fun onAnimationComplete() {
        current = when (current) {
            Status.EXPANDING -> Status.EXPANDED
            Status.COLLAPSING -> {
                searchText = ""
                Status.COLLAPSED
            }

            else -> current
        }
    }

    enum class Status { EXPANDED, EXPANDING, COLLAPSED, COLLAPSING }
    enum class ResultStatus { DEFAULT, EMPTY, LOAD, SHOW }
}

@Composable
fun SearchStatus.TopAppBarAnim(
    modifier: Modifier = Modifier,
    visible: Boolean = isCollapseStage(),
    content: @Composable () -> Unit
) {
    val topAppBarAlpha = animateFloatAsState(
        if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 550 else 0, easing = FastOutSlowInEasing),
    )
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(colorScheme.surface)
        )
        Box(
            modifier = Modifier
                .alpha(topAppBarAlpha.value)
        ) { content() }
    }
}

// Search Box Composable
@Composable
fun SearchStatus.SearchBox(
    backdrop: LayerBackdrop?,
    collapseBar: @Composable (SearchStatus, Boolean, Dp) -> Unit = { searchStatus, blurActive, topPadding ->
        SearchBarFake(searchStatus.label, blurActive, topPadding)
    },
    searchBarTopPadding: Dp = 12.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable (MutableState<Dp>) -> Unit
) {
    val searchStatus = this
    val density = LocalDensity.current

    val offsetY = remember { mutableIntStateOf(0) }
    val boxHeight = remember { mutableStateOf(0.dp) }

    val blurActive = backdrop != null

    BlurredBar(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(10f)
            .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
            .offset(y = contentPadding.calculateTopPadding())
            .onGloballyPositioned {
                it.positionInWindow().y.apply {
                    offsetY.intValue = (this@apply * 0.9).toInt()
                    with(density) {
                        searchStatus.offsetY = this@apply.toDp()
                        boxHeight.value = it.size.height.toDp()
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { searchStatus.current = SearchStatus.Status.EXPANDING }
            },
        backdrop = backdrop,
        blurEnabled = blurActive
    ) {
        collapseBar(searchStatus, blurActive, searchBarTopPadding)
    }
    Box {
        AnimatedVisibility(
            visible = searchStatus.isCollapseStage(),
            enter = fadeIn(tween(300, easing = LinearOutSlowInEasing)) + slideInVertically(
                tween(
                    300,
                    easing = LinearOutSlowInEasing
                )
            ) { -offsetY.intValue },
            exit = fadeOut(tween(300, easing = LinearOutSlowInEasing)) + slideOutVertically(
                tween(
                    300,
                    easing = LinearOutSlowInEasing
                )
            ) { -offsetY.intValue }
        ) {
            content(boxHeight)
        }
    }
}

// Search Pager Composable
@Composable
fun SearchStatus.SearchPager(
    defaultResult: @Composable () -> Unit,
    expandBar: @Composable (SearchStatus, Dp) -> Unit = { searchStatus, padding ->
        SearchBar(searchStatus, padding)
    },
    searchBarTopPadding: Dp = 12.dp,
    enableRefresh: Boolean = false,
    onRefreshing: suspend () -> Unit = {},
    result: LazyListScope.() -> Unit
) {
    val searchStatus = this
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val topPadding by animateDpAsState(
        targetValue = if (searchStatus.isExpandStage()) {
            systemBarsPadding + 5.dp
        } else {
            max(searchStatus.offsetY, 0.dp)
        },
        animationSpec = tween(300, easing = LinearOutSlowInEasing)
    ) {
        searchStatus.onAnimationComplete()
    }

    var isRefreshing by rememberSaveable { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            onRefreshing()
            isRefreshing = false
        }
    }

    val lazyColumn: @Composable () -> Unit = {
        if (enableRefresh) {
            PullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                },
                pullToRefreshState = pullToRefreshState,
                refreshTexts = listOf(
                    stringResource(R.string.pull_down_to_refresh),
                    stringResource(R.string.release_to_refresh),
                    stringResource(R.string.refreshing),
                    stringResource(R.string.refresh_successfully)
                )
            ) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .background(colorScheme.surface),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    result()
                }
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .background(colorScheme.surface),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                result()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .then(
                if (!searchStatus.isCollapsed()) Modifier.pointerInput(Unit) { } else Modifier
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(10f)
                .height(topPadding)
                .background(if (searchStatus.isExpandStage()) colorScheme.surface else Color.Transparent),
        ) {}
        Row(
            Modifier
                .fillMaxWidth()
                .zIndex(10f)
                .background(if (!searchStatus.isCollapsed()) colorScheme.surface else Color.Transparent),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!searchStatus.isCollapsed()) {
                Box(
                    modifier = Modifier.weight(1f)
                ) {
                    expandBar(searchStatus, searchBarTopPadding)
                }

                AnimatedVisibility(
                    visible = searchStatus.isExpandStage(),
                    enter = expandHorizontally() + slideInHorizontally(initialOffsetX = { it }),
                    exit = shrinkHorizontally() + slideOutHorizontally(targetOffsetX = { it })
                ) {
                    Text(
                        text = stringResource(android.R.string.cancel),
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 4.dp, end = 16.dp, top = searchBarTopPadding, bottom = 12.dp)
                            .clickable(
                                interactionSource = null,
                                enabled = searchStatus.isExpand(),
                                indication = null
                            ) { searchStatus.current = SearchStatus.Status.COLLAPSING },
                        textAlign = TextAlign.Start
                    )
                    BackHandler(enabled = true) {
                        searchStatus.current = SearchStatus.Status.COLLAPSING
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = searchStatus.isExpand(),
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            when (searchStatus.resultStatus) {
                SearchStatus.ResultStatus.DEFAULT -> defaultResult()
                SearchStatus.ResultStatus.EMPTY -> {}
                SearchStatus.ResultStatus.LOAD -> {}
                SearchStatus.ResultStatus.SHOW -> {
                    lazyColumn()
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    searchStatus: SearchStatus,
    searchBarTopPadding: Dp = 12.dp,
) {
    val focusRequester = remember { FocusRequester() }
    var expanded by rememberSaveable { mutableStateOf(false) }

    InputField(
        query = searchStatus.searchText,
        onQueryChange = { searchStatus.searchText = it },
        label = "",
        leadingIcon = {
            Icon(
                imageVector = MiuixIcons.Basic.Search,
                contentDescription = "back",
                modifier = Modifier
                    .size(44.dp)
                    .padding(start = 16.dp, end = 8.dp),
                tint = colorScheme.onSurfaceContainerHigh,
            )
        },
        trailingIcon = {
            AnimatedVisibility(
                searchStatus.searchText.isNotEmpty(),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                Icon(
                    imageVector = MiuixIcons.Basic.SearchCleanup,
                    tint = colorScheme.onSurface,
                    contentDescription = "Clean",
                    modifier = Modifier
                        .size(44.dp)
                        .padding(start = 8.dp, end = 16.dp)
                        .clickable(
                            interactionSource = null,
                            indication = null
                        ) {
                            searchStatus.searchText = ""
                        },
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = searchBarTopPadding, bottom = 8.dp)
            .focusRequester(focusRequester)
            .background(Color.Transparent),
        onSearch = { it },
        expanded = searchStatus.isExpandStage(),
        onExpandedChange = {
            searchStatus.current = if (it) SearchStatus.Status.EXPANDED else SearchStatus.Status.COLLAPSED
        }
    )
    LaunchedEffect(Unit) {
        if (!expanded && searchStatus.isExpandStage()) {
            focusRequester.requestFocus()
            expanded = true
        }
    }
}

@Composable
private fun SearchBarFake(
    label: String,
    blurActive: Boolean,
    searchBarTopPadding: Dp = 12.dp
) {
    InputField(
        query = "",
        onQueryChange = { },
        label = label,
        leadingIcon = {
            Icon(
                imageVector = MiuixIcons.Basic.Search,
                contentDescription = "Clean",
                modifier = Modifier
                    .size(44.dp)
                    .padding(start = 16.dp, end = 8.dp),
                tint = colorScheme.onSurfaceContainerHigh,
            )
        },
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(top = searchBarTopPadding, bottom = 8.dp)
            .background(if (blurActive) Color.Transparent else colorScheme.surface),
        onSearch = { },
        enabled = false,
        expanded = false,
        onExpandedChange = { }
    )
}
