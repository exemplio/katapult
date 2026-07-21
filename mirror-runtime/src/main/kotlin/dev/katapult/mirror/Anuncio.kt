package dev.katapult.mirror

import io.nayuki.qrcodegen.QrCode
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLEncoder
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Descubrimiento de dev servers, como en Expo Go: cada servidor de Katapult
 * (espejo o Go) se anuncia en la LAN por mDNS/Bonjour y katapult-go los lista
 * en su pantalla de conexión — sin teclear IPs. El QR es el atajo complementario:
 * codifica un deep link katapult:// que la cámara del sistema abre en la app.
 */

/** El tipo de servicio que busca katapult-go (NSBonjourServices en su Info.plist). */
const val TIPO_SERVICIO_MDNS = "_katapult._tcp.local."

// Los bridges de Docker (172.x) y demás interfaces virtuales también tienen IP
// site-local: si no se filtran por nombre, el anuncio y el QR salen con una IP
// que el iPhone no puede alcanzar.
private val INTERFACES_VIRTUALES = Regex("^(docker|br-|veth|virbr|tun|tap|wg|tailscale|zt)")

/** La IPv4 de la LAN real, o null si no hay red (p. ej. sin WiFi ni cable). */
fun ipLan(): InetAddress? =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && !INTERFACES_VIRTUALES.containsMatchIn(it.name) }
        // Físicas primero: wl* (WiFi), eth*/en* (cable).
        .sortedBy { !(it.name.startsWith("wl") || it.name.startsWith("eth") || it.name.startsWith("en")) }
        .flatMap { it.inetAddresses.asSequence() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }

/**
 * Registra el servicio en mDNS. Devuelve el handle para cerrarlo, o null si no
 * se pudo (sin red, multicast bloqueado…): el anuncio es cortesía, nunca motivo
 * para tirar el servidor.
 */
fun anunciarServicio(modo: String, puerto: Int, extras: Map<String, String> = emptyMap()): AutoCloseable? {
    val ip = ipLan() ?: return null
    return try {
        // Ligado a la IP de la LAN explícitamente: JmDNS sin argumentos elige
        // interfaz por su cuenta y puede anunciar por una que el iPhone no ve.
        val jmdns = JmDNS.create(ip)
        val equipo = try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            ip.hostAddress
        }
        val nombre = "Katapult $modo en $equipo"
        jmdns.registerService(
            ServiceInfo.create(TIPO_SERVICIO_MDNS, nombre, puerto, 0, 0, mapOf("modo" to modo) + extras),
        )
        AutoCloseable { jmdns.close() }
    } catch (e: Exception) {
        System.err.println("⚠️  Sin anuncio mDNS (${e.message}); usa la IP a mano o el QR.")
        null
    }
}

/**
 * Imprime el QR del deep link `katapult://<modo>?url=<url>` para escanear con
 * la cámara del iPhone. Colores ANSI explícitos (negro/blanco) para que el QR
 * escanee igual en terminales claras y oscuras.
 */
fun imprimirQrAcceso(modo: String, url: String) {
    val enlace = "katapult://$modo?url=" + URLEncoder.encode(url, "UTF-8")
    val qr = QrCode.encodeText(enlace, QrCode.Ecc.LOW)
    val margen = 2
    val sb = StringBuilder()
    // Dos filas de módulos por línea de texto: '▀' pinta la de arriba con el
    // color de tinta y la de abajo con el de fondo.
    var y = -margen
    while (y < qr.size + margen) {
        for (x in -margen until qr.size + margen) {
            val tinta = if (qr.getModule(x, y)) 30 else 37       // negro / blanco
            val fondo = if (qr.getModule(x, y + 1)) 40 else 47
            sb.append("\u001B[$tinta;${fondo}m▀")
        }
        sb.append("\u001B[0m\n")
        y += 2
    }
    print(sb)
    println("   $enlace")
}
