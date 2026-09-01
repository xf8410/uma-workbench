package com.uma.workbench.agent

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterFreeModelsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val refresher = OpenRouterFreeModelRefresher(json)

    /** 真实 OpenRouter /models 响应结构（节选，字段结构一致）。 */
    private val apiResponse = """
    {"data":[
      {"id":"deepseek/deepseek-r1:free","name":"DeepSeek R1","pricing":{"prompt":"0","completion":"0","request":"0","image":"0"}},
      {"id":"qwen/qwen3-235b-a22b:free","name":"Qwen3 235B","pricing":{"prompt":"0","completion":"0"}},
      {"id":"anthropic/claude-sonnet-4","name":"Claude Sonnet 4","pricing":{"prompt":"0.000003","completion":"0.000015"}},
      {"id":"google/gemini-2.0-flash-exp:free","name":"Gemini Flash Exp","pricing":{"prompt":"0","completion":"0"}},
      {"id":"meta/llama-3.3-70b-instruct","name":"Llama 70B","pricing":{"prompt":"0.0000001","completion":"0.0000002"}}
    ]}
    """.trimIndent()

    @Test fun extractFreePicksZeroPricingAndFreeSuffix() {
        val free = refresher.extractFree(json.parseToJsonElement(apiResponse))
        assertEquals(
            listOf(
                "deepseek/deepseek-r1:free",
                "google/gemini-2.0-flash-exp:free",
                "qwen/qwen3-235b-a22b:free"
            ),
            free
        )
    }

    @Test fun pricingOnlyOneSideZeroIsNotFree() {
        val oneSided = """{"data":[{"id":"x/y","pricing":{"prompt":"0","completion":"0.00001"}}]}"""
        assertTrue(refresher.extractFree(json.parseToJsonElement(oneSided)).isEmpty())
    }

    private fun catalog(defaultModel: AiModelSelection? = null) = AiProviderCatalog(
        providers = listOf(
            AiProviderProfile(id = "or", name = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1", models = listOf("paid/manual-model")),
            AiProviderProfile(id = "other", name = "别的", baseUrl = "https://api.example.com/v1", models = listOf("m1"))
        ),
        defaultModel = defaultModel
    )

    @Test fun mergeInjectsFreeModelsIntoOpenRouterProviderOnly() {
        val merged = catalog().mergedWithFreeModels(listOf("a:free", "b:free"))
        val or = merged.providers.first { it.id == "or" }
        val other = merged.providers.first { it.id == "other" }
        assertEquals(listOf("a:free", "b:free", "paid/manual-model"), or.models)
        assertEquals(listOf("m1"), other.models)
    }

    @Test fun mergeClearsExpiredDefaultModelButKeepsActive() {
        // 默认选了「今天还免费」的模型 → 保留
        val active = catalog(AiModelSelection("or", "a:free")).mergedWithFreeModels(listOf("a:free", "b:free"))
        assertEquals(AiModelSelection("or", "a:free"), active.defaultModel)
        // 默认选了「昨天免费、今天到期」的模型 → 清空（到期自动关上）
        val expired = catalog(AiModelSelection("or", "expired:free")).mergedWithFreeModels(listOf("a:free", "b:free"))
        assertNull(expired.defaultModel)
        // 默认选手填付费模型 → 不受影响
        val manual = catalog(AiModelSelection("or", "paid/manual-model")).mergedWithFreeModels(listOf("a:free"))
        assertEquals(AiModelSelection("or", "paid/manual-model"), manual.defaultModel)
    }

    @Test fun stripRemovesInjectedFreeKeepsManualAndDefaultModel() {
        val free = listOf("a:free", "b:free")
        val merged = catalog(AiModelSelection("or", "a:free")).mergedWithFreeModels(free)
        // 用户在免费期内手动加了两个模型（一个免费一个付费）
        val withUserEdits = merged.copy(providers = merged.providers.map {
            if (it.id == "or") it.copy(models = it.models + "user/paid") else it
        })
        val stripped = withUserEdits.strippedOfFreeModels(free)
        val or = stripped.providers.first { it.id == "or" }
        assertEquals(listOf("paid/manual-model", "user/paid"), or.models)
        // defaultModel 保留（load 时校验，免费期内不丢）
        assertEquals(AiModelSelection("or", "a:free"), stripped.defaultModel)
    }

    @Test fun saveLoadRoundTripKeepsManualOnly() {
        val free = listOf("a:free")
        val merged = catalog().mergedWithFreeModels(free)
        val persisted = merged.strippedOfFreeModels(free) // save()
        // 免费池轮换：第二天 b:free 免费、a:free 到期
        val reloaded = persisted.mergedWithFreeModels(listOf("b:free")) // load()
        val or = reloaded.providers.first { it.id == "or" }
        assertEquals(listOf("b:free", "paid/manual-model"), or.models)
    }
}
