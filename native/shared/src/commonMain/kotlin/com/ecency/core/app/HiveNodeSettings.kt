package com.ecency.core.app

import com.ecency.core.api.EcencyException
import com.ecency.core.api.rpc.HiveApiException
import com.ecency.core.api.rpc.HiveNodes
import com.ecency.core.hive.ChainTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the server check in settings found (RN `_changeApi`'s three outcomes). */
enum class ServerCheck {
    /** It answered and its head block is recent: RN "alert.connection_success". */
    OK,

    /** No usable answer within 5 s: RN "alert.connection_fail". */
    UNREACHABLE,

    /** It answered, but its chain time is 15 s or more behind the device: RN "settings.server_fail". */
    STALE,
}

/**
 * The Hive node pool and the settings server picker, as the RN app does them (`queries/sdk-config.ts`
 * initSdkConfig, `hive.ts` checkClient, `ecency.ts` getNodes, `settingsContainer` `_changeApi`). The settings
 * screen ([SettingsModel]) uses [servers], [selected] and [select].
 *
 * At start ([start]) the saved server (when one is saved and not blocked) is put in front of the built-in list at
 * once, then the published list (`public-nodes.json`) replaces the built-in one; the client's health ranking
 * orders the pool from there, as in the SDK. Blocked nodes never enter it, a saved one included.
 */
class HiveNodeSettings internal constructor(private val env: AppEnvironment) {
    private var started = false

    /** The list the pool was last built from (built-in until the published one arrived). */
    private var list: List<String> = HiveNodes.DEFAULT

    /**
     * The server the user picked in settings (RN `settings.server`), or null. A saved value that is not a safe
     * server ([HiveNodes.userServer]: https, no user or password, not blocked) reads as none: the default pool.
     */
    fun selected(): String? = HiveNodes.userServer(env.prefs.getString(PREFS_KEY))

    /**
     * Installs the pool: the saved server first, at once; the published list when it arrives. Idempotent. Runs only
     * in the apps' real builds ([AppEnvironment.managesHiveNodes]); a demo run keeps its offline node.
     */
    @Throws(CancellationException::class)
    suspend fun start() {
        if (started || !env.managesHiveNodes) return
        started = true
        install(HiveNodes.DEFAULT)
        install(servers())
    }

    /**
     * RN useGetServersQuery / getNodes: the published node list without blocked nodes, or the built-in list when
     * it cannot be read. The picker lists these.
     */
    @Throws(CancellationException::class)
    suspend fun servers(): List<String> = try {
        HiveNodes.parseRemote(env.rest.get(HiveNodes.REMOTE_LIST_URL))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } ?: HiveNodes.DEFAULT

    /**
     * RN `_changeApi`'s check: `condenser_api.get_dynamic_global_properties` on [server] alone within 5 s, and its
     * chain time less than 15 s behind the device clock.
     */
    @Throws(CancellationException::class)
    suspend fun check(server: String): ServerCheck {
        val node = HiveNodes.sanitize(listOf(server)).singleOrNull() ?: return ServerCheck.UNREACHABLE
        val time = try {
            ((env.api.client.ask(node, "condenser_api.get_dynamic_global_properties", JsonArray(emptyList()), CHECK_TIMEOUT_MILLIS) as? JsonObject)
                ?.get("time") as? JsonPrimitive)?.takeIf { it.isString }?.content
        } catch (e: CancellationException) {
            throw e
        } catch (_: HiveApiException) {
            null
        } ?: return ServerCheck.UNREACHABLE
        val serverMillis = try {
            ChainTime.toEpochSeconds(time) * 1000
        } catch (_: Exception) {
            return ServerCheck.UNREACHABLE
        }
        return if (env.nowMillis() - serverMillis < MAX_LAG_MILLIS) ServerCheck.OK else ServerCheck.STALE
    }

    /**
     * Picks [server] in settings: checked first ([check]); when it passes it is saved and the pool becomes
     * [server] followed by the rest of [list] (RN `setHiveNodes([server, ...serverList])`). Otherwise nothing
     * changes. A blocked server never passes.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun select(server: String, list: List<String>): ServerCheck {
        val node = HiveNodes.userServer(server) ?: return ServerCheck.UNREACHABLE
        val result = check(node)
        if (result != ServerCheck.OK) return result
        env.prefs.putString(PREFS_KEY, node)
        this.list = list.ifEmpty { HiveNodes.DEFAULT }
        env.api.client.setNodes(HiveNodes.withFirst(node, this.list))
        return result
    }

    /**
     * The saved server changed outside [select] (the RN settings import): the pool is built again from it and the
     * last list. Does nothing before [start] or in a demo run.
     */
    internal fun applySaved() {
        if (started) install(list)
    }

    private fun install(list: List<String>) {
        this.list = list
        val pool = HiveNodes.pool(selected(), list)
        if (pool.isNotEmpty()) env.api.client.setNodes(pool)
    }

    companion object {
        /** RN stores it in the settings schema as `server`. */
        const val PREFS_KEY = "settings.server"

        /** RN `_changeApi`: an AbortController at 5 s, alive when the chain time lags less than 15 s. */
        const val CHECK_TIMEOUT_MILLIS = 5_000L
        const val MAX_LAG_MILLIS = 15_000L
    }
}
