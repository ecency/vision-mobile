package com.ecency.core.render.blocks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BlocksTest {
    private fun cell(header: Boolean) = Block.Table.Cell(header, emptyList())

    @Test
    fun tableColumnsCountHeaderCellsOfWidestRow() {
        val table = Block.Table(
            listOf(
                Block.Table.Row(List(4) { cell(true) }),
                Block.Table.Row(List(5) { cell(false) }),
            ),
        )
        assertEquals(4, table.maxHeaderColumns)
        assertEquals(0, Block.Table(emptyList()).maxHeaderColumns)
    }

    @Test
    fun headingLevelIsBounded() {
        assertFailsWith<IllegalArgumentException> { Block.Heading(0, emptyList()) }
        assertFailsWith<IllegalArgumentException> { Block.Heading(7, emptyList()) }
        assertEquals(6, Block.Heading(6, emptyList()).level)
    }

    @Test
    fun plainTextReadsChipsAndCollapsesSpaces() {
        val inlines = listOf(
            Inline.Text("Hi  "), Inline.Mention("alice"), Inline.Text(" see "),
            Inline.Link(LinkTarget.External("https://example.com"), listOf(Inline.Text("this", setOf(Inline.Mark.Bold)))),
            Inline.Text(" "), Inline.Tag("hive"), Inline.LineBreak, Inline.Text(" next line "),
        )
        assertEquals("Hi @alice see this #hive\nnext line", inlines.plainText())
    }

    @Test
    fun imageUrlsWalkNestedBlocksInDocumentOrderOnce() {
        fun img(n: Int) = Block.Image("https://example.com/$n.png", null)
        val blocks = listOf(
            img(1),
            Block.Quote(listOf(img(2))),
            Block.ListBlock(false, 1, listOf(listOf(img(3)), listOf(img(1)))),
            Block.Table(listOf(Block.Table.Row(listOf(Block.Table.Cell(false, listOf(img(4))))))),
            Block.ImageGrid(listOf(img(5), img(6))),
            Block.Details(emptyList(), listOf(img(7))),
            Block.Image("https://example.com/linked.png", null, LinkTarget.External("https://example.com")),
        )
        assertEquals((1..7).map { "https://example.com/$it.png" }, blocks.imageUrls())
    }

    @Test
    fun hiveAccountNamesAreValidatedBeforeTheyReachAUrl() {
        listOf("ecency", "hive.blog", "good-karma", "abc", "a1b2c3d4e5f6g7h8").forEach { assertEquals(true, isHiveAccountName(it), it) }
        listOf("ab", "Ecency", "1abc", "bad name", "../x", "a/b", "abc-", "abc..def", "a12345678901234567", "").forEach {
            assertEquals(false, isHiveAccountName(it), it)
        }
    }

    @Test
    fun bodyDirectionFollowsItsFirstStrongLetter() {
        fun para(vararg inlines: Inline) = Block.Paragraph(inlines.toList())
        assertEquals(false, listOf(para(Inline.Text("Hello"))).isRtlContent())
        assertEquals(true, listOf(para(Inline.Text("مرحبا Hello"))).isRtlContent())
        // a mention, digits and punctuation decide nothing; the Arabic after them does
        assertEquals(true, listOf(para(Inline.Mention("alice"), Inline.Text(" 12, ")), Block.Heading(2, listOf(Inline.Text("שלום")))).isRtlContent())
        assertEquals(true, listOf(Block.Quote(listOf(para(Inline.Text("سلام"))))).isRtlContent())
        assertEquals(false, listOf(Block.Image("https://x/a.png", null), para(Inline.Text("123 Latin"))).isRtlContent())
        assertEquals(false, emptyList<Block>().isRtlContent(), "nothing to read is left to right")
        assertEquals(null, firstStrongRtlText("12 @!"))
        // digits of any script and combining marks are weak, as in dir="auto"
        assertEquals(false, listOf(para(Inline.Text("٢٠٢٤ Year in review"))).isRtlContent())
        assertEquals(false, firstStrongRtlText("۱۴۰۳ Persian year, in English"))
        assertEquals(true, firstStrongRtlText("٢٠٢٤ مراجعة العام"))
        assertEquals(true, listOf(para(Inline.Text("هذه جملة عربية كاملة."))).isRtlContent())
        assertEquals(true, firstStrongRtlText("\u064E\u0628"), "a leading mark is skipped, the Arabic letter decides")
        assertEquals(null, firstStrongRtlText("\u064E 1"))
        // right to left scripts outside the BMP: Adlam and Hanifi Rohingya letters, not their digits
        assertEquals(true, firstStrongRtlText("\uD83A\uDD00 abc"))
        assertEquals(true, firstStrongRtlText("\uD803\uDD00"))
        assertEquals(false, firstStrongRtlText("\uD83A\uDD50 abc"), "an Adlam digit is weak")
        assertEquals(false, firstStrongRtlText("\uD83D\uDE00 hi"), "an emoji is neutral")
    }
}
