package nl.koffietijd.tibberwidget

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class TibberException(message: String) : IOException(message)

object TibberApi {
    private const val ENDPOINT = "https://api.tibber.com/v1-beta/gql"
    private const val USER_AGENT = "TibberWallWidget/1.0 (Android)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun query(priceResolution: String?): String {
        val priceArgs = if (priceResolution != null) "(resolution: $priceResolution)" else ""
        return """
        {
          viewer {
            websocketSubscriptionUrl
            homes {
              id
              appNickname
              address { address1 city }
              features { realTimeConsumptionEnabled }
              currentSubscription {
                status
                priceInfo$priceArgs {
                  current { total energy tax startsAt level currency }
                  today { total energy tax startsAt level }
                  tomorrow { total energy tax startsAt level }
                }
              }
              hourly: consumption(resolution: HOURLY, last: 48) { nodes { from consumption cost } }
              daily: consumption(resolution: DAILY, last: 3) { nodes { from consumption cost } }
            }
          }
        }
        """.trimIndent()
    }

    /**
     * Haalt alle gegevens op. Probeert eerst kwartierprijzen (Tibber NL rekent sinds okt. 2025 per
     * kwartier) en valt terug op uurprijzen als de API dat argument niet accepteert.
     */
    fun fetchViewer(token: String): JSONObject {
        return try {
            post(token, query("QUARTER_HOURLY"))
        } catch (e: TibberException) {
            if (e.message?.contains("401") == true || e.message?.contains("token", ignoreCase = true) == true) throw e
            post(token, query(null))
        }
    }

    /**
     * Teruglevering (zonnepanelen) per uur. Apart opgevraagd zodat een account zonder
     * teruglevering de rest van de gegevens niet laat mislukken.
     */
    fun fetchProduction(token: String): JSONObject = post(
        token,
        "{ viewer { homes { id production(resolution: HOURLY, last: 48) { nodes { from production profit } } } } }",
    )

    private fun post(token: String, query: String): JSONObject {
        val body = JSONObject().put("query", query).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                throw TibberException("Ongeldig API-token (HTTP ${resp.code})")
            }
            if (!resp.isSuccessful) throw TibberException("Tibber API gaf HTTP ${resp.code}")
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw TibberException("Onverwacht antwoord van Tibber")
            }
            val errors = json.optJSONArray("errors")
            val viewer = json.optJSONObject("data")?.optJSONObject("viewer")
            if (viewer == null) {
                val msg = errors?.optJSONObject(0)?.optString("message") ?: "Geen gegevens ontvangen"
                throw TibberException(msg)
            }
            return viewer
        }
    }

    /**
     * Haalt één live meting op via de Tibber websocket (alleen met Tibber Pulse / Watty).
     * Geeft null terug als er binnen de tijdslimiet niets binnenkomt.
     */
    suspend fun fetchLiveMeasurement(token: String, wsUrl: String, homeId: String): JSONObject? =
        withTimeoutOrNull(25_000) {
            suspendCancellableCoroutine { cont ->
                val request = Request.Builder()
                    .url(wsUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Sec-WebSocket-Protocol", "graphql-transport-ws")
                    .build()
                val subscription = """
                    subscription {
                      liveMeasurement(homeId: "$homeId") {
                        timestamp power powerProduction accumulatedConsumption accumulatedProduction
                        accumulatedCost accumulatedReward minPower averagePower maxPower currency
                      }
                    }
                """.trimIndent()

                val done = AtomicBoolean(false)
                fun finish(ws: WebSocket, result: JSONObject?) {
                    if (!done.compareAndSet(false, true)) return
                    try {
                        ws.send(JSONObject().put("id", "1").put("type", "complete").toString())
                        ws.close(1000, null)
                    } catch (_: Exception) {
                    }
                    if (cont.isActive) cont.resume(result)
                }

                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            JSONObject().put("type", "connection_init")
                                .put("payload", JSONObject().put("token", token)).toString()
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val msg = try { JSONObject(text) } catch (e: Exception) { return }
                        when (msg.optString("type")) {
                            "connection_ack" -> webSocket.send(
                                JSONObject().put("id", "1").put("type", "subscribe")
                                    .put("payload", JSONObject().put("query", subscription)).toString()
                            )
                            "next" -> {
                                val live = msg.optJSONObject("payload")?.optJSONObject("data")
                                    ?.optJSONObject("liveMeasurement")
                                if (live != null) finish(webSocket, live)
                            }
                            "ping" -> webSocket.send(JSONObject().put("type", "pong").toString())
                            "error", "complete" -> finish(webSocket, null)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (done.compareAndSet(false, true) && cont.isActive) cont.resume(null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (done.compareAndSet(false, true) && cont.isActive) cont.resume(null)
                    }
                })
                cont.invokeOnCancellation { ws.cancel() }
            }
        }
}
