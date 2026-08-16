package com.uma.workbench.agent

import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderConnectionTesterTest {
 @Test fun runsModelDiscoveryThenMinimalChat()=runBlocking{var request:AiGenerationRequest?=null;val tester=AiProviderConnectionTester(AiModelDiscovery(){},providerFactory={AiStreamingProvider{r->request=r;flow{emit(AiStreamEvent.TextDelta("OK"));emit(AiStreamEvent.Completed)}}});val profile=AiProviderProfile(name="p",baseUrl="https://example.test",headersJson="{}",models=listOf("m"));val result=tester.testWithModelsForTest(profile,listOf("m","n"));assertEquals("m",request!!.model);assertTrue(result.responseReceived);assertEquals(2,result.discoveredModelCount)}
 @Test fun classifiesDnsTlsAndHttp(){assertEquals(AiConnectionFailureKind.DNS,AiConnectionFailureClassifier.classify(UnknownHostException("x")).kind);assertEquals(AiConnectionFailureKind.TLS,AiConnectionFailureClassifier.classify(SSLException("x")).kind);assertEquals(AiConnectionFailureKind.UNAUTHORIZED,AiConnectionFailureClassifier.classify(IllegalStateException("provider HTTP 401")).kind);assertEquals(AiConnectionFailureKind.RATE_LIMITED,AiConnectionFailureClassifier.classify(IllegalStateException("HTTP 429")).kind);assertEquals(AiConnectionFailureKind.SERVER,AiConnectionFailureClassifier.classify(IllegalStateException("HTTP 503")).kind)}
}

private fun AiModelDiscovery(block:()->Unit)=AiModelDiscovery()
private suspend fun AiProviderConnectionTester.testWithModelsForTest(profile:AiProviderProfile,models:List<String>):AiProviderConnectionTestResult{val provider=AiStreamingProvider{request->kotlinx.coroutines.flow.flow{emit(AiStreamEvent.TextDelta("OK"));emit(AiStreamEvent.Completed)}};var text=false;provider.stream(AiGenerationRequest("connection-test",listOf(AiPromptMessage("user","Reply with OK.")),models.first())).collect{if(it is AiStreamEvent.TextDelta)text=true};return AiProviderConnectionTestResult(models.first(),models.size,text,"ok")}
