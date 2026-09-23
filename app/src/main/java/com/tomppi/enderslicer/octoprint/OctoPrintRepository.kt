package com.tomppi.enderslicer.octoprint

import android.content.Context
import com.tomppi.enderslicer.engine.SliceArtifactPublisher
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class OctoPrintRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val store = OctoPrintSecretStore(appContext)
    private val refreshMutex = Mutex()
    private val commandMutex = Mutex()

    private var generation = 0L
    private var setupRequestId = 0L
    private var testRequestId = 0L
    private var authorizationLaunchNonce = 0L
    private var webcamRequestId = 0L

    private var sessionJob = SupervisorJob(scope.coroutineContext[Job])
    private var sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
    private var pollingJob: Job? = null
    private var authorizationJob: Job? = null
    private var webcamJob: Job? = null
    private var webcamVisible = false

    private var activeClient: OctoPrintClient? = null
    private var activeClientKey: String? = null
    private var cachedServerInfo: OctoPrintServerInfo? = null
    private var cachedWebcam: OctoPrintWebcamConfig? = null
    private var lastStaticRefreshMillis = 0L
    private var lastAutoConnectAttemptMillis = 0L
    private var lastManualDisconnectMillis = 0L

    private val initialConfig = store.loadConfig()
    private val initialApiKey = store.loadApiKey()
    @Volatile
    private var cachedApiKey: String? = (initialApiKey as? ApiKeyLoad.Loaded)?.apiKey
    private val _state = MutableStateFlow(
        OctoPrintUiState(
            config = initialConfig,
            hasApiKey = initialApiKey is ApiKeyLoad.Loaded,
            statusMessage = when {
                // A stored credential that cannot be decrypted is said out loud
                // rather than treated as absent: the difference is a user who
                // knows to re-enter a key and one who re-approves the app.
                initialApiKey is ApiKeyLoad.Unreadable ->
                    "The saved OctoPrint API key could not be read; enter it again to reconnect"
                initialConfig.isConfigured && initialApiKey is ApiKeyLoad.Loaded ->
                    "Connecting to OctoPrint…"
                else -> "Configure OctoPrint to begin"
            },
        ),
    )
    val state: StateFlow<OctoPrintUiState> = _state.asStateFlow()

    init {
        if (_state.value.isReady) restartPolling(immediate = true)
    }

    fun testConnection(baseUrl: String, apiKey: String?) {
        val requestId = ++testRequestId
        val requestGeneration = generation
        scope.launch {
            setStatus("Testing OctoPrint connection…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val client = OctoPrintClient(baseUrl, apiKey?.trim()?.takeIf(String::isNotBlank))
                    try {
                        val version = client.version()
                        val user = if (apiKey.isNullOrBlank()) null else client.currentUser()
                        OctoPrintJson.parseServerInfo(version, user)
                    } finally {
                        client.cancelActiveRequests()
                    }
                }
            }.onSuccess { info ->
                if (requestId != testRequestId || requestGeneration != generation) return@onSuccess
                _state.update {
                    it.copy(
                        statusMessage = "Connected to ${info.displayText ?: info.serverVersion ?: "OctoPrint"}",
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                if (requestId == testRequestId && requestGeneration == generation && error !is CancellationException) {
                    setError(error)
                }
            }
        }
    }

    fun saveManualConfiguration(
        baseUrl: String,
        username: String,
        apiKey: String,
        snapshotUrlOverride: String,
        pollIntervalSeconds: Int,
    ) {
        val requestId = ++setupRequestId
        authorizationJob?.cancel()
        authorizationJob = null
        scope.launch {
            setStatus("Validating OctoPrint credentials…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val normalized = OctoPrintClient.normalizeBaseUrl(baseUrl).toString().removeSuffix("/")
                    val cleanKey = apiKey.trim()
                    require(cleanKey.isNotBlank()) { "Enter an OctoPrint API key" }
                    val config = OctoPrintConfig(
                        baseUrl = normalized,
                        username = username.trim(),
                        snapshotUrlOverride = snapshotUrlOverride.trim(),
                        pollIntervalSeconds = pollIntervalSeconds.coerceIn(1, 30),
                    )
                    val client = OctoPrintClient(config.baseUrl, cleanKey)
                    try {
                        val info = OctoPrintJson.parseServerInfo(client.version(), client.currentUser())
                        Triple(config, cleanKey, info)
                    } finally {
                        client.cancelActiveRequests()
                    }
                }
            }.onSuccess { (config, key, info) ->
                if (requestId != setupRequestId) return@onSuccess
                activateConfiguration(config, key, info)
            }.onFailure { error ->
                if (requestId == setupRequestId && error !is CancellationException) setError(error)
            }
        }
    }

    fun beginApplicationAuthorization(
        baseUrl: String,
        username: String,
        snapshotUrlOverride: String,
        pollIntervalSeconds: Int,
    ) {
        val requestId = ++setupRequestId
        authorizationJob?.cancel()
        authorizationJob = scope.launch {
            setStatus("Requesting OctoPrint authorization…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val normalized = OctoPrintClient.normalizeBaseUrl(baseUrl).toString().removeSuffix("/")
                    val config = OctoPrintConfig(
                        baseUrl = normalized,
                        username = username.trim(),
                        snapshotUrlOverride = snapshotUrlOverride.trim(),
                        pollIntervalSeconds = pollIntervalSeconds.coerceIn(1, 30),
                    )
                    val client = OctoPrintClient(config.baseUrl)
                    try {
                        require(client.probeApplicationKeys()) {
                            "This OctoPrint server does not expose the Application Keys workflow; use a user API key instead"
                        }
                        config to client.requestApplicationKey(config.username.takeIf(String::isNotBlank))
                    } finally {
                        client.cancelActiveRequests()
                    }
                }
            }.onSuccess { (config, authorization) ->
                if (requestId != setupRequestId) return@onSuccess
                authorizationLaunchNonce++
                _state.update {
                    it.copy(
                        authorizationPending = true,
                        authorizationDialogUrl = authorization.dialogUrl,
                        authorizationDialogLaunchNonce = authorizationLaunchNonce,
                        statusMessage = "Approve enderslicercura in the OctoPrint authorization page",
                        errorMessage = null,
                    )
                }
                pollAuthorization(config, authorization.pollingUrl, requestId)
            }.onFailure { error ->
                if (requestId == setupRequestId && error !is CancellationException) setError(error)
            }
        }
    }

    fun reopenAuthorizationDialog() {
        val current = _state.value
        if (!current.authorizationPending || current.authorizationDialogUrl.isNullOrBlank()) return
        authorizationLaunchNonce++
        _state.update { it.copy(authorizationDialogLaunchNonce = authorizationLaunchNonce) }
    }

    fun cancelAuthorization() {
        setupRequestId++
        authorizationJob?.cancel()
        authorizationJob = null
        _state.update {
            it.copy(
                authorizationPending = false,
                authorizationDialogUrl = null,
                authorizationDialogLaunchNonce = 0L,
                statusMessage = if (it.isReady) {
                    "OctoPrint authorization cancelled; existing configuration kept"
                } else {
                    "OctoPrint authorization cancelled"
                },
                errorMessage = null,
            )
        }
    }

    fun clearConfiguration() {
        setupRequestId++
        testRequestId++
        authorizationJob?.cancel()
        authorizationJob = null
        resetSession()
        store.clearAll()
        cachedApiKey = null
        _state.value = OctoPrintUiState(statusMessage = "OctoPrint configuration removed")
    }

    fun refresh() {
        sessionScope.launch { refreshAll(showSpinner = true, forceStatic = true) }
    }

    fun refreshFiles(force: Boolean = true) {
        val requestGeneration = generation
        sessionScope.launch {
            _state.update { it.copy(isFileListRefreshing = true, errorMessage = null) }
            runCatching {
                withContext(Dispatchers.IO) { requireClient().files(force) }
            }.onSuccess { (files, freeBytes) ->
                if (!isCurrent(requestGeneration)) return@onSuccess
                _state.update {
                    it.copy(
                        files = files,
                        freeBytes = freeBytes,
                        isFileListRefreshing = false,
                        statusMessage = "Loaded ${files.size} OctoPrint file entries",
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                if (!isCurrent(requestGeneration) || error is CancellationException) return@onFailure
                _state.update { it.copy(isFileListRefreshing = false) }
                handleSessionError(error, requestGeneration, forbiddenMeansInvalidKey = false)
            }
        }
    }

    fun uploadGcode(
        path: String?,
        suggestedName: String,
        remoteDirectory: String,
        action: OctoPrintUploadAction,
    ) {
        val source = path?.let(::File)
        if (source == null || !SliceArtifactPublisher.isCompleteGcode(source)) {
            setError(IllegalStateException("Slice the model before sending G-code to OctoPrint"))
            return
        }
        if (_state.value.isUploading) {
            setError(IllegalStateException("A G-code upload is already in progress"))
            return
        }
        if (action == OctoPrintUploadAction.UPLOAD_AND_PRINT && _state.value.hasActiveJob) {
            setError(IllegalStateException("Finish or cancel the active OctoPrint job before uploading and printing"))
            return
        }
        val cleanDirectory = runCatching {
            OctoPrintClient.normalizeRemotePath(remoteDirectory, allowBlank = true)
        }.getOrElse {
            setError(it)
            return
        }
        val remoteName = sanitizeGcodeName(suggestedName.ifBlank { source.name })
        val requestGeneration = generation
        val sourceLease = runCatching {
            SliceArtifactPublisher.acquireLease(source)
        }.getOrElse { error ->
            setError(error)
            return
        }
        _state.update {
            it.copy(
                isUploading = true,
                uploadProgress = 0f,
                uploadFileName = remoteName,
                statusMessage = "Uploading $remoteName to OctoPrint…",
                errorMessage = null,
            )
        }
        val uploadJob = sessionScope.launch {
            runCatching {
                val uploadSource = withContext(Dispatchers.IO) {
                    snapshotGcodeForUpload(source)
                }
                try {
                    val onProgress: (Long, Long) -> Unit = { sent, total ->
                        if (isCurrent(requestGeneration)) {
                            val progress = if (total <= 0L) {
                                null
                            } else {
                                (sent.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                            }
                            _state.update { current -> current.copy(uploadProgress = progress) }
                        }
                    }
                    if (action == OctoPrintUploadAction.UPLOAD_AND_PRINT) {
                        commandMutex.withLock {
                            if (!isCurrent(requestGeneration)) {
                                throw CancellationException("OctoPrint configuration changed before upload")
                            }
                            val client = requireClient()
                            val freshState = freshSafetyState(client, requestGeneration)
                            noActiveJobGuard("uploading and starting a print")(freshState)?.let { reason ->
                                throw IllegalStateException(reason)
                            }
                            if (!isCurrent(requestGeneration)) {
                                throw CancellationException("OctoPrint configuration changed before print upload")
                            }
                            withContext(Dispatchers.IO) {
                                client.upload(uploadSource, remoteName, cleanDirectory, action, onProgress)
                            }
                        }
                    } else {
                        val client = requireClient()
                        withContext(Dispatchers.IO) {
                            client.upload(uploadSource, remoteName, cleanDirectory, action, onProgress)
                        }
                    }
                } finally {
                    uploadSource.delete()
                }
            }.onSuccess { response ->
                if (!isCurrent(requestGeneration)) return@onSuccess
                val effectivePrint = response.optBoolean("effectivePrint", false)
                val effectiveSelect = response.optBoolean("effectiveSelect", false)
                val warning = when {
                    action == OctoPrintUploadAction.UPLOAD_AND_PRINT && !effectivePrint ->
                        "$remoteName was uploaded, but OctoPrint did not start printing. Check printer state and select the file manually."
                    action == OctoPrintUploadAction.UPLOAD_AND_SELECT && !effectiveSelect ->
                        "$remoteName was uploaded, but OctoPrint did not select it."
                    else -> null
                }
                _state.update {
                    it.copy(
                        isUploading = false,
                        uploadProgress = 1f,
                        statusMessage = warning ?: when {
                            effectivePrint -> "$remoteName uploaded and printing started"
                            effectiveSelect -> "$remoteName uploaded and selected"
                            else -> "$remoteName uploaded to OctoPrint"
                        },
                        errorMessage = warning,
                    )
                }
                refreshFiles(force = true)
                refreshAll(showSpinner = false, forceStatic = false)
            }.onFailure { error ->
                if (!isCurrent(requestGeneration) || error is CancellationException) return@onFailure
                _state.update { it.copy(isUploading = false, uploadProgress = null) }
                handleSessionError(error, requestGeneration, forbiddenMeansInvalidKey = false)
            }
        }
        uploadJob.invokeOnCompletion { sourceLease.close() }
    }

    fun selectFile(path: String, print: Boolean) = operation(
        message = if (print) "Starting OctoPrint file…" else "Selecting OctoPrint file…",
        guard = if (print) noActiveJobGuard("starting another print") else null,
    ) { selectFile(path, print) }

    fun deleteFile(path: String) = operation(
        message = "Deleting OctoPrint file…",
        refreshFileList = true,
        guard = activeFileGuard("deleted", path),
        optimisticFiles = { files -> filesWithoutDeleted(files, path) },
        onHttpFailure = { client, error ->
            error is OctoPrintClient.OctoPrintHttpException &&
                error.statusCode in 500..599 &&
                fileTreeIsGone(client, path)
        },
    ) { deleteFile(path) }

    fun createFolder(parentPath: String, name: String) = operation(
        message = "Creating OctoPrint folder…",
        refreshFileList = true,
    ) { createFolder(parentPath, name) }

    fun moveFile(path: String, destination: String) = operation(
        message = "Moving OctoPrint file…",
        refreshFileList = true,
        guard = activeFileGuard("moved", path),
        optimisticFiles = { files -> filesWithMoved(files, path, destination) },
    ) { moveFile(path, destination) }

    fun copyFile(path: String, destination: String) = operation(
        message = "Copying OctoPrint file…",
        refreshFileList = true,
        guard = activeFileGuard("copied", path),
        optimisticFiles = { files -> filesWithCopied(files, path, destination) },
    ) { copyFile(path, destination) }

    fun startJob() = operation(
        message = "Starting print…",
        guard = noActiveJobGuard("starting a print"),
    ) { jobCommand("start") }

    fun pauseJob() = operation(
        message = "Pausing print…",
        guard = { if (!it.isPrinting) "No active print is available to pause" else null },
    ) { jobCommand("pause", "pause") }

    fun resumeJob() = operation(
        message = "Resuming print…",
        guard = { if (!it.isPaused) "No paused print is available to resume" else null },
    ) { jobCommand("pause", "resume") }

    fun cancelJob() = operation(
        message = "Cancelling print…",
        guard = { if (!it.hasActiveJob) "No active print is available to cancel" else null },
    ) { jobCommand("cancel") }

    fun restartJob() = operation(
        message = "Restarting print…",
        guard = { if (!it.isPaused) "Restart is only available for a paused print" else null },
    ) { jobCommand("restart") }

    fun connect(
        port: String?,
        baudrate: Int?,
        printerProfile: String?,
        save: Boolean,
        autoConnect: Boolean,
    ) = operation(
        message = "Connecting OctoPrint to the printer…",
        guard = noActiveJobGuard("reconnecting the printer"),
    ) { connect(port, baudrate, printerProfile, save, autoConnect) }

    fun disconnect() {
        lastManualDisconnectMillis = System.currentTimeMillis()
        operation(
            message = "Disconnecting printer…",
            guard = noActiveJobGuard("disconnecting the printer"),
        ) { disconnect() }
    }

    fun jog(x: Double? = null, y: Double? = null, z: Double? = null) = operation(
        message = "Jogging printer…",
        guard = idlePrinterGuard("Jogging"),
    ) { jog(x, y, z) }

    fun home(axes: Set<String>) = operation(
        message = "Homing ${axes.joinToString("").uppercase(Locale.US)}…",
        guard = idlePrinterGuard("Homing"),
    ) { home(axes) }

    fun setToolTemperature(tool: String, target: Int) = operation(
        message = "Setting nozzle target…",
        guard = operationalGuard("Nozzle temperature control"),
    ) { setToolTemperature(tool, target) }

    fun setBedTemperature(target: Int) = operation(
        message = "Setting bed target…",
        guard = operationalGuard("Bed temperature control"),
    ) { setBedTemperature(target) }

    fun extrude(amountMm: Double) = operation(
        message = if (amountMm > 0) "Extruding filament…" else "Retracting filament…",
        guard = idlePrinterGuard(if (amountMm > 0) "Extrusion" else "Retraction"),
    ) { extrude(amountMm) }

    fun setFeedRate(percent: Int) = operation(
        message = "Setting feed rate…",
        guard = operationalGuard("Feed-rate control"),
    ) { setFeedRate(percent) }

    fun setFlowRate(percent: Int) = operation(
        message = "Setting flow rate…",
        guard = operationalGuard("Flow-rate control"),
    ) { setFlowRate(percent) }

    fun sendGcode(command: String) = operation(
        message = "Sending G-code command…",
        guard = idlePrinterGuard("Terminal commands"),
    ) { sendGcode(command) }

    fun setWebcamVisible(visible: Boolean) {
        webcamVisible = visible
        webcamRequestId++
        if (visible) {
            restartWebcamPolling()
        } else {
            webcamJob?.cancel()
            webcamJob = null
            _state.update { it.copy(webcamFrame = null, webcamError = null) }
        }
    }

    fun saveSnapshotOverride(value: String) {
        val config = _state.value.config.copy(snapshotUrlOverride = value.trim())
        store.saveConfig(config)
        webcamRequestId++
        _state.update { it.copy(config = config, webcamFrame = null, webcamError = null) }
        if (webcamVisible) restartWebcamPolling()
    }

    private suspend fun pollAuthorization(config: OctoPrintConfig, pollingUrl: String, requestId: Long) {
        runCatching {
            withTimeout(AUTHORIZATION_TIMEOUT_MILLIS) {
                val client = OctoPrintClient(config.baseUrl)
                try {
                    while (isActive && requestId == setupRequestId) {
                        when (val result = withContext(Dispatchers.IO) { client.pollApplicationKey(pollingUrl) }) {
                            OctoPrintClient.AppKeyPollResult.Pending -> delay(1_000L)
                            OctoPrintClient.AppKeyPollResult.Denied -> error("OctoPrint authorization was denied or expired")
                            is OctoPrintClient.AppKeyPollResult.Granted -> {
                                val authorizedClient = OctoPrintClient(config.baseUrl, result.apiKey)
                                try {
                                    val info = withContext(Dispatchers.IO) {
                                        OctoPrintJson.parseServerInfo(
                                            authorizedClient.version(),
                                            authorizedClient.currentUser(),
                                        )
                                    }
                                    return@withTimeout result.apiKey to info
                                } finally {
                                    authorizedClient.cancelActiveRequests()
                                }
                            }
                        }
                    }
                    error("OctoPrint authorization cancelled")
                } finally {
                    client.cancelActiveRequests()
                }
            }
        }.onSuccess { (key, info) ->
            if (requestId != setupRequestId) return@onSuccess
            authorizationJob = null
            activateConfiguration(config, key, info)
        }.onFailure { error ->
            if (requestId != setupRequestId) return@onFailure
            if (error is CancellationException && error !is TimeoutCancellationException) return@onFailure
            authorizationJob = null
            _state.update {
                it.copy(
                    authorizationPending = false,
                    authorizationDialogUrl = null,
                    authorizationDialogLaunchNonce = 0L,
                )
            }
            setError(error)
        }
    }

    private fun activateConfiguration(config: OctoPrintConfig, key: String, info: OctoPrintServerInfo) {
        resetSession()
        store.saveConfiguration(config, key)
        cachedApiKey = key
        cachedServerInfo = info
        lastStaticRefreshMillis = 0L
        _state.value = OctoPrintUiState(
            config = config,
            hasApiKey = true,
            serverInfo = info,
            statusMessage = "OctoPrint configured",
        )
        restartPolling(immediate = true)
    }

    private fun resetSession() {
        generation++
        pollingJob?.cancel()
        webcamJob?.cancel()
        activeClient?.cancelActiveRequests()
        sessionJob.cancel()
        sessionJob = SupervisorJob(scope.coroutineContext[Job])
        sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
        pollingJob = null
        webcamJob = null
        activeClient = null
        activeClientKey = null
        cachedServerInfo = null
        cachedWebcam = null
        lastStaticRefreshMillis = 0L
        lastAutoConnectAttemptMillis = 0L
        lastManualDisconnectMillis = 0L
        webcamRequestId++
    }

    private fun restartPolling(immediate: Boolean) {
        pollingJob?.cancel()
        if (!_state.value.isReady) return
        val requestGeneration = generation
        pollingJob = sessionScope.launch {
            if (!immediate) delay(1_000L)
            while (isActive && isCurrent(requestGeneration) && _state.value.isReady) {
                refreshAll(showSpinner = false, forceStatic = false)
                maybeAutoConnect()
                val seconds = if (_state.value.hasActiveJob) {
                    ACTIVE_PRINT_POLL_SECONDS
                } else {
                    _state.value.config.pollIntervalSeconds.coerceIn(1, 30)
                }
                delay(seconds * 1_000L)
            }
        }
        if (webcamVisible) restartWebcamPolling()
    }

    private fun maybeAutoConnect() {
        val current = _state.value
        if (!current.isReady) return
        if (!current.connection.autoConnect) return
        if (!current.printer.closedOrError) return
        val now = System.currentTimeMillis()
        if (now - lastAutoConnectAttemptMillis < AUTO_CONNECT_RETRY_MILLIS) return
        if (now - lastManualDisconnectMillis < AUTO_CONNECT_DISCONNECT_GRACE_MILLIS) return
        lastAutoConnectAttemptMillis = now
        connect(null, null, null, save = false, autoConnect = current.connection.autoConnect)
    }

    private suspend fun refreshAll(showSpinner: Boolean, forceStatic: Boolean) {
        if (!_state.value.isReady) return
        val requestGeneration = generation
        refreshMutex.withLock {
            if (!isCurrent(requestGeneration)) return@withLock
            if (showSpinner) _state.update { it.copy(isRefreshing = true, errorMessage = null) }
            runCatching {
                coroutineScope {
                    val client = requireClient()
                    val now = System.currentTimeMillis()
                    val refreshStatic = forceStatic ||
                        cachedServerInfo == null ||
                        now - lastStaticRefreshMillis >= STATIC_REFRESH_INTERVAL_MILLIS
                    val static = if (refreshStatic) {
                        async(Dispatchers.IO) {
                            val server = OctoPrintJson.parseServerInfo(client.version(), client.currentUser())
                            val webcam = runCatching { client.webcamSettings() }
                                .getOrDefault(cachedWebcam ?: _state.value.webcam)
                            server to webcam
                        }
                    } else {
                        null
                    }
                    val job = async(Dispatchers.IO) { client.jobState() }
                    val connection = async(Dispatchers.IO) { client.connectionState() }
                    val printer = async(Dispatchers.IO) {
                        try {
                            client.printerState()
                        } catch (error: OctoPrintClient.OctoPrintHttpException) {
                            if (error.statusCode != 409) throw error
                            OctoPrintPrinterState(text = connection.await().state)
                        }
                    }
                    val staticResult = static?.await()
                    RefreshSnapshot(
                        server = staticResult?.first ?: cachedServerInfo ?: _state.value.serverInfo,
                        job = job.await(),
                        connection = connection.await(),
                        printer = printer.await(),
                        webcam = staticResult?.second ?: cachedWebcam ?: _state.value.webcam,
                        refreshedStatic = staticResult != null,
                    )
                }
            }.onSuccess { snapshot ->
                if (!isCurrent(requestGeneration)) return@onSuccess
                if (snapshot.refreshedStatic) {
                    cachedServerInfo = snapshot.server
                    cachedWebcam = snapshot.webcam
                    lastStaticRefreshMillis = System.currentTimeMillis()
                }
                _state.update {
                    it.copy(
                        serverInfo = snapshot.server,
                        job = snapshot.job,
                        connection = snapshot.connection,
                        printer = snapshot.printer,
                        webcam = snapshot.webcam,
                        isRefreshing = false,
                        lastUpdatedEpochMillis = System.currentTimeMillis(),
                        statusMessage = snapshot.job.error
                            ?: snapshot.printer.text.takeIf(String::isNotBlank)
                            ?: snapshot.connection.state,
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                if (!isCurrent(requestGeneration) || error is CancellationException) return@onFailure
                _state.update { it.copy(isRefreshing = false) }
                handleSessionError(error, requestGeneration, forbiddenMeansInvalidKey = true)
            }
        }
    }

    private fun restartWebcamPolling() {
        webcamJob?.cancel()
        if (!webcamVisible || !_state.value.isReady) return
        val requestGeneration = generation
        val requestId = ++webcamRequestId
        webcamJob = sessionScope.launch {
            while (isActive && webcamVisible && isCurrent(requestGeneration) && requestId == webcamRequestId) {
                val snapshotUrl = _state.value.config.snapshotUrlOverride.takeIf(String::isNotBlank)
                    ?: _state.value.webcam.snapshotUrl
                if (snapshotUrl.isNullOrBlank()) {
                    delay(3_000L)
                    continue
                }
                runCatching {
                    withContext(Dispatchers.IO) { requireClient().fetchWebcamSnapshot(snapshotUrl) }
                }.onSuccess { bytes ->
                    if (isCurrent(requestGeneration) && requestId == webcamRequestId) {
                        _state.update { it.copy(webcamFrame = bytes, webcamError = null) }
                    }
                }.onFailure { error ->
                    if (error is OctoPrintClient.OctoPrintHttpException && error.statusCode == 401) {
                        // The webcam snapshot is a separate resource (typically
                        // mjpg-streamer or a reverse-proxied /webcam/ path) that may
                        // be password-protected or misconfigured. Its 401 does not
                        // mean the OctoPrint API key is invalid, so never invalidate
                        // the stored configuration here.
                        if (isCurrent(requestGeneration) && requestId == webcamRequestId) {
                            _state.update {
                                it.copy(webcamError = "Webcam snapshot rejected (HTTP 401); check the webcam URL and credentials")
                            }
                        }
                    } else if (isCurrent(requestGeneration) && requestId == webcamRequestId) {
                        _state.update { it.copy(webcamError = "Webcam snapshot failed: ${error.message ?: error::class.java.simpleName}") }
                    }
                }
                delay(WEBCAM_POLL_MILLIS)
            }
        }
    }

    private fun operation(
        message: String,
        refreshFileList: Boolean = false,
        guard: ((OctoPrintUiState) -> String?)? = null,
        optimisticFiles: ((List<OctoPrintFileEntry>) -> List<OctoPrintFileEntry>)? = null,
        onHttpFailure: (suspend (OctoPrintClient, Throwable) -> Boolean)? = null,
        block: OctoPrintClient.() -> Unit,
    ) {
        val requestGeneration = generation
        sessionScope.launch {
            commandMutex.withLock {
                if (!isCurrent(requestGeneration)) return@withLock
                val client = runCatching { requireClient() }.getOrElse { error ->
                    if (error !is CancellationException) {
                        handleSessionError(error, requestGeneration, forbiddenMeansInvalidKey = false)
                    }
                    return@withLock
                }
                val guardState = if (guard == null) {
                    _state.value
                } else {
                    runCatching { freshSafetyState(client, requestGeneration) }.getOrElse { error ->
                        if (error !is CancellationException) {
                            handleSessionError(error, requestGeneration, forbiddenMeansInvalidKey = false)
                        }
                        return@withLock
                    }
                }
                if (!isCurrent(requestGeneration)) return@withLock
                guard?.invoke(guardState)?.let { reason ->
                    setError(IllegalStateException(reason))
                    return@withLock
                }
                setStatus(message)
                runCatching {
                    withContext(Dispatchers.IO) {
                        if (!isCurrent(requestGeneration)) {
                            throw CancellationException("OctoPrint configuration changed before command execution")
                        }
                        client.block()
                    }
                }.onSuccess {
                    if (!isCurrent(requestGeneration)) return@onSuccess
                    applyOperationSuccess(optimisticFiles, refreshFileList)
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    val reconciled = onHttpFailure?.let { recover ->
                        try {
                            recover(client, error)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (verifyError: Throwable) {
                            false
                        }
                    } ?: false
                    if (reconciled) {
                        if (!isCurrent(requestGeneration)) return@onFailure
                        applyOperationSuccess(optimisticFiles, refreshFileList)
                    } else {
                        handleSessionError(error, requestGeneration, forbiddenMeansInvalidKey = false)
                    }
                }
            }
        }
    }

    private suspend fun applyOperationSuccess(
        optimisticFiles: ((List<OctoPrintFileEntry>) -> List<OctoPrintFileEntry>)?,
        refreshFileList: Boolean,
    ) {
        optimisticFiles?.let { transform ->
            _state.update { current -> current.copy(files = transform(current.files)) }
        }
        if (refreshFileList) refreshFiles(force = true)
        refreshAll(showSpinner = false, forceStatic = false)
    }

    /**
     * Fetches the minimum current server state immediately before a guarded
     * command. This deliberately does not call refreshAll while commandMutex is
     * held, avoiding refresh/command lock recursion while still failing closed.
     */
    private suspend fun freshSafetyState(
        client: OctoPrintClient,
        requestGeneration: Long,
    ): OctoPrintUiState = coroutineScope {
        val job = async(Dispatchers.IO) { client.jobState() }
        val connection = async(Dispatchers.IO) { client.connectionState() }
        val printer = async(Dispatchers.IO) {
            try {
                client.printerState()
            } catch (error: OctoPrintClient.OctoPrintHttpException) {
                if (error.statusCode != 409) throw error
                OctoPrintPrinterState(text = connection.await().state)
            }
        }
        val freshJob = job.await()
        val freshConnection = connection.await()
        val freshPrinter = printer.await()
        if (!isCurrent(requestGeneration)) {
            throw CancellationException("OctoPrint configuration changed during safety preflight")
        }
        val refreshedAt = System.currentTimeMillis()
        val merged = OctoPrintSafetyPreflight.merge(
            cached = _state.value,
            job = freshJob,
            connection = freshConnection,
            printer = freshPrinter,
            refreshedAtEpochMillis = refreshedAt,
        )
        _state.update { current ->
            if (isCurrent(requestGeneration)) {
                OctoPrintSafetyPreflight.merge(
                    cached = current,
                    job = freshJob,
                    connection = freshConnection,
                    printer = freshPrinter,
                    refreshedAtEpochMillis = refreshedAt,
                )
            } else {
                current
            }
        }
        merged
    }

    private fun idlePrinterGuard(action: String): (OctoPrintUiState) -> String? = { current ->
        when {
            !current.isReady -> "Configure and connect OctoPrint before $action"
            !current.printer.operational || current.hasActiveJob ->
                "$action is only available while the printer is operational and idle"
            else -> null
        }
    }

    private fun operationalGuard(action: String): (OctoPrintUiState) -> String? = { current ->
        when {
            !current.isReady -> "Configure and connect OctoPrint before $action"
            !current.printer.operational -> "$action requires an operational printer connection"
            else -> null
        }
    }

    private fun noActiveJobGuard(action: String): (OctoPrintUiState) -> String? = { current ->
        when {
            !current.isReady -> "Configure OctoPrint before $action"
            current.hasActiveJob -> "Finish or cancel the active OctoPrint job before $action"
            else -> null
        }
    }

    private fun activeFileGuard(action: String, targetPath: String): (OctoPrintUiState) -> String? = { current ->
        val activePath = current.job.filePath
        if (activePath != null && targetPath.isNotBlank() && normalizeForCompare(activePath) == normalizeForCompare(targetPath)) {
            "The file currently being printed ($activePath) cannot be $action"
        } else {
            null
        }
    }

    private fun normalizeForCompare(value: String): String = value
        .trim()
        .trim('/')
        .lowercase()
        .split('/')
        .filter { it.isNotBlank() && it != "." }
        .joinToString("/")

    private fun filesWithoutDeleted(files: List<OctoPrintFileEntry>, path: String): List<OctoPrintFileEntry> {
        val target = normalizeForCompare(path)
        return files.filterNot { entry ->
            val normalized = normalizeForCompare(entry.path)
            normalized == target || normalized.startsWith("$target/")
        }
    }

    private fun filesWithMoved(files: List<OctoPrintFileEntry>, path: String, destination: String): List<OctoPrintFileEntry> {
        val leaf = path.substringAfterLast('/')
        val cleanDestination = OctoPrintClient.normalizeRemotePath(destination, allowBlank = true)
        val newRoot = if (cleanDestination.isBlank()) leaf else "$cleanDestination/$leaf"
        return files.map { entry ->
            when {
                entry.path == path -> entry.copy(path = newRoot)
                entry.path.startsWith("$path/") -> entry.copy(path = newRoot + entry.path.removePrefix(path))
                else -> entry
            }
        }
    }

    private fun filesWithCopied(files: List<OctoPrintFileEntry>, path: String, destination: String): List<OctoPrintFileEntry> {
        val source = files.firstOrNull { it.path == path } ?: return files
        val leaf = path.substringAfterLast('/')
        val cleanDestination = OctoPrintClient.normalizeRemotePath(destination, allowBlank = true)
        val newPath = if (cleanDestination.isBlank()) leaf else "$cleanDestination/$leaf"
        if (files.any { it.path == newPath }) return files
        return files + source.copy(path = newPath)
    }

    private suspend fun fileTreeIsGone(client: OctoPrintClient, path: String): Boolean {
        val files = withContext(Dispatchers.IO) { client.files(force = true) }.first
        val target = normalizeForCompare(path)
        return files.none { entry ->
            val normalized = normalizeForCompare(entry.path)
            normalized == target || normalized.startsWith("$target/")
        }
    }

    private fun requireClient(): OctoPrintClient {
        val current = _state.value
        require(current.config.isConfigured) { "Configure the OctoPrint server first" }
        val key = cachedApiKey
        if (key == null) {
            error("OctoPrint API key is missing or unreadable; authorize the app again")
        }
        val cached = activeClient
        if (cached != null && activeClientKey == key) return cached
        cached?.cancelActiveRequests()
        return OctoPrintClient(current.config.baseUrl, key).also {
            activeClient = it
            activeClientKey = key
        }
    }

    private fun handleSessionError(
        error: Throwable,
        requestGeneration: Long,
        forbiddenMeansInvalidKey: Boolean,
    ) {
        if (!isCurrent(requestGeneration) || error is CancellationException) return
        val http = error as? OctoPrintClient.OctoPrintHttpException
        // A 403 is only proof when the client could see the documented rejection
        // (a same-origin API response whose JSON error names the key). The old
        // check searched the peer's message text, so a proxy or a captive portal
        // could word a 403 well enough to make the app delete a valid key.
        val forbiddenIsInvalidKey = forbiddenMeansInvalidKey && http?.apiKeyRejected == true
        if (http?.statusCode == 401 || forbiddenIsInvalidKey) {
            invalidateAuthorization(error.message ?: "OctoPrint authorization is no longer valid")
        } else {
            setError(error)
        }
    }

    private fun invalidateAuthorization(message: String) {
        val current = _state.value
        val config = current.config
        val pending = current.authorizationPending
        val dialogUrl = current.authorizationDialogUrl
        val dialogNonce = current.authorizationDialogLaunchNonce
        resetSession()
        store.clearApiKey()
        cachedApiKey = null
        _state.value = OctoPrintUiState(
            config = config,
            hasApiKey = false,
            authorizationPending = pending,
            authorizationDialogUrl = dialogUrl,
            authorizationDialogLaunchNonce = dialogNonce,
            statusMessage = "$message. Authorize OctoPrint again.",
            errorMessage = "$message. Authorize OctoPrint again.",
        )
    }

    private fun isCurrent(requestGeneration: Long): Boolean = requestGeneration == generation

    private fun setStatus(message: String) {
        _state.update { it.copy(statusMessage = message, errorMessage = null) }
    }

    private fun setError(error: Throwable) {
        val message = error.message ?: error::class.java.simpleName
        _state.update { it.copy(statusMessage = message, errorMessage = message) }
    }

    private fun snapshotGcodeForUpload(source: File): File {
        require(source.isFile && source.length() > 0L) { "The validated G-code is unavailable; slice again" }
        val beforeLength = source.length()
        val beforeModified = source.lastModified()
        val directory = File(appContext.cacheDir, "octoprint-uploads").apply { mkdirs() }
        directory.listFiles()?.forEach { candidate ->
            if (candidate.isFile && System.currentTimeMillis() - candidate.lastModified() > UPLOAD_SNAPSHOT_MAX_AGE_MILLIS) {
                candidate.delete()
            }
        }
        val snapshot = File.createTempFile("validated-", ".gcode", directory)
        try {
            source.inputStream().buffered().use { input ->
                snapshot.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(
                source.isFile &&
                    source.length() == beforeLength &&
                    source.lastModified() == beforeModified &&
                    snapshot.length() == beforeLength,
            ) { "The G-code changed while preparing the upload; try again" }
            return snapshot
        } catch (error: Throwable) {
            snapshot.delete()
            throw error
        }
    }

    private fun sanitizeGcodeName(value: String): String {
        val leafName = value.substringAfterLast('/').substringAfterLast('\\')
        val stem = leafName
            .substringBeforeLast('.', leafName)
            .replace(Regex("[^A-Za-z0-9._ -]+"), "_")
            .trim(' ', '.', '_')
            .take(96)
            .ifBlank { "enderslicercura-${System.currentTimeMillis()}" }
        return "$stem.gcode"
    }

    private data class RefreshSnapshot(
        val server: OctoPrintServerInfo,
        val job: OctoPrintJobState,
        val connection: OctoPrintConnectionState,
        val printer: OctoPrintPrinterState,
        val webcam: OctoPrintWebcamConfig,
        val refreshedStatic: Boolean,
    )

    private companion object {
        const val ACTIVE_PRINT_POLL_SECONDS = 2
        const val AUTHORIZATION_TIMEOUT_MILLIS = 5L * 60L * 1_000L
        const val UPLOAD_SNAPSHOT_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        const val STATIC_REFRESH_INTERVAL_MILLIS = 5L * 60L * 1_000L
        const val WEBCAM_POLL_MILLIS = 1_500L
        const val AUTO_CONNECT_RETRY_MILLIS = 30_000L
        const val AUTO_CONNECT_DISCONNECT_GRACE_MILLIS = 60_000L
    }
}
