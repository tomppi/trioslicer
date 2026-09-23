package com.tomppi.enderslicer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomppi.enderslicer.BuildConfig
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.octoprint.OctoPrintViewModel
import com.tomppi.enderslicer.smartinfill.FilaSimBoundaryCondition
import com.tomppi.enderslicer.smartinfill.FilaSimEngine
import com.tomppi.enderslicer.smartinfill.SmartInfillActivity
import com.tomppi.enderslicer.smartinfill.SmartInfillController
import com.tomppi.enderslicer.smartinfill.SmartInfillNativeExport
import com.tomppi.enderslicer.smartinfill.SmartInfillOverlay
import com.tomppi.enderslicer.smartinfill.SmartInfillPackage
import com.tomppi.enderslicer.smartinfill.SmartInfillPackageStore
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import com.tomppi.enderslicer.smartinfill.SmartInfillUiState
import com.tomppi.enderslicer.storage.OneShotExportFileProvider
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegratedEnderSlicerApp(
    slicerViewModel: MainViewModel,
    octoPrintViewModel: OctoPrintViewModel,
    engine: SlicerEngine,
    onEngineChange: (SlicerEngine) -> Unit,
) {
    val slicerState by slicerViewModel.uiState.collectAsStateWithLifecycle()
    val octoPrintState by octoPrintViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val smartInfillStore = remember(context) { SmartInfillPackageStore(context.applicationContext) }
    var smartInfillPackage by remember { mutableStateOf(smartInfillStore.loadActive()) }
    val smartInfillLoadWarning = remember(smartInfillStore) { smartInfillStore.consumeLoadWarning() }
    var smartInfillImporting by remember { mutableStateOf(false) }
    var smartInfillValidating by remember { mutableStateOf(false) }
    // Which validation run owns the flag. The package id cannot identify a run: a
    // rotate or scale replaces the mesh, which restarts the effect with the SAME
    // package, and the cancelled run would then clear the flag the new run set.
    var smartInfillValidationRun by remember { mutableStateOf(0) }
    var smartInfillOpen by rememberSaveable { mutableStateOf(false) }

    fun deleteHandoff(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    LaunchedEffect(smartInfillLoadWarning) {
        smartInfillLoadWarning?.let { warning ->
            Toast.makeText(context, warning, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(smartInfillPackage) {
        SmartInfillRuntime.activate(smartInfillPackage)
    }

    // A null mesh is also the normal transient state while MainViewModel
    // restores the workspace after process recreation. Keep the persisted
    // package until a concrete mesh exists, then validate its exact digest.
    LaunchedEffect(slicerState.mesh, smartInfillPackage?.id) {
        val packageValue = smartInfillPackage
        val mesh = slicerState.mesh
        if (packageValue == null || mesh == null) {
            // Nothing is being validated for these keys, and Remove stays enabled
            // while a validation runs, so the flag must never outlive the run that
            // set it - refusing to clear it here left Slice blocked until restart.
            smartInfillValidating = false
            return@LaunchedEffect
        }
        val run = smartInfillValidationRun + 1
        smartInfillValidationRun = run
        smartInfillValidating = true
        SmartInfillRuntime.activate(null)
        try {
            withContext(Dispatchers.IO) {
                val validationFile = File(
                    context.cacheDir,
                    "filasim-source/validation-${packageValue.id}-${UUID.randomUUID()}.stl",
                )
                validationFile.parentFile?.mkdirs()
                try {
                    StlMeshWriter.writeBinary(mesh, validationFile)
                    packageValue.requireMatchesSource(validationFile)
                } finally {
                    validationFile.delete()
                }
            }
            if (smartInfillPackage?.id == packageValue.id) SmartInfillRuntime.activate(packageValue)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A cancelled A validation must never clear a newer B package. The
            // runtime was cleared at the top of this run, so it cannot say which
            // package is current any more: the package state is what names it.
            if (smartInfillPackage?.id == packageValue.id) {
                smartInfillStore.clearActive()
                SmartInfillRuntime.activate(null)
                smartInfillPackage = null
                Toast.makeText(
                    context,
                    "Smart Infill was cleared because the model geometry or placement changed",
                    Toast.LENGTH_LONG,
                ).show()
            }
        } finally {
            // Only the newest run may clear the flag. The package id is not enough:
            // re-validating the same package (a move or scale) starts a new run, and
            // the cancelled one would clear the flag while the new one is still
            // hashing - which unblocked Slice with the runtime already cleared.
            if (smartInfillValidationRun == run) {
                smartInfillValidating = false
            }
        }
    }

    LaunchedEffect(octoPrintState.authorizationDialogLaunchNonce) {
        if (octoPrintState.authorizationDialogLaunchNonce == 0L) return@LaunchedEffect
        val url = octoPrintState.authorizationDialogUrl ?: return@LaunchedEffect
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { error ->
            Toast.makeText(
                context,
                error.message ?: "Unable to open the OctoPrint authorization page",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Commits a modifier archive into the slice pipeline.
     *
     * Exactly the import, activation and cleanup the WebView handoff performed,
     * so a package produced by the native engine behaves like one produced in
     * the browser - and the store's validation stays the single gate.
     */
    fun applyModifierPackage(exportUri: Uri, metadata: String, sourceSha: String) {
        val previousPackage = smartInfillPackage
        smartInfillImporting = true
        // Keep validation of the previous package from clearing the newly
        // published active-package pointer during the import handoff.
        SmartInfillRuntime.activate(null)
        scope.launch {
            try {
                // importPackage activates the package on disk as part of its commit, so
                // the in-memory handoff and the published-slice invalidation have to
                // complete even when a rotation disposes this composition mid-import:
                // otherwise the disk and the runtime disagree and G-code sliced before
                // the modifiers were applied stays exportable.
                val packageValue = withContext(NonCancellable + Dispatchers.IO) {
                    val imported = smartInfillStore.importPackage(exportUri, metadata, sourceSha)
                    SmartInfillRuntime.activate(imported)
                    imported
                }
                smartInfillPackage = packageValue
                smartInfillOpen = true
                slicerViewModel.invalidatePublishedSlice(SMART_INFILL_CHANGED_MESSAGE)
                withContext(NonCancellable + Dispatchers.IO) {
                    previousPackage
                        ?.takeIf { it.id != packageValue.id }
                        ?.directory
                        ?.deleteRecursively()
                }
                Toast.makeText(
                    context,
                    "Smart Infill enabled with ${packageValue.modifiers.size} density regions",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                SmartInfillRuntime.activate(previousPackage)
                Toast.makeText(
                    context,
                    error.message ?: "Unable to import the filaSim modifier package",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                smartInfillImporting = false
                deleteHandoff(exportUri)
            }
        }
    }

    /**
     * The STL the structural analysis runs on: the displayed mesh written the
     * way the validator writes it, so the package's fingerprint matches the
     * model the slice checks against. The native engine analyzes this file, and
     * the WebView handoff received it, which keeps both producers identical.
     */
    suspend fun prepareSmartInfillSource(mesh: StlMesh): File = withContext(Dispatchers.IO) {
        val source = File(context.cacheDir, "filasim-source/current-displayed.stl")
        source.parentFile?.mkdirs()
        StlMeshWriter.writeBinary(mesh, source)
        source
    }

    fun processSmartInfillResult(result: androidx.activity.result.ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return
        val data = result.data
        val exportUri = data?.data
        val resultKind = data?.getStringExtra(SmartInfillActivity.EXTRA_RESULT_KIND)
        if (exportUri == null || resultKind.isNullOrBlank()) {
            exportUri?.let(::deleteHandoff)
            Toast.makeText(context, "filaSim returned an incomplete export", Toast.LENGTH_LONG).show()
            return
        }
        if (slicerViewModel.uiState.value.isBusy || smartInfillImporting) {
            deleteHandoff(exportUri)
            Toast.makeText(
                context,
                "Smart Infill export was not applied because another operation is active",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        if (resultKind == SmartInfillActivity.RESULT_SHAPE) {
            scope.launch {
                val previousPath = slicerViewModel.uiState.value.modelPath
                slicerViewModel.importPartTopoResult(exportUri)
                val completed = slicerViewModel.awaitIdleIfBusy()
                val imported = completed.mesh != null && completed.modelPath != previousPath
                if (imported) {
                    val previousPackage = smartInfillPackage
                    smartInfillStore.clearActive()
                    SmartInfillRuntime.activate(null)
                    smartInfillPackage = null
                    smartInfillOpen = false
                    withContext(Dispatchers.IO) {
                        previousPackage?.directory?.deleteRecursively()
                    }
                    Toast.makeText(
                        context,
                        "Imported the filaSim Part Topo shape; inspect and slice it as a new model",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "The filaSim Part Topo shape could not be imported; the previous model and Smart Infill package were kept",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                deleteHandoff(exportUri)
            }
            return
        }

        if (resultKind != SmartInfillActivity.RESULT_MODIFIERS) {
            deleteHandoff(exportUri)
            Toast.makeText(context, "filaSim returned an unknown export type", Toast.LENGTH_LONG).show()
            return
        }
        val metadata = data.getStringExtra(SmartInfillActivity.EXTRA_METADATA_JSON)
        val sourceSha = data.getStringExtra(SmartInfillActivity.EXTRA_SOURCE_SHA256)
        if (metadata.isNullOrBlank() || sourceSha.isNullOrBlank()) {
            deleteHandoff(exportUri)
            Toast.makeText(context, "filaSim returned incomplete Smart Infill metadata", Toast.LENGTH_LONG).show()
            return
        }
        applyModifierPackage(exportUri, metadata, sourceSha)
    }


    val smartInfillLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (slicerViewModel.deferUntilRestoreCompletes { processSmartInfillResult(result) }) {
            return@rememberLauncherForActivityResult
        }
        processSmartInfillResult(result)
    }

    fun clearBuildPlate() {
        if (slicerState.isBusy || smartInfillImporting) {
            Toast.makeText(context, "Finish the current operation first", Toast.LENGTH_SHORT).show()
            return
        }
        val packageToDelete = smartInfillPackage
        scope.launch {
            slicerViewModel.clearBuildPlate()
            val completed = slicerViewModel.awaitIdleIfBusy()
            val cleared = completed.mesh == null && completed.modelPath == null
            if (cleared) {
                smartInfillStore.clearActive()
                SmartInfillRuntime.activate(null)
                smartInfillPackage = null
                smartInfillOpen = false
                withContext(Dispatchers.IO) {
                    packageToDelete?.directory?.deleteRecursively()
                }
            } else {
                Toast.makeText(
                    context,
                    "The build plate could not be cleared; the model and Smart Infill package were kept",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun launchSmartInfill() {
        val mesh = slicerState.mesh
        if (mesh == null || slicerState.isBusy || smartInfillImporting) {
            Toast.makeText(context, "Import a model and finish the current operation first", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            runCatching { prepareSmartInfillSource(mesh) }.onSuccess { source ->
                smartInfillLauncher.launch(
                    Intent(context, SmartInfillActivity::class.java)
                        .putExtra(SmartInfillActivity.EXTRA_MODEL_PATH, source.absolutePath)
                        .putExtra(SmartInfillActivity.EXTRA_MODEL_NAME, mesh.displayName),
                )
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    error.message ?: "Unable to prepare the model for filaSim",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // ---- native Smart Infill session --------------------------------------
    //
    // The structural workflow runs in the app: a session is opened on the
    // displayed model's STL, boundary conditions are assigned by tapping
    // surfaces, and the result is committed through the same store import the
    // WebView handoff used, so the slice pipeline sees one kind of package.

    var smartInfillController by remember { mutableStateOf<SmartInfillController?>(null) }
    var smartInfillStarting by remember { mutableStateOf(false) }
    val nativeInfillScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val nativeInfillState = smartInfillController?.state?.collectAsStateWithLifecycle()?.value

    DisposableEffect(smartInfillController) {
        val controller = smartInfillController
        onDispose {
            slicerViewModel.setSmartInfillPickHandler(null)
            slicerViewModel.setSmartInfillPicking(false)
            slicerViewModel.setSmartInfillOverlay(null)
            controller?.close()
        }
    }

    DisposableEffect(Unit) {
        onDispose { nativeInfillScope.cancel() }
    }

    // A different model invalidates the session: it was opened on the old geometry.
    LaunchedEffect(slicerState.modelPath) {
        smartInfillController = null
    }

    // While a condition is armed, a model tap belongs to Smart Infill.
    LaunchedEffect(smartInfillController, nativeInfillState?.pickingConditionId) {
        val controller = smartInfillController
        if (controller == null) {
            slicerViewModel.setSmartInfillPickHandler(null)
            slicerViewModel.setSmartInfillPicking(false)
        } else {
            slicerViewModel.setSmartInfillPickHandler(controller::pickAt)
            slicerViewModel.setSmartInfillPicking(nativeInfillState?.pickingConditionId != null)
        }
    }

    // The picked supports and loads are tinted on the model while the panel is
    // open, so the setup the solver runs is the one the user sees on the part.
    val smartInfillConditions = nativeInfillState?.conditions.orEmpty()
    val smartInfillPickingId = nativeInfillState?.pickingConditionId
    val smartInfillBins = nativeInfillState?.resultBins ?: IntArray(0)
    val smartInfillBinDensities = nativeInfillState?.resultBinDensities ?: DoubleArray(0)
    // The optimized volumes, drawn as coloured wireframe shells over the model.
    val smartInfillVolumes = nativeInfillState?.optimization?.regions.orEmpty()
    val smartInfillOverlay = remember(
        smartInfillConditions,
        smartInfillPickingId,
        smartInfillOpen,
        smartInfillBins,
        smartInfillBinDensities,
        smartInfillVolumes,
    ) {
        if (smartInfillOpen) {
            SmartInfillOverlay.of(
                conditions = smartInfillConditions,
                pickingConditionId = smartInfillPickingId,
                surfaceBins = smartInfillBins,
                binDensities = smartInfillBinDensities,
                volumes = smartInfillVolumes,
            )
        } else {
            null
        }
    }
    LaunchedEffect(smartInfillOverlay) {
        slicerViewModel.setSmartInfillOverlay(smartInfillOverlay)
    }

    fun startNativeSmartInfill() {
        val mesh = slicerState.mesh
        if (mesh == null || slicerState.isBusy || smartInfillImporting || smartInfillStarting) {
            Toast.makeText(context, "Import a model and finish the current operation first", Toast.LENGTH_SHORT).show()
            return
        }
        smartInfillStarting = true
        // Preparing a real part is tens of seconds of voxelizing (see the handover):
        // the plate can change under it, so the model it was opened for is checked
        // again before the session is published.
        val startingModelPath = slicerState.modelPath
        scope.launch {
            // The session is opened inside withContext: if the composition is torn
            // down while that runs, withContext completes and then throws, and a
            // controller referenced nowhere would keep its Rust session - mesh and
            // grid - for the life of the process.
            var created: SmartInfillController? = null
            try {
                val controller = withContext(Dispatchers.IO) {
                    val source = prepareSmartInfillSource(mesh)
                    SmartInfillController(FilaSimEngine.open(source, mesh.displayName), nativeInfillScope)
                        .also { created = it }
                }
                if (slicerViewModel.uiState.value.modelPath != startingModelPath) {
                    // A session on geometry that is no longer on the plate would
                    // tint the wrong part and export a package the store refuses.
                    controller.close()
                    Toast.makeText(
                        context,
                        "The model changed while the analysis was being prepared — start it again",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                smartInfillController = controller
                // The preparation is the controller's own tracked work, and this
                // waits for it: a tap radius that suits this part, the voxel grid,
                // and the pick adjacency the first tap needs. Tracked because
                // clearing the plate in the middle of it must not free the native
                // session while the worker is still inside it.
                controller.prepare(SmartInfillController.spotSizeForDiagonalMm(meshDiagonalMm(mesh)))
                    ?.join()
            } catch (cancelled: CancellationException) {
                created?.close()
                throw cancelled
            } catch (error: Throwable) {
                Toast.makeText(
                    context,
                    error.message ?: "Unable to start the Smart Infill engine",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                smartInfillStarting = false
            }
        }
    }

    fun applyNativeSmartInfill() {
        val controller = smartInfillController ?: return
        val optimization = nativeInfillState?.optimization ?: return
        // The analyzed geometry, needed twice: its fingerprint is the package
        // check, and its bounds are the frame the modifier volumes are written
        // in (the store places them again when it stages them for the slice).
        val mesh = slicerState.mesh ?: return
        if (slicerState.isBusy || smartInfillImporting) {
            Toast.makeText(context, "Finish the current operation first", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val handoff = try {
                withContext(Dispatchers.IO) {
                    // Must be the directory the one-shot provider serves: a file
                    // outside it has no content URI, and FileProvider throws.
                    val directory = File(
                        context.cacheDir,
                        OneShotExportFileProvider.SMART_INFILL_DIRECTORY,
                    )
                    val name = controller.state.value.modelName.ifBlank { "model.stl" }
                    val analyzedSource = prepareSmartInfillSource(mesh)
                    if (optimization.solid) {
                        SmartInfillNativeExport.writeOptimizedShape(
                            directory = directory,
                            sourceName = name,
                            shape = controller.optimizedShape(),
                            analyzedSource = analyzedSource,
                        ) to null
                    } else {
                        SmartInfillNativeExport.writeModifierArchive(
                            directory = directory,
                            sourceName = name,
                            archive = controller.modifierArchive(),
                            analyzedSource = analyzedSource,
                        ) to controller.modifierMetadata()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Toast.makeText(
                    context,
                    error.message ?: "Unable to write the Smart Infill result",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val (file, metadata) = handoff
            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
            if (metadata == null) {
                // Part Topo returns a different body: it replaces the model, and
                // any modifier package goes with the geometry it was made for.
                val previousPath = slicerState.modelPath
                slicerViewModel.importPartTopoResult(uri)
                val completed = slicerViewModel.awaitIdleIfBusy()
                val imported = completed.mesh != null && completed.modelPath != previousPath
                if (imported) {
                    val previousPackage = smartInfillPackage
                    smartInfillStore.clearActive()
                    SmartInfillRuntime.activate(null)
                    smartInfillPackage = null
                    smartInfillOpen = false
                    withContext(Dispatchers.IO) { previousPackage?.directory?.deleteRecursively() }
                    Toast.makeText(
                        context,
                        "Imported the optimized body; inspect and slice it as a new model",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "The optimized body could not be imported; the model was kept",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                deleteHandoff(uri)
            } else {
                smartInfillOpen = false
                applyModifierPackage(uri, metadata, controller.sourceSha256)
            }
        }
    }

    fun removeSmartInfillPackage() {
        val packageToDelete = smartInfillPackage
        smartInfillStore.clearActive()
        SmartInfillRuntime.activate(null)
        smartInfillPackage = null
        smartInfillOpen = false
        slicerViewModel.invalidatePublishedSlice(SMART_INFILL_CHANGED_MESSAGE)
        scope.launch(Dispatchers.IO) {
            packageToDelete?.directory?.deleteRecursively()
        }
        Toast.makeText(context, "Smart Infill removed", Toast.LENGTH_SHORT).show()
    }

    val smartSummary = smartInfillPackage?.summary
    val smartInfillMenuLabel = if (smartSummary == null) {
        "Smart Infill"
    } else {
        "Smart Infill ${smartSummary.baseDensityPercent.toInt()}→${smartSummary.modifierDensitiesPercent.maxOrNull() ?: smartSummary.baseDensityPercent.toInt()}%"
    }
    // The workflow is only on screen while there is a model to analyze.
    val smartInfillPanelOpen = smartInfillOpen && slicerState.mesh != null
    EnderSlicerApp(
        viewModel = slicerViewModel,
        engine = engine,
        onEngineChange = onEngineChange,
        sliceBlockedReason = when {
            smartInfillImporting -> "Smart Infill import is still being committed"
            smartInfillValidating -> "Smart Infill is being validated for the current model"
            else -> null
        },
        plateOverflowItems = { close ->
            DropdownMenuItem(
                text = { Text("Clear plate") },
                onClick = {
                    close()
                    clearBuildPlate()
                },
                enabled = !slicerState.isBusy && !smartInfillImporting && (
                    slicerState.mesh != null ||
                        slicerState.gcodePath != null ||
                        smartInfillPackage != null
                    ),
            )
        },
        moreExtraItems = {
            MoreRow(
                icon = AppIcons.Bolt,
                title = smartInfillMenuLabel,
                subtitle = if (smartSummary == null) {
                    "Load-optimized density modifiers via filaSim"
                } else {
                    "Base " + smartSummary.baseDensityPercent.toInt() + "% · " + smartSummary.mode + " " + smartSummary.pattern
                },
                enabled = slicerState.mesh != null && !slicerState.isBusy && !smartInfillImporting,
                badge = "EXP",
                onClick = { smartInfillOpen = true },
            )
        },
        printTabContent = {
            HardenedOctoPrintSheet(
                state = octoPrintState,
                localGcodePath = slicerState.gcodePath.takeIf {
                    !slicerState.isBusy && slicerState.hasCurrentGcode()
                },
                suggestedFileName = suggestedOctoPrintName(slicerState),
                viewModel = octoPrintViewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            )
        },
        plateOverlayContent = {
            if (smartInfillPanelOpen) {
                SmartInfillWorkbenchPanel(
                    state = nativeInfillState ?: SmartInfillUiState(
                        modelName = slicerState.mesh?.displayName.orEmpty(),
                        hasSession = false,
                    ),
                    packageValue = smartInfillPackage,
                    starting = smartInfillStarting,
                    enabled = !slicerState.isBusy && !smartInfillImporting,
                    onStart = ::startNativeSmartInfill,
                    onAddCondition = { condition -> smartInfillController?.addCondition(condition) },
                    onArmPicking = { id -> smartInfillController?.armPicking(id) },
                    onRemoveCondition = { id -> smartInfillController?.removeCondition(id) },
                    onExpandToSurface = { id -> smartInfillController?.expandToSurface(id) },
                    onUpdateCondition = { id, condition ->
                        smartInfillController?.updateCondition(id, condition)
                    },
                    onSpotSize = { mm -> smartInfillController?.setSpotSize(mm) },
                    onConfiguration = { configuration ->
                        smartInfillController?.setConfiguration(configuration)
                    },
                    onOptions = { options -> smartInfillController?.setOptions(options) },
                    onCheck = { smartInfillController?.checkSetup() },
                    onSolve = { smartInfillController?.solve() },
                    onOptimize = { smartInfillController?.optimize() },
                    onStop = { smartInfillController?.cancel() },
                    onApply = ::applyNativeSmartInfill,
                    onRemovePackage = ::removeSmartInfillPackage,
                    onOpenWorkspace = {
                        smartInfillOpen = false
                        launchSmartInfill()
                    },
                    onClose = { smartInfillOpen = false },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .fillMaxWidth(),
                )
            }
        },
    )

    if (smartInfillImporting) {
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("Importing and validating Smart Infill…")
                }
            }
        }
    }
}

/** Status left behind when the active Smart Infill package changes. */
private const val SMART_INFILL_CHANGED_MESSAGE = "Smart Infill package changed; slice again to export G-code"

/**
 * The displayed model's bounding-box diagonal, which sizes the Smart Infill tap
 * radius: a selection has to scale with the part, not with a fixed millimetre
 * default that is wrong at both ends of the size range.
 */
private fun meshDiagonalMm(mesh: StlMesh): Double {
    val bounds = mesh.bounds
    val width = bounds.width.toDouble()
    val depth = bounds.depth.toDouble()
    val height = bounds.height.toDouble()
    return Math.sqrt(width * width + depth * depth + height * height)
}

private fun suggestedOctoPrintName(state: MainUiState): String {
    val rawName = state.mesh?.displayName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
    val source = rawName
        ?.let { name -> name.substringBeforeLast('.', name) }
        ?.takeIf(String::isNotBlank)
        ?: "enderslicercura"
    return "$source.gcode"
}

private suspend fun MainViewModel.awaitIdleIfBusy(): MainUiState {
    val started = uiState.value.isBusy
    return if (started) uiState.first { state -> !state.isBusy } else uiState.value
}
