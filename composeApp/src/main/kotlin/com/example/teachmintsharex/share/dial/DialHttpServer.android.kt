package com.example.teachmintsharex.share.dial

import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DialHttpServer implements the DIAL REST API and serves device description XML.
 * This is what Windows Connect app queries after discovering the device via SSDP.
 */
class DialHttpServer(
    private val onScreenMirrorRequest: () -> Unit,
    private val onWfdDescriptionRequest: ((String) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    private var deviceName: String = "TeachmintShareX"
    private var deviceUuid: String = "00000000-0000-0000-0000-000000000000"
    private var manufacturer: String = "Teachmint"
    private var modelName: String = "ShareX Display"
    private var localIp: String? = null
    private var httpPort: Int = 8080

    /**
     * Starts the DIAL HTTP server
     *
     * @param port Port to listen on (default 8080)
     * @param deviceName Friendly name of the device
     * @param deviceUuid Unique device identifier
     */
    fun start(
        port: Int = 8080,
        deviceName: String = "TeachmintShareX",
        deviceUuid: String,
        manufacturer: String = "Teachmint",
        modelName: String = "ShareX Display",
        localIp: String? = null,
    ): Int {
        this.deviceName = deviceName
        this.deviceUuid = deviceUuid
        this.manufacturer = manufacturer
        this.modelName = modelName
        this.localIp = localIp
        this.httpPort = port

        server = embeddedServer(CIO, port = port) {
            routing {
                // DIAL-compatible device description (default).
                get("/dd.xml") {
                    call.response.headers.append("Application-URL", applicationUrl())
                    call.respondText(
                        contentType = ContentType.Text.Xml,
                        text = getDialDeviceDescriptionXml()
                    )
                    println("DIAL_HTTP: 📄 Served device description XML to ${call.request.local.remoteHost}")
                }

                // Explicit DIAL description endpoint.
                get("/dial/dd.xml") {
                    call.response.headers.append("Application-URL", applicationUrl())
                    call.respondText(
                        contentType = ContentType.Text.Xml,
                        text = getDialDeviceDescriptionXml()
                    )
                    println("DIAL_HTTP: 📄 Served DIAL description XML to ${call.request.local.remoteHost}")
                }

                // Miracast/WFD-flavored description endpoint.
                get("/wfd/dd.xml") {
                    val requesterHost = call.request.local.remoteHost
                    call.respondText(
                        contentType = ContentType.Text.Xml,
                        text = getWfdDeviceDescriptionXml()
                    )
                    if (requesterHost.isNotBlank()) {
                        scope.launch {
                            onWfdDescriptionRequest?.invoke(requesterHost)
                        }
                    }
                    println("DIAL_HTTP: 📄 Served WFD description XML to $requesterHost")
                }

                // Service Control Protocol Description (SCPD) for AVTransport
                get("/avt/scpd.xml") {
                    call.respondText(
                        contentType = ContentType.Text.Xml,
                        text = getAVTransportScpdXml()
                    )
                    println("DIAL_HTTP: 📄 Served AVTransport SCPD to ${call.request.local.remoteHost}")
                }

                // Service Control Protocol Description (SCPD) for RenderingControl
                get("/rc/scpd.xml") {
                    call.respondText(
                        contentType = ContentType.Text.Xml,
                        text = getRenderingControlScpdXml()
                    )
                    println("DIAL_HTTP: 📄 Served RenderingControl SCPD to ${call.request.local.remoteHost}")
                }

                // Service Control Protocol Description (SCPD) for ConnectionManager
                get("/cm/scpd.xml") {
                    call.respondText(
                        contentType = ContentType.Text.Xml,
                        text = getConnectionManagerScpdXml()
                    )
                    println("DIAL_HTTP: 📄 Served ConnectionManager SCPD to ${call.request.local.remoteHost}")
                }

                // DIAL REST API - Application list
                get("/apps") {
                    call.respondText(
                        contentType = ContentType.Application.Xml,
                        text = getApplicationListXml()
                    )
                    println("DIAL_HTTP: 📱 Served application list to ${call.request.local.remoteHost}")
                }

                // Minimal DIAL SCPD endpoint for strict control points.
                get("/apps/scpd.xml") {
                    call.respondText(
                        contentType = ContentType.Text.Xml,
                        text = getDialScpdXml()
                    )
                    println("DIAL_HTTP: 📄 Served DIAL SCPD to ${call.request.local.remoteHost}")
                }

                // Event endpoint placeholder.
                get("/apps/event") {
                    call.respond(HttpStatusCode.OK)
                }

                // DIAL REST API - Screen mirroring application status
                get("/apps/ScreenMirroring") {
                    call.respondText(
                        contentType = ContentType.Application.Xml,
                        text = getScreenMirroringStatusXml()
                    )
                    println("DIAL_HTTP: 🖥️  Served ScreenMirroring status to ${call.request.local.remoteHost}")
                }

                // Generic DIAL app status endpoint for interoperability.
                get("/apps/{appName}") {
                    val appName = call.parameters["appName"].orEmpty()
                    call.respondText(
                        contentType = ContentType.Application.Xml,
                        text = getGenericAppStatusXml(appName),
                    )
                    println("DIAL_HTTP: 📱 Served app status '$appName' to ${call.request.local.remoteHost}")
                }

                // DIAL REST API - Launch screen mirroring
                post("/apps/ScreenMirroring") {
                    println("DIAL_HTTP: 🚀 Received ScreenMirroring launch request from ${call.request.local.remoteHost}")

                    // Notify that screen mirroring was requested
                    scope.launch {
                        onScreenMirrorRequest()
                    }

                    call.response.headers.append("Location", "/apps/ScreenMirroring/run")
                    call.respondText(
                        status = HttpStatusCode.Created,
                        text = ""
                    )
                }

                // Generic DIAL app launch endpoint for interoperability.
                post("/apps/{appName}") {
                    val appName = call.parameters["appName"].orEmpty()
                    println(
                        "DIAL_HTTP: 🚀 Received app launch request '$appName' " +
                            "from ${call.request.local.remoteHost}",
                    )

                    if (isMirroringApp(appName)) {
                        scope.launch { onScreenMirrorRequest() }
                    }

                    call.response.headers.append("Location", "/apps/$appName/run")
                    call.respondText(
                        status = HttpStatusCode.Created,
                        text = "",
                    )
                }

                // DIAL REST API - Stop screen mirroring
                delete("/apps/ScreenMirroring/run") {
                    println("DIAL_HTTP: 🛑 Received ScreenMirroring stop request from ${call.request.local.remoteHost}")
                    call.respond(HttpStatusCode.OK)
                }

                // Generic DIAL app stop endpoint for interoperability.
                delete("/apps/{appName}/run") {
                    val appName = call.parameters["appName"].orEmpty()
                    println(
                        "DIAL_HTTP: 🛑 Received app stop request '$appName' " +
                            "from ${call.request.local.remoteHost}",
                    )
                    call.respond(HttpStatusCode.OK)
                }

                // Root endpoint
                get("/") {
                    call.respondText("DIAL Server - TeachmintShareX")
                }
            }
        }

        server?.start(wait = false)
        println("DIAL_HTTP: ✅ DIAL HTTP server started on port $port")
        return port
    }

    /**
     * Stops the DIAL HTTP server
     */
    fun stop() {
        server?.stop(1000, 2000)
        server = null
        println("DIAL_HTTP: 🔴 DIAL HTTP server stopped")
    }

    /**
     * Generates a DIAL-compatible device description XML.
     */
    private fun getDialDeviceDescriptionXml(): String {
        return """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <URLBase>http://${resolvedHost()}:$httpPort/</URLBase>
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:tvdevice:1</deviceType>
    <friendlyName>$deviceName</friendlyName>
    <manufacturer>$manufacturer</manufacturer>
    <modelName>$modelName</modelName>
    <UDN>uuid:$deviceUuid</UDN>
    <serviceList>
      <service>
        <serviceType>urn:dial-multiscreen-org:service:dial:1</serviceType>
        <serviceId>urn:dial-multiscreen-org:serviceId:dial</serviceId>
        <controlURL>/apps</controlURL>
        <eventSubURL>/apps/event</eventSubURL>
        <SCPDURL>/apps/scpd.xml</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>"""
    }

    /**
     * Generates Miracast/Wireless Display description XML for WFD clients.
     */
    private fun getWfdDeviceDescriptionXml(): String {
        return """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <URLBase>http://${resolvedHost()}:$httpPort/</URLBase>
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:Wireless-Display:1</deviceType>
    <friendlyName>$deviceName</friendlyName>
    <manufacturer>$manufacturer</manufacturer>
    <modelName>$modelName</modelName>
    <UDN>uuid:$deviceUuid</UDN>
    <iconList>
      <icon>
        <mimetype>image/png</mimetype>
        <width>128</width>
        <height>128</height>
        <depth>32</depth>
        <url>/icon.png</url>
      </icon>
    </iconList>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <controlURL>/avt/control</controlURL>
        <eventSubURL>/avt/event</eventSubURL>
        <SCPDURL>/avt/scpd.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <controlURL>/rc/control</controlURL>
        <eventSubURL>/rc/event</eventSubURL>
        <SCPDURL>/rc/scpd.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <controlURL>/cm/control</controlURL>
        <eventSubURL>/cm/event</eventSubURL>
        <SCPDURL>/cm/scpd.xml</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>"""
    }

    private fun resolvedHost(): String = localIp ?: "127.0.0.1"

    private fun applicationUrl(): String = "http://${resolvedHost()}:$httpPort/apps/"

    /**
     * Generates application list XML (DIAL REST API)
     */
    private fun getApplicationListXml(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<applications xmlns="urn:dial-multiscreen-org:schemas:dial">
  <application name="ScreenMirroring" href="/apps/ScreenMirroring"/>
  <application name="Miracast" href="/apps/Miracast"/>
  <application name="YouTube" href="/apps/YouTube"/>
</applications>"""
    }

    /**
     * Generates screen mirroring application status XML
     */
    private fun getScreenMirroringStatusXml(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<service xmlns="urn:dial-multiscreen-org:schemas:dial">
  <name>ScreenMirroring</name>
  <options allowStop="true"/>
  <state>stopped</state>
  <link rel="run" href="/apps/ScreenMirroring/run"/>
</service>"""
    }

    private fun getGenericAppStatusXml(appName: String): String {
        val safeAppName = appName.ifBlank { "UnknownApp" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<service xmlns="urn:dial-multiscreen-org:schemas:dial">
  <name>$safeAppName</name>
  <options allowStop="true"/>
  <state>stopped</state>
  <link rel="run" href="/apps/$safeAppName/run"/>
</service>"""
    }

    private fun isMirroringApp(appName: String): Boolean {
        val normalized = appName.trim().lowercase()
        if (normalized.isEmpty()) return false
        return normalized.contains("mirror") ||
            normalized.contains("miracast") ||
            normalized.contains("projection")
    }

    private fun getDialScpdXml(): String {
        return """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <actionList/>
  <serviceStateTable/>
</scpd>"""
    }

    /**
     * Generates AVTransport service description (required for Miracast)
     */
    private fun getAVTransportScpdXml(): String {
        return """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <actionList>
    <action>
      <name>SetAVTransportURI</name>
      <argumentList>
        <argument>
          <name>InstanceID</name>
          <direction>in</direction>
          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>
        </argument>
        <argument>
          <name>CurrentURI</name>
          <direction>in</direction>
          <relatedStateVariable>AVTransportURI</relatedStateVariable>
        </argument>
        <argument>
          <name>CurrentURIMetaData</name>
          <direction>in</direction>
          <relatedStateVariable>AVTransportURIMetaData</relatedStateVariable>
        </argument>
      </argumentList>
    </action>
    <action>
      <name>Play</name>
      <argumentList>
        <argument>
          <name>InstanceID</name>
          <direction>in</direction>
          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>
        </argument>
        <argument>
          <name>Speed</name>
          <direction>in</direction>
          <relatedStateVariable>TransportPlaySpeed</relatedStateVariable>
        </argument>
      </argumentList>
    </action>
    <action>
      <name>Stop</name>
      <argumentList>
        <argument>
          <name>InstanceID</name>
          <direction>in</direction>
          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>
        </argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no">
      <name>A_ARG_TYPE_InstanceID</name>
      <dataType>ui4</dataType>
    </stateVariable>
    <stateVariable sendEvents="no">
      <name>AVTransportURI</name>
      <dataType>string</dataType>
    </stateVariable>
    <stateVariable sendEvents="no">
      <name>AVTransportURIMetaData</name>
      <dataType>string</dataType>
    </stateVariable>
    <stateVariable sendEvents="no">
      <name>TransportPlaySpeed</name>
      <dataType>string</dataType>
      <defaultValue>1</defaultValue>
    </stateVariable>
  </serviceStateTable>
</scpd>"""
    }

    /**
     * Generates RenderingControl service description (required for Miracast)
     */
    private fun getRenderingControlScpdXml(): String {
        return """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <actionList>
    <action>
      <name>GetVolume</name>
      <argumentList>
        <argument>
          <name>InstanceID</name>
          <direction>in</direction>
          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>
        </argument>
        <argument>
          <name>Channel</name>
          <direction>in</direction>
          <relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable>
        </argument>
        <argument>
          <name>CurrentVolume</name>
          <direction>out</direction>
          <relatedStateVariable>Volume</relatedStateVariable>
        </argument>
      </argumentList>
    </action>
    <action>
      <name>SetVolume</name>
      <argumentList>
        <argument>
          <name>InstanceID</name>
          <direction>in</direction>
          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>
        </argument>
        <argument>
          <name>Channel</name>
          <direction>in</direction>
          <relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable>
        </argument>
        <argument>
          <name>DesiredVolume</name>
          <direction>in</direction>
          <relatedStateVariable>Volume</relatedStateVariable>
        </argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no">
      <name>A_ARG_TYPE_InstanceID</name>
      <dataType>ui4</dataType>
    </stateVariable>
    <stateVariable sendEvents="no">
      <name>A_ARG_TYPE_Channel</name>
      <dataType>string</dataType>
      <allowedValueList>
        <allowedValue>Master</allowedValue>
      </allowedValueList>
    </stateVariable>
    <stateVariable sendEvents="yes">
      <name>Volume</name>
      <dataType>ui2</dataType>
      <allowedValueRange>
        <minimum>0</minimum>
        <maximum>100</maximum>
        <step>1</step>
      </allowedValueRange>
    </stateVariable>
  </serviceStateTable>
</scpd>"""
    }

    /**
     * Generates ConnectionManager service description (required for Miracast)
     */
    private fun getConnectionManagerScpdXml(): String {
        return """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <actionList>
    <action>
      <name>GetProtocolInfo</name>
      <argumentList>
        <argument>
          <name>Source</name>
          <direction>out</direction>
          <relatedStateVariable>SourceProtocolInfo</relatedStateVariable>
        </argument>
        <argument>
          <name>Sink</name>
          <direction>out</direction>
          <relatedStateVariable>SinkProtocolInfo</relatedStateVariable>
        </argument>
      </argumentList>
    </action>
    <action>
      <name>GetCurrentConnectionIDs</name>
      <argumentList>
        <argument>
          <name>ConnectionIDs</name>
          <direction>out</direction>
          <relatedStateVariable>CurrentConnectionIDs</relatedStateVariable>
        </argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="yes">
      <name>SourceProtocolInfo</name>
      <dataType>string</dataType>
    </stateVariable>
    <stateVariable sendEvents="yes">
      <name>SinkProtocolInfo</name>
      <dataType>string</dataType>
    </stateVariable>
    <stateVariable sendEvents="yes">
      <name>CurrentConnectionIDs</name>
      <dataType>string</dataType>
    </stateVariable>
  </serviceStateTable>
</scpd>"""
    }
}
