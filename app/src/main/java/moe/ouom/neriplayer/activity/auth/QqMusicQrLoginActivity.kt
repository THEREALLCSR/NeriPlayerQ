package moe.ouom.neriplayer.activity.auth

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * File: moe.ouom.neriplayer.activity.auth/QqMusicQrLoginActivity
 */

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.activity.enableNeriEdgeToEdge
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicQrLoginClient
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicQrLoginSession
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicQrLoginState
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSessionStore
import moe.ouom.neriplayer.util.platform.lockPortraitIfPhone

/**
 * QQ 音乐扫码登录界面。
 *
 * 视觉上与项目里已有的网易云/哔哩哔哩扫码登录保持一致：
 *   · CoordinatorLayout + MaterialToolbar（可返回）
 *   · 顶部品牌色渐变背景
 *   · 白色圆角卡片承载二维码，进度环覆盖在卡片中央
 *   · 状态用"药丸"标签展示，主按钮为品牌色实心按钮
 *
 * 为什么用 View 而不是 Compose：
 * 同目录下的 `NeteaseQrLoginActivity` / `BiliQrLoginActivity` 都是 View 实现，
 * 扫码页需要在没有 Compose 主题上下文的情况下也能稳定渲染，保持一致即可。
 *
 * 流程：
 *   1. 打开页面先取一张二维码；
 *   2. 每 1.2 秒轮询一次扫码状态；
 *   3. 扫码确认后 [QQMusicQrLoginClient] 自动完成授权并返回 Cookie；
 *   4. 本页把 Cookie 存进 [QQMusicSessionStore]，然后 setResult(RESULT_OK) 关闭。
 */
class QqMusicQrLoginActivity : ComponentActivity() {

    private companion object {
        const val QR_SIZE_DP = 216
        const val POLL_INTERVAL_MS = 1_200L
    }

    private lateinit var imageView: ImageView
    private lateinit var statusView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var refreshButton: MaterialButton
    private var loginJob: Job? = null
    private var session: QQMusicQrLoginSession? = null

    // 主题色（在 buildLayout 里按 Material 属性取值，跟随应用主题）
    private var surfaceColor: Int = Color.WHITE
    private var onSurfaceColor: Int = AndroidColor.BLACK
    private var onSurfaceVariantColor: Int = AndroidColor.DKGRAY
    private var primaryColor: Int = AndroidColor.BLACK
    private var onPrimaryColor: Int = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (QQMusicSessionStore(this).read().isLoggedIn) {
            // 已经登录过：直接返回成功，避免重复扫码。
            finishWithSuccess()
            return
        }
        lockPortraitIfPhone()
        enableNeriEdgeToEdge()
        setContentView(buildLayout())
        startLogin()
    }

    override fun onDestroy() {
        loginJob?.cancel()
        super.onDestroy()
    }

    // ── 界面 ────────────────────────────────────────────────────────────────

    private fun buildLayout(): View {
        val root = CoordinatorLayout(this).apply { fitsSystemWindows = false }

        surfaceColor = root.materialColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        onSurfaceColor = root.materialColor(com.google.android.material.R.attr.colorOnSurface, AndroidColor.BLACK)
        val surfaceVariant = root.materialColor(
            com.google.android.material.R.attr.colorSurfaceVariant,
            Color.rgb(244, 241, 246)
        )
        onSurfaceVariantColor = root.materialColor(
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            AndroidColor.DKGRAY
        )
        primaryColor = root.materialColor(androidx.appcompat.R.attr.colorPrimary, Color.rgb(49, 194, 124))
        onPrimaryColor = root.materialColor(com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
        val softPrimary = ColorUtils.blendARGB(surfaceColor, primaryColor, 0.12f)
        val softSurface = ColorUtils.blendARGB(surfaceColor, surfaceVariant, 0.18f)

        // 与网易云登录页相同的三段式渐变：品牌色 → 表面色 → 纯表面
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(softPrimary, softSurface, surfaceColor)
        )

        val appBar = AppBarLayout(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
        }
        appBar.addView(
            MaterialToolbar(this).apply {
                title = getString(CoreCommonR.string.settings_qq_music)
                setNavigationIcon(R.drawable.ic_arrow_back_24)
                setNavigationOnClickListener { finish() }
                setBackgroundColor(Color.TRANSPARENT)
            }
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp(), 22.dp(), 24.dp(), 48.dp())
        }

        val qrCardSizePx = minOf(240.dp(), resources.displayMetrics.widthPixels - 96.dp())
            .coerceAtLeast(204.dp())
        val qrImageSizePx = minOf(QR_SIZE_DP.dp(), qrCardSizePx - 24.dp()).coerceAtLeast(180.dp())
        val actionWidthPx = minOf(420.dp(), resources.displayMetrics.widthPixels - 48.dp())
            .coerceAtLeast(228.dp())

        val titleText = TextView(this).apply {
            text = "QQ 音乐登录"
            gravity = Gravity.CENTER
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurfaceColor)
        }
        val subtitleText = TextView(this).apply {
            text = "使用手机 QQ 扫描二维码，登录后即可访问你的歌单与账号资料。"
            gravity = Gravity.CENTER
            textSize = 14f
            setLineSpacing(2.dp().toFloat(), 1f)
            setTextColor(onSurfaceVariantColor)
        }

        val qrCard = MaterialCardView(this).apply {
            radius = 28.dp().toFloat()
            cardElevation = 2.dp().toFloat()
            strokeWidth = 0
            setCardBackgroundColor(Color.WHITE)
            useCompatPadding = false
            preventCornerOverlap = true
            layoutParams = LinearLayout.LayoutParams(qrCardSizePx, qrCardSizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        imageView = ImageView(this).apply {
            background = roundedBackground(Color.WHITE, 22.dp())
            clipToOutline = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(2.dp(), 2.dp(), 2.dp(), 2.dp())
            layoutParams = FrameLayout.LayoutParams(qrImageSizePx, qrImageSizePx, Gravity.CENTER)
        }
        qrCard.addView(imageView)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(42.dp(), 42.dp(), Gravity.CENTER)
        }
        qrCard.addView(progressBar)

        statusView = TextView(this).apply {
            text = "正在获取二维码…"
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 15f
            setPadding(16.dp(), 9.dp(), 16.dp(), 9.dp())
            setTextColor(primaryColor)
            background = roundedBackground(
                ColorUtils.blendARGB(surfaceColor, primaryColor, 0.11f),
                20.dp()
            )
        }

        refreshButton = MaterialButton(this).apply {
            text = "刷新二维码"
            cornerRadius = 20.dp()
            minHeight = 52.dp()
            insetTop = 0
            insetBottom = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            setTextColor(onPrimaryColor)
            setOnClickListener { startLogin() }
        }

        val cancelButton = MaterialButton(this).apply {
            text = "取消"
            cornerRadius = 20.dp()
            minHeight = 50.dp()
            insetTop = 0
            insetBottom = 0
            strokeWidth = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ColorUtils.blendARGB(surfaceColor, primaryColor, 0.10f)
            )
            setTextColor(primaryColor)
            setOnClickListener { finish() }
        }

        content.addView(titleText, matchWidthWrapHeight())
        content.addVerticalSpace(8)
        content.addView(subtitleText, matchWidthWrapHeight())
        content.addVerticalSpace(22)
        content.addView(qrCard)
        content.addVerticalSpace(14)
        content.addView(statusView, wrapContentCentered())
        content.addVerticalSpace(10)
        content.addView(
            TextView(this).apply {
                text = "扫码后请在手机 QQ 上点击“确认登录”。二维码过期会自动刷新。"
                gravity = Gravity.CENTER
                textSize = 14f
                setLineSpacing(3.dp().toFloat(), 1f)
                setTextColor(onSurfaceVariantColor)
            },
            fixedWidthWrapHeight(actionWidthPx)
        )
        content.addVerticalSpace(16)
        content.addView(refreshButton, fixedWidthWrapHeight(actionWidthPx))
        content.addVerticalSpace(10)
        content.addView(cancelButton, fixedWidthWrapHeight(actionWidthPx))

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(content)
        }
        root.addView(scrollView)

        // 让内容避开状态栏与导航栏
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            appBar.updatePadding(top = statusTop)
            content.updatePadding(bottom = 48.dp() + navBottom)
            insets
        }
        return root
    }

    // ── 登录流程 ────────────────────────────────────────────────────────────

    private fun startLogin() {
        loginJob?.cancel()
        val client = QQMusicQrLoginClient(AppContainer.sharedOkHttpClient)
        loginJob = lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                statusView.text = "正在获取二维码…"
                imageView.setImageDrawable(null)
                refreshButton.isEnabled = false

                val created = client.createSession()
                session = created
                BitmapFactory.decodeByteArray(created.imageBytes, 0, created.imageBytes.size)
                    ?.let { imageView.setImageBitmap(it) }
                progressBar.visibility = View.GONE
                refreshButton.isEnabled = true
                statusView.text = "请使用手机 QQ 扫码"

                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    when (val state = client.poll(created)) {
                        QQMusicQrLoginState.Waiting -> statusView.text = "请使用手机 QQ 扫码"
                        QQMusicQrLoginState.Scanned -> statusView.text = "已扫码，请在手机上确认"
                        QQMusicQrLoginState.Expired -> {
                            statusView.text = "二维码已过期，正在刷新…"
                            startLogin()   // 自动换一张新码
                            return@launch
                        }
                        QQMusicQrLoginState.Rejected -> {
                            statusView.text = "你已取消授权，可点“刷新二维码”重试"
                            progressBar.visibility = View.GONE
                            refreshButton.isEnabled = true
                            return@launch
                        }
                        is QQMusicQrLoginState.Authorized -> {
                            QQMusicSessionStore(this@QqMusicQrLoginActivity).save(state.cookie)
                            statusView.text = "登录成功"
                            finishWithSuccess()
                            return@launch
                        }
                    }
                }
            } catch (error: Exception) {
                // 网络错误/协议异常都落到这里：显示原因，允许用户重试。
                progressBar.visibility = View.GONE
                refreshButton.isEnabled = true
                statusView.text = "登录失败：" + (error.message ?: "未知错误")
            }
        }
    }

    private fun finishWithSuccess() {
        setResult(RESULT_OK)
        finish()
    }

    // ── 布局小工具（与 NeteaseQrLoginActivity 的实现保持一致）────────────────

    private fun matchWidthWrapHeight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun fixedWidthWrapHeight(widthPx: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun wrapContentCentered(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun LinearLayout.addVerticalSpace(heightPx: Int) {
        addView(android.view.View(this@QqMusicQrLoginActivity), LinearLayout.LayoutParams(1, heightPx))
    }

    private fun View.materialColor(attr: Int, fallback: Int): Int =
        MaterialColors.getColor(this, attr, fallback)

    private fun roundedBackground(color: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()
}
