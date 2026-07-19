# STEP 1 — plugin.xml: Optional Dependencies

## Goal

Remove the hard `org.jetbrains.kotlin` dependency(blocks PyCharm) and replace it with an optional one . Add an optional Python dep for PyCharm.

## Current State

```xml
<depends > com.intellij.modules.platform < / depends >
<depends > org.jetbrains.kotlin < / depends >
```

## Target State

```xml
<depends > com.intellij.modules.platform < / depends >

<!--Enabled only when Kotlin plugin present : IDEA, Android Studio -->
<depends optional ="true" config -file = "maxvibes-kotlin.xml" > org.jetbrains.kotlin < / depends >

<!--Enabled only when Python plugin present : PyCharm, IDEA Ultimate -->
<depends optional ="true" config -file = "maxvibes-python.xml" > com.intellij.modules.python < / depends >
```

## New File : maxvibes -kotlin.xml

Path: `maxvibes-plugin/src/main/resources/META-INF/maxvibes-kotlin.xml`

Move the K2 extension here(must leave plugin.xml or it fails validation on PyCharm):

```xml
<idea - plugin >
<extensions defaultExtensionNs ="org.jetbrains.kotlin" >
<supportsKotlinPluginMode supportsK2 ="true" / >
</extensions >
</idea - plugin >
```

## New File : maxvibes -python.xml

Path: `maxvibes-plugin/src/main/resources/META-INF/maxvibes-python.xml`

```xml
<idea - plugin >
<!--Python - specific extensions go here in the future-- >
</idea - plugin >
```

## Migration Summary

| Element | Before | After |
|---------|--------|-------|
| `<depends>com.intellij.modules.platform</depends>` | plugin.xml | plugin.xml(unchanged) |
| `<depends>org.jetbrains.kotlin</depends>` | plugin.xml(hard) | plugin.xml optional → maxvibes -kotlin.xml |
| `<supportsKotlinPluginMode supportsK2="true"/>` | plugin.xml extensions | maxvibes -kotlin.xml |
| Python dependency | — | plugin.xml optional → maxvibes -python.xml |

## Risk

Low.The `supportsKotlinPluginMode` extension * * must * * move — leaving it in plugin . xml without a hard Kotlin dependency causes validation failure on PyCharm .
