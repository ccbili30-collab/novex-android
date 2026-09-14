package novex.conversation

import org.junit.Assert.*
import org.junit.Test

class RequestCapacityTest {
    private fun measured(n:Long)=TokenMeasurement(n,"测试计量输入，非实际模型分词",false)
    @Test fun `一百万窗口包含输出预留而超量不截断`() {
        val model=ModelCapacity(1_000_000,32_000)
        assertEquals(CapacityDecision.Fits(0,false),RequestCapacity.check(model,1_000_000,8_000,measured(992_000)))
        assertEquals(CapacityDecision.Exceeded(1,false),RequestCapacity.check(model,1_000_000,8_000,measured(992_001)))
    }
    @Test fun `未知能力和超模型设置不能被视为可发送`() {
        assertEquals(CapacityDecision.UnknownModelWindow,RequestCapacity.check(ModelCapacity(null),128_000,8_000,measured(1)))
        assertTrue(RequestCapacity.check(ModelCapacity(128_000),1_000_000,8_000,measured(1)) is CapacityDecision.InvalidSetting)
        assertTrue(RequestCapacity.check(ModelCapacity(128_000,4096),128_000,8_000,measured(1)) is CapacityDecision.InvalidSetting)
    }
    @Test fun `保留估算性质且大整数不溢出`() {
        assertEquals(CapacityDecision.Exceeded(1,true),RequestCapacity.check(ModelCapacity(Long.MAX_VALUE),Long.MAX_VALUE,1,
            TokenMeasurement(Long.MAX_VALUE,"估算来源",true)))
    }
}
