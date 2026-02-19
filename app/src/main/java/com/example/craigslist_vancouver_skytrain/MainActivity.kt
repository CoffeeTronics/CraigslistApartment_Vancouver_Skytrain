package com.example.craigslist_vancouver_skytrain

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
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
    val priceUsd: String
)

class MainActivity : ComponentActivity() {
    private val officeWalkTime = 13 // Constant walk from destination station to Baxter Pl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
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
                    Column(modifier = Modifier.padding(16.dp).statusBarsPadding()) {
                        if (!showResults && !isSearching) {
                            Text(
                                text = "Vancouver Skytrain Housing",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            CraigslistFilterDashboard(onSearch = { url, distance ->
                                isSearching = true
                                searchResults = emptyList()
                                debugLogs = emptyList()

                                runSearch(
                                    url = url,
                                    maxDistance = distance,
                                    onStatus = { statusMessage = it },
                                    onDebug = { debugLogs = it },
                                    onResult = { results ->
                                        searchResults = results
                                        isSearching = false
                                        showResults = true
                                    }
                                )
                            })
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
                                    Button(onClick = { showResults = false }) { Text("Return to Search") }
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

    @Composable
    fun ResultCard(result: CraigslistResult) {
        val totalTime = result.totalCommuteTime

        // Detect the SkyTrain line for better commute context
        val trainLine = when {
            result.stationName.contains("Production Way") ||
                    result.stationName.contains("Lougheed") ||
                    result.stationName.contains("Burquitlam") ||
                    result.stationName.contains("Sperling") -> "Millennium Line"

            result.stationName.contains("Metrotown") ||
                    result.stationName.contains("Edmonds") ||
                    result.stationName.contains("Joyce") -> "Expo Line"

            else -> "SkyTrain"
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Top Row: Title and Price
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = result.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).clickable {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                        }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = result.price,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        if (result.priceUsd.isNotEmpty()) {
                            Text(
                                text = result.priceUsd,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }

                Text(
                    text = "Transit: $trainLine",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)

                // Commute Breakdown
                Text(
                    text = "Total Commute: ${totalTime} mins",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = if (totalTime <= 35) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                )

                Column(modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
                    Text("• Walk to ${result.stationName}: ${result.walkToStationTime} mins", style = MaterialTheme.typography.bodySmall)
                    Text("• SkyTrain to office area: ${result.stationTravelTime} mins", style = MaterialTheme.typography.bodySmall)
                    Text("• Final walk to Baxter Pl: $officeWalkTime mins", style = MaterialTheme.typography.bodySmall)
                }

                // Maps Action Button
                Button(
                    onClick = {
                        val mapUri = Uri.parse(
                            "http://maps.google.com/maps?saddr=${result.lat},${result.lon}" +
                                    "&daddr=${result.stationLat},${result.stationLon}&dirflg=w"
                        )
                        startActivity(Intent(Intent.ACTION_VIEW, mapUri).setPackage("com.google.android.apps.maps"))
                    },
                    modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("View Walk")
                }
            }
        }
    }

    private suspend fun getUsdExchangeRate(): Double {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.exchangerate-api.com/v4/latest/CAD")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                val jsonObject = JSONObject(response)
                val rates = jsonObject.getJSONObject("rates")
                rates.getDouble("USD")
            } catch (e: Exception) {
                // Fallback rate
                0.73
            }
        }
    }

    private fun getRandomHeaders(): Map<String, String> {
        val userAgents = listOf(
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36"
        )

        return mapOf(
            "User-Agent" to userAgents.random(),
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Connection" to "keep-alive"
        )
    }

    private fun runSearch(
        url: String,
        maxDistance: Int,
        onStatus: (String) -> Unit,
        onDebug: (List<DebugEntry>) -> Unit,
        onResult: (List<CraigslistResult>) -> Unit
    ) {
        lifecycleScope.launch {
            val debugList = mutableListOf<DebugEntry>()
            val exchangeRate = getUsdExchangeRate()
            val results = withContext(Dispatchers.IO) {
                val stations = loadStations()
                try {
                    // Initial request with randomized headers to mimic human browsing
                    val doc = Jsoup.connect(url)
                        .headers(getRandomHeaders())
                        .timeout(15000)
                        .get()

                    val listings = doc.select(".cl-static-search-result")
                    val totalToCheck = listings.size.coerceAtMost(500) // Search depth

                    val allResults = listings.take(totalToCheck).mapIndexedNotNull { index, element ->
                        val title = element.select(".title").text()
                        val priceStr = element.select(".price").text() // Scrape price

                        withContext(Dispatchers.Main) {
                            onStatus("Checking ${index + 1} of $totalToCheck: ${title.take(15)}...")
                        }

                        // Delay to prevent rate-limiting while searching from Arizona
                        delay(Random.nextLong(10, 25))

                        val link = element.select("a").attr("abs:href")
                        try {
                            val detailDoc = Jsoup.connect(link)
                                .headers(getRandomHeaders())
                                .header("Referer", url)
                                .timeout(10000)
                                .get()

                            val timeElement = detailDoc.select("time.timeago").first()
                            val datetime = timeElement?.attr("datetime")
                            val postDateMillis = if (datetime != null) {
                                try {
                                    // Handles format like: 2024-05-10T11:04:47-0700
                                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
                                    sdf.parse(datetime)?.time ?: 0L
                                } catch (e: ParseException) {
                                    0L // Couldn't parse date
                                }
                            } else {
                                0L
                            }

                            val mapDiv = detailDoc.select("#map").first()
                            val lat = mapDiv?.attr("data-latitude")?.toDoubleOrNull()
                            val lon = mapDiv?.attr("data-longitude")?.toDoubleOrNull()

                            if (lat == null || lon == null) {
                                debugList.add(DebugEntry(title, "No GPS data on map"))
                                return@mapIndexedNotNull null
                            }

                            val distResult = FloatArray(1)
                            var bestStat: Station? = null
                            var minDist = Float.MAX_VALUE

                            // Find the absolute closest station
                            for (s in stations) {
                                android.location.Location.distanceBetween(lat, lon, s.lat, s.lon, distResult)
                                if (distResult[0] < minDist) {
                                    minDist = distResult[0]
                                    bestStat = s
                                }
                            }

                            if (bestStat != null) {
                                val walkTime = (minDist / 80).toInt()

                                if (minDist > maxDistance) {
                                    debugList.add(DebugEntry(title, "Too far from station (${minDist.toInt()}m)"))
                                    null
                                } else {
                                    // Commute check: Walk + Train + 13m Office Walk
                                    val total = walkTime + bestStat.travelTimeMin + officeWalkTime
                                    if (total > 45) {
                                        debugList.add(DebugEntry(title, "Commute too long ($total mins)"))
                                        null
                                    } else {
                                        val priceCad = priceStr.filter { it.isDigit() }.toIntOrNull()
                                        val priceUsd = if (priceCad != null) {
                                            "($${(priceCad * exchangeRate).toInt()} USD)"
                                        } else {
                                            ""
                                        }

                                        CraigslistResult(
                                            title = title,
                                            price = if (priceStr.isEmpty()) "N/A" else priceStr,
                                            url = link,
                                            stationName = bestStat.name,
                                            stationTravelTime = bestStat.travelTimeMin,
                                            walkToStationTime = walkTime,
                                            lat = lat,
                                            lon = lon,
                                            totalCommuteTime = total,
                                            postDateMillis = postDateMillis,
                                            stationLat = bestStat.lat,
                                            stationLon = bestStat.lon,
                                            priceUsd = priceUsd
                                        )
                                    }
                                }
                            } else {
                                debugList.add(DebugEntry(title, "No suitable stations found"))
                                null
                            }
                        } catch (e: Exception) {
                            debugList.add(DebugEntry(title, "Detail page load error"))
                            null
                        }
                    }

                    // Deduplicate results by title, keeping the newest post
                    val uniqueResults = allResults
                        .groupBy { it.title.trim().lowercase(Locale.ROOT) }
                        .mapValues { (_, groupedResults) ->
                            groupedResults.maxByOrNull { it.postDateMillis }!!
                        }
                        .values
                        .sortedByDescending { it.postDateMillis }

                    uniqueResults
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { onStatus("Search failed: Check connection") }
                    emptyList<CraigslistResult>()
                }
            }
            onDebug(debugList)
            onResult(results)
        }
    }

    private fun loadStations(): List<Station> {
        val stations = mutableListOf<Station>()
        try {
            val jsonString = assets.open("Suitable_Stations_Within_45.json").use { it.bufferedReader().readText() }
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                stations.add(Station(obj.getString("station"), obj.getDouble("lat"), obj.getDouble("lon"), obj.getInt("travel_time_min")))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return stations
    }
}

fun buildCraigslistUrl(
    region: String,
    maxPrice: String,
    minBeds: String,
    maxBeds: String,
    minBaths: String,
    maxBaths: String,
    typeIds: List<String>,
    parkingIds: List<String>,
    cats: Boolean,
    dogs: Boolean,
    furnished: Boolean
): String {
    val regionPath = when(region) {
        "van" -> "van/"
        "bnc" -> "bnc/"
        else -> ""
    }

    val baseUrl = "https://vancouver.craigslist.org/search/${regionPath}apa?"
    val params = mutableListOf(
        "max_price=$maxPrice",
        "min_bedrooms=$minBeds",
        "max_bedrooms=$maxBeds",
        "min_bathrooms=$minBaths",
        "max_bathrooms=$maxBaths",
        "hasPic=1"
    )

    typeIds.forEach { params.add("housing_type=$it") }
    parkingIds.forEach { params.add("parking_multi=$it") }

    if (cats) params.add("pets_cat=1")
    if (dogs) params.add("pets_dog=1")
    if (furnished) params.add("is_furnished=1")

    return baseUrl + params.joinToString("&")
}

@Composable
fun CraigslistFilterDashboard(onSearch: (String, Int) -> Unit) {
    var selectedRegion by remember { mutableStateOf("bnc") }
    var regionExpanded by remember { mutableStateOf(false) }
    var maxPrice by remember { mutableStateOf("4000") }
    var minBeds by remember { mutableStateOf("1") }
    var maxBeds by remember { mutableStateOf("2") }
    var minBaths by remember { mutableStateOf("1") }
    var maxBaths by remember { mutableStateOf("2") }
    var selectedParking by remember { mutableStateOf(setOf<String>()) }
    var maxWalkDist by remember { mutableStateOf(800f) }

    val regionsMap = mapOf("Burnaby/New West" to "bnc", "City of Vancouver" to "van", "All Regions" to "both")
    val parkingMap = mapOf("carport" to "1", "attached garage" to "2", "detached garage" to "3", "off-street" to "4", "street" to "5")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Region Dropdown - RESTORED
        Text("Search Region", style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { regionExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Region: ${regionsMap.filterValues { it == selectedRegion }.keys.first()}")
            }
            DropdownMenu(expanded = regionExpanded, onDismissRequest = { regionExpanded = false }) {
                regionsMap.forEach { (label, value) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { selectedRegion = value; regionExpanded = false })
                }
            }
        }

        // 2. Price and Range Fields
        OutlinedTextField(value = maxPrice, onValueChange = { maxPrice = it }, label = { Text("Max Price") }, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = minBeds, onValueChange = { minBeds = it }, label = { Text("Min Beds") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = maxBeds, onValueChange = { maxBeds = it }, label = { Text("Max Beds") }, modifier = Modifier.weight(1f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = minBaths, onValueChange = { minBaths = it }, label = { Text("Min Baths") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = maxBaths, onValueChange = { maxBaths = it }, label = { Text("Max Baths") }, modifier = Modifier.weight(1f))
        }

        // 3. Parking Checklist
        Text("Parking Selection", style = MaterialTheme.typography.titleSmall)
        Surface(modifier = Modifier.height(130.dp).fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.small) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                parkingMap.forEach { (label, id) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedParking = if (selectedParking.contains(id)) selectedParking - id else selectedParking + id
                        }.padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = selectedParking.contains(id), onCheckedChange = null)
                        Text(text = label)
                    }
                }
            }
        }

        // 4. Walk Slider
        Text("Max Walk: ${maxWalkDist.toInt()}m", style = MaterialTheme.typography.labelLarge)
        Slider(value = maxWalkDist, onValueChange = { maxWalkDist = it }, valueRange = 400f..1600f, steps = 2)

        Spacer(modifier = Modifier.height(8.dp))

        // 5. Final Search Button
        Button(
            onClick = {
                val url = buildCraigslistUrl(
                    selectedRegion, maxPrice, minBeds, maxBeds, minBaths, maxBaths,
                    emptyList(), selectedParking.toList(), false, false, false
                )
                onSearch(url, maxWalkDist.toInt())
            },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Text("Search Vancouver Housing", modifier = Modifier.padding(8.dp))
        }
    }
}
