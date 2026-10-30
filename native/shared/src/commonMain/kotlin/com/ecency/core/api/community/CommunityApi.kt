package com.ecency.core.api.community

import com.ecency.core.api.EcencyException
import com.ecency.core.api.guarded
import com.ecency.core.api.model.arr
import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.render.js.Js
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** `bridge.list_communities` sorts. */
enum class CommunitySort(val apiValue: String) { RANK("rank"), NEW("new"), SUBS("subs") }

/** A page of community subscribers and the cursor for the next one (null at the end). */
data class SubscriberPage(val items: List<Subscriber>, val next: String?)

/** A page of the community log and the cursor for the next one (null at the end). */
data class ActivityPage(val items: List<CommunityActivity>, val next: String?)

/**
 * The community and account lookup reads the RN app makes through @ecency/sdk, with the SDK's parameters:
 * `getCommunitiesQueryOptions`, `getCommunityQueryOptions`, `getAccountSubscriptionsQueryOptions`,
 * `getCommunitySubscribersInfiniteQueryOptions`, `getAccountNotificationsInfiniteQueryOptions`,
 * `lookupAccountsQueryOptions` and `getTrendingTagsQueryOptions`.
 */
class CommunityApi(private val client: HiveRpcClient) {

    /** `bridge.list_communities`; [query] null lists all, [observer] adds each community's context. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun communities(
        sort: CommunitySort = CommunitySort.RANK,
        query: String? = null,
        limit: Int = 100,
        observer: String? = null,
    ): List<Community> = guarded {
        val result = client.call(
            "bridge.list_communities",
            buildJsonObject {
                put("last", "")
                put("limit", limit)
                put("sort", sort.apiValue)
                put("query", query?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
                observer?.takeIf { it.isNotEmpty() }?.let { put("observer", it) }
            },
        )
        result.arr().orEmpty().mapNotNull(Community::fromJson)
    }

    /** `bridge.get_community`; null when it does not exist. The SDK always sends an observer ("" for none). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun community(name: String, observer: String? = null): Community? = guarded {
        Community.fromJson(
            client.call(
                "bridge.get_community",
                buildJsonObject {
                    put("name", name)
                    put("observer", observer.orEmpty())
                },
            ),
        )
    }

    /**
     * `bridge.get_community_context` (SDK getCommunityContextQueryOptions): [account]'s role in [community],
     * "guest" when it has none.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun role(account: String, community: String): String = guarded {
        val o = client.call("bridge.get_community_context", buildJsonObject { put("account", account); put("name", community) }) as? JsonObject
        (o?.get("role") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: "guest"
    }

    /** `bridge.list_all_subscriptions`: every community [account] subscribed to. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun subscriptions(account: String): List<Subscription> = guarded {
        client.call("bridge.list_all_subscriptions", buildJsonObject { put("account", account) })
            .arr().orEmpty().mapNotNull(Subscription::fromJson)
    }

    /**
     * `bridge.list_subscribers`, [SUBSCRIBERS_PAGE_SIZE] per page. [last] is omitted on the first page: hivemind
     * reads "" as a real cursor and returns nothing. A short page is the end.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun subscribers(community: String, last: String? = null): SubscriberPage = guarded {
        val rows = client.call(
            "bridge.list_subscribers",
            buildJsonObject {
                put("community", community)
                put("limit", SUBSCRIBERS_PAGE_SIZE)
                last?.takeIf { it.isNotEmpty() }?.let { put("last", it) }
            },
        ).arr().orEmpty()
        val items = rows.mapNotNull(Subscriber::fromJson)
        SubscriberPage(items, if (rows.size >= SUBSCRIBERS_PAGE_SIZE) items.lastOrNull()?.account else null)
    }

    /** `bridge.account_notifications` for a community account (its activity log). A short page is the end. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun activities(account: String, limit: Int = ACTIVITY_PAGE_SIZE, lastId: String? = null): ActivityPage = guarded {
        val rows = client.call(
            "bridge.account_notifications",
            buildJsonObject {
                put("account", account)
                put("limit", limit)
                // sent back as hivemind gave it (a string), as the SDK does
                lastId?.let { put("last_id", it) }
            },
        ).arr().orEmpty()
        val items = rows.mapNotNull(CommunityActivity::fromJson)
        ActivityPage(items, if (rows.size >= limit) items.lastOrNull()?.id else null)
    }

    /**
     * `condenser_api.lookup_accounts`: names from [prefix] on, in chain order (a neighbour comes back even for a
     * name that does not exist). A prefix the chain cannot hold (over 16 bytes) answers nothing without a call.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun lookupAccounts(prefix: String, limit: Int = 50): List<String> = guarded {
        if (!isQueryableAccountName(prefix)) return@guarded emptyList()
        client.call("condenser_api.lookup_accounts", buildJsonArray { add(JsonPrimitive(prefix)); add(JsonPrimitive(limit)) })
            .arr().orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    /** `condenser_api.get_trending_tags` from [afterTag]: tag names without "" and community names (SDK). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun trendingTags(afterTag: String = "", limit: Int = 20): List<String> = guarded {
        client.call("condenser_api.get_trending_tags", buildJsonArray { add(JsonPrimitive(afterTag)); add(JsonPrimitive(limit)) })
            .arr().orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("name")?.let { n -> (n as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } }
            .filter { it.isNotEmpty() && !it.startsWith("hive-") }
    }

    companion object {
        /** Hivemind caps list_subscribers at 100 rows (SDK SUBSCRIBERS_PAGE_SIZE). */
        const val SUBSCRIBERS_PAGE_SIZE = 100

        /** RN communityActivitiesScreen PAGE_SIZE. */
        const val ACTIVITY_PAGE_SIZE = 50

        /** SDK isQueryableAccountName: non-empty and at most 16 UTF-8 bytes. */
        fun isQueryableAccountName(value: String?): Boolean = !value.isNullOrEmpty() && Js.utf8(value).size <= 16
    }
}
