package com.tomppi.enderslicer.profile

object CuraProjectAudit {
    private val exposedOrMachineKeys = setOf(
        "machine_name", "machine_width", "machine_depth", "machine_height", "machine_shape",
        "machine_center_is_zero", "machine_heated_bed", "machine_heated_build_volume",
        "machine_gcode_flavor", "machine_nozzle_size", "material_diameter", "gantry_height",
        "machine_start_gcode", "machine_end_gcode", "layer_height", "layer_height_0",
        "adaptive_layer_height_enabled", "adaptive_layer_height_variation",
        "adaptive_layer_height_variation_step", "adaptive_layer_height_threshold", "line_width",
        "slicing_tolerance", "wall_line_count", "wall_thickness", "top_layers", "bottom_layers",
        "top_bottom_thickness", "initial_bottom_layers", "hole_xy_offset", "xy_offset_layer_0",
        "z_seam_type", "z_seam_x", "z_seam_y", "z_seam_relative", "z_seam_corner",
        "infill_sparse_density", "infill_pattern", "zig_zaggify_infill",
        "speed_print", "speed_wall", "speed_wall_0",
        "speed_wall_x", "speed_infill", "speed_topbottom", "speed_travel", "speed_layer_0",
        "material_print_temperature", "material_print_temperature_layer_0", "material_bed_temperature",
        "build_volume_temperature", "material_standby_temperature", "material_density",
        "material_adhesion_tendency", "material_surface_energy", "material_brand", "material_type",
        "material_guid", "extruders_enabled_count", "material_flow",
        "cool_fan_speed", "cool_fan_speed_0", "cool_fan_full_layer",
        "support_enable", "support_type", "support_structure", "support_angle", "support_infill_rate",
        "support_pattern", "support_interface_enable", "support_interface_density",
        "support_interface_height", "support_z_distance", "support_xy_distance",
        "speed_support", "speed_support_interface",
        "retraction_amount", "retraction_speed", "retraction_min_travel", "retract_at_layer_change",
        "retraction_combing", "travel_avoid_other_parts", "travel_avoid_distance",
        "retraction_hop_enabled", "retraction_hop", "machine_firmware_retract",
        "coasting_enable", "coasting_volume", "coasting_min_volume", "coasting_speed",
        "adhesion_type", "skirt_line_count", "brim_width", "raft_margin",
        "ironing_enabled", "ironing_only_highest_layer", "ironing_flow", "speed_ironing",
    )

    private val highImpactHiddenKeys = linkedMapOf(
        "support_roof_enable" to "support roof",
        "support_bottom_enable" to "support floor",
        "support_roof_density" to "support-roof density",
        "support_bottom_density" to "support-floor density",
        "support_tree_branch_diameter" to "tree-support branch diameter",
        "support_tree_branch_angle" to "tree-support branch angle",
        "support_tree_tip_diameter" to "tree-support tip diameter",
        "xy_offset" to "horizontal expansion",
        "infill_wipe_dist" to "infill wipe distance",
        "bridge_settings_enabled" to "bridge settings",
        "bridge_wall_speed" to "bridge wall speed",
        "bridge_skin_speed" to "bridge skin speed",
        "acceleration_enabled" to "acceleration control",
        "jerk_enabled" to "jerk control",
        "speed_slowdown_layers" to "initial-layer speed ramp",
        "magic_spiralize" to "spiralize outer contour",
        "meshfix_union_all" to "mesh union repair",
        "meshfix_extensive_stitching" to "extensive mesh stitching",
        "meshfix_keep_open_polygons" to "open-polygon handling",
    )

    fun warnings(rawValues: Map<String, String>): List<String> {
        val active = highImpactHiddenKeys.mapNotNull { (key, label) ->
            val value = rawValues[key]?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            "$label ($key=$value)"
        }
        val unknownConcrete = rawValues.entries
            .filter { (key, value) ->
                key !in exposedOrMachineKeys &&
                    key !in highImpactHiddenKeys &&
                    value.isNotBlank() &&
                    !value.trim().startsWith("=")
            }
            .map(Map.Entry<String, String>::key)
            .sorted()

        return buildList {
            if (active.isNotEmpty()) {
                add("Imported Cura behavior is active but not editable in TrioSlicer: ${active.joinToString(limit = 12, truncated = "…")}")
            }
            if (unknownConcrete.isNotEmpty()) {
                add("${unknownConcrete.size} additional Cura values are passed through in the background but are not exposed in the app")
            }
        }
    }
}
