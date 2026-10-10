package com.abdownloadmanager.desktop.nativemessaging.host

import com.abdownloadmanager.desktop.utils.singleInstance.IPCServiceProviderAwakerSupport
import com.abdownloadmanager.desktop.utils.singleInstance.service.IDefaultAppIPCService
import com.abdownloadmanager.integration.model.AddDownloadsFromIntegration
import com.abdownloadmanager.integration.model.ApiCapabilities
import com.abdownloadmanager.desktop.utils.AppInfo
import kotlinx.serialization.json.Json

class AddDownloadNativeRequestHandler(
    private val json: Json,
    private val appIPC: IPCServiceProviderAwakerSupport<IDefaultAppIPCService>,
) : NativeMessagingMessageHandler {
    override fun accept(request: NativeMessagingMessage.Content): Boolean {
        return request.action == "add"
    }

    override suspend fun handle(request: NativeMessagingMessage.Content): NativeMessagingMessage.Content {
        val integrationRequest = json.decodeFromString<AddDownloadsFromIntegration>(request.payload)
        appIPC.getService().useService {
            it.addDownloadByGui(integrationRequest)
        }
        return context(json) {
            NativeMessagingMessage.Content.boolean(true)
        }
    }
}

class PingNativeRequestHandler(
    private val json: Json
) : NativeMessagingMessageHandler {
    override fun accept(request: NativeMessagingMessage.Content): Boolean {
        return request.action == "ping"
    }

    override suspend fun handle(request: NativeMessagingMessage.Content): NativeMessagingMessage.Content {
        return context(json) {
            NativeMessagingMessage.Content.boolean(true)
        }
    }
}

/**
 * What this build can download. Answered by the native host itself (same build as the app); live tool state
 * (ffmpeg) is only reported by the app's HTTP /capabilities.
 */
class CapabilitiesNativeRequestHandler(
    private val json: Json
) : NativeMessagingMessageHandler {
    override fun accept(request: NativeMessagingMessage.Content): Boolean {
        return request.action == "capabilities"
    }

    override suspend fun handle(request: NativeMessagingMessage.Content): NativeMessagingMessage.Content {
        return context(json) {
            NativeMessagingMessage.Content.create(
                ApiCapabilities(
                    app = AppInfo.displayName,
                    version = AppInfo.version.toString(),
                    jobTypes = ApiCapabilities.JOB_TYPES,
                    features = ApiCapabilities.baseFeatures(),
                )
            )
        }
    }
}

object DefinedNativeMessagingHandlers {
    fun getAll(
        json: Json, appIPC: IPCServiceProviderAwakerSupport<IDefaultAppIPCService>,
    ): List<NativeMessagingMessageHandler> {
        return listOf(
            AddDownloadNativeRequestHandler(json, appIPC),
            PingNativeRequestHandler(json),
            CapabilitiesNativeRequestHandler(json),
        )
    }
}
