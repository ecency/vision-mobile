package com.ecency.core.editor

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A post with a 3Speak video: the embed addresses, the thumbnails that reach `meta.image`, and the metadata, against RN's code. */
class VideoMetaGoldenTest {
    @Test
    fun videoThumbnailsAndMetadataMatchRn() = runTest {
        for (c in VideoMetaGoldenVectors.cases) {
            val thumbs = c.videoThumbs.map { VideoThumb(it.first, it.second) }
            assertEquals(c.embeds, EditorMeta.extractVideoEmbedUrls(c.body), "embeds of ${c.body}")
            val collected = EditorMeta.collectVideoThumbUrls(thumbs, c.body)
            assertEquals(c.collected, collected, "thumbnails of ${c.body} with $thumbs")
            assertEquals(c.restored, EditorMeta.restoreVideoThumbs(c.body, listOf(VideoMetaGoldenVectors.THUMB, VideoMetaGoldenVectors.IMAGE)).map { it.embedUrl to it.thumbUrl }, "restored for ${c.body}")
            if (c.body.isEmpty()) continue
            assertEquals(c.meta, EditorMeta.stringify(EditorMeta.extractMetadata(c.body, null, videoThumbUrls = collected, fetchRatios = false)), "metadata of ${c.body} with $thumbs")
            assertEquals(c.metaWithThumb, EditorMeta.stringify(EditorMeta.extractMetadata(c.body, collected.firstOrNull(), videoThumbUrls = collected, fetchRatios = false)), "metadata with thumbnail of ${c.body}")
        }
    }
}
