package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRequestHeadersTest {
 @Test fun resolvesBearerAndApiKeyTemplates(){val credential=AiApiCredential(id="k",label="key",secret="secret-value");assertEquals(mapOf("Authorization" to "Bearer secret-value"),AiRequestHeaders.resolve(AiRequestHeaders.DEFAULT_JSON,credential));assertEquals(mapOf("X-API-Key" to "secret-value","X-Client" to "workbench"),AiRequestHeaders.resolve("{\"X-API-Key\":\"{{secret}}\",\"X-Client\":\"workbench\"}",credential))}
 @Test fun allowsNoAuthentication(){assertFalse(AiRequestHeaders.requiresCredential("{}"));val provider=AiProviderProfile(name="local",baseUrl="https://local.test",headersJson="{}");assertTrue(provider.configured);assertEquals(emptyMap<String,String>(),AiRequestHeaders.resolve("{}",null))}
 @Test(expected=IllegalArgumentException::class) fun rejectsHostOverride(){AiRequestHeaders.parse("{\"Host\":\"other\"}")}
 @Test(expected=IllegalArgumentException::class) fun rejectsContentLengthOverride(){AiRequestHeaders.parse("{\"Content-Length\":\"1\"}")}
 @Test(expected=IllegalStateException::class) fun requiresEnabledCredentialWhenTemplateUsesSecret(){AiRequestHeaders.resolve(AiRequestHeaders.DEFAULT_JSON,null)}
}
