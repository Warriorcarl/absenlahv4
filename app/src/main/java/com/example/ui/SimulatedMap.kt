package com.example.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * High-performance, fully interactive Map component powered by Leaflet and 
 * genuine Google Maps tile servers (Streets, Satellite, and Hybrid layers).
 * Uses real-time Javascript marker injection to prevent WebView flickering or reload loops.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SimulatedMap(
    modifier: Modifier = Modifier,
    warehouseCenter: Pair<Double, Double> = Pair(-6.2088, 106.8456),
    warehouseRadiusMeters: Double = 100.0,
    userLocation: Pair<Double, Double>? = null,
    couriers: List<Pair<String, Pair<Double, Double>>> = emptyList(), // Name to lat-lon
    deliveryDestination: Pair<Double, Double>? = null,
    onMapClick: ((Double, Double) -> Unit)? = null
) {
    val warehouseLat = warehouseCenter.first
    val warehouseLon = warehouseCenter.second

    var isPageLoaded by remember { mutableStateOf(false) }

    val htmlContent = remember(warehouseLat, warehouseLon, warehouseRadiusMeters) {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                html, body, #map {
                    height: 100%;
                    margin: 0;
                    padding: 0;
                    background: #111827;
                }
                .leaflet-control-attribution {
                    display: none !important;
                }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                // Custom Icons using beautiful inline SVG
                var warehouseIcon = L.divIcon({
                    html: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="#f97316" width="30" height="30"><path d="M12 2L2 12h3v8h6v-6h2v6h6v-8h3L12 2zm0 10a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z"/></svg>',
                    className: 'custom-div-icon',
                    iconSize: [30, 30],
                    iconAnchor: [15, 30],
                    popupAnchor: [0, -30]
                });

                var userIcon = L.divIcon({
                    html: '<div style="position: relative;"><div style="position: absolute; width: 14px; height: 14px; background: rgba(16, 185, 129, 0.4); border-radius: 50%; top: -7px; left: -7px; animation: pulse 1.5s infinite;"></div><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="#10b981" width="28" height="28" style="position: relative; z-index: 10;"><path d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z"/></svg></div><style>@keyframes pulse { 0% { transform: scale(0.8); opacity: 0.5; } 100% { transform: scale(2.2); opacity: 0; } }</style>',
                    className: 'custom-div-icon',
                    iconSize: [28, 28],
                    iconAnchor: [14, 28],
                    popupAnchor: [0, -28]
                });

                var destIcon = L.divIcon({
                    html: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="#ef4444" width="28" height="28"><path d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z"/></svg>',
                    className: 'custom-div-icon',
                    iconSize: [28, 28],
                    iconAnchor: [14, 28],
                    popupAnchor: [0, -28]
                });

                var courierIcon = L.divIcon({
                    html: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="#a855f7" width="28" height="28"><path d="M20 8h-3V4H3c-1.1 0-2 .9-2 2v11h2c0 1.66 1.34 3 3 3s3-1.34 3-3h6c0 1.66 1.34 3 3 3s3-1.34 3-3h2v-5l-3-4zM6 18.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm12.5-5.5h-2.5V10h2.5v3zm-1 5.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z"/></svg>',
                    className: 'custom-div-icon',
                    iconSize: [28, 28],
                    iconAnchor: [14, 28],
                    popupAnchor: [0, -28]
                });

                // Set up Map centered at Warehouse
                var map = L.map('map', {
                    zoomControl: true,
                    attributionControl: false
                }).setView([$warehouseLat, $warehouseLon], 15);

                // Authentic Google Maps Tile Layers
                var googleStreets = L.tileLayer('https://{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}', {
                    maxZoom: 20,
                    subdomains: ['mt0','mt1','mt2','mt3']
                }).addTo(map);

                var googleSat = L.tileLayer('https://{s}.google.com/vt/lyrs=s&x={x}&y={y}&z={z}', {
                    maxZoom: 20,
                    subdomains: ['mt0','mt1','mt2','mt3']
                });

                var googleHybrid = L.tileLayer('https://{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
                    maxZoom: 20,
                    subdomains: ['mt0','mt1','mt2','mt3']
                });

                var baseMaps = {
                    "Google Maps Standard": googleStreets,
                    "Google Maps Satelit": googleSat,
                    "Google Maps Hibrida": googleHybrid
                };
                
                L.control.layers(baseMaps, null, { position: 'topright' }).addTo(map);

                // Add Warehouse Geofence Circle & Marker
                L.circle([$warehouseLat, $warehouseLon], {
                    color: '#3b82f6',
                    fillColor: '#3b82f6',
                    fillOpacity: 0.15,
                    radius: $warehouseRadiusMeters,
                    dashArray: '5, 5'
                }).addTo(map);

                L.marker([$warehouseLat, $warehouseLon], {icon: warehouseIcon}).addTo(map).bindPopup('Gudang Geofence');

                // Dynamic Markers state
                var userMarker = null;
                var destMarker = null;
                var courierMarkers = {};
                var routeLine = null;

                function updatePositions(userLat, userLon, couriersList, destLat, destLon) {
                    // 1. User Position
                    if (userLat !== 0.0 && userLon !== 0.0) {
                        if (!userMarker) {
                            userMarker = L.marker([userLat, userLon], {icon: userIcon}).addTo(map).bindPopup('Posisi Saya');
                        } else {
                            userMarker.setLatLng([userLat, userLon]);
                        }
                    } else {
                        if (userMarker) {
                            map.removeLayer(userMarker);
                            userMarker = null;
                        }
                    }

                    // 2. Destination Position
                    if (destLat !== 0.0 && destLon !== 0.0) {
                        if (!destMarker) {
                            destMarker = L.marker([destLat, destLon], {icon: destIcon}).addTo(map).bindPopup('Tujuan Paket');
                        } else {
                            destMarker.setLatLng([destLat, destLon]);
                        }

                        // Draw Route Line from Warehouse
                        if (!routeLine) {
                            routeLine = L.polyline([[$warehouseLat, $warehouseLon], [destLat, destLon]], {
                                color: '#ef4444',
                                dashArray: '5, 5',
                                weight: 3
                            }).addTo(map);
                        } else {
                            routeLine.setLatLngs([[$warehouseLat, $warehouseLon], [destLat, destLon]]);
                        }
                    } else {
                        if (destMarker) {
                            map.removeLayer(destMarker);
                            destMarker = null;
                        }
                        if (routeLine) {
                            map.removeLayer(routeLine);
                            routeLine = null;
                        }
                    }

                    // 3. Couriers Location
                    var activeNames = {};
                    for (var i = 0; i < couriersList.length; i++) {
                        var c = couriersList[i];
                        activeNames[c.name] = true;

                        if (courierMarkers[c.name]) {
                            courierMarkers[c.name].setLatLng([c.lat, c.lon]);
                        } else {
                            courierMarkers[c.name] = L.marker([c.lat, c.lon], {icon: courierIcon}).addTo(map).bindPopup(c.name);
                        }
                    }

                    // Remove inactive couriers
                    for (var name in courierMarkers) {
                        if (!activeNames[name]) {
                            map.removeLayer(courierMarkers[name]);
                            delete courierMarkers[name];
                        }
                    }

                    // Auto fit bounds to active markers
                    var markersGroup = [];
                    if (userLat !== 0.0 && userLon !== 0.0) markersGroup.push([userLat, userLon]);
                    if (destLat !== 0.0 && destLon !== 0.0) markersGroup.push([destLat, destLon]);
                    for (var i = 0; i < couriersList.length; i++) {
                        markersGroup.push([couriersList[i].lat, couriersList[i].lon]);
                    }
                    markersGroup.push([$warehouseLat, $warehouseLon]); // warehouse is always there
                    
                    if (markersGroup.length > 1) {
                        map.fitBounds(markersGroup, { padding: [30, 30] });
                    } else {
                        map.setView([$warehouseLat, $warehouseLon], 15);
                    }
                }

                // Click Callback
                map.on('click', function(e) {
                    if (window.AndroidInterface) {
                        window.AndroidInterface.onMapClick(e.latlng.lat, e.latlng.lng);
                    }
                });
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF111827))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isPageLoaded = true
                            // Trigger initial coordinate injection
                            val userLat = userLocation?.first ?: 0.0
                            val userLon = userLocation?.second ?: 0.0
                            val destLat = deliveryDestination?.first ?: 0.0
                            val destLon = deliveryDestination?.second ?: 0.0
                            val couriersJsArray = couriers.map { courier ->
                                val escapedName = courier.first.replace("'", "\\'")
                                "{ name: '$escapedName', lat: ${courier.second.first}, lon: ${courier.second.second} }"
                            }.joinToString(", ", "[", "]")
                            val js = "updatePositions($userLat, $userLon, $couriersJsArray, $destLat, $destLon);"
                            view?.evaluateJavascript(js, null)
                        }
                    }

                    if (onMapClick != null) {
                        addJavascriptInterface(object : Any() {
                            @android.webkit.JavascriptInterface
                            fun onMapClick(lat: Double, lon: Double) {
                                post {
                                    onMapClick(lat, lon)
                                }
                            }
                        }, "AndroidInterface")
                    }

                    loadDataWithBaseURL("https://localhost", htmlContent, "text/html", "UTF-8", null)
                }
            },
            update = { webView ->
                if (isPageLoaded) {
                    val userLat = userLocation?.first ?: 0.0
                    val userLon = userLocation?.second ?: 0.0
                    val destLat = deliveryDestination?.first ?: 0.0
                    val destLon = deliveryDestination?.second ?: 0.0

                    val couriersJsArray = couriers.map { courier ->
                        val escapedName = courier.first.replace("'", "\\'")
                        "{ name: '$escapedName', lat: ${courier.second.first}, lon: ${courier.second.second} }"
                    }.joinToString(", ", "[", "]")

                    val js = "updatePositions($userLat, $userLon, $couriersJsArray, $destLat, $destLon);"
                    webView.evaluateJavascript(js, null)
                }
            }
        )
    }
}
