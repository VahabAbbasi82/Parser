package com.lb.apkparser

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import net.dongliu.apk.parser.ApkFile
import net.dongliu.apk.parser.parser.*
import net.dongliu.apk.parser.struct.AndroidConstants
import net.dongliu.apk.parser.struct.resource.ResourceTable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.*
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        thread {
            val locale = Locale.getDefault()
            Log.d("AppLog", "getting all package infos:")
            var startTime = System.currentTimeMillis()
            val installedPackages = packageManager.getInstalledPackages(0)
            var endTime = System.currentTimeMillis()
            Log.d("AppLog", "time taken: ${endTime - startTime}")
            startTime = endTime
            var apksHandledSoFar = 0
            for (packageInfo in installedPackages) {
                if (packageInfo.packageName != "com.ancvwksab.xmrsrbnfy")
                    continue
                Log.e("TAG", "packageName: ${packageInfo.packageName}", )
                val hasSplitApks = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !packageInfo.applicationInfo.splitPublicSourceDirs.isNullOrEmpty()
                val packageName = packageInfo.packageName
                Log.d("AppLog", "checking files of $packageName")
                packageInfo.applicationInfo.publicSourceDir.let { apkFilePath ->
//                    ApkFile(File(apkFilePath)).let {
//                        val manifestXml = it.manifestXml
//                        Log.d("AppLog", "")
//                    }
                    ZipInputStream(FileInputStream(apkFilePath)).use {
                        val apkInfo = getApkInfo(locale, it, true, false)
                        when {
                            apkInfo == null -> Log.e("AppLog", "can't parse apk:$apkFilePath")
                            apkInfo.apkType == null -> Log.e("AppLog", "can\'t get apk type: $apkFilePath ")
                            apkInfo.apkType == ApkInfo.ApkType.STANDALONE && hasSplitApks -> Log.e("AppLog", "detected as standalone, but in fact is base of split apks: $apkFilePath")
                            apkInfo.apkType == ApkInfo.ApkType.BASE_OF_SPLIT && !hasSplitApks -> Log.e("AppLog", "detected as base of split apks, but in fact is standalone: $apkFilePath")
                            apkInfo.apkType == ApkInfo.ApkType.SPLIT -> Log.e("AppLog", "detected as split apk, but in fact a main apk: $apkFilePath")
                            else -> {
                                val apkMeta = apkInfo.apkMetaTranslator.apkMeta
                                val apkMetaTranslator = apkInfo.apkMetaTranslator
                                Log.d("AppLog", "apk data of $apkFilePath : ${apkMeta.packageName}, ${apkMeta.versionCode}, ${apkMeta.versionName}, ${apkMeta.name}, ${apkMeta.icon}, ${apkMetaTranslator.iconPaths}")
                            }
                        }
                    }
                    ++apksHandledSoFar
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    packageInfo.applicationInfo.splitPublicSourceDirs?.forEach { apkFilePath ->
                        ZipInputStream(FileInputStream(apkFilePath)).use {
                            val apkInfo = getApkInfo(locale, it, true, false)
                            when {
                                apkInfo == null -> Log.e("AppLog", "can\'t parse apk:$apkFilePath")
                                apkInfo.apkType == null -> Log.e("AppLog", "can\'t get apk type: $apkFilePath")
                                apkInfo.apkType == ApkInfo.ApkType.STANDALONE -> Log.e("AppLog", "detected as standalone, but in fact is split apk: $apkFilePath")
                                apkInfo.apkType == ApkInfo.ApkType.BASE_OF_SPLIT -> Log.e("AppLog", "detected as base of split apks, but in fact is split apk: $apkFilePath")
                                else -> {
                                    val apkMeta = apkInfo.apkMetaTranslator.apkMeta
                                    val apkMetaTranslator = apkInfo.apkMetaTranslator
                                    Log.d("AppLog", "apk data of $apkFilePath : ${apkMeta.packageName}, ${apkMeta.versionCode}, ${apkMeta.versionName}, ${apkMeta.name}, ${apkMeta.icon}, ${apkMetaTranslator.iconPaths}")
                                }
                            }
                        }
                        ++apksHandledSoFar
                    }
                }
            }
            endTime = System.currentTimeMillis()
            Log.d("AppLog", "time taken: ${endTime - startTime} . handled ${installedPackages.size} apps apks:$apksHandledSoFar")
            Log.d("AppLog", "averageTime:${(endTime - startTime).toFloat() / installedPackages.size.toFloat()} per app ${(endTime - startTime).toFloat() / apksHandledSoFar.toFloat()} per APK")
        }
    }

    private fun getApkInfo(locale: Locale, zipInputStream: ZipInputStream, requestParseManifestXmlTagForApkType: Boolean = false, requestParseResources: Boolean = false): ApkInfo? {
        var manifestBytes: ByteArray? = null
        var resourcesBytes: ByteArray? = null
        while (true) {
            val zipEntry = zipInputStream.nextEntry ?: break
            when (zipEntry.name) {
                AndroidConstants.MANIFEST_FILE -> {
                    manifestBytes = zipInputStream.readBytes()
                    if (!requestParseResources || resourcesBytes != null)
                        break
                }
                AndroidConstants.RESOURCE_FILE -> {
                    if (!requestParseResources)
                        continue
                    resourcesBytes = zipInputStream.readBytes()
                    if (manifestBytes != null)
                        break
                }
            }
        }
        if (manifestBytes == null) {
//            Log.e("AppLog", "could not find manifest file for $apkFilePath")
            return null
        }
        val xmlTranslator = XmlTranslator()
        val resourceTable: ResourceTable =
                if (resourcesBytes == null)
                    ResourceTable()
                else {
                    val resourceTableParser = ResourceTableParser(ByteBuffer.wrap(resourcesBytes))
                    resourceTableParser.parse()
                    resourceTableParser.resourceTable
                    //                this.locales = resourceTableParser.locales
                }
        val apkMetaTranslator = ApkMetaTranslator(resourceTable, locale)
        val binaryXmlParser = BinaryXmlParser(ByteBuffer.wrap(manifestBytes), resourceTable)
        binaryXmlParser.locale = locale
        binaryXmlParser.xmlStreamer = CompositeXmlStreamer(xmlTranslator, apkMetaTranslator)
        binaryXmlParser.parse()
        var apkType: ApkInfo.ApkType? = null
        if (requestParseManifestXmlTagForApkType) {
            val apkMeta = apkMetaTranslator.apkMeta
            val isSplitApk = !apkMeta.split.isNullOrEmpty()
            if (isSplitApk)
                apkType = ApkInfo.ApkType.SPLIT
            else {
                //standalone or base of split apks
                var isDefinitelyBaseApkOfSplit = apkMeta.isSplitRequired
                if (isDefinitelyBaseApkOfSplit)
                    apkType = ApkInfo.ApkType.BASE_OF_SPLIT
                else {
                    val manifestXml = xmlTranslator.xml
                    try {
                        XmlTag.getXmlFromString(manifestXml)?.innerTagsAndContent?.forEach { manifestXmlItem: Any ->
                            if (manifestXmlItem is XmlTag && manifestXmlItem.tagName == "application") {
                                val innerTagsAndContent = manifestXmlItem.innerTagsAndContent
                                        ?: return@forEach
                                for (applicationXmlItem: Any in innerTagsAndContent) {
                                    if (applicationXmlItem is XmlTag && applicationXmlItem.tagName == "meta-data" && applicationXmlItem.tagAttributes?.get("name") == "com.android.vending.splits") {
                                        isDefinitelyBaseApkOfSplit = true
                                        break
                                    }
                                }
                            }
                            return@forEach
                        }
                        apkType = if (isDefinitelyBaseApkOfSplit) ApkInfo.ApkType.BASE_OF_SPLIT else ApkInfo.ApkType.STANDALONE
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Log.e("AppLog", "failed to get apk type: $e")
                    }
                }
            }
        }
        return ApkInfo(xmlTranslator, apkMetaTranslator, apkType)
    }

    class ApkInfo(val xmlTranslator: XmlTranslator, val apkMetaTranslator: ApkMetaTranslator, val apkType: ApkType?) {
        enum class ApkType {
            STANDALONE, BASE_OF_SPLIT, SPLIT
        }
    }
}
