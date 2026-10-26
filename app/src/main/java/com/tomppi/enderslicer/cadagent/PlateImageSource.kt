package com.tomppi.enderslicer.cadagent

import com.tomppi.enderslicer.viewer.PlateImage

/**
 * The app's own plate renderer, offered to the agent as the `plate_screenshot` tool.
 *
 * The image is the plate view the user is looking at - same camera, same mesh, same tinted
 * selected face - drawn by this app and never by the engine: the engine's `screenshot` is a
 * second camera nobody is looking through, which is why the bridge refuses it. A null answer
 * means the plate is not drawing right now (another tab, the app in the background, a surface
 * being rebuilt); the tool says so instead of sending a picture that would not be the truth.
 */
fun interface PlateImageSource {
    fun renderPlate(): PlateImage?
}

/**
 * What the app hands the in-app agent for one turn, beyond the user's own words.
 *
 * The selection block is the message's preamble (see
 * [com.tomppi.enderslicer.solidcraft.SelectedFace.promptBlock]); [attachPlateToSelection] is the
 * sheet's **Lend the agent my view** switch - when it is on, a message that carries a face
 * selection carries a plate render too, so `make this face 2 mm thicker` is shown rather than
 * only described. [faceTinted] says the picture has the amber selection tint in it, which is what
 * lets the caption state it instead of leaving the model to infer it.
 */
data class AgentView(
    val selectionBlock: String = "",
    val plate: PlateImageSource? = null,
    val attachPlateToSelection: Boolean = false,
    val faceTinted: Boolean = false,
) {
    val hasSelection: Boolean get() = selectionBlock.isNotBlank()
}
