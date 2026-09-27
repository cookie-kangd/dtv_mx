package dtv.mobile.repo

import dtv.mobile.model.Platform
import dtv.mobile.model.Streamer
import kotlinx.coroutines.flow.Flow

data class DouyuCate1(
  val id: String,
  val name: String,
  val cate2List: List<DouyuCate2>,
)

data class DouyuCate2(
  val id: String,
  val name: String,
  val shortName: String,
  val iconUrl: String?,
)

data class DouyuCate3(
  val id: String,
  val name: String,
  val iconUrl: String? = null,
)

data class DouyuCategories(
  val cate1List: List<DouyuCate1>,
)

data class PagedResult<T>(
  val items: List<T>,
  val total: Int? = null,
)

data class DouyuPlayVariant(
  val name: String,
  val rate: Int,
  val bit: Int? = null,
)

data class DouyuPlayInfo(
  val cdns: List<String>,
  val variants: List<DouyuPlayVariant>,
)

data class DanmakuMessage(
  val roomId: String,
  val user: String,
  val content: String,
  val userLevel: Int = 0,
  val fansClubLevel: Int = 0,
  val color: String? = null,
)

data class TwitchCate(
  val id: String,
  val name: String,
)

data class TwitchVariant(
  val name: String,
  val display: String,
  val url: String,
)

data class TwitchPlayInfo(
  val variants: List<TwitchVariant>,
)

data class TwitchPage(
  val items: List<Streamer>,
  val cursor: String?,
  val hasMore: Boolean,
)

data class BilibiliQrCode(
  val url: String,
  val qrcodeKey: String,
)

enum class BilibiliQrStatus { Waiting, Scanned, Confirmed, Expired, Failed }

data class BilibiliQrPollResult(
  val status: BilibiliQrStatus,
  val message: String? = null,
)

interface DtvRepository {
  suspend fun searchAnchors(platform: Platform, keyword: String): List<Streamer>

  /**
   * Fetch live status for an existing streamer.
   *
   * Returns null when the platform doesn't support querying or the request fails.
   */
  suspend fun fetchLiveStatus(streamer: Streamer): Boolean?

  /**
   * Refresh a followed streamer card snapshot (avatar/nickname/title/viewers/live).
   *
   * Returns null when the platform doesn't support querying or the request fails.
   */
  suspend fun fetchFollowedStreamerSnapshot(streamer: Streamer): Streamer?

  suspend fun fetchHuyaCategories(): List<HuyaCate1>

  suspend fun fetchBilibiliCategories(): List<BilibiliCate1>

  suspend fun fetchDouyinCategories(): List<DouyinCate1>

  suspend fun fetchDouyuCategories(): DouyuCategories

  suspend fun fetchDouyuThreeCate(cate2Id: String): List<DouyuCate3>

  suspend fun fetchDouyuLiveListByCate2(cate2Id: String, offset: Int, limit: Int): PagedResult<Streamer>

  suspend fun fetchDouyuLiveListByCate3(cate3Id: String, page: Int, limit: Int): PagedResult<Streamer>

  suspend fun fetchDouyuPlayInfo(roomId: String): DouyuPlayInfo

  suspend fun resolveDouyuStreamUrl(roomId: String, quality: String? = null, cdn: String? = null): String

  fun observeDouyuDanmaku(roomId: String): Flow<DanmakuMessage>

  fun observeHuyaDanmaku(roomId: String): Flow<DanmakuMessage>

  fun observeDouyinDanmaku(webRid: String): Flow<DanmakuMessage>

  suspend fun fetchHuyaLiveList(gid: String, page: Int, limit: Int): PagedResult<Streamer>

  suspend fun resolveHuyaStreamUrl(roomId: String): String

  suspend fun fetchDouyinPartitionLiveList(
    partition: String,
    partitionType: String,
    offset: Int,
    limit: Int,
    msToken: String,
  ): PagedResult<Streamer>

  suspend fun resolveDouyinStreamUrl(webRid: String, desiredQuality: String? = null): String

  suspend fun fetchBilibiliLiveList(parentAreaId: Int, areaId: Int, page: Int, pageSize: Int): PagedResult<Streamer>

  suspend fun resolveBilibiliStreamUrl(roomId: String, qn: Int? = null): String

  fun observeBilibiliDanmaku(roomId: String): Flow<DanmakuMessage>

  suspend fun fetchTwitchCategories(): List<TwitchCate>

  /**
   * Twitch 直播列表。
   *
   * 没有 cursor 参数：匿名 GQL 客户端带游标翻页会被服务端 integrity check 拒绝，
   * 第二页永远拿不到（详见 TwitchApiAndroid 的注释），因此 Twitch 固定为单页。
   *
   * @param gameSlug null = 「推荐」；非 null = 该分类下的直播
   * @param chineseOnly 仅对「推荐」生效：true 时只取中文（ZH）频道，
   *   并按「中文人气总榜 + 中文谈天说地 + 中文IRL」聚合，行为对齐网页版中文推荐
   */
  suspend fun fetchTwitchLiveList(gameSlug: String?, limit: Int, chineseOnly: Boolean): TwitchPage

  suspend fun searchTwitchChannels(keyword: String): List<Streamer>

  suspend fun fetchTwitchPlayInfo(login: String): TwitchPlayInfo

  suspend fun resolveTwitchStreamUrl(login: String, quality: String? = null): String

  fun observeTwitchDanmaku(login: String): Flow<DanmakuMessage>

  suspend fun generateBilibiliQrCode(): BilibiliQrCode

  suspend fun pollBilibiliQrCode(qrcodeKey: String): BilibiliQrPollResult

  suspend fun getBilibiliCookie(): String?

  suspend fun mergeBilibiliCookie(cookieHeader: String)

  suspend fun clearBilibiliCookie()
}
