package fr.onrails.trainwifi

import android.net.Network

/**
 * The Wi-Fi the service talks to the portal on. [TrainMap] needs it too, because the portal serves
 * the map tiles and MapLibre opens its own sockets.
 */
object BoundNetwork {
    @Volatile
    var current: Network? = null

    @Volatile
    var portal: Portal? = null
}
