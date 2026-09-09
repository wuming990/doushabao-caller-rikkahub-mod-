package me.rerere.rikkahub.agent.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v290：Kimi「RPM (Requests Per Minute) limit」限速文案必须被判为可恢复。
 * 真机实锤：两份关键词表都没接住这句话，多把密钥不换、续跑不触发，直接弹「消息生成失败」。
 */
class AgentErrorKindClassificationTest {

    @Test
    fun `Kimi的RPM限速报错判为可恢复`() {
        val msg = "RPM (Requests Per Minute) limit of the model kimi-k3 is exceeded. " +
            "Please try again later Request id: 021788670385441137f500080bf18603e76d17d12e7c43e53dbc5"
        assertEquals(AgentErrorKind.RECOVERABLE, AgentErrorKind.classify(msg))
    }

    @Test
    fun `Request_id长串不会被误提为状态码`() {
        // 长串内部没有词边界，不能因为包含 "500"/"429" 片段就误判（v284 同类教训）
        val msg = "request id: 021788670385441137f500080bf18603e76d17d12e7c43e53dbc5"
        assertEquals(AgentErrorKind.UNKNOWN, AgentErrorKind.classify(msg))
    }

    @Test
    fun `真正的服务器故障仍然判为可恢复`() {
        assertEquals(
            AgentErrorKind.RECOVERABLE,
            AgentErrorKind.classify("429 Too Many Requests, please retry later"),
        )
    }

    @Test
    fun `v291_TPM与天配额与节流文案判为可恢复`() {
        assertEquals(AgentErrorKind.RECOVERABLE, AgentErrorKind.classify("inference tpm exhausted"))
        assertEquals(AgentErrorKind.RECOVERABLE, AgentErrorKind.classify("tokens per minute limit reached"))
        assertEquals(AgentErrorKind.RECOVERABLE, AgentErrorKind.classify("requests per day exceeded"))
        assertEquals(AgentErrorKind.RECOVERABLE, AgentErrorKind.classify("Request throttled by upstream"))
    }
}
