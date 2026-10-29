package com.ecency.core.render.methods

/**
 * render-helper `consts/`: the frontend whitelist, profile sections and the per-element regexes.
 * Sources are the JS literals verbatim, compiled through [JsRegex] (flags noted per pattern). The
 * hot patterns (linkify, IMG_REGEX, ENTITY_REGEX, YOUTUBE_REGEX) are scanners in [Scanners].
 * Every regex is compiled once and is immutable, so it is safe to share across threads.
 */
internal object Consts {
    val WHITE_LIST = listOf(
        "ecency.com", "hive.blog", "peakd.com", "snapie.io", "hivesuite.app", "travelfeed.io", "dapplr.in", "leofinance.io",
        "inleo.io", "proofofbrain.io", "stemgeeks.net", "hiveblockexplorer.com", "proofofbrain.blog", "weedcash.network",
        "dapplr.in", "liketu.com", "bilpcoin.com", "inji.com",
    )

    val SECTION_LIST = listOf(
        "wallet", "feed", "followers", "following", "points", "communities", "posts", "blog", "comments", "replies",
        "settings", "engine", "permissions", "referrals", "payout", "activities", "spk", "trail",
    )

    private fun i(src: String) = JsRegex.compile(src, ignoreCase = true)
    private fun cs(src: String) = JsRegex.compile(src, ignoreCase = false)

    val POST_REGEX by lazy { i("""^https?:\/\/([^/]+)\/([^/]+)\/(@[\w.\d-]+)\/(.+)$""") }
    val CCC_REGEX by lazy { i("""^https?:\/\/(.*)\/ccc\/([\w.\d-]+)\/(.*)""") }
    val MENTION_REGEX by lazy { i("""^https?:\/\/(.*)\/(@[\w.\d-]+)$""") }
    val TOPIC_REGEX by lazy { i("""^https?:\/\/(.*)\/(trending|hot|created|promoted|muted|payout)\/(.*)$""") }
    val INTERNAL_MENTION_REGEX by lazy { i("""^\/@[\w.\d-]+$""") }
    val INTERNAL_TOPIC_REGEX by lazy { i("""^\/(trending|hot|created|promoted|muted|payout)\/(.*)$""") }
    val INTERNAL_POST_TAG_REGEX by lazy { i("""^(.+?)\/(@[\w.\d-]+)\/(.*)$""") }
    val INTERNAL_POST_REGEX by lazy { i("""^\/(@[\w.\d-]+)\/(.*)$""") }
    val CUSTOM_COMMUNITY_REGEX by lazy { i("""^https?:\/\/(.*)\/c\/(hive-\d+)(.*)""") }
    val YOUTUBE_EMBED_REGEX by lazy { i("""^(https?:)?\/\/(?:www\.|m\.)?youtube\.com\/(embed|shorts)\/.*""") }
    val VIMEO_REGEX by lazy { i("""(https?:\/\/)?(www\.)?(?:vimeo)\.com.*(?:videos|video|channels|)\/([\d]+)""") }
    val VIMEO_EMBED_REGEX by lazy { cs("""https:\/\/player\.vimeo\.com\/video\/([0-9]+)(?:$|[?#])""") }
    val BITCHUTE_REGEX by lazy { i("""^(?:https?:\/\/)?(?:www\.)?bitchute\.com\/(?:video|embed)\/([a-z0-9_-]+)""") }
    val D_TUBE_REGEX by lazy { cs("""(https?:\/\/d\.tube\/#!\/v\/)(\w+)\/(\w+)""") }
    val D_TUBE_REGEX2 by lazy { cs("""(https?:\/\/d\.tube\/v\/)(\w+)\/(\w+)""") }
    val D_TUBE_EMBED_REGEX by lazy { i("""^https:\/\/emb\.d\.tube\/#!\/[^/?#]+\/[^/?#]+(?:$|[?#])""") }
    val TWITCH_REGEX by lazy { i("""https?:\/\/(?:www\.)?twitch\.tv\/(?:(videos)\/)?([a-zA-Z0-9][\w]{3,24})""") }
    val DAPPLR_REGEX by lazy { i("""^(https?:)?\/\/[a-z]*\.dapplr\.in\/file\/dapplr-videos\/.*""") }
    val TRUVVL_REGEX by lazy { i("""^https?:\/\/embed\.truvvl\.com\/(@[\w.\d-]+)\/(.*)""") }
    val LBRY_REGEX by lazy { i("""^(https?:)?\/\/lbry\.tv\/\$\/embed\/[^?#]+(?:$|[?#])""") }
    val ODYSEE_REGEX by lazy { i("""^(https?:)?\/\/odysee\.com\/(?:\$|%24)\/embed\/[^?#]+(?:$|[?#])""") }
    val ODYSEE_WATCH_REGEX by lazy {
        i(
            """^(?:https?:)?\/\/odysee\.com\/(@[^/?#\s:"'<>\\]+:[^/?#\s:"'<>\\]+\/[^/?#\s:"'<>\\]+:[^/?#\s:"'<>\\]+|[^@/?#\s:"'<>\\][^/?#\s:"'<>\\]*:[^/?#\s:"'<>\\]+)(?:$|[?#])""",
        )
    }
    val SKATEHIVE_IPFS_REGEX by lazy { i("""^https?:\/\/ipfs\.skatehive\.app\/ipfs\/([^/?#]+)""") }
    val SKATEHYPE_EMBED_REGEX by lazy { i("""^(https?:)?\/\/(www\.)?skatehype\.com\/ifplay\.php\?v=\d+(?:$|[&#])""") }
    val ARCH_REGEX by lazy { i("""^(https?:)?\/\/archive\.org\/embed\/[^/?#]+(?:$|[?#])""") }
    val SPEAK_REGEX by lazy {
        i("""(?:https?:\/\/(?:(?:play\.)?3speak\.([a-z]+)\/watch\?v=)|(?:(?:play\.)?3speak\.([a-z]+)\/embed\?v=))([A-Za-z0-9_\-\.\/]+)(&.*)?""")
    }
    val SPEAK_EMBED_REGEX by lazy { i("""^(https?:)?\/\/(?:play\.)?3speak\.([a-z]+)\/(?:embed|watch)\?.+$""") }
    val SPEAK_AUDIO_REGEX by lazy { i("""https?:\/\/audio\.3speak\.tv\/play\?[^\s]+""") }
    val SPEAK_AUDIO_EMBED_REGEX by lazy { i("""^https?:\/\/audio\.3speak\.tv\/play\?.+$""") }
    val LIKETU_AUDIO_REGEX by lazy { i("""^https?:\/\/cdn\.liketu\.com\/.+\.(?:webm|mp3|m4a|ogg|wav)(?:\?.*)?$""") }

    /** `/g` in JS, but every use resets it (match() or lastIndex = 0 before exec), so it is stateless. */
    val TWITTER_REGEX by lazy { i("""(?:https?:\/\/(?:(?:twitter\.com\/(.*?)\/status\/(.*))))""") }

    /** `/g` in JS; a.method resets lastIndex before exec. */
    val SPOTIFY_REGEX by lazy { i("""^https:\/\/open\.spotify\.com\/playlist\/(.*)?$""") }
    val RUMBLE_REGEX by lazy { cs("""^https:\/\/rumble\.com\/embed\/([a-zA-Z0-9-]+)\/\?pub=\w+""") }
    val BRIGHTEON_REGEX by lazy { i("""^https?:\/\/(www\.)?brighteon\.com\/(?:embed\/)?(.*[0-9].*)""") }
    val VIMM_EMBED_REGEX by lazy { i("""^https:\/\/www\.vimm\.tv\/[^?#]+(?:$|[?#])""") }
    val SPOTIFY_EMBED_REGEX by lazy {
        i("""^https:\/\/open\.spotify\.com\/(embed|embed-podcast)\/(playlist|show|episode|track|album)\/([^/?#]+)(?:$|[?#])""")
    }
    val SOUNDCLOUD_EMBED_REGEX by lazy { i("""^https:\/\/w\.soundcloud\.com\/player\/\?[^#]+$""") }
    val TWITCH_EMBED_REGEX by lazy { i("""^(https?:)?\/\/player\.twitch\.tv\/(?:\?[^/]+)?$""") }
    val BRAND_NEW_TUBE_REGEX by lazy { i("""^https:\/\/brandnewtube\.com\/embed\/[a-z0-9]+$""") }
    val LOOM_REGEX by lazy { i("""^(https?:)?\/\/www\.loom\.com\/share\/([^/?#]+)(?:$|[?#])""") }
    val LOOM_EMBED_REGEX by lazy { i("""^(https?:)?\/\/www\.loom\.com\/embed\/([^/?#]+)(?:$|[?#])""") }
    val AUREAL_EMBED_REGEX by lazy { i("""^(https?:)?\/\/(www\.)?(?:aureal-embed)\.web\.app\/([0-9]+)(?:$|[?#])""") }

    // Inline patterns from the methods.
    val NORMALIZE_DISPLAY_HOST by lazy { i("""^https?:\/\/(www\.)?(ecency\.com|peakd\.com|hive\.blog|leofinance\.io|inleo\.io|snapie\.io|hivesuite\.app)""") }
    val PROPOSAL_IDS by lazy { cs("""proposal_ids=\[(\d+)]""") }
    val SHORTS_ID by lazy { i("""\/shorts\/([A-Za-z0-9_-]{11})\/?(?=[?#]|$)""") }
    val SOUNDCLOUD_URL by lazy { cs("""url=(.+?)(?:&|$)""") }
    val EXTERNAL_SCHEME by lazy { i("""^((#)|(mailto:)|(\/(?!\/))|(((steem|hive|esteem|ecency|https?):)?\/\/))""") }
}
