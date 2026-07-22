package com.authzmatrix.handler

import burp.api.montoya.core.ToolType
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import com.authzmatrix.core.AppContext

/**
 * Observes proxy traffic: harvests JWTs (always) and, when auto mode is on, replays each
 * in-scope request against every persona. Our own replays go out as EXTENSIONS tool
 * traffic, not PROXY, so they never feed back into this handler.
 */
class ProxyHttpHandler(private val ctx: AppContext) : HttpHandler {

    override fun handleHttpRequestToBeSent(request: HttpRequestToBeSent): RequestToBeSentAction {
        if (request.toolSource().isFromTool(ToolType.PROXY)) {
            ctx.capture.inspectRequest(request)
        }
        return RequestToBeSentAction.continueWith(request)
    }

    override fun handleHttpResponseReceived(response: HttpResponseReceived): ResponseReceivedAction {
        if (response.toolSource().isFromTool(ToolType.PROXY)) {
            val req = response.initiatingRequest()
            ctx.capture.inspectResponse(response, "${req.method()} ${req.path()}")
            if (ctx.api.scope().isInScope(req.url()) && !com.authzmatrix.core.StaticAssets.isStatic(req.url())) {
                if (ctx.autoMode.get()) ctx.autoTest(req)
                if (ctx.autoLowerMode.get()) ctx.autoLowerTest(req)
            }
        }
        return ResponseReceivedAction.continueWith(response)
    }
}
