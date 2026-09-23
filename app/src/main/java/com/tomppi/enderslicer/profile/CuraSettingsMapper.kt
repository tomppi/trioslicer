package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings

object CuraSettingsMapper {
    fun apply(base: SlicerSettings, values: Map<String, String>): SlicerSettings {
        fun number(key: String): Double? = values[key]
            ?.trim()
            ?.takeUnless { it.startsWith("=") }
            ?.toDoubleOrNull()

        fun bool(key: String): Boolean? = when (values[key]?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }

        return base.copy(
            printerName = values["machine_name"] ?: base.printerName,
            machineWidthMm = number("machine_width") ?: base.machineWidthMm,
            machineDepthMm = number("machine_depth") ?: base.machineDepthMm,
            machineHeightMm = number("machine_height") ?: base.machineHeightMm,
            buildPlateShape = values["machine_shape"] ?: base.buildPlateShape,
            originAtCenter = bool("machine_center_is_zero") ?: base.originAtCenter,
            heatedBed = bool("machine_heated_bed") ?: base.heatedBed,
            heatedBuildVolume = bool("machine_heated_build_volume") ?: base.heatedBuildVolume,
            gcodeFlavor = values["machine_gcode_flavor"] ?: base.gcodeFlavor,
            nozzleSizeMm = number("machine_nozzle_size") ?: base.nozzleSizeMm,
            filamentDiameterMm = number("material_diameter") ?: base.filamentDiameterMm,
            gantryHeightMm = number("gantry_height") ?: base.gantryHeightMm,
            layerHeightMm = number("layer_height") ?: base.layerHeightMm,
            initialLayerHeightMm = number("layer_height_0") ?: base.initialLayerHeightMm,
            adaptiveLayerHeightEnabled = bool("adaptive_layer_height_enabled") ?: base.adaptiveLayerHeightEnabled,
            adaptiveLayerHeightVariationMm = number("adaptive_layer_height_variation")
                ?: base.adaptiveLayerHeightVariationMm,
            adaptiveLayerHeightVariationStepMm = number("adaptive_layer_height_variation_step")
                ?: base.adaptiveLayerHeightVariationStepMm,
            adaptiveLayerHeightThreshold = number("adaptive_layer_height_threshold")
                ?: base.adaptiveLayerHeightThreshold,
            lineWidthMm = number("line_width") ?: base.lineWidthMm,
            slicingTolerance = values["slicing_tolerance"] ?: base.slicingTolerance,
            wallLineCount = number("wall_line_count")?.toInt() ?: base.wallLineCount,
            wallThicknessMm = number("wall_thickness") ?: base.wallThicknessMm,
            topLayers = number("top_layers")?.toInt() ?: base.topLayers,
            bottomLayers = number("bottom_layers")?.toInt() ?: base.bottomLayers,
            topBottomThicknessMm = number("top_bottom_thickness") ?: base.topBottomThicknessMm,
            initialBottomLayers = number("initial_bottom_layers")?.toInt() ?: base.initialBottomLayers,
            holeHorizontalExpansionMm = number("hole_xy_offset") ?: base.holeHorizontalExpansionMm,
            initialLayerHorizontalExpansionMm = number("xy_offset_layer_0")
                ?: base.initialLayerHorizontalExpansionMm,
            zSeamType = values["z_seam_type"] ?: base.zSeamType,
            zSeamXmm = number("z_seam_x") ?: base.zSeamXmm,
            zSeamYmm = number("z_seam_y") ?: base.zSeamYmm,
            zSeamRelative = bool("z_seam_relative") ?: base.zSeamRelative,
            zSeamCorner = values["z_seam_corner"] ?: base.zSeamCorner,
            infillDensityPercent = number("infill_sparse_density") ?: base.infillDensityPercent,
            infillPattern = values["infill_pattern"] ?: base.infillPattern,
            zigZagConnectInfill = bool("zig_zaggify_infill") ?: base.zigZagConnectInfill,
            printSpeedMmPerSecond = number("speed_print") ?: base.printSpeedMmPerSecond,
            wallSpeedMmPerSecond = number("speed_wall") ?: base.wallSpeedMmPerSecond,
            outerWallSpeedMmPerSecond = number("speed_wall_0") ?: base.outerWallSpeedMmPerSecond,
            innerWallSpeedMmPerSecond = number("speed_wall_x") ?: base.innerWallSpeedMmPerSecond,
            infillSpeedMmPerSecond = number("speed_infill") ?: base.infillSpeedMmPerSecond,
            topBottomSpeedMmPerSecond = number("speed_topbottom") ?: base.topBottomSpeedMmPerSecond,
            travelSpeedMmPerSecond = number("speed_travel") ?: base.travelSpeedMmPerSecond,
            initialLayerSpeedMmPerSecond = number("speed_layer_0") ?: base.initialLayerSpeedMmPerSecond,
            nozzleTemperatureC = number("material_print_temperature")?.toInt() ?: base.nozzleTemperatureC,
            initialNozzleTemperatureC = number("material_print_temperature_layer_0")?.toInt()
                ?: base.initialNozzleTemperatureC,
            bedTemperatureC = number("material_bed_temperature")?.toInt() ?: base.bedTemperatureC,
            buildVolumeTemperatureC = number("build_volume_temperature") ?: base.buildVolumeTemperatureC,
            materialStandbyTemperatureC = number("material_standby_temperature")
                ?: base.materialStandbyTemperatureC,
            materialDensityGPerCm3 = number("material_density") ?: base.materialDensityGPerCm3,
            materialAdhesionTendency = number("material_adhesion_tendency")?.toInt()
                ?: base.materialAdhesionTendency,
            materialSurfaceEnergyPercent = number("material_surface_energy")?.toInt()
                ?: base.materialSurfaceEnergyPercent,
            materialBrand = values["material_brand"] ?: base.materialBrand,
            materialType = values["material_type"] ?: base.materialType,
            materialGuid = values["material_guid"] ?: base.materialGuid,
            enabledExtruderCount = number("extruders_enabled_count")?.toInt() ?: base.enabledExtruderCount,
            materialFlowPercent = number("material_flow") ?: base.materialFlowPercent,
            // CuraEngine reads the cooling fan from the Regular Fan Speed child
            // (cool_fan_speed_min) rather than the bare cool_fan_speed parent,
            // so prefer the child when an imported stack carries both.
            fanSpeedPercent = number("cool_fan_speed_min") ?: number("cool_fan_speed") ?: base.fanSpeedPercent,
            initialFanSpeedPercent = number("cool_fan_speed_0") ?: base.initialFanSpeedPercent,
            fanFullAtLayer = number("cool_fan_full_layer")?.toInt() ?: base.fanFullAtLayer,
            supportsEnabled = bool("support_enable") ?: base.supportsEnabled,
            supportPlacement = values["support_type"] ?: base.supportPlacement,
            supportStructure = values["support_structure"] ?: base.supportStructure,
            supportAngleDegrees = number("support_angle") ?: base.supportAngleDegrees,
            supportDensityPercent = number("support_infill_rate") ?: base.supportDensityPercent,
            supportPattern = values["support_pattern"] ?: base.supportPattern,
            supportInterfaceEnabled = bool("support_interface_enable") ?: base.supportInterfaceEnabled,
            supportInterfaceDensityPercent = number("support_interface_density")
                ?: base.supportInterfaceDensityPercent,
            supportInterfaceHeightMm = number("support_interface_height") ?: base.supportInterfaceHeightMm,
            supportZDistanceMm = number("support_z_distance") ?: base.supportZDistanceMm,
            supportXyDistanceMm = number("support_xy_distance") ?: base.supportXyDistanceMm,
            supportSpeedMmPerSecond = number("speed_support") ?: base.supportSpeedMmPerSecond,
            supportInterfaceSpeedMmPerSecond = number("speed_support_interface")
                ?: base.supportInterfaceSpeedMmPerSecond,
            retractionDistanceMm = number("retraction_amount") ?: base.retractionDistanceMm,
            retractionSpeedMmPerSecond = number("retraction_speed") ?: base.retractionSpeedMmPerSecond,
            retractionMinimumTravelMm = number("retraction_min_travel") ?: base.retractionMinimumTravelMm,
            retractAtLayerChange = bool("retract_at_layer_change") ?: base.retractAtLayerChange,
            combingMode = values["retraction_combing"] ?: base.combingMode,
            avoidPrintedParts = bool("travel_avoid_other_parts") ?: base.avoidPrintedParts,
            travelAvoidDistanceMm = number("travel_avoid_distance") ?: base.travelAvoidDistanceMm,
            zHopEnabled = bool("retraction_hop_enabled") ?: base.zHopEnabled,
            zHopHeightMm = number("retraction_hop") ?: base.zHopHeightMm,
            firmwareRetraction = bool("machine_firmware_retract") ?: base.firmwareRetraction,
            coastingEnabled = bool("coasting_enable") ?: base.coastingEnabled,
            coastingVolumeMm3 = number("coasting_volume") ?: base.coastingVolumeMm3,
            coastingMinimumVolumeMm3 = number("coasting_min_volume") ?: base.coastingMinimumVolumeMm3,
            coastingSpeedPercent = number("coasting_speed") ?: base.coastingSpeedPercent,
            adhesionType = values["adhesion_type"] ?: base.adhesionType,
            skirtLineCount = number("skirt_line_count")?.toInt() ?: base.skirtLineCount,
            brimWidthMm = number("brim_width") ?: base.brimWidthMm,
            raftMarginMm = number("raft_margin") ?: base.raftMarginMm,
            ironingEnabled = bool("ironing_enabled") ?: base.ironingEnabled,
            ironingOnlyHighestLayer = bool("ironing_only_highest_layer") ?: base.ironingOnlyHighestLayer,
            ironingFlowPercent = number("ironing_flow") ?: base.ironingFlowPercent,
            ironingSpeedMmPerSecond = number("speed_ironing") ?: base.ironingSpeedMmPerSecond,
            overriddenSettingKeys = emptySet(),
        )
    }
}
