package com.pulseforge.apk

import com.pulseforge.apk.axml.AxmlNode
import com.pulseforge.model.ComponentInfo
import com.pulseforge.model.ComponentType
import com.pulseforge.model.FeatureUse
import com.pulseforge.model.IntentFilterInfo
import com.pulseforge.model.ManifestInfo
import com.pulseforge.model.PermissionUse

/**
 * Maps a parsed binary-XML tree onto the typed [ManifestInfo] model.
 * Attribute lookups match on the local name, because packers sometimes emit a variant namespace.
 */
object ManifestReader {

    fun read(root: AxmlNode): ManifestInfo {
        require(root.name == "manifest") { "root element is <${root.name}>, expected <manifest>" }

        val usesSdk = root.child("uses-sdk")
        val application = root.child("application")

        val components = buildList {
            application?.let { app ->
                app.childrenNamed("activity").forEach { add(component(it, ComponentType.ACTIVITY)) }
                app.childrenNamed("activity-alias").forEach { add(component(it, ComponentType.ACTIVITY)) }
                app.childrenNamed("service").forEach { add(component(it, ComponentType.SERVICE)) }
                app.childrenNamed("receiver").forEach { add(component(it, ComponentType.RECEIVER)) }
                app.childrenNamed("provider").forEach { add(component(it, ComponentType.PROVIDER)) }
            }
        }

        val requested = buildList {
            root.childrenNamed("uses-permission").forEach { add(permission(it)) }
            root.childrenNamed("uses-permission-sdk-23").forEach { add(permission(it)) }
        }.distinctBy { it.name }

        val declared = root.childrenNamed("permission").map { node ->
            PermissionUse(
                name = node.attrValue("name").orEmpty(),
                protectionLevel = node.attrValue("protectionLevel"),
                declaredByApp = true
            )
        }.filter { it.name.isNotEmpty() }

        val features = root.childrenNamed("uses-feature").map { node ->
            FeatureUse(
                name = node.attrValue("name").orEmpty(),
                required = node.boolAttr("required") ?: true,
                glEsVersion = node.attrValue("glEsVersion")
            )
        }.filter { it.name.isNotEmpty() }

        var attributeCount = 0
        root.walk { attributeCount += it.attributes.size }

        return ManifestInfo(
            packageName = root.attrValue("package").orEmpty(),
            versionName = root.attrValue("versionName"),
            versionCode = root.longAttr("versionCode"),
            minSdk = usesSdk?.intAttr("minSdkVersion"),
            targetSdk = usesSdk?.intAttr("targetSdkVersion"),
            applicationLabel = application?.attrValue("label"),
            applicationClass = application?.attrValue("name"),
            debuggable = application?.boolAttr("debuggable"),
            allowBackup = application?.boolAttr("allowBackup"),
            usesCleartextTraffic = application?.boolAttr("usesCleartextTraffic"),
            networkSecurityConfig = application?.attrValue("networkSecurityConfig"),
            extractNativeLibs = application?.boolAttr("extractNativeLibs"),
            testOnly = application?.boolAttr("testOnly"),
            fullBackupContent = application?.attrValue("fullBackupContent"),
            dataExtractionRules = application?.attrValue("dataExtractionRules"),
            requestLegacyExternalStorage = application?.boolAttr("requestLegacyExternalStorage"),
            components = components,
            permissions = requested,
            declaredPermissions = declared,
            features = features,
            rawAttributeCount = attributeCount
        )
    }

    private fun component(node: AxmlNode, type: ComponentType): ComponentInfo {
        val filters = node.childrenNamed("intent-filter").map { filter(it) }
        return ComponentInfo(
            type = type,
            name = node.attrValue("name").orEmpty(),
            exported = node.boolAttr("exported"),
            enabled = node.boolAttr("enabled") ?: true,
            hasIntentFilter = filters.isNotEmpty(),
            permission = node.attrValue("permission"),
            readPermission = node.attrValue("readPermission"),
            writePermission = node.attrValue("writePermission"),
            authorities = node.attrValue("authorities"),
            processName = node.attrValue("process"),
            taskAffinity = node.attrValue("taskAffinity"),
            launchMode = node.attrValue("launchMode"),
            intentFilters = filters
        )
    }

    private fun filter(node: AxmlNode): IntentFilterInfo {
        val data = node.childrenNamed("data")
        fun values(attr: String) = data.mapNotNull { it.attrValue(attr) }.filter { it.isNotEmpty() }.distinct()
        return IntentFilterInfo(
            actions = node.childrenNamed("action").mapNotNull { it.attrValue("name") }.distinct(),
            categories = node.childrenNamed("category").mapNotNull { it.attrValue("name") }.distinct(),
            schemes = values("scheme"),
            hosts = values("host"),
            mimeTypes = values("mimeType"),
            paths = (values("path") + values("pathPrefix") + values("pathPattern")).distinct(),
            autoVerify = node.boolAttr("autoVerify") ?: false
        )
    }

    private fun permission(node: AxmlNode) = PermissionUse(
        name = node.attrValue("name").orEmpty(),
        maxSdkVersion = node.intAttr("maxSdkVersion")
    )
}
