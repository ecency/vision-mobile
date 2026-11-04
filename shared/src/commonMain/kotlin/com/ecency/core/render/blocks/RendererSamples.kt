package com.ecency.core.render.blocks

/**
 * Extra synthetic blocks for the renderer parity work (#3575): an animated GIF for the badge, a table
 * wide enough to scroll sideways and a justified paragraph. Kept apart from the main sample so the
 * HTML-generated sample can replace that one without touching these.
 */
object RendererSamples {
    /** Public domain animation (Wikimedia Commons, Newton's cradle) through the Ecency image proxy. */
    const val GIF =
        "https://images.ecency.com/p/C3TZR1g81UNcykK9RbpHBdii2wXA7k63h8DH93fAFVQ8QGTNk4JzkDubpLp4gjX4CyKwchd7SAatghsaxS3ksHmagFPrn6AaveEcMt7BunxW1xu5HSkWEjw?format=match&mode=fit"

    private fun p(text: String, style: BlockStyle = BlockStyle.Default) = Block.Paragraph(listOf(Inline.Text(text)), style)
    private fun row(header: Boolean, vararg cells: String) = Block.Table.Row(cells.map { Block.Table.Cell(header, listOf(p(it))) })

    val extras: List<Block> = listOf(
        Block.Heading(2, listOf(Inline.Text("Renderer extras"))),
        Block.Image(GIF, "sample animation"),
        Block.Table(
            listOf(
                row(true, "Week", "Posts", "Comments", "Votes", "Rewards"),
                row(false, "1", "12", "140", "1,204", "35.120 HBD"),
                row(false, "2", "9", "98", "877", "28.004 HBD"),
            ),
        ),
        p(
            "Justified paragraph text that runs long enough to wrap over several lines on a phone screen, so that both of its edges line up with the column.",
            BlockStyle(justify = true),
        ),
        Block.Paragraph(listOf(Inline.Text("Thanks "), Inline.Mention("hive.blog"), Inline.Text(" and "), Inline.Mention("ecency"), Inline.Text(" for reading."))),
        // A Speak voice player with a synthetic waveform (no stream: the demo plays nothing).
        Block.SpeakAudio("", 83_000, List(48) { i -> 0.15 + 0.85 * ((i * 37 + 11) % 17) / 16.0 }),
    )
}
