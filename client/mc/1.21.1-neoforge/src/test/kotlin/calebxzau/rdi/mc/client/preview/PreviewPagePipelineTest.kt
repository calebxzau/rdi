package calebxzau.rdi.mc.client.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreviewPagePipelineTest {
    @Test
    fun writerBlocksSecondSubmissionButAllowsRenderCursorToAdvance(): Unit {
        val pipeline = PreviewPagePipeline(2)
        assertFalse(pipeline.readyToPublish)
        pipeline.submitPage(0)
        assertEquals(1, pipeline.renderPageIndex)
        assertTrue(pipeline.writerBusy)
        assertFalse(pipeline.readyToPublish)
        assertFailsWith<IllegalArgumentException> { pipeline.submitPage(1) }
        assertFalse(pipeline.completePage(1))
        assertTrue(pipeline.writerBusy)
        assertTrue(pipeline.completePage(0))
        pipeline.submitPage(1)
        assertEquals(2, pipeline.renderPageIndex)
        assertFalse(pipeline.readyToPublish)
        assertFalse(pipeline.completePage(0))
        assertTrue(pipeline.writerBusy)
        assertTrue(pipeline.completePage(1))
        assertTrue(pipeline.readyToPublish)
    }

    @Test
    fun emptyPipelineIsReadyImmediately(): Unit {
        assertTrue(PreviewPagePipeline(0).readyToPublish)
    }

    @Test
    fun duplicateCompletionIsRejected(): Unit {
        val pipeline = PreviewPagePipeline(1)
        pipeline.submitPage(0)
        assertTrue(pipeline.completePage(0))
        assertFalse(pipeline.completePage(0))
        assertEquals(1, pipeline.writtenCount)
    }
}
