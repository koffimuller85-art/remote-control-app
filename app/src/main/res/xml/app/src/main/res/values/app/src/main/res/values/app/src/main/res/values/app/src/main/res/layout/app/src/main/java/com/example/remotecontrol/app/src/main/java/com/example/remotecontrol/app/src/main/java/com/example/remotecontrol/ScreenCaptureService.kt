package com.example.remotecontrol

import android.app.*
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.webrtc.*

/**
 * Capture l'écran (MediaProjection) et le diffuse en direct via WebRTC
 * vers le navigateur qui a ouvert controller.html?code=XXXXXX.
 * Reçoit aussi, via le data channel, les positions de tap envoyées par
 * l'iPhone, et les transmet à RemoteAccessibilityService pour les rejouer.
 */
class ScreenCaptureService : Service(), SignalingClient.Listener {

    private lateinit var signaling: SignalingClient
    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var mediaProjection: MediaProjection? = null
    private var videoCapturer: VideoCapturer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val roomCode = intent.getStringExtra("roomCode") ?: return START_NOT_STICKY
        val serverUrl = intent.getStringExtra("serverUrl") ?: return START_NOT_STICKY

        setupWebRTC()

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        startCapture(mediaProjection!!)

        signaling = SignalingClient(serverUrl, roomCode, this)
        signaling.connect()

        return START_STICKY
    }

    private fun setupWebRTC() {
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun startCapture(projection: MediaProjection) {
        videoCapturer = ScreenCapturerAndroid(projection, object : MediaProjection.Callback() {})
        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val videoSource = factory.createVideoSource(true)
        videoCapturer?.initialize(surfaceHelper, applicationContext, videoSource.capturerObserver)

        val metrics = resources.displayMetrics
        videoCapturer?.startCapture(metrics.widthPixels, metrics.heightPixels, 15)

        val videoTrack = factory.createVideoTrack("screen0", videoSource)

        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        )

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signaling.sendIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            }
            override fun onDataChannel(dc: DataChannel) {
                dc.registerObserver(object : DataChannel.Observer {
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        // Les taps arrivent aussi via le WebSocket (onTapReceived) selon
                        // ta config côté controller.html — garde ce channel en réserve.
                    }
                    override fun onBufferedAmountChange(p0: Long) {}
                    override fun onStateChange() {}
                })
            }
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
            override fun onAddStream(p0: MediaStream) {}
            override fun onRemoveStream(p0: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) {}
            override fun onAddTrack(p0: RtpReceiver, p1: Array<out MediaStream>) {}
        })

        peerConnection?.addTrack(videoTrack)
    }

    // --- SignalingClient.Listener : messages venant du serveur Render ---

    override fun onConnected() {
        // Une fois connecté et prêt, on crée une "offer" WebRTC pour le navigateur
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                signaling.sendOffer(desc.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    override fun onRemoteOffer(sdp: String) {
        // Pas utilisé côté Android : c'est nous qui envoyons l'offer.
    }

    override fun onRemoteAnswer(sdp: String) {
        peerConnection?.setRemoteDescription(
            SimpleSdpObserver(),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    override fun onRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    override fun onTapReceived(xRatio: Float, yRatio: Float) {
        // xRatio / yRatio sont entre 0 et 1 (position relative sur l'écran
        // capturé). RemoteAccessibilityService convertit en pixels réels
        // et rejoue le tap.
        RemoteAccessibilityService.instance?.performTapAt(xRatio, yRatio)
    }

    override fun onDisconnected() {
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        videoCapturer?.stopCapture()
        peerConnection?.close()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "screen_share_channel"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Partage d'écran", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Partage d'écran actif")
            .setContentText("Ton écran est en cours de diffusion")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }
}

private class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
