/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright 2012 Blender Foundation */

/** \file
 * \ingroup intern_locale
 */

#include <boost/locale.hpp>
#include <iostream>
#include <stdio.h>

#include "boost_locale_wrapper.h"

static std::string messages_path;
static std::string default_domain;
static std::string locale_str;

/* NOTE: We cannot use short stuff like `boost::locale::gettext`, because those return
 * `std::basic_string` objects, which c_ptr()-returned char* is no more valid
 * once deleted (which happens as soons they are out of scope of this func). */
typedef boost::locale::message_format<char> char_message_facet;
static std::locale locale_global;
static char_message_facet const *facet_global = NULL;

static void bl_locale_global_cache()
{
  /* Cache facet in global variable. Not only is it better for performance,
   * it also fixes crashes on macOS when doing translation from threads other
   * than main. Likely because of some internal thread local variables. */
  try {
    /* facet_global reference is valid as long as local_global exists,
     * so we store both. */
    locale_global = std::locale();
    facet_global = &std::use_facet<char_message_facet>(locale_global);
  }
  /* `if std::has_facet<char_message_facet>(l) == false`, LC_ALL = "C" case. */
  catch (const std::bad_cast &e) {
#ifndef NDEBUG
    std::cout << "bl_locale_global_cache:" << e.what() << " \n";
#endif
    (void)e;
    facet_global = NULL;
  }
  catch (const std::exception &e) {
#ifndef NDEBUG
    std::cout << "bl_locale_global_cache:" << e.what() << " \n";
#endif
    (void)e;
    facet_global = NULL;
  }
}

void bl_locale_init(const char *_messages_path, const char *_default_domain)
{
  /* Avoid using ICU backend, we do not need its power and it's rather heavy! */
  boost::locale::localization_backend_manager lman =
      boost::locale::localization_backend_manager::global();
#if defined(_WIN32)
  lman.select("winapi");
#else
  lman.select("posix");
#endif
  boost::locale::localization_backend_manager::global(lman);

  messages_path = _messages_path;
  default_domain = _default_domain;
}

void bl_locale_set(const char *locale)
{
  /* Pin the generator to the backend bl_locale_init() asks for, rather than
   * letting it take localization_backend_manager::global().  A default-built
   * generator falls back to ICU whenever bl_locale_init() has not run first,
   * and this engine ships no ICU data: libicuc.so is 6 MB (data-less) and
   * Android's own icudt76l.dat belongs to a different ICU version.  Creating a
   * collator then throws std::runtime_error out of an uncaught path and aborts
   * the whole process - which is what killed the first GPU render. */
  boost::locale::localization_backend_manager lman =
      boost::locale::localization_backend_manager::global();
#if defined(_WIN32)
  lman.select("winapi");
#else
  lman.select("posix");
#endif
  boost::locale::generator gen(lman);
  std::locale _locale;
  /* Specify location of dictionaries. */
  gen.add_messages_path(messages_path);
  gen.add_messages_domain(default_domain);
  // gen.set_default_messages_domain(default_domain);

  try {
    if (locale && locale[0]) {
      _locale = gen(locale);
    }
    else {
#if defined(__APPLE__) && !defined(WITH_HEADLESS) && !defined(WITH_GHOST_SDL)
      std::string locale_osx = osx_user_locale() + std::string(".UTF-8");
      _locale = gen(locale_osx.c_str());
#else
      _locale = gen("");
#endif
    }
    std::locale::global(_locale);
    /* NOTE: boost always uses "C" LC_NUMERIC by default! */

    bl_locale_global_cache();

    /* Generate the locale string
     * (useful to know which locale we are actually using in case of "default" one). */
#define LOCALE_INFO std::use_facet<boost::locale::info>(_locale)

    locale_str = LOCALE_INFO.language();
    if (LOCALE_INFO.country() != "") {
      locale_str += "_" + LOCALE_INFO.country();
    }
    if (LOCALE_INFO.variant() != "") {
      locale_str += "@" + LOCALE_INFO.variant();
    }

#undef LOCALE_INFO
  }
  /* Extra catch on `std::runtime_error` is needed for macOS/Clang as it seems that exceptions
   * like `boost::locale::conv::conversion_error` (which inherit from `std::runtime_error`) are
   * not caught by their ancestor `std::exception`. See #88877#1177108 */
  catch (std::runtime_error const &e) {
    std::cout << "bl_locale_set(" << locale << "): " << e.what() << " \n";
  }
  catch (std::exception const &e) {
    std::cout << "bl_locale_set(" << locale << "): " << e.what() << " \n";
  }
}

const char *bl_locale_get(void)
{
  return locale_str.c_str();
}

const char *bl_locale_pgettext(const char *msgctxt, const char *msgid)
{
  if (facet_global) {
    char const *r = facet_global->get(0, msgctxt, msgid);
    if (r) {
      return r;
    }
  }

  return msgid;
}
