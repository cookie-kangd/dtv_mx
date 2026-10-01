package dtv.mobile.util

/**
 * 当前时间戳（UTC 毫秒）。
 *
 * 抖音签名（ABogus）要把绝对时间写进签名字段，所以这里必须是墙上时钟，
 * 不能用单调时钟替代。commonMain 里直接写 System.currentTimeMillis() 属于
 * JVM 依赖，会让这一层在 Android 之外编译不过，故收敛成 expect/actual。
 *
 * 注意：**测量耗时不要用这个函数** —— 那种场景请用 kotlin.time 的
 * TimeSource.Monotonic，既没有平台依赖，也不会被系统时间跳变影响。
 */
expect fun currentTimeMillis(): Long
