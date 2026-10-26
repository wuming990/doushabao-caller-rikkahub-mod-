package me.rerere.ai.provider.providers.claude

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * v1012：记住「哪个供应商的哪个型号，请求要长什么样才吐得出内容」。
 *
 * 键 = `providerId|modelId`。带上 modelId 是因为同一个中转站常常同时挂新旧两代 Claude，
 * 一个型号要剥字段不代表另一个也要。
 *
 * ## 存的是「字段组合」，不是「第几格」
 *
 * v1012 存的是阶梯下标（裸整数）。阶梯是**算出来的派生列表**，把「第几格」当持久状态，
 * 等于给未来的重排留雷：同一个数字换一套阶梯就指向别的东西；而且越界值会被 `coerceIn`
 * **夹到最后一格**，也就是把用户无声钉死在「什么都不发」上。
 * 现在直接存 [ClaudeRequestFlags] —— 自描述，重排不改语义，也没有越界这回事。
 *
 * ## 只存实测成功过的组合
 *
 * 第一版是「撞一次 400 就 +1 档并立刻落盘」，等于**一次误判永久改坏**：上下文超长、
 * 图片过大、工具 schema 非法这些同样回 400 的情况，会把该型号一路钉到最退化那一档，
 * 而且用户完全看不见。
 * 现在：请求内的逐档试探只改内存变量，**只有真的吐出可见内容那一刻**才落盘。
 * 于是「学到某个组合」这件事本身就带着「那个组合确实能用」的证明。
 *
 * ## 落盘形态与失败处理
 *
 * 沿用 [me.rerere.ai.util.KeyRoulette.lru] 的做法（同样是「撞出来的经验值」，重启不该重来）。
 * 读写失败一律静默降级为「什么都没学过」，唯一后果是下次重新试一遍，不影响请求；
 * **刻意不打日志** —— ai 模块单测没开 returnDefaultValues，`android.util.Log` 一调就抛
 * 「not mocked」把测试打死（这条坑项目里记过档，解码器的跳帧计数也因此放解码器、
 * 日志放使用方）。
 */
class ClaudeCompatMemory(
    private val file: File? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val winners = ConcurrentHashMap<String, ClaudeRequestFlags>().also { loaded ->
        // 属性初始化器里不能写 return（编译器会当场拒绝），所以改成嵌套判空而不是提前退出。
        runCatching {
            val raw = file?.takeIf { it.exists() }?.readText().orEmpty()
            if (raw.isNotBlank()) {
                json.decodeFromString<Map<String, ClaudeRequestFlags>>(raw).forEach { (k, v) ->
                    loaded[k] = v
                }
            }
        }
        // v1012 的旧文件是 Map<String,Int>（裸下标），这里按对象解必然解不动 →
        // 整库当空 → 「视为没学过、重学」。不替旧下标猜含义是故意的：
        // 它的语义绑在当时的阶梯形状上，猜一次就是引入一个新错误。
    }

    /** 这个组合上次是用哪套字段成功的；没见过 = 完整形态（一档都不剥）。 */
    fun flagsFor(key: String): ClaudeRequestFlags = winners[key] ?: ClaudeRequestFlags()

    /**
     * 这一档真的吐出过内容 → 记住。
     *
     * 两种情况不写盘：值没变（每条消息都会调它，不能变成每条一次磁盘写）；
     * 以及「记的就是默认值」—— 完整形态本来就是没学过时的返回值，
     * 为它建一个文件纯属噪音。
     */
    fun remember(key: String, flags: ClaudeRequestFlags) {
        // 成功一次就作废本会话的「撞底下限」：一次 400 不该把这台设备钉到重启为止 ——
        // 上游抽风是暂时的，而旧写法既看不见也没地方清（设置页那个出口只在真的学到过东西时才出现）。
        // 两个表都空时（绝大多数时候）直接跳过，免得每条消息都去摸一次并发表。
        // 不附加「这档不是默认档」这种条件：成功本身就证明「底」是过时结论。
        // 多一道门就多一种「钉死没解除」的可能（二审 P2）。
        if (winners.isNotEmpty() || exhausted.isNotEmpty()) exhausted.remove(key)
        val previous = winners.put(key, flags)
        if (previous == flags) return
        if (previous == null && flags == ClaudeRequestFlags()) {
            winners.remove(key)
            return
        }
        persist()
    }

    /**
     * 本会话内「这一路的阶梯已经撞到底」的下限。**刻意不落盘** ——
     * 它是一次探测的临时结论，不是实测成功的事实：落盘就等于把「今天上游抽风」
     * 永久记成「这家不行」，而重启后本来就该重新试一次。
     *
     * 为什么需要它：生成层按用户的续写次数（可到 99）整请求重发，而每一趟都会
     * 从第 0 档重撞一遍阶梯 —— 最坏 99 × 7 ≈ 700 次请求。次数归用户决定，
     * 但让他花的每一次都重复上一次的白撞，是我们的问题。
     */
    private val exhausted = ConcurrentHashMap<String, Int>()

    fun exhaustedFloor(key: String): Int = exhausted[key] ?: 0

    fun rememberExhausted(key: String, index: Int) {
        if (index > exhaustedFloor(key)) exhausted[key] = index
    }

    /** 设置页「重新试探」用：清掉学到的组合与本会话的撞底记录，下次从完整形态重新试。 */
    fun forget(key: String) {
        exhausted.remove(key)
        if (winners.remove(key) != null) persist()
    }

    /** 给设置页显示「当前实际用哪种形态」。 */
    fun describe(key: String): String? = winners[key]?.label()

    /**
     * 某个供应商下**所有**已学到的形态：modelId 到形态描述。
     *
     * 设置页要能看见并清掉这些，否则「这家不认提示词缓存」就是一个用户永远看不到、
     * 也没法撤销的静默涨价 —— 学到的东西一旦落盘就必须可见，这是它落盘的代价。
     */
    fun entriesForProvider(providerId: String): List<Pair<String, String>> {
        val prefix = "$providerId|"
        return winners.entries.filter { it.key.startsWith(prefix) && it.value != ClaudeRequestFlags() }
            .map { it.key.removePrefix(prefix) to it.value.label() }
            .sortedBy { it.first }
    }

    /**
     * 这家供应商有没有「撞到底」的记录。
     *
     * [entriesForProvider] 只列实测成功过的组合，所以「一路撞底、一次都没成功」那种
     * 恰恰不在里面 —— 而那种情况下软件同样在背着用户从最退化档起步，出口必须一起给。
     */
    fun hasExhaustedForProvider(providerId: String): Boolean {
        val prefix = "$providerId|"
        return exhausted.keys.any { it.startsWith(prefix) }
    }

    /** 清掉某个供应商下的全部学习记录（设置页「重新试探」用）。 */
    fun forgetProvider(providerId: String) {
        val prefix = "$providerId|"
        val keys = winners.keys.filter { it.startsWith(prefix) } +
            exhausted.keys.filter { it.startsWith(prefix) }
        if (keys.isEmpty()) return
        keys.forEach { winners.remove(it); exhausted.remove(it) }
        persist()
    }

    /**
     * 先写临时文件再 rename —— Linux 上 rename 是原子的。
     *
     * 直接 writeText 会在写的中途被进程杀掉（换档学习发生在网络回调链上，App 随时可能被回收），
     * 留下半截 JSON；下次启动整库解不动 = 用户学到的东西无声消失。
     * rename 失败时退回直写，至少不比原来更差。
     */
    private fun persist() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json.encodeToString(winners.toMap()))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    companion object {
        private const val FILE_NAME = "claude_request_compat.json"

        /**
         * 进程内共享实例。设置页要显示与清除学到的形态，而 provider 内部才是写入方 ——
         * 两边必须拿同一份内存，否则「重新试探」清了个空对象，用户按了等于没按。
         */
        @Volatile
        private var shared: ClaudeCompatMemory? = null

        fun default(): ClaudeCompatMemory = ClaudeCompatMemory(null)

        fun of(context: Context): ClaudeCompatMemory = synchronized(this) {
            shared ?: ClaudeCompatMemory(File(context.cacheDir, FILE_NAME)).also { shared = it }
        }
    }
}
