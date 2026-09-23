// Headless OrcaSlicer console for the EnderSlicer Android app.
//
// Upstream's orca-slicer executable cannot be built without the GUI: OrcaSlicer.cpp
// includes wxWidgets, GLFW, OpenGL and the GUI library unconditionally, and the only
// console entry point (the --slice action) lives in that same translation unit and
// uses Slic3r::GUI::PartPlateList. This driver instead links libslic3r alone - the same
// shape as PrusaSlicer's prusa-slicer-console - and slices through the library API:
//
//     PresetBundle::load_presets -> select printer/print/filament
//     -> Print::apply -> Print::process -> Print::export_gcode
//
// Usage:
//   orca-console --datadir DIR [--printer-preset NAME] [--print-preset NAME]
//                [--filament-preset NAME] [--printer-config FILE] [--print-config FILE]
//                [--filament-config FILE] [-o FILE | --outputdir DIR] [--info] model.stl
//
// Progress is written to stdout as "NN => stage", the shape the app's runner parses.
// Exit codes: 0 success, 2 usage, 3 profile loading, 4 apply, 5 slice/export.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

#include <boost/filesystem.hpp>

#include "libslic3r/AppConfig.hpp"
#include "libslic3r/Config.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Utils.hpp"
#include "libslic3r_version.h"

namespace fs = boost::filesystem;
using namespace Slic3r;

namespace {

struct Options {
    std::string datadir;
    std::string outputdir;
    std::string output;
    std::string printer_preset;
    std::string print_preset;
    std::string filament_preset;
    std::vector<std::string> printer_configs;
    std::vector<std::string> print_configs;
    std::vector<std::string> filament_configs;
    std::vector<std::string> models;
    bool info = false;
    bool dump_settings = false;
    bool list_presets = false;
};

void usage()
{
    std::printf(
        "orca-console " SoftFever_VERSION " - headless OrcaSlicer slicing engine\n"
        "Usage: orca-console --datadir DIR [options] model.stl\n"
        "\n"
        "  --datadir DIR            resources directory holding profiles/ (required)\n"
        "  --printer-preset NAME    printer preset to select from the bundled profiles\n"
        "  --print-preset NAME      print (process) preset to select\n"
        "  --filament-preset NAME   filament preset to select\n"
        "  --printer-config FILE    ini overrides applied over the printer preset\n"
        "  --print-config FILE      ini overrides applied over the print preset\n"
        "  --filament-config FILE   ini overrides applied over the filament preset\n"
        "  -o, --output FILE        G-code path (default: <outputdir>/output.gcode)\n"
        "  --outputdir DIR          directory for the default G-code path\n"
        "  --info                   print engine and resource information, then exit\n"
        "  --dump-settings          write every print option as JSON to stdout, then exit\n"
        "  --list-presets           write the loaded preset names to stdout, then exit\n"
        "  -h, --help               this text\n");
}

// "NN => stage" is the progress shape the Android runner already parses.
void report_status(const PrintBase::SlicingStatus &status)
{
    std::printf("%d => %s\n", status.percent, status.text.c_str());
    std::fflush(stdout);
}

bool parse_args(int argc, char **argv, Options &opt)
{
    auto value = [&](int &i, const char *name) -> std::string {
        if (i + 1 >= argc) {
            std::fprintf(stderr, "orca-console: missing value for %s\n", name);
            std::exit(2);
        }
        return argv[++i];
    };

    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        if (arg == "--datadir")                opt.datadir = value(i, "--datadir");
        else if (arg == "--outputdir")         opt.outputdir = value(i, "--outputdir");
        else if (arg == "-o" || arg == "--output") opt.output = value(i, "--output");
        else if (arg == "--printer-preset")    opt.printer_preset = value(i, "--printer-preset");
        else if (arg == "--print-preset")      opt.print_preset = value(i, "--print-preset");
        else if (arg == "--filament-preset")   opt.filament_preset = value(i, "--filament-preset");
        else if (arg == "--printer-config")    opt.printer_configs.push_back(value(i, "--printer-config"));
        else if (arg == "--print-config")      opt.print_configs.push_back(value(i, "--print-config"));
        else if (arg == "--filament-config")   opt.filament_configs.push_back(value(i, "--filament-config"));
        else if (arg == "--info")              opt.info = true;
        else if (arg == "--dump-settings")     opt.dump_settings = true;
        else if (arg == "--list-presets")      opt.list_presets = true;
        else if (arg == "-h" || arg == "--help") { usage(); std::exit(0); }
        else if (arg == "--version")           { std::printf("orca-console %s\n", SoftFever_VERSION); std::exit(0); }
        else if (!arg.empty() && arg[0] == '-') {
            std::fprintf(stderr, "orca-console: unknown option %s\n", arg.c_str());
            usage();
            return false;
        }
        else opt.models.push_back(arg);
    }
    return true;
}

// Loads an ini file and layers it over an edited preset. Unknown keys are ignored so a
// setting the app knows about but this engine version dropped cannot fail the slice.
bool apply_config_files(PresetCollection &collection, const std::vector<std::string> &files, const char *label)
{
    for (const std::string &file : files) {
        DynamicPrintConfig overrides;
        try {
            // Orca reads both ini and json here; the app writes ini because it is flat
            // key=value with no preset metadata to invent.
            overrides.load_from_ini(file, ForwardCompatibilitySubstitutionRule::Enable);
        } catch (const std::exception &error) {
            std::fprintf(stderr, "orca-console: cannot read %s config %s: %s\n", label, file.c_str(), error.what());
            return false;
        }
        collection.get_edited_preset().config.apply(overrides, true);
    }
    return true;
}

// Center of the printable area, so a model exported around the origin still lands on the
// bed the way the app's other engines place it (they arrange/center too).
bool printable_area_center(const DynamicPrintConfig &config, Vec2d &center)
{
    if (!config.has("printable_area"))
        return false;
    const ConfigOptionPoints *area = config.option<ConfigOptionPoints>("printable_area");
    if (area == nullptr || area->values.empty())
        return false;

    const std::vector<Vec2d> &points = area->values;
    double min_x = points.front().x(), max_x = min_x;
    double min_y = points.front().y(), max_y = min_y;
    for (const Vec2d &point : points) {
        min_x = std::min(min_x, point.x());
        max_x = std::max(max_x, point.x());
        min_y = std::min(min_y, point.y());
        max_y = std::max(max_y, point.y());
    }
    center = Vec2d(0.5 * (min_x + max_x), 0.5 * (min_y + max_y));
    return true;
}

const char *option_type_name(ConfigOptionType type)
{
    switch (type) {
    case coFloat:             return "float";
    case coFloats:            return "floats";
    case coInt:               return "int";
    case coInts:              return "ints";
    case coString:            return "string";
    case coStrings:           return "strings";
    case coPercent:           return "percent";
    case coPercents:          return "percents";
    case coFloatOrPercent:    return "float_or_percent";
    case coFloatsOrPercents:  return "floats_or_percents";
    case coPoint:             return "point";
    case coPoints:            return "points";
    case coPoint3:            return "point3";
    case coBool:              return "bool";
    case coBools:             return "bools";
    case coEnum:              return "enum";
    case coEnums:             return "enums";
    default:                  return "other";
    }
}

const char *option_mode_name(ConfigOptionMode mode)
{
    switch (mode) {
    case comSimple:   return "simple";
    case comAdvanced: return "advanced";
    case comExpert:   return "expert";
    case comDevelop:  return "develop";
    }
    return "simple";
}

std::string json_escape(const std::string &value)
{
    std::string escaped;
    escaped.reserve(value.size() + 8);
    for (const char character : value) {
        switch (character) {
        case '"':  escaped += "\\\""; break;
        case '\\': escaped += "\\\\"; break;
        case '\n': escaped += "\\n"; break;
        case '\r': escaped += "\\r"; break;
        case '\t': escaped += "\\t"; break;
        default:
            if (static_cast<unsigned char>(character) < 0x20)
                escaped += ' ';
            else
                escaped += character;
        }
    }
    return escaped;
}

// The catalogue the app's all-settings sheet browses. It is generated by the engine itself,
// so the keys it offers cannot drift from the options this build actually accepts - the
// Prusa path's hand-generated catalog could, and did.
void dump_settings()
{
    std::printf("{\n  \"settings\": [");
    bool first = true;
    for (const auto &entry : print_config_def.options) {
        const ConfigOptionDef &definition = entry.second;
        // SLA options are not slice settings for a filament printer, and a readonly option
        // is not something a user can be offered.
        if (definition.printer_technology == ptSLA || definition.readonly)
            continue;

        std::printf("%s\n    {\"key\": \"%s\", \"type\": \"%s\", \"mode\": \"%s\", \"category\": \"%s\", \"default\": \"%s\", \"desc\": \"%s\"",
                    first ? "" : ",", json_escape(entry.first).c_str(), option_type_name(definition.type),
                    option_mode_name(definition.mode), json_escape(definition.category).c_str(),
                    json_escape(definition.default_value ? definition.default_value->serialize() : std::string()).c_str(),
                    json_escape(definition.tooltip).c_str());
        if (!definition.enum_values.empty()) {
            std::printf(", \"values\": [");
            for (size_t index = 0; index < definition.enum_values.size(); ++index)
                std::printf("%s\"%s\"", index == 0 ? "" : ", ", json_escape(definition.enum_values[index]).c_str());
            std::printf("]");
        }
        if (definition.min > -FLT_MAX || definition.max < FLT_MAX)
            std::printf(", \"min\": %g, \"max\": %g", definition.min, definition.max);
        std::printf(" }");
        first = false;
    }
    std::printf("\n  ]\n}\n");
}

// Preset inventory, for diagnosing a catalogue that loaded empty or a name that does not
// resolve - the app shows the same names in its engine pickers.
void list_presets(const PresetBundle &bundle)
{
    std::printf("vendors: %zu\n", bundle.vendors.size());
    auto dump = [](const char *label, const PresetCollection &collection) {
        const std::deque<Preset> &presets = collection.get_presets();
        std::printf("%s: %zu\n", label, presets.size());
        for (const Preset &preset : presets)
            std::printf("  %s%s\n", preset.name.c_str(), preset.is_visible ? "" : "  [hidden]");
    };
    dump("printers", bundle.printers);
    dump("prints", bundle.prints);
    dump("filaments", bundle.filaments);
}

} // namespace

int main(int argc, char **argv)
{
    std::setvbuf(stdout, nullptr, _IOLBF, 0);

    Options opt;
    if (!parse_args(argc, argv, opt))
        return 2;

    // The catalogue needs no resources: it describes the engine's own option definitions.
    if (opt.dump_settings) {
        dump_settings();
        return 0;
    }

    if (opt.datadir.empty()) {
        std::fprintf(stderr, "orca-console: --datadir is required\n");
        return 2;
    }
    const fs::path datadir = fs::absolute(opt.datadir);
    if (!fs::is_directory(datadir / "profiles")) {
        std::fprintf(stderr, "orca-console: %s does not contain profiles/\n", datadir.string().c_str());
        return 3;
    }

    // libslic3r resolves bundled vendor profiles under resources_dir()/profiles and keeps
    // user presets under data_dir()/user; the app passes one writable directory for both.
    set_resources_dir(datadir.string());
    set_data_dir(datadir.string());
    set_var_dir((datadir / "images").string());
    set_local_dir((datadir / "i18n").string());
    set_logging_level(2);

    if (opt.info) {
        std::printf("orca-console %s\nresources: %s\n", SoftFever_VERSION, datadir.string().c_str());
        return 0;
    }
    // The vendor bundles ship under <datadir>/profiles, which is where resources_dir() points
    // for the catalogue and the per-printer bed assets. load_system_presets_from_json() reads
    // the *installed* system presets from <datadir>/system instead, and an empty one there is
    // why every preset lookup fell back to "Default Printer". Linking the two keeps a single
    // copy of the profile tree on the device.
    const fs::path system_dir = datadir / "system";
    const bool system_dir_is_empty = fs::is_directory(system_dir) && fs::is_empty(system_dir);
    if (!fs::exists(system_dir) || system_dir_is_empty) {
        boost::system::error_code link_error;
        if (system_dir_is_empty)
            fs::remove(system_dir, link_error);
        fs::create_symlink("profiles", system_dir, link_error);
        if (link_error) {
            std::fprintf(stderr, "orca-console: cannot link %s to profiles: %s\n",
                         system_dir.string().c_str(), link_error.message().c_str());
            return 3;
        }
    }

    PresetBundle bundle;
    bundle.set_default_suppressed(true);
    AppConfig app_config;
    app_config.set("preset_folder", "default");
    try {
        // The GUI runs this at startup: it creates <datadir>/system and <datadir>/user, which
        // load_presets() then iterates over. The upstream CLI never loads the preset catalogue,
        // so a console that does has to create them itself.
        bundle.setup_directories();
        bundle.load_presets(app_config, ForwardCompatibilitySubstitutionRule::Enable);
    } catch (const std::exception &error) {
        std::fprintf(stderr, "orca-console: cannot load the bundled profiles: %s\n", error.what());
        return 3;
    }

    // Resolved by index rather than through select_preset_by_name(): that one falls back to
    // the first visible preset when the name is unknown and still answers true, which sliced
    // with "Default Printer" while the requested name quietly went unused. The strict variant
    // is protected, so the lookup is done here.
    auto select = [](PresetCollection &collection, const std::string &name, const char *label) -> bool {
        if (name.empty())
            return true;
        const std::deque<Preset> &presets = collection.get_presets();
        for (size_t index = 0; index < presets.size(); ++index) {
            // Visibility is deliberately not checked: the vendor loader leaves every preset
            // hidden until a printer has been selected, and the app names its choices.
            if (presets[index].name == name) {
                collection.select_preset(index);
                return true;
            }
        }
        std::fprintf(stderr, "orca-console: unknown %s preset \"%s\"\n", label, name.c_str());
        return false;
    };
    if (!select(bundle.printers, opt.printer_preset, "printer"))
        return 3;
    // Choosing a printer is what makes its compatible print and filament presets visible, and
    // it is also where the two defaults come from: the machine profile names the process and
    // filament the desktop app preselects with it.
    bundle.update_compatible(PresetSelectCompatibleType::Never);

    auto printer_default = [&bundle](const char *key) -> std::string {
        const DynamicPrintConfig &config = bundle.printers.get_edited_preset().config;
        if (!config.has(key))
            return {};
        const ConfigOptionStrings *values = config.option<ConfigOptionStrings>(key);
        return (values != nullptr && !values->values.empty()) ? values->values.front() : std::string();
    };
    const std::string print_preset =
        opt.print_preset.empty() ? printer_default("default_print_profile") : opt.print_preset;
    const std::string filament_preset =
        opt.filament_preset.empty() ? printer_default("default_filament_profile") : opt.filament_preset;

    if (!select(bundle.prints, print_preset, "print") ||
        !select(bundle.filaments, filament_preset, "filament"))
        return 3;

    if (opt.list_presets) {
        list_presets(bundle);
        return 0;
    }
    if (opt.models.empty()) {
        std::fprintf(stderr, "orca-console: no model file given\n");
        usage();
        return 2;
    }
    if (opt.models.size() > 1) {
        // The app slices one model per request; refuse rather than silently dropping input.
        std::fprintf(stderr, "orca-console: exactly one model file is supported\n");
        return 2;
    }

    if (!apply_config_files(bundle.printers, opt.printer_configs, "printer") ||
        !apply_config_files(bundle.filaments, opt.filament_configs, "filament") ||
        !apply_config_files(bundle.prints, opt.print_configs, "print"))
        return 3;

    // Which presets actually took effect, on stderr so it lands in the app's engine log next
    // to the progress lines. A silently unselected preset is otherwise only visible as a
    // default bed size in the exported G-code.
    std::fprintf(stderr, "orca-console: printer=\"%s\" print=\"%s\" filament=\"%s\"\n",
                 bundle.printers.get_edited_preset().name.c_str(),
                 bundle.prints.get_edited_preset().name.c_str(),
                 bundle.filaments.get_edited_preset().name.c_str());

    DynamicPrintConfig config;
    try {
        config = bundle.full_config();
    } catch (const std::exception &error) {
        std::fprintf(stderr, "orca-console: the printer/print/filament combination is incomplete: %s\n", error.what());
        return 3;
    }

    Model model;
    try {
        ConfigSubstitutionContext substitutions(ForwardCompatibilitySubstitutionRule::Enable);
        model = Model::read_from_file(opt.models.front(), nullptr, &substitutions,
                                      LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances);
    } catch (const std::exception &error) {
        std::fprintf(stderr, "orca-console: cannot read %s: %s\n", opt.models.front().c_str(), error.what());
        return 4;
    }
    if (model.objects.empty()) {
        std::fprintf(stderr, "orca-console: %s contains no printable object\n", opt.models.front().c_str());
        return 4;
    }

    Vec2d bed_center;
    if (printable_area_center(config, bed_center))
        model.center_instances_around_point(bed_center);

    Print print;
    print.set_status_callback(report_status);
    // "Is this a Bambu Lab printer?" decides which Bambu-only envelope the engine writes into
    // the G-code - the spaghetti-detector M981 pair, the M624/M625 handling and more. Upstream
    // sets it from the selected vendor (the CLI at OrcaSlicer.cpp:6059, the GUI from its preset
    // bundle), and libslic3r never initialises the member, so leaving it unset is undefined
    // behaviour: a Creality slice took the Bambu path and emitted commands its firmware has
    // never heard of, which the app's G-code safety gate then rightly refused.
    print.is_BBL_printer() = bundle.is_bbl_vendor();
    // apply() classifies the change (unchanged / changed / invalidated) rather than
    // reporting success; a configuration the engine refuses arrives as an exception.
    try {
        print.apply(model, std::move(config));
    } catch (const std::exception &error) {
        std::fprintf(stderr, "orca-console: the sliced configuration was rejected by the engine: %s\n", error.what());
        return 4;
    }

    std::string output = opt.output;
    if (output.empty()) {
        const fs::path directory = opt.outputdir.empty() ? fs::current_path() : fs::path(opt.outputdir);
        fs::create_directories(directory);
        output = (directory / "output.gcode").string();
    }

    try {
        print.process();
        const std::string written = print.export_gcode(output, nullptr, nullptr);
        std::printf("100 => G-code written to %s\n", written.c_str());
    } catch (const std::exception &error) {
        std::fprintf(stderr, "orca-console: slicing failed: %s\n", error.what());
        return 5;
    }
    return 0;
}
