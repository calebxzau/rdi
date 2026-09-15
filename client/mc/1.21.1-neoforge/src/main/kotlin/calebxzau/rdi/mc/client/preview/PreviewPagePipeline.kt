package calebxzau.rdi.mc.client.preview

/**
 * Render/write sequencing for one export. The render cursor may move ahead of
 * the single writer slot, but a page is counted as written only when its own
 * completion is received.
 */
internal class PreviewPagePipeline(private val pageCount: Int) {
    var renderPageIndex: Int = 0
        private set
    var pendingWritePage: Int? = null
        private set
    var writtenCount: Int = 0
        private set

    init {
        require(pageCount >= 0) { "pageCount must be non-negative" }
    }

    val writerBusy: Boolean get() = pendingWritePage != null
    val readyToPublish: Boolean
        get() = renderPageIndex == pageCount && pendingWritePage == null && writtenCount == pageCount

    fun submitPage(page: Int) {
        require(page == renderPageIndex) { "page $page is not the current render page $renderPageIndex" }
        require(pendingWritePage == null) { "the page writer is already busy" }
        require(page < pageCount) { "page $page is outside page count $pageCount" }
        pendingWritePage = page
        renderPageIndex++
    }

    fun completePage(page: Int): Boolean {
        if (pendingWritePage != page) return false
        pendingWritePage = null
        writtenCount++
        return true
    }
}
