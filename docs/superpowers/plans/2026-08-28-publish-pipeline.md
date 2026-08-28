# 발행 파이프라인 (SP1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 손으로 쓴 테스트 글(제목·문단·사진)을 네이버 스마트에디터에 자동 입력하고, 사용자가 발행한 뒤 이력에 기록되는 흐름을 제품 수준으로 완성한다.

**Architecture:** 단일 Android 모듈. `domain`(순수 Kotlin 모델 + 상태 기계) ← `publish`(WebView 엔진, documentModel 변환, 이미지 준비) / `session` / `data`(Room, DataStore) ← `ui`(Compose 화면 + Hilt ViewModel). 발행 흐름은 순수 Kotlin `PublishStateMachine`이 `(state, event) → (state, effects)`로 결정하고 ViewModel이 효과를 실행한다.

**Tech Stack:** AGP 9.3.2 (빌트인 Kotlin, KGP 2.4.10), Gradle 9.7.1, Compose BOM 2026.08.00, Material3, Navigation Compose 2.10 (type-safe), Hilt 2.60.1 + KSP 2.3.11, Room 2.8.4, DataStore, kotlinx-serialization, Coil 3, Robolectric 4.16, Turbine.

**Spec:** `docs/superpowers/specs/2026-08-28-publish-pipeline-design.md` (설계), `spike/findings.md` (에디터 내부 API 규칙), `docs/design-guide.md` (UI 토큰·컴포넌트·문구)

## Global Constraints

- applicationId/패키지 루트: `com.csh.blogwriter`. 앱 이름 "블로그 도우미".
- compileSdk 37, targetSdk 37, **minSdk 33**. JDK 17. 새 의존성은 `gradle/libs.versions.toml`에만 추가.
- 빌드/테스트 명령 (Git Bash): `export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"; export JAVA_HOME="C:\\Program Files\\Android\\Android Studio\\jbr"; ./gradlew.bat :app:testDebugUnitTest --tests "<FQCN>"` / `./gradlew.bat :app:assembleDebug`.
- 사용자 화면 문구: 존댓말 "~해요"체, 기술 용어(API, 세션, 토큰, HTML, WebView) 금지 (`docs/design-guide.md` §6). 관리자 화면(FailureLog)만 예외.
- 화면 코드는 `ui/theme`의 토큰(`AppTheme.colors`, `AppTheme.typography`, `AppSpacing`)과 `ui/components`만 사용. Material3 컴포넌트를 화면에서 직접 색 지정해 쓰지 않는다.
- 터치 타겟 최소 56dp, CTA 높이 60dp, 화면 좌우 여백 24dp, 콘텐츠 최대 폭 720dp.
- 에디터 내부 API 규칙은 `spike/findings.md` §3~§4를 따른다. 특히: `uploadImagesFromFiles`는 **두 번 await**, image 컴포넌트 `path`에는 업로드 응답의 **`url`**.
- 발행 버튼은 자동으로 누르지 않는다. 사용자가 에디터에서 직접 탭.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- 테스트는 Robolectric `sdk=35` (`app/src/test/resources/robolectric.properties`). Android 클래스를 쓰는 테스트만 `@RunWith(RobolectricTestRunner::class)`; 순수 Kotlin은 일반 JUnit4.

---

### Task 1: 프로젝트 골격 (완료됨 — 검증만)

커밋 `4611dca`에서 완료. 실행자는 아래 검증만 수행한다.

**Files (존재 확인):**
- `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`
- `app/build.gradle.kts` (compose/serialization/ksp/hilt 플러그인, 의존성 전부)
- `app/src/main/AndroidManifest.xml`, `App.kt`(@HiltAndroidApp), `MainActivity.kt`(@AndroidEntryPoint)
- `app/src/main/java/com/csh/blogwriter/data/db/{AppDatabase,PublishHistoryEntity,PublishHistoryDao}.kt`, `di/DatabaseModule.kt`
- `app/src/test/java/com/csh/blogwriter/data/db/PublishHistoryDaoTest.kt`, `app/src/test/resources/robolectric.properties`

- [ ] **Step 1: 빌드와 테스트 통과 확인**

Run: `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, `app/build/test-results/testDebugUnitTest/TEST-com.csh.blogwriter.data.db.PublishHistoryDaoTest.xml`에 `failures="0"`.

---

### Task 2: 디자인 토큰과 공용 컴포넌트

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/ui/theme/Color.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/theme/Type.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/theme/Spacing.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/components/Buttons.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/components/AppTopBar.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/components/ScreenScaffold.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/components/ListRow.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/components/InlineBanner.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/components/ProgressScreen.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/components/ResultScreen.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/components/ConfirmSheet.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/components/AppTextField.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/MainActivity.kt` (AppTheme 적용)
- Test: `app/src/test/java/com/csh/blogwriter/ui/components/BottomCtaTest.kt`

**Interfaces:**
- Produces: `AppTheme { colors: AppColors; typography: AppTypography }`, `AppSpacing`, `BottomCta(text, onClick, modifier, enabled, loading)`, `WeakButton`, `DangerButton`, `AppTopBar(onBack, title, actions)`, `ScreenScaffold(topBar, bottom, content)`, `ListRow(title, subtitle, onClick, leading)`, `InlineBanner(text, kind, onClick)`, `ProgressScreen(title, detail, progress, onCancel)`, `ResultScreen(...)`, `ConfirmSheet(...)`, `AppTextField(...)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/ui/components/BottomCtaTest.kt
package com.csh.blogwriter.ui.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.csh.blogwriter.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BottomCtaTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun clickInvokesCallbackWhenEnabled() {
        var clicks = 0
        compose.setContent { AppTheme { BottomCta(text = "다음", onClick = { clicks++ }) } }
        compose.onNodeWithText("다음").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun disabledAndLoadingBlockClicks() {
        var clicks = 0
        compose.setContent { AppTheme { BottomCta(text = "다음", onClick = { clicks++ }, loading = true) } }
        compose.onNodeWithText("다음").assertIsNotEnabled()
        compose.onNodeWithTag("bottom_cta_loading").assertExists()
        assertEquals(0, clicks)
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.ui.components.BottomCtaTest"`
Expected: 컴파일 실패 (`Unresolved reference: AppTheme`, `BottomCta`).

- [ ] **Step 3: 테마 토큰 구현**

```kotlin
// ui/theme/Color.kt
package com.csh.blogwriter.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

object Palette {
    val Blue500 = Color(0xFF3182F6); val Blue600 = Color(0xFF1B64DA); val Blue100 = Color(0xFFE8F3FF)
    val Grey50 = Color(0xFFF9FAFB); val Grey100 = Color(0xFFF2F4F6); val Grey200 = Color(0xFFE5E8EB)
    val Grey300 = Color(0xFFD1D6DB); val Grey400 = Color(0xFFB0B8C1); val Grey500 = Color(0xFF8B95A1)
    val Grey600 = Color(0xFF6B7684); val Grey700 = Color(0xFF4E5968); val Grey800 = Color(0xFF333D4B)
    val Grey900 = Color(0xFF191F28)
    val Red500 = Color(0xFFF04452); val Red100 = Color(0xFFFFEEEE)
    val Green500 = Color(0xFF03B26C); val Green100 = Color(0xFFE5F7EF)
    val Orange500 = Color(0xFFFF9E2C)
    val White = Color(0xFFFFFFFF)
}

@Immutable
data class AppColors(
    val background: Color, val backgroundAlt: Color, val surface: Color, val surfaceWeak: Color, val border: Color,
    val textPrimary: Color, val textSecondary: Color, val textTertiary: Color, val textOnBrand: Color,
    val fillBrand: Color, val fillBrandPressed: Color, val fillBrandWeak: Color,
    val fillDanger: Color, val fillDangerWeak: Color, val fillSuccess: Color, val fillSuccessWeak: Color, val fillWarning: Color,
)

val LightAppColors = AppColors(
    background = Palette.White, backgroundAlt = Palette.Grey50, surface = Palette.White, surfaceWeak = Palette.Grey100, border = Palette.Grey200,
    textPrimary = Palette.Grey900, textSecondary = Palette.Grey600, textTertiary = Palette.Grey400, textOnBrand = Palette.White,
    fillBrand = Palette.Blue500, fillBrandPressed = Palette.Blue600, fillBrandWeak = Palette.Blue100,
    fillDanger = Palette.Red500, fillDangerWeak = Palette.Red100, fillSuccess = Palette.Green500, fillSuccessWeak = Palette.Green100, fillWarning = Palette.Orange500,
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
```

```kotlin
// ui/theme/Type.kt
package com.csh.blogwriter.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class AppTypography(
    val display: TextStyle, val title1: TextStyle, val title2: TextStyle, val title3: TextStyle,
    val body1: TextStyle, val body2: TextStyle, val caption: TextStyle, val button: TextStyle,
)

private fun style(size: Int, weight: FontWeight, line: Int) =
    TextStyle(fontSize = size.sp, fontWeight = weight, lineHeight = line.sp)

val DefaultAppTypography = AppTypography(
    display = style(32, FontWeight.Bold, 40), title1 = style(26, FontWeight.Bold, 34),
    title2 = style(22, FontWeight.Bold, 30), title3 = style(19, FontWeight.SemiBold, 26),
    body1 = style(17, FontWeight.Normal, 26), body2 = style(15, FontWeight.Normal, 22),
    caption = style(13, FontWeight.Normal, 18), button = style(18, FontWeight.SemiBold, 24),
)

val LocalAppTypography = staticCompositionLocalOf { DefaultAppTypography }
```

```kotlin
// ui/theme/Spacing.kt
package com.csh.blogwriter.ui.theme

import androidx.compose.ui.unit.dp

object AppSpacing {
    val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 20.dp
    val xxl = 24.dp; val section = 32.dp; val huge = 40.dp
    val screenHorizontal = 24.dp
    val contentMaxWidth = 720.dp
    val ctaHeight = 60.dp
    val touchTarget = 56.dp
    val radiusControl = 16.dp; val radiusCard = 20.dp; val radiusSheet = 24.dp; val radiusThumb = 12.dp
}
```

```kotlin
// ui/theme/Theme.kt
package com.csh.blogwriter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = LightAppColors
    val scheme = lightColorScheme(
        primary = colors.fillBrand, onPrimary = colors.textOnBrand,
        background = colors.background, onBackground = colors.textPrimary,
        surface = colors.surface, onSurface = colors.textPrimary,
        surfaceVariant = colors.surfaceWeak, onSurfaceVariant = colors.textSecondary,
        error = colors.fillDanger, outline = colors.border,
    )
    CompositionLocalProvider(LocalAppColors provides colors, LocalAppTypography provides DefaultAppTypography) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object AppTheme {
    val colors: AppColors @Composable @ReadOnlyComposable get() = LocalAppColors.current
    val typography: AppTypography @Composable @ReadOnlyComposable get() = LocalAppTypography.current
}
```

- [ ] **Step 4: 버튼 구현**

```kotlin
// ui/components/Buttons.kt
package com.csh.blogwriter.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun BottomCta(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) = CtaButton(text, onClick, modifier, enabled && !loading, loading,
    container = AppTheme.colors.fillBrand, content = AppTheme.colors.textOnBrand)

@Composable
fun WeakButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    CtaButton(text, onClick, modifier, enabled, false, container = AppTheme.colors.fillBrandWeak, content = AppTheme.colors.fillBrand)

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    CtaButton(text, onClick, modifier, enabled, false, container = AppTheme.colors.fillDanger, content = AppTheme.colors.textOnBrand)

@Composable
private fun CtaButton(
    text: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean, loading: Boolean,
    container: Color, content: Color,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(AppSpacing.ctaHeight),
        shape = RoundedCornerShape(AppSpacing.radiusControl),
        colors = ButtonDefaults.buttonColors(
            containerColor = container, contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.4f), disabledContentColor = content,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = AppTheme.typography.button)
            if (loading) {
                Spacer(Modifier.width(AppSpacing.md))
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).testTag("bottom_cta_loading"),
                    color = content, strokeWidth = 2.dp,
                )
            }
        }
    }
}
```

- [ ] **Step 5: 나머지 컴포넌트 구현**

```kotlin
// ui/components/AppTopBar.kt
package com.csh.blogwriter.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun AppTopBar(
    onBack: (() -> Unit)? = null,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로 가기", tint = AppTheme.colors.textPrimary)
            }
        }
        Box(Modifier.weight(1f).padding(start = AppSpacing.sm)) {
            if (title != null) Text(title, style = AppTheme.typography.title3, color = AppTheme.colors.textPrimary)
        }
        actions()
    }
}
```

```kotlin
// ui/components/ScreenScaffold.kt
package com.csh.blogwriter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 한 화면 = 상단바 + 스크롤 가능한 본문 + 하단 고정 CTA. 본문 폭은 720dp로 제한해 가운데 정렬. */
@Composable
fun ScreenScaffold(
    topBar: @Composable () -> Unit = {},
    bottom: @Composable (ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        topBar()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = AppSpacing.contentMaxWidth).fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenHorizontal),
                content = content,
            )
        }
        if (bottom != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = AppSpacing.contentMaxWidth).fillMaxWidth()
                        .padding(horizontal = AppSpacing.screenHorizontal, vertical = AppSpacing.xxl),
                    content = bottom,
                )
            }
        }
    }
}
```

```kotlin
// ui/components/ListRow.kt
package com.csh.blogwriter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailingChevron: Boolean = onClick != null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clip(RoundedCornerShape(AppSpacing.radiusCard))
            .background(AppTheme.colors.surfaceWeak)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(AppSpacing.lg)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTheme.typography.title3, color = AppTheme.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = AppTheme.typography.body2, color = AppTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailingChevron) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = AppTheme.colors.textTertiary)
    }
}
```

```kotlin
// ui/components/InlineBanner.kt
package com.csh.blogwriter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

enum class BannerKind { Info, Danger, Success, Warning }

@Composable
fun InlineBanner(text: String, kind: BannerKind = BannerKind.Info, onClick: (() -> Unit)? = null) {
    val c = AppTheme.colors
    val (bg, fg, icon) = when (kind) {
        BannerKind.Info -> Triple(c.fillBrandWeak, c.fillBrand, Icons.Rounded.Info)
        BannerKind.Danger -> Triple(c.fillDangerWeak, c.fillDanger, Icons.Rounded.Error)
        BannerKind.Success -> Triple(c.fillSuccessWeak, c.fillSuccess, Icons.Rounded.CheckCircle)
        BannerKind.Warning -> Triple(c.surfaceWeak, c.fillWarning, Icons.Rounded.Warning)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(AppSpacing.radiusControl)).background(bg)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg)
        Spacer(Modifier.width(AppSpacing.md))
        Text(text, style = AppTheme.typography.body2, color = c.textPrimary, modifier = Modifier.weight(1f))
        if (onClick != null) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = fg)
    }
}
```

```kotlin
// ui/components/ProgressScreen.kt
package com.csh.blogwriter.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 전체 화면 진행 표시. progress 가 null 이면 불확정 바. */
@Composable
fun ProgressScreen(title: String, detail: String?, progress: Float?, onCancel: (() -> Unit)? = null) {
    ScreenScaffold(bottom = if (onCancel != null) ({ WeakButton("그만두기", onCancel) }) else null) {
        Spacer(Modifier.height(AppSpacing.huge * 2))
        Text(title, style = AppTheme.typography.title1, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(AppSpacing.xxl))
        val barModifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        if (progress == null) LinearProgressIndicator(modifier = barModifier, color = AppTheme.colors.fillBrand, trackColor = AppTheme.colors.surfaceWeak)
        else LinearProgressIndicator(progress = { progress }, modifier = barModifier, color = AppTheme.colors.fillBrand, trackColor = AppTheme.colors.surfaceWeak)
        if (detail != null) {
            Spacer(Modifier.height(AppSpacing.md))
            Text(detail, style = AppTheme.typography.body2, color = AppTheme.colors.textSecondary)
        }
    }
}
```

```kotlin
// ui/components/ResultScreen.kt
package com.csh.blogwriter.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun ResultScreen(
    success: Boolean,
    title: String,
    message: String?,
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    ScreenScaffold(bottom = {
        BottomCta(primaryText, onPrimary)
        if (secondaryText != null && onSecondary != null) {
            Spacer(Modifier.height(AppSpacing.md))
            WeakButton(secondaryText, onSecondary)
        }
    }) {
        Spacer(Modifier.height(AppSpacing.huge * 2))
        Icon(
            if (success) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
            contentDescription = null,
            tint = if (success) AppTheme.colors.fillSuccess else AppTheme.colors.fillDanger,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(AppSpacing.xxl))
        Text(title, style = AppTheme.typography.display, color = AppTheme.colors.textPrimary)
        if (message != null) {
            Spacer(Modifier.height(AppSpacing.lg))
            Text(message, style = AppTheme.typography.body1, color = AppTheme.colors.textSecondary)
        }
    }
}
```

```kotlin
// ui/components/ConfirmSheet.kt
package com.csh.blogwriter.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    visible: Boolean,
    title: String,
    message: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    if (!visible) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = AppSpacing.radiusSheet, topEnd = AppSpacing.radiusSheet),
        containerColor = AppTheme.colors.surface,
    ) {
        Column(Modifier.padding(horizontal = AppSpacing.screenHorizontal).padding(bottom = AppSpacing.section)) {
            Text(title, style = AppTheme.typography.title2, color = AppTheme.colors.textPrimary)
            if (message != null) {
                Spacer(Modifier.height(AppSpacing.md))
                Text(message, style = AppTheme.typography.body1, color = AppTheme.colors.textSecondary)
            }
            Spacer(Modifier.height(AppSpacing.section))
            if (danger) DangerButton(confirmText, onConfirm) else BottomCta(confirmText, onConfirm)
            Spacer(Modifier.height(AppSpacing.md))
            WeakButton(dismissText, onDismiss)
        }
    }
}
```

```kotlin
// ui/components/AppTextField.kt
package com.csh.blogwriter.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    Column(modifier) {
        Text(label, style = AppTheme.typography.body2, color = c.textSecondary)
        Spacer(Modifier.height(AppSpacing.sm))
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine, minLines = minLines,
            textStyle = AppTheme.typography.body1.copy(color = c.textPrimary),
            placeholder = placeholder?.let { { Text(it, style = AppTheme.typography.body1, color = c.textTertiary) } },
            shape = RoundedCornerShape(AppSpacing.radiusControl),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.surfaceWeak, unfocusedContainerColor = c.surfaceWeak,
                focusedBorderColor = c.fillBrand, unfocusedBorderColor = c.surfaceWeak,
                cursorColor = c.fillBrand,
            ),
        )
    }
}
```

- [ ] **Step 6: MainActivity에 AppTheme 적용**

`MainActivity.setContent { ... }` 를 `setContent { AppTheme { Text("블로그 도우미") } }` 로 바꾸고 `import com.csh.blogwriter.ui.theme.AppTheme` 추가.

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.ui.components.BottomCtaTest"`
Expected: PASS (2 tests). Robolectric 에서 Compose 테스트가 폰트 문제로 실패하면 `robolectric.properties` 에 `qualifiers=w800dp-h1280dp-xhdpi` 를 추가하고 재시도.

- [ ] **Step 8: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/ui app/src/main/java/com/csh/blogwriter/MainActivity.kt app/src/test/java/com/csh/blogwriter/ui
git commit -m "Add Toss-like design tokens and shared components"
```

---

### Task 3: 도메인 모델과 직렬화

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/domain/model/PostContent.kt`
- Create: `app/src/main/java/com/csh/blogwriter/domain/model/PublishJob.kt`
- Test: `app/src/test/java/com/csh/blogwriter/domain/model/PostContentJsonTest.kt`

**Interfaces:**
- Produces: `PostContent(title, blocks)`, `Block.Paragraph(runs, align, list)`, `Block.Image(ref)`, `Block.Quote(text, source)`, `Run(text, bold, color, background, size)`, `FontSize.BODY/TITLE`, `Align`, `ListType`, `PostContentJson.encode(PostContent): String`, `PostContentJson.decode(String): PostContent`, `PreparedImage(ref, file, width, height)`, `PublishJob(id, content, images)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/domain/model/PostContentJsonTest.kt
package com.csh.blogwriter.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostContentJsonTest {
    private val sample = PostContent(
        title = "제목",
        blocks = listOf(
            Block.Paragraph(runs = listOf(Run("굵게", bold = true), Run(" 보통"))),
            Block.Image(ref = "img_001"),
            Block.Paragraph(runs = listOf(Run("가운데")), align = Align.CENTER, list = ListType.BULLET),
            Block.Quote(text = "인용", source = "출처"),
            Block.Quote(text = "출처 없음"),
        ),
    )

    @Test
    fun roundTripsThroughJson() {
        val json = PostContentJson.encode(sample)
        assertEquals(sample, PostContentJson.decode(json))
    }

    @Test
    fun usesStableTypeDiscriminators() {
        val json = PostContentJson.encode(sample)
        assertTrue(json.contains("\"type\":\"paragraph\""))
        assertTrue(json.contains("\"type\":\"image\""))
        assertTrue(json.contains("\"type\":\"quote\""))
    }

    @Test
    fun imageRefsAreListedInOrder() {
        assertEquals(listOf("img_001"), sample.imageRefs())
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.domain.model.PostContentJsonTest"`
Expected: 컴파일 실패.

- [ ] **Step 3: 모델 구현**

```kotlin
// domain/model/PostContent.kt
package com.csh.blogwriter.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PostContent(val title: String, val blocks: List<Block>) {
    fun imageRefs(): List<String> = blocks.filterIsInstance<Block.Image>().map { it.ref }
}

@Serializable
sealed interface Block {
    @Serializable @SerialName("paragraph")
    data class Paragraph(val runs: List<Run>, val align: Align = Align.LEFT, val list: ListType? = null) : Block

    @Serializable @SerialName("image")
    data class Image(val ref: String) : Block

    @Serializable @SerialName("quote")
    data class Quote(val text: String, val source: String? = null) : Block
}

@Serializable
data class Run(
    val text: String,
    val bold: Boolean = false,
    val color: String? = null,
    val background: String? = null,
    val size: FontSize = FontSize.BODY,
)

/** 스마트에디터 fontSizeCode. 스파이크에서 검증된 값만 둔다. */
@Serializable
enum class FontSize(val code: String) { BODY("fs19"), TITLE("fs28") }

@Serializable enum class Align { LEFT, CENTER, RIGHT }
@Serializable enum class ListType { BULLET, DECIMAL }

object PostContentJson {
    val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(content: PostContent): String = json.encodeToString(PostContent.serializer(), content)
    fun decode(text: String): PostContent = json.decodeFromString(PostContent.serializer(), text)
}
```

```kotlin
// domain/model/PublishJob.kt
package com.csh.blogwriter.domain.model

import java.io.File

data class PreparedImage(val ref: String, val file: File, val width: Int, val height: Int)

data class PublishJob(val id: String, val content: PostContent, val images: List<PreparedImage>)
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.domain.model.PostContentJsonTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/domain app/src/test/java/com/csh/blogwriter/domain
git commit -m "Add PostContent domain model with JSON serialization"
```

---

### Task 4: URL 파서 (발행 감지 / 세션 만료 / 블로그 ID)

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/publish/PublishUrlParser.kt`
- Create: `app/src/main/java/com/csh/blogwriter/session/BlogIdResolver.kt`
- Test: `app/src/test/java/com/csh/blogwriter/publish/PublishUrlParserTest.kt`
- Test: `app/src/test/java/com/csh/blogwriter/session/BlogIdResolverTest.kt`

**Interfaces:**
- Produces: `PublishUrlParser.isLoginPage(url): Boolean`, `PublishUrlParser.parsePublished(url): PublishedPost?` with `PublishedPost(blogId, logNo).url`, `BlogIdResolver.fromUrl(url): String?`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/publish/PublishUrlParserTest.kt
package com.csh.blogwriter.publish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishUrlParserTest {
    private val published = "https://blog.naver.com/PostView.naver?blogId=myblog&Redirect=View&logNo=224000000001&categoryNo=25&isAfterWrite=true&isMrblogPost=false"

    @Test
    fun detectsLoginRedirect() {
        assertTrue(PublishUrlParser.isLoginPage("https://nid.naver.com/nidlogin.login?mode=form&url=https%3A%2F%2Fblog.naver.com"))
        assertFalse(PublishUrlParser.isLoginPage("https://blog.naver.com/myblog?Redirect=Write"))
    }

    @Test
    fun parsesPublishedUrl() {
        val post = PublishUrlParser.parsePublished(published)!!
        assertEquals("myblog", post.blogId)
        assertEquals("224000000001", post.logNo)
        assertEquals("https://blog.naver.com/myblog/224000000001", post.url)
    }

    @Test
    fun ignoresPostViewWithoutAfterWriteFlag() {
        assertNull(PublishUrlParser.parsePublished("https://blog.naver.com/PostView.naver?blogId=myblog&logNo=1"))
        assertNull(PublishUrlParser.parsePublished("https://blog.naver.com/myblog?Redirect=Write&categoryNo=25"))
        assertNull(PublishUrlParser.parsePublished("not a url"))
    }
}
```

```kotlin
// app/src/test/java/com/csh/blogwriter/session/BlogIdResolverTest.kt
package com.csh.blogwriter.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlogIdResolverTest {
    @Test
    fun extractsIdFromBlogHomeUrl() {
        assertEquals("myblog", BlogIdResolver.fromUrl("https://blog.naver.com/myblog"))
        assertEquals("my_blog-1", BlogIdResolver.fromUrl("https://blog.naver.com/my_blog-1/"))
        assertEquals("myblog", BlogIdResolver.fromUrl("https://blog.naver.com/myblog?tab=1"))
    }

    @Test
    fun rejectsNonHomeUrls() {
        assertNull(BlogIdResolver.fromUrl("https://blog.naver.com/MyBlog.naver"))
        assertNull(BlogIdResolver.fromUrl("https://blog.naver.com/PostView.naver?blogId=x"))
        assertNull(BlogIdResolver.fromUrl("https://nid.naver.com/nidlogin.login"))
        assertNull(BlogIdResolver.fromUrl("https://blog.naver.com/myblog/224000000001"))
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.publish.PublishUrlParserTest" --tests "com.csh.blogwriter.session.BlogIdResolverTest"`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

```kotlin
// publish/PublishUrlParser.kt
package com.csh.blogwriter.publish

import java.net.URI
import java.net.URLDecoder

data class PublishedPost(val blogId: String, val logNo: String) {
    val url: String get() = "https://blog.naver.com/$blogId/$logNo"
}

object PublishUrlParser {
    fun isLoginPage(url: String): Boolean = url.startsWith("https://nid.naver.com/")

    fun parsePublished(url: String): PublishedPost? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host != "blog.naver.com" || uri.path != "/PostView.naver") return null
        val query = queryMap(uri.rawQuery ?: return null)
        if (query["isAfterWrite"] != "true") return null
        val blogId = query["blogId"]?.takeIf { it.isNotBlank() } ?: return null
        val logNo = query["logNo"]?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: return null
        return PublishedPost(blogId, logNo)
    }

    private fun queryMap(rawQuery: String): Map<String, String> =
        rawQuery.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
}
```

```kotlin
// session/BlogIdResolver.kt
package com.csh.blogwriter.session

/** `https://blog.naver.com/MyBlog.naver` 가 리다이렉트된 `https://blog.naver.com/{blogId}` 에서 blogId 를 뽑는다. */
object BlogIdResolver {
    private val pattern = Regex("^https://blog\\.naver\\.com/([A-Za-z0-9_-]+)/?(?:\\?.*)?$")
    fun fromUrl(url: String): String? = pattern.matchEntire(url)?.groupValues?.get(1)
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: 위와 같은 명령. Expected: PASS (5 tests).

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/publish/PublishUrlParser.kt app/src/main/java/com/csh/blogwriter/session/BlogIdResolver.kt app/src/test/java/com/csh/blogwriter/publish app/src/test/java/com/csh/blogwriter/session
git commit -m "Add publish URL and blog id parsers"
```

---

### Task 5: DocumentModelConverter

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/publish/UploadedImage.kt`
- Create: `app/src/main/java/com/csh/blogwriter/publish/DocumentModelConverter.kt`
- Test: `app/src/test/java/com/csh/blogwriter/publish/DocumentModelConverterTest.kt`

**Interfaces:**
- Consumes: `PostContent`, `Block`, `Run`, `FontSize`, `Align`, `ListType` (Task 3).
- Produces: `UploadedImage(ref, url, fileName, width, height, fileSize, domain)` (+ `UploadedImage.fromResponse(ref, JsonObject)`), `DocumentModelConverter(idGenerator).convert(content, images: Map<String, UploadedImage>, documentId: String, version: String): JsonObject`, `DocumentModelConverter.expectedComponentCount(content): Int`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/publish/DocumentModelConverterTest.kt
package com.csh.blogwriter.publish

import com.csh.blogwriter.domain.model.Align
import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.FontSize
import com.csh.blogwriter.domain.model.ListType
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentModelConverterTest {
    private var counter = 0
    private val converter = DocumentModelConverter(idGenerator = { "SE-${++counter}" })
    private val image = UploadedImage(
        ref = "img_001", url = "/MjAy/abc.PNG/img_001.jpg", fileName = "img_001.jpg",
        width = 1600, height = 1200, fileSize = 12345, domain = "https://blogfiles.pstatic.net",
    )

    private fun convert(content: PostContent, images: Map<String, UploadedImage> = mapOf("img_001" to image)) =
        converter.convert(content, images, documentId = "DOC1", version = "2.10.2")

    private fun components(doc: JsonObject) = doc["document"]!!.jsonObject["components"]!!.jsonArray

    @Test
    fun envelopeAndTitle() {
        val doc = convert(PostContent("제목", emptyList()), emptyMap())
        val document = doc["document"]!!.jsonObject
        assertEquals("2.10.2", document["version"]!!.jsonPrimitive.content)
        assertEquals("DOC1", document["id"]!!.jsonPrimitive.content)
        assertEquals("", doc["documentId"]!!.jsonPrimitive.content)
        val title = components(doc)[0].jsonObject
        assertEquals("documentTitle", title["@ctype"]!!.jsonPrimitive.content)
        val node = title["title"]!!.jsonArray[0].jsonObject["nodes"]!!.jsonArray[0].jsonObject
        assertEquals("제목", node["value"]!!.jsonPrimitive.content)
        assertEquals("textNode", node["@ctype"]!!.jsonPrimitive.content)
    }

    @Test
    fun paragraphRunStylesAndParagraphStyle() {
        val content = PostContent("t", listOf(
            Block.Paragraph(listOf(Run("굵게", bold = true, color = "#ff0010", background = "#ffd300", size = FontSize.TITLE), Run("보통")),
                align = Align.CENTER, list = ListType.BULLET),
        ))
        val text = components(convert(content))[1].jsonObject
        assertEquals("text", text["@ctype"]!!.jsonPrimitive.content)
        val paragraph = text["value"]!!.jsonArray[0].jsonObject
        val pStyle = paragraph["style"]!!.jsonObject
        assertEquals("paragraphStyle", pStyle["@ctype"]!!.jsonPrimitive.content)
        assertEquals("center", pStyle["align"]!!.jsonPrimitive.content)
        assertEquals("bullet", pStyle["list"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(1.7, pStyle["lineHeight"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val bold = paragraph["nodes"]!!.jsonArray[0].jsonObject["style"]!!.jsonObject
        assertTrue(bold["bold"]!!.jsonPrimitive.boolean)
        assertEquals("#ff0010", bold["fontColor"]!!.jsonPrimitive.content)
        assertEquals("#ffd300", bold["backgroundColor"]!!.jsonPrimitive.content)
        assertEquals("fs28", bold["fontSizeCode"]!!.jsonPrimitive.content)
        assertEquals("nanumsquare", bold["fontFamily"]!!.jsonPrimitive.content)
        val plain = paragraph["nodes"]!!.jsonArray[1].jsonObject["style"]!!.jsonObject
        assertNull(plain["bold"]); assertNull(plain["fontColor"])
        assertEquals("fs19", plain["fontSizeCode"]!!.jsonPrimitive.content)
    }

    @Test
    fun consecutiveParagraphsShareOneTextComponent() {
        val content = PostContent("t", listOf(
            Block.Paragraph(listOf(Run("a"))), Block.Paragraph(listOf(Run("b"))),
            Block.Image("img_001"),
            Block.Paragraph(listOf(Run("c"))),
        ))
        val comps = components(convert(content))
        assertEquals(listOf("documentTitle", "text", "image", "text"), comps.map { it.jsonObject["@ctype"]!!.jsonPrimitive.content })
        assertEquals(2, comps[1].jsonObject["value"]!!.jsonArray.size)
        assertEquals(4, DocumentModelConverter.expectedComponentCount(content))
    }

    @Test
    fun imageMappingUsesUploadUrlAsPath() {
        val img = components(convert(PostContent("t", listOf(Block.Image("img_001")))))[1].jsonObject
        assertEquals("image", img["@ctype"]!!.jsonPrimitive.content)
        assertEquals("/MjAy/abc.PNG/img_001.jpg", img["path"]!!.jsonPrimitive.content)
        assertEquals("https://blogfiles.pstatic.net/MjAy/abc.PNG/img_001.jpg?type=w1", img["src"]!!.jsonPrimitive.content)
        assertEquals("https://blogfiles.pstatic.net", img["domain"]!!.jsonPrimitive.content)
        assertEquals(693, img["width"]!!.jsonPrimitive.int)
        assertEquals(520, img["height"]!!.jsonPrimitive.int)
        assertEquals(1600, img["originalWidth"]!!.jsonPrimitive.int)
        assertEquals(1200, img["originalHeight"]!!.jsonPrimitive.int)
        assertEquals(12345, img["fileSize"]!!.jsonPrimitive.int)
        assertEquals("img_001.jpg", img["fileName"]!!.jsonPrimitive.content)
        assertTrue(img["represent"]!!.jsonPrimitive.boolean)
        assertTrue(img["internalResource"]!!.jsonPrimitive.boolean)
        assertEquals("fit", img["contentMode"]!!.jsonPrimitive.content)
        assertEquals("local", img["origin"]!!.jsonObject["srcFrom"]!!.jsonPrimitive.content)
    }

    @Test
    fun onlyFirstImageIsRepresentative() {
        val second = image.copy(ref = "img_002", url = "/x/img_002.jpg", fileName = "img_002.jpg")
        val comps = components(convert(PostContent("t", listOf(Block.Image("img_001"), Block.Image("img_002"))),
            mapOf("img_001" to image, "img_002" to second)))
        assertTrue(comps[1].jsonObject["represent"]!!.jsonPrimitive.boolean)
        assertFalse(comps[2].jsonObject["represent"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun quoteWithAndWithoutSource() {
        val comps = components(convert(PostContent("t", listOf(Block.Quote("인용", "출처"), Block.Quote("없음")))))
        val withSource = comps[1].jsonObject
        assertEquals("quotation", withSource["@ctype"]!!.jsonPrimitive.content)
        assertEquals("인용", withSource["value"]!!.jsonArray[0].jsonObject["nodes"]!!.jsonArray[0].jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("출처", withSource["source"]!!.jsonArray[0].jsonObject["nodes"]!!.jsonArray[0].jsonObject["value"]!!.jsonPrimitive.content)
        assertNull(comps[2].jsonObject["source"])
    }

    @Test
    fun missingUploadedImageThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            convert(PostContent("t", listOf(Block.Image("img_009"))))
        }
    }

    @Test
    fun uploadedImageParsesEditorResponse() {
        val response = Json.parseToJsonElement("""{"url":"/a/b.PNG/x.jpg","path":"/a/b.PNG","fileName":"x.jpg","width":800,"height":600,"fileSize":21096,"domain":"https://blogfiles.pstatic.net"}""").jsonObject
        val parsed = UploadedImage.fromResponse("img_001", response)
        assertEquals(UploadedImage("img_001", "/a/b.PNG/x.jpg", "x.jpg", 800, 600, 21096, "https://blogfiles.pstatic.net"), parsed)
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.publish.DocumentModelConverterTest"`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

```kotlin
// publish/UploadedImage.kt
package com.csh.blogwriter.publish

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** 에디터 이미지 업로드 응답 (spike/findings.md §4). path 로는 반드시 url(파일명 포함)을 쓴다. */
data class UploadedImage(
    val ref: String,
    val url: String,
    val fileName: String,
    val width: Int,
    val height: Int,
    val fileSize: Long,
    val domain: String,
) {
    companion object {
        fun fromResponse(ref: String, response: JsonObject): UploadedImage = UploadedImage(
            ref = ref,
            url = response["url"]!!.jsonPrimitive.content,
            fileName = response["fileName"]?.jsonPrimitive?.content ?: response["url"]!!.jsonPrimitive.content.substringAfterLast('/'),
            width = response["width"]!!.jsonPrimitive.int,
            height = response["height"]!!.jsonPrimitive.int,
            fileSize = response["fileSize"]?.jsonPrimitive?.content?.toLong() ?: 0L,
            domain = response["domain"]?.jsonPrimitive?.content ?: "https://blogfiles.pstatic.net",
        )
    }
}
```

```kotlin
// publish/DocumentModelConverter.kt
package com.csh.blogwriter.publish

import com.csh.blogwriter.domain.model.Align
import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.ListType
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/** PostContent → 스마트에디터 ONE documentModel (spike/findings.md §3). */
class DocumentModelConverter(private val idGenerator: () -> String = { "SE-" + UUID.randomUUID() }) {

    fun convert(content: PostContent, images: Map<String, UploadedImage>, documentId: String, version: String): JsonObject {
        var representAssigned = false
        val components = buildJsonArray {
            add(titleComponent(content.title))
            val pendingParagraphs = mutableListOf<Block.Paragraph>()
            fun flush() {
                if (pendingParagraphs.isNotEmpty()) { add(textComponent(pendingParagraphs.toList())); pendingParagraphs.clear() }
            }
            for (block in content.blocks) {
                when (block) {
                    is Block.Paragraph -> pendingParagraphs += block
                    is Block.Image -> {
                        flush()
                        val uploaded = requireNotNull(images[block.ref]) { "업로드 결과 없음: ${block.ref}" }
                        add(imageComponent(uploaded, represent = !representAssigned))
                        representAssigned = true
                    }
                    is Block.Quote -> { flush(); add(quoteComponent(block)) }
                }
            }
            flush()
        }
        return buildJsonObject {
            putJsonObject("document") {
                put("version", version); put("theme", "default"); put("language", "ko-KR"); put("id", documentId)
                put("components", components)
            }
            put("documentId", "")
        }
    }

    private fun titleComponent(title: String) = buildJsonObject {
        put("id", idGenerator()); put("layout", "default")
        put("title", buildJsonArray { add(plainParagraph(title)) })
        put("subTitle", null as String?); put("align", "left"); put("@ctype", "documentTitle")
    }

    private fun textComponent(paragraphs: List<Block.Paragraph>) = buildJsonObject {
        put("id", idGenerator()); put("layout", "default")
        put("value", buildJsonArray { paragraphs.forEach { add(paragraph(it)) } })
        put("@ctype", "text")
    }

    private fun paragraph(p: Block.Paragraph) = buildJsonObject {
        put("id", idGenerator())
        put("nodes", buildJsonArray { p.runs.forEach { add(textNode(it)) } })
        putJsonObject("style") {
            put("lineHeight", 1.7)
            if (p.align != Align.LEFT) put("align", p.align.name.lowercase())
            if (p.list != null) putJsonObject("list") {
                put("type", if (p.list == ListType.BULLET) "bullet" else "decimal"); put("level", 0); put("@ctype", "paragraphListStyle")
            }
            put("@ctype", "paragraphStyle")
        }
        put("@ctype", "paragraph")
    }

    private fun textNode(run: Run) = buildJsonObject {
        put("id", idGenerator()); put("value", run.text)
        putJsonObject("style") {
            put("fontFamily", "nanumsquare"); put("fontSizeCode", run.size.code)
            if (run.bold) put("bold", true)
            if (run.color != null) put("fontColor", run.color)
            if (run.background != null) put("backgroundColor", run.background)
            put("@ctype", "nodeStyle")
        }
        put("@ctype", "textNode")
    }

    private fun plainParagraph(text: String) = buildJsonObject {
        put("id", idGenerator())
        put("nodes", buildJsonArray { add(buildJsonObject { put("id", idGenerator()); put("value", text); put("@ctype", "textNode") }) })
        put("@ctype", "paragraph")
    }

    private fun quoteComponent(q: Block.Quote) = buildJsonObject {
        put("id", idGenerator()); put("layout", "default")
        put("value", buildJsonArray { add(plainParagraph(q.text)) })
        if (q.source != null) put("source", buildJsonArray { add(plainParagraph(q.source)) })
        put("@ctype", "quotation")
    }

    private fun imageComponent(img: UploadedImage, represent: Boolean): JsonObject {
        val width = min(693, img.width)
        val height = (img.height.toDouble() * width / img.width).roundToInt()
        return buildJsonObject {
            put("id", idGenerator()); put("layout", "default")
            put("src", "${img.domain}${img.url}?type=w1")
            put("internalResource", true); put("represent", represent)
            put("path", img.url); put("domain", img.domain)
            put("fileSize", img.fileSize)
            put("width", width); put("widthPercentage", 0); put("height", height)
            put("originalWidth", img.width); put("originalHeight", img.height)
            put("fileName", img.fileName)
            put("format", "normal"); put("displayFormat", "normal"); put("imageLoaded", true); put("contentMode", "fit")
            putJsonObject("origin") { put("srcFrom", "local"); put("@ctype", "imageOrigin") }
            put("ai", false); put("@ctype", "image")
        }
    }

    companion object {
        /** 제목 1 + (연속 문단은 하나의 text 컴포넌트) + 이미지/인용구 각 1. 주입 후 검증에 사용. */
        fun expectedComponentCount(content: PostContent): Int {
            var count = 1
            var inText = false
            for (block in content.blocks) {
                if (block is Block.Paragraph) { if (!inText) { count++; inText = true } }
                else { count++; inText = false }
            }
            return count
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.publish.DocumentModelConverterTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/publish app/src/test/java/com/csh/blogwriter/publish
git commit -m "Add DocumentModelConverter for SmartEditor ONE"
```

---

### Task 6: FallbackTextRenderer

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/publish/FallbackTextRenderer.kt`
- Test: `app/src/test/java/com/csh/blogwriter/publish/FallbackTextRendererTest.kt`

**Interfaces:**
- Produces: `FallbackTextRenderer.render(content: PostContent): String`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/publish/FallbackTextRendererTest.kt
package com.csh.blogwriter.publish

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.ListType
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackTextRendererTest {
    @Test
    fun rendersPlainTextWithImageMarkers() {
        val content = PostContent("제목", listOf(
            Block.Paragraph(listOf(Run("첫 ", bold = true), Run("문단"))),
            Block.Image("img_001"),
            Block.Paragraph(listOf(Run("항목")), list = ListType.BULLET),
            Block.Paragraph(listOf(Run("번호")), list = ListType.DECIMAL),
            Block.Quote("인용", "출처"),
            Block.Quote("출처 없음"),
            Block.Image("img_002"),
        ))
        val expected = """
            제목

            첫 문단

            [사진 1]

            • 항목
            1. 번호

            "인용" — 출처

            "출처 없음"

            [사진 2]
        """.trimIndent()
        assertEquals(expected, FallbackTextRenderer.render(content))
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.publish.FallbackTextRendererTest"`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

```kotlin
// publish/FallbackTextRenderer.kt
package com.csh.blogwriter.publish

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.ListType
import com.csh.blogwriter.domain.model.PostContent

/** 자동 입력 실패 시 클립보드에 넣을 서식 없는 텍스트. 연속 목록 문단은 한 덩어리, 나머지는 빈 줄로 구분. */
object FallbackTextRenderer {
    fun render(content: PostContent): String {
        val chunks = mutableListOf<String>()
        var imageNo = 0
        var decimalNo = 0
        var listBuffer: MutableList<String>? = null
        fun flushList() { listBuffer?.let { chunks += it.joinToString("\n") }; listBuffer = null; decimalNo = 0 }

        chunks += content.title
        for (block in content.blocks) {
            when (block) {
                is Block.Paragraph -> {
                    val text = block.runs.joinToString("") { it.text }
                    if (block.list == null) { flushList(); chunks += text }
                    else {
                        val prefix = if (block.list == ListType.BULLET) "• " else "${++decimalNo}. "
                        (listBuffer ?: mutableListOf<String>().also { listBuffer = it }) += prefix + text
                    }
                }
                is Block.Image -> { flushList(); chunks += "[사진 ${++imageNo}]" }
                is Block.Quote -> { flushList(); chunks += "\"${block.text}\"" + (block.source?.let { " — $it" } ?: "") }
            }
        }
        flushList()
        return chunks.joinToString("\n\n")
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: 위 명령. Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/publish/FallbackTextRenderer.kt app/src/test/java/com/csh/blogwriter/publish/FallbackTextRendererTest.kt
git commit -m "Add fallback plain-text renderer"
```

---

### Task 7: PublishStateMachine

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/domain/publish/PublishState.kt`
- Create: `app/src/main/java/com/csh/blogwriter/domain/publish/PublishStateMachine.kt`
- Test: `app/src/test/java/com/csh/blogwriter/domain/publish/PublishStateMachineTest.kt`

**Interfaces:**
- Consumes: `PublishUrlParser.isLoginPage`, `PublishUrlParser.parsePublished` (Task 4).
- Produces: `PublishStage`, `PublishState` (sealed), `PublishEvent` (sealed), `PublishEffect` (sealed), `PublishStateMachine(totalImages, expectedComponents).reduce(state, event): Transition` with `Transition(state, effects)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/domain/publish/PublishStateMachineTest.kt
package com.csh.blogwriter.domain.publish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishStateMachineTest {
    private val loginUrl = "https://nid.naver.com/nidlogin.login?url=x"
    private val publishedUrl = "https://blog.naver.com/PostView.naver?blogId=myblog&logNo=224000000001&isAfterWrite=true"

    private fun machine(images: Int = 2, components: Int = 4) = PublishStateMachine(totalImages = images, expectedComponents = components)

    private fun PublishStateMachine.run(vararg events: PublishEvent): List<Transition> {
        var state: PublishState = PublishState.Idle
        return events.map { e -> reduce(state, e).also { state = it.state } }
    }

    @Test
    fun happyPathWithImages() {
        val t = machine().run(
            PublishEvent.Start,
            PublishEvent.ImagePrepared(1), PublishEvent.ImagesPrepared,
            PublishEvent.PageLoaded("https://blog.naver.com/myblog?Redirect=Write&categoryNo=25"),
            PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed,
            PublishEvent.ImageUploaded("img_001"), PublishEvent.ImageUploaded("img_002"),
            PublishEvent.Injected(4),
            PublishEvent.UrlChanged(publishedUrl),
        )
        assertEquals(PublishState.PreparingImages(0, 2), t[0].state)
        assertEquals(listOf(PublishEffect.PrepareImages), t[0].effects)
        assertEquals(PublishState.PreparingImages(1, 2), t[1].state)
        assertEquals(PublishState.LoadingEditor, t[2].state)
        assertEquals(listOf(PublishEffect.LoadEditor), t[2].effects)
        assertEquals(PublishState.LoadingEditor, t[3].state)
        assertEquals(listOf(PublishEffect.StartReadyPolling), t[3].effects)
        assertEquals(PublishState.DismissingPopups, t[4].state)
        assertEquals(listOf(PublishEffect.DismissPopups), t[4].effects)
        assertEquals(PublishState.UploadingImages(0, 2), t[5].state)
        assertEquals(listOf(PublishEffect.UploadImages), t[5].effects)
        assertEquals(PublishState.UploadingImages(1, 2), t[6].state)
        assertEquals(PublishState.Injecting, t[7].state)
        assertEquals(listOf(PublishEffect.Inject), t[7].effects)
        assertEquals(PublishState.Reviewing, t[8].state)
        assertEquals(listOf(PublishEffect.ShowEditor), t[8].effects)
        assertEquals(PublishState.Published("224000000001", "https://blog.naver.com/myblog/224000000001"), t[9].state)
        assertEquals(listOf(PublishEffect.SavePublished("224000000001", "https://blog.naver.com/myblog/224000000001")), t[9].effects)
    }

    @Test
    fun noImagesSkipsUploadStage() {
        val t = machine(images = 0, components = 2).run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("https://blog.naver.com/x?Redirect=Write"),
            PublishEvent.EditorReady, PublishEvent.PopupsDismissed,
        )
        assertEquals(PublishState.Injecting, t.last().state)
        assertEquals(listOf(PublishEffect.Inject), t.last().effects)
    }

    @Test
    fun loginRedirectBecomesSessionExpiredAndSavesPending() {
        val t = machine().run(PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.UrlChanged(loginUrl))
        assertEquals(PublishState.SessionExpired, t.last().state)
        assertEquals(listOf(PublishEffect.SavePending), t.last().effects)
    }

    @Test
    fun timeoutFailsWithStageAndLogs() {
        val t = machine().run(PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.Timeout(PublishStage.LOAD_EDITOR))
        val failed = t.last().state as PublishState.Failed
        assertEquals(PublishStage.LOAD_EDITOR, failed.stage)
        assertEquals(listOf(PublishEffect.LogFailure(PublishStage.LOAD_EDITOR, failed.message)), t.last().effects)
    }

    @Test
    fun imageFailureFailsUploadStage() {
        val t = machine().run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("u"), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.ImageFailed("img_001", "SERVER_ERROR"),
        )
        val failed = t.last().state as PublishState.Failed
        assertEquals(PublishStage.UPLOAD, failed.stage)
        assertTrue(failed.message.contains("img_001"))
    }

    @Test
    fun wrongComponentCountFailsInjectStage() {
        val t = machine(images = 0, components = 3).run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("u"), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.Injected(1),
        )
        assertEquals(PublishStage.INJECT, (t.last().state as PublishState.Failed).stage)
    }

    @Test
    fun jsErrorInReviewIsIgnoredButOtherUrlsInReviewAreIgnoredToo() {
        val m = machine(images = 0, components = 2)
        val reviewing = m.run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("u"), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.Injected(2),
        ).last().state
        assertEquals(PublishState.Reviewing, reviewing)
        assertEquals(PublishState.Reviewing, m.reduce(reviewing, PublishEvent.UrlChanged("https://blog.naver.com/myblog?Redirect=Write&categoryNo=25")).state)
        assertEquals(PublishState.SessionExpired, m.reduce(reviewing, PublishEvent.UrlChanged(loginUrl)).state)
    }

    @Test
    fun terminalStatesIgnoreFurtherEvents() {
        val m = machine()
        val failed = PublishState.Failed(PublishStage.UPLOAD, "x")
        assertEquals(Transition(failed, emptyList()), m.reduce(failed, PublishEvent.EditorReady))
        val published = PublishState.Published("1", "u")
        assertEquals(Transition(published, emptyList()), m.reduce(published, PublishEvent.UrlChanged(loginUrl)))
    }

    @Test
    fun retryFromFailedRestarts() {
        val t = machine().reduce(PublishState.Failed(PublishStage.UPLOAD, "x"), PublishEvent.Retry)
        assertEquals(PublishState.PreparingImages(0, 2), t.state)
        assertEquals(listOf(PublishEffect.PrepareImages), t.effects)
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.domain.publish.PublishStateMachineTest"`
Expected: 컴파일 실패.

- [ ] **Step 3: 상태/이벤트/효과 정의**

```kotlin
// domain/publish/PublishState.kt
package com.csh.blogwriter.domain.publish

enum class PublishStage { PREPARE, LOAD_EDITOR, DISMISS_POPUPS, UPLOAD, INJECT, REVIEW }

sealed interface PublishState {
    data object Idle : PublishState
    data class PreparingImages(val done: Int, val total: Int) : PublishState
    data object LoadingEditor : PublishState
    data object DismissingPopups : PublishState
    data class UploadingImages(val done: Int, val total: Int) : PublishState
    data object Injecting : PublishState
    data object Reviewing : PublishState
    data class Published(val logNo: String, val url: String) : PublishState
    data object SessionExpired : PublishState
    data class Failed(val stage: PublishStage, val message: String) : PublishState

    val isTerminal: Boolean get() = this is Published || this is SessionExpired || this is Failed
}

sealed interface PublishEvent {
    data object Start : PublishEvent
    data class ImagePrepared(val done: Int) : PublishEvent
    data object ImagesPrepared : PublishEvent
    data class PageLoaded(val url: String) : PublishEvent
    data object EditorReady : PublishEvent
    data object PopupsDismissed : PublishEvent
    data class ImageUploaded(val ref: String) : PublishEvent
    data class ImageFailed(val ref: String, val message: String) : PublishEvent
    data class Injected(val componentCount: Int) : PublishEvent
    data class UrlChanged(val url: String) : PublishEvent
    data class Timeout(val stage: PublishStage) : PublishEvent
    data class JsError(val stage: PublishStage, val message: String) : PublishEvent
    data object Retry : PublishEvent
}

sealed interface PublishEffect {
    data object PrepareImages : PublishEffect
    data object LoadEditor : PublishEffect
    data object StartReadyPolling : PublishEffect
    data object DismissPopups : PublishEffect
    data object UploadImages : PublishEffect
    data object Inject : PublishEffect
    data object ShowEditor : PublishEffect
    data class SavePublished(val logNo: String, val url: String) : PublishEffect
    data object SavePending : PublishEffect
    data class LogFailure(val stage: PublishStage, val message: String) : PublishEffect
}

data class Transition(val state: PublishState, val effects: List<PublishEffect>)
```

- [ ] **Step 4: 상태 기계 구현**

```kotlin
// domain/publish/PublishStateMachine.kt
package com.csh.blogwriter.domain.publish

import com.csh.blogwriter.publish.PublishUrlParser

/**
 * 발행 흐름의 순수 상태 전이. 부수효과는 [PublishEffect] 로 돌려주고 ViewModel 이 실행한다.
 * 어떤 상태에서든 로그인 페이지로의 이동은 SessionExpired, 타임아웃/JS 오류는 Failed.
 */
class PublishStateMachine(private val totalImages: Int, private val expectedComponents: Int) {

    fun reduce(state: PublishState, event: PublishEvent): Transition {
        if (state.isTerminal) {
            return if (event is PublishEvent.Retry && state !is PublishState.Published) start() else Transition(state, emptyList())
        }
        return when (event) {
            is PublishEvent.Start, is PublishEvent.Retry -> start()
            is PublishEvent.UrlChanged -> onUrl(state, event.url)
            is PublishEvent.PageLoaded ->
                if (PublishUrlParser.isLoginPage(event.url)) expired()
                else if (state is PublishState.LoadingEditor) Transition(state, listOf(PublishEffect.StartReadyPolling))
                else Transition(state, emptyList())
            is PublishEvent.Timeout -> fail(event.stage, "제한 시간 초과")
            is PublishEvent.JsError -> fail(event.stage, event.message)
            is PublishEvent.ImagePrepared ->
                if (state is PublishState.PreparingImages) Transition(state.copy(done = event.done), emptyList()) else Transition(state, emptyList())
            is PublishEvent.ImagesPrepared ->
                if (state is PublishState.PreparingImages) Transition(PublishState.LoadingEditor, listOf(PublishEffect.LoadEditor)) else Transition(state, emptyList())
            is PublishEvent.EditorReady ->
                if (state is PublishState.LoadingEditor) Transition(PublishState.DismissingPopups, listOf(PublishEffect.DismissPopups)) else Transition(state, emptyList())
            is PublishEvent.PopupsDismissed ->
                if (state !is PublishState.DismissingPopups) Transition(state, emptyList())
                else if (totalImages == 0) inject()
                else Transition(PublishState.UploadingImages(0, totalImages), listOf(PublishEffect.UploadImages))
            is PublishEvent.ImageUploaded ->
                if (state !is PublishState.UploadingImages) Transition(state, emptyList())
                else if (state.done + 1 >= state.total) inject()
                else Transition(state.copy(done = state.done + 1), emptyList())
            is PublishEvent.ImageFailed -> fail(PublishStage.UPLOAD, "사진 업로드 실패 (${event.ref}): ${event.message}")
            is PublishEvent.Injected ->
                if (state !is PublishState.Injecting) Transition(state, emptyList())
                else if (event.componentCount != expectedComponents) fail(PublishStage.INJECT, "컴포넌트 수 불일치: 기대 $expectedComponents, 실제 ${event.componentCount}")
                else Transition(PublishState.Reviewing, listOf(PublishEffect.ShowEditor))
        }
    }

    private fun start() = Transition(PublishState.PreparingImages(0, totalImages), listOf(PublishEffect.PrepareImages))
    private fun inject() = Transition(PublishState.Injecting, listOf(PublishEffect.Inject))
    private fun expired() = Transition(PublishState.SessionExpired, listOf(PublishEffect.SavePending))
    private fun fail(stage: PublishStage, message: String) =
        Transition(PublishState.Failed(stage, message), listOf(PublishEffect.LogFailure(stage, message)))

    private fun onUrl(state: PublishState, url: String): Transition {
        if (PublishUrlParser.isLoginPage(url)) return expired()
        if (state is PublishState.Reviewing) {
            val post = PublishUrlParser.parsePublished(url) ?: return Transition(state, emptyList())
            return Transition(PublishState.Published(post.logNo, post.url), listOf(PublishEffect.SavePublished(post.logNo, post.url)))
        }
        return Transition(state, emptyList())
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.domain.publish.PublishStateMachineTest"`
Expected: PASS (9 tests).

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/domain/publish app/src/test/java/com/csh/blogwriter/domain/publish
git commit -m "Add pure PublishStateMachine with effects"
```

---

### Task 8: 데이터 계층 (Room 엔티티, 리포지토리 인터페이스, SettingsStore)

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/data/db/FailureLogEntity.kt`, `FailureLogDao.kt`
- Create: `app/src/main/java/com/csh/blogwriter/data/db/PendingJobEntity.kt`, `PendingJobDao.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/data/db/AppDatabase.kt` (엔티티 추가, version 유지 1 — 아직 배포 전)
- Create: `app/src/main/java/com/csh/blogwriter/data/repo/HistoryRepository.kt`, `FailureLogRepository.kt`, `PendingJobRepository.kt` (인터페이스 + Room 구현)
- Create: `app/src/main/java/com/csh/blogwriter/data/prefs/SettingsStore.kt` (인터페이스 + DataStore 구현)
- Modify: `app/src/main/java/com/csh/blogwriter/di/DatabaseModule.kt` (DAO 제공), Create: `di/DataModule.kt` (Binds)
- Test: `app/src/test/java/com/csh/blogwriter/data/db/PendingJobDaoTest.kt`, `app/src/test/java/com/csh/blogwriter/data/prefs/SettingsStoreTest.kt`

**Interfaces:**
- Produces:
  - `PublishHistoryItem(id, title, logNo, url, publishedAt, imageCount)`; `HistoryRepository { fun observeAll(): Flow<List<PublishHistoryItem>>; suspend fun add(title, logNo, url, imageCount) }`
  - `FailureLogItem(id, at, stage, message, detail, appVersion)`; `FailureLogRepository { fun observeAll(): Flow<List<FailureLogItem>>; suspend fun add(stage: String, message: String, detail: String) }`
  - `PendingJob(id, content: PostContent, imageUris: List<String>, preparedPaths: List<String>?, createdAt, lastFailure: String?)`; `PendingJobRepository { fun observeLatest(): Flow<PendingJob?>; suspend fun get(id): PendingJob?; suspend fun save(job); suspend fun setPreparedPaths(id, paths); suspend fun setLastFailure(id, msg?); suspend fun delete(id) }`
  - `SettingsStore { val blogId: Flow<String?>; suspend fun setBlogId(id: String?); suspend fun blogIdOnce(): String? }`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/data/db/PendingJobDaoTest.kt
package com.csh.blogwriter.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PendingJobDaoTest {
    private lateinit var db: AppDatabase
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    @Test
    fun upsertsAndObservesLatest() = runTest {
        val dao = db.pendingJobDao()
        dao.upsert(PendingJobEntity(id = "a", contentJson = "{}", imageUrisJson = "[]", preparedPathsJson = null, createdAt = 1, lastFailure = null))
        dao.upsert(PendingJobEntity(id = "b", contentJson = "{}", imageUrisJson = "[]", preparedPathsJson = null, createdAt = 2, lastFailure = null))
        assertEquals("b", dao.observeLatest().first()!!.id)
        dao.updatePreparedPaths("b", "[\"/tmp/x.jpg\"]")
        assertEquals("[\"/tmp/x.jpg\"]", dao.get("b")!!.preparedPathsJson)
        dao.delete("b"); dao.delete("a")
        assertNull(dao.observeLatest().first())
    }

    @Test
    fun failureLogNewestFirst() = runTest {
        val dao = db.failureLogDao()
        dao.insert(FailureLogEntity(at = 1, stage = "UPLOAD", message = "m1", detail = "", appVersion = "0.1.0"))
        dao.insert(FailureLogEntity(at = 2, stage = "INJECT", message = "m2", detail = "", appVersion = "0.1.0"))
        assertEquals(listOf("m2", "m1"), dao.observeAll().first().map { it.message })
    }
}
```

```kotlin
// app/src/test/java/com/csh/blogwriter/data/prefs/SettingsStoreTest.kt
package com.csh.blogwriter.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store(): SettingsStore {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create { folder.newFile("settings.preferences_pb") }
        return DataStoreSettingsStore(dataStore)
    }

    @Test
    fun blogIdRoundTrip() = runTest {
        val s = store()
        assertNull(s.blogId.first())
        s.setBlogId("myblog")
        assertEquals("myblog", s.blogIdOnce())
        s.setBlogId(null)
        assertNull(s.blogId.first())
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.data.*"`
Expected: 컴파일 실패.

- [ ] **Step 3: 엔티티/DAO 구현**

```kotlin
// data/db/FailureLogEntity.kt
package com.csh.blogwriter.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "failure_log")
data class FailureLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val stage: String,
    val message: String,
    val detail: String,
    val appVersion: String,
)
```

```kotlin
// data/db/FailureLogDao.kt
package com.csh.blogwriter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FailureLogDao {
    @Insert suspend fun insert(entity: FailureLogEntity): Long
    @Query("SELECT * FROM failure_log ORDER BY at DESC LIMIT 200") fun observeAll(): Flow<List<FailureLogEntity>>
}
```

```kotlin
// data/db/PendingJobEntity.kt
package com.csh.blogwriter.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_job")
data class PendingJobEntity(
    @PrimaryKey val id: String,
    val contentJson: String,
    val imageUrisJson: String,
    val preparedPathsJson: String?,
    val createdAt: Long,
    val lastFailure: String?,
)
```

```kotlin
// data/db/PendingJobDao.kt
package com.csh.blogwriter.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingJobDao {
    @Upsert suspend fun upsert(entity: PendingJobEntity)
    @Query("SELECT * FROM pending_job WHERE id = :id") suspend fun get(id: String): PendingJobEntity?
    @Query("SELECT * FROM pending_job ORDER BY createdAt DESC LIMIT 1") fun observeLatest(): Flow<PendingJobEntity?>
    @Query("UPDATE pending_job SET preparedPathsJson = :json WHERE id = :id") suspend fun updatePreparedPaths(id: String, json: String?)
    @Query("UPDATE pending_job SET lastFailure = :message WHERE id = :id") suspend fun updateLastFailure(id: String, message: String?)
    @Query("DELETE FROM pending_job WHERE id = :id") suspend fun delete(id: String)
}
```

`AppDatabase`:
```kotlin
@Database(entities = [PublishHistoryEntity::class, FailureLogEntity::class, PendingJobEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun publishHistoryDao(): PublishHistoryDao
    abstract fun failureLogDao(): FailureLogDao
    abstract fun pendingJobDao(): PendingJobDao
}
```
(기존 `app/schemas/.../1.json` 은 삭제 후 재생성된다 — 아직 배포 전이므로 마이그레이션 없음.)

- [ ] **Step 4: 리포지토리와 SettingsStore 구현**

```kotlin
// data/repo/HistoryRepository.kt
package com.csh.blogwriter.data.repo

import com.csh.blogwriter.data.db.PublishHistoryDao
import com.csh.blogwriter.data.db.PublishHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class PublishHistoryItem(val id: Long, val title: String, val logNo: String, val url: String, val publishedAt: Long, val imageCount: Int)

interface HistoryRepository {
    fun observeAll(): Flow<List<PublishHistoryItem>>
    suspend fun add(title: String, logNo: String, url: String, imageCount: Int)
}

class RoomHistoryRepository @Inject constructor(private val dao: PublishHistoryDao) : HistoryRepository {
    override fun observeAll(): Flow<List<PublishHistoryItem>> = dao.observeAll().map { rows ->
        rows.map { PublishHistoryItem(it.id, it.title, it.logNo, it.url, it.publishedAt, it.imageCount) }
    }
    override suspend fun add(title: String, logNo: String, url: String, imageCount: Int) {
        dao.insert(PublishHistoryEntity(title = title, logNo = logNo, url = url, publishedAt = System.currentTimeMillis(), imageCount = imageCount))
    }
}
```

```kotlin
// data/repo/FailureLogRepository.kt
package com.csh.blogwriter.data.repo

import com.csh.blogwriter.BuildConfig
import com.csh.blogwriter.data.db.FailureLogDao
import com.csh.blogwriter.data.db.FailureLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class FailureLogItem(val id: Long, val at: Long, val stage: String, val message: String, val detail: String, val appVersion: String)

interface FailureLogRepository {
    fun observeAll(): Flow<List<FailureLogItem>>
    suspend fun add(stage: String, message: String, detail: String)
}

class RoomFailureLogRepository @Inject constructor(private val dao: FailureLogDao) : FailureLogRepository {
    override fun observeAll(): Flow<List<FailureLogItem>> = dao.observeAll().map { rows ->
        rows.map { FailureLogItem(it.id, it.at, it.stage, it.message, it.detail, it.appVersion) }
    }
    override suspend fun add(stage: String, message: String, detail: String) {
        dao.insert(FailureLogEntity(at = System.currentTimeMillis(), stage = stage, message = message, detail = detail, appVersion = BuildConfig.VERSION_NAME))
    }
}
```

```kotlin
// data/repo/PendingJobRepository.kt
package com.csh.blogwriter.data.repo

import com.csh.blogwriter.data.db.PendingJobDao
import com.csh.blogwriter.data.db.PendingJobEntity
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.PostContentJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class PendingJob(
    val id: String,
    val content: PostContent,
    val imageUris: List<String>,
    val preparedPaths: List<String>?,
    val createdAt: Long,
    val lastFailure: String?,
)

interface PendingJobRepository {
    fun observeLatest(): Flow<PendingJob?>
    suspend fun get(id: String): PendingJob?
    suspend fun save(job: PendingJob)
    suspend fun setPreparedPaths(id: String, paths: List<String>?)
    suspend fun setLastFailure(id: String, message: String?)
    suspend fun delete(id: String)
}

class RoomPendingJobRepository @Inject constructor(private val dao: PendingJobDao) : PendingJobRepository {
    private val listSerializer = ListSerializer(String.serializer())
    private fun encodeList(list: List<String>) = Json.encodeToString(listSerializer, list)
    private fun decodeList(text: String) = Json.decodeFromString(listSerializer, text)

    private fun PendingJobEntity.toModel() = PendingJob(
        id = id, content = PostContentJson.decode(contentJson), imageUris = decodeList(imageUrisJson),
        preparedPaths = preparedPathsJson?.let(::decodeList), createdAt = createdAt, lastFailure = lastFailure,
    )

    override fun observeLatest(): Flow<PendingJob?> = dao.observeLatest().map { it?.toModel() }
    override suspend fun get(id: String): PendingJob? = dao.get(id)?.toModel()
    override suspend fun save(job: PendingJob) = dao.upsert(
        PendingJobEntity(job.id, PostContentJson.encode(job.content), encodeList(job.imageUris), job.preparedPaths?.let(::encodeList), job.createdAt, job.lastFailure)
    )
    override suspend fun setPreparedPaths(id: String, paths: List<String>?) = dao.updatePreparedPaths(id, paths?.let(::encodeList))
    override suspend fun setLastFailure(id: String, message: String?) = dao.updateLastFailure(id, message)
    override suspend fun delete(id: String) = dao.delete(id)
}
```

```kotlin
// data/prefs/SettingsStore.kt
package com.csh.blogwriter.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

interface SettingsStore {
    val blogId: Flow<String?>
    suspend fun setBlogId(id: String?)
    suspend fun blogIdOnce(): String? = blogId.first()
}

class DataStoreSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) : SettingsStore {
    private val keyBlogId = stringPreferencesKey("blog_id")
    override val blogId: Flow<String?> = dataStore.data.map { it[keyBlogId] }
    override suspend fun setBlogId(id: String?) {
        dataStore.edit { prefs -> if (id == null) prefs.remove(keyBlogId) else prefs[keyBlogId] = id }
    }
}
```

DI:
```kotlin
// di/DatabaseModule.kt 에 추가
@Provides fun provideFailureLogDao(db: AppDatabase): FailureLogDao = db.failureLogDao()
@Provides fun providePendingJobDao(db: AppDatabase): PendingJobDao = db.pendingJobDao()
@Provides @Singleton fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
```
(`import androidx.datastore.preferences.core.PreferenceDataStoreFactory`, `androidx.datastore.preferences.preferencesDataStoreFile`, `androidx.datastore.core.DataStore`, `androidx.datastore.preferences.core.Preferences`)

```kotlin
// di/DataModule.kt
package com.csh.blogwriter.di

import com.csh.blogwriter.data.prefs.DataStoreSettingsStore
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds @Singleton abstract fun historyRepository(impl: RoomHistoryRepository): HistoryRepository
    @Binds @Singleton abstract fun failureLogRepository(impl: RoomFailureLogRepository): FailureLogRepository
    @Binds @Singleton abstract fun pendingJobRepository(impl: RoomPendingJobRepository): PendingJobRepository
    @Binds @Singleton abstract fun settingsStore(impl: DataStoreSettingsStore): SettingsStore
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `rm -rf app/schemas; ./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.data.*"`
Expected: PASS (4 tests: 기존 DAO 1 + PendingJob 2 + Settings 1).

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/data app/src/main/java/com/csh/blogwriter/di app/src/test/java/com/csh/blogwriter/data app/schemas
git commit -m "Add failure log, pending job storage, repositories and settings store"
```

---

### Task 9: ImagePreparer

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/publish/ImagePreparer.kt`
- Test: `app/src/test/java/com/csh/blogwriter/publish/ImagePreparerTest.kt`

**Interfaces:**
- Consumes: `PreparedImage` (Task 3).
- Produces: `ImagePreparer(context).prepare(jobId: String, uris: List<Uri>, onProgress: (Int) -> Unit): List<PreparedImage>` (refs `img_001`…; files in `cacheDir/publish/{jobId}/`), `ImagePreparer.load(jobId, paths: List<String>): List<PreparedImage>?` (기존 파일 재사용, 하나라도 없으면 null), `ImagePreparer.clear(jobId)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/publish/ImagePreparerTest.kt
package com.csh.blogwriter.publish

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagePreparerTest {
    @get:Rule val folder = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    private fun jpeg(name: String, w: Int, h: Int, orientation: Int? = null): Uri {
        val file = File(folder.root, name)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (orientation != null) ExifInterface(file.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
        return Uri.fromFile(file)
    }

    @Test
    fun resizesToLongEdge1600AndNamesSequentially() = runTest {
        val preparer = ImagePreparer(context)
        val progress = mutableListOf<Int>()
        val result = preparer.prepare("job1", listOf(jpeg("a.jpg", 3200, 1600), jpeg("b.jpg", 400, 800)), progress::add)

        assertEquals(listOf("img_001", "img_002"), result.map { it.ref })
        assertEquals(1600, result[0].width); assertEquals(800, result[0].height)
        assertEquals(400, result[1].width); assertEquals(800, result[1].height)
        assertTrue(result.all { it.file.exists() && it.file.name == "${it.ref}.jpg" })
        val decoded = BitmapFactory.decodeFile(result[0].file.absolutePath)
        assertEquals(1600, decoded.width)
        assertEquals(listOf(1, 2), progress)
    }

    @Test
    fun appliesExifRotation() = runTest {
        val result = ImagePreparer(context).prepare("job2", listOf(jpeg("r.jpg", 800, 400, ExifInterface.ORIENTATION_ROTATE_90)), {})
        assertEquals(400, result[0].width); assertEquals(800, result[0].height)
    }

    @Test
    fun loadReusesExistingFilesAndClearDeletes() = runTest {
        val preparer = ImagePreparer(context)
        val prepared = preparer.prepare("job3", listOf(jpeg("c.jpg", 100, 100)), {})
        val loaded = preparer.load("job3", prepared.map { it.file.absolutePath })!!
        assertEquals(prepared.map { it.ref }, loaded.map { it.ref })
        assertEquals(100, loaded[0].width)
        preparer.clear("job3")
        assertNull(preparer.load("job3", prepared.map { it.file.absolutePath }))
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.publish.ImagePreparerTest"`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

```kotlin
// publish/ImagePreparer.kt
package com.csh.blogwriter.publish

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.csh.blogwriter.domain.model.PreparedImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.max

/** 갤러리 Uri → 업로드용 JPEG (긴 변 1600px, 품질 85, EXIF 회전 적용, ASCII 파일명). */
class ImagePreparer @Inject constructor(@ApplicationContext private val context: Context) {

    companion object { const val LONG_EDGE = 1600; const val QUALITY = 85 }

    private fun dir(jobId: String) = File(context.cacheDir, "publish/$jobId").apply { mkdirs() }

    suspend fun prepare(jobId: String, uris: List<Uri>, onProgress: (Int) -> Unit): List<PreparedImage> = withContext(Dispatchers.IO) {
        val dir = dir(jobId)
        uris.mapIndexed { index, uri ->
            val ref = "img_%03d".format(index + 1)
            val bitmap = decodeScaled(uri)
            val out = File(dir, "$ref.jpg")
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            val prepared = PreparedImage(ref, out, bitmap.width, bitmap.height)
            bitmap.recycle()
            onProgress(index + 1)
            prepared
        }
    }

    fun load(jobId: String, paths: List<String>): List<PreparedImage>? {
        val images = paths.mapIndexed { index, path ->
            val file = File(path)
            if (!file.exists()) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            PreparedImage("img_%03d".format(index + 1), file, opts.outWidth, opts.outHeight)
        }
        return images
    }

    fun clear(jobId: String) { dir(jobId).deleteRecursively() }

    private fun decodeScaled(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("이미지를 읽을 수 없습니다: $uri")
        val orientation = resolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        val longEdge = max(decoded.width, decoded.height)
        val scale = if (longEdge > LONG_EDGE) LONG_EDGE.toFloat() / longEdge else 1f
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
        if (matrix.isIdentity) return decoded
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result !== decoded) decoded.recycle()
        return result
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.publish.ImagePreparerTest"`
Expected: PASS (3 tests). `file://` Uri 를 Robolectric ContentResolver 가 열지 못하면 테스트의 `jpeg()` 를 `Uri.fromFile` 대신 `Uri.parse("file://" + file.absolutePath)` 로 바꾸고, 그래도 실패하면 `ShadowContentResolver.registerInputStream(uri, file.inputStream())` 을 사용한다.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/publish/ImagePreparer.kt app/src/test/java/com/csh/blogwriter/publish/ImagePreparerTest.kt
git commit -m "Add ImagePreparer with resize and EXIF rotation"
```

---

### Task 10: 내비게이션 + 홈/이력/실패 로그 화면

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/ui/navigation/Routes.kt`, `AppNavHost.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/home/HomeScreen.kt`, `HomeViewModel.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/history/HistoryScreen.kt`, `HistoryViewModel.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/admin/FailureLogScreen.kt`, `FailureLogViewModel.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/format/DateFormats.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/MainActivity.kt` (AppNavHost)
- Test: `app/src/test/java/com/csh/blogwriter/ui/home/HomeViewModelTest.kt`, `app/src/test/java/com/csh/blogwriter/ui/format/DateFormatsTest.kt`

**Interfaces:**
- Consumes: `HistoryRepository`, `PendingJobRepository`, `SettingsStore`, `FailureLogRepository` (Task 8); 컴포넌트 (Task 2).
- Produces: `Routes` — `Home`, `Login(returnTo: String?)`, `TestCompose`, `Publish(jobId: String)`, `Fallback(jobId: String)`, `History`, `FailureLogs` (모두 `@Serializable`). `AppNavHost()`. `HomeUiState(hasBlogId: Boolean, pendingJobId: String?, pendingTitle: String?)`. `DateFormats.relative(epochMillis, now): String`.
- Login/TestCompose/Publish/Fallback 목적지는 이 태스크에서 자리표시 Composable(`Text("준비 중")`)로 두고 이후 태스크가 교체한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/ui/home/HomeViewModelTest.kt
package com.csh.blogwriter.ui.home

import app.cash.turbine.test
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.domain.model.PostContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val blogId = MutableStateFlow<String?>(null)
    private val pending = MutableStateFlow<PendingJob?>(null)

    private val settings = object : SettingsStore {
        override val blogId: Flow<String?> = this@HomeViewModelTest.blogId
        override suspend fun setBlogId(id: String?) { this@HomeViewModelTest.blogId.value = id }
    }
    private val pendingRepo = object : PendingJobRepository {
        override fun observeLatest(): Flow<PendingJob?> = pending
        override suspend fun get(id: String): PendingJob? = pending.value?.takeIf { it.id == id }
        override suspend fun save(job: PendingJob) { pending.value = job }
        override suspend fun setPreparedPaths(id: String, paths: List<String>?) {}
        override suspend fun setLastFailure(id: String, message: String?) {}
        override suspend fun delete(id: String) { pending.value = null }
    }

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun reflectsBlogIdAndPendingJob() = runTest {
        val vm = HomeViewModel(settings, pendingRepo)
        vm.uiState.test {
            assertEquals(HomeUiState(hasBlogId = false, pendingJobId = null, pendingTitle = null), awaitItem())
            blogId.value = "myblog"
            assertEquals(true, awaitItem().hasBlogId)
            pending.value = PendingJob("j1", PostContent("올리다 만 글", emptyList()), emptyList(), null, 1L, null)
            val s = awaitItem()
            assertEquals("j1", s.pendingJobId); assertEquals("올리다 만 글", s.pendingTitle)
        }
    }
}
```

```kotlin
// app/src/test/java/com/csh/blogwriter/ui/format/DateFormatsTest.kt
package com.csh.blogwriter.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DateFormatsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun relativeAndAbsoluteForms() {
        val now = at(2026, 8, 28, 15, 30)
        assertEquals("방금 전", DateFormats.relative(at(2026, 8, 28, 15, 30), now, zone))
        assertEquals("5분 전", DateFormats.relative(at(2026, 8, 28, 15, 25), now, zone))
        assertEquals("2시간 전", DateFormats.relative(at(2026, 8, 28, 13, 30), now, zone))
        assertEquals("어제 오후 3:30", DateFormats.relative(at(2026, 8, 27, 15, 30), now, zone))
        assertEquals("8월 20일 오전 9:05", DateFormats.relative(at(2026, 8, 20, 9, 5), now, zone))
        assertEquals("2025년 12월 1일", DateFormats.relative(at(2025, 12, 1, 9, 5), now, zone))
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.ui.home.HomeViewModelTest" --tests "com.csh.blogwriter.ui.format.DateFormatsTest"`
Expected: 컴파일 실패.

- [ ] **Step 3: DateFormats 와 ViewModel 구현**

```kotlin
// ui/format/DateFormats.kt
package com.csh.blogwriter.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

object DateFormats {
    fun relative(epochMillis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val diffMin = (now - epochMillis) / 60_000
        val t = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            diffMin < 1 -> "방금 전"
            diffMin < 60 -> "${diffMin}분 전"
            diffMin < 24 * 60 && t.toLocalDate() == today -> "${diffMin / 60}시간 전"
            t.toLocalDate() == today.minusDays(1) -> "어제 ${time(t)}"
            t.year == today.year -> "${t.monthValue}월 ${t.dayOfMonth}일 ${time(t)}"
            else -> "${t.year}년 ${t.monthValue}월 ${t.dayOfMonth}일"
        }
    }

    private fun time(t: ZonedDateTime): String {
        val h = t.hour
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = when (h % 12) { 0 -> 12; else -> h % 12 }
        return "$ampm $h12:${"%02d".format(t.minute)}"
    }
}
```

```kotlin
// ui/home/HomeViewModel.kt
package com.csh.blogwriter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.PendingJobRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(val hasBlogId: Boolean, val pendingJobId: String?, val pendingTitle: String?)

@HiltViewModel
class HomeViewModel @Inject constructor(settings: SettingsStore, pendingJobs: PendingJobRepository) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = combine(settings.blogId, pendingJobs.observeLatest()) { blogId, pending ->
        HomeUiState(hasBlogId = blogId != null, pendingJobId = pending?.id, pendingTitle = pending?.content?.title)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(false, null, null))
}
```

```kotlin
// ui/history/HistoryViewModel.kt
package com.csh.blogwriter.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.repo.HistoryRepository
import com.csh.blogwriter.data.repo.PublishHistoryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(repo: HistoryRepository) : ViewModel() {
    val items: StateFlow<List<PublishHistoryItem>> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```

```kotlin
// ui/admin/FailureLogViewModel.kt
package com.csh.blogwriter.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.repo.FailureLogItem
import com.csh.blogwriter.data.repo.FailureLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class FailureLogViewModel @Inject constructor(repo: FailureLogRepository) : ViewModel() {
    val items: StateFlow<List<FailureLogItem>> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```

- [ ] **Step 4: 라우트와 NavHost**

```kotlin
// ui/navigation/Routes.kt
package com.csh.blogwriter.ui.navigation

import kotlinx.serialization.Serializable

object Routes {
    @Serializable data object Home
    /** returnTo: 로그인 후 돌아갈 곳. null 이면 Home. 값은 "publish:{jobId}" 또는 "compose". */
    @Serializable data class Login(val returnTo: String? = null)
    @Serializable data object TestCompose
    @Serializable data class Publish(val jobId: String)
    @Serializable data class Fallback(val jobId: String)
    @Serializable data object History
    @Serializable data object FailureLogs
}
```

```kotlin
// ui/navigation/AppNavHost.kt
package com.csh.blogwriter.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.csh.blogwriter.ui.admin.FailureLogScreen
import com.csh.blogwriter.ui.history.HistoryScreen
import com.csh.blogwriter.ui.home.HomeScreen

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.Home) {
        composable<Routes.Home> {
            HomeScreen(
                onNewPost = { nav.navigate(Routes.TestCompose) },
                onLogin = { returnTo -> nav.navigate(Routes.Login(returnTo)) },
                onResumePending = { jobId -> nav.navigate(Routes.Publish(jobId)) },
                onHistory = { nav.navigate(Routes.History) },
                onAdmin = { nav.navigate(Routes.FailureLogs) },
            )
        }
        composable<Routes.Login> { Text("준비 중: 로그인") }
        composable<Routes.TestCompose> { Text("준비 중: 글쓰기") }
        composable<Routes.Publish> { Text("준비 중: 발행 " + it.toRoute<Routes.Publish>().jobId) }
        composable<Routes.Fallback> { Text("준비 중: 폴백 " + it.toRoute<Routes.Fallback>().jobId) }
        composable<Routes.History> { HistoryScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.FailureLogs> { FailureLogScreen(onBack = { nav.popBackStack() }) }
    }
}
```

- [ ] **Step 5: 화면 구현**

```kotlin
// ui/home/HomeScreen.kt
package com.csh.blogwriter.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.BannerKind
import com.csh.blogwriter.ui.components.BottomCta
import com.csh.blogwriter.ui.components.InlineBanner
import com.csh.blogwriter.ui.components.ListRow
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun HomeScreen(
    onNewPost: () -> Unit,
    onLogin: (returnTo: String) -> Unit,
    onResumePending: (jobId: String) -> Unit,
    onHistory: () -> Unit,
    onAdmin: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenScaffold(
        topBar = {
            AppTopBar(actions = {
                IconButton(onClick = onAdmin, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Settings, contentDescription = "관리자 설정", tint = AppTheme.colors.textTertiary)
                }
            })
        },
        bottom = { BottomCta("새 글 쓰기", onClick = { if (state.hasBlogId) onNewPost() else onLogin("compose") }) },
    ) {
        Spacer(Modifier.height(AppSpacing.section))
        Text("오늘은 어떤 이야기를\n올릴까요?", style = AppTheme.typography.title1, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(AppSpacing.section))
        if (state.pendingJobId != null) {
            InlineBanner("올리다 만 글이 있어요: ${state.pendingTitle ?: ""}", BannerKind.Info) { onResumePending(state.pendingJobId!!) }
            Spacer(Modifier.height(AppSpacing.lg))
        }
        if (!state.hasBlogId) {
            InlineBanner("네이버 로그인이 필요해요", BannerKind.Warning) { onLogin("home") }
            Spacer(Modifier.height(AppSpacing.lg))
        }
        ListRow(title = "발행한 글", subtitle = "지금까지 올린 글을 볼 수 있어요", onClick = onHistory)
    }
}
```

```kotlin
// ui/history/HistoryScreen.kt
package com.csh.blogwriter.ui.history

import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.ListRow
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.format.DateFormats
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun HistoryScreen(onBack: () -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ScreenScaffold(topBar = { AppTopBar(onBack = onBack) }) {
        Spacer(Modifier.height(AppSpacing.lg))
        Text("발행한 글", style = AppTheme.typography.title1, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(AppSpacing.xxl))
        if (items.isEmpty()) {
            Text("아직 올린 글이 없어요.\n첫 글을 써 볼까요?", style = AppTheme.typography.body1, color = AppTheme.colors.textSecondary)
        } else {
            LazyColumn {
                items(items, key = { it.id }) { item ->
                    ListRow(
                        title = item.title,
                        subtitle = DateFormats.relative(item.publishedAt) + " · 사진 ${item.imageCount}장",
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, item.url.toUri())) },
                    )
                    Spacer(Modifier.height(AppSpacing.md))
                }
            }
        }
    }
}
```

```kotlin
// ui/admin/FailureLogScreen.kt
package com.csh.blogwriter.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.ListRow
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.format.DateFormats
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 관리자용. 기술 용어 허용. 항목을 탭하면 상세가 클립보드에 복사된다. */
@Composable
fun FailureLogScreen(onBack: () -> Unit, viewModel: FailureLogViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ScreenScaffold(topBar = { AppTopBar(onBack = onBack, title = "실패 로그 (관리자)") }) {
        Spacer(Modifier.height(AppSpacing.lg))
        if (items.isEmpty()) Text("기록된 실패가 없습니다.", style = AppTheme.typography.body1, color = AppTheme.colors.textSecondary)
        LazyColumn {
            items(items, key = { it.id }) { item ->
                ListRow(
                    title = "[${item.stage}] ${item.message}",
                    subtitle = DateFormats.relative(item.at) + " · v${item.appVersion}",
                    onClick = {
                        val text = "${item.stage} @ ${item.at}\n${item.message}\n${item.detail}"
                        context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("failure", text))
                    },
                )
                Spacer(Modifier.height(AppSpacing.md))
            }
        }
    }
}
```

`MainActivity.setContent { AppTheme { AppNavHost() } }` 로 교체.

- [ ] **Step 6: 테스트와 빌드 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.ui.*" :app:assembleDebug`
Expected: PASS, BUILD SUCCESSFUL. 에뮬레이터에 설치해 홈 화면(제목, 로그인 배너, 발행한 글 행, 새 글 쓰기 CTA)이 보이는지 확인:
`adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.csh.blogwriter/.MainActivity`

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter app/src/test/java/com/csh/blogwriter/ui
git commit -m "Add navigation, home, history and failure log screens"
```

---

### Task 11: WebView 엔진 (NaverEditorWebView, EditorBridge, editor_bridge.js)

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/publish/NaverWebViewConfig.kt`
- Create: `app/src/main/java/com/csh/blogwriter/publish/LocalImageInterceptor.kt`
- Create: `app/src/main/java/com/csh/blogwriter/publish/EditorBridge.kt`
- Create: `app/src/main/java/com/csh/blogwriter/publish/NaverEditorWebView.kt`
- Create: `app/src/main/assets/editor_bridge.js`
- Test: `app/src/test/java/com/csh/blogwriter/publish/LocalImageInterceptorTest.kt`

**Interfaces:**
- Consumes: `PreparedImage`, `UploadedImage.fromResponse` (Task 3, 5).
- Produces:
  - `NaverWebViewConfig.apply(webView)` (데스크톱 UA 등), `NaverWebViewConfig.DESKTOP_UA`
  - `LocalImageInterceptor(images: Map<String, File>).intercept(url): WebResourceResponse?`; `LocalImageInterceptor.urlFor(ref) = "https://blog.naver.com/__app__/{ref}.jpg"`; `LocalImageInterceptor.refFromUrl(url): String?`
  - `EditorBridge.Listener { onReady(); onPopupsDismissed(count); onImageUploaded(ref, response: JsonObject); onImageFailed(ref, message); onInjected(componentCount); onError(step, message); onLog(message) }`
  - `NaverEditorWebView(context, listener: NaverEditorWebView.Listener)`: `val view: WebView`, `fun loadEditor(blogId)`, `fun setLocalImages(images: List<PreparedImage>)`, `fun installBridgeScript()`, `fun checkReady()`, `fun dismissPopups()`, `fun uploadImages(refs: List<String>)`, `fun setDocument(documentJson: String)`, `fun destroy()`. `Listener : EditorBridge.Listener { fun onUrlChanged(url); fun onPageFinished(url) }`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/publish/LocalImageInterceptorTest.kt
package com.csh.blogwriter.publish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalImageInterceptorTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun buildsAndParsesUrls() {
        assertEquals("https://blog.naver.com/__app__/img_001.jpg", LocalImageInterceptor.urlFor("img_001"))
        assertEquals("img_001", LocalImageInterceptor.refFromUrl("https://blog.naver.com/__app__/img_001.jpg"))
        assertNull(LocalImageInterceptor.refFromUrl("https://blog.naver.com/myblog?Redirect=Write"))
    }

    @Test
    fun servesKnownFilesOnly() {
        val file = folder.newFile("img_001.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val interceptor = LocalImageInterceptor(mapOf("img_001" to file))
        val response = interceptor.intercept(LocalImageInterceptor.urlFor("img_001"))
        assertNotNull(response)
        assertEquals("image/jpeg", response!!.mimeType)
        assertEquals(3, response.data.readBytes().size)
        assertNull(interceptor.intercept(LocalImageInterceptor.urlFor("img_999")))
        assertNull(interceptor.intercept("https://blogfiles.pstatic.net/x.png"))
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.publish.LocalImageInterceptorTest"`
Expected: 컴파일 실패.

- [ ] **Step 3: 설정/인터셉터/브리지 구현**

```kotlin
// publish/NaverWebViewConfig.kt
package com.csh.blogwriter.publish

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import com.csh.blogwriter.BuildConfig

object NaverWebViewConfig {
    /** 모바일 UA 면 m.blog.naver.com → 앱 설치 안내로 빠지므로 데스크톱 UA 를 강제한다 (spike/findings.md §1). */
    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
    const val LOGIN_URL = "https://nid.naver.com/nidlogin.login"
    const val MY_BLOG_URL = "https://blog.naver.com/MyBlog.naver"
    fun writeUrl(blogId: String) = "https://blog.naver.com/$blogId?Redirect=Write"

    @SuppressLint("SetJavaScriptEnabled")
    fun apply(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }
}
```

```kotlin
// publish/LocalImageInterceptor.kt
package com.csh.blogwriter.publish

import android.webkit.WebResourceResponse
import java.io.File

/** 페이지와 같은 origin(blog.naver.com)의 가짜 경로로 로컬 파일을 제공한다. JS 의 fetch 가 CORS 없이 File 을 만들 수 있다. */
class LocalImageInterceptor(private val images: Map<String, File>) {
    companion object {
        private const val PREFIX = "https://blog.naver.com/__app__/"
        fun urlFor(ref: String) = "$PREFIX$ref.jpg"
        fun refFromUrl(url: String): String? =
            if (url.startsWith(PREFIX) && url.endsWith(".jpg")) url.removePrefix(PREFIX).removeSuffix(".jpg") else null
    }

    fun intercept(url: String): WebResourceResponse? {
        val ref = refFromUrl(url) ?: return null
        val file = images[ref]?.takeIf { it.exists() } ?: return null
        return WebResourceResponse("image/jpeg", null, file.inputStream()).apply {
            responseHeaders = mapOf("Cache-Control" to "no-store")
        }
    }
}
```

```kotlin
// publish/EditorBridge.kt
package com.csh.blogwriter.publish

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject

/** JS(editor_bridge.js) → Kotlin 콜백. 모든 콜백은 메인 스레드로 전달된다. 이름은 JS 쪽 `AndroidBridge.*` 와 일치해야 한다. */
class EditorBridge(private val listener: Listener) {
    interface Listener {
        fun onReady()
        fun onPopupsDismissed(count: Int)
        fun onImageUploaded(ref: String, response: JsonObject)
        fun onImageFailed(ref: String, message: String)
        fun onInjected(componentCount: Int)
        fun onError(step: String, message: String)
        fun onLog(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private fun post(block: () -> Unit) = main.post(block)

    @JavascriptInterface fun onReady() = post { listener.onReady() }
    @JavascriptInterface fun onPopupsDismissed(count: Int) = post { listener.onPopupsDismissed(count) }
    @JavascriptInterface fun onImageUploaded(ref: String, responseJson: String) = post {
        runCatching { Json.parseToJsonElement(responseJson).jsonObject }
            .onSuccess { listener.onImageUploaded(ref, it) }
            .onFailure { listener.onImageFailed(ref, "응답 파싱 실패: ${it.message}") }
    }
    @JavascriptInterface fun onImageFailed(ref: String, message: String) = post { listener.onImageFailed(ref, message) }
    @JavascriptInterface fun onInjected(componentCount: Int) = post { listener.onInjected(componentCount) }
    @JavascriptInterface fun onError(step: String, message: String) = post { listener.onError(step, message) }
    @JavascriptInterface fun log(message: String) = post { listener.onLog(message) }
}
```

- [ ] **Step 4: NaverEditorWebView 구현**

```kotlin
// publish/NaverEditorWebView.kt
package com.csh.blogwriter.publish

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.csh.blogwriter.domain.model.PreparedImage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 스마트에디터 페이지를 띄우고 editor_bridge.js 의 함수를 호출하는 래퍼.
 * 모든 결과는 [Listener] 로 비동기 회신된다. 화면(Compose AndroidView)이 [view] 를 붙인다.
 */
class NaverEditorWebView(context: Context, private val listener: Listener) {
    interface Listener : EditorBridge.Listener {
        fun onUrlChanged(url: String)
        fun onPageFinished(url: String)
    }

    companion object { private const val TAG = "NaverEditorWebView" }

    private var interceptor = LocalImageInterceptor(emptyMap())
    private var bridgeScript: String? = null

    val view: WebView = WebView(context).also { web ->
        NaverWebViewConfig.apply(web)
        web.addJavascriptInterface(EditorBridge(listener), "AndroidBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { listener.onUrlChanged(url) }
            override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) { listener.onUrlChanged(url) }
            override fun onPageFinished(v: WebView, url: String) {
                CookieManager.getInstance().flush()
                listener.onPageFinished(url)
            }
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                interceptor.intercept(request.url.toString()) ?: super.shouldInterceptRequest(v, request)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d(TAG, "JS[${m.messageLevel()}] ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
    }

    fun loadEditor(blogId: String) = view.loadUrl(NaverWebViewConfig.writeUrl(blogId))

    fun setLocalImages(images: List<PreparedImage>) {
        interceptor = LocalImageInterceptor(images.associate { it.ref to it.file })
    }

    /** 페이지 로드 후 한 번 호출. window.__app 을 정의한다. 이미 있으면 다시 정의하지 않는다. */
    fun installBridgeScript() {
        val script = bridgeScript ?: view.context.assets.open("editor_bridge.js").bufferedReader().readText().also { bridgeScript = it }
        view.evaluateJavascript("if(!window.__app){$script}", null)
    }

    fun checkReady() = view.evaluateJavascript("window.__app && window.__app.checkReady();", null)
    fun dismissPopups() = view.evaluateJavascript("window.__app.dismissPopups();", null)

    fun uploadImages(refs: List<String>) {
        val arg = JSONArray(refs.map { JSONObject().put("ref", it).put("url", LocalImageInterceptor.urlFor(it)) })
        view.evaluateJavascript("window.__app.uploadImages($arg);", null)
    }

    fun setDocument(documentJson: String) {
        // JSON 문자열을 JS 문자열 리터럴로 안전하게 넘긴 뒤 JS 쪽에서 parse 한다.
        val literal = JSONObject.quote(documentJson)
        view.evaluateJavascript("window.__app.setDocument($literal);", null)
    }

    fun destroy() {
        view.stopLoading()
        view.removeJavascriptInterface("AndroidBridge")
        view.destroy()
    }
}
```

- [ ] **Step 5: editor_bridge.js 작성**

```javascript
// app/src/main/assets/editor_bridge.js
// 스마트에디터 ONE 내부 API 호출. 규칙은 spike/findings.md §2~§4. 모든 결과는 AndroidBridge 콜백으로 회신한다.
window.__app = (function () {
  var B = window.AndroidBridge;
  function log(m) { try { B.log(String(m)); } catch (e) {} }
  function err(step, e) { try { B.onError(step, (e && (e.stack || e.message)) || (function () { try { return JSON.stringify(e); } catch (_) { return String(e); } })()); } catch (_) {} }
  function frameWin() { var f = document.querySelector('#mainFrame'); return (f && f.contentWindow) || window; }
  function editor() { var w = frameWin(); var eds = w.SmartEditor && w.SmartEditor._editors; if (!eds) return null; var ids = Object.keys(eds); return ids.length ? eds[ids[0]] : null; }
  function uid() { return 'SE-' + (window.crypto && crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random().toString(16).slice(2)); }

  function checkReady() {
    try {
      var ed = editor();
      if (ed && typeof ed.setDocumentData === 'function' && ed._videoUploadService && ed._videoUploadService._imageUploadService) { B.onReady(); return true; }
      return false;
    } catch (e) { err('ready', e); return false; }
  }

  // "작성 중인 글이 있습니다" → 취소(새 글), 도움말 → 닫기. 없으면 0.
  function dismissPopups() {
    try {
      var doc = frameWin().document, count = 0;
      ['취소', '닫기'].forEach(function (label) {
        var nodes = Array.prototype.slice.call(doc.querySelectorAll('button, a'));
        nodes.filter(function (n) { return (n.innerText || '').trim() === label; }).forEach(function (n) { n.click(); count++; });
      });
      B.onPopupsDismissed(count);
    } catch (e) { err('popups', e); }
  }

  // items: [{ref, url}] — url 은 앱이 shouldInterceptRequest 로 제공하는 같은 origin 경로
  function uploadImages(items) {
    var ed = editor(); if (!ed) { err('upload', 'editor missing'); return; }
    var svc = ed._videoUploadService._imageUploadService;
    (async function () {
      for (var i = 0; i < items.length; i++) {
        var item = items[i];
        try {
          var blob = await (await fetch(item.url, { cache: 'no-store' })).blob();
          var file = new File([blob], item.ref + '.jpg', { type: 'image/jpeg', lastModified: Date.now() });
          var list = svc.createSourceList([item.ref], [file]);
          var pending = await svc.uploadImagesFromFiles(list);      // Promise<Promise[]> — 두 번 await
          var results = await Promise.all(Array.isArray(pending) ? pending : [pending]);
          var r = results[0];
          if (!r || r.code !== 'SUCCESS' || !r.response || !r.response.url) { B.onImageFailed(item.ref, JSON.stringify(r && (r.response || r))); return; }
          var resp = r.response;
          B.onImageUploaded(item.ref, JSON.stringify({ url: resp.url, path: resp.path, fileName: resp.fileName, width: resp.width, height: resp.height, fileSize: resp.fileSize, domain: resp.domain }));
        } catch (e) {
          B.onImageFailed(item.ref, (e && (e.message || JSON.stringify(e))) || String(e));
          return;
        }
      }
    })();
  }

  function setDocument(json) {
    try {
      var ed = editor(); if (!ed) { err('inject', 'editor missing'); return; }
      var doc = JSON.parse(json);
      var before = ed.getDocumentData();
      doc.document.version = before.document.version || doc.document.version;
      doc.document.id = before.document.id || doc.document.id;
      var r = ed.setDocumentData(doc);
      Promise.resolve(r).then(function () {
        setTimeout(function () {
          try { B.onInjected(ed.getDocumentData().document.components.length); } catch (e) { err('inject', e); }
        }, 800);
      }).catch(function (e) { err('inject', e); });
    } catch (e) { err('inject', e); }
  }

  return { checkReady: checkReady, dismissPopups: dismissPopups, uploadImages: uploadImages, setDocument: setDocument, uid: uid };
})();
```

- [ ] **Step 6: 테스트와 빌드 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.publish.LocalImageInterceptorTest" :app:assembleDebug`
Expected: PASS, BUILD SUCCESSFUL.

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/publish app/src/main/assets app/src/test/java/com/csh/blogwriter/publish
git commit -m "Add Naver editor WebView engine and JS bridge"
```

---

### Task 12: 로그인 화면과 세션 (FR-1.1, FR-1.2)

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/ui/login/LoginViewModel.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/login/LoginScreen.kt`
- Create: `app/src/main/java/com/csh/blogwriter/session/NaverSession.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/ui/navigation/AppNavHost.kt` (Login 목적지 교체)
- Test: `app/src/test/java/com/csh/blogwriter/ui/login/LoginViewModelTest.kt`

**Interfaces:**
- Consumes: `BlogIdResolver` (Task 4), `SettingsStore` (Task 8), `NaverWebViewConfig` (Task 11), `Routes.Login(returnTo)` (Task 10).
- Produces: `LoginPhase { LoggingIn, ResolvingBlogId, Done(blogId), Error(message) }`, `LoginViewModel.phase: StateFlow<LoginPhase>`, `LoginViewModel.onUrlChanged(url)`, `LoginViewModel.urlToLoad: StateFlow<String>`; `NaverSession.logout()`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/ui/login/LoginViewModelTest.kt
package com.csh.blogwriter.ui.login

import com.csh.blogwriter.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val stored = MutableStateFlow<String?>(null)
    private val settings = object : SettingsStore {
        override val blogId: Flow<String?> = stored
        override suspend fun setBlogId(id: String?) { stored.value = id }
    }
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun loginThenResolveBlogId() = runTest {
        val vm = LoginViewModel(settings)
        assertEquals(LoginPhase.LoggingIn, vm.phase.value)
        assertEquals("https://nid.naver.com/nidlogin.login", vm.urlToLoad.value)

        vm.onUrlChanged("https://nid.naver.com/nidlogin.login?mode=form")
        assertEquals(LoginPhase.LoggingIn, vm.phase.value)

        vm.onUrlChanged("https://www.naver.com/")
        assertEquals(LoginPhase.ResolvingBlogId, vm.phase.value)
        assertEquals("https://blog.naver.com/MyBlog.naver", vm.urlToLoad.value)

        vm.onUrlChanged("https://blog.naver.com/MyBlog.naver")
        vm.onUrlChanged("https://blog.naver.com/myblog")
        advanceUntilIdle()
        assertEquals(LoginPhase.Done("myblog"), vm.phase.value)
        assertEquals("myblog", stored.value)
    }

    @Test
    fun loginPageAfterResolvingMeansSessionDidNotStick() = runTest {
        val vm = LoginViewModel(settings)
        vm.onUrlChanged("https://www.naver.com/")
        vm.onUrlChanged("https://nid.naver.com/nidlogin.login?url=blog")
        assertEquals(LoginPhase.LoggingIn, vm.phase.value)
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.ui.login.LoginViewModelTest"`
Expected: 컴파일 실패.

- [ ] **Step 3: ViewModel 과 세션 구현**

```kotlin
// ui/login/LoginViewModel.kt
package com.csh.blogwriter.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.publish.NaverWebViewConfig
import com.csh.blogwriter.publish.PublishUrlParser
import com.csh.blogwriter.session.BlogIdResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LoginPhase {
    data object LoggingIn : LoginPhase
    data object ResolvingBlogId : LoginPhase
    data class Done(val blogId: String) : LoginPhase
}

/**
 * 로그인 페이지에서 nid.naver.com 밖으로 나가면 로그인 성공 → MyBlog.naver 를 로드해 리다이렉트 URL 에서 blogId 를 얻는다.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(private val settings: SettingsStore) : ViewModel() {
    private val _phase = MutableStateFlow<LoginPhase>(LoginPhase.LoggingIn)
    val phase: StateFlow<LoginPhase> = _phase
    private val _urlToLoad = MutableStateFlow(NaverWebViewConfig.LOGIN_URL)
    val urlToLoad: StateFlow<String> = _urlToLoad

    fun onUrlChanged(url: String) {
        when (val p = _phase.value) {
            LoginPhase.LoggingIn -> if (!PublishUrlParser.isLoginPage(url) && url.startsWith("https://")) {
                _phase.value = LoginPhase.ResolvingBlogId
                _urlToLoad.value = NaverWebViewConfig.MY_BLOG_URL
            }
            LoginPhase.ResolvingBlogId -> {
                if (PublishUrlParser.isLoginPage(url)) { _phase.value = LoginPhase.LoggingIn; _urlToLoad.value = NaverWebViewConfig.LOGIN_URL; return }
                val id = BlogIdResolver.fromUrl(url) ?: return
                _phase.value = LoginPhase.Done(id)
                viewModelScope.launch { settings.setBlogId(id) }
            }
            is LoginPhase.Done -> Unit
        }
    }
}
```

```kotlin
// session/NaverSession.kt
package com.csh.blogwriter.session

import android.webkit.CookieManager
import com.csh.blogwriter.data.prefs.SettingsStore
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class NaverSession @Inject constructor(private val settings: SettingsStore) {
    suspend fun logout() {
        suspendCancellableCoroutine { cont -> CookieManager.getInstance().removeAllCookies { cont.resume(Unit) } }
        CookieManager.getInstance().flush()
        settings.setBlogId(null)
    }
}
```

- [ ] **Step 4: 로그인 화면**

```kotlin
// ui/login/LoginScreen.kt
package com.csh.blogwriter.ui.login

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.publish.NaverWebViewConfig
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.ProgressScreen
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun LoginScreen(onBack: () -> Unit, onDone: (blogId: String) -> Unit, viewModel: LoginViewModel = hiltViewModel()) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val url by viewModel.urlToLoad.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val webView = remember {
        WebView(context).also { web ->
            NaverWebViewConfig.apply(web)
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, u: String, favicon: Bitmap?) { viewModel.onUrlChanged(u) }
                override fun doUpdateVisitedHistory(v: WebView, u: String, isReload: Boolean) { viewModel.onUrlChanged(u) }
                override fun onPageFinished(v: WebView, u: String) { CookieManager.getInstance().flush() }
            }
        }
    }
    LaunchedEffect(url) { webView.loadUrl(url) }
    LaunchedEffect(phase) { (phase as? LoginPhase.Done)?.let { onDone(it.blogId) } }

    when (phase) {
        LoginPhase.LoggingIn -> ScreenScaffold(topBar = { AppTopBar(onBack = onBack) }) {
            Text("네이버에 로그인해 주세요", style = AppTheme.typography.title2, color = AppTheme.colors.textPrimary)
            Spacer(Modifier.height(AppSpacing.md))
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize().padding(bottom = AppSpacing.lg))
        }
        else -> {
            // ResolvingBlogId / Done: 블로그 정보를 읽는 동안 WebView 는 숨기고 진행 화면만 보여준다.
            ProgressScreen(title = "블로그 정보를 확인하고 있어요", detail = null, progress = null)
            AndroidView(factory = { webView }, modifier = Modifier.height(0.dp))
        }
    }
}
```
(`import androidx.compose.ui.unit.dp` 추가.)

`AppNavHost` 의 Login 목적지:
```kotlin
composable<Routes.Login> { entry ->
    val returnTo = entry.toRoute<Routes.Login>().returnTo
    LoginScreen(
        onBack = { nav.popBackStack() },
        onDone = {
            nav.popBackStack()
            when {
                returnTo == "compose" -> nav.navigate(Routes.TestCompose)
                returnTo?.startsWith("publish:") == true -> nav.navigate(Routes.Publish(returnTo.removePrefix("publish:")))
            }
        },
    )
}
```

- [ ] **Step 5: 테스트와 에뮬레이터 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.ui.login.LoginViewModelTest" :app:assembleDebug`
Expected: PASS. 에뮬레이터: 홈 → "네이버 로그인이 필요해요" 배너 → 로그인 화면에서 로그인 → 홈으로 복귀하고 배너가 사라진다. `adb logcat -s NaverEditorWebView` 로 오류 없음 확인.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/ui/login app/src/main/java/com/csh/blogwriter/session app/src/main/java/com/csh/blogwriter/ui/navigation app/src/test/java/com/csh/blogwriter/ui/login
git commit -m "Add Naver login screen with blog id resolution"
```

---

### Task 13: 테스트 글 작성 화면 (SP1 임시)

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/ui/compose/TestPostBuilder.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/compose/TestComposeViewModel.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/compose/TestComposeScreen.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/components/PhotoGrid.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/ui/navigation/AppNavHost.kt`
- Test: `app/src/test/java/com/csh/blogwriter/ui/compose/TestPostBuilderTest.kt`

**Interfaces:**
- Consumes: `PostContent`, `Block`, `Run` (Task 3), `PendingJobRepository`, `PendingJob` (Task 8), 컴포넌트 (Task 2).
- Produces: `TestPostBuilder.build(title, body, imageCount): PostContent`, `TestComposeViewModel { title, body, photos: StateFlow<List<Uri>>; setTitle; setBody; addPhotos; removePhoto; movePhoto(from,to); suspend fun createJob(): String }`, `PhotoGrid(uris, onRemove, onMoveUp, onMoveDown)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/ui/compose/TestPostBuilderTest.kt
package com.csh.blogwriter.ui.compose

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class TestPostBuilderTest {
    @Test
    fun splitsParagraphsOnBlankLinesAndInterleavesImages() {
        val content = TestPostBuilder.build("제목", "첫 문단\n둘째 줄\n\n두 번째 문단\n\n\n세 번째", imageCount = 2)
        assertEquals("제목", content.title)
        assertEquals(listOf(
            Block.Paragraph(listOf(Run("첫 문단\n둘째 줄"))),
            Block.Image("img_001"),
            Block.Paragraph(listOf(Run("두 번째 문단"))),
            Block.Image("img_002"),
            Block.Paragraph(listOf(Run("세 번째"))),
        ), content.blocks)
    }

    @Test
    fun extraImagesGoToTheEndAndEmptyBodyStillWorks() {
        val content = TestPostBuilder.build("t", "하나", imageCount = 3)
        assertEquals(listOf("paragraph", "image", "image", "image"), content.blocks.map { it.kind() })
        assertEquals(listOf(Block.Image("img_001")), TestPostBuilder.build("t", "  ", imageCount = 1).blocks)
    }

    private fun Block.kind() = when (this) { is Block.Paragraph -> "paragraph"; is Block.Image -> "image"; is Block.Quote -> "quote" }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.ui.compose.TestPostBuilderTest"`
Expected: 컴파일 실패.

- [ ] **Step 3: Builder 와 ViewModel 구현**

```kotlin
// ui/compose/TestPostBuilder.kt
package com.csh.blogwriter.ui.compose

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run

/** 빈 줄로 문단을 나누고, 문단 사이에 사진을 하나씩 끼운다. 남는 사진은 끝에 붙인다. */
object TestPostBuilder {
    fun build(title: String, body: String, imageCount: Int): PostContent {
        val paragraphs = body.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val blocks = mutableListOf<Block>()
        var nextImage = 1
        fun image(): Block = Block.Image("img_%03d".format(nextImage++))
        paragraphs.forEach { p ->
            blocks += Block.Paragraph(listOf(Run(p)))
            if (nextImage <= imageCount) blocks += image()
        }
        while (nextImage <= imageCount) blocks += image()
        return PostContent(title.trim(), blocks)
    }
}
```

```kotlin
// ui/compose/TestComposeViewModel.kt
package com.csh.blogwriter.ui.compose

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TestComposeViewModel @Inject constructor(private val pendingJobs: PendingJobRepository) : ViewModel() {
    val title = MutableStateFlow("")
    val body = MutableStateFlow("")
    private val _photos = MutableStateFlow<List<Uri>>(emptyList())
    val photos: StateFlow<List<Uri>> = _photos

    fun addPhotos(uris: List<Uri>) = _photos.update { (it + uris).distinct() }
    fun removePhoto(uri: Uri) = _photos.update { it - uri }
    fun movePhoto(from: Int, to: Int) = _photos.update { list ->
        if (from !in list.indices || to !in list.indices) list else list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** PendingJob 을 저장하고 id 를 돌려준다. 발행 화면은 이 id 로 작업을 읽는다. */
    suspend fun createJob(): String {
        val id = UUID.randomUUID().toString()
        val content = TestPostBuilder.build(title.value, body.value, _photos.value.size)
        pendingJobs.save(PendingJob(id, content, _photos.value.map { it.toString() }, null, System.currentTimeMillis(), null))
        return id
    }
}
```

- [ ] **Step 4: PhotoGrid 와 화면**

```kotlin
// ui/components/PhotoGrid.kt
package com.csh.blogwriter.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 선택한 사진 그리드. 순서 배지 + 삭제/앞으로/뒤로 버튼(드래그 대신). 높이는 내용에 맞춰 고정. */
@Composable
fun PhotoGrid(uris: List<Uri>, onRemove: (Uri) -> Unit, onMove: (from: Int, to: Int) -> Unit, columns: Int = 3) {
    val rows = (uris.size + columns - 1) / columns
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxWidth().aspectRatio(columns.toFloat() / rows.coerceAtLeast(1) * 0.8f),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        userScrollEnabled = false,
    ) {
        itemsIndexed(uris, key = { _, u -> u.toString() }) { index, uri ->
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(AppSpacing.radiusThumb))) {
                    AsyncImage(model = uri, contentDescription = "사진 ${index + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    Box(Modifier.padding(AppSpacing.sm).size(28.dp).clip(CircleShape).background(AppTheme.colors.fillBrand), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", style = AppTheme.typography.body2, color = AppTheme.colors.textOnBrand)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) { Icon(Icons.Rounded.ArrowBack, contentDescription = "앞으로") }
                    IconButton(onClick = { onRemove(uri) }) { Icon(Icons.Rounded.Close, contentDescription = "빼기", tint = AppTheme.colors.fillDanger) }
                    IconButton(onClick = { onMove(index, index + 1) }, enabled = index < uris.lastIndex) { Icon(Icons.Rounded.ArrowForward, contentDescription = "뒤로") }
                }
            }
        }
    }
}
```

```kotlin
// ui/compose/TestComposeScreen.kt
package com.csh.blogwriter.ui.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.ui.components.AppTextField
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.BottomCta
import com.csh.blogwriter.ui.components.PhotoGrid
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.components.WeakButton
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme
import kotlinx.coroutines.launch

/** SP1 임시 화면: 제목/본문/사진을 손으로 넣어 발행 파이프라인을 시험한다. SP2 에서 4단계 글쓰기 흐름으로 대체된다. */
@Composable
fun TestComposeScreen(onBack: () -> Unit, onPublish: (jobId: String) -> Unit, viewModel: TestComposeViewModel = hiltViewModel()) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    val body by viewModel.body.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)) { uris -> viewModel.addPhotos(uris) }

    ScreenScaffold(
        topBar = { AppTopBar(onBack = onBack) },
        bottom = {
            BottomCta("발행하러 가기", enabled = title.isNotBlank() && (body.isNotBlank() || photos.isNotEmpty()), loading = creating, onClick = {
                creating = true
                scope.launch { onPublish(viewModel.createJob()) }
            })
        },
    ) {
        Spacer(Modifier.height(AppSpacing.lg))
        Text("테스트 글을 써 볼까요?", style = AppTheme.typography.title1, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(AppSpacing.xxl))
        androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
            AppTextField(value = title, onValueChange = { viewModel.title.value = it }, label = "제목", placeholder = "오늘의 이야기")
            Spacer(Modifier.height(AppSpacing.xl))
            AppTextField(value = body, onValueChange = { viewModel.body.value = it }, label = "본문 (빈 줄로 문단을 나눠요)", placeholder = "여기에 글을 써 주세요", singleLine = false, minLines = 6)
            Spacer(Modifier.height(AppSpacing.xl))
            Text("사진 ${photos.size}장", style = AppTheme.typography.title3, color = AppTheme.colors.textPrimary)
            Spacer(Modifier.height(AppSpacing.md))
            if (photos.isNotEmpty()) { PhotoGrid(photos, onRemove = viewModel::removePhoto, onMove = viewModel::movePhoto); Spacer(Modifier.height(AppSpacing.md)) }
            WeakButton("사진 고르기", onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            Spacer(Modifier.height(AppSpacing.section))
        }
    }
}
```

`AppNavHost`: `composable<Routes.TestCompose> { TestComposeScreen(onBack = { nav.popBackStack() }, onPublish = { id -> nav.navigate(Routes.Publish(id)) { popUpTo(Routes.Home) } }) }`

- [ ] **Step 5: 테스트/빌드/에뮬레이터 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.ui.compose.TestPostBuilderTest" :app:assembleDebug`
Expected: PASS. 에뮬레이터: 새 글 쓰기 → 제목/본문 입력, 사진 고르기(에뮬레이터 갤러리에 사진이 없으면 `adb push` 로 `/sdcard/Pictures/` 에 jpg 2장 넣고 `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/a.jpg`) → 발행하러 가기 → "준비 중: 발행 {id}" 자리표시가 보인다.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/ui app/src/test/java/com/csh/blogwriter/ui/compose
git commit -m "Add temporary test compose screen with photo picker"
```

---

### Task 14: 발행 화면과 PublishViewModel

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/ui/publish/EditorController.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/publish/PublishViewModel.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/publish/PublishPanel.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/publish/PublishScreen.kt` (SP1 임시 전체 화면 래퍼)
- Modify: `app/src/main/java/com/csh/blogwriter/ui/navigation/AppNavHost.kt`
- Test: `app/src/test/java/com/csh/blogwriter/ui/publish/PublishViewModelTest.kt`

**Interfaces:**
- Consumes: `PublishStateMachine`, `PublishState/Event/Effect` (Task 7), `ImagePreparer` (Task 9), `DocumentModelConverter`, `UploadedImage` (Task 5), `NaverEditorWebView` (Task 11), 리포지토리/SettingsStore (Task 8), `PendingJob`.
- Produces: `EditorController` 인터페이스 (WebView 추상화 — 테스트에서 가짜로 대체), `PublishUiState(state: PublishState, title: String)`, `PublishViewModel(jobId 는 SavedStateHandle 로)`: `uiState`, `attach(controller)`, `detach()`, `onRetry()`, `onLeave()`; 내비게이션 신호 `navigation: SharedFlow<PublishNav>` with `PublishNav.SessionExpired(jobId)`, `PublishNav.Failed(jobId)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/ui/publish/PublishViewModelTest.kt
package com.csh.blogwriter.ui.publish

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.FailureLogRepository
import com.csh.blogwriter.data.repo.HistoryRepository
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.data.repo.PublishHistoryItem
import com.csh.blogwriter.data.repo.FailureLogItem
import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.PreparedImage
import com.csh.blogwriter.domain.model.Run
import com.csh.blogwriter.domain.publish.PublishStage
import com.csh.blogwriter.domain.publish.PublishState
import com.csh.blogwriter.publish.DocumentModelConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PublishViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val content = PostContent("제목", listOf(Block.Paragraph(listOf(Run("본문"))), Block.Image("img_001")))
    private val job = PendingJob("job1", content, listOf("content://a"), null, 1L, null)

    private val pending = MutableStateFlow<PendingJob?>(job)
    private val history = mutableListOf<PublishHistoryItem>()
    private val failures = mutableListOf<FailureLogItem>()

    private val pendingRepo = object : PendingJobRepository {
        override fun observeLatest(): Flow<PendingJob?> = pending
        override suspend fun get(id: String) = pending.value?.takeIf { it.id == id }
        override suspend fun save(job: PendingJob) { pending.value = job }
        override suspend fun setPreparedPaths(id: String, paths: List<String>?) { pending.value = pending.value?.copy(preparedPaths = paths) }
        override suspend fun setLastFailure(id: String, message: String?) { pending.value = pending.value?.copy(lastFailure = message) }
        override suspend fun delete(id: String) { pending.value = null }
    }
    private val historyRepo = object : HistoryRepository {
        override fun observeAll() = flowOf(history.toList())
        override suspend fun add(title: String, logNo: String, url: String, imageCount: Int) { history += PublishHistoryItem(1, title, logNo, url, 0, imageCount) }
    }
    private val failureRepo = object : FailureLogRepository {
        override fun observeAll() = flowOf(failures.toList())
        override suspend fun add(stage: String, message: String, detail: String) { failures += FailureLogItem(1, 0, stage, message, detail, "t") }
    }
    private val settings = object : SettingsStore {
        override val blogId: Flow<String?> = MutableStateFlow("myblog")
        override suspend fun setBlogId(id: String?) {}
    }
    private val preparer = object : ImagePreparing {
        override suspend fun prepare(jobId: String, uris: List<String>, onProgress: (Int) -> Unit) = uris.mapIndexed { i, _ -> PreparedImage("img_%03d".format(i + 1), File("/tmp/img.jpg"), 800, 600).also { onProgress(i + 1) } }
        override fun load(jobId: String, paths: List<String>) = null
        override fun clear(jobId: String) {}
    }

    class FakeController : EditorController {
        val calls = mutableListOf<String>()
        override fun loadEditor(blogId: String) { calls += "load:$blogId" }
        override fun setLocalImages(images: List<PreparedImage>) { calls += "images:${images.size}" }
        override fun installBridgeScript() { calls += "install" }
        override fun checkReady() { calls += "ready?" }
        override fun dismissPopups() { calls += "popups" }
        override fun uploadImages(refs: List<String>) { calls += "upload:${refs.joinToString(",")}" }
        override fun setDocument(documentJson: String) { calls += "inject" }
    }

    private fun vm() = PublishViewModel(SavedStateHandle(mapOf("jobId" to "job1")), pendingRepo, historyRepo, failureRepo, settings, preparer, DocumentModelConverter())

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun happyPathDrivesControllerAndSavesHistory() = runTest {
        val vm = vm(); val c = FakeController()
        vm.attach(c); advanceUntilIdle()
        assertEquals(PublishState.LoadingEditor, vm.uiState.value.state)
        assertEquals(listOf("images:1", "load:myblog"), c.calls)

        vm.onUrlChanged("https://blog.naver.com/myblog?Redirect=Write"); vm.onPageFinished("https://blog.naver.com/myblog?Redirect=Write&categoryNo=25"); advanceUntilIdle()
        assertTrue(c.calls.contains("install")); assertTrue(c.calls.contains("ready?"))
        vm.onReady(); advanceUntilIdle()
        assertEquals(PublishState.DismissingPopups, vm.uiState.value.state); assertTrue(c.calls.contains("popups"))
        vm.onPopupsDismissed(0); advanceUntilIdle()
        assertEquals(PublishState.UploadingImages(0, 1), vm.uiState.value.state); assertTrue(c.calls.contains("upload:img_001"))
        vm.onImageUploaded("img_001", Json.parseToJsonElement("""{"url":"/a/b.PNG/img_001.jpg","fileName":"img_001.jpg","width":800,"height":600,"fileSize":1,"domain":"https://blogfiles.pstatic.net"}""").jsonObject); advanceUntilIdle()
        assertEquals(PublishState.Injecting, vm.uiState.value.state); assertTrue(c.calls.contains("inject"))
        vm.onInjected(3); advanceUntilIdle()
        assertEquals(PublishState.Reviewing, vm.uiState.value.state)
        vm.onUrlChanged("https://blog.naver.com/PostView.naver?blogId=myblog&logNo=99&isAfterWrite=true"); advanceUntilIdle()
        assertEquals(PublishState.Published("99", "https://blog.naver.com/myblog/99"), vm.uiState.value.state)
        assertEquals("제목", history.single().title); assertEquals(1, history.single().imageCount)
        assertNull(pending.value)
    }

    @Test
    fun loginRedirectSavesPendingAndSignalsSessionExpired() = runTest {
        val vm = vm(); val c = FakeController()
        vm.navigation.test {
            vm.attach(c); advanceUntilIdle()
            vm.onUrlChanged("https://nid.naver.com/nidlogin.login?url=x"); advanceUntilIdle()
            assertEquals(PublishNav.SessionExpired("job1"), awaitItem())
        }
        assertEquals(PublishState.SessionExpired, vm.uiState.value.state)
        assertEquals(listOf("/tmp/img.jpg"), pending.value!!.preparedPaths)
    }

    @Test
    fun editorReadyTimeoutFailsAndLogs() = runTest {
        val vm = vm(); val c = FakeController()
        vm.navigation.test {
            vm.attach(c); advanceUntilIdle()
            vm.onPageFinished("https://blog.naver.com/myblog?Redirect=Write&categoryNo=25")
            advanceTimeBy(PublishViewModel.EDITOR_READY_TIMEOUT_MS + 1_000); advanceUntilIdle()
            assertEquals(PublishNav.Failed("job1"), awaitItem())
        }
        val failed = vm.uiState.value.state as PublishState.Failed
        assertEquals(PublishStage.LOAD_EDITOR, failed.stage)
        assertEquals("LOAD_EDITOR", failures.single().stage)
        assertEquals(failed.message, pending.value!!.lastFailure)
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.ui.publish.PublishViewModelTest"`
Expected: 컴파일 실패.

- [ ] **Step 3: EditorController 와 ImagePreparing 추상화**

```kotlin
// ui/publish/EditorController.kt
package com.csh.blogwriter.ui.publish

import com.csh.blogwriter.domain.model.PreparedImage

/** ViewModel 이 WebView 에 내리는 명령. 실제 구현은 NaverEditorWebView, 테스트는 가짜. */
interface EditorController {
    fun loadEditor(blogId: String)
    fun setLocalImages(images: List<PreparedImage>)
    fun installBridgeScript()
    fun checkReady()
    fun dismissPopups()
    fun uploadImages(refs: List<String>)
    fun setDocument(documentJson: String)
}

/** ImagePreparer 의 테스트용 추상화. Uri 는 문자열로 받는다. */
interface ImagePreparing {
    suspend fun prepare(jobId: String, uris: List<String>, onProgress: (Int) -> Unit): List<PreparedImage>
    fun load(jobId: String, paths: List<String>): List<PreparedImage>?
    fun clear(jobId: String)
}
```

`ImagePreparer`(Task 9)가 `ImagePreparing` 을 구현하도록 수정: `class ImagePreparer @Inject constructor(...) : ImagePreparing` 에 오버로드 추가
```kotlin
override suspend fun prepare(jobId: String, uris: List<String>, onProgress: (Int) -> Unit): List<PreparedImage> =
    prepare(jobId, uris.map(Uri::parse), onProgress)
```
(기존 `prepare(jobId, List<Uri>, ...)` 는 유지, `load`/`clear` 에 `override` 추가.) Hilt 바인딩: `di/DataModule.kt` 에 `@Binds abstract fun imagePreparing(impl: ImagePreparer): ImagePreparing` 추가.

`NaverEditorWebView`(Task 11)에 `: EditorController` 를 붙이고 해당 메서드에 `override` 를 추가한다.

- [ ] **Step 4: PublishViewModel 구현**

```kotlin
// ui/publish/PublishViewModel.kt
package com.csh.blogwriter.ui.publish

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.FailureLogRepository
import com.csh.blogwriter.data.repo.HistoryRepository
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.domain.model.PreparedImage
import com.csh.blogwriter.domain.publish.PublishEffect
import com.csh.blogwriter.domain.publish.PublishEvent
import com.csh.blogwriter.domain.publish.PublishStage
import com.csh.blogwriter.domain.publish.PublishState
import com.csh.blogwriter.domain.publish.PublishStateMachine
import com.csh.blogwriter.publish.DocumentModelConverter
import com.csh.blogwriter.publish.UploadedImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

data class PublishUiState(val state: PublishState = PublishState.Idle, val title: String = "", val lastUrl: String = "")

sealed interface PublishNav {
    data class SessionExpired(val jobId: String) : PublishNav
    data class Failed(val jobId: String) : PublishNav
}

/**
 * 발행 흐름 조정자. 상태 결정은 [PublishStateMachine], 부수효과(WebView 명령, 저장, 타임아웃)는 여기서.
 * WebView 콜백(onUrlChanged, onReady, …)은 화면이 [NaverEditorWebView.Listener] 로 연결해 준다.
 */
@HiltViewModel
class PublishViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val pendingJobs: PendingJobRepository,
    private val history: HistoryRepository,
    private val failures: FailureLogRepository,
    private val settings: SettingsStore,
    private val preparer: ImagePreparing,
    private val converter: DocumentModelConverter,
) : ViewModel() {

    companion object {
        const val EDITOR_READY_TIMEOUT_MS = 30_000L
        const val READY_POLL_MS = 500L
        const val POPUP_TIMEOUT_MS = 5_000L
        const val UPLOAD_TIMEOUT_PER_IMAGE_MS = 60_000L
        const val INJECT_TIMEOUT_MS = 15_000L
    }

    private val jobId: String = checkNotNull(savedState["jobId"])
    private val _uiState = MutableStateFlow(PublishUiState())
    val uiState: StateFlow<PublishUiState> = _uiState
    private val _navigation = MutableSharedFlow<PublishNav>(extraBufferCapacity = 1)
    val navigation: SharedFlow<PublishNav> = _navigation

    private var controller: EditorController? = null
    private var job: PendingJob? = null
    private var machine: PublishStateMachine? = null
    private var images: List<PreparedImage> = emptyList()
    private val uploaded = mutableMapOf<String, UploadedImage>()
    private var timeoutJob: Job? = null
    private var pollJob: Job? = null

    fun attach(controller: EditorController) {
        this.controller = controller
        if (job == null) viewModelScope.launch { start() }
    }

    fun detach() { controller = null; pollJob?.cancel(); timeoutJob?.cancel() }

    private suspend fun start() {
        val loaded = pendingJobs.get(jobId) ?: run {
            dispatch(PublishEvent.JsError(PublishStage.PREPARE, "작업을 찾을 수 없음: $jobId")); return
        }
        job = loaded
        machine = PublishStateMachine(totalImages = loaded.content.imageRefs().size, expectedComponents = DocumentModelConverter.expectedComponentCount(loaded.content))
        _uiState.update { it.copy(title = loaded.content.title) }
        dispatch(PublishEvent.Start)
    }

    // ---- WebView 콜백 (NaverEditorWebView.Listener 가 위임) ----
    fun onUrlChanged(url: String) { _uiState.update { it.copy(lastUrl = url) }; dispatch(PublishEvent.UrlChanged(url)) }
    fun onPageFinished(url: String) { dispatch(PublishEvent.PageLoaded(url)) }
    fun onReady() { pollJob?.cancel(); dispatch(PublishEvent.EditorReady) }
    fun onPopupsDismissed(count: Int) { dispatch(PublishEvent.PopupsDismissed) }
    fun onImageUploaded(ref: String, response: JsonObject) {
        runCatching { UploadedImage.fromResponse(ref, response) }
            .onSuccess { uploaded[ref] = it; dispatch(PublishEvent.ImageUploaded(ref)) }
            .onFailure { dispatch(PublishEvent.ImageFailed(ref, "응답 해석 실패: ${it.message}")) }
    }
    fun onImageFailed(ref: String, message: String) = dispatch(PublishEvent.ImageFailed(ref, message))
    fun onInjected(componentCount: Int) = dispatch(PublishEvent.Injected(componentCount))
    fun onJsError(step: String, message: String) {
        val stage = when (step) { "ready" -> PublishStage.LOAD_EDITOR; "popups" -> PublishStage.DISMISS_POPUPS; "upload" -> PublishStage.UPLOAD; else -> PublishStage.INJECT }
        dispatch(PublishEvent.JsError(stage, message))
    }
    fun onRetry() { uploaded.clear(); dispatch(PublishEvent.Retry) }

    // ---- 상태 기계 구동 ----
    private fun dispatch(event: PublishEvent) {
        val m = machine ?: return
        val current = _uiState.value.state
        val (next, effects) = m.reduce(current, event)
        if (next != current) { _uiState.update { it.copy(state = next) }; timeoutJob?.cancel() }
        effects.forEach { runEffect(it) }
    }

    private fun runEffect(effect: PublishEffect) {
        val c = controller
        when (effect) {
            PublishEffect.PrepareImages -> viewModelScope.launch { prepareImages() }
            PublishEffect.LoadEditor -> { c?.loadEditor(blogIdOrFail() ?: return); armTimeout(PublishStage.LOAD_EDITOR, EDITOR_READY_TIMEOUT_MS) }
            PublishEffect.StartReadyPolling -> startReadyPolling()
            PublishEffect.DismissPopups -> { c?.dismissPopups(); armTimeout(PublishStage.DISMISS_POPUPS, POPUP_TIMEOUT_MS) }
            PublishEffect.UploadImages -> { c?.uploadImages(images.map { it.ref }); armTimeout(PublishStage.UPLOAD, UPLOAD_TIMEOUT_PER_IMAGE_MS * images.size) }
            PublishEffect.Inject -> inject()
            PublishEffect.ShowEditor -> Unit
            is PublishEffect.SavePublished -> viewModelScope.launch {
                val j = job ?: return@launch
                history.add(j.content.title, effect.logNo, effect.url, images.size)
                pendingJobs.delete(j.id)
                preparer.clear(j.id)
            }
            PublishEffect.SavePending -> viewModelScope.launch {
                pendingJobs.setPreparedPaths(jobId, images.map { it.file.absolutePath }.takeIf { it.isNotEmpty() })
                _navigation.emit(PublishNav.SessionExpired(jobId))
            }
            is PublishEffect.LogFailure -> viewModelScope.launch {
                failures.add(effect.stage.name, effect.message, "url=${_uiState.value.lastUrl}")
                pendingJobs.setLastFailure(jobId, effect.message)
                _navigation.emit(PublishNav.Failed(jobId))
            }
        }
    }

    private fun blogIdOrFail(): String? = job?.let { _ -> null } ?: null // 대체됨 아래에서

    private suspend fun prepareImages() {
        val j = job ?: return
        val reused = j.preparedPaths?.let { preparer.load(j.id, it) }
        images = try {
            reused ?: preparer.prepare(j.id, j.imageUris) { done -> dispatch(PublishEvent.ImagePrepared(done)) }
        } catch (e: Exception) {
            dispatch(PublishEvent.JsError(PublishStage.PREPARE, "사진 준비 실패: ${e.message}")); return
        }
        controller?.setLocalImages(images)
        dispatch(PublishEvent.ImagesPrepared)
    }

    private fun startReadyPolling() {
        val c = controller ?: return
        c.installBridgeScript()
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) { c.checkReady(); delay(READY_POLL_MS) }
        }
    }

    private fun inject() {
        val j = job ?: return
        val c = controller ?: return
        val json = try {
            converter.convert(j.content, uploaded, documentId = "", version = "2.10.2").toString()
        } catch (e: IllegalArgumentException) {
            dispatch(PublishEvent.JsError(PublishStage.INJECT, e.message ?: "변환 실패")); return
        }
        c.setDocument(json)
        armTimeout(PublishStage.INJECT, INJECT_TIMEOUT_MS)
    }

    private fun armTimeout(stage: PublishStage, millis: Long) {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch { delay(millis); dispatch(PublishEvent.Timeout(stage)) }
    }
}
```

`blogIdOrFail` 자리표시를 다음으로 교체한다 (blogId 는 attach 시점에 한 번 읽어 둔다):

```kotlin
    private var blogId: String? = null

    // start() 안, pendingJobs.get 앞에:
        blogId = settings.blogIdOnce()
        if (blogId == null) { machine = PublishStateMachine(0, 0); dispatch(PublishEvent.UrlChanged("https://nid.naver.com/nidlogin.login")); return }

    private fun blogIdOrFail(): String? = blogId
```

- [ ] **Step 5: PublishPanel 과 PublishScreen**

SP2 의 글쓰기 화면은 "왼쪽 채팅 + 오른쪽 사이드 패널(WebView)" 구조다(디자인 가이드 §8). 그래서 발행 UI 는 부모가 크기를 정하는 **`PublishPanel`** 로 만들고, SP1 에서는 이를 전체 화면으로 감싼 임시 `PublishScreen` 을 쓴다.

```kotlin
// ui/publish/PublishPanel.kt
package com.csh.blogwriter.ui.publish

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.domain.publish.PublishState
import com.csh.blogwriter.publish.NaverEditorWebView
import com.csh.blogwriter.ui.components.BannerKind
import com.csh.blogwriter.ui.components.InlineBanner
import com.csh.blogwriter.ui.components.ProgressScreen
import com.csh.blogwriter.ui.components.ResultScreen
import com.csh.blogwriter.ui.theme.AppSpacing
import kotlinx.serialization.json.JsonObject

/**
 * 발행 패널: 진행 오버레이 → 에디터 WebView(검토) → 결과. 부모가 준 [modifier] 크기를 채운다.
 * SP1: 전체 화면. SP2: 채팅 화면 오른쪽 사이드 패널.
 */
@Composable
fun PublishPanel(
    viewModel: PublishViewModel,
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
    onSessionExpired: (jobId: String) -> Unit,
    onFailed: (jobId: String) -> Unit,
    onCancelRequest: () -> Unit,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val editor = remember {
        NaverEditorWebView(context, object : NaverEditorWebView.Listener {
            override fun onUrlChanged(url: String) = viewModel.onUrlChanged(url)
            override fun onPageFinished(url: String) = viewModel.onPageFinished(url)
            override fun onReady() = viewModel.onReady()
            override fun onPopupsDismissed(count: Int) = viewModel.onPopupsDismissed(count)
            override fun onImageUploaded(ref: String, response: JsonObject) = viewModel.onImageUploaded(ref, response)
            override fun onImageFailed(ref: String, message: String) = viewModel.onImageFailed(ref, message)
            override fun onInjected(componentCount: Int) = viewModel.onInjected(componentCount)
            override fun onError(step: String, message: String) = viewModel.onJsError(step, message)
            override fun onLog(message: String) = Unit
        })
    }
    DisposableEffect(editor) {
        viewModel.attach(editor)
        onDispose { viewModel.detach(); editor.destroy() }
    }
    LaunchedEffect(Unit) {
        viewModel.navigation.collect { nav ->
            when (nav) {
                is PublishNav.SessionExpired -> onSessionExpired(nav.jobId)
                is PublishNav.Failed -> onFailed(nav.jobId)
            }
        }
    }

    val state = ui.state
    Box(modifier) {
        // WebView 는 항상 살아 있어야 하므로 먼저 배치하고, 필요할 때만 오버레이로 가린다.
        Column(Modifier.fillMaxSize()) {
            if (state is PublishState.Reviewing) {
                Box(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)) {
                    InlineBanner("내용을 확인하고 오른쪽 위 '발행'을 눌러 주세요", BannerKind.Info)
                }
            }
            AndroidView(factory = { editor.view }, modifier = Modifier.fillMaxSize())
        }
        when (state) {
            is PublishState.Idle, is PublishState.PreparingImages ->
                ProgressScreen("사진을 준비하고 있어요", (state as? PublishState.PreparingImages)?.let { "${it.total}장 중 ${it.done}장" },
                    (state as? PublishState.PreparingImages)?.let { if (it.total == 0) null else it.done.toFloat() / it.total }, onCancel = onCancelRequest)
            is PublishState.LoadingEditor -> ProgressScreen("네이버 글쓰기 화면을 여는 중이에요", null, null, onCancel = onCancelRequest)
            is PublishState.DismissingPopups -> ProgressScreen("네이버 글쓰기 화면을 여는 중이에요", null, null)
            is PublishState.UploadingImages -> ProgressScreen("사진을 올리고 있어요", "${state.total}장 중 ${state.done}장", state.done.toFloat() / state.total)
            is PublishState.Injecting -> ProgressScreen("글을 채워 넣고 있어요", null, null)
            is PublishState.Reviewing -> Unit
            is PublishState.Published -> ResultScreen(success = true, title = "발행했어요", message = "발행한 글 목록에서 다시 볼 수 있어요.", primaryText = "확인", onPrimary = onDone)
            is PublishState.SessionExpired, is PublishState.Failed -> ProgressScreen("잠시만요", null, null)
        }
    }
}
```

```kotlin
// ui/publish/PublishScreen.kt — SP1 임시 전체 화면 래퍼. SP2 에서는 채팅 화면이 PublishPanel 을 직접 배치한다.
package com.csh.blogwriter.ui.publish

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.csh.blogwriter.ui.components.ConfirmSheet

@Composable
fun PublishScreen(
    onDone: () -> Unit,
    onSessionExpired: (jobId: String) -> Unit,
    onFailed: (jobId: String) -> Unit,
    onLeave: () -> Unit,
    viewModel: PublishViewModel = hiltViewModel(),
) {
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler { confirmLeave = true }
    PublishPanel(
        viewModel = viewModel,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        onDone = onDone, onSessionExpired = onSessionExpired, onFailed = onFailed,
        onCancelRequest = { confirmLeave = true },
    )
    ConfirmSheet(
        visible = confirmLeave,
        title = "작성 중인 글을 두고 나갈까요?",
        message = "나중에 홈 화면에서 이어서 올릴 수 있어요.",
        confirmText = "나가기", onConfirm = { confirmLeave = false; onLeave() },
        dismissText = "계속 진행", onDismiss = { confirmLeave = false },
    )
}
```

`AppNavHost`:
```kotlin
composable<Routes.Publish> { entry ->
    val jobId = entry.toRoute<Routes.Publish>().jobId
    PublishScreen(
        onDone = { nav.navigate(Routes.Home) { popUpTo(Routes.Home) { inclusive = true } } },
        onSessionExpired = { id -> nav.navigate(Routes.Login("publish:$id")) { popUpTo(Routes.Home) } },
        onFailed = { id -> nav.navigate(Routes.Fallback(id)) { popUpTo(Routes.Home) } },
        onLeave = { nav.navigate(Routes.Home) { popUpTo(Routes.Home) { inclusive = true } } },
    )
}
```

- [ ] **Step 6: 테스트/빌드 확인**

Run: `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
Expected: 전체 PASS, BUILD SUCCESSFUL.

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter app/src/test/java/com/csh/blogwriter/ui/publish
git commit -m "Add publish screen driving the editor through the state machine"
```

---

### Task 15: 폴백 화면 (FR-8)

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/ui/fallback/FallbackViewModel.kt`
- Create: `app/src/main/java/com/csh/blogwriter/ui/fallback/FallbackScreen.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/ui/navigation/AppNavHost.kt`
- Test: `app/src/test/java/com/csh/blogwriter/ui/fallback/FallbackViewModelTest.kt`

**Interfaces:**
- Consumes: `PendingJobRepository`, `FailureLogRepository` (Task 8), `FallbackTextRenderer` (Task 6), 컴포넌트.
- Produces: `FallbackUiState(title, reason, clipboardText, shareText)`, `FallbackViewModel.uiState`; `FallbackReason.userMessage(stage)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/ui/fallback/FallbackViewModelTest.kt
package com.csh.blogwriter.ui.fallback

import androidx.lifecycle.SavedStateHandle
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FallbackViewModelTest {
    private val job = PendingJob("j", PostContent("제목", listOf(Block.Paragraph(listOf(Run("본문"))), Block.Image("img_001"))), listOf("u"), null, 0, "사진 업로드 실패 (img_001): SERVER_ERROR")
    private val repo = object : PendingJobRepository {
        private val flow = MutableStateFlow<PendingJob?>(job)
        override fun observeLatest(): Flow<PendingJob?> = flow
        override suspend fun get(id: String) = flow.value
        override suspend fun save(job: PendingJob) {}
        override suspend fun setPreparedPaths(id: String, paths: List<String>?) {}
        override suspend fun setLastFailure(id: String, message: String?) {}
        override suspend fun delete(id: String) {}
    }
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun buildsUserFacingStateFromJob() = runTest {
        val vm = FallbackViewModel(SavedStateHandle(mapOf("jobId" to "j")), repo)
        advanceUntilIdle()
        val s = vm.uiState.value!!
        assertEquals("제목", s.title)
        assertEquals("제목\n\n본문\n\n[사진 1]", s.clipboardText)
        assertTrue(s.reason.contains("사진"))
        assertTrue(s.shareText.contains("SERVER_ERROR"))
    }

    @Test
    fun reasonMessagesAreNonTechnical() {
        assertEquals("네이버 글쓰기 화면이 열리지 않았어요.", FallbackReason.userMessage("제한 시간 초과", stageHint = "LOAD_EDITOR"))
        assertEquals("사진을 올리다가 멈췄어요.", FallbackReason.userMessage("사진 업로드 실패 (img_001): x", stageHint = null))
        assertEquals("글을 자동으로 채우지 못했어요.", FallbackReason.userMessage("컴포넌트 수 불일치", stageHint = null))
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.ui.fallback.FallbackViewModelTest"`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

```kotlin
// ui/fallback/FallbackViewModel.kt
package com.csh.blogwriter.ui.fallback

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.publish.FallbackTextRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FallbackUiState(val title: String, val reason: String, val clipboardText: String, val shareText: String)

object FallbackReason {
    /** 기술 메시지 → 사용자 문장. stageHint 는 PublishStage.name 또는 null. */
    fun userMessage(technical: String, stageHint: String?): String = when {
        stageHint == "LOAD_EDITOR" || technical.contains("editor missing") -> "네이버 글쓰기 화면이 열리지 않았어요."
        technical.contains("사진") || stageHint == "UPLOAD" -> "사진을 올리다가 멈췄어요."
        else -> "글을 자동으로 채우지 못했어요."
    }
}

@HiltViewModel
class FallbackViewModel @Inject constructor(savedState: SavedStateHandle, private val pendingJobs: PendingJobRepository) : ViewModel() {
    private val jobId: String = checkNotNull(savedState["jobId"])
    private val _uiState = MutableStateFlow<FallbackUiState?>(null)
    val uiState: StateFlow<FallbackUiState?> = _uiState

    init {
        viewModelScope.launch {
            val job = pendingJobs.get(jobId) ?: return@launch
            val technical = job.lastFailure ?: "알 수 없음"
            _uiState.value = FallbackUiState(
                title = job.content.title,
                reason = FallbackReason.userMessage(technical, stageHint = null),
                clipboardText = FallbackTextRenderer.render(job.content),
                shareText = "[블로그 도우미 오류]\n글: ${job.content.title}\n원인: $technical",
            )
        }
    }
}
```

```kotlin
// ui/fallback/FallbackScreen.kt
package com.csh.blogwriter.ui.fallback

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.BottomCta
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.components.WeakButton
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

private const val NAVER_BLOG_PACKAGE = "com.nhn.android.blog"

@Composable
fun FallbackScreen(onRetry: () -> Unit, onHome: () -> Unit, viewModel: FallbackViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val s = state ?: return
    ScreenScaffold(
        topBar = { AppTopBar(onBack = onHome) },
        bottom = {
            BottomCta("글 복사하고 블로그 앱 열기", onClick = {
                context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("post", s.clipboardText))
                val launch = context.packageManager.getLaunchIntentForPackage(NAVER_BLOG_PACKAGE)
                context.startActivity(launch ?: Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$NAVER_BLOG_PACKAGE".toUri()))
            })
            Spacer(Modifier.height(AppSpacing.md))
            WeakButton("다시 시도", onClick = onRetry)
            Spacer(Modifier.height(AppSpacing.md))
            WeakButton("관리자에게 알리기", onClick = {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, s.shareText) }
                context.startActivity(Intent.createChooser(send, "관리자에게 알리기"))
            })
        },
    ) {
        Spacer(Modifier.height(AppSpacing.section))
        Text(s.reason, style = AppTheme.typography.title1, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(AppSpacing.lg))
        Text(
            "글을 복사해 두었다가 네이버 블로그 앱에서 붙여넣으면 돼요. 사진은 앱에서 갤러리로 직접 넣어 주세요.\n\n그대로 두면 홈 화면에서 나중에 다시 시도할 수 있어요.",
            style = AppTheme.typography.body1, color = AppTheme.colors.textSecondary,
        )
    }
}
```

`AppNavHost`:
```kotlin
composable<Routes.Fallback> { entry ->
    val jobId = entry.toRoute<Routes.Fallback>().jobId
    FallbackScreen(
        onRetry = { nav.navigate(Routes.Publish(jobId)) { popUpTo(Routes.Home) } },
        onHome = { nav.navigate(Routes.Home) { popUpTo(Routes.Home) { inclusive = true } } },
    )
}
```

- [ ] **Step 4: 테스트/빌드 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.ui.fallback.FallbackViewModelTest" :app:assembleDebug`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/ui app/src/test/java/com/csh/blogwriter/ui/fallback
git commit -m "Add fallback screen with clipboard copy and blog app hand-off"
```

---

### Task 16: 에뮬레이터 종단 검증과 수정

이 태스크는 코드보다 검증이 중심이다. 발견한 결함은 각각 실패 테스트 → 수정 → 커밋으로 처리한다.

**Files:**
- Modify: 결함에 따라. 예상 후보: `editor_bridge.js`(팝업 셀렉터, 준비 판정), `PublishViewModel`(타이밍), `PublishScreen`(WebView 크기/오버레이).

- [ ] **Step 1: 설치와 로그 준비**

```bash
./gradlew.bat :app:assembleDebug
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" logcat -c
"$ADB" shell am start -n com.csh.blogwriter/.MainActivity
```
관찰: `"$ADB" logcat -d -s NaverEditorWebView AndroidRuntime` (JS 콘솔과 크래시).

- [ ] **Step 2: 시나리오 A — 정상 발행**

1. 홈 → 로그인 배너 → 네이버 로그인(사용자가 에뮬레이터에서 입력) → 홈으로 복귀, 배너 사라짐.
2. 새 글 쓰기 → 제목 "종단 테스트", 본문 두 문단, 사진 2장 → 발행하러 가기.
3. 진행 화면이 "사진을 준비하고 있어요 → 네이버 글쓰기 화면을 여는 중이에요 → 사진을 올리고 있어요 2장 중 n장 → 글을 채워 넣고 있어요" 순으로 바뀌고, 에디터가 제목/문단/사진 2장이 채워진 상태로 노출된다.
4. 에디터의 발행 → 비공개 → 발행. "발행했어요" 화면 → 확인 → 홈 → 발행한 글에 항목이 있고 탭하면 브라우저가 열린다.

Expected: 위 전부 통과. 실패하면 logcat 의 `JS[...]` 줄과 `PublishState` 를 근거로 수정.

- [ ] **Step 3: 시나리오 B — 세션 만료 재개**

```bash
"$ADB" shell pm clear com.nhn.android.blog 2>/dev/null; # 무관. 쿠키는 앱 데이터에 있음
```
앱 데이터 중 쿠키만 지울 수 없으므로 관리자 화면에 임시로 "로그아웃(테스트)" 버튼을 붙이지 않고, 대신 `NaverSession.logout()` 을 호출하는 디버그 전용 Broadcast 를 둔다:

```kotlin
// App.kt (debug only) — BuildConfig.DEBUG 일 때 등록
if (BuildConfig.DEBUG) registerReceiver(object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CoroutineScope(Dispatchers.Main).launch { EntryPointAccessors.fromApplication(context, DebugEntryPoint::class.java).session().logout() }
    }
}, IntentFilter("com.csh.blogwriter.DEBUG_LOGOUT"), Context.RECEIVER_EXPORTED)
```
```kotlin
// di/DebugEntryPoint.kt
@EntryPoint @InstallIn(SingletonComponent::class)
interface DebugEntryPoint { fun session(): NaverSession }
```
실행: 새 글 작성 후 발행하러 가기 직전에 `"$ADB" shell am broadcast -a com.csh.blogwriter.DEBUG_LOGOUT` → 발행하러 가기 → 로그인 화면으로 이동("올리다 만 글" 보존) → 로그인 → 자동으로 발행 화면 재진입 → 정상 발행.

Expected: 재로그인 후 같은 글로 재개. 홈에 돌아가면 배너가 사라져 있다.

- [ ] **Step 4: 시나리오 C — 폴백**

기내 모드: `"$ADB" shell cmd connectivity airplane-mode enable` 후 새 글 발행 시도 → 30초 내 "네이버 글쓰기 화면이 열리지 않았어요" 폴백 화면 → "글 복사하고 블로그 앱 열기" 가 Play 스토어(또는 앱) 인텐트를 띄우고 클립보드에 글이 들어 있다 → 관리자 화면(톱니)에 `[LOAD_EDITOR] 제한 시간 초과` 로그. `"$ADB" shell cmd connectivity airplane-mode disable` 후 "다시 시도" 로 정상 발행.

- [ ] **Step 5: 결함 수정 커밋**

각 결함마다: 재현 테스트(가능하면 단위) → 수정 → `./gradlew.bat :app:testDebugUnitTest` → 커밋 `fix: <원인>`.

- [ ] **Step 6: 테스트 발행물 정리**

사용자가 네이버에서 비공개 테스트 글을 삭제하도록 안내한다 (앱은 삭제 기능이 없다).

---

### Task 17: CI/CD와 릴리스 서명, README

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/workflows/release.yml`
- Modify: `app/build.gradle.kts` (signingConfig release: 환경변수/`keystore.properties` 기반)
- Create: `README.md`

- [ ] **Step 1: 서명 설정**

`app/build.gradle.kts` 의 `android { }` 안에 추가:
```kotlin
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH") ?: rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
                java.util.Properties().apply { f.inputStream().use(::load) }.getProperty("storeFile")
            }
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: propOrNull("storePassword")
                keyAlias = System.getenv("KEY_ALIAS") ?: propOrNull("keyAlias")
                keyPassword = System.getenv("KEY_PASSWORD") ?: propOrNull("keyPassword")
            }
        }
    }
```
파일 상단(플러그인 블록 아래)에:
```kotlin
fun propOrNull(name: String): String? = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> java.util.Properties().apply { f.inputStream().use(::load) }.getProperty(name) }
```
`buildTypes.release` 에 `signingConfig = signingConfigs.getByName("release")`.

- [ ] **Step 2: 워크플로**

```yaml
# .github/workflows/ci.yml
name: CI
on:
  push: { branches: [main] }
  pull_request:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        if: always()
        with: { name: test-results, path: app/build/test-results/ }
```

```yaml
# .github/workflows/release.yml
name: Release
on:
  push:
    tags: ['v*']
jobs:
  release:
    runs-on: ubuntu-latest
    permissions: { contents: write }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v4
      - name: Decode keystore
        run: echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > "$RUNNER_TEMP/release.jks"
      - name: Build signed APK
        env:
          KEYSTORE_PATH: ${{ runner.temp }}/release.jks
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: ./gradlew :app:testDebugUnitTest :app:assembleRelease --no-daemon
      - name: Rename
        run: cp app/build/outputs/apk/release/app-release.apk "blogwriter-${GITHUB_REF_NAME}.apk"
      - uses: softprops/action-gh-release@v2
        with:
          files: blogwriter-*.apk
          generate_release_notes: true
```

- [ ] **Step 3: README**

```markdown
# 블로그 도우미

네이버 블로그 글쓰기를 돕는 갤럭시 탭용 앱. 사진과 줄거리로 글을 만들고(SP2), 네이버 스마트에디터에 자동 입력한 뒤 사용자가 직접 발행한다.

## 개발
- Android Studio (AGP 9.3, JDK 17). `./gradlew :app:testDebugUnitTest :app:assembleDebug`
- 설계: `docs/superpowers/specs/`, 디자인: `docs/design-guide.md`, 에디터 규칙: `spike/findings.md`

## 릴리스
1. 최초 1회 서명 키 생성: `keytool -genkeypair -v -keystore release.jks -alias blogwriter -keyalg RSA -keysize 2048 -validity 10000`
2. GitHub Secrets 등록: `KEYSTORE_BASE64`(`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
3. `git tag v0.1.0 && git push origin v0.1.0` → Releases 에 `blogwriter-v0.1.0.apk` 첨부
4. 태블릿에서 Release 페이지의 APK 를 내려받아 설치 (출처를 알 수 없는 앱 허용 필요)

로컬 서명 빌드: 루트에 `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) 를 두고 `./gradlew :app:assembleRelease`.
```

- [ ] **Step 4: 로컬 검증**

Run: `keytool -genkeypair -v -keystore "$TEMP/test.jks" -alias blogwriter -keyalg RSA -keysize 2048 -validity 30 -storepass testtest -keypass testtest -dname "CN=test"` 후
`KEYSTORE_PATH="$TEMP/test.jks" KEYSTORE_PASSWORD=testtest KEY_ALIAS=blogwriter KEY_PASSWORD=testtest ./gradlew.bat :app:assembleRelease`
Expected: `app/build/outputs/apk/release/app-release.apk` 생성. R8 로 WebView 브리지가 제거되지 않았는지 릴리스 APK를 에뮬레이터에 설치해 시나리오 A 를 한 번 더 수행.

- [ ] **Step 5: 커밋**

```bash
git add .github app/build.gradle.kts README.md
git commit -m "Add CI, signed release workflow and README"
```

---

## 완료 기준 (스펙 §1 성공 기준 대응)

| 기준 | 확인 태스크 |
|---|---|
| 1. 로그인→작성→자동 입력→발행→이력 | Task 16 시나리오 A |
| 2. 재로그인 후 재개 | Task 16 시나리오 B |
| 3. 폴백 + 실패 로그 | Task 16 시나리오 C |
| 4. 단위 테스트로 잠금 | Task 3~9, 12~15 |
| 5. 태그 푸시로 서명 APK 릴리스 | Task 17 |
