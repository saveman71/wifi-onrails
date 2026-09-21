package fr.onrails.trainwifi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.log.Logger
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.concurrent.thread

/**
 * Live map of the train on the portal's own map: MapLibre reads the PMTiles archives at
 * /maps/europe.pmtiles and /maps/osm_railways.pmtiles through the style at /karto/style-dark.json.
 * That style is the one the portal uses itself, down to the LGV lines and the TGV station markers.
 *
 * Two things make it work on a train:
 *
 * - The style calls its own host `http://localhost:8000`, which is the portal talking to itself.
 *   [rewrite] puts the portal base URL there instead.
 * - MapLibre opens its own sockets, and they have to go to the Wi-Fi the portal is on rather than
 *   the default route. [bindHttp] hands it an OkHttp client tied to that network.
 *   `MapLibre.setConnected(true)` goes with it: MapLibre reads the default network to tell whether
 *   it is online, and that is mobile data until the portal is activated.
 */
class TrainMap(private val context: Context, private val mapView: MapView, private val recenterButton: View) {

    companion object {
        const val DEFAULT_ZOOM = 8.0
        const val MIN_ZOOM = 4.0
        const val MAX_ZOOM = 14.0
        private const val STYLE_HOST = "http://localhost:8000"
        private const val ROUTE_SOURCE = "trainwifi-route"
        private const val STOPS_SOURCE = "trainwifi-stops"
        private const val TRAIN_SOURCE = "trainwifi-train"
        private const val STOP_ICON = "trainwifi-stop-icon"
        private const val TRAIN_ICON = "trainwifi-train-icon"

        /** Must run before the first MapView is inflated. */
        fun configure(context: Context) {
            // MapLibre logs one line per tile at INFO, which pushes everything else out of logcat.
            Logger.setVerbosity(Logger.WARN)
            MapLibre.getInstance(context)
        }

        private fun rewrite(styleJson: String, portal: Portal): String =
            styleJson.replace(STYLE_HOST, portal.baseUrl)
    }

    private val main = Handler(Looper.getMainLooper())
    private var follow = true
    private var centeredOnce = false
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleLoading = false
    private var styleKey: String? = null
    private var routeKey: String? = null
    private var boundNetworkId: String? = null
    private var trainPosition: LatLng? = null

    init {
        mapView.onCreate(null)
        mapView.addOnDidFailLoadingMapListener { reason -> AppState.log("Map failed to load: $reason") }
        mapView.getMapAsync { ready ->
            map = ready
            ready.uiSettings.isLogoEnabled = false
            ready.uiSettings.isRotateGesturesEnabled = false
            ready.uiSettings.isTiltGesturesEnabled = false
            ready.setMinZoomPreference(MIN_ZOOM)
            ready.setMaxZoomPreference(MAX_ZOOM)
        }

        // Inside a ScrollView: keep the drag for the map, and stop following once the user pans.
        mapView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_MOVE -> if (follow) {
                    follow = false
                    recenterButton.visibility = View.VISIBLE
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        recenterButton.setOnClickListener {
            follow = true
            it.visibility = View.GONE
            trainPosition?.let { position -> centerOn(position) }
        }
    }

    /** Updates the map; returns false when there is no position to show (caller hides the card). */
    fun render(state: TrainState): Boolean {
        val gps = state.gps
        val latitude = gps?.latitude
        val longitude = gps?.longitude
        if (gps == null || !gps.fix || latitude == null || longitude == null) return false

        bindHttp()
        loadStyle()
        trainPosition = LatLng(latitude, longitude)

        val loaded = style ?: return true
        renderRoute(loaded, state.path, state.trip)
        loaded.getSourceAs<GeoJsonSource>(TRAIN_SOURCE)?.setGeoJson(Point.fromLngLat(longitude, latitude))
        if (follow) centerOn(LatLng(latitude, longitude))
        return true
    }

    private fun bindHttp() {
        val network = BoundNetwork.current ?: return
        val id = network.toString()
        if (id == boundNetworkId) return
        boundNetworkId = id
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                // The socket factory alone leaves name resolution on the default network, where
                // wifi.sncf does not resolve.
                .dns(object : Dns {
                    override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
                })
                .build(),
        )
        MapLibre.setConnected(true)
        AppState.log("Map traffic sent over $network")
    }

    private fun loadStyle() {
        val portal = BoundNetwork.portal ?: return
        val network = BoundNetwork.current ?: return
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val path = if (night) "/karto/style-dark.json" else "/karto/style-light.json"
        val key = portal.baseUrl + path
        if (key == styleKey || styleLoading) return
        styleLoading = true

        thread(name = "map-style") {
            val json = try {
                rewrite(PortalClient(network).get(portal.url(path)).body, portal)
            } catch (e: Exception) {
                AppState.log("No portal map style (${e.javaClass.simpleName}): ${e.message}")
                styleLoading = false
                return@thread
            }
            main.post {
                styleLoading = false
                // getMapAsync may not have run yet. Leave styleKey empty so the next render tries again.
                val target = map ?: return@post
                styleKey = key
                target.setStyle(Style.Builder().fromJson(json)) { ready ->
                    style = ready
                    routeKey = null
                    addLayers(ready)
                    AppState.log("Portal map style in use: $path")
                }
            }
        }
    }

    private fun addLayers(style: Style) {
        style.addImage(STOP_ICON, bitmap(R.drawable.ic_map_stop))
        style.addImage(TRAIN_ICON, bitmap(R.drawable.ic_map_train))
        style.addSource(GeoJsonSource(ROUTE_SOURCE))
        style.addSource(GeoJsonSource(STOPS_SOURCE))
        style.addSource(GeoJsonSource(TRAIN_SOURCE))
        style.addLayer(
            LineLayer("trainwifi-route-line", ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(context.getColor(R.color.brand_red)),
                PropertyFactory.lineWidth(3.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            SymbolLayer("trainwifi-stops-symbols", STOPS_SOURCE).withProperties(
                PropertyFactory.iconImage(STOP_ICON),
                PropertyFactory.iconAllowOverlap(true),
            ),
        )
        style.addLayer(
            SymbolLayer("trainwifi-train-symbol", TRAIN_SOURCE).withProperties(
                PropertyFactory.iconImage(TRAIN_ICON),
                PropertyFactory.iconAllowOverlap(true),
            ),
        )
    }

    private fun renderRoute(style: Style, path: List<LatLon>, trip: Trip?) {
        val stops = trip?.stops.orEmpty().filter { it.hasCoordinates }
        // The rails when the portal gives them, else the stations joined by straight lines.
        val line = if (path.size >= 2) path else stops.map { LatLon(it.latitude!!, it.longitude!!) }
        val key = "${line.size}|" + stops.joinToString(";") { "${it.latitude},${it.longitude}" }
        if (key == routeKey) return
        routeKey = key

        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(
            LineString.fromLngLats(line.map { Point.fromLngLat(it.longitude, it.latitude) }),
        )
        style.getSourceAs<GeoJsonSource>(STOPS_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                stops.map { Feature.fromGeometry(Point.fromLngLat(it.longitude!!, it.latitude!!)) },
            ),
        )
    }

    private fun centerOn(position: LatLng) {
        val camera = if (centeredOnce) {
            CameraUpdateFactory.newLatLng(position)
        } else {
            CameraUpdateFactory.newLatLngZoom(position, DEFAULT_ZOOM)
        }
        if (centeredOnce) map?.easeCamera(camera, 800) else map?.moveCamera(camera)
        centeredOnce = true
    }

    private fun bitmap(drawableId: Int): Bitmap {
        val drawable = requireNotNull(context.getDrawable(drawableId))
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 48
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(result))
        return result
    }

    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onDestroy() = mapView.onDestroy()
    fun onLowMemory() = mapView.onLowMemory()
}
