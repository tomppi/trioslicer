from pathlib import Path
import sys

root = Path(sys.argv[1])
project_root = Path.cwd()
arc_patch_root = project_root / "native" / "curaengine" / "patches"

def replace(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Expected Android patch context was not found in {path}: {old!r}")
    path.write_text(text.replace(old, new))

conanfile = root / "conanfile.py"
replace(
    conanfile,
    'if req.startswith("onetbb/") and self.settings.arch == "wasm" and self.settings.os == "Emscripten":\n                continue',
    'if req.startswith("onetbb/") and (\n                self.settings.os == "Android"\n                or (self.settings.arch == "wasm" and self.settings.os == "Emscripten")\n            ):\n                continue',
)
replace(
    conanfile,
    'tc.variables["ENABLE_THREADING"] = not (self.settings.arch == "wasm" and self.settings.os == "Emscripten")',
    'tc.variables["ENABLE_THREADING"] = not (self.settings.arch == "wasm" and self.settings.os == "Emscripten")\n        tc.variables["ENABLE_TBB"] = self.settings.os != "Android" and not (self.settings.arch == "wasm" and self.settings.os == "Emscripten")',
)

cmake = root / "CMakeLists.txt"
replace(
    cmake,
    'option(ENABLE_THREADING "Enable threading support" ON)',
    'option(ENABLE_THREADING "Enable threading support" ON)\noption(ENABLE_TBB "Enable OneTBB global thread controller" ON)',
)
replace(
    cmake,
    'if (NOT EMSCRIPTEN)\n    find_package(TBB REQUIRED)\nendif ()',
    'if (ENABLE_TBB AND NOT EMSCRIPTEN)\n    find_package(TBB REQUIRED)\nendif ()',
)
replace(
    cmake,
    '        CURA_ENGINE_HASH=\\"${CURA_ENGINE_HASH}\\"',
    '        CURA_ENGINE_HASH=\\"${CURA_ENGINE_HASH}\\"\n        $<$<NOT:$<BOOL:${ENABLE_TBB}>>:CURA_ENGINE_NO_TBB>',
)
replace(
    cmake,
    '        $<$<NOT:$<BOOL:${EMSCRIPTEN}>>:onetbb::onetbb>',
    '        $<$<AND:$<BOOL:${ENABLE_TBB}>,$<NOT:$<BOOL:${EMSCRIPTEN}>>>:onetbb::onetbb>',
)

application_h = root / "include" / "Application.h"
text = application_h.read_text()
text = text.replace(
    '#ifndef __EMSCRIPTEN__\n#include <oneapi/tbb/global_control.h>\n#endif',
    '#if !defined(__EMSCRIPTEN__) && !defined(CURA_ENGINE_NO_TBB)\n#include <oneapi/tbb/global_control.h>\n#endif',
)
text = text.replace(
    '#ifndef __EMSCRIPTEN__\n    tbb::global_control* tbb_controller_ = nullptr;\n#endif',
    '#if !defined(__EMSCRIPTEN__) && !defined(CURA_ENGINE_NO_TBB)\n    tbb::global_control* tbb_controller_ = nullptr;\n#endif',
)
application_h.write_text(text)

application_cpp = root / "src" / "Application.cpp"
text = application_cpp.read_text()
text = text.replace(
    '#ifndef __EMSCRIPTEN__\n    delete tbb_controller_;\n#endif',
    '#if !defined(__EMSCRIPTEN__) && !defined(CURA_ENGINE_NO_TBB)\n    delete tbb_controller_;\n#endif',
)
text = text.replace(
    '#ifndef __EMSCRIPTEN__\n    delete tbb_controller_;\n    tbb_controller_ = new tbb::global_control(tbb::global_control::max_allowed_parallelism, nthreads + 1);\n#endif',
    '#if !defined(__EMSCRIPTEN__) && !defined(CURA_ENGINE_NO_TBB)\n    delete tbb_controller_;\n    tbb_controller_ = new tbb::global_control(tbb::global_control::max_allowed_parallelism, nthreads + 1);\n#endif',
)
application_cpp.write_text(text)

command_line_cpp = root / "src" / "communication" / "CommandLine.cpp"
replace(
    command_line_cpp,
    '''                        const auto transformation = slice->scene.mesh_groups[mesh_group_index].settings.get<Matrix4x3D>("mesh_rotation_matrix");
                        const auto extruder_nr = slice->scene.mesh_groups[mesh_group_index].settings.get<size_t>("extruder_nr");''',
    '''                        auto transformation = slice->scene.mesh_groups[mesh_group_index].settings.get<Matrix4x3D>("mesh_rotation_matrix");
                        // EnderSlicer: Cura's frontend applies the complete affine
                        // transform before converting vertices to integer microns.
                        // mesh_position is too late for that because MeshGroup
                        // finalization runs after the STL loader has rounded each
                        // transformed vertex. Carry the translation in Matrix4x3D.
                        transformation.m[3][0] = slice->scene.mesh_groups[mesh_group_index].settings.get<double>("enderslicer_mesh_translation_x");
                        transformation.m[3][1] = slice->scene.mesh_groups[mesh_group_index].settings.get<double>("enderslicer_mesh_translation_y");
                        transformation.m[3][2] = slice->scene.mesh_groups[mesh_group_index].settings.get<double>("enderslicer_mesh_translation_z");
                        const auto extruder_nr = slice->scene.mesh_groups[mesh_group_index].settings.get<size_t>("extruder_nr");''',
)
replace(
    command_line_cpp,
    '''                        if (! loadMeshIntoMeshGroup(
                                &slice->scene.mesh_groups[mesh_group_index],
                                settings_folder / model_name,
                                transformation,
                                slice->scene.extruders[extruder_nr].settings_))
                        {
                            spdlog::error("Failed to load model: {} (error number {})", model_name, errno);
                            exit(1);
                        }''',
    '''                        if (! loadMeshIntoMeshGroup(
                                &slice->scene.mesh_groups[mesh_group_index],
                                settings_folder / model_name,
                                transformation,
                                slice->scene.extruders[extruder_nr].settings_))
                        {
                            spdlog::error("Failed to load model: {} (error number {})", model_name, errno);
                            exit(1);
                        }

                        // EnderSlicer: resolved model values must live on the
                        // actual Mesh, not only on MeshGroup::settings. The
                        // latter is not a parent of Mesh::settings_, so without
                        // this copy settable_per_mesh values such as
                        // support_interface_enable silently use extruder/default
                        // values during slicing.
                        Mesh& loaded_mesh = slice->scene.mesh_groups[mesh_group_index].meshes.back();
                        for (const auto& [setting_key, setting_value] : values)
                        {
                            loaded_mesh.settings_.add(setting_key, setting_value);
                        }''',
)

layer_plan_buffer_cpp = root / "src" / "LayerPlanBuffer.cpp"
replace(
    layer_plan_buffer_cpp,
    '''        if (extruder_plan.temperature_factor_ > 0) // force lower printing temperatures due to minimum layer time
        {
            print_temp = print_temp * (1 - extruder_plan.temperature_factor_) + extruder_plan.temperature_factor_ * extruder_settings.get<Temperature>("cool_min_temperature");
            initial_print_temp = std::min(initial_print_temp, print_temp);
        }''',
    '''        if (extruder_plan.temperature_factor_ > 0) // force lower printing temperatures due to minimum layer time
        {
            const Temperature configured_cool_min_temperature = extruder_settings.get<Temperature>("cool_min_temperature");
            // EnderSlicer: zero cool_min_temperature is a Cura frontend sentinel,
            // not a valid extrusion target. Keep the current print temperature
            // as the minimum while preserving every legitimate nonzero value.
            const Temperature safe_cool_min_temperature
                = configured_cool_min_temperature > 0 ? configured_cool_min_temperature : print_temp;
            print_temp = print_temp * (1 - extruder_plan.temperature_factor_)
                       + extruder_plan.temperature_factor_ * safe_cool_min_temperature;
            initial_print_temp = std::min(initial_print_temp, print_temp);
        }''',
)

# Install the native arc-overhang source and register it with CuraEngine.
(root / "include" / "ArcOverhang.h").write_text((arc_patch_root / "include" / "ArcOverhang.h").read_text())
(root / "src" / "ArcOverhang.cpp").write_text((arc_patch_root / "src" / "ArcOverhang.cpp").read_text())
(root / "include" / "WaveOverhang.h").write_text((arc_patch_root / "include" / "WaveOverhang.h").read_text())
(root / "src" / "WaveOverhang.cpp").write_text((arc_patch_root / "src" / "WaveOverhang.cpp").read_text())

# Keep arc overhangs as real skin for Cura's estimates and motion behaviour,
# but carry one private flag so the G-code writer can emit an exact preview marker.
gcode_path_config_h = root / "include" / "GCodePathConfig.h"
replace(
    gcode_path_config_h,
    '''    bool is_bridge_path{ false }; //!< whether current config is used when bridging
    double fan_speed{ FAN_SPEED_DEFAULT }; //!< fan speed override for this path, value should be within range 0-100 (inclusive) and ignored otherwise''',
    '''    bool is_bridge_path{ false }; //!< whether current config is used when bridging
    bool is_arc_overhang{ false }; //!< EnderSlicer native Multiplex path
    bool is_wave_overhang{ false }; //!< EnderSlicer native wavefront path
    double fan_speed{ FAN_SPEED_DEFAULT }; //!< fan speed override for this path, value should be within range 0-100 (inclusive) and ignored otherwise''',
)

layer_plan_cpp = root / "src" / "LayerPlan.cpp"
replace(
    layer_plan_cpp,
    '''            const auto& extruder_changed = ! last_extrusion_config.has_value() || (last_extrusion_config.value().type != path.config.type);
            if (! path.config.isTravelPath() && extruder_changed)
            {
                gcode.writeTypeComment(path.config.type);
                if (path.config.isBridgePath())
                {
                    gcode.writeComment("BRIDGE");
                }
                last_extrusion_config = path.config;
                update_extrusion_offset = true;
            }''',
    '''            const bool feature_changed = ! last_extrusion_config.has_value()
                                         || last_extrusion_config.value().type != path.config.type
                                         || last_extrusion_config.value().is_arc_overhang != path.config.is_arc_overhang
                                         || last_extrusion_config.value().is_wave_overhang != path.config.is_wave_overhang;
            if (! path.config.isTravelPath() && feature_changed)
            {
                if (path.config.is_wave_overhang)
                {
                    gcode.writeComment("TYPE:WAVE-OVERHANG");
                }
                else if (path.config.is_arc_overhang)
                {
                    // App-owned semantic marker. Firmware ignores comments, while
                    // EnderSlicer's layer preview can classify these paths exactly.
                    gcode.writeComment("TYPE:ARC-OVERHANG");
                }
                else
                {
                    gcode.writeTypeComment(path.config.type);
                }
                if (path.config.isBridgePath())
                {
                    gcode.writeComment("BRIDGE");
                }
                last_extrusion_config = path.config;
                update_extrusion_offset = true;
            }''',
)

replace(
    cmake,
    "        src/Application.cpp\n",
    "        src/Application.cpp\n        src/ArcOverhang.cpp\n        src/WaveOverhang.cpp\n",
)

fff_gcode_writer_cpp = root / "src" / "FffGcodeWriter.cpp"
replace(
    fff_gcode_writer_cpp,
    '#include "Application.h"\n',
    '#include "Application.h"\n#include "ArcOverhang.h"\n#include "WaveOverhang.h"\n',
)
replace(
    fff_gcode_writer_cpp,
    '''void FffGcodeWriter::addMeshPartToGCode(
    const SliceDataStorage& storage,
    const SliceMeshStorage& mesh,
    const size_t extruder_nr,
    const MeshPathConfigs& mesh_config,
    SliceLayerPart& part,
    LayerPlan& gcode_layer) const
{
    const Settings& mesh_group_settings = Application::getInstance().current_slice_->scene.current_mesh_group->settings;
    const bool infill_before_walls = mesh.settings.get<bool>("infill_before_walls");
    bool added_something = false;

    const bool end_infill_close_to_seam
        = infill_before_walls && mesh.settings.get<InfillStartEndPreference>("infill_start_end_preference") == InfillStartEndPreference::END_CLOSE_TO_SEAM;

    // Pre-process the insets without actually adding them, so that we know where they are going to start printing
    InsetsPreprocessResult insets_preprocess_result = preProcessInsets(storage, gcode_layer, mesh, extruder_nr, mesh_config, part, end_infill_close_to_seam);
    bool infill_added = false;

    if (infill_before_walls)
    {
        std::optional<Point2LL> near_end_location;
        if (end_infill_close_to_seam && insets_preprocess_result.walls_optimizer)
        {
            near_end_location = insets_preprocess_result.walls_optimizer->getStartPosition();
        }

        infill_added = processInfill(storage, gcode_layer, mesh, extruder_nr, mesh_config, part, near_end_location);
        added_something = added_something | infill_added;
    }

    added_something |= endProcessInsets(insets_preprocess_result, storage, gcode_layer, mesh, extruder_nr, mesh_config, part, infill_added);''',
    '''static bool emitOverhangFillForSkinPart(
    const SliceDataStorage& storage,
    LayerPlan& gcode_layer,
    const SliceMeshStorage& mesh,
    const size_t layer_nr,
    const MeshPathConfigs& mesh_config,
    const SkinPart& skin_part,
    const GCodePathConfig& skin_config)
{
    // Emits wave/arc overhang fill for a skin part before the walls are added,
    // so a step-out wall prints onto the freshly laid fill instead of into the
    // void. Returns whether the fill was emitted; the caller clears the handled
    // island so the skin phase does not print it again.
    Shape supported;
    bridgeAngle(mesh, skin_part.skin_fill, storage, layer_nr, 1, nullptr, supported);
    if (supported.empty())
    {
        return false;
    }
    const bool wave_overhang_enabled = mesh.settings.get<bool>("enderslicer_wave_overhang_enabled");
    const bool arc_overhang_enabled = mesh.settings.get<bool>("enderslicer_arc_overhang_enabled") && ! wave_overhang_enabled;
    if (wave_overhang_enabled)
    {
        WaveOverhangParameters wave_parameters;
        wave_parameters.line_spacing = mesh.settings.get<coord_t>("enderslicer_wave_overhang_line_spacing");
        wave_parameters.perimeter_overlap = mesh.settings.get<coord_t>("enderslicer_wave_overhang_perimeter_overlap");
        wave_parameters.minimum_width = mesh.settings.get<coord_t>("enderslicer_wave_overhang_minimum_width");
        wave_parameters.max_iterations = mesh.settings.get<size_t>("enderslicer_wave_overhang_max_iterations");
        wave_parameters.pattern = mesh.settings.get<std::string>("enderslicer_wave_overhang_pattern");
        wave_parameters.reverse_direction = mesh.settings.get<bool>("enderslicer_wave_overhang_reverse_odd_layers") && layer_nr % 2 != 0;

        OpenLinesSet wave_lines;
        if (WaveOverhangGenerator::generate(
                skin_part.skin_fill,
                supported,
                wave_parameters,
                wave_lines))
        {
            GCodePathConfig wave_config = skin_config;
            wave_config.is_wave_overhang = true;
            wave_config.speed_derivatives.speed = mesh.settings.get<Velocity>("enderslicer_wave_overhang_speed");
            const double line_width_mm = std::max(INT2MM(skin_config.getLineWidth()), 0.001);
            const double layer_height_mm = std::max(INT2MM(mesh.settings.get<coord_t>("layer_height")), 0.001);
            const double nominal_mm3_per_mm = line_width_mm * layer_height_mm;
            const double wave_flow = mesh.settings.get<double>("enderslicer_wave_overhang_flow_mm3_per_mm") / nominal_mm3_per_mm;
            const double wave_fan_speed = mesh.settings.get<double>("enderslicer_wave_overhang_fan_speed");

            gcode_layer.setIsInside(true);
            for (const OpenPolyline& wave_line : wave_lines)
            {
                if (! wave_line.isValid())
                {
                    continue;
                }
                OpenLinesSet ordered_wave;
                ordered_wave.push_back(wave_line, CheckNonEmptyParam::OnlyIfValid);
                gcode_layer.addLinesByOptimizer(
                    ordered_wave,
                    wave_config,
                    SpaceFillType::PolyLines,
                    false,
                    0,
                    wave_flow,
                    std::nullopt,
                    wave_fan_speed);
            }
            return true;
        }
    }
    if (arc_overhang_enabled)
    {
        ArcOverhangParameters arc_parameters;
        arc_parameters.line_spacing = std::max<coord_t>(
            1,
            static_cast<coord_t>(std::llround(
                static_cast<double>(skin_config.getLineWidth())
                * mesh.settings.get<double>("enderslicer_arc_overhang_line_spacing")
                / 100.0)));
        arc_parameters.min_radius = mesh.settings.get<coord_t>("enderslicer_arc_overhang_min_radius");
        arc_parameters.max_radius = mesh.settings.get<coord_t>("enderslicer_arc_overhang_max_radius");
        arc_parameters.max_area_mm2 = mesh.settings.get<double>("enderslicer_arc_overhang_max_area");
        arc_parameters.resolution = mesh.settings.get<coord_t>("enderslicer_arc_overhang_resolution");

        OpenLinesSet arc_lines;
        if (ArcOverhangGenerator::generate(
                skin_part.skin_fill,
                supported,
                arc_parameters,
                arc_lines))
        {
            GCodePathConfig arc_config = skin_config;
            arc_config.is_arc_overhang = true;
            arc_config.speed_derivatives.speed = mesh.settings.get<Velocity>("enderslicer_arc_overhang_speed");
            const double arc_flow = mesh.settings.get<double>("enderslicer_arc_overhang_flow") / 100.0;
            const double arc_fan_speed = mesh.settings.get<double>("enderslicer_arc_overhang_fan_speed");
            constexpr coord_t arc_wipe_distance = 0;
            constexpr bool optimize_arc_travel = false;

            gcode_layer.setIsInside(true);
            // Preserve the generator's inner-to-outer order. Submitting one
            // clipped arc at a time prevents the generic path optimizer from
            // jumping to an unsupported outer radius first.
            for (const OpenPolyline& arc_line : arc_lines)
            {
                if (! arc_line.isValid())
                {
                    continue;
                }
                OpenLinesSet ordered_arc;
                ordered_arc.push_back(arc_line, CheckNonEmptyParam::OnlyIfValid);
                gcode_layer.addLinesByOptimizer(
                    ordered_arc,
                    arc_config,
                    SpaceFillType::PolyLines,
                    optimize_arc_travel,
                    arc_wipe_distance,
                    arc_flow,
                    std::nullopt,
                    arc_fan_speed);
            }
            return true;
        }
    }
    return false;
}

void FffGcodeWriter::addMeshPartToGCode(
    const SliceDataStorage& storage,
    const SliceMeshStorage& mesh,
    const size_t extruder_nr,
    const MeshPathConfigs& mesh_config,
    SliceLayerPart& part,
    LayerPlan& gcode_layer) const
{
    const Settings& mesh_group_settings = Application::getInstance().current_slice_->scene.current_mesh_group->settings;
    const bool infill_before_walls = mesh.settings.get<bool>("infill_before_walls");
    bool added_something = false;

    const bool end_infill_close_to_seam
        = infill_before_walls && mesh.settings.get<InfillStartEndPreference>("infill_start_end_preference") == InfillStartEndPreference::END_CLOSE_TO_SEAM;

    // Pre-process the insets without actually adding them, so that we know where they are going to start printing
    InsetsPreprocessResult insets_preprocess_result = preProcessInsets(storage, gcode_layer, mesh, extruder_nr, mesh_config, part, end_infill_close_to_seam);
    bool infill_added = false;

    // EnderSlicer: emit overhang fill before the walls. A step-out wall must
    // print onto the freshly laid overhang floor instead of into the void.
    // Handled skin parts are emptied so the later skin phase skips them.
    const size_t layer_nr = gcode_layer.getLayerNr();
    if (layer_nr > 0 && (mesh.settings.get<bool>("enderslicer_wave_overhang_enabled") || mesh.settings.get<bool>("enderslicer_arc_overhang_enabled") || mesh.settings.get<bool>("enderslicer_brick_wall_enabled") ))
    {
        for (SkinPart& skin_part : part.skin_parts)
        {
            if (skin_part.skin_fill.empty())
            {
                continue;
            }
            if (emitOverhangFillForSkinPart(storage, gcode_layer, mesh, layer_nr, mesh_config, skin_part, mesh_config.skin_config))
            {
                skin_part.skin_fill = Shape();
                added_something = true;
            }
        }
    }

    if (infill_before_walls)
    {
        std::optional<Point2LL> near_end_location;
        if (end_infill_close_to_seam && insets_preprocess_result.walls_optimizer)
        {
            near_end_location = insets_preprocess_result.walls_optimizer->getStartPosition();
        }

        infill_added = processInfill(storage, gcode_layer, mesh, extruder_nr, mesh_config, part, near_end_location);
        added_something = added_something | infill_added;
    }

    added_something |= endProcessInsets(insets_preprocess_result, storage, gcode_layer, mesh, extruder_nr, mesh_config, part, infill_added);''',
)

# Install the native brick-wall overhang source and register it with CuraEngine.
(root / "include" / "BrickWalls.h").write_text((arc_patch_root / "include" / "BrickWalls.h").read_text())
(root / "src" / "BrickWalls.cpp").write_text((arc_patch_root / "src" / "BrickWalls.cpp").read_text())

replace(
    cmake,
    "        src/Application.cpp\n        src/ArcOverhang.cpp\n        src/WaveOverhang.cpp\n",
    "        src/Application.cpp\n        src/ArcOverhang.cpp\n        src/WaveOverhang.cpp\n        src/BrickWalls.cpp\n",
)

# Brick-wall paths carry their own preview marker so EnderSlicer's layer
# preview can classify the staircase courses exactly.
replace(
    gcode_path_config_h,
    '''    bool is_bridge_path{ false }; //!< whether current config is used when bridging
    bool is_arc_overhang{ false }; //!< EnderSlicer native Multiplex path
    bool is_wave_overhang{ false }; //!< EnderSlicer native wavefront path''',
    '''    bool is_bridge_path{ false }; //!< whether current config is used when bridging
    bool is_arc_overhang{ false }; //!< EnderSlicer native Multiplex path
    bool is_wave_overhang{ false }; //!< EnderSlicer native wavefront path
    bool is_brick_wall{ false }; //!< EnderSlicer brick-wall staircase path''',
)

replace(
    layer_plan_cpp,
    '''            const bool feature_changed = ! last_extrusion_config.has_value()
                                         || last_extrusion_config.value().type != path.config.type
                                         || last_extrusion_config.value().is_arc_overhang != path.config.is_arc_overhang
                                         || last_extrusion_config.value().is_wave_overhang != path.config.is_wave_overhang;''',
    '''            const bool feature_changed = ! last_extrusion_config.has_value()
                                         || last_extrusion_config.value().type != path.config.type
                                         || last_extrusion_config.value().is_arc_overhang != path.config.is_arc_overhang
                                         || last_extrusion_config.value().is_wave_overhang != path.config.is_wave_overhang
                                         || last_extrusion_config.value().is_brick_wall != path.config.is_brick_wall;''',
)
replace(
    layer_plan_cpp,
    '''                if (path.config.is_wave_overhang)
                {
                    gcode.writeComment("TYPE:WAVE-OVERHANG");
                }
                else if (path.config.is_arc_overhang)''',
    '''                if (path.config.is_wave_overhang)
                {
                    gcode.writeComment("TYPE:WAVE-OVERHANG");
                }
                else if (path.config.is_brick_wall)
                {
                    // App-owned semantic marker for the brick-wall staircase
                    // courses, classified exactly in EnderSlicer's layer preview.
                    gcode.writeComment("TYPE:BRICK-WALL");
                }
                else if (path.config.is_arc_overhang)''',
)

replace(
    fff_gcode_writer_cpp,
    '#include "Application.h"\n#include "ArcOverhang.h"\n#include "WaveOverhang.h"\n',
    '#include "Application.h"\n#include "ArcOverhang.h"\n#include "BrickWalls.h"\n#include "WaveOverhang.h"\n',
)

# Brick-wall staircase courses print before the walls so a wall that would
# otherwise print into the void rests on freshly laid courses. Only regions
# the generator can actually cover get courses; everything else keeps the
# normal wall path.
replace(
    fff_gcode_writer_cpp,
    '''            if (emitOverhangFillForSkinPart(storage, gcode_layer, mesh, layer_nr, mesh_config, skin_part, mesh_config.skin_config))
            {
                skin_part.skin_fill = Shape();
                added_something = true;
            }
        }
    }

    if (infill_before_walls)''',
    '''            if (emitOverhangFillForSkinPart(storage, gcode_layer, mesh, layer_nr, mesh_config, skin_part, mesh_config.skin_config))
            {
                skin_part.skin_fill = Shape();
                added_something = true;
            }
        }
    }

    const bool masonry_walls_enabled = mesh.settings.get<bool>("enderslicer_masonry_walls_enabled") ;
    const bool brick_walls_enabled = mesh.settings.get<bool>("enderslicer_brick_wall_enabled") && ! masonry_walls_enabled;

    if (masonry_walls_enabled)
    {
        // Masonry-bonded walls: the whole wall stack leans alternately +/- half
        // a bead per layer, so every bead rests on the shoulder of the bead
        // beneath instead of stacking flat (Lego style). Cura's wall emission
        // is replaced for the layer; layer 0 prints straight.
        insets_preprocess_result.walls_optimizer.reset();
        part.wall_toolpaths.clear();

        BeadAngleParameters wall_parameters;
        wall_parameters.line_width = mesh_config.inset0_config.getLineWidth();
        wall_parameters.base_wall_count = std::max<size_t>(mesh.settings.get<size_t>("wall_line_count"), 1);
        const coord_t lean = (layer_nr % 2 == 0) ? wall_parameters.line_width / 2 : -wall_parameters.line_width / 2;

        OpenLinesSet masonry_walls;
        BeadAngleGenerator::generateMasonryWalls(part.outline, wall_parameters, layer_nr > 0 ? lean : 0, masonry_walls);

        gcode_layer.setIsInside(true);
        for (OpenPolyline& wall : masonry_walls.getLines())
        {
            if (! wall.isValid())
            {
                continue;
            }
            OpenLinesSet ordered_wall;
            ordered_wall.push_back(std::move(wall), CheckNonEmptyParam::OnlyIfValid);
            gcode_layer.addLinesByOptimizer(
                ordered_wall,
                mesh_config.inset0_config,
                SpaceFillType::PolyLines,
                false,
                0,
                1.0,
                std::nullopt,
                mesh_config.inset0_config.fan_speed);
        }
        added_something = true;
    }


    if (layer_nr > 0 && brick_walls_enabled)
    {
        Shape supported;
        bridgeAngle(mesh, part.outline, storage, layer_nr, 1, nullptr, supported);
        if (! supported.empty())
        {
            BrickWallsParameters brick_parameters;
            brick_parameters.brick_width = mesh_config.inset0_config.getLineWidth();
            brick_parameters.max_iterations = mesh.settings.get<size_t>("enderslicer_brick_wall_max_iterations");
            brick_parameters.layer_height = mesh.settings.get<coord_t>("layer_height");
            brick_parameters.stagger_odd_layers = (layer_nr % 2) == 1;
            brick_parameters.brick_length = mesh.settings.get<coord_t>("enderslicer_brick_wall_brick_length");

            OpenLinesSet brick_courses;
            if (BrickWallsGenerator::generate(part.outline, supported, brick_parameters, brick_courses))
            {
                GCodePathConfig brick_config = mesh_config.inset0_config;
                brick_config.is_brick_wall = true;
                brick_config.speed_derivatives.speed = mesh.settings.get<Velocity>("enderslicer_brick_wall_speed");
                brick_config.fan_speed = mesh.settings.get<double>("enderslicer_brick_wall_fan_speed");
                const double brick_flow = mesh.settings.get<double>("enderslicer_brick_wall_flow") / 100.0;

                gcode_layer.setIsInside(true);
                for (OpenPolyline& course : brick_courses.getLines())
                {
                    if (! course.isValid())
                    {
                        continue;
                    }
                    OpenLinesSet ordered_course;
                    ordered_course.push_back(std::move(course), CheckNonEmptyParam::OnlyIfValid);
                    gcode_layer.addLinesByOptimizer(
                        ordered_course,
                        brick_config,
                        SpaceFillType::PolyLines,
                        false,
                        0,
                        brick_flow,
                        std::nullopt,
                        brick_config.fan_speed);
                }
                added_something = true;
            }
        }
    }

    if (infill_before_walls)''',
)

# Bead-angle overhang generator (kept in its own module; idempotent).
import _apply_bead_angle_patch
_apply_bead_angle_patch.apply(root, arc_patch_root, replace)
