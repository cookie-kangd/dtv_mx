package dtv.mobile.util

/**
 * 把 ISO-8601 的 UTC 时间串（如 GitHub Release 的 published_at："2026-09-27T12:00:00Z"）
 * 按设备本地时区格式化成可读时间（yyyy-MM-dd HH:mm）。
 *
 * 之前「设置 → 关于」的更新卡片是把 UTC 串直接去掉 T/Z 显示，国内用户看到的时间比
 * 实际晚 8 小时、又没有时区标注，很容易误判成"这个版本很旧"。
 * 解析失败时退化为原串的简单处理，保证不抛异常。
 */
expect fun formatIsoUtcToLocal(iso: String): String
