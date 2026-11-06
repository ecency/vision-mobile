package com.ecency.core.app

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.ecency.BlogClaim
import com.ecency.core.api.ecency.HostingApi
import com.ecency.core.auth.AuthException
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ecency Pro copy (web `pro.*` wording where it fits, issue #3555). No text points to buying Pro anywhere else. */
object ProCopy {
    val TITLE: String get() = L.native.pro.title
    val SUBTITLE: String get() = L.native.pro.subtitle
    val MEMBER: String get() = L.native.pro.member
    fun until(date: String) = L.native.pro.memberUntil(date = date)
    val CARD_PLAIN: String get() = L.native.pro.cardDescriptionPlain
    fun card(price: String) = L.native.pro.cardDescription(price = price)
    val BENEFITS: List<String> get() = listOf(L.native.pro.benefitBadge, L.native.pro.benefitBlog, L.native.pro.benefitSupport, L.native.pro.benefitEarly)
    fun buy(price: String) = L.native.pro.buy(price = price)
    fun extend(price: String) = L.native.pro.extend(price = price)
    val EXTEND_NOTE: String get() = L.native.pro.extendNote
    val UNAVAILABLE: String get() = L.native.pro.unavailable
    val ACTIVATING: String get() = L.native.pro.activating
    val SUCCESS_TITLE: String get() = L.native.pro.successTitle
    val SUCCESS_BODY: String get() = L.native.pro.successBody
    val EXTENDING: String get() = L.native.pro.extending
    val OPEN: String get() = L.native.pro.openA11y
    val CLOSE: String get() = UiCopy.CLOSE

    // the free blog (#3636, web `pro-blog.*`): no template, accent or font pickers, the default look
    val BLOG_CLAIM: String get() = L.native.pro.blogClaim
    fun blogIntro(address: String) = L.native.pro.blogIntro(address = address)
    val BLOG_TITLE_LABEL: String get() = L.native.pro.blogTitleLabel
    val BLOG_DESCRIPTION_LABEL: String get() = L.native.pro.blogDescriptionLabel
    val BLOG_CLAIM_CONFIRM: String get() = L.native.pro.blogClaimConfirm
    val BLOG_CANCEL: String get() = L.alert.cancel
    val BLOG_CLAIMED: String get() = L.native.pro.blogClaimed
    val BLOG_EXISTING: String get() = L.native.pro.blogExisting
    val BLOG_OPEN: String get() = L.native.pro.blogOpen
    val BLOG_NOT_PRO: String get() = L.native.pro.blogNotPro
    val BLOG_UNAVAILABLE: String get() = L.native.pro.blogUnavailable
    val BLOG_FAILED: String get() = L.native.pro.blogFailed
    val BLOG_LOGIN: String get() = L.native.pro.blogLogin
}

data class ProState(
    /** The signed-in account: the only one a purchase here is for (no gifting). */
    val account: String? = null,
    /** On the Pro roster (the badge's list, [ProMembersModel]). */
    val member: Boolean = false,
    /** The store's localized price of [PurchaseSkus.PRO]; null until known or when the store does not sell it. */
    val price: String? = null,
    val loadingPrice: Boolean = false,
    /** The store answered without the product. */
    val unavailable: Boolean = false,
    /** The Pro sheet is open. */
    val open: Boolean = false,
    /** The store sheet or the order is under way ([PurchaseServiceState.busy]). */
    val buying: Boolean = false,
    /** Ecency accepted this account's Pro purchase ([ProPurchases]; also one that settled or was recovered). */
    val purchased: Boolean = false,
    /** The account was a member already when it bought: the purchase extends the membership. */
    val extending: Boolean = false,
    /** A pending payment or an order still being processed. */
    val notice: String? = null,
    val alert: StoreAlert? = null,
    /** When the account's membership ends (Ecency's Points API, `pro.expires`); null when not known. */
    val expiresMillis: Long? = null,
    /** The member's free blog (#3636): whether it exists is being asked. */
    val blogChecking: Boolean = false,
    /** The blog's address once known (it existed, or the claim made it). */
    val blogUrl: String? = null,
    /** This claim made the blog; false: it was there already. */
    val blogClaimed: Boolean = false,
    /** The claim form is shown. */
    val blogFormOpen: Boolean = false,
    /** The profile's name and about are being read into the form. */
    val blogPrefilling: Boolean = false,
    val blogTitle: String = "",
    val blogDescription: String = "",
    /** The claim is on its way. */
    val blogClaiming: Boolean = false,
    /** Why the claim failed. */
    val blogError: AppError? = null,
) {
    /** "Claim your free blog": a member whose blog is not known to exist, once that was asked. */
    val showBlogClaim: Boolean get() = account != null && member && blogUrl == null && !blogChecking && !blogFormOpen
    val canClaimBlog: Boolean get() = account != null && member && blogFormOpen && !blogPrefilling && !blogClaiming
    /** The blog's address without its scheme ({username}.blogs.ecency.com). */
    val blogAddress: String? get() = blogUrl?.takeIf { member }?.removePrefix("https://")?.trimEnd('/')
    val blogHeading: String get() = if (blogClaimed) ProCopy.BLOG_CLAIMED else ProCopy.BLOG_EXISTING
    /** The address the form claims, before there is an answer. */
    val blogIntro: String get() = ProCopy.blogIntro(account?.let { com.ecency.core.api.ecency.HostingApi.blogUrl(it).removePrefix("https://") }.orEmpty())

    /** "Your membership runs until {date}." for a member whose end date is known (read when shown: language). */
    val expiryText: String? get() = expiresMillis?.takeIf { member }?.let { ProCopy.until(LocaleFormat.date(it)) }
    /** Paid and accepted, not on the roster yet. */
    val activating: Boolean get() = purchased && !member
    val succeeded: Boolean get() = purchased && member
    val cardText: String get() = if (member) ProCopy.MEMBER else price?.let { ProCopy.card(it) } ?: ProCopy.CARD_PLAIN
    val buyLabel: String? get() = price?.let { if (member) ProCopy.extend(it) else ProCopy.buy(it) }
    val showBuy: Boolean get() = account != null && price != null && !purchased
    val canBuy: Boolean get() = showBuy && !buying
    val successTitle: String get() = if (extending) ProCopy.MEMBER else ProCopy.SUCCESS_TITLE
    val successBody: String get() = if (extending) ProCopy.EXTENDING else ProCopy.SUCCESS_BODY
}

/** Where an account's latest Pro purchase stands, app-wide ([ProPurchases]). */
enum class ProProgress { PENDING, PROCESSING, DELIVERED }

/** An account's latest Pro purchase: [progress] null while the store sheet is open or after a failure. */
data class ProPurchase(val progress: ProProgress?, val extending: Boolean)

/**
 * The latest Pro purchase per account, app-wide (one per app, [AppEnvironment.proPurchases]): [PurchaseService]
 * records what happened to each `1999pro` purchase for the account it is credited to, so a payment that settles
 * later, a recovered purchase, or an outcome that landed while Perks was closed shows when the sheet opens again.
 */
class ProPurchases internal constructor(
    dispatchers: AppDispatchers,
    /** Whether an account is on the roster now: an extension, for a purchase this run never saw start (a recovery). */
    private val isPro: (String) -> Boolean,
) : StateModel<Map<String, ProPurchase>>(emptyMap(), dispatchers) {
    /** A buy for [user] starts; [extending]: the account is a member already. A pending or processing one stays so. */
    internal fun started(user: String, extending: Boolean) = setState { m ->
        val key = user.lowercase()
        m + (key to ProPurchase(m[key]?.progress?.takeIf { it != ProProgress.DELIVERED }, extending))
    }

    internal fun update(user: String, progress: ProProgress?) = setState { m ->
        val key = user.lowercase()
        m + (key to ProPurchase(progress, m[key]?.extending ?: isPro(key)))
    }

    /** A pending entry the store no longer holds: of [user], or of every account (null). */
    internal fun dropPending(user: String?) = setState { m ->
        m.mapValues { (k, v) -> if (v.progress == ProProgress.PENDING && (user == null || k == user.lowercase())) v.copy(progress = null) else v }
    }

    /** The account saw its finished purchase. */
    internal fun clear(user: String) = setState { it - user.lowercase() }

    fun of(user: String?): ProPurchase? = user?.let { current[it.lowercase()] }
}

/**
 * The Pro card on Perks and its sheet (issue #3555): membership from the Pro roster, the store's price of
 * [PurchaseSkus.PRO], and the purchase through [PurchaseService] (the money rules, recovery and the order are the
 * same as for Points) for the signed-in account. What happened to the account's purchase comes from
 * [ProPurchases]; once Ecency accepted the order the roster is read again ([ProMembersModel.activate]) and until
 * the account shows on it the sheet says the membership is activating. Alerts belong to this screen's own buy.
 */
class ProModel internal constructor(private val env: AppEnvironment) : StateModel<ProState>(ProState(), env.dispatchers) {
    private var started = false

    /** The account this screen's buy is for, from the tap to its outcome (alerts only). */
    private var buyingFor: String? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.session.awaitLoaded()
            env.session.state.collect { s ->
                if (s.username != current.account) {
                    buyingFor = null
                    accountGen++
                    setState { ProState(account = s.username, price = it.price, unavailable = it.unavailable, open = it.open) }
                    sync()
                }
            }
        }
        scope.launch { env.proMembers.state.collect { sync() } }
        scope.launch { env.purchases.state.collect { sync() } }
        scope.launch { env.proPurchases.state.collect { sync() } }
        scope.launch {
            state.collect {
                readExpiry()
                readBlog()
            }
        }
        scope.launch { env.purchases.outcomes.collect { if (it.productId == PurchaseSkus.PRO) onOutcome(it) } }
        env.proMembers.ensureLoaded()
        loadPrice()
    }

    /** Everything that is not this screen's own: membership, a buy under way (any account's), the purchase's progress. */
    private fun sync() = setState { s ->
        val p = env.proPurchases.of(s.account)
        s.copy(
            member = s.account != null && env.proMembers.isPro(s.account),
            buying = PurchaseSkus.PRO in env.purchases.current.busy,
            purchased = p?.progress == ProProgress.DELIVERED,
            extending = p?.extending == true,
            notice = when (p?.progress) {
                ProProgress.PENDING -> PurchaseCopy.PENDING
                ProProgress.PROCESSING -> PurchaseCopy.PROCESSING
                else -> null
            },
        )
    }

    /** The account (and whether its purchase was delivered) the end date was read for: read again when either changes. */
    private var expiryReadFor: Pair<String, Boolean>? = null
    private var expiryJob: Job? = null

    /**
     * The member's end date (#3636) from the Points API's `pro.expires`, once per account, and again once a purchase
     * extended it. Only the newest read lands (an older one is cancelled); a failed read is tried again
     * [EXPIRY_TRIES] times in all, then shows no date.
     */
    private fun readExpiry() {
        val s = current
        val account = s.account
        if (account == null || !s.member) {
            expiryReadFor = null
            expiryJob?.cancel()
            if (s.expiresMillis != null) setState { it.copy(expiresMillis = null) }
            return
        }
        val key = account to s.purchased
        if (key == expiryReadFor) return
        expiryReadFor = key
        expiryJob?.cancel()
        expiryJob = scope.launch {
            var ends: Long? = null
            for (attempt in 1..EXPIRY_TRIES) {
                try {
                    ends = env.walletApi.proMembership(account)?.takeIf { it.active }?.expiresMillis
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (attempt < EXPIRY_TRIES) delay(EXPIRY_RETRY_MILLIS)
                }
            }
            if (expiryReadFor == key && current.account == account) setState { it.copy(expiresMillis = ends) }
        }
    }

    /** RN `getItems` for the one product. */
    fun loadPrice() {
        if (current.loadingPrice) return
        setState { it.copy(loadingPrice = true) }
        scope.launch {
            val (products, _) = env.purchases.products(listOf(PurchaseSkus.PRO))
            val product = products.firstOrNull { it.productId == PurchaseSkus.PRO }
            setState { it.copy(loadingPrice = false, price = product?.localizedPrice, unavailable = product == null) }
        }
    }

    fun open() {
        setState { it.copy(open = true) }
        if (current.price == null) loadPrice()
    }

    /** Closing keeps a purchase under way (its outcome is kept app-wide); a finished one starts over next time. */
    fun dismiss() {
        val s = current
        if (s.succeeded) s.account?.let { env.proPurchases.clear(it) }
        setState { it.copy(open = false) }
        sync()
    }

    fun dismissAlert() = setState { it.copy(alert = null) }

    /**
     * Buys (or, for a member, extends) Pro for the signed-in account. Refused while any Pro buy is under way (another
     * account's included: its outcome is not this account's).
     */
    fun buy() {
        val user = env.session.current.username
            ?: return setState { it.copy(alert = StoreAlert(PurchaseCopy.NOT_LOGGED_IN, PurchaseCopy.NOT_LOGGED_IN_DESC)) }
        val s = current
        if (!s.canBuy || s.account != user || PurchaseSkus.PRO in env.purchases.current.busy) return
        buyingFor = user
        env.proPurchases.started(user, extending = s.member)
        env.purchases.buy(PurchaseSkus.PRO, PurchaseTarget(user = user))
    }

    // ---- the free blog (#3636, web `features/pro/pro-blog-claim.tsx`)

    /** Moves on with every account change: an answer asked for under another one is dropped. */
    private var accountGen = 0
    private var blogReadFor: String? = null
    private var blogJob: Job? = null

    /**
     * Whether a member's blog exists (web's existence probe), once per account: an existing one shows its address
     * instead of the claim. A failed or abandoned lookup leaves the claim (web fails open: the claim answers honestly).
     */
    private fun readBlog() {
        val s = current
        val account = s.account
        if (account == null || !s.member) {
            blogReadFor = null
            blogJob?.cancel()
            if (s.blogChecking) setState { it.copy(blogChecking = false) }
            return
        }
        if (account == blogReadFor) return
        blogReadFor = account
        blogJob?.cancel()
        val gen = accountGen
        setState { it.copy(blogChecking = true) }
        blogJob = scope.launch {
            val url = try {
                env.hostingApi.blog(account)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (gen != accountGen || current.account != account) return@launch
            // a claim that answered first keeps its address
            setState { it.copy(blogChecking = false, blogUrl = it.blogUrl ?: url, blogFormOpen = it.blogFormOpen && it.blogUrl == null && url == null) }
        }
    }

    /** "Claim your free blog": the form, its title and description from the account's profile (web's prefill). */
    fun openBlogForm() {
        val s = current
        val account = s.account ?: return
        if (!s.member || s.blogUrl != null || s.blogFormOpen) return
        val prefill = s.blogTitle.isEmpty() && s.blogDescription.isEmpty()
        setState { it.copy(blogFormOpen = true, blogError = null, blogPrefilling = prefill) }
        if (!prefill) return
        val gen = accountGen
        scope.launch {
            val profile = try {
                env.api.accounts.getAccount(account)?.profile
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (gen != accountGen || current.account != account) return@launch
            setState {
                it.copy(
                    blogPrefilling = false,
                    // only empty fields are filled: what was typed meanwhile stays
                    blogTitle = it.blogTitle.ifEmpty { profile?.displayName?.take(HostingApi.TITLE_MAX).orEmpty() },
                    blogDescription = it.blogDescription.ifEmpty { profile?.about?.take(HostingApi.DESCRIPTION_MAX).orEmpty() },
                )
            }
        }
    }

    fun cancelBlogForm() {
        if (current.blogClaiming) return
        setState { it.copy(blogFormOpen = false, blogError = null) }
    }

    fun setBlogTitle(text: String) = setState { it.copy(blogTitle = text.take(HostingApi.TITLE_MAX)) }

    fun setBlogDescription(text: String) = setState { it.copy(blogDescription = text.take(HostingApi.DESCRIPTION_MAX)) }

    /**
     * Claims the signed-in member's blog with the form's title and description, on the default look. One claim at a
     * time; Ecency answers an existing blog unchanged ([BlogClaim.created] false). An answer that lands after the
     * account changed is dropped.
     */
    fun claimBlog() {
        val user = env.session.current.username ?: return
        val s = current
        if (!s.canClaimBlog || s.account != user) return
        val gen = accountGen
        setState { it.copy(blogClaiming = true, blogError = null) }
        scope.launch {
            val result = try {
                Result.success(env.hostingApi.claim(user, s.blogTitle, s.blogDescription))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (gen != accountGen || current.account != user) return@launch
            result.fold(
                onSuccess = { c -> setState { it.copy(blogClaiming = false, blogFormOpen = false, blogUrl = c.blogUrl, blogClaimed = c.created) } },
                onFailure = { e -> setState { it.copy(blogClaiming = false, blogError = blogError(e)) } },
            )
        }
    }

    /** web's messages by status: 403 not a member, 503 (and the proxy's 502) unavailable, the rest failed. */
    private fun blogError(e: Throwable): AppError = when {
        e is AuthException -> AppError(ErrorKind.AUTH, ProCopy.BLOG_LOGIN, false)
        e !is EcencyHttpException -> AppError(ErrorKind.UNKNOWN, ProCopy.BLOG_FAILED, true)
        e.status == 0 -> AppError.from(e)
        e.status == 401 -> AppError(ErrorKind.AUTH, ProCopy.BLOG_LOGIN, false)
        e.status == 403 -> AppError(ErrorKind.AUTH, ProCopy.BLOG_NOT_PRO, false)
        e.status == 502 || e.status == 503 -> AppError(ErrorKind.NETWORK, ProCopy.BLOG_UNAVAILABLE, true)
        else -> AppError(ErrorKind.UNKNOWN, ProCopy.BLOG_FAILED, true)
    }

    private fun onOutcome(o: PurchaseOutcome) {
        if (!o.fromBuy) return
        // an outcome of another account's buy (the account changed meanwhile) is not this screen's
        if (buyingFor == null || buyingFor != current.account) return
        when (o) {
            is PurchaseOutcome.Delivered, is PurchaseOutcome.Pending, is PurchaseOutcome.Processing -> buyingFor = null
            // another account's earlier purchase goes first: told here, its delivery is not this buy's
            is PurchaseOutcome.Earlier -> {
                if (!o.pending) buyingFor = null
                setState {
                    it.copy(alert = StoreAlert(PurchaseCopy.WARNING, if (o.pending) PurchaseCopy.earlierPending(o.target) else PurchaseCopy.earlierPurchase(o.target)))
                }
            }
            is PurchaseOutcome.Failed -> setState { it.copy(alert = StoreAlert(PurchaseCopy.WARNING, o.message)) }
            is PurchaseOutcome.Held -> setState { it.copy(alert = StoreAlert(PurchaseCopy.WARNING, PurchaseCopy.held(o.order))) }
            // a cancel is told nothing (IapReport); the rest as the other purchase screens
            is PurchaseOutcome.StoreFailed -> when {
                o.report != IapReport.REPORTED -> Unit
                o.error.isBillingUnavailable && env.store.platform == PurchaseService.PLAY ->
                    setState { it.copy(alert = StoreAlert(PurchaseCopy.WARNING, PurchaseCopy.GOOGLE_PLAY_VERSION)) }
                else -> setState { it.copy(alert = StoreAlert(PurchaseCopy.WARNING, o.error.message)) }
            }
        }
    }

    companion object {
        /** End date reads in all when they fail, this far apart. */
        const val EXPIRY_TRIES = 3
        const val EXPIRY_RETRY_MILLIS = 5_000L
    }
}
