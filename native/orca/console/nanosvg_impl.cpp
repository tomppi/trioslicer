// NanoSVG's implementation is compiled by the GUI (slic3r/GUI/BitmapCache.cpp defines
// NANOSVG_IMPLEMENTATION), while libslic3r's Format/svg.cpp and NSVGUtils.cpp call into it -
// a GUI-free console therefore links with nsvgDelete undefined. Compile the parser here.
//
// The rasteriser is deliberately not included: nothing under libslic3r calls it, and the
// console has no use for a bitmap rasteriser.
#define NANOSVG_IMPLEMENTATION
#include "nanosvg/nanosvg.h"
