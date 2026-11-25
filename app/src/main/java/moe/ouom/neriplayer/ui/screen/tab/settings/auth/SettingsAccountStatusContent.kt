package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.ouom.neriplayer.activity.auth.QqMusicQrLoginActivity
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthState
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.state.collectAsStateWithLifecycleCompat
import moe.ouom.neriplayer.ui.screen.tab.settings.state.formatSyncTime

internal fun accountStatusText(
    resources: Resources,
    valid: Boolean,
    hasSaved: Boolean,
    relativeTime: String,
    validRes: Int,
    savedInvalidRes: Int,
    missingRes: Int
): String {
    if (valid) return resources.getString(validRes, relativeTime)
    val statusRes = if (hasSaved) savedInvalidRes else missingRes
    return resources.getString(statusRes)
}

@Composable
private fun accountSavedAtText(savedAt: Long): String =
    if (savedAt > 0L) formatSyncTime(savedAt) else stringResource(CoreCommonR.string.time_just_now)

internal fun hasValidSavedCookieHealth(state: SavedCookieAuthState): Boolean =
    state == SavedCookieAuthState.Valid

internal fun hasValidYouTubeAuthHealth(state: YouTubeAuthState): Boolean =
    state == YouTubeAuthState.Valid

internal fun shouldLoadSettingsAccountProfiles(
    screenActive: Boolean,
    settingsVisible: Boolean,
    activePage: SettingsPage?
): Boolean = screenActive && settingsVisible && activePage == SettingsPage.Accounts

@Composable
internal fun SettingsLoginExpandedContent(
    controller: SettingsAccountAuthController,
    isActive: Boolean = true,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val biliVm = controller.biliVm
    val youtubeVm = controller.youtubeVm
    val neteaseVm = controller.neteaseVm

    // ── QQ 音乐 ─────────────────────────────────────────────────────────────
    // QQ 暂不做独立 ViewModel：登录态就是一个本地 Cookie 文件，
    // 登录页返回后把 revision +1，remember 会重新读一遍仓库，卡片立刻刷新。
    var qqAuthRevision by remember { mutableIntStateOf(0) }
    // 与网易云/B站/YouTube 一致：登录成功后弹一次"登录成功"提示
    var showQqLoginSuccess by remember { mutableStateOf(false) }
    val qqSession = remember(qqAuthRevision) { AppContainer.qqMusicSessionStore.read() }
    // 播放时若服务端判定登录失效，这里读到时间戳就显示"已过期"，而不是继续装作已登录
    val qqExpiredAt = remember(qqAuthRevision) { AppContainer.qqMusicSessionStore.expiredAtOrNull() }
    // QQ 账号资料（昵称/头像）：登录后单独拉一次，失败就退回"账号资料暂不可用"
    var qqProfile by remember(qqAuthRevision) { mutableStateOf<SettingsAccountProfile?>(null) }
    LaunchedEffect(qqAuthRevision) {
        qqProfile = if (qqSession.isLoggedIn) loadQqMusicAccountProfile() else null
    }
    val context = LocalContext.current
    val qqLoginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            qqAuthRevision++
            showQqLoginSuccess = true
        }
    }
    val bili by biliVm.uiState.collectAsStateWithLifecycleCompat()
    val youtube by youtubeVm.uiState.collectAsStateWithLifecycleCompat()
    val netease by neteaseVm.uiState.collectAsStateWithLifecycleCompat()
    val biliAuthorizationFlow = remember {
        AppContainer.biliCookieRepo.cookieFlow.map { cookies ->
            SettingsAccountAuthorizationSnapshot(cookies)
        }.distinctUntilChanged()
    }
    val neteaseAuthorizationFlow = remember {
        AppContainer.neteaseCookieRepo.cookieFlow.map { cookies ->
            SettingsAccountAuthorizationSnapshot(cookies)
        }.distinctUntilChanged()
    }
    val youtubeAuthorizationFlow = remember {
        AppContainer.youtubeAuthRepo.authFlow.map { auth ->
            SettingsYouTubeAccountAuthorizationSnapshot(auth)
        }.distinctUntilChanged()
    }
    val emptyAuthorization = remember { SettingsAccountAuthorizationSnapshot(emptyMap()) }
    val emptyYouTubeAuthorization = remember { SettingsYouTubeAccountAuthorizationSnapshot(YouTubeAuthBundle()) }
    val biliAuthorization by biliAuthorizationFlow.collectAsStateWithLifecycle(initialValue = emptyAuthorization)
    val neteaseAuthorization by neteaseAuthorizationFlow.collectAsStateWithLifecycle(initialValue = emptyAuthorization)
    val youtubeAuthorization by youtubeAuthorizationFlow.collectAsStateWithLifecycle(initialValue = emptyYouTubeAuthorization)
    val youtubeEnabled by AppContainer.settingsRepo.youtubeEnabledFlow.collectAsStateWithLifecycle(
        initialValue = YouTubeFeatureGate.isEnabled()
    )
    val biliProfile = rememberSettingsAccountProfile(
        request = SettingsAccountProfileRequest(bili.hasSavedCookies, bili.health.savedAt, biliAuthorization.identity),
        isActive = isActive
    ) { loadBiliAccountProfile(biliAuthorization) }
    val neteaseProfile = rememberSettingsAccountProfile(
        request = SettingsAccountProfileRequest(netease.hasSavedCookies, netease.health.savedAt, neteaseAuthorization.identity),
        isActive = isActive
    ) { loadNeteaseAccountProfile(neteaseAuthorization) }
    val youtubeProfile = rememberSettingsAccountProfile(
        request = SettingsAccountProfileRequest(youtube.hasSavedAuth, youtubeAuthorization.savedAt, youtubeAuthorization.identity),
        isActive = isActive && youtubeEnabled
    ) { loadYouTubeAccountProfile(youtubeAuthorization) }

    LaunchedEffect(biliVm, youtubeVm, neteaseVm) {
        biliVm.refreshAuthHealth()
        neteaseVm.refreshAuthHealth()
        youtubeVm.refreshAuthHealth()
    }

    SettingsAccountCardsContent(
        accounts = listOf(
            SettingsAccountCardUiState(
                platform = SettingsAccountPlatform.Netease,
                hasSavedAuthorization = netease.hasSavedCookies,
                authorizationComplete = hasValidSavedCookieHealth(netease.health.state),
                savedAtLabel = accountSavedAtText(netease.health.savedAt),
                profile = neteaseProfile.profile,
                profileLoading = neteaseProfile.loading
            ),
            SettingsAccountCardUiState(
                platform = SettingsAccountPlatform.Bilibili,
                hasSavedAuthorization = bili.hasSavedCookies,
                authorizationComplete = hasValidSavedCookieHealth(bili.health.state),
                savedAtLabel = accountSavedAtText(bili.health.savedAt),
                profile = biliProfile.profile,
                profileLoading = biliProfile.loading
            ),
            SettingsAccountCardUiState(
                platform = SettingsAccountPlatform.YouTube,
                hasSavedAuthorization = youtube.hasSavedAuth,
                authorizationComplete = hasValidYouTubeAuthHealth(youtube.health.state),
                savedAtLabel = accountSavedAtText(youtube.health.savedAt),
                profile = youtubeProfile.profile,
                profileLoading = youtubeProfile.loading
            ),
            SettingsAccountCardUiState(
                platform = SettingsAccountPlatform.QqMusic,
                hasSavedAuthorization = qqSession.isLoggedIn,
                authorizationComplete = qqSession.isLoggedIn && qqExpiredAt == null,
                // 有账号时直接把 uin 显示成副标题，用户能确认登的是哪个号；
                // 服务端已判定失效时补一句"重新扫码"，否则用户会以为是没版权。
                savedAtLabel = if (qqSession.isLoggedIn) {
                    buildString {
                        append("QQ ${qqSession.uin}")
                        if (qqExpiredAt != null) append(" · 登录已过期，请重新扫码")
                    }
                } else {
                    null
                },
                profile = qqProfile,
                profileLoading = qqSession.isLoggedIn && qqProfile == null,
            )
        ),
        onLogin = { platform ->
            // QQ 音乐走自己的扫码页；其它平台保持原有弹窗流程不变。
            if (platform == SettingsAccountPlatform.QqMusic) {
                // “重新登录”前必须清掉旧登录态：登录页检测到已登录会直接关闭，
                // 用户会以为按钮没反应。
                AppContainer.qqMusicSessionStore.clear()
                qqAuthRevision++
                qqLoginLauncher.launch(Intent(context, QqMusicQrLoginActivity::class.java))
            } else {
                controller.actions.openPlatformLogin(platform)
            }
        },
        onManageSaved = { platform ->
            // QQ 的“退出登录”只需要清掉本地 Cookie；卡片靠 revision 立刻刷新。
            // 其它平台仍走原有的“已保存授权”管理弹窗。
            if (platform == SettingsAccountPlatform.QqMusic) {
                AppContainer.qqMusicSessionStore.clear()
                qqAuthRevision++
            } else {
                controller.actions.openPlatformSavedAuthorization(platform)
            }
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )

    if (showQqLoginSuccess) {
        LoginSuccessDialog(
            title = stringResource(CoreCommonR.string.settings_qqmusic_login_success),
            onDismiss = { showQqLoginSuccess = false }
        )
    }
}

internal class SettingsAccountEntryAction(
    private val hasSaved: Boolean,
    private val onOpenSaved: () -> Unit,
    private val onOpenSheet: () -> Unit
) {
    val onClick: () -> Unit = {
        if (hasSaved) onOpenSaved() else onOpenSheet()
    }
}
