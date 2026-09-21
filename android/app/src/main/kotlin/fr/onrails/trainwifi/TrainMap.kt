package fr.onrails.trainwifi

import android.content.Context
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.infowindow.MarkerInfoWindow
import java.io.File

/**
 * Live map of the train: osmdroid with CARTO's free raster basemap (dark or light following the
 * system theme), the train position from /router/api/train/gps, and the route through the stops
 * when the portal gives their coordinates. Follows the train until the user pans; a Recenter chip
 * brings it back.
 */
class TrainMap(private val context: Context, private val mapView: MapView, private val recenterButton: View) {

    companion object {
        const val DEFAULT_ZOOM = 9.0
        const val MIN_ZOOM = 5.0
        const val MAX_ZOOM = 14.0 // keeps tile downloads modest on the train's data quota
        private const val ATTRIBUTION = "© OpenStreetMap contributors © CARTO"

        /** Must run before the first MapView is inflated. */
        fun configure(context: Context) {
            val config = Configuration.getInstance()
            config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            config.userAgentValue = context.packageName
            config.osmdroidBasePath = File(context.cacheDir, "osmdroid")
            config.osmdroidTileCache = File(config.osmdroidBasePath, "tiles")
        }
    }

    private var follow = true
    private var centeredOnce = false
    private var trainMarker: Marker? = null
    private var routeLine: Polyline? = null
    private val stopMarkers = mutableListOf<Marker>()
    private var routeKey: String? = null

    init {
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val style = if (night) "dark_all" else "light_all"
        val source = XYTileSource(
            "carto-$style", 3, 18, 512, "@2x.png",
            arrayOf("a", "b", "c", "d").map { "https://$it.basemaps.cartocdn.com/$style/" }.toTypedArray(),
            ATTRIBUTION,
        )
        mapView.setTileSource(source)
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.setMinZoomLevel(MIN_ZOOM)
        mapView.setMaxZoomLevel(MAX_ZOOM)
        mapView.controller.setZoom(DEFAULT_ZOOM)
        mapView.overlays.add(CopyrightOverlay(context))

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
            trainMarker?.position?.let { p -> mapView.controller.animateTo(p) }
        }
    }

    /** Updates the map; returns false when there is no position to show (caller hides the card). */
    fun render(state: TrainState): Boolean {
        val gps = state.gps
        val latitude = gps?.latitude
        val longitude = gps?.longitude
        if (gps == null || !gps.fix || latitude == null || longitude == null) return false

        val position = GeoPoint(latitude, longitude)
        renderRoute(state.path, state.trip)

        val marker = trainMarker ?: Marker(mapView).also {
            it.icon = context.getDrawable(R.drawable.ic_map_train)
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            it.setInfoWindow(null as MarkerInfoWindow?)
            trainMarker = it
        }
        marker.position = position
        // Keep the train above the route and the stops.
        mapView.overlays.remove(marker)
        mapView.overlays.add(marker)

        if (follow) {
            if (centeredOnce) mapView.controller.animateTo(position) else mapView.controller.setCenter(position)
            centeredOnce = true
        }
        mapView.invalidate()
        return true
    }

    private fun renderRoute(path: List<LatLon>, trip: Trip?) {
        val stops = trip?.stops.orEmpty()
            .filter { it.hasCoordinates }
            .map { GeoPoint(it.latitude!!, it.longitude!!) }
        // The rails when the portal gives them, else the stations joined by straight lines.
        val route = if (path.size >= 2) path.map { GeoPoint(it.latitude, it.longitude) } else stops
        val key = "${route.size}|" + stops.joinToString(";") { "${it.latitude},${it.longitude}" }
        if (key == routeKey) return
        routeKey = key

        routeLine?.let { mapView.overlays.remove(it) }
        stopMarkers.forEach { mapView.overlays.remove(it) }
        stopMarkers.clear()
        routeLine = null
        if (route.size < 2) return

        val line = Polyline(mapView).apply {
            setPoints(route)
            outlinePaint.color = context.getColor(R.color.brand_red)
            outlinePaint.strokeWidth = 8f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setInfoWindow(null as InfoWindow?)
        }
        mapView.overlays.add(line)
        routeLine = line

        for (point in stops) {
            val stopMarker = Marker(mapView).apply {
                icon = context.getDrawable(R.drawable.ic_map_stop)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null as MarkerInfoWindow?)
                position = point
            }
            mapView.overlays.add(stopMarker)
            stopMarkers += stopMarker
        }
    }

    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onDestroy() = mapView.onDetach()
}
