package com.csh.blogwriter.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalUriHandler
import com.csh.blogwriter.data.repo.SessionStatus
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
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
import com.csh.blogwriter.chat.AttachedPhoto
import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.data.repo.MessageKind
import com.csh.blogwriter.data.repo.MessageRole
import com.csh.blogwriter.domain.publish.PublishState
import com.csh.blogwriter.ui.chat.components.Composer
import com.csh.blogwriter.ui.chat.components.MessageBubble
import com.csh.blogwriter.ui.chat.components.PhotosBubble
import com.csh.blogwriter.ui.chat.components.PlanPanel
import com.csh.blogwriter.ui.chat.components.QuickReplyChips
import com.csh.blogwriter.ui.chat.components.SessionListPane
import com.csh.blogwriter.ui.chat.components.SessionListWidth
import com.csh.blogwriter.ui.chat.components.SessionRailWidth
import com.csh.blogwriter.ui.chat.components.SystemMessage
import com.csh.blogwriter.ui.chat.components.ToolStatusLine
import com.csh.blogwriter.ui.components.BannerKind
import com.csh.blogwriter.ui.components.BottomCta
import com.csh.blogwriter.ui.components.InlineBanner
import com.csh.blogwriter.ui.components.PhotoGrid
import com.csh.blogwriter.ui.components.WeakButton
import com.csh.blogwriter.ui.publish.PublishPanel
import com.csh.blogwriter.ui.publish.PublishViewModel
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme
import kotlinx.coroutines.launch

/** 가로 3단(대화 기록·채팅·에디터 패널)로 나눌 수 있는 최소 폭. */
private val WIDE_MIN = 840.dp
private val PANEL_MIN = 520.dp
private val TRAY_MAX_HEIGHT = 240.dp

@Composable
fun ChatScreen(
    sessionId: String?,
    onOpenMemory: () -> Unit,
    onAdmin: () -> Unit,
    onSessionExpired: (jobId: String) -> Unit,
    onFailed: (jobId: String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    // 회전으로 화면이 다시 만들어져도 보던 대화를 그대로 둔다 — 뷰모델이 첫 진입만 받는다.
    LaunchedEffect(sessionId) { viewModel.openInitial(sessionId) }

    // 패널을 한 번 연 뒤에는 접어도 컴포지션에 남겨 둔다 — 빠지면 WebView 가 파괴돼 처음부터 다시 올려야 한다.
    // 대화를 바꾸면 처음부터 다시 센다: 안 그러면 이어 쓰던 글이 있는 대화를 열자마자
    // 접힌 채로 에디터를 띄우고 사진까지 올리기 시작한다.
    val openSessionId = ui.session?.id
    var panelMounted by remember(openSessionId) { mutableStateOf(false) }
    var panelStatus by remember(openSessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(openSessionId, ui.panelOpen, ui.panelJobId) {
        if (ui.panelOpen) panelMounted = true
        if (ui.panelJobId == null) { panelMounted = false; panelStatus = null }
    }
    // 오른쪽 자리는 초안이 있으면 에디터가, 아직 계획뿐이면 계획이 차지한다.
    val showEditor = panelMounted && ui.panelJobId != null
    val planMarkdown = if (showEditor) null else ui.plan

    // 입력창에 커서가 있는 동안에는 채팅 쪽을 넓혀 준다 — 오른쪽 패널이 말할 자리를 덜 뺏도록.
    var composerFocused by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        val screenWidth = maxWidth
        val wide = screenWidth >= WIDE_MIN
        // 채팅 : 에디터 = 3 : 7 (사용자 결정). 입력창에 커서가 있거나 대화 목록을 펼쳐 두면 5 : 5 로 벌린다.
        val chatNeedsRoom = composerFocused || !ui.listCollapsed
        // 에디터는 최소 PANEL_MIN.
        val targetPanelWidth = maxOf(PANEL_MIN, screenWidth * (if (chatNeedsRoom) 0.5f else 0.7f))
        val panelMountedNow = showEditor || planMarkdown != null
        // 안쪽(내용)과 바깥쪽(보이는 폭)을 같은 스펙으로 따로 움직인다 —
        // 접을 때는 바깥만 0 으로 줄어 WebView 는 제 폭 그대로 살아 있는다.
        val panelWidth by animateDpAsState(targetPanelWidth, tween(200), label = "panelContentWidth")
        // 에디터 페이지는 화면 전체 폭 기준 PC 레이아웃 — 패널 비율만큼 축소해 가로 스크롤을 없앤다.
        val editorScalePercent = (targetPanelWidth.value / screenWidth.value * 100f).roundToInt().coerceIn(30, 100)
        val shownPanelWidth by animateDpAsState(
            targetValue = if (ui.panelOpen) (if (wide) targetPanelWidth else screenWidth) else 0.dp,
            animationSpec = tween(200), label = "panelWidth",
        )

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(if (ui.listCollapsed) SessionRailWidth else SessionListWidth).fillMaxHeight().statusBarsPadding().clearFocusOnPress()) {
                    SessionListPane(
                        sessions = sessions,
                        currentId = ui.session?.id,
                        collapsed = ui.listCollapsed,
                        onSelect = viewModel::open,
                        onNew = { viewModel.open(null) },
                        onToggle = viewModel::toggleList,
                        onDelete = viewModel::deleteSession,
                        onRename = viewModel::renameSession,
                        onSettings = onAdmin,
                    )
                }
                ChatPane(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    ui = ui,
                    viewModel = viewModel,
                    panelStatus = panelStatus,
                    onOpenMemory = onOpenMemory,
                    onOpenSessions = null,
                    onComposerFocusChanged = { composerFocused = it },
                )
                if (panelMountedNow) {
                    // 접을 때는 폭만 0 으로 줄인다 — 컴포지션에 남아 있어야 WebView 와 편집 내용이 살아남는다.
                    Box(Modifier.width(shownPanelWidth).fillMaxHeight().clipToBounds().clearFocusOnPress()) {
                        Box(Modifier.requiredWidth(panelWidth).fillMaxHeight()) {
                            if (planMarkdown != null) PlanPanel(planMarkdown, onSave = viewModel::savePlanEdit)
                            else PanelHost(ui.panelJobId, viewModel, onSessionExpired, onFailed, editorScalePercent) { panelStatus = it }
                        }
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
                            onDelete = viewModel::deleteSession,
                            onRename = viewModel::renameSession,
                            onSettings = { scope.launch { drawerState.close() }; onAdmin() },
                        )
                    }
                },
            ) {
                ChatPane(
                    modifier = Modifier.fillMaxSize(),
                    ui = ui,
                    viewModel = viewModel,
                    panelStatus = panelStatus,
                    onOpenMemory = onOpenMemory,
                    onOpenSessions = { scope.launch { drawerState.open() } },
                    onComposerFocusChanged = { composerFocused = it },
                )
            }
            if (panelMountedNow) {
                if (ui.panelOpen) BackHandler { viewModel.togglePanel() }
                // 세로에서도 접힌 패널은 폭 0 으로만 줄인다 (WebView 를 살려 두려고).
                Box(Modifier.align(Alignment.CenterEnd).width(shownPanelWidth).fillMaxHeight().clipToBounds()) {
                    Column(Modifier.requiredWidth(screenWidth).fillMaxHeight().background(AppTheme.colors.background)) {
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = viewModel::togglePanel, modifier = Modifier.size(AppSpacing.touchTarget)) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "채팅으로", tint = AppTheme.colors.textPrimary)
                            }
                            Text("채팅으로", style = AppTheme.typography.body1, color = AppTheme.colors.textPrimary)
                        }
                        Box(Modifier.weight(1f)) {
                            if (planMarkdown != null) PlanPanel(planMarkdown, onSave = viewModel::savePlanEdit)
                            else PanelHost(ui.panelJobId, viewModel, onSessionExpired, onFailed, 100) { panelStatus = it }
                        }
                    }
                }
            }
        }
    }
}

/** 가운데 채팅: 상단 바 + 말풍선 목록 + 빠른 답장 + 입력줄. 아직 아무 말도 안 했으면 큰 제목 + 가운데 입력창. */
@Composable
private fun ChatPane(
    modifier: Modifier,
    ui: ChatUiState,
    viewModel: ChatViewModel,
    panelStatus: String?,
    onOpenMemory: () -> Unit,
    onOpenSessions: (() -> Unit)?,
    onComposerFocusChanged: (Boolean) -> Unit,
) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    /** 방금 보냈으면 읽던 자리와 상관없이 맨 아래로 따라간다. */
    var justSent by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val itemCount = ui.messages.size + (if (ui.streamingSay != null) 1 else 0) + (if (ui.thinking) 1 else 0)
    LaunchedEffect(itemCount, ui.streamingSay) {
        if (itemCount == 0) return@LaunchedEffect
        // 위쪽 지난 대화를 읽는 중이면 끌어내리지 않는다.
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: (itemCount - 1)
        if (justSent || lastVisible >= itemCount - 2) {
            listState.animateScrollToItem(itemCount - 1)
            justSent = false
        }
    }

    Column(modifier.statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            // 좁은 화면에서만 대화 기록 버튼이 필요하다 — 넓으면 왼쪽에 늘 붙어 있다.
            if (onOpenSessions != null) {
                IconButton(onClick = onOpenSessions, modifier = Modifier.size(AppSpacing.touchTarget)) {
                    Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "대화 기록", tint = c.textSecondary)
                }
            }
            Text(
                ui.session?.title ?: "새 글",
                style = AppTheme.typography.title3, color = c.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = AppSpacing.sm),
            )
            TextButton(onClick = onOpenMemory) { Text("기억한 것들", style = AppTheme.typography.body2, color = c.fillBrand) }
            if (ui.hasPanel) {
                val what = if (ui.panelJobId != null) "초안" else "계획"
                IconButton(onClick = viewModel::togglePanel, modifier = Modifier.size(AppSpacing.touchTarget)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Article,
                        contentDescription = if (ui.panelOpen) "$what 접기" else "$what 열기",
                        tint = if (ui.panelOpen) c.fillBrand else c.textSecondary,
                    )
                }
            }
        }
        // 새 버전 알림 (FR-12). 닫으면 그 태그는 다시 뜨지 않는다.
        updateInfo?.let { info ->
            Column(Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)) {
                InlineBanner("새 버전(${info.tag})이 나왔어요 — 받으러 가기", BannerKind.Info) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, info.htmlUrl.toUri()))
                }
                Spacer(Modifier.height(AppSpacing.sm))
                WeakButton("닫기", onClick = viewModel::dismissUpdate)
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
        // 패널을 접어 둔 동안에도 초안이 어디까지 갔는지 여기서 보인다 (디자인 가이드 §8).
        if (ui.panelJobId != null && !ui.panelOpen && panelStatus != null) {
            Box(Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)) {
                InlineBanner("$panelStatus — 열기", BannerKind.Info, onClick = viewModel::togglePanel)
            }
        }
        val composer: @Composable (Boolean) -> Unit = { hero ->
            Composer(
                text = draft,
                onTextChange = { draft = it },
                onSend = { justSent = true; viewModel.send(draft); draft = "" },
                onAttach = viewModel::attachPhotos,
                enabled = ui.hasKey && !ui.thinking,
                placeholder = if (ui.thinking) "글을 구상하고 있어요" else "오늘 있었던 일을 들려주세요",
                canAttach = ui.panelJobId == null,
                hero = hero,
                onFocusChanged = onComposerFocusChanged,
            )
        }
        // 아직 말이 오가지 않은 새 글(사진만 붙여 둔 것도 포함) — 목록 대신 큰 제목과 가운데 입력창을 보여 준다.
        if (!ui.thinking && ui.messages.none { it.kind != MessageKind.PHOTOS }) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = AppSpacing.contentMaxWidth).padding(vertical = AppSpacing.lg)) {
                    Text(
                        "오늘은 어떤 이야기를 올릴까요?",
                        style = AppTheme.typography.display, color = c.textPrimary,
                        textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg),
                    )
                    Spacer(Modifier.height(AppSpacing.section))
                    AttachmentTray(ui.tray, viewModel)
                    composer(true)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.clearFocusOnPress().weight(1f).fillMaxWidth().padding(horizontal = AppSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            ) {
                items(ui.messages, key = { it.id }) { message -> MessageItem(message, ui.panelOpen, viewModel) }
                ui.streamingSay?.let { partial -> item { MessageBubble(partial, mine = false) } }
                if (ui.thinking) item { ToolStatusLine(ui.toolStatus) }
            }
            ui.draftGate?.let { gate -> DraftGateCard(gate, viewModel) }
            QuickReplyChips(ui.quickReplies) { justSent = true; viewModel.sendQuickReply(it) }
            AttachmentTray(ui.tray, viewModel)
            // 계획이 있고 아직 초안이 없는 동안에는 초안 버튼이 입력창 위에 늘 걸려 있다.
            val published = ui.session?.status == SessionStatus.PUBLISHED
            if (ui.plan != null && ui.panelJobId == null && !ui.thinking && ui.draftGate == null && !published) {
                Box(Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)) {
                    BottomCta(
                        ChatViewModel.DRAFT_CHIP,
                        onClick = { justSent = true; viewModel.requestDraft() },
                        enabled = ui.hasKey,
                    )
                }
            }
            // 초안이 나온 뒤 붙인 사진은 에디터에 올라가 있지 않아 다음 수정본 주입이 깨진다 — 버튼을 막고 이유를 알려 준다.
            if (ui.panelJobId != null) {
                Text(
                    ChatViewModel.NO_PHOTO_AFTER_DRAFT,
                    style = AppTheme.typography.caption,
                    color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = AppSpacing.lg),
                )
            }
            composer(false)
        }
    }
}

/** 글자 수나 사진 쓰임이 걸린 초안을 에디터에 넣기 전에 한 번 물어보는 카드. */
@Composable
private fun DraftGateCard(gate: DraftGate, viewModel: ChatViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)) {
        InlineBanner(ChatViewModel.GATE_TITLE, BannerKind.Warning)
        gate.issues.forEach { issue ->
            Text(
                issue,
                style = AppTheme.typography.body2,
                color = AppTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
            )
        }
        Spacer(Modifier.height(AppSpacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Box(Modifier.weight(1f)) { WeakButton(ChatViewModel.GATE_FIX, onClick = viewModel::fixDraftGate) }
            Box(Modifier.weight(1f)) { BottomCta(ChatViewModel.GATE_ACCEPT, onClick = viewModel::acceptDraftGate) }
        }
    }
}

/** 입력창 위에 걸리는 사진판. 보내기 전에 빼거나 순서를 바꿀 수 있다. */
@Composable
private fun AttachmentTray(photos: List<AttachedPhoto>, viewModel: ChatViewModel) {
    if (photos.isEmpty()) return
    val uris = remember(photos) { photos.map { Uri.parse(it.uri) } }
    Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)) {
        Text("붙인 사진 ${photos.size}장", style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
        Spacer(Modifier.height(AppSpacing.sm))
        // 많이 고르면 사진판이 화면을 다 먹는다 — 높이를 묶고 그 안에서 넘긴다.
        Box(Modifier.heightIn(max = TRAY_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
            PhotoGrid(
                uris = uris,
                // 위치는 사진판 기준으로 그대로 넘긴다 (뷰모델이 안 보낸 사진 시작점을 더한다).
                onRemove = { uri -> photos.firstOrNull { it.uri == uri.toString() }?.let { viewModel.removePhoto(it.ref) } },
                onMove = { from, to -> viewModel.movePhoto(from, to) },
                columns = 5,
            )
        }
    }
}

/** 접힌 패널 배너에 쓸 한 줄. 발행이 끝나면(=패널이 사라지면) null. */
private fun panelStatusText(state: PublishState): String? = when (state) {
    is PublishState.Idle, is PublishState.PreparingImages -> "사진을 준비하고 있어요"
    is PublishState.LoadingEditor, is PublishState.DismissingPopups -> "네이버 글쓰기 화면을 여는 중이에요"
    is PublishState.UploadingImages -> "사진을 올리고 있어요 ${state.total}장 중 ${state.done}장"
    is PublishState.Injecting -> "초안을 넣고 있어요"
    is PublishState.Reviewing -> "초안이 준비됐어요"
    is PublishState.SessionExpired -> "네이버에 다시 로그인해 주세요"
    is PublishState.Failed -> "초안을 넣다가 문제가 생겼어요"
    is PublishState.Published -> null
}

@Composable
private fun MessageItem(message: ChatMessage, panelOpen: Boolean, viewModel: ChatViewModel) {
    when (message.kind) {
        MessageKind.TEXT -> MessageBubble(ChatPayloads.readText(message.payloadJson), mine = message.role == MessageRole.USER)
        MessageKind.PHOTOS -> ChatPayloads.readPhotos(message.payloadJson)?.let { PhotosBubble(it.uris) }
        // 계획 본문은 오른쪽 패널에 있다 — 목록에는 그 자리를 가리키는 한 줄만 남긴다.
        MessageKind.PLAN -> Text(
            if (message.role == MessageRole.USER) "계획을 직접 고쳤어요 · 보기" else "계획을 오른쪽에 정리했어요 · 보기",
            style = AppTheme.typography.body2,
            color = AppTheme.colors.fillBrand,
            modifier = Modifier.fillMaxWidth().clickable(onClick = viewModel::openPanel)
                .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.md),
        )
        MessageKind.POST -> SystemMessage {
            Column {
                InlineBanner("초안을 만들었어요", BannerKind.Success)
                if (!panelOpen) {
                    Spacer(Modifier.height(AppSpacing.sm))
                    WeakButton("초안 열기", onClick = viewModel::togglePanel)
                }
            }
        }
        MessageKind.SYSTEM -> SystemMessage {
            val text = ChatPayloads.readText(message.payloadJson)
            val url = Regex("""https?://\S+""").find(text)?.value
            val uriHandler = LocalUriHandler.current
            // 발행 링크처럼 주소가 들어 있으면 배너 전체를 눌러 열 수 있게 한다.
            InlineBanner(text, if (url != null) BannerKind.Success else BannerKind.Info, onClick = url?.let { { uriHandler.openUri(it) } })
        }
    }
}

/** 오른쪽 에디터 패널. 발행 작업마다 따로 살아 있는 [PublishViewModel] 을 붙인다. */
@Composable
private fun PanelHost(
    jobId: String?,
    chatViewModel: ChatViewModel,
    onSessionExpired: (jobId: String) -> Unit,
    onFailed: (jobId: String) -> Unit,
    scalePercent: Int,
    onStatus: (String?) -> Unit,
) {
    if (jobId == null) return
    val publishViewModel = rememberPublishViewModel(jobId)
    val publishUi by publishViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(publishViewModel) {
        chatViewModel.reinject.collect { publishViewModel.reinject(it) }
    }
    LaunchedEffect(publishUi.state) {
        onStatus(panelStatusText(publishUi.state))
        (publishUi.state as? PublishState.Published)?.let { chatViewModel.onPublished(it.url) }
    }
    PublishPanel(
        viewModel = publishViewModel,
        modifier = Modifier.fillMaxSize(),
        onDone = chatViewModel::togglePanel,
        onSessionExpired = onSessionExpired,
        onFailed = onFailed,
        onCancelRequest = chatViewModel::togglePanel,
        contentScalePercent = scalePercent,
    )
}

/**
 * 입력창 밖(대화 목록·메시지·오른쪽 패널)을 누르면 입력창의 커서를 놓는다 — 그래야 패널 비율이 3:7 로 돌아온다.
 * Initial 단계에서 엿보기만 하므로 자식(WebView 포함)의 터치 처리는 그대로다.
 */
private fun Modifier.clearFocusOnPress(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    pointerInput(focusManager) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            focusManager.clearFocus()
        }
    }
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
