package com.example.remotecontrol

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

/**
 * Se connecte à ton serveur de signalisation sur Render
 * (wss://signaling-server-2mly.onrender.com) et échange les messages
 * WebRTC (offer / answer / ice candidates) avec le navigateur
 * qui a ouvert controller.html?code=XXXXXX
 */
class SignalingClient(
    serverUrl: String,
    private val roomCode: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onRemoteOffer(sdp: String)
        fun onRemoteAnswer(sdp: String)
        fun onRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        fun onTapReceived(xRatio: Float, yRatio: Float)
        fun onDisconnected()
    }

    private val client = object : WebSocketClient(URI(serverUrl)) {
        override fun onOpen(handshakedata: ServerHandshake?) {
            // On rejoint la "room" identifiée par le code affiché sur controller.html
            val join = JSONObject()
            join.put("type", "join")
            join.put("code", roomCode)
            join.put("role", "android")
            send(join.toString())
            listener.onConnected()
        }

        override fun onMessage(message: String?) {
            if (message == null) return
            try {
                val json = JSONObject(message)
                when (json.optString("type")) {
                    "offer" -> listener.onRemoteOffer(json.getString("sdp"))
                    "answer" -> listener.onRemoteAnswer(json.getString("sdp"))
                    "ice-candidate" -> listener.onRemoteIceCandidate(
                        json.optString("sdpMid"),
                        json.optInt("sdpMLineIndex"),
                        json.getString("candidate")
                    )
                    // Message custom envoyé depuis controller.html quand l'iPhone tape à l'écran
                    "tap" -> listener.onTapReceived(
                        json.getDouble("xRatio").toFloat(),
                        json.getDouble("yRatio").toFloat()
                    )
                }
            } catch (e: Exception) {
                Log.e("SignalingClient", "Message invalide: $message", e)
            }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            listener.onDisconnected()
        }

        override fun onError(ex: Exception?) {
            Log.e("SignalingClient", "Erreur WebSocket", ex)
        }
    }

    fun connect() = client.connect()
    fun close() = client.close()

    fun sendOffer(sdp: String) = send("offer", sdp)
    fun sendAnswer(sdp: String) = send("answer", sdp)

    fun sendIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val json = JSONObject()
        json.put("type", "ice-candidate")
        json.put("code", roomCode)
        json.put("sdpMid", sdpMid)
        json.put("sdpMLineIndex", sdpMLineIndex)
        json.put("candidate", candidate)
        client.send(json.toString())
    }

    private fun send(type: String, sdp: String) {
        val json = JSONObject()
        json.put("type", type)
        json.put("code", roomCode)
        json.put("sdp", sdp)
        client.send(json.toString())
    }
}
