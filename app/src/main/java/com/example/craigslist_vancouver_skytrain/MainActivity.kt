package com.example.craigslist_vancouver_skytrain

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

enum class SortOption(val displayName: String) {
    Newest("Newest"),
    Price("Price"),
    TotalCommute("Commute"),
    SkytrainTime("Skytrain"),
    WalkTime("Walk Time")
}

data class Station(val name: String, val lat: Double, val lon: Double, val travelTimeMin: Int)
data class DebugEntry(val title: String, val reason: String)
data class CraigslistResult(
    val title: String,
    val price: String,
    val url: String,
    val stationName: String,
    val stationTravelTime: Int,
    val walkToStationTime: Int,
    val lat: Double,
    val lon: Double,
    val totalCommuteTime: Int,
    val postDateMillis: Long,
    val stationLat: Double,
    val stationLon: Double,
    val priceUsd: String,
    val destStationName: String,
    val destStationLat: Double,
    val destStationLon: Double,
    val officeWalkTime: Int,
    val commuteDestination: String,
    val transitMode: String = "Skytrain"
)

data class NearbyStation(val name: String, val walkTimeMin: Int, val line: String, val lat: Double, val lon: Double, val type: String = "Skytrain")

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private val MAPS_API_KEY = BuildConfig.MAPS_API_KEY 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("exchange_rate_prefs", Context.MODE_PRIVATE)

        setContent {
            var currentScreen by remember { mutableStateOf("commute_setup") }
            var destinationAddress by remember { mutableStateOf(prefs.getString("saved_address", "") ?: "") }
            var arrivalTime by remember { mutableStateOf("09:00") }
            var maxTrainTime by remember { mutableStateOf("45") }
            var suitableStations by remember { mutableStateOf(emptyList<Station>()) }
            var closestStations by remember { mutableStateOf(emptyList<NearbyStation>()) }
            var selectedDestStation by remember { mutableStateOf<NearbyStation?>(null) }
            var reachableRegions by remember { mutableStateOf(setOf("van", "bnc")) }
            var officeWalkTime by remember { mutableStateOf(13) }
            
            var selectedModes by remember { mutableStateOf(setOf("skytrain_walk")) }

            var searchResults by remember { mutableStateOf(emptyList<CraigslistResult>()) }
            var isSearching by remember { mutableStateOf(false) }
            var statusMessage by remember { mutableStateOf("Ready to search") }
            var showResults by remember { mutableStateOf(false) }
            var debugLogs by remember { mutableStateOf(emptyList<DebugEntry>()) }
            var sortOption by remember { mutableStateOf(SortOption.Newest) }

            val sortedResults by remember(searchResults, sortOption) {
                derivedStateOf {
                    when (sortOption) {
                        SortOption.Newest -> searchResults.sortedByDescending { it.postDateMillis }
                        SortOption.Price -> searchResults.sortedBy {
                            it.price.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
                        }
                        SortOption.TotalCommute -> searchResults.sortedBy { it.totalCommuteTime }
                        SortOption.SkytrainTime -> searchResults.sortedBy { it.stationTravelTime }
                        SortOption.WalkTime -> searchResults.sortedBy { it.walkToStationTime }
                    }
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (currentScreen) {
                        "commute_setup" -> CommuteSetupScreen(
                            initialAddress = destinationAddress,
                            closestStations = closestStations,
                            onNext = { address, time, maxTime, modes, strategy ->
                                prefs.edit().putString("saved_address", address).apply()
                                destinationAddress = address
                                arrivalTime = time
                                maxTrainTime = maxTime
                                selectedModes = modes.toSet()
                                isSearching = true
                                statusMessage = "Identifying reachable hubs..."
                                lifecycleScope.launch {
                                    val setupResult = findSuitableHubsAndRegions(address, time, maxTime.toInt(), modes, strategy)
                                    suitableStations = setupResult.suitableStations
                                    closestStations = setupResult.closestToDest
                                    reachableRegions = setupResult.regions
                                    isSearching = false
                                    
                                    if (closestStations.size >= 2 && Math.abs(closestStations[0].walkTimeMin - closestStations[1].walkTimeMin) <= 5) {
                                        currentScreen = "station_choice"
                                    } else if (suitableStations.isNotEmpty()) {
                                        if (closestStations.isNotEmpty()) {
                                            selectedDestStation = closestStations[0]
                                            officeWalkTime = closestStations[0].walkTimeMin
                                        }
                                        delay(5000)
                                        currentScreen = "search_filters"
                                    } else {
                                        statusMessage = "No stations found within criteria."
                                    }
                                }
                            }
                        )
                        "station_choice" -> StationChoiceScreen(
                            destinationAddress = destinationAddress,
                            stations = closestStations.take(2),
                            onStationSelected = { station ->
                                selectedDestStation = station
                                officeWalkTime = station.walkTimeMin
                                currentScreen = "search_filters"
                            }
                        )
                        "search_filters" -> {
                            Column(modifier = Modifier.padding(16.dp).statusBarsPadding()) {
                                if (!showResults && !isSearching) {
                                    Text(
                                        text = "Search Housing",
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 16.dp)
                                    )
                                    CraigslistFilterDashboard(
                                        regions = reachableRegions,
                                        onSearch = { url, distance, excludeRoomShare ->
                                            isSearching = true
                                            searchResults = emptyList()
                                            debugLogs = emptyList()

                                            val destStation = if (selectedDestStation != null) {
                                                Station(selectedDestStation!!.name, selectedDestStation!!.lat, selectedDestStation!!.lon, 0)
                                            } else null

                                            runSearch(
                                                url = url,
                                                maxDistance = distance,
                                                stations = suitableStations,
                                                destStation = destStation,
                                                officeWalkTime = officeWalkTime,
                                                commuteDestination = destinationAddress,
                                                excludeRoomShare = excludeRoomShare,
                                                transitMode = if (selectedModes.contains("bus")) "Bus" else "Skytrain",
                                                onStatus = { statusMessage = it },
                                                onDebug = { debugLogs = it },
                                                onResult = { results ->
                                                    searchResults = results
                                                    isSearching = false
                                                    showResults = true
                                                }
                                            )
                                        }
                                    )
                                } else {
                                    if (isSearching) {
                                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                            CircularProgressIndicator()
                                            Spacer(Modifier.height(16.dp))
                                            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    } else {
                                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("Results (${searchResults.size})", fontWeight = FontWeight.Bold)
                                            var sortMenuExpanded by remember { mutableStateOf(false) }
                                            Box {
                                                OutlinedButton(onClick = { sortMenuExpanded = true }) { Text(sortOption.displayName) }
                                                DropdownMenu(
                                                    expanded = sortMenuExpanded,
                                                    onDismissRequest = { sortMenuExpanded = false }
                                                ) {
                                                    SortOption.values().forEach { option ->
                                                        DropdownMenuItem(
                                                            text = { Text(option.displayName) },
                                                            onClick = {
                                                                sortOption = option
                                                                sortMenuExpanded = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            Button(onClick = { showResults = false }) { Text("Back") }
                                        }

                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            item { Text("Verified Commutes", fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp)) }
                                            items(sortedResults) { result -> ResultCard(result) }

                                            if (debugLogs.isNotEmpty()) {
                                                item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
                                                item { Text("Debug Log (Skipped Listings)", fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(8.dp)) }
                                                items(debugLogs) { debug ->
                                                    Text(text = "❌ ${debug.title}: ${debug.reason}", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun StationChoiceScreen(destinationAddress: String, stations: List<NearbyStation>, onStationSelected: (NearbyStation) -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp).statusBarsPadding(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Multiple Nearby Hubs Found", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("Two transit hubs are within 5 minutes walk. Preview the path to help you decide:", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(32.dp))

            stations.forEach { station ->
                Button(
                    onClick = {
                        val mapUri = Uri.parse("http://maps.google.com/maps?saddr=${station.lat},${station.lon}&daddr=${Uri.encode(destinationAddress)}&dirflg=w")
                        val intent = Intent(Intent.ACTION_VIEW, mapUri)
                        intent.setPackage("com.google.android.apps.maps")
                        startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Preview Walk to ${station.name} (${station.walkTimeMin}m)")
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))
            
            Text("Which hub do you want to use for your commute?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            stations.forEach { station ->
                OutlinedButton(
                    onClick = { onStationSelected(station) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Use ${station.name}")
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    fun CommuteSetupScreen(
        initialAddress: String,
        closestStations: List<NearbyStation>,
        onNext: (String, String, String, List<String>, String) -> Unit
    ) {
        var address by remember { mutableStateOf(initialAddress) }
        var arrivalTimeStr by remember { mutableStateOf("09:00") }
        var maxTrainTime by remember { mutableStateOf("45") }
        
        var modeBusSkytrain by remember { mutableStateOf(false) }
        var modeBus by remember { mutableStateOf(false) }
        var modeSkytrain by remember { mutableStateOf(true) }
        var transitStrategy by remember { mutableStateOf("shortest_time") }
        var strategyExpanded by remember { mutableStateOf(false) }

        val context = LocalContext.current

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).statusBarsPadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Commute Setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Destination Address") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    val calendar = Calendar.getInstance()
                    TimePickerDialog(context, { _, hour, minute ->
                        arrivalTimeStr = String.format(Locale.US, "%02d:%02d", hour, minute)
                    }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Arrival Time: $arrivalTimeStr")
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = maxTrainTime,
                onValueChange = { maxTrainTime = it },
                label = { Text("Max Transit Time (mins)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(24.dp))

            Text("Commute Modes:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.Start))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { modeBusSkytrain = !modeBusSkytrain }) {
                Checkbox(checked = modeBusSkytrain, onCheckedChange = { modeBusSkytrain = it })
                Text("Bus and Skytrain")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { modeBus = !modeBus }) {
                Checkbox(checked = modeBus, onCheckedChange = { modeBus = it })
                Text("Bus")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { modeSkytrain = !modeSkytrain }) {
                Checkbox(checked = modeSkytrain, onCheckedChange = { modeSkytrain = it })
                Text("Skytrain")
            }

            if (modeBusSkytrain || modeBus) {
                Spacer(Modifier.height(16.dp))
                Text("Transit Strategy:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.Start))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { strategyExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (transitStrategy == "shortest_time") "Shortest time" else "Fewest transfers")
                    }
                    DropdownMenu(expanded = strategyExpanded, onDismissRequest = { strategyExpanded = false }) {
                        DropdownMenuItem(text = { Text("Shortest time") }, onClick = { transitStrategy = "shortest_time"; strategyExpanded = false })
                        DropdownMenuItem(text = { Text("Fewest transfers") }, onClick = { transitStrategy = "fewest_transfers"; strategyExpanded = false })
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { 
                    val modes = mutableListOf<String>()
                    if (modeBusSkytrain) modes.add("bus_skytrain")
                    if (modeBus) modes.add("bus")
                    if (modeSkytrain) modes.add("skytrain_walk")
                    onNext(address, arrivalTimeStr, maxTrainTime, modes, transitStrategy) 
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Find Suitable Transit Hubs")
            }

            if (closestStations.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("Closest Transit Hubs to Destination:", fontWeight = FontWeight.Bold)
                closestStations.forEachIndexed { index, hub ->
                    val isClosest = index == 0
                    val significant = closestStations.size >= 2 && (closestStations[1].walkTimeMin - closestStations[0].walkTimeMin) > 5
                    val shouldHighlight = isClosest && (closestStations.size == 1 || significant)
                    
                    val typeLabel = if (hub.type == "Bus Stop") " (Bus Stop)" else " (${hub.line})"
                    Text(
                        text = "• ${hub.name}$typeLabel: ${hub.walkTimeMin} min walk" + if (shouldHighlight) " [RECOMMENDED]" else "",
                        color = if (shouldHighlight) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (shouldHighlight) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }

    private fun getStationLine(stationName: String): String {
        return when {
            stationName.contains("Production Way") ||
                    stationName.contains("Lougheed") ||
                    stationName.contains("Burquitlam") ||
                    stationName.contains("Sperling") ||
                    stationName.contains("Lake City") ||
                    stationName.contains("Holdom") ||
                    stationName.contains("Brentwood") ||
                    stationName.contains("Moody") ||
                    stationName.contains("Inlet") ||
                    stationName.contains("Coquitlam Central") ||
                    stationName.contains("Lafarge Lake") ||
                    stationName.contains("Gilmore") -> "Millennium Line"

            stationName.contains("Metrotown") ||
                    stationName.contains("Edmonds") ||
                    stationName.contains("Joyce") ||
                    stationName.contains("Patterson") ||
                    stationName.contains("Royal Oak") ||
                    stationName.contains("22nd Street") ||
                    stationName.contains("New Westminster") ||
                    stationName.contains("Columbia") ||
                    stationName.contains("Scott Road") ||
                    stationName.contains("Gateway") ||
                    stationName.contains("Surrey Central") ||
                    stationName.contains("King George") -> "Expo Line"
            
            stationName.contains("Marine Drive") ||
                    stationName.contains("Bridgeport") ||
                    stationName.contains("Aberdeen") ||
                    stationName.contains("Lansdowne") ||
                    stationName.contains("Richmond–Brighouse") ||
                    stationName.contains("Templeton") ||
                    stationName.contains("Sea Island Centre") ||
                    stationName.contains("YVR–Airport") ||
                    stationName.contains("Langara–49th") ||
                    stationName.contains("Oakridge–41st") ||
                    stationName.contains("King Edward") ||
                    stationName.contains("Broadway–City Hall") ||
                    stationName.contains("Olympic Village") -> "Canada Line"

            else -> "SkyTrain"
        }
    }

    private suspend fun getLatLngFromAddress(address: String): Pair<Double, Double>? {
        return withContext(Dispatchers.IO) {
            try {
                val encodedAddress = URLEncoder.encode(address, "UTF-8")
                val url = "https://maps.googleapis.com/maps/api/geocode/json?address=$encodedAddress&key=$MAPS_API_KEY"
                val conn = URL(url).openConnection() as HttpURLConnection
                val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(resp)
                if (json.getString("status") == "OK") {
                    val location = json.getJSONArray("results").getJSONObject(0)
                        .getJSONObject("geometry").getJSONObject("location")
                    Pair(location.getDouble("lat"), location.getDouble("lng"))
                } else null
            } catch (e: Exception) { null }
        }
    }

    private suspend fun getNearbyBusStops(lat: Double, lon: Double, destinationAddr: String): List<NearbyStation> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json?location=$lat,$lon&rankby=distance&type=transit_station&key=$MAPS_API_KEY"
                val conn = URL(url).openConnection() as HttpURLConnection
                val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(resp)
                val results = mutableListOf<NearbyStation>()
                if (json.getString("status") == "OK") {
                    val places = json.getJSONArray("results")
                    for (i in 0 until places.length().coerceAtMost(5)) {
                        val place = places.getJSONObject(i)
                        val name = place.getString("name")
                        val pLat = place.getJSONObject("geometry").getJSONObject("location").getDouble("lat")
                        val pLon = place.getJSONObject("geometry").getJSONObject("location").getDouble("lng")
                        
                        val walkUrl = "https://maps.googleapis.com/maps/api/distancematrix/json?origins=$pLat,$pLon&destinations=${URLEncoder.encode(destinationAddr, "UTF-8")}&mode=walking&key=$MAPS_API_KEY"
                        val walkConn = URL(walkUrl).openConnection() as HttpURLConnection
                        val walkResp = BufferedReader(InputStreamReader(walkConn.inputStream)).readText()
                        val walkJson = JSONObject(walkResp)
                        val walkTime = walkJson.getJSONArray("rows").getJSONObject(0).getJSONArray("elements").getJSONObject(0).getJSONObject("duration").getInt("value") / 60
                        
                        results.add(NearbyStation(name, walkTime, "Bus", pLat, pLon, "Bus Stop"))
                    }
                }
                results
            } catch (e: Exception) { emptyList() }
        }
    }

    data class SetupResult(val suitableStations: List<Station>, val closestToDest: List<NearbyStation>, val regions: Set<String>)

    private suspend fun findSuitableHubsAndRegions(destination: String, arrivalTimeStr: String, maxTime: Int, modes: List<String>, strategy: String): SetupResult {
        return withContext(Dispatchers.IO) {
            val allStations = loadAllStationsFromAssets()
            if (MAPS_API_KEY == "YOUR_API_KEY_HERE" || MAPS_API_KEY.isEmpty()) {
                return@withContext SetupResult(allStations.filter { it.travelTimeMin <= maxTime }, emptyList(), setOf("van", "bnc"))
            }

            val destCoords = getLatLngFromAddress(destination) ?: return@withContext SetupResult(emptyList(), emptyList(), emptySet())

            try {
                val encodedDest = URLEncoder.encode(destination, "UTF-8")
                val stationWalkTimes = mutableListOf<NearbyStation>()

                // Phase 1: Transit Hub Discovery
                if (modes.contains("skytrain_walk") || modes.contains("bus_skytrain")) {
                    val walkBatches = allStations.chunked(25)
                    for (batch in walkBatches) {
                        val origins = batch.joinToString("|") { "${it.lat},${it.lon}" }
                        val walkUrl = "https://maps.googleapis.com/maps/api/distancematrix/json?origins=$origins&destinations=$encodedDest&mode=walking&key=$MAPS_API_KEY"
                        val conn = URL(walkUrl).openConnection() as HttpURLConnection
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val json = JSONObject(resp)
                        if (json.getString("status") == "OK") {
                            val rows = json.getJSONArray("rows")
                            for (i in 0 until rows.length()) {
                                val element = rows.getJSONObject(i).getJSONArray("elements").getJSONObject(0)
                                if (element.getString("status") == "OK") {
                                    val dur = element.getJSONObject("duration").getInt("value") / 60
                                    stationWalkTimes.add(NearbyStation(batch[i].name, dur, getStationLine(batch[i].name), batch[i].lat, batch[i].lon))
                                }
                            }
                        }
                    }
                }

                if (modes.contains("bus") || modes.contains("bus_skytrain")) {
                    val busStops = getNearbyBusStops(destCoords.first, destCoords.second, destination)
                    stationWalkTimes.addAll(busStops)
                }

                if (stationWalkTimes.isEmpty()) return@withContext SetupResult(emptyList(), emptyList(), emptySet())
                
                val finalNearby = mutableListOf<NearbyStation>()
                if (modes.contains("bus") && !modes.contains("skytrain_walk") && !modes.contains("bus_skytrain")) {
                    finalNearby.addAll(stationWalkTimes.filter { it.type == "Bus Stop" }.sortedBy { it.walkTimeMin }.take(2))
                } else if (modes.contains("bus_skytrain")) {
                    finalNearby.addAll(stationWalkTimes.filter { it.type == "Skytrain" }.sortedBy { it.walkTimeMin }.take(2))
                    finalNearby.addAll(stationWalkTimes.filter { it.type == "Bus Stop" }.sortedBy { it.walkTimeMin }.take(2))
                } else {
                    finalNearby.addAll(stationWalkTimes.sortedBy { it.walkTimeMin }.take(2))
                }
                
                SetupResult(allStations.filter { it.travelTimeMin <= maxTime }, finalNearby.sortedBy { it.walkTimeMin }, setOf("van", "bnc", "rds", "pml"))
            } catch (e: Exception) {
                SetupResult(allStations.filter { it.travelTimeMin <= maxTime }, emptyList(), setOf("van", "bnc"))
            }
        }
    }

    private fun loadAllStationsFromAssets(): List<Station> {
        return listOf(
            Station("Waterfront Station", 49.2859, -123.1117, 0),
            Station("Burrard Station", 49.2855, -123.1200, 2),
            Station("Granville Station", 49.2831, -123.1161, 3),
            Station("Stadium–Chinatown Station", 49.2796, -123.1097, 5),
            Station("Main Street–Science World Station", 49.2733, -123.0996, 7),
            Station("Commercial–Broadway Station", 49.2626, -123.0692, 10),
            Station("Nanaimo Station", 49.2483, -123.0559, 13),
            Station("29th Avenue Station", 49.2442, -123.0460, 15),
            Station("Joyce–Collingwood Station", 49.2384, -123.0318, 17),
            Station("Patterson Station", 49.2297, -123.0127, 19),
            Station("Metrotown Station", 49.2257, -123.0039, 21),
            Station("Royal Oak Station", 49.2200, -122.9884, 23),
            Station("Edmonds Station", 49.2138, -122.9591, 26),
            Station("22nd Street Station", 49.2000, -122.9490, 29),
            Station("New Westminster Station", 49.2014, -122.9107, 32),
            Station("Columbia Station", 49.2048, -122.9061, 34),
            Station("Sapperton Station", 49.2245, -122.8895, 37),
            Station("Braid Station", 49.2331, -122.8828, 39),
            Station("Lougheed Town Centre Station", 49.2485, -122.8970, 41),
            Station("Production Way–University Station", 49.2534, -122.9181, 43),
            Station("Scott Road Station", 49.2044, -122.8743, 38),
            Station("Gateway Station", 49.1990, -122.8507, 41),
            Station("Surrey Central Station", 49.1896, -122.8480, 43),
            Station("King George Station", 49.1827, -122.8452, 45),
            Station("VCC–Clark Station", 49.2658, -123.0867, 12),
            Station("Renfrew Station", 49.2589, -123.0454, 15),
            Station("Rupert Station", 49.2609, -123.0329, 17),
            Station("Gilmore Station", 49.2648, -123.0137, 19),
            Station("Brentwood Town Centre Station", 49.2664, -123.0016, 21),
            Station("Holdom Station", 49.2647, -122.9822, 23),
            Station("Sperling–Burnaby Lake Station", 49.2592, -122.9640, 26),
            Station("Lake City Way Station", 49.2546, -122.9392, 28),
            Station("Burquitlam Station", 49.2613, -122.8899, 45),
            Station("Moody Centre Station", 49.2780, -122.8460, 50),
            Station("Inlet Centre Station", 49.2772, -122.8282, 53),
            Station("Coquitlam Central Station", 49.2739, -122.7999, 56),
            Station("Lincoln Station", 49.2798, -122.7930, 58),
            Station("Lafarge Lake–Douglas Station", 49.2856, -122.7916, 60),
            Station("Vancouver City Centre Station", 49.2825, -123.1185, 2),
            Station("Yaletown–Roundhouse Station", 49.2745, -123.1219, 4),
            Station("Olympic Village Station", 49.2665, -123.1154, 6),
            Station("Broadway–City Hall Station", 49.2630, -123.1147, 8),
            Station("King Edward Station", 49.2492, -123.1158, 11),
            Station("Oakridge–41st Avenue Station", 49.2331, -123.1162, 14),
            Station("Langara–49th Avenue Station", 49.2263, -123.1161, 16),
            Station("Marine Drive Station", 49.2098, -123.1174, 19),
            Station("Bridgeport Station", 49.1924, -123.1262, 22),
            Station("Aberdeen Station", 49.1843, -123.1362, 25),
            Station("Lansdowne Station", 49.1747, -123.1362, 27),
            Station("Richmond–Brighouse Station", 49.1681, -123.1362, 29),
            Station("Templeton Station", 49.1967, -123.1462, 26),
            Station("Sea Island Centre Station", 49.1931, -123.1587, 28),
            Station("YVR–Airport Station", 49.1939, -123.1769, 30)
        )
    }

    @Composable
    fun ResultCard(result: CraigslistResult) {
        val context = LocalContext.current
        val totalTime = result.totalCommuteTime
        val trainLine = getStationLine(result.stationName)

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Text(
                        text = result.title, 
                        style = MaterialTheme.typography.titleMedium, 
                        color = MaterialTheme.colorScheme.primary, 
                        modifier = Modifier.weight(1f).clickable { 
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                            intent.putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, context.packageName)
                            context.startActivity(intent)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = result.price, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.secondary)
                        if (result.priceUsd.isNotEmpty()) Text(text = result.priceUsd, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                Text(text = "Transit: $trainLine", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                Text(text = "Total Commute: ${totalTime} mins", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = if (totalTime <= 35) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface)
                Column(modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
                    Text("• Walk to ${result.stationName}: ${result.walkToStationTime} mins", style = MaterialTheme.typography.bodySmall)
                    Text("• Transit ride: ${result.stationTravelTime} mins", style = MaterialTheme.typography.bodySmall)
                    Text("• Final walk to destination: ${result.officeWalkTime} mins", style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    val button1Text = if (result.transitMode == "Bus") "Walk 1" else "Walk to Station"
                    val transitBtnText = if (result.transitMode == "Bus") "Transit" else "Train"
                    val button2Text = if (result.transitMode == "Bus") "Walk 2" else "Walk to Office"

                    Button(onClick = { 
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://maps.google.com/maps?saddr=${result.lat},${result.lon}&daddr=${result.stationLat},${result.stationLon}&dirflg=w"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                        context.startActivity(intent)
                    }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text(button1Text, fontSize = 11.sp) }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = { 
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://maps.google.com/maps?saddr=${result.stationLat},${result.stationLon}&daddr=${result.destStationLat},${result.destStationLon}&dirflg=r"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                        context.startActivity(intent)
                    }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text(transitBtnText, fontSize = 11.sp) }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = { 
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://maps.google.com/maps?saddr=${result.destStationLat},${result.destStationLon}&daddr=${Uri.encode(result.commuteDestination)}&dirflg=w"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                        context.startActivity(intent)
                    }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text(button2Text, fontSize = 11.sp) }
                }
            }
        }
    }

    private suspend fun getUsdExchangeRate(): Double {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val storedDate = prefs.getString("date", null)
        if (today == storedDate) {
            val storedRate = prefs.getFloat("rate", 0.0f).toDouble()
            if (storedRate > 0.0) return storedRate
        }
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.exchangerate-api.com/v4/latest/CAD")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                val response = BufferedReader(InputStreamReader(connection.inputStream)).readText()
                val jsonObject = JSONObject(response)
                val exchangeRate = jsonObject.getJSONObject("rates").getDouble("USD")
                prefs.edit().putString("date", today).putFloat("rate", exchangeRate.toFloat()).apply()
                exchangeRate
            } catch (e: Exception) { prefs.getFloat("rate", 0.73f).toDouble() }
        }
    }

    private fun getRandomHeaders(): Map<String, String> {
        val userAgents = listOf("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1", "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36")
        return mapOf("User-Agent" to userAgents.random(), "Accept-Language" to "en-US,en;q=0.9", "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8", "Connection" to "keep-alive")
    }

    private fun runSearch(url: String, maxDistance: Int, stations: List<Station>, destStation: Station?, officeWalkTime: Int, commuteDestination: String, excludeRoomShare: Boolean, transitMode: String, onStatus: (String) -> Unit, onDebug: (List<DebugEntry>) -> Unit, onResult: (List<CraigslistResult>) -> Unit) {
        lifecycleScope.launch {
            val debugList = mutableListOf<DebugEntry>()
            val exchangeRate = getUsdExchangeRate()
            val results = withContext(Dispatchers.IO) {
                try {
                    val doc = Jsoup.connect(url).headers(getRandomHeaders()).timeout(15000).get()
                    val listings = doc.select(".cl-static-search-result")
                    val totalToCheck = listings.size.coerceAtMost(500)
                    val allResults = listings.take(totalToCheck).mapIndexedNotNull { index, element ->
                        val title = element.select(".title").text()
                        val priceStr = element.select(".price").text()
                        
                        if (excludeRoomShare) {
                            val lowerTitle = title.lowercase()
                            val roomShareKeywords = listOf("room", "share", "roommate", "master bedroom", "sublet", "rent a room")
                            if (roomShareKeywords.any { lowerTitle.contains(it) }) {
                                debugList.add(DebugEntry(title, "Exclude Room/Share"))
                                return@mapIndexedNotNull null
                            }
                        }

                        withContext(Dispatchers.Main) { onStatus("Checking ${index + 1} of $totalToCheck: ${title.take(15)}...") }
                        delay(Random.nextLong(10, 30))
                        val link = element.select("a").attr("abs:href")
                        try {
                            val detailDoc = Jsoup.connect(link).headers(getRandomHeaders()).header("Referer", url).timeout(10000).get()
                            val finalCanonicalUrl = detailDoc.location()
                            val timeElement = detailDoc.select("time.timeago").first()
                            val postDateMillis = if (timeElement != null) { try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(timeElement.attr("datetime"))?.time ?: 0L } catch (e: Exception) { 0L } } else 0L
                            val mapDiv = detailDoc.select("#map").first()
                            val lat = mapDiv?.attr("data-latitude")?.toDoubleOrNull()
                            val lon = mapDiv?.attr("data-longitude")?.toDoubleOrNull()
                            if (lat == null || lon == null) { debugList.add(DebugEntry(title, "No GPS data")); return@mapIndexedNotNull null }
                            val distResult = FloatArray(1)
                            var bestStat: Station? = null
                            var minDist = Float.MAX_VALUE
                            for (s in stations) { android.location.Location.distanceBetween(lat, lon, s.lat, s.lon, distResult); if (distResult[0] < minDist) { minDist = distResult[0]; bestStat = s } }
                            if (bestStat != null && destStation != null) {
                                val walkTime = (minDist / 80).toInt()
                                if (minDist > maxDistance) { debugList.add(DebugEntry(title, "Too far")); null } else {
                                    val total = walkTime + bestStat.travelTimeMin + officeWalkTime
                                    if (total > 85) { debugList.add(DebugEntry(title, "Commute > 85m")); null } else {
                                        val priceCad = priceStr.filter { it.isDigit() }.toIntOrNull()
                                        val priceUsd = if (priceCad != null) "($${(priceCad * exchangeRate).toInt()} USD)" else ""
                                        CraigslistResult(title = title, price = if (priceStr.isEmpty()) "N/A" else priceStr, url = finalCanonicalUrl, stationName = bestStat.name, stationTravelTime = bestStat.travelTimeMin, walkToStationTime = walkTime, lat = lat, lon = lon, totalCommuteTime = total, postDateMillis = postDateMillis, stationLat = bestStat.lat, stationLon = bestStat.lon, priceUsd = priceUsd, destStationName = destStation.name, destStationLat = destStation.lat, destStationLon = destStation.lon, officeWalkTime = officeWalkTime, commuteDestination = commuteDestination, transitMode = transitMode)
                                    }
                                }
                            } else { debugList.add(DebugEntry(title, "No station")); null }
                        } catch (e: Exception) { debugList.add(DebugEntry(title, "Error")); null }
                    }
                    allResults.groupBy { it.title.trim().lowercase() }.mapValues { it.value.maxByOrNull { r -> r.postDateMillis }!! }.values.sortedByDescending { it.postDateMillis }
                } catch (e: Exception) { withContext(Dispatchers.Main) { onStatus("Failed") }; emptyList<CraigslistResult>() }
            }
            onDebug(debugList); onResult(results)
        }
    }
}

fun buildCraigslistUrl(region: String, maxPrice: String, minBeds: String, maxBeds: String, minBaths: String, maxBaths: String, typeIds: List<String>, parkingIds: List<String>, laundryIds: List<String>, cats: Boolean, dogs: Boolean, furnished: Boolean): String {
    val regionPath = when(region) { "van" -> "van/"; "bnc" -> "bnc/"; "rds" -> "rds/"; "pml" -> "pml/"; else -> "" }
    val baseUrl = "https://vancouver.craigslist.org/search/${regionPath}apa?"
    val params = mutableListOf("max_price=$maxPrice", "min_bedrooms=$minBeds", "max_bedrooms=$maxBeds", "min_bathrooms=$minBaths", "max_bathrooms=$maxBaths", "hasPic=1")
    typeIds.forEach { params.add("housing_type=$it") }
    parkingIds.forEach { params.add("parking_multi=$it") }
    laundryIds.forEach { params.add("laundry=$it") }
    if (cats) params.add("pets_cat=1"); if (dogs) params.add("pets_dog=1"); if (furnished) params.add("is_furnished=1")
    return baseUrl + params.joinToString("&")
}

@Composable
fun CraigslistFilterDashboard(regions: Set<String>, onSearch: (String, Int, Boolean) -> Unit) {
    var selectedRegion by remember { mutableStateOf(if (regions.isNotEmpty()) regions.first() else "bnc") }
    var regionExpanded by remember { mutableStateOf(false) }
    var maxPrice by remember { mutableStateOf("4000") }
    var minBeds by remember { mutableStateOf("1") }
    var maxBeds by remember { mutableStateOf("2") }
    var minBaths by remember { mutableStateOf("1") }
    var maxBaths by remember { mutableStateOf("2") }
    var selectedParking by remember { mutableStateOf(setOf<String>()) }
    var selectedLaundry by remember { mutableStateOf(setOf<String>()) }
    var excludeRoomShare by remember { mutableStateOf(true) }
    var maxWalkDist by remember { mutableStateOf(800f) }

    val allRegionsMap = mapOf("Burnaby/New West" to "bnc", "City of Vancouver" to "van", "Richmond/Surrey/Delta" to "rds", "Tri-Cities/Pitt/Maple" to "pml")
    val filteredRegions = allRegionsMap.filter { regions.contains(it.value) }
    val parkingMap = mapOf("carport" to "1", "attached garage" to "2", "detached garage" to "3", "off-street" to "4", "street" to "5")
    val laundryMap = mapOf("w/d in unit" to "1", "w/d hookups" to "2", "laundry in bldg" to "3", "laundry on site" to "4", "no laundry on site" to "5")

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { excludeRoomShare = !excludeRoomShare }) {
            Checkbox(checked = excludeRoomShare, onCheckedChange = { excludeRoomShare = it })
            Text("Exclude room/share", fontWeight = FontWeight.Bold)
        }
        
        Text("Search Region", style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { regionExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Region: ${allRegionsMap.entries.find { it.value == selectedRegion }?.key ?: "Select"}") }
            DropdownMenu(expanded = regionExpanded, onDismissRequest = { regionExpanded = false }) { filteredRegions.forEach { (label, value) -> DropdownMenuItem(text = { Text(label) }, onClick = { selectedRegion = value; regionExpanded = false }) } }
        }
        OutlinedTextField(value = maxPrice, onValueChange = { maxPrice = it }, label = { Text("Max Price") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = minBeds, onValueChange = { minBeds = it }, label = { Text("Min Beds") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(value = maxBeds, onValueChange = { maxBeds = it }, label = { Text("Max Beds") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = minBaths, onValueChange = { minBaths = it }, label = { Text("Min Baths") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(value = maxBaths, onValueChange = { maxBaths = it }, label = { Text("Max Baths") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Text("Parking Selection", style = MaterialTheme.typography.titleSmall)
        Surface(modifier = Modifier.height(110.dp).fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.small) { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { parkingMap.forEach { (label, id) -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { selectedParking = if (selectedParking.contains(id)) selectedParking - id else selectedParking + id }.padding(vertical = 4.dp)) { Checkbox(checked = selectedParking.contains(id), onCheckedChange = null); Text(text = label) } } } }
        Text("Laundry Selection", style = MaterialTheme.typography.titleSmall)
        Surface(modifier = Modifier.height(110.dp).fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.small) { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { laundryMap.forEach { (label, id) -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { selectedLaundry = if (selectedLaundry.contains(id)) selectedLaundry - id else selectedLaundry + id }.padding(vertical = 4.dp)) { Checkbox(checked = selectedLaundry.contains(id), onCheckedChange = null); Text(text = label) } } } }
        Text("Max Walk: ${maxWalkDist.toInt()}m", style = MaterialTheme.typography.labelLarge)
        Slider(value = maxWalkDist, onValueChange = { maxWalkDist = it }, valueRange = 400f..1600f, steps = 2)
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { onSearch(buildCraigslistUrl(selectedRegion, maxPrice, minBeds, maxBeds, minBaths, maxBaths, emptyList(), selectedParking.toList(), selectedLaundry.toList(), false, false, false), maxWalkDist.toInt(), excludeRoomShare) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Search Vancouver Housing", modifier = Modifier.padding(8.dp)) }
    }
}
