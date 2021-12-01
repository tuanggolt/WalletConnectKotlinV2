package org.walletconnect.walletconnectv2.engine

import android.app.Application
import com.tinder.scarlet.WebSocket
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import org.walletconnect.walletconnectv2.clientsync.pairing.Pairing
import org.walletconnect.walletconnectv2.clientsync.pairing.SettledPairingSequence
import org.walletconnect.walletconnectv2.clientsync.pairing.after.PostSettlementPairing
import org.walletconnect.walletconnectv2.clientsync.pairing.before.proposal.PairingPermissions
import org.walletconnect.walletconnectv2.clientsync.pairing.before.success.PairingParticipant
import org.walletconnect.walletconnectv2.clientsync.pairing.before.success.PairingState
import org.walletconnect.walletconnectv2.clientsync.session.Controller
import org.walletconnect.walletconnectv2.clientsync.session.Session
import org.walletconnect.walletconnectv2.clientsync.session.SettledSessionPermissions
import org.walletconnect.walletconnectv2.clientsync.session.SettledSessionSequence
import org.walletconnect.walletconnectv2.clientsync.session.after.PostSettlementSession
import org.walletconnect.walletconnectv2.clientsync.session.after.params.Reason
import org.walletconnect.walletconnectv2.clientsync.session.before.PreSettlementSession
import org.walletconnect.walletconnectv2.clientsync.session.before.proposal.RelayProtocolOptions
import org.walletconnect.walletconnectv2.clientsync.session.before.success.SessionParticipant
import org.walletconnect.walletconnectv2.clientsync.session.common.SessionState
import org.walletconnect.walletconnectv2.common.*
import org.walletconnect.walletconnectv2.crypto.CryptoManager
import org.walletconnect.walletconnectv2.crypto.codec.AuthenticatedEncryptionCodec
import org.walletconnect.walletconnectv2.crypto.data.PublicKey
import org.walletconnect.walletconnectv2.crypto.data.SharedKey
import org.walletconnect.walletconnectv2.crypto.managers.LazySodiumCryptoManager
import org.walletconnect.walletconnectv2.engine.model.EngineData
import org.walletconnect.walletconnectv2.engine.sequence.SequenceLifecycle
import org.walletconnect.walletconnectv2.engine.serailising.encode
import org.walletconnect.walletconnectv2.engine.serailising.toEncryptionPayload
import org.walletconnect.walletconnectv2.engine.serailising.tryDeserialize
import org.walletconnect.walletconnectv2.engine.serailising.trySerialize
import org.walletconnect.walletconnectv2.errors.*
import org.walletconnect.walletconnectv2.exceptionHandler
import org.walletconnect.walletconnectv2.relay.WakuRelayRepository
import org.walletconnect.walletconnectv2.relay.data.jsonrpc.JsonRpcMethod.WC_PAIRING_DELETE
import org.walletconnect.walletconnectv2.relay.data.jsonrpc.JsonRpcMethod.WC_PAIRING_PAYLOAD
import org.walletconnect.walletconnectv2.relay.data.jsonrpc.JsonRpcMethod.WC_PAIRING_PING
import org.walletconnect.walletconnectv2.relay.data.jsonrpc.JsonRpcMethod.WC_SESSION_DELETE
import org.walletconnect.walletconnectv2.relay.data.jsonrpc.JsonRpcMethod.WC_SESSION_NOTIFICATION
import org.walletconnect.walletconnectv2.relay.data.jsonrpc.JsonRpcMethod.WC_SESSION_PAYLOAD
import org.walletconnect.walletconnectv2.relay.data.jsonrpc.JsonRpcMethod.WC_SESSION_PING
import org.walletconnect.walletconnectv2.relay.data.model.Relay
import org.walletconnect.walletconnectv2.relay.data.model.jsonrpc.JsonRpcRequest
import org.walletconnect.walletconnectv2.scope
import org.walletconnect.walletconnectv2.util.Logger
import org.walletconnect.walletconnectv2.util.generateId
import org.walletconnect.walletconnectv2.util.hexToUtf8
import java.util.*

internal class EngineInteractor {
    //region provide with DI
    // TODO: add logic to check hostName for ws/wss scheme with and without ://
    private lateinit var relayRepository: WakuRelayRepository
    private val codec: AuthenticatedEncryptionCodec = AuthenticatedEncryptionCodec()
    private val crypto: CryptoManager = LazySodiumCryptoManager()
    //endregion

    private var metaData: AppMetaData? = null

    private val _sequenceEvent: MutableStateFlow<SequenceLifecycle> = MutableStateFlow(SequenceLifecycle.Default)
    val sequenceEvent: StateFlow<SequenceLifecycle> = _sequenceEvent


    //Move to the Relayer
    private val _peerResponse: MutableStateFlow<Pair<Topic, EngineData.JsonRpcResponse?>> = MutableStateFlow(Pair(Topic(""), null))
    val peerResponse: StateFlow<Pair<Topic, EngineData.JsonRpcResponse?>> = _peerResponse

    private var isConnected = MutableStateFlow(false) // TODO: Maybe replace with an enum

    internal fun initialize(engine: EngineFactory) {
        this.metaData = engine.metaData
        relayRepository = WakuRelayRepository.initRemote(engine.toRelayInitParams())

        scope.launch(exceptionHandler) {
            relayRepository.eventsFlow
                .onEach { event: WebSocket.Event ->
                    Logger.log("$event")
                    if (event is WebSocket.Event.OnConnectionOpened<*>) {
                        isConnected.compareAndSet(expect = false, update = true)
                    }
                }
                .filterIsInstance<WebSocket.Event.OnConnectionFailed>()
                .collect { event -> throw event.throwable.exception }
        }

        scope.launch(exceptionHandler) {
            relayRepository.subscriptionRequest.collect { peerRequest ->

                val topic: Topic = peerRequest.subscriptionTopic
                val (sharedKey, selfPublic) = crypto.getKeyAgreement(topic)

                //Move to JSONRpcSelializer
                val decryptedMessage: String = if (sharedKey.keyAsHex.isEmpty() || selfPublic.keyAsHex.isEmpty()) {
                    peerRequest.message.hexToUtf8
                } else {
                    val encryptionPayload = peerRequest.message.toEncryptionPayload()
                    codec.decrypt(encryptionPayload, sharedKey as SharedKey)
                }

                tryDeserialize<JsonRpcRequest>(decryptedMessage)?.let { request ->
                    when (val rpc = request.method) {
                        WC_PAIRING_PAYLOAD -> onPairingPayload(decryptedMessage, sharedKey as SharedKey, selfPublic as PublicKey)
                        WC_PAIRING_DELETE -> onPairingDelete(decryptedMessage, topic)
                        WC_SESSION_PAYLOAD -> onSessionPayload(decryptedMessage, topic)
                        WC_SESSION_DELETE -> onSessionDelete(decryptedMessage, topic)
                        WC_SESSION_NOTIFICATION -> onSessionNotification(decryptedMessage, topic)
                        WC_SESSION_PING, WC_PAIRING_PING -> onPing(topic, request.id)
                        else -> onUnsupported(rpc)
                    }
                }

                tryDeserialize<Relay.Subscription.Acknowledgement>(decryptedMessage)?.let { acknowledgement ->
                    _peerResponse.value =
                        Pair(topic, EngineData.JsonRpcResponse.JsonRpcResult(acknowledgement.id, acknowledgement.result.toString()))
                }

                tryDeserialize<Relay.Subscription.JsonRpcError>(decryptedMessage)?.let { exception ->
                    _peerResponse.value = Pair(
                        topic,
                        EngineData.JsonRpcResponse.JsonRpcError(
                            exception.id,
                            EngineData.JsonRpcResponse.Error(exception.error.code, exception.error.message)
                        )
                    )
                }
            }
        }
    }

    internal fun pair(uri: String, onSuccess: (String) -> Unit, onFailure: (Throwable) -> Unit) {
        require(::relayRepository.isInitialized)

        val pairingProposal: Pairing.Proposal = uri.toPairProposal()
        val selfPublicKey: PublicKey = crypto.generateKeyPair()
        val expiry = Expiry((Calendar.getInstance().timeInMillis / 1000) + pairingProposal.ttl.seconds)
        val peerPublicKey = PublicKey(pairingProposal.pairingProposer.publicKey)
        val controllerPublicKey = if (pairingProposal.pairingProposer.controller) {
            peerPublicKey
        } else {
            selfPublicKey
        }
        val settledSequence = settlePairingSequence(
            pairingProposal.relay,
            selfPublicKey,
            peerPublicKey,
            controllerPublicKey,
            expiry
        )

        relayRepository.subscribe(pairingProposal.topic)

        val publishId: Long = generateId()
        val preSettlementPairingApprove = pairingProposal.toApprove(publishId, settledSequence.settledTopic, expiry, selfPublicKey)

        Logger.error("Kobe; Pairing Approve: $preSettlementPairingApprove")

        val encodedMessage = trySerialize(preSettlementPairingApprove).encode()
        val settledTopic = settledSequence.settledTopic.value

        scope.launch {
            supervisorScope {
                peerResponse
                    .filter { (topic, response) -> topic.value == pairingProposal.topic.value && response?.id == publishId }
                    .collect { (topic, response) ->

                        Logger.error("Kobe: Peer Response: $response")

                        when (response) {
                            is EngineData.JsonRpcResponse.JsonRpcResult -> {

                                Logger.error("Kobe: Peer Pairing Approve Acknowledgement: $response")

                                relayRepository.unsubscribe(pairingProposal.topic, SubscriptionId(response.id.toString()))
                                relayRepository.subscribe(settledSequence.settledTopic)

                                onSuccess(settledTopic)
                                pairingUpdate(settledSequence)
                            }
                            is EngineData.JsonRpcResponse.JsonRpcError -> {

                                Logger.error("Kobe: Peer Pairing Approve Error: $response")

                                onFailure(Throwable(response.error.message))
                            }
                            else -> {
                                Logger.error("Unknown object")
                            }
                        }
                    }
                cancel()
            }
        }

        isConnected
            .filter { isOnline -> isOnline }  // TODO: Update once enum is in place
            .onEach {
                supervisorScope {
                    //Check Response on Topic A
                    relayRepository.publish(pairingProposal.topic, encodedMessage) { error -> onFailure(error) }
                    cancel()
                }
            }
            .launchIn(scope)
    }

    private fun pairingUpdate(settledSequence: SettledPairingSequence) {
        val pairingUpdate: PostSettlementPairing.PairingUpdate =
            PostSettlementPairing.PairingUpdate(id = generateId(), params = Pairing.UpdateParams(state = PairingState(metaData)))
        val json: String = trySerialize(pairingUpdate)
        val (sharedKey, selfPublic) = crypto.getKeyAgreement(Topic(settledSequence.settledTopic.value))
        val encryptedMessage: String = codec.encrypt(json, sharedKey as SharedKey, selfPublic as PublicKey)

        //TODO add Peer Responses
        /*TODO update Pairing's metadata in local storage*/

        relayRepository.publish(settledSequence.settledTopic, encryptedMessage) { error -> Logger.error("Pairing update error: $error") }
    }

    internal fun approve(
        proposal: EngineData.SessionProposal,
        onSuccess: (EngineData.SettledSession) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        require(::relayRepository.isInitialized)

        val selfPublicKey: PublicKey = crypto.generateKeyPair()
        val peerPublicKey = PublicKey(proposal.proposerPublicKey)
        val sessionState = SessionState(proposal.accounts)
        val expiry = Expiry((Calendar.getInstance().timeInMillis / 1000) + proposal.ttl)
        val settledSession: SettledSessionSequence =
            settleSessionSequence(RelayProtocolOptions(), selfPublicKey, peerPublicKey, expiry, sessionState)
        val sessionApprove = PreSettlementSession.Approve(
            id = generateId(), params = Session.Success(
                relay = RelayProtocolOptions(), state = settledSession.state, expiry = expiry,
                responder = SessionParticipant(selfPublicKey.keyAsHex, metadata = this.metaData)
            )
        )
        val approvalJson: String = trySerialize(sessionApprove)
        val (sharedKey, selfPublic) = crypto.getKeyAgreement(Topic(proposal.topic))

        val encryptedMessage: String = codec.encrypt(approvalJson, sharedKey as SharedKey, selfPublic as PublicKey)
        relayRepository.subscribe(settledSession.topic)

        //TODO add the Peer Responses
//        with(proposal) { onSuccess(EngineData.SettledSession(icon, name, url, settledSession.topic.value))
        relayRepository.publish(Topic(proposal.topic), encryptedMessage)
        { error -> onFailure(error) }
    }

    internal fun reject(
        reason: String, topic: String,
        onSuccess: (Pair<String, String>) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        require(::relayRepository.isInitialized)

        val sessionReject = PreSettlementSession.Reject(id = generateId(), params = Session.Failure(reason = reason))
        val json: String = trySerialize(sessionReject)
        val (sharedKey, selfPublic) = crypto.getKeyAgreement(Topic(topic))
        val encryptedMessage: String = codec.encrypt(json, sharedKey as SharedKey, selfPublic as PublicKey)

        //TODO add the Peer Responses
//        onSuccess(Pair(topic, reason))
        relayRepository.publish(Topic(topic), encryptedMessage) { error -> onFailure(error) }
    }

    internal fun disconnect(
        topic: String, reason: String,
        onSuccess: (Pair<String, String>) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        require(::relayRepository.isInitialized)

        val sessionDelete = PostSettlementSession.SessionDelete(id = generateId(), params = Session.DeleteParams(Reason(message = reason)))
        val json = trySerialize(sessionDelete)
        val (sharedKey, selfPublic) = crypto.getKeyAgreement(Topic(topic))
        val encryptedMessage: String = codec.encrypt(json, sharedKey as SharedKey, selfPublic as PublicKey)
        //TODO Add subscriptionId from local storage + Delete all data from local storage coupled with given session
        crypto.removeKeys(topic)

        relayRepository.unsubscribe(Topic(topic), SubscriptionId("1"))

        //TODO add Peer Responses onSuccess(Pair(topic, reason))
        relayRepository.publish(Topic(topic), encryptedMessage) { error -> onFailure(error) }
    }

    internal fun respondSessionPayload(
        topic: String, jsonRpcResponse: EngineData.JsonRpcResponse,
        onSuccess: (String) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        require(::relayRepository.isInitialized)

        val json = trySerialize(jsonRpcResponse)
        val (sharedKey, selfPublic) = crypto.getKeyAgreement(Topic(topic))
        val encryptedMessage: String = codec.encrypt(json, sharedKey as SharedKey, selfPublic as PublicKey)

        //TODO add Peer Responses
//         onSuccess(topic)
        relayRepository.publish(Topic(topic), encryptedMessage) { error -> onFailure(error) }
    }

    internal fun update(
        topic: String, sessionState: EngineData.SessionState,
        onSuccess: (Pair<String, List<String>>) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        require(::relayRepository.isInitialized)

        val sessionUpdate: PostSettlementSession.SessionUpdate =
            PostSettlementSession.SessionUpdate(id = generateId(), params = Session.UpdateParams(SessionState(sessionState.accounts)))
        val json = trySerialize(sessionUpdate)
        val (sharedKey, selfPublic) = crypto.getKeyAgreement(Topic(topic))
        val encryptedMessage: String = codec.encrypt(json, sharedKey as SharedKey, selfPublic as PublicKey)
        //TODO update the session in local storage


//TODO handle Peer responses onSuccess(Pair(topic, sessionState.accounts))
        relayRepository.publish(Topic(topic), encryptedMessage) { error -> onFailure(error) }
    }

    internal fun upgrade(
        topic: String, permissions: EngineData.SessionPermissions,
        onSuccess: (Pair<String, EngineData.SessionPermissions>) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        require(::relayRepository.isInitialized)

        val sessionUpgrade =
            PostSettlementSession.SessionUpgrade(
                id = generateId(),
                params = Session.SessionPermissionsParams(permissions = permissions.toSessionsPermissions())
            )
        val json = trySerialize(sessionUpgrade)
        val (sharedKey, selfPublic) = crypto.getKeyAgreement(Topic(topic))
        val encryptedMessage: String = codec.encrypt(json, sharedKey as SharedKey, selfPublic as PublicKey)
        //TODO update session in local storage

        //TODO add Peer responses  onSuccess(Pair(topic, permissions))
        relayRepository.publish(Topic(topic), encryptedMessage) { error -> onFailure(error) }
    }

    internal fun notify(
        topic: String, notification: EngineData.Notification,
        onSuccess: (String) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        require(::relayRepository.isInitialized)

        /*TODO check whether under given topic there is a pairing or session stored and create proper Notification class*/
        //val pairingNotification = PostSettlementPairing.PairingNotification(id = generateId(), params = Pairing.NotificationParams(notification.type, notification.data))
        val sessionNotification =
            PostSettlementSession
                .SessionNotification(id = generateId(), params = Session.NotificationParams(notification.type, notification.data))
        val json = trySerialize(sessionNotification)
        val (sharedKey, selfPublic) = crypto.getKeyAgreement(Topic(topic))
        val encryptedMessage: String = codec.encrypt(json, sharedKey as SharedKey, selfPublic as PublicKey)

        //TODO handle peer reponses onSuccess(topic)
        relayRepository.publish(Topic(topic), encryptedMessage) { error -> onFailure(error) }
    }

    internal fun ping(topic: String, onSuccess: (String) -> Unit, onFailure: (Throwable) -> Unit) {
        require(::relayRepository.isInitialized)

        /*TODO check whether under given topic there is a pairing or session stored and create proper Ping class*/
        //val pairingParams = PostSettlementPairing.PairingPing(id = generateId(), params = Pairing.PingParams())
        val sessionPing = PostSettlementSession.SessionPing(id = generateId(), params = Session.PingParams())
        val json = trySerialize(sessionPing)
        val (sharedKey, selfPublic) = crypto.getKeyAgreement(Topic(topic))
        val encryptedMessage: String = codec.encrypt(json, sharedKey as SharedKey, selfPublic as PublicKey)

        //TODO handle peer responses, onSuccess = { onSuccess(topic) }
        relayRepository.publish(Topic(topic), encryptedMessage) { error -> onFailure(error) }
    }

    private fun onPairingPayload(decryptedMessage: String, sharedKey: SharedKey, selfPublic: PublicKey) {
        tryDeserialize<PostSettlementPairing.PairingPayload>(decryptedMessage)?.let { pairingPayload ->
            val proposal = pairingPayload.payloadParams
            //TODO validate session proposal
            crypto.setEncryptionKeys(sharedKey, selfPublic, proposal.topic)
            val sessionProposal = proposal.toSessionProposal()
            _sequenceEvent.value = SequenceLifecycle.OnSessionProposal(sessionProposal)
        } ?: throw NoSessionProposalException()
    }

    private fun onSessionPayload(decryptedMessage: String, topic: Topic) {
        tryDeserialize<PostSettlementSession.SessionPayload>(decryptedMessage)?.let { sessionPayload ->
            //TODO Validate session request + add unmarshaling of generic session request payload to the usable generic object
            val params = sessionPayload.sessionParams
            val chainId = sessionPayload.params.chainId
            val method = sessionPayload.params.request.method
            _sequenceEvent.value = SequenceLifecycle.OnSessionRequest(
                EngineData.SessionRequest(topic.value, chainId, EngineData.SessionRequest.JSONRPCRequest(sessionPayload.id, method, params))
            )
        } ?: throw NoSessionRequestPayloadException()
    }

    private fun onSessionDelete(decryptedMessage: String, topic: Topic) {
        tryDeserialize<PostSettlementSession.SessionDelete>(decryptedMessage)?.let { sessionDelete ->
            //TODO Add subscriptionId from local storage + Delete all data from local storage coupled with given session
            crypto.removeKeys(topic.value)
            relayRepository.unsubscribe(topic, SubscriptionId("1"))
            val reason = sessionDelete.message
            _sequenceEvent.value = SequenceLifecycle.OnSessionDeleted(EngineData.DeletedSession(topic.value, reason))
        } ?: throw NoSessionDeletePayloadException()
    }

    private fun onPairingDelete(decryptedMessage: String, topic: Topic) {
        //TODO Add subscriptionId from local storage + Delete all data from local storage coupled with given Pairing
        crypto.removeKeys(topic.value)
        relayRepository.unsubscribe(topic, SubscriptionId("1"))
    }

    private fun onSessionNotification(decryptedMessage: String, topic: Topic) {
        tryDeserialize<PostSettlementSession.SessionNotification>(decryptedMessage)?.let { sessionNotification ->
            val type = sessionNotification.params.type
            val data = sessionNotification.notificationParams
            _sequenceEvent.value =
                SequenceLifecycle.OnSessionNotification(EngineData.SessionNotification(topic.value, type, data))
        } ?: throw NoSessionNotificationPayloadException()
    }

    private fun onPing(topic: Topic, requestId: Long) {
        val jsonRpcResult = EngineData.JsonRpcResponse.JsonRpcResult(id = requestId, result = "true")
        val json = trySerialize(jsonRpcResult)
        val (sharedKey, selfPublic) = crypto.getKeyAgreement(topic)
        val encryptedMessage: String = codec.encrypt(json, sharedKey as SharedKey, selfPublic as PublicKey)

        //TODO DO the Peer Responses
        relayRepository.publish(topic, encryptedMessage) { error -> Logger.error("Ping Error: $error") }
    }

    private fun onUnsupported(rpc: String?) {
        Logger.error("Unsupported JsonRpc method: $rpc")
    }

    private fun settlePairingSequence(
        relay: JSONObject,
        selfPublicKey: PublicKey,
        peerPublicKey: PublicKey,
        controllerPublicKey: PublicKey,
        expiry: Expiry
    ): SettledPairingSequence {
        val (_, settledTopic) = crypto.generateTopicAndSharedKey(selfPublicKey, peerPublicKey)
        return SettledPairingSequence(
            settledTopic,
            relay,
            selfPublicKey,
            peerPublicKey,
            PairingPermissions(PairingParticipant(controllerPublicKey.keyAsHex)),
            expiry
        )
    }

    private fun settleSessionSequence(
        relay: RelayProtocolOptions,
        selfPublicKey: PublicKey,
        peerPublicKey: PublicKey,
        expiry: Expiry,
        sessionState: SessionState
    ): SettledSessionSequence {
        val (sharedKey, topic) = crypto.generateTopicAndSharedKey(selfPublicKey, peerPublicKey)
        return SettledSessionSequence(
            topic,
            relay,
            selfPublicKey,
            peerPublicKey,
            SettledSessionPermissions(Controller(selfPublicKey.keyAsHex)),
            sharedKey,
            expiry,
            sessionState
        )
    }

    class EngineFactory(
        val useTLs: Boolean = false,
        val hostName: String,
        val apiKey: String,
        val isController: Boolean,
        val application: Application,
        val metaData: AppMetaData
    )
}