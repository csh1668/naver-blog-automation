package com.csh.blogwriter.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.os.Bundle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.hilt.lifecycle.viewmodel.compose.rememberHiltViewModelFactory
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.defaultViewModelCreationExtras
import androidx.lifecycle.defaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.data.repo.MessageKind
import com.csh.blogwriter.data.repo.MessageRole
import com.csh.blogwriter.domain.publish.PublishState
import com.csh.blogwriter.ui.chat.components.Composer
import com.csh.blogwriter.ui.chat.components.MessageBubble
import com.csh.blogwriter.ui.chat.components.PhotosBubble
import com.csh.blogwriter.ui.chat.components.PlanCard
import com.csh.blogwriter.ui.chat.components.QuickReplyChips
import com.csh.blogwriter.ui.chat.components.SessionListPane
import com.csh.blogwriter.ui.chat.components.SessionListWidth
import com.csh.blogwriter.ui.chat.components.SessionRailWidth
import com.csh.blogwriter.ui.chat.components.SystemMessage
import com.csh.blogwriter.ui.chat.components.ToolStatusLine
import com.csh.blogwriter.ui.components.BannerKind
import com.csh.blogwriter.ui.components.InlineBanner
import com.csh.blogwriter.ui.components.WeakButton
import com.csh.blogwriter.ui.publish.PublishPanel
import com.csh.blogwriter.ui.publish.PublishViewModel
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme
import kotlinx.coroutines.launch

/** 가로 3단(대화 기록·채팅·에디터 패널)로 나눌 수 있는 최소 폭. */
private val WIDE_MIN = 840.dp
private val PANEL_MIN = 520.dp

@Composable
fun ChatScreen(
    sessionId: String?,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onSessionExpired: (jobId: String) -> Unit,
    onFailed: (jobId: String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId) { viewModel.open(sessionId) }

    BoxWithConstraints(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        val wide = maxWidth >= WIDE_MIN
        val panelWidth = maxOf(PANEL_MIN, maxWidth / 2)
        val panelVisible = ui.panelOpen && ui.panelJobId != null

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(if (ui.listCollapsed) SessionRailWidth else SessionListWidth).fillMaxHeight().statusBarsPadding()) {
                    SessionListPane(
                        sessions = sessions,
                        currentId = ui.session?.id,
                        collapsed = ui.listCollapsed,
                        onSelect = viewModel::open,
                        onNew = { viewModel.open(null) },
                        onToggle = viewModel::toggleList,
                    )
                }
                ChatPane(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    ui = ui,
                    viewModel = viewModel,
                    onOpenMemory = onOpenMemory,
                    onBack = onBack,
                    onOpenSessions = null,
                )
                AnimatedVisibility(
                    visible = panelVisible,
                    enter = slideInHorizontally(tween(200)) { it },
                    exit = slideOutHorizontally(tween(200)) { it },
                ) {
                    Box(Modifier.width(panelWidth).fillMaxHeight()) {
                        PanelHost(ui.panelJobId, viewModel, onSessionExpired, onFailed)
                    }
                }
            }
        } else {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(Modifier.width(SessionListWidth)) {
                        SessionListPane(
                            sessions = sessions,
                            currentId = ui.session?.id,
                            collapsed = false,
                            onSelect = { id -> viewModel.open(id); scope.launch { drawerState.close() } },
                            onNew = { viewModel.open(null); scope.launch { drawerState.close() } },
                            onToggle = { scope.launch { drawerState.close() } },
                        )
                    }
                },
            ) {
                ChatPane(
                    modifier = Modifier.fillMaxSize(),
                    ui = ui,
                    viewModel = viewModel,
                    onOpenMemory = onOpenMemory,
                    onBack = onBack,
                    onOpenSessions = { scope.launch { drawerState.open() } },
                )
            }
            if (panelVisible) {
                BackHandler { viewModel.togglePanel() }
                Column(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = viewModel::togglePanel, modifier = Modifier.size(AppSpacing.touchTarget)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "채팅으로", tint = AppTheme.colors.textPrimary)
                        }
                        Text("채팅으로", style = AppTheme.typography.body1, color = AppTheme.colors.textPrimary)
                    }
                    Box(Modifier.weight(1f)) { PanelHost(ui.panelJobId, viewModel, onSessionExpired, onFailed) }
                }
            }
        }
    }
}

/** 가운데 채팅: 상단 바 + 말풍선 목록 + 빠른 답장 + 입력줄. */
@Composable
private fun ChatPane(
    modifier: Modifier,
    ui: ChatUiState,
    viewModel: ChatViewModel,
    onOpenMemory: () -> Unit,
    onBack: () -> Unit,
    onOpenSessions: (() -> Unit)?,
) {
    val c = AppTheme.colors
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val itemCount = ui.messages.size + (if (ui.streamingSay != null) 1 else 0) + (if (ui.thinking) 1 else 0)
    LaunchedEffect(itemCount, ui.streamingSay) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(modifier.statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            if (onOpenSessions != null) {
                IconButton(onClick = onOpenSessions, modifier = Modifier.size(AppSpacing.touchTarget)) {
                    Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "대화 기록", tint = c.textSecondary)
                }
            } else {
                IconButton(onClick = onBack, modifier = Modifier.size(AppSpacing.touchTarget)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로 가기", tint = c.textSecondary)
                }
            }
            Text(
                ui.session?.title ?: "새 글",
                style = AppTheme.typography.title3, color = c.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = AppSpacing.sm),
            )
            TextButton(onClick = onOpenMemory) { Text("기억한 것들", style = AppTheme.typography.body2, color = c.fillBrand) }
            if (ui.panelJobId != null) {
                IconButton(onClick = viewModel::togglePanel, modifier = Modifier.size(AppSpacing.touchTarget)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Article,
                        contentDescription = if (ui.panelOpen) "초안 접기" else "초안 열기",
                        tint = if (ui.panelOpen) c.fillBrand else c.textSecondary,
                    )
                }
            }
        }
        if (!ui.hasKey) {
            Box(Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)) {
                InlineBanner(ChatViewModel.NO_KEY, BannerKind.Warning)
            }
        }
        ui.error?.let { message ->
            Box(Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)) {
                InlineBanner(message, BannerKind.Danger, onClick = viewModel::clearError)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            items(ui.messages, key = { it.id }) { message -> MessageItem(message, ui.panelOpen, viewModel) }
            ui.streamingSay?.let { partial -> item { MessageBubble(partial, mine = false) } }
            if (ui.thinking) item { ToolStatusLine(ui.toolStatus) }
        }
        QuickReplyChips(ui.quickReplies) { viewModel.sendQuickReply(it) }
        Composer(
            text = draft,
            onTextChange = { draft = it },
            onSend = { viewModel.send(draft); draft = "" },
            onAttach = viewModel::attachPhotos,
            enabled = ui.hasKey && !ui.thinking,
            placeholder = if (ui.thinking) "글을 구상하고 있어요" else "오늘 있었던 일을 들려주세요",
        )
    }
}

@Composable
private fun MessageItem(message: ChatMessage, panelOpen: Boolean, viewModel: ChatViewModel) {
    when (message.kind) {
        MessageKind.TEXT -> MessageBubble(ChatPayloads.readText(message.payloadJson), mine = message.role == MessageRole.USER)
        MessageKind.PHOTOS -> ChatPayloads.readPhotos(message.payloadJson)?.let { PhotosBubble(it.uris) }
        MessageKind.PLAN -> ChatPayloads.readPlan(message.payloadJson)?.let { plan ->
            PlanCard(plan) { index, title -> viewModel.sendQuickReply("${index}번 제목으로: $title") }
        }
        MessageKind.POST -> SystemMessage {
            Column {
                InlineBanner("초안을 만들었어요", BannerKind.Success)
                if (!panelOpen) {
                    Spacer(Modifier.height(AppSpacing.sm))
                    WeakButton("초안 열기", onClick = viewModel::togglePanel)
                }
            }
        }
        MessageKind.SYSTEM -> SystemMessage { InlineBanner(ChatPayloads.readText(message.payloadJson), BannerKind.Info) }
    }
}

/** 오른쪽 에디터 패널. 발행 작업마다 따로 살아 있는 [PublishViewModel] 을 붙인다. */
@Composable
private fun PanelHost(
    jobId: String?,
    chatViewModel: ChatViewModel,
    onSessionExpired: (jobId: String) -> Unit,
    onFailed: (jobId: String) -> Unit,
) {
    if (jobId == null) return
    val publishViewModel = rememberPublishViewModel(jobId)
    val publishUi by publishViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(publishViewModel) {
        chatViewModel.reinject.collect { publishViewModel.reinject(it) }
    }
    LaunchedEffect(publishUi.state) {
        (publishUi.state as? PublishState.Published)?.let { chatViewModel.onPublished(it.url) }
    }
    PublishPanel(
        viewModel = publishViewModel,
        modifier = Modifier.fillMaxSize(),
        onDone = chatViewModel::togglePanel,
        onSessionExpired = onSessionExpired,
        onFailed = onFailed,
        onCancelRequest = chatViewModel::togglePanel,
    )
}

/**
 * PublishViewModel 은 jobId 를 SavedStateHandle 로 받는다 — 화면 경로가 아니라 채팅 안에서 열리므로
 * 기본 인자를 직접 실어 준다. key 를 jobId 로 두어 작업마다 별도 인스턴스를 갖는다.
 */
@Composable
private fun rememberPublishViewModel(jobId: String): PublishViewModel {
    val owner = checkNotNull(LocalViewModelStoreOwner.current) { "ViewModelStoreOwner 가 없어요" }
    val factory = rememberHiltViewModelFactory(owner.defaultViewModelProviderFactory)
    val extras = remember(owner, jobId) {
        MutableCreationExtras(owner.defaultViewModelCreationExtras).apply {
            set(DEFAULT_ARGS_KEY, Bundle().apply { putString("jobId", jobId) })
        }
    }
    return viewModel(owner, key = jobId, factory = factory, extras = extras)
}
