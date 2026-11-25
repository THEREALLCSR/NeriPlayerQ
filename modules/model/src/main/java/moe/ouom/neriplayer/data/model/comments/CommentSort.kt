package moe.ouom.neriplayer.data.model.comments

enum class CommentSort {
    HOT,
    NEWEST,
    RECOMMENDED;

    companion object {
        fun supportedBy(platform: CommentPlatform): List<CommentSort> = when (platform) {
            CommentPlatform.NETEASE -> entries
            CommentPlatform.BILIBILI -> listOf(HOT, NEWEST)
            // QQ 音乐评论接口只提供"精彩 + 最新"一页，排序能力有限
            CommentPlatform.QQ_MUSIC -> listOf(HOT)
        }
    }
}
